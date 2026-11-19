package main

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func newTestApp(t *testing.T) *app {
	t.Helper()
	dir := t.TempDir()
	configPath := filepath.Join(dir, "config.json")
	content := `{"version":1,"calendar":{"key":"juhe"},"weather":{"host":"h","key":"qw"},"tts":{"key":"mimo"}}`
	if err := os.WriteFile(configPath, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	svc, err := newApp(configPath, dir, "", 8787)
	if err != nil {
		t.Fatal(err)
	}
	svc.restart = func() {}
	return svc
}

// setupAdmin 走一遍「首次设置口令」，返回带会话 Cookie 的请求构造器。
func setupAdmin(t *testing.T, svc *app) func(method, path string, body string) *http.Request {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/admin/setup",
		strings.NewReader(`{"user":"admin","pass":"secret123"}`))
	// 测试请求的 RemoteAddr 不是回环地址，所以必须带上启动时打印的设置码
	req.Header.Set("X-Setup-Token", svc.setupToken)
	rec := httptest.NewRecorder()
	svc.handleSetup(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("setup 失败：%d %s", rec.Code, rec.Body.String())
	}
	cookies := rec.Result().Cookies()
	return func(method, path, body string) *http.Request {
		var r *http.Request
		if body == "" {
			r = httptest.NewRequest(method, path, nil)
		} else {
			r = httptest.NewRequest(method, path, strings.NewReader(body))
		}
		for _, c := range cookies {
			r.AddCookie(c)
		}
		return r
	}
}

func TestFirstRunRequiresSetupThenLogin(t *testing.T) {
	svc := newTestApp(t)

	rec := httptest.NewRecorder()
	svc.handleState(rec, httptest.NewRequest(http.MethodGet, "/api/admin/state", nil))
	var state map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &state); err != nil {
		t.Fatal(err)
	}
	if state["setup"] != false || state["loggedIn"] != false {
		t.Fatalf("state = %v", state)
	}

	setupAdmin(t, svc)

	rec = httptest.NewRecorder()
	svc.handleSetup(rec, httptest.NewRequest(http.MethodPost, "/api/admin/setup",
		strings.NewReader(`{"user":"x","pass":"yyyyyy"}`)))
	if rec.Code != http.StatusConflict {
		t.Fatalf("重复设置应 409，实际 %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	svc.handleLogin(rec, httptest.NewRequest(http.MethodPost, "/api/admin/login",
		strings.NewReader(`{"user":"admin","pass":"wrong-pass"}`)))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("错口令应 401，实际 %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	svc.handleLogin(rec, httptest.NewRequest(http.MethodPost, "/api/admin/login",
		strings.NewReader(`{"user":"admin","pass":"secret123"}`)))
	if rec.Code != http.StatusOK || len(rec.Result().Cookies()) == 0 {
		t.Fatalf("登录失败：%d", rec.Code)
	}
}

