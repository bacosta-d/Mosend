package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const chunkSize = 64 * 1024

var errRejected = errors.New("对方拒绝接收")

type TransferServer struct {
	store *Store
	port  int
	bind  string
	ln    net.Listener

	mu       sync.Mutex
	flushing map[string]bool
}

func newTransferServer(store *Store, port int, bind string) *TransferServer {
	if bind == "" {
		bind = "0.0.0.0"
	}
	return &TransferServer{
		store:    store,
		port:     port,
		bind:     bind,
		flushing: make(map[string]bool),
	}
}

func (t *TransferServer) Start() error {
	ln, err := net.Listen("tcp4", net.JoinHostPort(t.bind, fmt.Sprint(t.port)))
	if err != nil {
		return fmt.Errorf("TCP 监听 %s:%d 失败: %w", t.bind, t.port, err)
	}
	t.ln = ln
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go t.handleIncoming(c)
		}
	}()
	log.Printf("文件传输服务已启动: TCP %s:%d", t.bind, t.port)
	return nil
}

// ---------------- 接收 ----------------

func (t *TransferServer) handleIncoming(conn net.Conn) {
	defer func() { _ = conn.Close() }()
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Minute))

	frame, err := readFrame(conn)
	if err != nil {
		return
	}
	typ, err := decodeType(frame)
	if err != nil || typ != msgTransferRequest {
		return
	}
	var req TransferRequest
	if err := json.Unmarshal(frame, &req); err != nil {
		return
	}
	t.store.UpsertDevice(req.Sender, remoteIP(conn))

	if req.IsText {
		t.receiveText(conn, req)
		return
	}

	accepted := t.store.AutoAccept()
	if !accepted {
		p := t.store.AddPending(req, conn)
		select {
		case accepted = <-p.ch:
		case <-time.After(10 * time.Minute):
			accepted = false
		}
	}
	if !accepted {
		_ = writeFrame(conn, TransferAck{Type: msgTransferAck, TransferID: req.TransferID, Accepted: false, Message: strp("Declined")})
		return
	}
	_ = writeFrame(conn, TransferAck{Type: msgTransferAck, TransferID: req.TransferID, Accepted: true})

	msg := &Message{
		ID:         "t:" + req.TransferID,
		DeviceID:   req.Sender.ID,
		DeviceName: req.Sender.Name,
		Dir:        "in",
		Kind:       "file",
		FileName:   req.FileName,
		FileSize:   req.FileSize,
		MimeType:   req.MimeType,
		Status:     "sending",
		TS:         time.Now().UnixMilli(),
	}
	t.store.AddMessage(msg)

	path := t.savePath(req.FileName)
	part := path + ".part"
	f, err := os.Create(part)
	if err != nil {
		t.store.UpdateMessage(msg.ID, func(m *Message) { m.Status = "failed" })
		return
	}
	var written int64
	bw := bufio.NewWriterSize(f, 256*1024)
loop:
	for {
		_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		fr, err := readFrame(conn)
		if err != nil {
			break
		}
		typ, _ := decodeType(fr)
		switch typ {
		case msgFileChunk:
			var c FileChunk
			if json.Unmarshal(fr, &c) != nil {
				continue
			}
			if c.Last {
				break loop
			}
			data, err := base64.StdEncoding.DecodeString(c.Data)
			if err != nil {
				continue
			}
			if _, err := bw.Write(data); err != nil {
				break loop
			}
			written += int64(len(data))
			pct := 0
			if req.FileSize > 0 {
				pct = int(written * 100 / req.FileSize)
			}
			t.store.UpdateProgress(msg.ID, pct)
		case msgTransferFinished:
			break loop
		}
	}
	_ = bw.Flush()
	_ = f.Close()
	if written > 0 {
		_ = os.Rename(part, path)
		t.store.UpdateMessage(msg.ID, func(m *Message) {
			m.Status = "delivered"
			m.Progress = 100
			m.LocalPath = path
		})
	} else {
		_ = os.Remove(part)
		t.store.UpdateMessage(msg.ID, func(m *Message) { m.Status = "failed" })
	}
}

