package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

//go:embed web/index.html
var webFS embed.FS

func main() {
	var (
		name      = flag.String("name", "", "设备名称（默认主机名）")
		discovery = flag.Int("discovery-port", 38671, "UDP 设备发现端口")
		transfer  = flag.Int("transfer-port", 38672, "TCP 文件传输端口")
		httpPort  = flag.Int("http-port", 38680, "本地界面端口（127.0.0.1）")
		download  = flag.String("download", "", "接收文件保存目录")
		bind      = flag.String("bind", "0.0.0.0", "TCP 监听地址")
		dataFile  = flag.String("data", "", "会话数据文件（默认用户配置目录）")
		noBrowser = flag.Bool("no-browser", false, "不自动打开浏览器")
	)
	flag.Parse()

	cfgDir := configDir()
	downloadDir := *download
	if downloadDir == "" {
		home, _ := os.UserHomeDir()
		downloadDir = filepath.Join(home, "Downloads", "Mosend")
	}
	_ = os.MkdirAll(downloadDir, 0o755)

	outboxDir := filepath.Join(cfgDir, "outbox")
	_ = os.MkdirAll(outboxDir, 0o755)

	dataPath := *dataFile
	if dataPath == "" {
		dataPath = filepath.Join(cfgDir, "data.json")
	}

	deviceName := *name
	if deviceName == "" {
		if h, err := os.Hostname(); err == nil && h != "" {
			deviceName = h
		} else {
			deviceName = "Windows PC"
		}
	}

	me := DeviceInfo{
		ID:           loadOrCreateID(),
		Name:         deviceName,
		DeviceType:   "DESKTOP",
		Capabilities: []string{"FILE_TRANSFER", "TEXT_TRANSFER"},
		Version:      "1.2.0-windows",
	}
	store := newStore(me, downloadDir, outboxDir, dataPath, *transfer, *discovery)
	store.Load()

	disc := newDiscovery(store, *discovery)
	if err := disc.Start(); err != nil {
		log.Printf("警告: %v", err)
	}

	ts := newTransferServer(store, *transfer, *bind)
	if err := ts.Start(); err != nil {
		log.Printf("警告: %v", err)
	}

	// 设备上线时自动补发离线队列
	store.SetOnlineHook(func(deviceID string) {
		ts.TrySendQueued(store.Device(deviceID))
	})
	// 周期性兜底重试（网络恢复等场景）
	go func() {
		t := time.NewTicker(15 * time.Second)
		defer t.Stop()
		for range t.C {
			for _, dv := range store.Devices() {
				if dv.Online {
					dev := dv.Device
					ts.TrySendQueued(&dev)
				}
			}
		}
	}()

	addr := fmt.Sprintf("127.0.0.1:%d", *httpPort)
	url := "http://" + addr + "/"
	srv := &http.Server{Handler: buildHandler(store, ts)}
	ln, err := net.Listen("tcp4", addr)
	if err != nil {
		log.Fatalf("本地界面端口 %d 启动失败: %v", *httpPort, err)
	}
	log.Printf("Mosend 界面地址: %s", url)
	log.Printf("接收目录: %s", downloadDir)
	log.Printf("离线队列目录: %s", outboxDir)
	log.Printf("会话数据: %s", dataPath)
	log.Printf("设备名称: %s (%s)", deviceName, me.ID)

	if !*noBrowser {
		go func() {
			time.Sleep(400 * time.Millisecond)
			openBrowser(url)
		}()
	}
	log.Fatal(srv.Serve(ln))
}

