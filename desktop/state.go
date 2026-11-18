package main

import (
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type Device struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Type     string `json:"deviceType"`
	Address  string `json:"address"`
	LastSeen int64  `json:"lastSeen"`
}

type Message struct {
	ID         string `json:"id"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	Dir        string `json:"dir"`  // in | out
	Kind       string `json:"kind"` // text | file
	Text       string `json:"text,omitempty"`
	FileName   string `json:"fileName,omitempty"`
	FileSize   int64  `json:"fileSize,omitempty"`
	MimeType   string `json:"mimeType,omitempty"`
	LocalPath  string `json:"localPath,omitempty"`
	Status     string `json:"status"` // queued | sending | sent | delivered | failed
	Progress   int    `json:"progress"`
	TS         int64  `json:"ts"`
}

type PendingView struct {
	TransferID string `json:"transferId"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
	FileName   string `json:"fileName"`
	FileSize   int64  `json:"fileSize"`
	MimeType   string `json:"mimeType"`
}

type pendingReq struct {
	view PendingView
	conn net.Conn
	req  TransferRequest
	ch   chan bool
}

type Store struct {
	mu            sync.RWMutex
	saveMu        sync.Mutex
	me            DeviceInfo
	devices       map[string]*Device
	messages      map[string][]*Message
	pending       map[string]*pendingReq
	autoAccept    bool
	downloadDir   string
	outboxDir     string
	dataFile      string
	transferPort  int
	discoveryPort int
	onOnline      func(deviceID string)
}

func newStore(me DeviceInfo, downloadDir, outboxDir, dataFile string, transferPort, discoveryPort int) *Store {
	return &Store{
		me:            me,
		devices:       make(map[string]*Device),
		messages:      make(map[string][]*Message),
		pending:       make(map[string]*pendingReq),
		downloadDir:   downloadDir,
		outboxDir:     outboxDir,
		dataFile:      dataFile,
		transferPort:  transferPort,
		discoveryPort: discoveryPort,
	}
}

func (s *Store) SetOnlineHook(fn func(deviceID string)) {
	s.mu.Lock()
	s.onOnline = fn
	s.mu.Unlock()
}

func (s *Store) Me() DeviceInfo {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.me
}

func (s *Store) SetAutoAccept(v bool) {
	s.mu.Lock()
	s.autoAccept = v
	s.mu.Unlock()
}

func (s *Store) AutoAccept() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.autoAccept
}

func (s *Store) DownloadDir() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.downloadDir
}

func (s *Store) OutboxDir() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.outboxDir
}

// UpsertDevice 记录/更新对端；新上线时触发离线队列刷新。
func (s *Store) UpsertDevice(info DeviceInfo, ip string) {
	if info.ID == "" || info.ID == s.me.ID {
		return
	}
	s.mu.Lock()
	d, ok := s.devices[info.ID]
	wasOnline := ok && time.Since(time.UnixMilli(d.LastSeen)) < 20*time.Second
	if !ok {
		d = &Device{ID: info.ID}
		s.devices[info.ID] = d
	}
	d.Name = info.Name
	d.Type = info.DeviceType
	d.Address = ip
	d.LastSeen = time.Now().UnixMilli()
	hook := s.onOnline
	s.mu.Unlock()

	// 对方重装/清数据会换 ID：把同名离线旧会话合并到当前设备
	s.MergeByName(info.ID, info.Name)

	if hook != nil && (!wasOnline || !ok) {
		go hook(info.ID)
	}
}