func TestKeysEndpointRequiresTokenAndSupportsETag(t *testing.T) {
	svc := newTestApp(t)
	token := svc.settings().AppToken

	rec := httptest.NewRecorder()
	svc.handleConfig(rec, httptest.NewRequest(http.MethodGet, "/api/keys", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("没有令牌应 401，实际 %d", rec.Code)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/keys", nil)
	req.Header.Set("X-Config-Token", token)
	rec = httptest.NewRecorder()
	svc.handleConfig(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("带令牌应 200，实际 %d", rec.Code)
	}
	etag := rec.Header().Get("ETag")
	if etag == "" {
		t.Fatal("缺少 ETag")
	}
	// 只下发 Key，不再有接口地址/解析映射
	body := rec.Body.String()
	if strings.Contains(body, "parse") || strings.Contains(body, "currentPath") {
		t.Fatalf("响应里不该再有接口配置：%s", body)
	}
	if !strings.Contains(body, "\"calendar\"") {
		t.Fatalf("响应里应包含 Key：%s", body)
	}

	req2 := httptest.NewRequest(http.MethodGet, "/api/keys", nil)
	req2.Header.Set("X-Config-Token", token)
	req2.Header.Set("If-None-Match", etag)
	rec2 := httptest.NewRecorder()
	svc.handleConfig(rec2, req2)
	if rec2.Code != http.StatusNotModified {
		t.Fatalf("命中 ETag 应 304，实际 %d", rec2.Code)
	}
}

func TestAdminSaveBumpsVersionAndKeepsBackup(t *testing.T) {
	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)

	body := `{"version":1,"weather":{"host":"new.host","key":"new-key"}}`
	rec := httptest.NewRecorder()
	svc.handleAdminSave(rec, withCookie(http.MethodPut, "/api/admin/config", body))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存应 200，实际 %d：%s", rec.Code, rec.Body.String())
	}

	raw, err := os.ReadFile(svc.configPath)
	if err != nil {
		t.Fatal(err)
	}
	var saved map[string]interface{}
	if err := json.Unmarshal(raw, &saved); err != nil {
		t.Fatal(err)
	}
	if saved["version"].(float64) != 2 {
		t.Fatalf("version 应加一到 2，实际 %v", saved["version"])
	}
	weather := saved["weather"].(map[string]interface{})
	if weather["key"] != "new-key" {
		t.Fatalf("weather = %v", weather)
	}
	if _, err := os.Stat(svc.configPath + ".bak"); err != nil {
		t.Fatalf("应留一份 .bak：%v", err)
	}
}

func TestAdminSaveRequiresAuthAndValidJSON(t *testing.T) {
	svc := newTestApp(t)

	rec := httptest.NewRecorder()
	svc.handleAdminSave(rec, httptest.NewRequest(http.MethodPut, "/api/admin/config",
		strings.NewReader(`{"version":1}`)))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("没有会话应 401，实际 %d", rec.Code)
	}

	withCookie := setupAdmin(t, svc)
	rec = httptest.NewRecorder()
	svc.handleAdminSave(rec, withCookie(http.MethodPut, "/api/admin/config", `{oops`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("坏 JSON 应 400，实际 %d", rec.Code)
	}
}

func TestSettingsSaveChangesPortUrlTokenAndPassword(t *testing.T) {
	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)
	oldToken := svc.settings().AppToken

	body := `{"port":9000,"publicBaseUrl":"https://tq.example.com/","adminUser":"boss",
	          "newPass":"newsecret","rotateToken":true}`
	rec := httptest.NewRecorder()
	svc.handleSettings(rec, withCookie(http.MethodPost, "/api/admin/settings", body))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存设置应 200，实际 %d：%s", rec.Code, rec.Body.String())
	}
	var result map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result["restart"] != true || result["port"].(float64) != 9000 {
		t.Fatalf("result = %v", result)
	}

	s := svc.settings()
	if s.Port != 9000 || s.PublicBaseURL != "https://tq.example.com" ||
		s.AdminUser != "boss" || s.AppToken == oldToken {
		t.Fatalf("settings = %+v", s)
	}

	rec = httptest.NewRecorder()
	svc.handleLogin(rec, httptest.NewRequest(http.MethodPost, "/api/admin/login",
		strings.NewReader(`{"user":"boss","pass":"newsecret"}`)))
	if rec.Code != http.StatusOK {
		t.Fatalf("新口令应能登录，实际 %d", rec.Code)
	}
	rec = httptest.NewRecorder()
	svc.handleLogin(rec, httptest.NewRequest(http.MethodPost, "/api/admin/login",
		strings.NewReader(`{"user":"boss","pass":"secret123"}`)))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("旧口令应失效，实际 %d", rec.Code)
	}
}

