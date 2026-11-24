package main

// 在线更新：从 GitHub Releases 拉取同架构的预编译二进制，校验 SHA-256 后
// 原子替换当前可执行文件，再交给 systemd 重启。
//
// 设计约束：
//   - 只认配置里的仓库（owner/repo），不接受任意 URL，避免被诱导下载。
//   - 必须带 SHA256SUMS.txt 并校验通过，否则拒绝替换。
//   - 临时文件与原二进制放在同一目录，保证 os.Rename 是原子替换。
//   - 更新失败自动保留旧二进制；成功前先备份为 <exe>.bak，可手动回滚。

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// version 由构建时注入：
//
//	go build -ldflags "-X main.version=v3.3.0"
//
// 未注入时保持 dev，此时只要配置了仓库就视为「有更新」。
var version = "dev"

const (
	defaultUpdateAPIBase = "https://api.github.com"
	updateChecksumAsset  = "SHA256SUMS.txt"
	maxBinarySize        = 64 << 20 // 64 MiB
	maxChecksumSize      = 1 << 20  // 1 MiB
)

var (
	// 测试里可替换。
	updateHTTPClient = &http.Client{Timeout: 60 * time.Second}
	executablePath   = os.Executable
)

type releaseAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Size               int64  `json:"size"`
}

type githubRelease struct {
	TagName     string         `json:"tag_name"`
	Name        string         `json:"name"`
	Body        string         `json:"body"`
	HTMLURL     string         `json:"html_url"`
	PublishedAt string         `json:"published_at"`
	Prerelease  bool           `json:"prerelease"`
	Draft       bool           `json:"draft"`
	Assets      []releaseAsset `json:"assets"`
}

// updateInfo 是返回给管理页的检查结果。
type updateInfo struct {
	Current     string `json:"current"`
	Latest      string `json:"latest"`
	Available   bool   `json:"available"`
	Notes       string `json:"notes"`
	URL         string `json:"url"`
	Repo        string `json:"repo"`
	AssetName   string `json:"assetName"`
	AssetURL    string `json:"assetUrl"`
	AssetSize   int64  `json:"assetSize"`
	ChecksumURL string `json:"checksumUrl"`
	PublishedAt string `json:"publishedAt"`
}

// updateAssetName 是构建产物命名约定，需与 CI 和工作流保持一致。
func updateAssetName() string {
	return fmt.Sprintf("tianqi-config-%s-%s", runtime.GOOS, runtime.GOARCH)
}

func validUpdateRepo(repo string) bool {
	parts := strings.Split(repo, "/")
	if len(parts) != 2 {
		return false
	}
	for _, part := range parts {
		if part == "" || strings.ContainsAny(part, " \t\r\n?#") {
			return false
		}
	}
	return true
}

// normalizeVersion 把 v3.2.0 / 3.2.0-rc1 归一成数字切片，便于逐段比较。
func normalizeVersion(value string) []int {
	value = strings.TrimSpace(value)
	value = strings.TrimPrefix(value, "v")
	if i := strings.IndexAny(value, "-+"); i >= 0 {
		value = value[:i]
	}
	if value == "" {
		return nil
	}
	parts := strings.Split(value, ".")
	nums := make([]int, 0, len(parts))
	for _, part := range parts {
		n, err := strconv.Atoi(part)
		if err != nil {
			return nil
		}
		nums = append(nums, n)
	}
	return nums
}

// isNewer 判断 latest 是否比 current 新。current 为 dev 时视为有更新。
func isNewer(current, latest string) bool {
	current = strings.TrimSpace(current)
	if current == "" || current == "dev" || current == "unknown" {
		return true
	}
	cur := normalizeVersion(current)
	lst := normalizeVersion(latest)
	if len(lst) == 0 {
		return false
	}
	if len(cur) == 0 {
		return true
	}
	length := len(cur)
	if len(lst) > length {
		length = len(lst)
	}
	for i := 0; i < length; i++ {
		var a, b int
		if i < len(cur) {
			a = cur[i]
		}
		if i < len(lst) {
			b = lst[i]
		}
		if a != b {
			return b > a
		}
	}
	return false
}

func (a *app) updateAPIBase() string {
	base := strings.TrimRight(strings.TrimSpace(a.settings().UpdateAPIBase), "/")
	if base == "" {
		return defaultUpdateAPIBase
	}
	return base
}

// checkUpdate 查询 GitHub Releases 的最新发布，并挑出当前平台对应的资产。
func (a *app) checkUpdate(ctx context.Context) (*updateInfo, error) {
	repo := strings.TrimSpace(a.settings().UpdateRepo)
	if repo == "" {
		return nil, fmt.Errorf("还没配置更新仓库，请先在「在线更新」里填 owner/repo")
	}
	if !validUpdateRepo(repo) {
		return nil, fmt.Errorf("更新仓库格式不对，应为 owner/repo")
	}

	endpoint := a.updateAPIBase() + "/repos/" + repo + "/releases/latest"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "tianqi-config/"+version)

	resp, err := updateHTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("访问更新仓库失败：%w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		detail := readSnippet(resp.Body, 512)
		return nil, fmt.Errorf("更新仓库返回 %d：%s", resp.StatusCode, detail)
	}

	var release githubRelease
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2<<20)).Decode(&release); err != nil {
		return nil, fmt.Errorf("解析发布信息失败：%w", err)
	}
	if strings.TrimSpace(release.TagName) == "" {
		return nil, fmt.Errorf("发布信息里没有版本号")
	}

	wantAsset := updateAssetName()
	info := &updateInfo{
		Current:     version,
		Latest:      release.TagName,
		Repo:        repo,
		Notes:       truncate(release.Body, 4000),
		URL:         release.HTMLURL,
		PublishedAt: release.PublishedAt,
		Available:   isNewer(version, release.TagName),
	}
	for _, asset := range release.Assets {
		switch asset.Name {
		case wantAsset:
			info.AssetName = asset.Name
			info.AssetURL = asset.BrowserDownloadURL
			info.AssetSize = asset.Size
		case updateChecksumAsset:
			info.ChecksumURL = asset.BrowserDownloadURL
		}
	}
	return info, nil
}

