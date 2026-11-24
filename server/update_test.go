package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestIsNewer(t *testing.T) {
	cases := []struct {
		current, latest string
		want            bool
	}{
		{"v3.2.0", "v3.3.0", true},
		{"v3.2.0", "v3.2.0", false},
		{"v3.2.0", "v3.2.1", true},
		{"v3.10.0", "v3.9.0", false},
		{"3.2", "v3.2.0", false},
		{"dev", "v1.0.0", true},
		{"", "v1.0.0", true},
		{"v1.0.0", "not-a-version", false},
	}
	for _, tc := range cases {
		if got := isNewer(tc.current, tc.latest); got != tc.want {
			t.Errorf("isNewer(%q, %q) = %v, want %v", tc.current, tc.latest, got, tc.want)
		}
	}
}

func TestLookupChecksum(t *testing.T) {
	first := strings.Repeat("a", 64)
	second := strings.Repeat("d", 64)
	sums := first + "  tianqi-config-linux-amd64\n" +
		second + "  other-file\n"
	if got := lookupChecksum(sums, "tianqi-config-linux-amd64"); got != first {
		t.Fatalf("got %q", got)
	}
	if got := lookupChecksum(sums, "missing"); got != "" {
		t.Fatalf("expected empty, got %q", got)
	}
}

func setUpdateSettings(t *testing.T, svc *app, repo, base string) {
	t.Helper()
	if err := svc.updateSettings(func(s *settings) {
		s.UpdateRepo = repo
		s.UpdateAPIBase = base
	}); err != nil {
		t.Fatal(err)
	}
}

func TestCheckUpdateSelectsMatchingAsset(t *testing.T) {
	svc := newTestApp(t)
	payload := []byte("binary-v9")
	sum := sha256.Sum256(payload)

	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/owner/repo/releases/latest", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"tag_name":     "v9.9.9",
			"name":         "v9.9.9",
			"body":         "release notes",
			"html_url":     "https://github.com/owner/repo/releases/tag/v9.9.9",
			"published_at": "2026-11-24T00:00:00Z",
			"assets": []map[string]interface{}{
				{"name": updateAssetName(), "browser_download_url": srv.URL + "/bin", "size": len(payload)},
				{"name": updateChecksumAsset, "browser_download_url": srv.URL + "/SHA256SUMS.txt"},
			},
		})
	})
	mux.HandleFunc("/bin", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(payload) })
	mux.HandleFunc("/SHA256SUMS.txt", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(hex.EncodeToString(sum[:]) + "  " + updateAssetName() + "\n"))
	})
	srv = httptest.NewServer(mux)
	defer srv.Close()

	setUpdateSettings(t, svc, "owner/repo", srv.URL)

	info, err := svc.checkUpdate(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !info.Available || info.Latest != "v9.9.9" {
		t.Fatalf("info = %+v", info)
	}
	if info.AssetURL != srv.URL+"/bin" || info.ChecksumURL != srv.URL+"/SHA256SUMS.txt" {
		t.Fatalf("asset urls = %+v", info)
	}
}

func TestCheckUpdateRequiresRepo(t *testing.T) {
	svc := newTestApp(t)
	if _, err := svc.checkUpdate(context.Background()); err == nil {
		t.Fatal("expected error when repo is empty")
	}
}

func newUpdateServer(t *testing.T, payload, sums []byte) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/owner/repo/releases/latest", func(w http.ResponseWriter, r *http.Request) {
		assets := []map[string]interface{}{
			{"name": updateAssetName(), "browser_download_url": srv.URL + "/bin", "size": len(payload)},
		}
		if sums != nil {
			assets = append(assets, map[string]interface{}{
				"name": updateChecksumAsset, "browser_download_url": srv.URL + "/SHA256SUMS.txt",
			})
		}
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"tag_name": "v9.9.9",
			"assets":   assets,
		})
	})
	mux.HandleFunc("/bin", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(payload) })
	mux.HandleFunc("/SHA256SUMS.txt", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(sums) })
	srv = httptest.NewServer(mux)
	return srv
}

func withFakeExecutable(t *testing.T, content []byte) string {
	t.Helper()
	dir := t.TempDir()
	exe := filepath.Join(dir, "tianqi-config")
	if err := os.WriteFile(exe, content, 0o755); err != nil {
		t.Fatal(err)
	}
	old := executablePath
	executablePath = func() (string, error) { return exe, nil }
	t.Cleanup(func() { executablePath = old })
	return exe
}

func TestApplyUpdateReplacesBinaryAndKeepsBackup(t *testing.T) {
	svc := newTestApp(t)
	payload := []byte("new-binary-content")
	sum := sha256.Sum256(payload)
	oldBinary := []byte("old-binary-content")
	exe := withFakeExecutable(t, oldBinary)

	srv := newUpdateServer(t, payload, []byte(hex.EncodeToString(sum[:])+"  "+updateAssetName()+"\n"))
	defer srv.Close()
	setUpdateSettings(t, svc, "owner/repo", srv.URL)

	info, applied, err := svc.applyUpdate(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !applied {
		t.Fatalf("expected applied, info=%+v", info)
	}
	got, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(payload) {
		t.Fatalf("binary = %q, want %q", got, payload)
	}
	backup, err := os.ReadFile(exe + ".bak")
	if err != nil {
		t.Fatal(err)
	}
	if string(backup) != string(oldBinary) {
		t.Fatalf("backup = %q, want %q", backup, oldBinary)
	}
}

func TestApplyUpdateChecksumMismatchKeepsOldBinary(t *testing.T) {
	svc := newTestApp(t)
	payload := []byte("new-binary-content")
	oldBinary := []byte("old-binary-content")
	exe := withFakeExecutable(t, oldBinary)

	srv := newUpdateServer(t, payload, []byte("0000000000000000000000000000000000000000000000000000000000000000  "+updateAssetName()+"\n"))
	defer srv.Close()
	setUpdateSettings(t, svc, "owner/repo", srv.URL)

	_, applied, err := svc.applyUpdate(context.Background())
	if err == nil {
		t.Fatal("expected checksum error")
	}
	if applied {
		t.Fatal("should not apply on checksum mismatch")
	}
	got, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(oldBinary) {
		t.Fatalf("binary changed despite mismatch: %q", got)
	}
}

func TestApplyUpdateRefusesWithoutChecksum(t *testing.T) {
	svc := newTestApp(t)
	payload := []byte("new-binary-content")
	exe := withFakeExecutable(t, []byte("old"))

	srv := newUpdateServer(t, payload, nil)
	defer srv.Close()
	setUpdateSettings(t, svc, "owner/repo", srv.URL)

	if _, _, err := svc.applyUpdate(context.Background()); err == nil {
		t.Fatal("expected error when checksum asset missing")
	}
	got, _ := os.ReadFile(exe)
	if string(got) != "old" {
		t.Fatalf("binary changed: %q", got)
	}
}

func TestUpdateCheckRequiresAuth(t *testing.T) {
	svc := newTestApp(t)
	rec := httptest.NewRecorder()
	svc.handleUpdateCheck(rec, httptest.NewRequest(http.MethodGet, "/api/admin/update/check", nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
}
