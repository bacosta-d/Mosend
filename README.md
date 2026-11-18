# Mosend

局域网聊天式文件互传：**Android ↔ Android / Android ↔ Windows**，同一 Wi-Fi 下自动发现、直接传输，无需账号与云端。

## 特性

- 设备自动发现（UDP 广播 + 心跳，20 秒离线判定）
- 聊天式会话：文本即时双向投递，文件以气泡呈现
- 文件传输（TCP 分片 + 进度）、按类型打开
- 离线队列：对方离线时排队，上线后自动补发
- 会话本地持久化（Android / 桌面端各自存储）
- 后台常驻（Android 前台服务；Windows 常驻进程）
- 前台/桌面双端同一协议，可与任意实现互通

## 目录

```
android/    Android 客户端（Kotlin + Jetpack Compose）
desktop/    Windows/Linux 桌面端（Go + 内嵌 Web UI）
```

## 协议

| 端口 | 协议 | 用途 |
|------|------|------|
| 38671 | UDP | 设备发现（discover / discover_response） |
| 38672 | TCP | 文本与文件传输（4 字节大端长度 + JSON 分帧） |

消息类型：`discover`、`discover_response`、`transfer_request`、`transfer_ack`、`file_chunk`（Base64）、`text_message`、`transfer_finished`。

## 构建

### Android

```bash
cd android
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 需在根目录放置 keystore.properties 与 keystore/（本地私钥，勿提交）
```

### 桌面端

```bash
cd desktop
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/Mosend-windows-amd64.exe .
# 安装包（需 NSIS）
makensis -DARCH=amd64 -DAPPVERSION=1.2.0 installer.nsi
```

## 使用

1. 手机与电脑连接同一 Wi-Fi。
2. Android 安装 APK 并打开；Windows 运行安装包或免安装版 exe（首次放行防火墙专用网络）。
3. 在设备列表选择对端进入会话，即可发送文本与文件。
4. 桌面端接收目录默认 `%USERPROFILE%\Downloads\Mosend`，会话数据在 `%APPDATA%\Mosend`。

## 注意

- 本仓库不包含签名密钥与构建产物。
- 文本/文件为局域网直传，不经服务器中转。