func TestSessionCookieIsSigned(t *testing.T) {
	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)

	if !svc.adminAuthorized(withCookie(http.MethodGet, "/api/admin/config", "")) {
		t.Fatal("合法会话应通过")
	}

	bad := withCookie(http.MethodGet, "/api/admin/config", "")
	var tampered *http.Cookie
	for _, c := range bad.Cookies() {
		if c.Name == sessionCookie {
			tampered = &http.Cookie{Name: c.Name, Value: c.Value + "0"}
		}
	}
	if tampered == nil {
		t.Fatal("没找到会话 Cookie")
	}
	bad.Header.Del("Cookie")
	bad.AddCookie(tampered)
	if svc.adminAuthorized(bad) {
		t.Fatal("篡改过的会话不该通过")
	}

	basic := httptest.NewRequest(http.MethodGet, "/api/admin/config", nil)
	basic.SetBasicAuth("admin", "secret123")
	if !svc.adminAuthorized(basic) {
		t.Fatal("Basic Auth 应通过")
	}
}

func TestAdminPageServesEmbeddedHTML(t *testing.T) {
	svc := newTestApp(t)
	rec := httptest.NewRecorder()
	svc.handleAdminPage(rec, httptest.NewRequest(http.MethodGet, "/admin", nil))
	body := rec.Body.String()
	if rec.Code != http.StatusOK || !strings.Contains(body, "云端 Key") {
		t.Fatalf("页面不对：%d", rec.Code)
	}
	if !strings.Contains(body, "先设置管理员口令") {
		t.Fatal("页面缺少首次设置面板")
	}
}

// 旧版配置缺字段时用内置默认值补齐（只补 Key 结构）。
func TestReadConfigFillsMissingDefaults(t *testing.T) {
	svc := newTestApp(t)
	if err := os.WriteFile(svc.configPath, []byte(`{"version":7}`), 0o600); err != nil {
		t.Fatal(err)
	}
	raw, err := svc.readConfig()
	if err != nil {
		t.Fatal(err)
	}
	var merged map[string]interface{}
	if err := json.Unmarshal(raw, &merged); err != nil {
		t.Fatal(err)
	}
	if merged["version"].(float64) != 7 {
		t.Fatalf("version 被改了：%v", merged["version"])
	}
	if _, ok := merged["calendar"].(map[string]interface{}); !ok {
		t.Fatalf("calendar 未补默认值：%v", merged)
	}
	if _, ok := merged["weather"].(map[string]interface{}); !ok {
		t.Fatalf("weather 未补默认值：%v", merged)
	}
	if _, ok := merged["tts"].(map[string]interface{}); !ok {
		t.Fatalf("tts 未补默认值：%v", merged)
	}
}

func TestEnsureConfigFileCreatesDefaults(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	if err := ensureConfigFile(path); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"schema": 2`) ||
		!strings.Contains(string(raw), `"calendar"`) {
		t.Fatalf("默认配置不完整：%s", raw)
	}
	// 已存在时不能覆盖
	if err := os.WriteFile(path, []byte(`{"version":9}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ensureConfigFile(path); err != nil {
		t.Fatal(err)
	}
	again, _ := os.ReadFile(path)
	if !strings.Contains(string(again), `"version":9`) {
		t.Fatal("已存在的配置被覆盖了")
	}
}

