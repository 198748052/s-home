// 天气 App 云端配置服务（Go 单二进制 + 网页管理）。
//
// 设计目标：装完只要暴露一个端口，其余全在网页里配。
//   - 首次打开 /admin：设置管理员口令
//   - 之后 /admin：改 Key、换语音厂商、改端口、改对外域名、轮换 App 令牌、测试合成
//   - App 用 GET /api/config + X-Config-Token 拉配置（ETag/304）
//
// 编译：go build -trimpath -ldflags "-s -w" -o tianqi-config .
// 运行：./tianqi-config -config /root/data/tianqi/config.json -data /root/data/tianqi
package main

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

//go:embed admin.html
var adminHTML []byte

//go:embed config.default.json
var defaultConfigJSON []byte

const (
	ttsTimeout    = 30 * time.Second
	defaultPort   = 8787
	pbkdf2Rounds  = 120000
	sessionTTL    = 7 * 24 * time.Hour
	sessionCookie = "tianqi_session"
	mimoModel = "mimo-v2.5-tts"
	mimoVoice = "冰糖"
	mimoStyle = "请用清晰自然、语速适中的播报语气朗读，像天气预报播音员一样。"
)

// mimoEndpoint 语音合成地址（写死，测试里可以替换）
var mimoEndpoint = "https://api.xiaomimimo.com/v1/chat/completions"

// ---------- 服务设置（server.json，可在网页里改） ----------

type settings struct {
	Port          int    `json:"port"`
	PublicBaseURL string `json:"publicBaseUrl"`
	AdminUser     string `json:"adminUser"`
	AdminSalt     string `json:"adminSalt"`
	AdminHash     string `json:"adminHash"`
	AppToken      string `json:"appToken"`
	SessionSecret string `json:"sessionSecret"`
}

func (s *settings) passwordSet() bool {
	return s.AdminHash != "" && s.AdminSalt != ""
}

func defaultSettings(port int) *settings {
	return &settings{
		Port:          port,
		AdminUser:     "admin",
		AppToken:      randomHex(24),
		SessionSecret: randomHex(32),
	}
}

func loadSettings(path string, port int) (*settings, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			return nil, err
		}
		s := defaultSettings(port)
		if err := saveSettings(path, s); err != nil {
			return nil, err
		}
		log.Printf("created %s (port=%d, 首次打开 /admin 设置管理员口令)", path, s.Port)
		return s, nil
	}
	var s settings
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, fmt.Errorf("server.json 不是合法 JSON：%w", err)
	}
	if s.Port <= 0 {
		s.Port = port
	}
	if s.AdminUser == "" {
		s.AdminUser = "admin"
	}
	if s.AppToken == "" {
		s.AppToken = randomHex(24)
	}
	if s.SessionSecret == "" {
		s.SessionSecret = randomHex(32)
	}
	return &s, nil
}

func saveSettings(path string, s *settings) error {
	payload, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	payload = append(payload, '\n')
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".server-*.tmp")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := tmp.Write(payload); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(name, 0o600); err != nil {
		return err
	}
	return os.Rename(name, path)
}

// ---------- 口令与会话 ----------

func randomHex(n int) string {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(buf)
}

// pbkdf2SHA256 手写一份，省得引第三方库。
func pbkdf2SHA256(password, salt string, rounds, keyLen int) []byte {
	prf := hmac.New(sha256.New, []byte(password))
	block := make([]byte, len(salt)+4)
	copy(block, salt)
	block[len(salt)] = 0
	block[len(salt)+1] = 0
	block[len(salt)+2] = 0
	block[len(salt)+3] = 1

	prf.Write(block)
	u := prf.Sum(nil)
	out := make([]byte, len(u))
	copy(out, u)
	for i := 1; i < rounds; i++ {
		prf.Reset()
		prf.Write(u)
		u = prf.Sum(nil)
		for j := range out {
			out[j] ^= u[j]
		}
	}
	if keyLen > len(out) {
		return out
	}
	return out[:keyLen]
}

