# 电动车导航屏

基于 ESP32-S3 的导航屏项目，包含安卓导航应用、手机到屏幕的 BLE 协议、路线与地图网关、电脑调试工具及转接板资料。当前主要联调入口为 `software/android_nav` 与 `software/phone_ble_nav`。

## 目录

| 路径 | 内容 |
| --- | --- |
| `software/android_nav/` | 安卓应用源码、构建脚本与测试 |
| `software/phone_ble_nav/` | ESP32-S3 蓝牙导航固件、显示代码及协议测试 |
| `software/phone_gateway/` | Node.js 网关、Cloudflare 部署方案与协议定义 |
| `software/pc_debug/`、`software/web_debug/` | 电脑 BLE 调试与网页预览 |
| `software/round_screen_test/`、`software/wide_screen_nav/` | 早期独立导航实验固件 |
| `software/*test/` | 开发板、GNSS 和屏幕点亮测试 |
| `hardware/` | 转接板生成、检查脚本及 EDA 导出资料 |
| `电动车转接板10.19.eprj2` | 当前嘉立创 EDA 主工程；`hardware/easyeda` 中为历史快照 |
| `docs/guides/`、`docs/hardware/`、`docs/design/` | 使用说明、接线和设计方案 |
| `output/carrier_v01/`、`output/pdf/` | 硬件图纸、BOM 和接口资料 |
| `output/previews/`、`output/navigation-concepts/` | 可公开的界面预览和概念设计 |

`build/` 保存本地 SDK/JDK、蓝牙依赖及运行文件；`work/` 保存中间文件；`outputs/` 保存本次交付包。上述目录以及历史记录、密钥、签名文件、APK、固件和演示文稿均不进入 Git。现有脚本使用的 `output/` 路径继续沿用。

## 开始使用

### 路线与地图网关

需要 Node.js 20+，使用环境文件的启动方式建议 Node.js 24+。在 PowerShell 中：

```powershell
Set-Location software/phone_gateway/backend
npm ci
npm test
Copy-Item .env.example .env
# 在本地填写自己的 Key，并按需设置 MOTO_PROVIDER=amap
node --env-file=.env src/server.js
```

完整配置见 [网关说明](software/phone_gateway/backend/README.md)；云端部署见 [Cloudflare 说明](software/phone_gateway/backend/cloudflare/README.md)。示例配置默认禁用真实服务；测试夹具和 DEMO 不代表实时导航。

### 安卓应用

使用 `software/android_nav/build.ps1` 构建。当前脚本要求 JDK，以及包含 `android-36/android.jar`、`android-15/` 构建工具的 SDK 目录；可通过 `-JdkRoot`、`-SdkRoot` 显式传入，默认读取 `build/android_tools/`。

该项目使用手工 Java / D8 / AAPT2 构建流程，并非 Gradle 工程。`-WorkRoot` 应指向本地 ASCII 路径（原电脑的 `E:/nav-android-build` 是兼容入口），签名密钥由脚本在该目录保存，不能提交。APK 输出至 `software/android_nav/output/`。

### ESP32 与电脑调试

固件需要 Arduino ESP32 开发环境，库依赖以源码的 `#include` 为准。参考 [手机蓝牙导航](docs/guides/手机蓝牙导航使用说明.md) 和 [电脑联调](docs/guides/电脑同步调试说明.md)。

早期独立导航固件使用 `wifi_secrets.h` 与 `amap_secrets.h`：将同目录的 `.example` 文件复制为实际头文件后，在本机填写凭据。网关同理使用 `.env.example`。真实配置被 Git 忽略。

部分 Windows 调试脚本保留原开发机的 Visual Studio、Arduino CLI 或 Python 路径，新电脑需要调整。电脑蓝牙依赖可安装到脚本读取的位置：

```powershell
python -m pip install --target build/ble_tools bleak==3.0.2
```

本仓库不包含 SDK/JDK、安装包和预编译固件；整理期间未在新电脑完整重建或重新烧录验证。

## 测试与预览

```powershell
node software/web_debug/test.cjs
powershell -File software/android_nav/tests/test.ps1
# C++ 协议测试需要 Visual Studio C++ 工具，先核对脚本中的安装路径
cmd /c software\phone_ble_nav\tests\run_tests.cmd
```

网页测试读取已有 [手机导航预览](output/previews/手机导航实机调试.html)，也可用 `software/web_debug/build.ps1` 重新生成。本机带令牌的电脑调试页由服务启动时生成，不随仓库分发。

## 发布到 GitHub

源码包及 Git 提交范围均已排除本地凭据、签名文件、调试日志、编译产物与历史压缩包。操作见 [上传说明](docs/guides/GitHub上传说明.md)。

保留第三方目录的 LICENSE、NOTICE 和 [第三方声明](software/phone_gateway/THIRD_PARTY_NOTICES.md)。上游 MOTO GPS 部分采用 PolyForm Noncommercial 1.0.0，相关部分需遵守原有非商业授权及署名要求。本项目尚未选择自有代码的开源许可证；公开仓库前请由项目作者决定授权方式。
