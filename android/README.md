# Mosend — 局域网聊天式文件互传（Android / COUI 风格）

零配置、无账号、无云端的局域网直传 App。手机与手机、手机与电脑在同一 Wi-Fi 下即可互相发现，并以**聊天会话**的形式发送文本与文件。

## 功能

- **自动发现**：UDP 广播 + 4 秒心跳，20 秒无心跳自动下线；含在线/离线状态。
- **聊天式会话**：每个设备一个会话，文本消息即时双向投递（无需确认、不落 txt），文件消息以气泡呈现。
- **文件互传**：TCP 长连接，64 KiB 分片，实时进度/速度；接收可弹窗确认或自动接受。
- **按类型打开**：接收到的文件通过 FileProvider + `ACTION_VIEW` 交给系统合适应用打开。
- **前台常驻**：前台 Service 保证退到后台/锁屏仍可发现设备、接收消息与文件（实测切后台后仍响应）。
- **持久化**：会话消息与传输记录分别 JSON 落盘，重启恢复。
- **保存目录**：支持系统文件夹选择器（SAF）指定任意可写目录；也可一键恢复为应用私有目录。

## 技术架构

```
service/TransferForegroundService.kt  # 前台常驻 + 常驻通知（dataSync）
model/
  Device.kt        # Device / DeviceType / Capability / DeviceInfo
  Transfer.kt      # TransferItem / 状态枚举
  Message.kt       # ChatMessage（TEXT/FILE 气泡）
network/
  DiscoveryService.kt  # UDP 38671 发现，StateFlow<List<Device>>
  Protocol.kt          # NetworkMessage 密封类 + 端口/分片配置
transfer/
  TransferEngine.kt    # TCP 38672 收发；文本→会话，文件→落盘+气泡
  TransferStore.kt     # 文件传输记录仓库
  MessageStore.kt      # 会话消息仓库（StateFlow + JSON）
  FileUtils.kt         # URI 复制 / 按 MIME 打开 / 分享（FileProvider）
ui/
  screen/HomeScreen.kt    # 会话列表 + 传输记录（可打开）
  screen/ChatScreen.kt    # 聊天界面（气泡/输入栏/附件）
  screen/SettingsScreen.kt
  components/CommonComponents.kt
  navigation/NavGraph.kt
```

## 网络协议

| 端口 | 协议 | 用途 |
|------|------|------|
| 38671 | UDP | 设备发现（Discover / DiscoverResponse） |
| 38672 | TCP | 文本与文件传输 |

分帧：`4 字节大端长度 + UTF-8 JSON`。

- 文本：`TransferRequest(isText)→ TransferAck → TextMessage → TransferFinished`，接收端直接生成聊天消息气泡。
- 文件：`TransferRequest → TransferAck → FileChunk×N(Base64) → TransferFinished`，接收端落盘并生成可点击的文件气泡。

## 构建

```bash
cd file-transfer

# Debug
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release（已配置签名，密钥在 keystore/，密码在 keystore.properties）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 已复制为 Mosend-release.apk
```

签名证书：CN=Mosend（SHA-256 `cfdf6b242abab2c01ba0fdea8cd88bbd846ee8a671ca669a5ef7de7483575d99`）。
请妥善保管 `keystore/mosend-release.jks` 与 `keystore.properties`，后续升级必须使用同一密钥。

本机（ARM64 + aapt2 经 qemu-x86_64 包装）已构建通过。

## 实测记录（真机 + Python 模拟对端）

1. **前台常驻**：`dumpsys activity services` 显示 `isForeground=true`、类型 `dataSync`、常驻通知；进程 cgroup 为 `/foreground`；在应用退到后台后，UDP 发现仍即时响应。
2. **聊天文本**：Python 以 `isText` 方式发送两条文本，接收端 ack 通过，会话列表显示最后一条预览，会话内呈现左右气泡（无 txt 文件生成）。
3. **文件 + 打开**：Python 发送 509 B PNG，应用弹「收到文件」→ 接收 → 文件落盘为合法 `PNG 240x160`；会话中出现文件气泡「test_chat.png · 509 B · 点击打开」；点击后系统弹出「打开方式」选择器（按 MIME 分发）。

## 电脑端对接

已提供可用的 Windows 桌面客户端（Go 实现，协议完全一致）：
`/workspace/ft-desktop/dist/`
- 安装包：`Mosend-Setup-1.2.0-amd64.exe` / `-arm64.exe`
- 免安装：`Mosend-windows-amd64.exe`、`Mosend-1.2.0-portable.zip`
- 详见 `ft-desktop/README.md`（支持会话持久化与离线消息队列）。

也可以按上述分帧自行实现：`ft_chat.py`、`ft_probe.py` 为最小参考。

## 已知限制 / 后续

- 会话为一对一、基于设备 ID；暂不支持群发与消息撤回。
- 离线设备可查看历史但无法发送（需要对方在线）。
- 可继续扩展：断点续传、多线程分片、桌面端完整客户端、剪贴板同步、未读角标。