// applyUpdate 下载、校验并原子替换当前二进制。返回 applied=false 表示已是最新。
func (a *app) applyUpdate(ctx context.Context) (*updateInfo, bool, error) {
	info, err := a.checkUpdate(ctx)
	if err != nil {
		return nil, false, err
	}
	if !info.Available {
		return info, false, nil
	}
	if info.AssetURL == "" {
		return info, false, fmt.Errorf("最新版本 %s 没有适用于 %s/%s 的安装包",
			info.Latest, runtime.GOOS, runtime.GOARCH)
	}
	if info.ChecksumURL == "" {
		return info, false, fmt.Errorf("发布里没有 %s，无法校验，拒绝更新", updateChecksumAsset)
	}

	sums, err := fetchText(ctx, info.ChecksumURL, maxChecksumSize)
	if err != nil {
		return info, false, fmt.Errorf("下载校验文件失败：%w", err)
	}
	want := lookupChecksum(sums, info.AssetName)
	if want == "" {
		return info, false, fmt.Errorf("校验文件里找不到 %s", info.AssetName)
	}

	exe, err := executablePath()
	if err != nil {
		return info, false, fmt.Errorf("定位当前可执行文件失败：%w", err)
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}

	tmp, err := os.CreateTemp(filepath.Dir(exe), ".tianqi-update-*.tmp")
	if err != nil {
		return info, false, fmt.Errorf("创建临时文件失败（需要可写目录）：%w", err)
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	hasher := sha256.New()
	if _, err := downloadTo(ctx, info.AssetURL, io.MultiWriter(tmp, hasher), maxBinarySize); err != nil {
		tmp.Close()
		return info, false, fmt.Errorf("下载安装包失败：%w", err)
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return info, false, err
	}
	if err := tmp.Close(); err != nil {
		return info, false, err
	}

	got := hex.EncodeToString(hasher.Sum(nil))
	if !strings.EqualFold(got, want) {
		return info, false, fmt.Errorf("校验失败：期望 %s，实际 %s", want, got)
	}
	if err := os.Chmod(tmpName, 0o755); err != nil {
		return info, false, fmt.Errorf("设置执行权限失败：%w", err)
	}

	// 备份旧二进制，失败也继续（只是失去一键回滚）。
	if err := copyFile(exe, exe+".bak"); err != nil {
		log.Printf("update: backup %s failed: %v", exe, err)
	}
	if err := os.Rename(tmpName, exe); err != nil {
		return info, false, fmt.Errorf("替换二进制失败：%w", err)
	}
	log.Printf("update: %s -> %s applied, restarting", version, info.Latest)
	return info, true, nil
}

func (a *app) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use GET"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	info, err := a.checkUpdate(ctx)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, info)
}

func (a *app) handleUpdateApply(w http.ResponseWriter, r *http.Request) {
	if !a.adminAuthorized(r) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
		return
	}
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "use POST"})
		return
	}
	if !a.updateMu.TryLock() {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "已有更新在进行中"})
		return
	}
	defer a.updateMu.Unlock()

	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	info, applied, err := a.applyUpdate(ctx)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok": true, "applied": applied, "restarting": applied, "info": info,
	})
	if applied {
		go a.restart()
	}
}

// ---------- HTTP 小工具 ----------

func downloadTo(ctx context.Context, url string, dst io.Writer, limit int64) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", "tianqi-config/"+version)
	resp, err := updateHTTPClient.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength > limit {
		return 0, fmt.Errorf("文件超过大小限制")
	}
	n, err := io.Copy(dst, io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return n, err
	}
	if n > limit {
		return n, fmt.Errorf("文件超过大小限制")
	}
	return n, nil
}

func fetchText(ctx context.Context, url string, limit int64) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "tianqi-config/"+version)
	resp, err := updateHTTPClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit))
	if err != nil {
		return "", err
	}
	return string(body), nil
}

// lookupChecksum 从 sha256sum 格式（"<hex>  <name>"）里取指定文件名的校验和。
func lookupChecksum(sums, name string) string {
	for _, line := range strings.Split(sums, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		candidate := strings.TrimPrefix(fields[1], "*")
		if candidate == name && len(fields[0]) == 64 {
			return fields[0]
		}
	}
	return ""
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func readSnippet(r io.Reader, limit int64) string {
	body, _ := io.ReadAll(io.LimitReader(r, limit))
	return strings.Join(strings.Fields(string(body)), " ")
}

func truncate(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return value[:limit] + "…"
}
