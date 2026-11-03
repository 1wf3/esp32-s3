# Windows 软件准备检查单

检查日期：2026-09-28。开发板：ESP32-S3 N8R8，经 USB-C 连接这台 Windows 电脑。

## 已完成

| 项目 | 结果 |
|---|---|
| Arduino IDE | 已安装 2.3.10 |
| Arduino CLI | IDE 自带 1.5.1，可用于命令行编译与上传 |
| Espressif ESP32 开发板包 | 已安装 `esp32:esp32` 3.3.12 |
| LovyanGFX | 已安装 1.2.30，供后续 GC9A01 圆屏使用 |
| TinyGPSPlus | 已安装 1.0.3，供后续 GNSS/NMEA 使用 |
| ArduinoJson | 已安装 7.4.3，供后续高德 JSON 解析使用 |
| Windows 串口 | `USB-Enhanced-SERIAL CH343 (COM7)`，设备状态正常，无需另装驱动 |
| 芯片只读核验 | ESP32-S3，8 MB Flash，8 MB PSRAM |
| 自检程序 | 已编译、上传并在串口确认：Flash 8388608 B；PSRAM 8388608 B；Wi-Fi 初始化成功 |
| Arduino IDE 图形界面 | 已选中 `ESP32S3 Dev Module` 与 COM7；115200 串口监视器再次确认上述自检输出和周期性 `Alive` 信息 |
| 圆屏点亮程序 | 2026-09-29 已编译并经 COM7 烧录，串口启动正常；用户确认圆屏显示黑底路线预览图 |
| iPhone 热点发现 | 2026-09-29 开启“最大兼容性”后，ESP32 扫描到名为 `iPhone` 的热点，RSSI -15 dBm、信道 6；尚未输入密码或完成联网 |
| iPhone 热点连接 | 已在本地密码文件配置并成功编译、烧录联网程序；重新接入 USB 并保持 iPhone 热点页面开启后，串口确认 `WIFI CONNECTED`，获得局域网 IP `172.20.10.9` |
| 高德设备端路线 | 2026-09-29 已用公开北京测试起终点经 HTTPS 请求高德驾车路线 2.0：HTTP 200、`status=1`、1 条路线、12 个路段、5334 米；设备解析并绘制道路折线。用户已目视确认初版屏幕显示正常 |
| 圆屏透视版 | 2026-09-29 已编译并通过 COM7 烧录；再次取得高德 HTTP 200、12 个路段、5334 米，渲染器加载了去重后的 119 个路线点。实物画面正在等待拍照核对 |

自检程序完整路径：`E:\电动车导航9.25\software\board_smoke_test\board_smoke_test.ino`。桌面另有“ESP32S3 自检程序文件夹”快捷方式；双击后即可看到 `board_smoke_test.ino`。程序不存储热点密码，也不调用高德 API。

现用程序：`E:\电动车导航9.25\software\round_screen_test\round_screen_test.ino`，透视渲染在同目录的 `nav_renderer.h`。它启动时闪过红、绿、蓝三色，显示初始路线示意，连接 iPhone 热点后通过 HTTPS 请求高德路线。透视版分层绘制路网、真实路线描边和路线芯、已驶过路段、位置箭头、进度弧、速度数字和速度环、下次转向距离。屏幕标明 `AMAP DEMO`：起终点仍是公开北京测试点；周边路网是视觉演示几何；28 km/h 速度和行进进度是模拟值。GNSS 到货后才能接入实时位置与速度。热点密码在 `wifi_secrets.h`，高德 Key 在 `amap_secrets.h` 和 `software/amap_key.txt`，这些本地文件已列入 `.gitignore`，不要公开。

## 在 Arduino IDE 中打开自检程序

1. 打开 Arduino IDE，选择“文件 → 打开”，打开 `software/board_smoke_test/board_smoke_test.ino`。
2. 点击窗口顶部的“选择开发板”→“选择其他开发板和端口”，在搜索框输入 `ESP32S3 Dev Module`，选中该板。也可以走“工具 → 开发板 → esp32 → ESP32S3 Dev Module”。**左侧“开发板管理器”只负责安装板卡包，不是在其中选择开发板。**
3. 工具 → 端口 → **COM7**。拔插后 COM 编号可能改变，以设备管理器中的 `USB-Enhanced-SERIAL CH343` 为准。
4. 工具菜单中设置：**Flash Size = 8MB**、**PSRAM = OPI PSRAM**、**Partition Scheme = 8M with spiffs (3MB APP/1.5MB SPIFFS)**。此板经 CH343 硬件串口连接，**USB CDC On Boot = Disabled**、**Upload Mode = UART0 / Hardware CDC**。
5. 点击“验证”可编译；点击“上传”可重新烧录。上传后打开“串口监视器”，波特率选 **115200**，应看到 `PSRAM detected: yes` 和周期性的 `Alive` 输出。

如果串口监视器看不到输出，按一下板上的 `RST/EN` 键；上传失败时先关闭串口监视器，再重试。若开发板进入下载模式后无法自动退出，松开 `BOOT` 后按 `RST/EN`。

已经通过 IDE 所用的配置确认 `esp32:esp32` **3.3.12 已安装**，且 `ESP32S3 Dev Module` 存在。若“开发板管理器”仍显示“安装”，先看版本下拉框：如果它选中的是 3.3.11，“安装”指安装**这个版本**，不能据此判断 3.3.12 缺失。请按上面的第 2 步在顶部选择开发板。若顶部搜索仍无结果，保存草稿、关闭所有 Arduino IDE 窗口后重开，再提供顶部选择窗口的截图。不要选择 `Arduino ESP32 Boards by Arduino`，它主要针对 Arduino Nano ESP32。

## 命令行复核（可选）

Arduino CLI 位于已安装的 Arduino IDE 目录内部。使用 PowerShell 时先找出路径：

```powershell
$cli = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter arduino-cli.exe | Select-Object -First 1 -ExpandProperty FullName
& $cli core list
& $cli lib list
& $cli board list
```

已验证的完整板卡标识为：

```text
esp32:esp32:esp32s3:FlashSize=8M,PartitionScheme=default_8MB,PSRAM=opi
```

## 下一阶段

先根据透视版实物照片调整颜色、字体和层次。GNSS 模块到货后，根据**实物背面的型号、引脚丝印和供电标称**确定接线；先单独做串口定位测试，再将真实位置、速度和方向接入导航渲染器。不要把未知电压的模块直接接 ESP32 的 3V3 引脚。