func buildHandler(store *Store, ts *TransferServer) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		data, err := webFS.ReadFile("web/index.html")
		if err != nil {
			http.Error(w, "ui missing", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(data)
	})

	mux.HandleFunc("/api/state", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{
			"me":          store.Me(),
			"devices":     store.Devices(),
			"messages":    store.AllMessages(),
			"pending":     store.PendingViews(),
			"autoAccept":  store.AutoAccept(),
			"downloadDir": store.DownloadDir(),
			"outboxDir":   store.OutboxDir(),
			"ports": map[string]int{
				"discovery": store.discoveryPort,
				"transfer":  store.transferPort,
			},
		})
	})

	// 发送文本：离线时进入队列
	mux.HandleFunc("/api/send_text", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			DeviceID string `json:"deviceId"`
			Text     string `json:"text"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeErr(w, err)
			return
		}
		body.Text = strings.TrimSpace(body.Text)
		if body.Text == "" {
			writeErr(w, fmt.Errorf("消息为空"))
			return
		}
		dev := store.Device(body.DeviceID)
		if dev == nil {
			writeErr(w, fmt.Errorf("未知设备"))
			return
		}
		m := &Message{
			ID:         "m:" + newID(),
			DeviceID:   dev.ID,
			DeviceName: dev.Name,
			Dir:        "out",
			Kind:       "text",
			Text:       body.Text,
			Status:     "queued",
			TS:         time.Now().UnixMilli(),
		}
		store.AddMessage(m)
		if dev.Address != "" {
			d := *dev
			go ts.TrySendQueued(&d)
		}
		writeJSON(w, map[string]any{"ok": true, "queued": dev.Address == ""})
	})

	// 上传文件：先存到持久 outbox，再排队发送
	mux.HandleFunc("/api/upload", func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(256 << 20); err != nil {
			writeErr(w, err)
			return
		}
		dev := store.Device(r.FormValue("deviceId"))
		if dev == nil {
			writeErr(w, fmt.Errorf("未知设备"))
			return
		}
		files := r.MultipartForm.File["files"]
		if len(files) == 0 {
			writeErr(w, fmt.Errorf("没有文件"))
			return
		}
		queued := 0
		for _, fh := range files {
			p, err := saveOutgoing(store.OutboxDir(), fh)
			if err != nil {
				writeErr(w, err)
				return
			}
			st, _ := os.Stat(p)
			var size int64
			if st != nil {
				size = st.Size()
			}
			name := filepath.Base(p)
			m := &Message{
				ID:         "t:" + newID(),
				DeviceID:   dev.ID,
				DeviceName: dev.Name,
				Dir:        "out",
				Kind:       "file",
				FileName:   name,
				FileSize:   size,
				MimeType:   guessMime(name),
				LocalPath:  p,
				Status:     "queued",
				TS:         time.Now().UnixMilli(),
			}
			store.AddMessage(m)
			queued++
		}
		if dev.Address != "" {
			d := *dev
			go ts.TrySendQueued(&d)
		}
		writeJSON(w, map[string]any{"ok": true, "count": queued, "queued": dev.Address == ""})
	})

	// 手动重试离线队列（可选）
	mux.HandleFunc("/api/flush", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			DeviceID string `json:"deviceId"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if dev := store.Device(body.DeviceID); dev != nil {
			d := *dev
			go ts.TrySendQueued(&d)
			writeJSON(w, map[string]any{"ok": true})
			return
		}
		writeErr(w, fmt.Errorf("未知设备"))
	})

	mux.HandleFunc("/api/accept", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			TransferID string `json:"transferId"`
			Accept     bool   `json:"accept"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeErr(w, err)
			return
		}
		store.ResolvePending(body.TransferID, body.Accept)
		writeJSON(w, map[string]any{"ok": true})
	})

	mux.HandleFunc("/api/autoaccept", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Value bool `json:"value"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeErr(w, err)
			return
		}
		store.SetAutoAccept(body.Value)
		writeJSON(w, map[string]any{"ok": true})
	})

	return mux
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, err error) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusBadRequest)
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": false, "error": err.Error()})
}

func saveOutgoing(dir string, fh *multipart.FileHeader) (string, error) {
	src, err := fh.Open()
	if err != nil {
		return "", err
	}
	defer src.Close()

	_ = os.MkdirAll(dir, 0o755)
	name := filepath.Base(strings.ReplaceAll(fh.Filename, "\\", "/"))
	if name == "" || name == "." || name == ".." {
		name = fmt.Sprintf("file_%d", time.Now().UnixNano())
	}
	dstPath := uniquePath(dir, name)
	dst, err := os.Create(dstPath)
	if err != nil {
		return "", err
	}
	defer dst.Close()
	if _, err := io.Copy(dst, src); err != nil {
		return "", err
	}
	return dstPath, nil
}

func uniquePath(dir, name string) string {
	p := filepath.Join(dir, name)
	if _, err := os.Stat(p); os.IsNotExist(err) {
		return p
	}
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 1; ; i++ {
		cand := filepath.Join(dir, fmt.Sprintf("%s_%d%s", stem, i, ext))
		if _, err := os.Stat(cand); os.IsNotExist(err) {
			return cand
		}
	}
}

func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}

func configDir() string {
	base, err := os.UserConfigDir()
	if err != nil || base == "" {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, ".config")
	}
	migrateConfig(base)
	return filepath.Join(base, "Mosend")
}

// migrateConfig 将旧版 FileTransfer 的用户数据迁移到 Mosend。
func migrateConfig(base string) {
	oldDir := filepath.Join(base, "FileTransfer")
	newDir := filepath.Join(base, "Mosend")
	if _, err := os.Stat(newDir); err == nil {
		return
	}
	if _, err := os.Stat(oldDir); err != nil {
		return
	}
	if err := os.Rename(oldDir, newDir); err == nil {
		return
	}
	// 跨设备/权限受限时退化为复制
	_ = filepath.Walk(oldDir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil {
			return nil
		}
		rel, relErr := filepath.Rel(oldDir, path)
		if relErr != nil {
			return nil
		}
		dst := filepath.Join(newDir, rel)
		if info.IsDir() {
			_ = os.MkdirAll(dst, 0o755)
			return nil
		}
		src, openErr := os.Open(path)
		if openErr != nil {
			return nil
		}
		defer src.Close()
		_ = os.MkdirAll(filepath.Dir(dst), 0o755)
		out, createErr := os.Create(dst)
		if createErr != nil {
			return nil
		}
		_, _ = io.Copy(out, src)
		_ = out.Close()
		return nil
	})
}

func loadOrCreateID() string {
	path := filepath.Join(configDir(), "desktop-id.txt")
	if b, err := os.ReadFile(path); err == nil {
		s := strings.TrimSpace(string(b))
		if len(s) >= 8 {
			return s
		}
	}
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err == nil {
		id := hex.EncodeToString(buf)
		_ = os.MkdirAll(filepath.Dir(path), 0o755)
		_ = os.WriteFile(path, []byte(id), 0o644)
		return id
	}
	return fmt.Sprintf("%016x", time.Now().UnixNano())
}

func newID() string {
	buf := make([]byte, 8)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}
