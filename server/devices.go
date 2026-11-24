package main

// 设备管理：App 每次拉 Key 时上报设备 ID、名称和版本，服务端记下最近一次见面。
// 只用于管理页展示「有几台设备对接过」，不做鉴权。

import (
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxDevices = 500

type device struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Version   string `json:"version"`
	IP        string `json:"ip"`
	FirstSeen string `json:"firstSeen"`
	LastSeen  string `json:"lastSeen"`
	Requests  int64  `json:"requests"`
}

type deviceStore struct {
	mu      sync.Mutex
	path    string
	devices map[string]*device
}

func newDeviceStore(path string) *deviceStore {
	store := &deviceStore{path: path, devices: map[string]*device{}}
	raw, err := os.ReadFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("读取设备列表失败：%v", err)
		}
		return store
	}
	var list []device
	if err := json.Unmarshal(raw, &list); err != nil {
		log.Printf("devices.json 不是合法 JSON：%v", err)
		return store
	}
	for i := range list {
		item := list[i]
		if item.ID == "" {
			continue
		}
		store.devices[item.ID] = &item
	}
	return store
}

// touch 记录一次设备上报。没有设备 ID 的请求（老版本 App）不计数。
func (s *deviceStore) touch(r *http.Request) {
	id := cleanHeader(r.Header.Get("X-Device-Id"), 128)
	if id == "" {
		return
	}
	now := time.Now().Format(time.RFC3339)
	s.mu.Lock()
	defer s.mu.Unlock()

	item, ok := s.devices[id]
	if !ok {
		item = &device{ID: id, FirstSeen: now}
		s.devices[id] = item
		if len(s.devices) > maxDevices {
			s.evictOldestLocked()
		}
	}
	item.Name = cleanHeader(r.Header.Get("X-Device-Name"), 80)
	item.Version = cleanHeader(r.Header.Get("X-App-Version"), 40)
	item.IP = clientIP(r)
	item.LastSeen = now
	item.Requests++
	s.saveLocked()
}

func (s *deviceStore) list() []device {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]device, 0, len(s.devices))
	for _, item := range s.devices {
		result = append(result, *item)
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].LastSeen > result[j].LastSeen
	})
	return result
}

func (s *deviceStore) count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.devices)
}

func (s *deviceStore) evictOldestLocked() {
	var oldestID string
	var oldest string
	for id, item := range s.devices {
		if oldestID == "" || item.LastSeen < oldest {
			oldestID = id
			oldest = item.LastSeen
		}
	}
	if oldestID != "" {
		delete(s.devices, oldestID)
	}
}

func (s *deviceStore) saveLocked() {
	list := make([]device, 0, len(s.devices))
	for _, item := range s.devices {
		list = append(list, *item)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].LastSeen > list[j].LastSeen
	})
	payload, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		log.Printf("编码设备列表失败：%v", err)
		return
	}
	payload = append(payload, '\n')
	if err := writeAtomic(s.path, payload, 0o600); err != nil {
		log.Printf("保存设备列表失败：%v", err)
	}
}

func cleanHeader(value string, limit int) string {
	runes := []rune(strings.TrimSpace(value))
	if len(runes) > limit {
		runes = runes[:limit]
	}
	return string(runes)
}

func clientIP(r *http.Request) string {
	if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
		if first := strings.TrimSpace(strings.Split(forwarded, ",")[0]); first != "" {
			return cleanHeader(first, 64)
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	return cleanHeader(strings.TrimPrefix(host, "::ffff:"), 64)
}