func hashPassword(password, salt string) string {
	return hex.EncodeToString(pbkdf2SHA256(password, salt, pbkdf2Rounds, 32))
}

func (a *app) checkPassword(user, password string) bool {
	s := a.settings()
	if !s.passwordSet() || user != s.AdminUser {
		return false
	}
	got := hashPassword(password, s.AdminSalt)
	return subtle.ConstantTimeCompare([]byte(got), []byte(s.AdminHash)) == 1
}

func (a *app) sessionToken() string {
	s := a.settings()
	expires := time.Now().Add(sessionTTL).Unix()
	payload := strconv.FormatInt(expires, 10)
	mac := hmac.New(sha256.New, []byte(s.SessionSecret))
	mac.Write([]byte(payload))
	return payload + "." + hex.EncodeToString(mac.Sum(nil))
}

func (a *app) sessionValid(r *http.Request) bool {
	cookie, err := r.Cookie(sessionCookie)
	if err != nil {
		return false
	}
	parts := strings.SplitN(cookie.Value, ".", 2)
	if len(parts) != 2 {
		return false
	}
	expires, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || time.Now().Unix() > expires {
		return false
	}
	mac := hmac.New(sha256.New, []byte(a.settings().SessionSecret))
	mac.Write([]byte(parts[0]))
	want := hex.EncodeToString(mac.Sum(nil))
	return subtle.ConstantTimeCompare([]byte(want), []byte(parts[1])) == 1
}

func (a *app) setSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookie,
		Value:    a.sessionToken(),
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   int(sessionTTL.Seconds()),
	})
}

// ---------- 应用主体 ----------

type app struct {
	configPath string
	dataDir    string
	addrFlag   string
	mu         chan struct{} // 简单互斥，保护 settings 读写
	cfg        *settings
	// restart 默认是「退出进程，让 systemd 拉起来」；测试里换成空实现
	restart func()
}

func newApp(configPath, dataDir, addrFlag string, port int) (*app, error) {
	if dataDir == "" {
		dataDir = filepath.Dir(configPath)
	}
	settingsPath := filepath.Join(dataDir, "server.json")
	s, err := loadSettings(settingsPath, port)
	if err != nil {
		return nil, err
	}
	a := &app{configPath: configPath, dataDir: dataDir, addrFlag: addrFlag,
		mu: make(chan struct{}, 1), cfg: s}
	a.restart = func() {
		time.Sleep(300 * time.Millisecond)
		os.Exit(0)
	}
	a.mu <- struct{}{}
	return a, nil
}

func (a *app) settings() *settings {
	<-a.mu
	s := a.cfg
	a.mu <- struct{}{}
	return s
}

func (a *app) updateSettings(fn func(*settings)) error {
	<-a.mu
	defer func() { a.mu <- struct{}{} }()
	fn(a.cfg)
	return saveSettings(filepath.Join(a.dataDir, "server.json"), a.cfg)
}

func (a *app) listenAddr() string {
	if a.addrFlag != "" {
		return a.addrFlag
	}
	return fmt.Sprintf("0.0.0.0:%d", a.settings().Port)
}

// ---------- 通用响应 ----------

