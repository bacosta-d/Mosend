#!/usr/bin/env python3
"""Go 桌面端联调：发现、双向文本/文件、离线队列与重试。"""
import base64
import json
import os
import socket
import struct
import threading
import time
import urllib.request

DISC_PORT = 39971
TCP_PORT = 39972
HTTP = "http://127.0.0.1:39980"
RECV_DIR = "/tmp/peer_recv"
GO_DL = "/tmp/ft-dl"

PEER = {
    "id": "peer-001",
    "name": "PyPeer",
    "deviceType": "DESKTOP",
    "capabilities": ["FILE_TRANSFER", "TEXT_TRANSFER"],
    "version": "1.0.0",
    "avatarHash": None,
}

results = []
received_texts = []
received_files = []
srv_sock = None
srv_running = False


def frame(obj):
    b = json.dumps(obj, ensure_ascii=False).encode()
    return struct.pack(">I", len(b)) + b


def read_frame(sock):
    hdr = b""
    while len(hdr) < 4:
        c = sock.recv(4 - len(hdr))
        if not c:
            return None
        hdr += c
    (n,) = struct.unpack(">I", hdr)
    body = b""
    while len(body) < n:
        c = sock.recv(n - len(body))
        if not c:
            return None
        body += c
    return json.loads(body.decode())


def recv_loop(conn):
    try:
        conn.settimeout(120)
        req = read_frame(conn)
        if not req or req.get("type") != "transfer_request":
            return
        tid = req["transferId"]
        if req.get("isText"):
            conn.sendall(frame({"type": "transfer_ack", "transferId": tid, "accepted": True, "message": None}))
            while True:
                m = read_frame(conn)
                if not m:
                    break
                if m.get("type") == "text_message":
                    received_texts.append(m.get("text"))
                elif m.get("type") == "transfer_finished":
                    break
        else:
            conn.sendall(frame({"type": "transfer_ack", "transferId": tid, "accepted": True, "message": None}))
            os.makedirs(RECV_DIR, exist_ok=True)
            path = os.path.join(RECV_DIR, os.path.basename(req.get("fileName") or "recv.bin"))
            data = b""
            while True:
                m = read_frame(conn)
                if not m:
                    break
                if m.get("type") == "file_chunk":
                    if m.get("last"):
                        break
                    data += base64.b64decode(m.get("data") or "")
                elif m.get("type") == "transfer_finished":
                    break
            with open(path, "wb") as f:
                f.write(data)
            received_files.append((path, len(data), data))
    except Exception as e:
        print("peer recv error:", e)
    finally:
        try:
            conn.close()
        except Exception:
            pass