// MergeByName 将同名且当前离线的旧会话迁移到 newID。
func (s *Store) MergeByName(newID, name string) {
	trimmed := strings.TrimSpace(name)
	if newID == "" || trimmed == "" {
		return
	}
	s.mu.Lock()
	var moved int
	now := time.Now()
	for oldID, list := range s.messages {
		if oldID == newID || len(list) == 0 {
			continue
		}
		if d, ok := s.devices[oldID]; ok && now.Sub(time.UnixMilli(d.LastSeen)) < 20*time.Second {
			continue
		}
		if strings.TrimSpace(list[len(list)-1].DeviceName) != trimmed {
			continue
		}
		for _, m := range list {
			m.DeviceID = newID
			m.DeviceName = name
		}
		s.messages[newID] = append(s.messages[newID], list...)
		delete(s.messages, oldID)
		moved += len(list)
	}
	if moved > 0 {
		sort.SliceStable(s.messages[newID], func(i, j int) bool {
			return s.messages[newID][i].TS < s.messages[newID][j].TS
		})
	}
	s.mu.Unlock()
	if moved > 0 {
		s.Save()
	}
}

func (s *Store) Device(id string) *Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if d, ok := s.devices[id]; ok {
		cp := *d
		return &cp
	}
	// 离线历史会话（重启后无在线设备记录）也可作为发送目标，进入队列
	if msgs := s.messages[id]; len(msgs) > 0 {
		name := msgs[len(msgs)-1].DeviceName
		return &Device{ID: id, Name: name}
	}
	return nil
}

func (s *Store) PruneDevices() {
	cut := time.Now().Add(-20 * time.Second).UnixMilli()
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, d := range s.devices {
		if d.LastSeen < cut {
			delete(s.devices, id)
		}
	}
}

type DeviceView struct {
	Device
	Online   bool   `json:"online"`
	LastText string `json:"lastText,omitempty"`
	LastTS   int64  `json:"lastTs,omitempty"`
	Queued   int    `json:"queued"`
}

// Devices 返回在线设备 ∪ 有历史消息的离线会话。
func (s *Store) Devices() []DeviceView {
	s.mu.RLock()
	defer s.mu.RUnlock()

	seen := make(map[string]bool, len(s.devices))
	out := make([]DeviceView, 0, len(s.devices)+len(s.messages))
	for _, d := range s.devices {
		seen[d.ID] = true
		v := DeviceView{Device: *d}
		v.Online = time.Since(time.UnixMilli(d.LastSeen)) < 20*time.Second
		if msgs := s.messages[d.ID]; len(msgs) > 0 {
			last := msgs[len(msgs)-1]
			if last.Kind == "file" {
				v.LastText = "[文件] " + last.FileName
			} else {
				v.LastText = last.Text
			}
			v.LastTS = last.TS
		}
		v.Queued = countQueued(s.messages[d.ID])
		out = append(out, v)
	}
	for id, msgs := range s.messages {
		if seen[id] || len(msgs) == 0 {
			continue
		}
		last := msgs[len(msgs)-1]
		v := DeviceView{
			Device: Device{
				ID:       id,
				Name:     last.DeviceName,
				Address:  "",
				LastSeen: 0,
			},
			Online: false,
			Queued: countQueued(msgs),
		}
		if last.Kind == "file" {
			v.LastText = "[文件] " + last.FileName
		} else {
			v.LastText = last.Text
		}
		v.LastTS = last.TS
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].LastTS != out[j].LastTS {
			return out[i].LastTS > out[j].LastTS
		}
		return out[i].Name < out[j].Name
	})
	return out
}

func countQueued(msgs []*Message) int {
	n := 0
	for _, m := range msgs {
		if m.Dir == "out" && m.Status == "queued" {
			n++
		}
	}
	return n
}

func (s *Store) AddMessage(m *Message) {
	s.mu.Lock()
	s.messages[m.DeviceID] = append(s.messages[m.DeviceID], m)
	s.mu.Unlock()
	s.Save()
}

// UpdateMessage 更新并持久化（用于状态变化）。
func (s *Store) UpdateMessage(id string, fn func(*Message)) {
	s.mu.Lock()
	found := false
	for _, list := range s.messages {
		for _, m := range list {
			if m.ID == id {
				fn(m)
				found = true
				break
			}
		}
		if found {
			break
		}
	}
	s.mu.Unlock()
	if found {
		s.Save()
	}
}