// 语音 Key 自检：打的是写死的 MiMo 协议，测试里把地址指到本地假接口。
func TestAdminTestTTSReportsSuccess(t *testing.T) {
	wav := []byte{0x52, 0x49, 0x46, 0x46, 0x00, 0x01}
	var gotAuth, gotBody string
	stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		raw, _ := io.ReadAll(r.Body)
		gotBody = string(raw)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"audio":{"data":"` +
			base64.StdEncoding.EncodeToString(wav) + `"}}}]}`))
	}))
	defer stub.Close()

	old := mimoEndpoint
	mimoEndpoint = stub.URL
	defer func() { mimoEndpoint = old }()

	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)
	rec := httptest.NewRecorder()
	svc.handleAdminTestTTS(rec, withCookie(http.MethodPost, "/api/admin/test-tts", `{}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("自检应 200，实际 %d：%s", rec.Code, rec.Body.String())
	}
	if gotAuth != "Bearer mimo" {
		t.Fatalf("鉴权头不对：%s", gotAuth)
	}
	if !strings.Contains(gotBody, "mimo-v2.5-tts") || !strings.Contains(gotBody, "assistant") {
		t.Fatalf("请求体不对：%s", gotBody)
	}
	var result map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result["ok"] != true || result["audioBytes"].(float64) != float64(len(wav)) {
		t.Fatalf("result = %v", result)
	}
}

func TestAdminTestTTSReportsFailure(t *testing.T) {
	stub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":{"message":"Invalid API Key"}}`))
	}))
	defer stub.Close()

	old := mimoEndpoint
	mimoEndpoint = stub.URL
	defer func() { mimoEndpoint = old }()

	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)
	rec := httptest.NewRecorder()
	svc.handleAdminTestTTS(rec, withCookie(http.MethodPost, "/api/admin/test-tts", `{}`))

	var result map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result["ok"] != false || result["status"].(float64) != 401 {
		t.Fatalf("result = %v", result)
	}
	if msg, _ := result["message"].(string); !strings.Contains(msg, "Invalid API Key") {
		t.Fatalf("message = %v", result["message"])
	}
}