func writeJSON(w http.ResponseWriter, status int, body interface{}) {
	payload, err := json.Marshal(body)
	if err != nil {
		http.Error(w, `{"error":"encode failed"}`, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(payload)
}

func (a *app) adminAuthorized(r *http.Request) bool {
	if a.sessionValid(r) {
		return true
	}
	user, pass, ok := r.BasicAuth()
	return ok && a.checkPassword(user, pass)
}

func etagOf(payload []byte) string {
	sum := sha256.Sum256(payload)
	return `"` + hex.EncodeToString(sum[:16]) + `"`
}

// ---------- App 接口 ----------

// ensureConfigFile 首次启动时把内置默认配置写出来。
// 默认值与 App 内置的一致（聚合万年历 + 和风天气 + MiMo），开箱即用。
func ensureConfigFile(path string) error {
	if _, err := os.Stat(path); err == nil {
		return nil
	}
	if err := writeAtomic(path, defaultConfigJSON, 0o640); err != nil {
		return err
	}
	log.Printf("created %s from built-in defaults", path)
	return nil
}

// readConfig 读盘并与内置默认值合并：旧版本配置里缺的键（比如新增的接口解析映射）
// 自动补上，已有的值保持不动——包括手动清空的空字符串，所以「清空某个字段」仍然有效。
func (a *app) readConfig() ([]byte, error) {
	raw, err := os.ReadFile(a.configPath)
	if err != nil {
		return nil, err
	}
	var stored map[string]interface{}
	if err := json.Unmarshal(raw, &stored); err != nil {
		return nil, fmt.Errorf("配置不是合法 JSON 对象：%w", err)
	}
	var defaults map[string]interface{}
	if err := json.Unmarshal(defaultConfigJSON, &defaults); err != nil {
		return nil, fmt.Errorf("内置默认配置损坏：%w", err)
	}
	payload, err := json.MarshalIndent(fillDefaults(stored, defaults), "", "  ")
	if err != nil {
		return nil, err
	}
	return append(payload, '\n'), nil
}

// fillDefaults 只补缺失的键，不覆盖已有值；嵌套对象递归处理。
func fillDefaults(stored, defaults map[string]interface{}) map[string]interface{} {
	out := make(map[string]interface{}, len(stored)+len(defaults))
	for key, value := range stored {
		out[key] = value
	}
	for key, def := range defaults {
		current, ok := out[key]
		if !ok {
			out[key] = def
			continue
		}
		currentMap, currentIsMap := current.(map[string]interface{})
		defaultMap, defaultIsMap := def.(map[string]interface{})
		if currentIsMap && defaultIsMap {
			out[key] = fillDefaults(currentMap, defaultMap)
		}
	}
	return out
}

func (a *app) handleConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	token := a.settings().AppToken
	if token != "" && r.Header.Get("X-Config-Token") != token {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid config token"})
		return
	}
	raw, err := a.readConfig()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError,
			map[string]string{"error": "cannot read config: " + err.Error()})
		return
	}
	var probe struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(raw, &probe); err != nil {
		writeJSON(w, http.StatusInternalServerError,
			map[string]string{"error": "invalid config json: " + err.Error()})
		return
	}

	etag := etagOf(raw)
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "no-cache")
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(raw)))
	w.Header().Set("X-Config-Version", strconv.Itoa(probe.Version))
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(raw)
	}
}

func (a *app) handleHealth(w http.ResponseWriter, r *http.Request) {
	token := a.settings().AppToken
	if token != "" && r.Header.Get("X-Config-Token") != token {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid config token"})
		return
	}
	raw, err := a.readConfig()
	body := map[string]interface{}{"ok": err == nil, "port": a.settings().Port}
	if err != nil {
		body["error"] = err.Error()
		writeJSON(w, http.StatusInternalServerError, body)
		return
	}
	var probe struct {
		Version   int    `json:"version"`
		UpdatedAt string `json:"updatedAt"`
	}
	_ = json.Unmarshal(raw, &probe)
	body["version"] = probe.Version
	body["updatedAt"] = probe.UpdatedAt
	writeJSON(w, http.StatusOK, body)
}

// ---------- 管理页与登录 ----------

func (a *app) handleAdminPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(adminHTML)
}