// UpdateProgress 仅更新进度，不落盘（高频调用）。
func (s *Store) UpdateProgress(id string, pct int) {
	s.mu.Lock()
	for _, list := range s.messages {
		for _, m := range list {
			if m.ID == id {
				m.Progress = pct
				s.mu.Unlock()
				return
			}
		}
	}
	s.mu.Unlock()
}

func (s *Store) Messages(deviceID string) []Message {
	s.mu.RLock()
	defer s.mu.RUnlock()
	src := s.messages[deviceID]
	out := make([]Message, len(src))
	for i, m := range src {
		out[i] = *m
	}
	return out
}

func (s *Store) AllMessages() map[string][]Message {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string][]Message, len(s.messages))
	for k, list := range s.messages {
		cp := make([]Message, len(list))
		for i, m := range list {
			cp[i] = *m
		}
		out[k] = cp
	}
	return out
}

func (s *Store) QueuedFor(deviceID string) []Message {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []Message
	for _, m := range s.messages[deviceID] {
		if m.Dir == "out" && m.Status == "queued" {
			out = append(out, *m)
		}
	}
	return out
}

// ---- 待接收文件（会话内确认） ----

func (s *Store) AddPending(req TransferRequest, conn net.Conn) *pendingReq {
	p := &pendingReq{
		view: PendingView{
			TransferID: req.TransferID,
			DeviceID:   req.Sender.ID,
			DeviceName: req.Sender.Name,
			FileName:   req.FileName,
			FileSize:   req.FileSize,
			MimeType:   req.MimeType,
		},
		conn: conn,
		req:  req,
		ch:   make(chan bool, 1),
	}
	s.mu.Lock()
	s.pending[req.TransferID] = p
	s.mu.Unlock()
	return p
}

func (s *Store) PendingViews() []PendingView {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]PendingView, 0, len(s.pending))
	for _, p := range s.pending {
		out = append(out, p.view)
	}
	return out
}

func (s *Store) ResolvePending(id string, accept bool) {
	s.mu.Lock()
	p, ok := s.pending[id]
	if ok {
		delete(s.pending, id)
	}
	s.mu.Unlock()
	if ok {
		select {
		case p.ch <- accept:
		default:
		}
	}
}

// ---------------- 持久化 ----------------

type persisted struct {
	Version  int                   `json:"version"`
	Messages map[string][]*Message `json:"messages"`
}

func (s *Store) Save() {
	s.mu.RLock()
	data := persisted{Version: 1, Messages: make(map[string][]*Message, len(s.messages))}
	for k, list := range s.messages {
		cp := make([]*Message, 0, len(list))
		for _, m := range list {
			mm := *m
			cp = append(cp, &mm)
		}
		data.Messages[k] = cp
	}
	file := s.dataFile
	s.mu.RUnlock()
	if file == "" {
		return
	}
	b, err := json.Marshal(data)
	if err != nil {
		return
	}
	s.saveMu.Lock()
	defer s.saveMu.Unlock()
	_ = os.MkdirAll(filepath.Dir(file), 0o755)
	tmp := file + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return
	}
	_ = os.Rename(tmp, file)
}

func (s *Store) Load() {
	if s.dataFile == "" {
		return
	}
	b, err := os.ReadFile(s.dataFile)
	if err != nil {
		return
	}
	var data persisted
	if err := json.Unmarshal(b, &data); err != nil {
		return
	}
	s.mu.Lock()
	for k, list := range data.Messages {
		for _, m := range list {
			switch m.Status {
			case "sending", "pending":
				m.Status = "queued"
			}
			if m.Dir == "out" && m.Status == "queued" && m.Kind == "file" {
				if _, err := os.Stat(m.LocalPath); err != nil {
					m.Status = "failed"
				}
			}
		}
		s.messages[k] = list
	}
	s.mu.Unlock()
}