func (t *TransferServer) receiveText(conn net.Conn, req TransferRequest) {
	_ = writeFrame(conn, TransferAck{Type: msgTransferAck, TransferID: req.TransferID, Accepted: true})
	var text string
	for {
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Minute))
		fr, err := readFrame(conn)
		if err != nil {
			break
		}
		typ, _ := decodeType(fr)
		if typ == msgTextMessage {
			var m TextMessage
			if json.Unmarshal(fr, &m) == nil {
				text = m.Text
			}
		} else if typ == msgTransferFinished {
			break
		}
	}
	if text != "" {
		t.store.AddMessage(&Message{
			ID:         "m:" + req.TransferID,
			DeviceID:   req.Sender.ID,
			DeviceName: req.Sender.Name,
			Dir:        "in",
			Kind:       "text",
			Text:       text,
			Status:     "delivered",
			TS:         time.Now().UnixMilli(),
		})
	}
}

// ---------------- 发送（离线队列） ----------------

// TrySendQueued 按顺序发送该设备的排队消息；网络失败保留排队，等待下次上线重试。
func (t *TransferServer) TrySendQueued(device *Device) {
	if device == nil || device.Address == "" {
		return
	}
	// 同一设备同一时刻只允许一个发送循环，避免重复发送
	t.mu.Lock()
	if t.flushing[device.ID] {
		t.mu.Unlock()
		return
	}
	t.flushing[device.ID] = true
	t.mu.Unlock()
	defer func() {
		t.mu.Lock()
		delete(t.flushing, device.ID)
		t.mu.Unlock()
	}()

	for {
		msgs := t.store.QueuedFor(device.ID)
		if len(msgs) == 0 {
			return
		}
		progressed := false
		for _, m := range msgs {
			var err error
			if m.Kind == "text" {
				err = t.sendTextQueued(device, m)
			} else {
				err = t.sendFileQueued(device, m)
			}
			if err != nil {
				log.Printf("发送失败(%s): %v", m.ID, err)
				if errors.Is(err, errRejected) || errors.Is(err, errFileMissing) {
					progressed = true
					continue
				}
				// 网络错误：保留队列，等待下次重试
				return
			}
			progressed = true
		}
		if !progressed {
			return
		}
	}
}

var errFileMissing = errors.New("源文件不存在")

func (t *TransferServer) sendTextQueued(device *Device, m Message) error {
	conn, err := t.dial(device)
	if err != nil {
		return err
	}
	defer conn.Close()

	t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "sending" })
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))

	req := TransferRequest{
		Type:       msgTransferRequest,
		TransferID: strings.TrimPrefix(m.ID, "m:"),
		FileName:   "",
		FileSize:   int64(len([]byte(m.Text))),
		MimeType:   "text/plain",
		Sender:     t.store.Me(),
		IsText:     true,
	}
	if err := writeFrame(conn, req); err != nil {
		t.requeue(m.ID)
		return err
	}
	ack, err := t.readAck(conn)
	if err != nil {
		t.requeue(m.ID)
		return err
	}
	if !ack.Accepted {
		t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "failed" })
		return errRejected
	}
	if err := writeFrame(conn, TextMessage{Type: msgTextMessage, TransferID: req.TransferID, Text: m.Text, Sender: t.store.Me()}); err != nil {
		t.requeue(m.ID)
		return err
	}
	_ = writeFrame(conn, TransferFinished{Type: msgTransferFinished, TransferID: req.TransferID, Success: true})
	t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "sent" })
	return nil
}