def start_server(bind_ip="127.0.0.2"):
    global srv_sock, srv_running
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((bind_ip, TCP_PORT))
    s.listen(8)
    srv_sock = s
    srv_running = True

    def loop():
        while srv_running:
            try:
                c, _ = s.accept()
            except OSError:
                return
            threading.Thread(target=recv_loop, args=(c,), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()


def stop_server():
    global srv_sock, srv_running
    srv_running = False
    if srv_sock:
        try:
            srv_sock.close()
        except Exception:
            pass
    srv_sock = None


def http_json(path, payload=None, method=None):
    data = None
    headers = {}
    if payload is not None:
        if isinstance(payload, (dict, list)):
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        else:
            data = payload
    req = urllib.request.Request(HTTP + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())


def http_multipart(path, fields, files):
    boundary = "----ftboundary"
    body = b""
    for k, v in fields.items():
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, k, v)).encode()
    for name, filename, content in files:
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: application/octet-stream\r\n\r\n" % (boundary, name, filename)).encode()
        body += content + b"\r\n"
    body += ("--%s--\r\n" % boundary).encode()
    req = urllib.request.Request(HTTP + path, data=body, method="POST",
                                 headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def announce_info(ip, info):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((ip, 0))
    payload = json.dumps({"type": "discover_response", "info": info, "port": DISC_PORT}, ensure_ascii=False).encode()
    s.sendto(payload, ("127.0.0.1", DISC_PORT))
    s.close()


def announce():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.2", 0))
    payload = json.dumps({"type": "discover_response", "info": PEER, "port": DISC_PORT}, ensure_ascii=False).encode()
    s.sendto(payload, ("127.0.0.1", DISC_PORT))
    s.settimeout(3)
    s.sendto(json.dumps({"type": "discover", "info": PEER}, ensure_ascii=False).encode(), ("127.0.0.1", DISC_PORT))
    try:
        data, _ = s.recvfrom(8192)
        msg = json.loads(data.decode())
        ok = msg.get("type") == "discover_response" and msg.get("info", {}).get("id")
        results.append(("Go 回复 DiscoverResponse", bool(ok), msg.get("info", {}).get("name")))
    except Exception as e:
        results.append(("Go 回复 DiscoverResponse", False, str(e)))
    s.close()


def py_send_text(text):
    s = socket.create_connection(("127.0.0.1", TCP_PORT), timeout=20)
    tid = "py-text-%d" % int(time.time() * 1000)
    s.sendall(frame({"type": "transfer_request", "transferId": tid, "fileName": "",
                     "fileSize": len(text.encode()), "mimeType": "text/plain",
                     "sender": PEER, "isText": True}))
    ack = read_frame(s)
    ok = bool(ack and ack.get("accepted"))
    s.sendall(frame({"type": "text_message", "transferId": tid, "text": text, "sender": PEER}))
    s.sendall(frame({"type": "transfer_finished", "transferId": tid, "success": True}))
    s.close()
    return ok


def py_send_file(name, content):
    s = socket.create_connection(("127.0.0.1", TCP_PORT), timeout=20)
    tid = "py-file-%d" % int(time.time() * 1000)
    s.sendall(frame({"type": "transfer_request", "transferId": tid, "fileName": name,
                     "fileSize": len(content), "mimeType": "text/plain",
                     "sender": PEER, "isText": False}))
    ack = read_frame(s)
    ok = bool(ack and ack.get("accepted"))
    off = 0
    while off < len(content):
        piece = content[off:off + 4096]
        s.sendall(frame({"type": "file_chunk", "transferId": tid, "offset": off,
                         "data": base64.b64encode(piece).decode(), "last": False}))
        off += len(piece)
    s.sendall(frame({"type": "file_chunk", "transferId": tid, "offset": off, "data": "", "last": True}))
    s.sendall(frame({"type": "transfer_finished", "transferId": tid, "success": True}))
    s.close()
    return ok, tid


def msg_status(device_id, text):
    st = http_json("/api/state")
    for m in st.get("messages", {}).get(device_id, []):
        if m.get("kind") == "text" and m.get("text") == text:
            return m.get("status")
    return None


def main():
    os.makedirs(RECV_DIR, exist_ok=True)
    start_server()
    time.sleep(0.5)

    announce()
    time.sleep(0.8)
    st = http_json("/api/state")
    devs = st.get("devices", [])
    results.append(("Go 发现 Python 设备", any(d.get("id") == PEER["id"] for d in devs),
                    ", ".join(d.get("name", "") for d in devs)))

    http_json("/api/autoaccept", {"value": True})
    time.sleep(0.3)

    # Go -> Python 文本
    r = http_json("/api/send_text", {"deviceId": PEER["id"], "text": "hello from Go desktop"})
    time.sleep(1.2)
    results.append(("Go→Python 文本", bool(r.get("ok")) and received_texts[-1:] == ["hello from Go desktop"], str(received_texts)))

    # Go -> Python 文件
    payload = b"GO-DESKTOP-FILE-" + bytes(range(0, 255)) * 4
    r = http_multipart("/api/upload", {"deviceId": PEER["id"]}, [("files", "from_go.bin", payload)])
    time.sleep(1.5)
    got = received_files[-1] if received_files else (None, 0, b"")
    results.append(("Go→Python 文件", bool(r.get("ok")) and got[2] == payload, "%s %d bytes" % (got[0], got[1])))

    # Python -> Go 文本
    ok = py_send_text("你好，来自桌面端测试的消息")
    time.sleep(1.0)
    st = http_json("/api/state")
    msgs = st.get("messages", {}).get(PEER["id"], [])
    in_text = any(m.get("dir") == "in" and m.get("kind") == "text" and m.get("text") == "你好，来自桌面端测试的消息" for m in msgs)
    results.append(("Python→Go 文本", ok and in_text, "msg count=%d" % len(msgs)))

    # Python -> Go 文件
    content = b"PY-TO-GO-" + b"x" * 300
    ok, tid = py_send_file("py_to_go.bin", content)
    time.sleep(1.5)
    st = http_json("/api/state")
    saved = None
    for m in st.get("messages", {}).get(PEER["id"], []):
        if m.get("kind") == "file" and m.get("fileName") == "py_to_go.bin":
            saved = (m.get("localPath"), m.get("status"))
    disk_ok = bool(saved and saved[0] and os.path.exists(saved[0]) and open(saved[0], "rb").read() == content)
    results.append(("Python→Go 文件", ok and disk_ok, str(saved)))

    # ---- 离线队列：使用不可达地址的对端（127.0.0.3 暂无监听） ----
    peer_off = {"id": "peer-offline", "name": "OfflinePeer", "deviceType": "DESKTOP",
                "capabilities": ["FILE_TRANSFER", "TEXT_TRANSFER"], "version": "1.0.0", "avatarHash": None}
    announce_info("127.0.0.3", peer_off)
    time.sleep(0.8)
    queued_text = "QUEUED-OFFLINE-消息"
    r = http_json("/api/send_text", {"deviceId": "peer-offline", "text": queued_text})
    time.sleep(1.2)
    st_queued = msg_status("peer-offline", queued_text)
    results.append(("离线消息进入队列", bool(r.get("ok")) and st_queued in ("queued", "sending"), "status=%s" % st_queued))

    # 让对端地址变为可达，再触发补发
    start_server("127.0.0.3")
    time.sleep(0.6)
    http_json("/api/flush", {"deviceId": "peer-offline"})
    time.sleep(2.0)
    st_sent = msg_status("peer-offline", queued_text)
    delivered = queued_text in received_texts
    results.append(("恢复后自动补发", st_sent == "sent" and delivered, "status=%s delivered=%s" % (st_sent, delivered)))

    print("\n===== 联调结果 =====")
    allpass = True
    for name, ok, detail in results:
        print(("PASS " if ok else "FAIL ") + name + "  |  " + str(detail))
        allpass = allpass and ok
    print("====================")
    print("ALL PASS" if allpass else "SOME FAILED")
    return 0 if allpass else 1


if __name__ == "__main__":
    raise SystemExit(main())