func TestAdminTestTTSWithoutKey(t *testing.T) {
	svc := newTestApp(t)
	if err := os.WriteFile(svc.configPath, []byte(`{"version":1,"tts":{"key":""}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	withCookie := setupAdmin(t, svc)
	rec := httptest.NewRecorder()
	svc.handleAdminTestTTS(rec, withCookie(http.MethodPost, "/api/admin/test-tts", `{}`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("没填 Key 应 400，实际 %d", rec.Code)
	}
}

// 首次设置口令必须能证明身份：公网请求没有设置码就该被拒绝，
// 否则谁先连上端口，谁就能把服务端变成自己的。
func TestSetupRequiresProofFromPublicAddress(t *testing.T) {
	svc := newTestApp(t)
	body := `{"user":"admin","pass":"secret123"}`

	// 公网地址 + 没有设置码 → 403
	req := httptest.NewRequest(http.MethodPost, "/api/admin/setup", strings.NewReader(body))
	req.RemoteAddr = "203.0.113.9:40000"
	rec := httptest.NewRecorder()
	svc.handleSetup(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("公网无设置码应 403，实际 %d", rec.Code)
	}

	// 公网地址 + 错误设置码 → 403
	req = httptest.NewRequest(http.MethodPost, "/api/admin/setup", strings.NewReader(body))
	req.RemoteAddr = "203.0.113.9:40000"
	req.Header.Set("X-Setup-Token", "wrong-token")
	rec = httptest.NewRecorder()
	svc.handleSetup(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("错误设置码应 403，实际 %d", rec.Code)
	}

	// 本机地址 → 直接允许（在服务器上或走 SSH 隧道操作）
	req = httptest.NewRequest(http.MethodPost, "/api/admin/setup", strings.NewReader(body))
	req.RemoteAddr = "127.0.0.1:55555"
	rec = httptest.NewRecorder()
	svc.handleSetup(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("本机设置应 200，实际 %d：%s", rec.Code, rec.Body.String())
	}

	// 设过之后再设 → 409，不管从哪来
	req = httptest.NewRequest(http.MethodPost, "/api/admin/setup", strings.NewReader(body))
	req.RemoteAddr = "127.0.0.1:55555"
	rec = httptest.NewRecorder()
	svc.handleSetup(rec, req)
	if rec.Code != http.StatusConflict {
		t.Fatalf("重复设置应 409，实际 %d", rec.Code)
	}
}

// 公网地址带上启动日志里的设置码，可以正常完成首次设置。
func TestSetupAcceptsTokenFromPublicAddress(t *testing.T) {
	svc := newTestApp(t)
	req := httptest.NewRequest(http.MethodPost, "/api/admin/setup",
		strings.NewReader(`{"user":"admin","pass":"secret123"}`))
	req.RemoteAddr = "203.0.113.9:40000"
	req.Header.Set("X-Setup-Token", svc.setupToken)
	rec := httptest.NewRecorder()
	svc.handleSetup(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("带设置码应 200，实际 %d：%s", rec.Code, rec.Body.String())
	}
}

// 只提交一部分配置时，没提交的 Key 必须保留：
// 整体替换会让 App 把云端空值当成「Key 被删了」，从而清掉本地已同步的 Key。
func TestAdminSaveMergesInsteadOfReplacing(t *testing.T) {
	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)

	rec := httptest.NewRecorder()
	svc.handleAdminSave(rec, withCookie(http.MethodPut, "/api/admin/config",
		`{"weather":{"host":"new.host","key":"new-key"}}`))
	if rec.Code != http.StatusOK {
		t.Fatalf("保存应 200，实际 %d：%s", rec.Code, rec.Body.String())
	}

	raw, err := os.ReadFile(svc.configPath)
	if err != nil {
		t.Fatal(err)
	}
	var saved map[string]interface{}
	if err := json.Unmarshal(raw, &saved); err != nil {
		t.Fatal(err)
	}
	calendar, _ := saved["calendar"].(map[string]interface{})
	if calendar["key"] != "juhe" {
		t.Fatalf("没提交的万年历 Key 丢了：%v", saved)
	}
	tts, _ := saved["tts"].(map[string]interface{})
	if tts["key"] != "mimo" {
		t.Fatalf("没提交的语音 Key 丢了：%v", saved)
	}
	weather, _ := saved["weather"].(map[string]interface{})
	if weather["key"] != "new-key" {
		t.Fatalf("weather = %v", weather)
	}
}

// Key 类型写错（数字/数组）应当当场 400，而不是存进磁盘等 App 解析失败。
func TestAdminSaveRejectsWrongKeyType(t *testing.T) {
	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)

	rec := httptest.NewRecorder()
	svc.handleAdminSave(rec, withCookie(http.MethodPut, "/api/admin/config",
		`{"calendar":{"key":123}}`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("类型不对应 400，实际 %d：%s", rec.Code, rec.Body.String())
	}

	raw, err := os.ReadFile(svc.configPath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"juhe"`) {
		t.Fatalf("校验失败时不该动磁盘：%s", raw)
	}
}

// settings() 必须返回值快照：并发读写时不能看到写了一半的字段（go test -race 会报）。
func TestSettingsSnapshotIsRaceFree(t *testing.T) {
	svc := newTestApp(t)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 50; i++ {
			s := svc.settings()
			if s.AdminUser == "" && s.Port == 0 && s.AppToken == "" {
				t.Errorf("读到空配置")
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 50; i++ {
			if err := svc.updateSettings(func(s *settings) {
				s.AppToken = randomHex(8)
			}); err != nil {
				t.Errorf("updateSettings: %v", err)
				return
			}
		}
	}()
	wg.Wait()
}

// 改口令太短要当场 400，不能静默忽略还回 ok：原来闭包里 return 只是跳出闭包，
// 配置照样保存、页面提示「已保存」并清空输入框，用户以为口令换了其实没换。
func TestAdminSettingsRejectsShortPassword(t *testing.T) {
	svc := newTestApp(t)
	withCookie := setupAdmin(t, svc)

	rec := httptest.NewRecorder()
	svc.handleSettings(rec, withCookie(http.MethodPost, "/api/admin/settings", `{"newPass":"123"}`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("短口令应 400，实际 %d：%s", rec.Code, rec.Body.String())
	}

	rec = httptest.NewRecorder()
	svc.handleLogin(rec, httptest.NewRequest(http.MethodPost, "/api/admin/login",
		strings.NewReader(`{"user":"admin","pass":"secret123"}`)))
	if rec.Code != http.StatusOK {
		t.Fatalf("旧口令应仍然有效，实际 %d", rec.Code)
	}
}