// handleState 给页面用：是否已设置口令、是否已登录、当前设置。
func (a *app) handleState(w http.ResponseWriter, r *http.Request) {
	s := a.settings()
	loggedIn := a.adminAuthorized(r)
	body := map[string]interface{}{
		"setup":         s.passwordSet(),
		"loggedIn":      loggedIn,
		"port":          s.Port,
		"publicBaseUrl": s.PublicBaseURL,
		"adminUser":     s.AdminUser,
	}
	if loggedIn {
		body["appToken"] = s.AppToken
		if s.PublicBaseURL != "" {
			body["configUrl"] = strings.TrimRight(s.PublicBaseURL, "/") + "/api/config"
		}
	}
	writeJSON(w, http.StatusOK, body)
}

// handleSetup 首次设置管理员口令；已设置过就拒绝。
func (a *app) handleSetup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use POST"})
		return
	}
	if a.settings().passwordSet() {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "已经设置过口令了"})
		return
	}
	var req struct {
		User string `json:"user"`
		Pass string `json:"pass"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<14)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求不是合法 JSON"})
		return
	}
	user := strings.TrimSpace(req.User)
	if user == "" {
		user = "admin"
	}
	if len(req.Pass) < 6 {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "口令至少 6 位"})
		return
	}
	salt := randomHex(16)
	hash := hashPassword(req.Pass, salt)
	if err := a.updateSettings(func(s *settings) {
		s.AdminUser = user
		s.AdminSalt = salt
		s.AdminHash = hash
	}); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	a.setSessionCookie(w)
	log.Printf("admin password set for user %q", user)
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})
}

func (a *app) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use POST"})
		return
	}
	var req struct {
		User string `json:"user"`
		Pass string `json:"pass"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<14)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求不是合法 JSON"})
		return
	}
	if !a.checkPassword(strings.TrimSpace(req.User), req.Pass) {
		time.Sleep(300 * time.Millisecond) // 拖一下，别让人快速撞库
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "用户名或口令不对"})
		return
	}
	a.setSessionCookie(w)
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})
}

func (a *app) handleLogout(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: "", Path: "/", HttpOnly: true, MaxAge: -1,
	})
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})
}

