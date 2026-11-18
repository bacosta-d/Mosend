# Mosend Desktop（Windows / Linux）

与 Android 端 **Mosend** 使用完全相同的局域网协议：

- **UDP 38671** 设备发现（`discover` / `discover_response`）
- **TCP 38672** 文本与文件传输（4 字节大端长度 + JSON 分帧）

界面为内嵌本地 Web UI（无需安装运行时），启动后自动打开浏览器。

## 安装（推荐）

运行 `Mosend-Setup-1.2.1-amd64.exe`（ARM64 设备用 `-arm64`），按向导完成：

- 默认安装到 `%LOCALAPPDATA%\Mosend`，无需管理员权限
- 自动创建开始菜单快捷方式；可选创建桌面快捷方式
- 附带卸载程序（设置 → 应用 → Mosend）

也可以直接使用免安装版 `Mosend-windows-amd64.exe`。

> 首次运行如弹出 Windows 防火墙提示，请勾选**专用网络**并允许，否则手机无法发现电脑。
>
> 如果之前安装过旧名称 FileTransfer，请先在「设置 → 应用」中卸载旧版，再安装 Mosend。

## 使用

1. 双击启动后浏览器自动打开 `http://127.0.0.1:38680/`。
2. 左侧出现同一 Wi-Fi 下的手机即表示已连通，点击进入会话。
3. 文本与文件都以聊天气泡呈现；`＋` 选择文件（支持多选）。
4. 顶部「自动接收」控制收到的文件是否需要确认。

### 本地存储

- **会话数据**：`%APPDATA%\Mosend\data.json`（消息、设备名、发送状态）
  - 关闭程序后重新打开，历史会话与消息完整保留。
  - 重启时处于「发送中」的消息会自动转为「排队中」继续重试。
- **离线队列**：`%APPDATA%\Mosend\outbox\`
  - 对方离线时发送的文本/文件会进入队列，界面显示「排队中」。
  - 对方上线或网络恢复后自动补发；文件基于队列副本，原文件删除也能补发。
- **接收文件**：`%USERPROFILE%\Downloads\Mosend`
- **设备 ID**：`%APPDATA%\Mosend\desktop-id.txt`

> 从旧版本升级时会自动把 `%APPDATA%\FileTransfer` 迁移为 `%APPDATA%\Mosend`（含会话、离线队列与设备 ID）。
> 卸载只删除程序与快捷方式，不删除上述用户数据。

## 命令行参数

```
-name              设备显示名称（默认主机名）
-discovery-port    UDP 发现端口（默认 38671，需与手机端一致）
-transfer-port     TCP 传输端口（默认 38672，需与手机端一致）
-http-port         本地界面端口（默认 38680，仅监听 127.0.0.1）
-download          接收文件保存目录
-bind              TCP 监听地址（默认 0.0.0.0）
-data              会话数据文件路径（默认用户配置目录）
-no-browser        不自动打开浏览器
```

## 构建

需要 Go 1.21+、NSIS（仅安装包需要）。

```bash
# 图标与 Windows 资源（可选）
python3 tools/gen_icon.py
printf '1 ICON "app.ico"\n' > app.rc
x86_64-w64-mingw32-windres --preprocessor=cat app.rc -O coff -o rsrc_windows_amd64.syso

# Windows 可执行文件
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/Mosend-windows-amd64.exe .
CGO_ENABLED=0 GOOS=windows GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/Mosend-windows-arm64.exe .

# 安装包
makensis -DARCH=amd64 -DAPPVERSION=1.2.1 installer.nsi
makensis -DARCH=arm64 -DAPPVERSION=1.2.1 installer.nsi
```

## 产物

| 文件 | 说明 |
|---|---|
| `dist/Mosend-Setup-1.2.1-amd64.exe` | Windows x64 安装包 |
| `dist/Mosend-Setup-1.2.1-arm64.exe` | Windows ARM64 安装包 |
| `dist/Mosend-windows-amd64.exe` | 免安装版 x64 |
| `dist/Mosend-windows-arm64.exe` | 免安装版 ARM64 |
| `dist/Mosend-1.2.1-portable.zip` | 免安装打包 |
| `dist/Mosend-linux-arm64` | Linux 构建（联调验证用） |

## 联调验证

`test_integration.py` 使用 Python 模拟对端，与桌面端双向测试：

```
PASS Go 回复 DiscoverResponse   | GoDesktop
PASS Go 发现 Python 设备        | PyPeer
PASS Go→Python 文本 / 文件      | 1036 bytes
PASS Python→Go 文本 / 文件      | delivered
PASS 离线消息进入队列           | status=queued
PASS 恢复后自动补发             | status=sent delivered=True
ALL PASS
```

持久化验证：结束进程后重启，离线会话、消息与离线文件队列完整保留。

说明：Windows 可执行文件为交叉编译产物（本环境无法运行 Windows 程序）；
协议与存储代码与通过联调的 Linux 构建完全同源。

## 说明与限制

- 单实例运行；同一台电脑请勿同时开两个。
- 文本与文件均为离线排队，不做服务端中转；双方需在同一局域网。
- 未做数字签名，SmartScreen 可能提示「更多信息 → 仍要运行」。
