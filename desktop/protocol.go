package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
)

// 与 Android 端 com.filetransfer.coui.network.Protocol 完全对应。
const (
	msgDiscover         = "discover"
	msgDiscoverResponse = "discover_response"
	msgTransferRequest  = "transfer_request"
	msgTransferAck      = "transfer_ack"
	msgFileChunk        = "file_chunk"
	msgTextMessage      = "text_message"
	msgTransferFinished = "transfer_finished"
)

type DeviceInfo struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	DeviceType   string   `json:"deviceType"`
	Capabilities []string `json:"capabilities"`
	Version      string   `json:"version"`
	AvatarHash   *string  `json:"avatarHash"`
}

type Discover struct {
	Type string     `json:"type"`
	Info DeviceInfo `json:"info"`
}

type DiscoverResponse struct {
	Type string     `json:"type"`
	Info DeviceInfo `json:"info"`
	Port int        `json:"port"`
}

type TransferRequest struct {
	Type       string     `json:"type"`
	TransferID string     `json:"transferId"`
	FileName   string     `json:"fileName"`
	FileSize   int64      `json:"fileSize"`
	MimeType   string     `json:"mimeType"`
	Sender     DeviceInfo `json:"sender"`
	IsText     bool       `json:"isText"`
}

type TransferAck struct {
	Type       string  `json:"type"`
	TransferID string  `json:"transferId"`
	Accepted   bool    `json:"accepted"`
	Message    *string `json:"message"`
}

type FileChunk struct {
	Type       string `json:"type"`
	TransferID string `json:"transferId"`
	Offset     int64  `json:"offset"`
	Data       string `json:"data"`
	Last       bool   `json:"last"`
}

type TextMessage struct {
	Type       string     `json:"type"`
	TransferID string     `json:"transferId"`
	Text       string     `json:"text"`
	Sender     DeviceInfo `json:"sender"`
}

type TransferFinished struct {
	Type       string  `json:"type"`
	TransferID string  `json:"transferId"`
	Success    bool    `json:"success"`
	Message    *string `json:"message"`
}

// envelope 用于先读 type 再二次解码。
type envelope struct {
	Type string `json:"type"`
}

const maxFrame = 16 * 1024 * 1024

// writeFrame: 4 字节大端长度 + UTF-8 JSON
func writeFrame(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	var hdr [4]byte
	binary.BigEndian.PutUint32(hdr[:], uint32(len(b)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	_, err = w.Write(b)
	return err
}

// readFrame 读取一帧并返回原始 JSON。
func readFrame(r io.Reader) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n == 0 || n > maxFrame {
		return nil, fmt.Errorf("invalid frame length %d", n)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

func decodeType(b []byte) (string, error) {
	var e envelope
	if err := json.Unmarshal(b, &e); err != nil {
		return "", err
	}
	return e.Type, nil
}

// guessMime 与 Android 端保持一致的常见类型映射。
func guessMime(name string) string {
	ext := ""
	for i := len(name) - 1; i >= 0; i-- {
		if name[i] == '.' {
			ext = name[i+1:]
			break
		}
	}
	switch lower(ext) {
	case "jpg", "jpeg":
		return "image/jpeg"
	case "png":
		return "image/png"
	case "gif":
		return "image/gif"
	case "webp":
		return "image/webp"
	case "mp4":
		return "video/mp4"
	case "mkv":
		return "video/x-matroska"
	case "mov":
		return "video/quicktime"
	case "mp3":
		return "audio/mpeg"
	case "wav":
		return "audio/wav"
	case "ogg":
		return "audio/ogg"
	case "pdf":
		return "application/pdf"
	case "txt":
		return "text/plain"
	case "json":
		return "application/json"
	case "xml":
		return "application/xml"
	case "zip":
		return "application/zip"
	case "rar":
		return "application/x-rar-compressed"
	case "apk":
		return "application/vnd.android.package-archive"
	default:
		return "application/octet-stream"
	}
}

func lower(s string) string {
	b := []byte(s)
	for i := range b {
		if b[i] >= 'A' && b[i] <= 'Z' {
			b[i] += 'a' - 'A'
		}
	}
	return string(b)
}