// handleSettings 保存端口、对外域名、管理员口令、App 令牌。
func (a *app) handleSettings(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use POST"})
		return
	}
	var req struct {
		Port          *int   `json:"port"`
		PublicBaseURL string `json:"publicBaseUrl"`
		AdminUser     string `json:"adminUser"`
		NewPass       string `json:"newPass"`
		RotateToken   bool   `json:"rotateToken"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<14)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求不是合法 JSON"})
		return
	}

	oldPort := a.settings().Port
	newToken := ""
	err := a.updateSettings(func(s *settings) {
		if req.Port != nil && *req.Port > 0 && *req.Port <= 65535 {
			s.Port = *req.Port
		}
		s.PublicBaseURL = strings.TrimRight(strings.TrimSpace(req.PublicBaseURL), "/")
		if u := strings.TrimSpace(req.AdminUser); u != "" {
			s.AdminUser = u
		}
		if len(req.NewPass) > 0 {
			if len(req.NewPass) < 6 {
				return
			}
			s.AdminSalt = randomHex(16)
			s.AdminHash = hashPassword(req.NewPass, s.AdminSalt)
		}
		if req.RotateToken {
			s.AppToken = randomHex(24)
			newToken = s.AppToken
		}
	})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}

	restart := a.settings().Port != oldPort
	if restart {
		// 换端口只能重启进程；systemd 会按 Restart=always 拉起来
		log.Printf("port changed %d -> %d, restarting", oldPort, a.settings().Port)
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true, "port": a.settings().Port, "restart": restart,
		"appToken": newToken,
	})
	if restart {
		go a.restart()
	}
}

func (a *app) handleRestart(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"ok": true})
	go a.restart()
}

// ---------- 配置读写 ----------

func (a *app) handleAdminGetConfig(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	raw, err := a.readConfig()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(raw)
}

// handleAdminSave 校验 JSON、version 加一、原子落盘。
func (a *app) handleAdminSave(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	var config map[string]interface{}
	if err := json.Unmarshal(body, &config); err != nil {
		writeJSON(w, http.StatusBadRequest,
			map[string]string{"error": "配置不是合法 JSON 对象：" + err.Error()})
		return
	}
	if config == nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "配置不能是空值"})
		return
	}

	oldVersion := 0
	if raw, readErr := a.readConfig(); readErr == nil {
		var probe struct {
			Version int `json:"version"`
		}
		_ = json.Unmarshal(raw, &probe)
		oldVersion = probe.Version
	}
	config["version"] = oldVersion + 1
	config["updatedAt"] = time.Now().Format(time.RFC3339)

	payload, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	payload = append(payload, '\n')
	if err := writeAtomic(a.configPath, payload, 0o640); err != nil {
		writeJSON(w, http.StatusInternalServerError,
			map[string]string{"error": "写入失败：" + err.Error()})
		return
	}

	// 写完立刻读回来核对：能立刻区分「没写进去」和「读到的是缓存」。
	// 也顺手把读回的值回给页面，用户一眼就能看到服务端现在存的是什么。
	stored := a.fingerprints()
	log.Printf("config saved: version=%d bytes=%d ttsKey=%s",
		oldVersion+1, len(payload), stored["ttsKey"])
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true, "version": oldVersion + 1, "bytes": len(payload),
		"stored": stored, "path": a.configPath,
	})
}

// fingerprints 读回磁盘上的配置，返回各 Key 的头尾摘要，用于保存后的核对。
func (a *app) fingerprints() map[string]string {
	result := map[string]string{"calendar": "-", "weatherHost": "-", "weatherKey": "-", "ttsKey": "-"}
	raw, err := a.readConfig()
	if err != nil {
		result["error"] = err.Error()
		return result
	}
	var cfg struct {
		Calendar struct {
			Key string `json:"key"`
		} `json:"calendar"`
		Weather struct {
			Host string `json:"host"`
			Key  string `json:"key"`
		} `json:"weather"`
		TTS struct {
			Key string `json:"key"`
		} `json:"tts"`
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		result["error"] = err.Error()
		return result
	}
	result["calendar"] = maskKey(cfg.Calendar.Key)
	result["weatherHost"] = maskKey(cfg.Weather.Host)
	result["weatherKey"] = maskKey(cfg.Weather.Key)
	result["ttsKey"] = maskKey(cfg.TTS.Key)
	return result
}

// maskKey 只露头尾，便于核对又不至于把 Key 打到日志/页面上。
func maskKey(value string) string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return "(空)"
	}
	if len(trimmed) <= 8 {
		return trimmed
	}
	return trimmed[:4] + "…" + trimmed[len(trimmed)-4:]
}

// writeAtomic 先写临时文件再 rename，避免读到写了一半的配置；顺手留一份 .bak。
func writeAtomic(path string, payload []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".config-*.tmp")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := tmp.Write(payload); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(name, mode); err != nil {
		return err
	}
	if old, readErr := os.ReadFile(path); readErr == nil {
		_ = os.WriteFile(path+".bak", old, mode)
	}
	return os.Rename(name, path)
}

// ---------- 语音合成自检 ----------

// handleAdminTestTTS 用配置里的 tts.key 真打一次小米 MiMo，只为了确认 Key 通不通。
func (a *app) handleAdminTestTTS(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use POST"})
		return
	}
	raw, err := a.readConfig()
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": err.Error()})
		return
	}
	var cfg struct {
		TTS struct {
			Key string `json:"key"`
		} `json:"tts"`
	}
	if err := json.Unmarshal(raw, &cfg); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if strings.TrimSpace(cfg.TTS.Key) == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "还没填语音合成 Key"})
		return
	}

	body := map[string]interface{}{
		"model": mimoModel,
		"messages": []map[string]string{
			{"role": "user", "content": mimoStyle},
			{"role": "assistant", "content": "今天多云，最高12度，最低3度。"},
		},
		"audio": map[string]string{"format": "wav", "voice": mimoVoice},
	}
	payload, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, mimoEndpoint, strings.NewReader(string(payload)))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(cfg.TTS.Key))
	req.Header.Set("Content-Type", "application/json; charset=utf-8")

	client := &http.Client{Timeout: ttsTimeout}
	resp, err := client.Do(req)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))

	result := map[string]interface{}{
		"ok":     resp.StatusCode >= 200 && resp.StatusCode < 300,
		"status": resp.StatusCode,
		"bytes":  len(respBody),
	}
	if result["ok"] == true {
		var parsed struct {
			Choices []struct {
				Message struct {
					Audio struct {
						Data string `json:"data"`
					} `json:"audio"`
				} `json:"message"`
			} `json:"choices"`
		}
		if json.Unmarshal(respBody, &parsed) == nil && len(parsed.Choices) > 0 {
			if data := parsed.Choices[0].Message.Audio.Data; data != "" {
				if decoded, err := base64.StdEncoding.DecodeString(data); err == nil {
					result["audioBytes"] = len(decoded)
				}
			}
		}
	} else {
		message := strings.Join(strings.Fields(string(respBody)), " ")
		if len(message) > 400 {
			message = message[:400] + "…"
		}
		result["message"] = message
	}
	writeJSON(w, http.StatusOK, result)
}
func jsonEscape(value string) string {
	quoted := strconv.Quote(value)
	return quoted[1 : len(quoted)-1]
}

// ---------- 启动 ----------

func main() {
	configPath := flag.String("config", envOr("TIANQI_CONFIG", "config.json"), "config.json 路径")
	dataDir := flag.String("data", os.Getenv("TIANQI_DATA"), "数据目录（放 server.json），默认与 config.json 同目录")
	addr := flag.String("addr", os.Getenv("TIANQI_ADDR"), "监听地址，留空则用网页里设置的端口，监听 0.0.0.0")
	port := flag.Int("port", defaultPort, "首次启动时的端口（之后在网页里改）")
	flag.Parse()

	// 没有配置文件就用内置默认值生成一份（与 App 内置的一致），开箱即用
	if err := ensureConfigFile(*configPath); err != nil {
		log.Fatalf("prepare config failed: %v", err)
	}

	svc, err := newApp(*configPath, *dataDir, *addr, *port)
	if err != nil {
		log.Fatalf("load settings failed: %v", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/keys", svc.handleConfig)
	mux.HandleFunc("/api/config", svc.handleConfig) // 老版本 App 的路径，保留兼容
	mux.HandleFunc("/api/health", svc.handleHealth)
	mux.HandleFunc("/admin", svc.handleAdminPage)
	mux.HandleFunc("/api/admin/state", svc.handleState)
	mux.HandleFunc("/api/admin/setup", svc.handleSetup)
	mux.HandleFunc("/api/admin/login", svc.handleLogin)
	mux.HandleFunc("/api/admin/logout", svc.handleLogout)
	mux.HandleFunc("/api/admin/settings", svc.handleSettings)
	mux.HandleFunc("/api/admin/restart", svc.handleRestart)
	mux.HandleFunc("/api/admin/config", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPut || r.Method == http.MethodPost {
			svc.handleAdminSave(w, r)
			return
		}
		svc.handleAdminGetConfig(w, r)
	})
	mux.HandleFunc("/api/admin/test-tts", svc.handleAdminTestTTS)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/admin", http.StatusFound)
	})

	httpServer := &http.Server{
		Addr:              svc.listenAddr(),
		Handler:           logRequests(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		log.Printf("listening on %s (config=%s, data=%s)", httpServer.Addr, *configPath, svc.dataDir)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("listen failed: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("shutdown: %v", err)
	}
	log.Print("stopped")
}

// logRequests 只记方法、路径、状态码，不记请求体（里面可能有 Key）。
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(recorder, r)
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, recorder.status,
			time.Since(start).Round(time.Millisecond))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