func (t *TransferServer) sendFileQueued(device *Device, m Message) error {
	if m.LocalPath == "" {
		t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "failed" })
		return errFileMissing
	}
	st, err := os.Stat(m.LocalPath)
	if err != nil {
		t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "failed" })
		return errFileMissing
	}
	conn, err := t.dial(device)
	if err != nil {
		return err
	}
	defer conn.Close()

	t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "sending" })
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))

	req := TransferRequest{
		Type:       msgTransferRequest,
		TransferID: strings.TrimPrefix(m.ID, "t:"),
		FileName:   m.FileName,
		FileSize:   st.Size(),
		MimeType:   m.MimeType,
		Sender:     t.store.Me(),
	}
	if err := writeFrame(conn, req); err != nil {
		t.requeue(m.ID)
		return err
	}
	ack, err := t.readAck(conn)
	if err != nil {
		t.requeue(m.ID)
		return err
	}
	if !ack.Accepted {
		t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "failed" })
		return errRejected
	}

	f, err := os.Open(m.LocalPath)
	if err != nil {
		t.store.UpdateMessage(m.ID, func(x *Message) { x.Status = "failed" })
		return errFileMissing
	}
	defer f.Close()

	buf := make([]byte, chunkSize)
	var sent int64
	for {
		n, rerr := f.Read(buf)
		if n > 0 {
			if err := writeFrame(conn, FileChunk{
				Type:       msgFileChunk,
				TransferID: req.TransferID,
				Offset:     sent,
				Data:       base64.StdEncoding.EncodeToString(buf[:n]),
			}); err != nil {
				t.requeue(m.ID)
				return err
			}
			sent += int64(n)
			pct := 0
			if st.Size() > 0 {
				pct = int(sent * 100 / st.Size())
			}
			t.store.UpdateProgress(m.ID, pct)
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			t.requeue(m.ID)
			return rerr
		}
	}
	_ = writeFrame(conn, FileChunk{Type: msgFileChunk, TransferID: req.TransferID, Offset: sent, Last: true})
	_ = writeFrame(conn, TransferFinished{Type: msgTransferFinished, TransferID: req.TransferID, Success: true})
	t.store.UpdateMessage(m.ID, func(x *Message) {
		x.Status = "sent"
		x.Progress = 100
	})
	return nil
}

func (t *TransferServer) requeue(msgID string) {
	t.store.UpdateMessage(msgID, func(m *Message) { m.Status = "queued" })
}

func (t *TransferServer) dial(device *Device) (net.Conn, error) {
	if device == nil {
		return nil, fmt.Errorf("设备不存在")
	}
	if device.Address == "" {
		return nil, fmt.Errorf("设备离线")
	}
	addr := net.JoinHostPort(device.Address, fmt.Sprint(t.port))
	return net.DialTimeout("tcp4", addr, 10*time.Second)
}

func (t *TransferServer) readAck(conn net.Conn) (*TransferAck, error) {
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))
	fr, err := readFrame(conn)
	if err != nil {
		return nil, err
	}
	var ack TransferAck
	if err := json.Unmarshal(fr, &ack); err != nil {
		return nil, err
	}
	return &ack, nil
}

func (t *TransferServer) savePath(name string) string {
	dir := t.store.DownloadDir()
	_ = os.MkdirAll(dir, 0o755)
	base := filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	if base == "" || base == "." || base == ".." {
		base = fmt.Sprintf("received_%d", time.Now().Unix())
	}
	target := filepath.Join(dir, base)
	if _, err := os.Stat(target); os.IsNotExist(err) {
		return target
	}
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	for i := 1; i < 10000; i++ {
		cand := filepath.Join(dir, fmt.Sprintf("%s_%d%s", stem, i, ext))
		if _, err := os.Stat(cand); os.IsNotExist(err) {
			return cand
		}
	}
	return target
}

func remoteIP(conn net.Conn) string {
	if addr, ok := conn.RemoteAddr().(*net.TCPAddr); ok {
		return addr.IP.String()
	}
	host, _, _ := net.SplitHostPort(conn.RemoteAddr().String())
	return host
}

func strp(s string) *string { return &s }
