# FunSportWorld Android

![Platform](https://img.shields.io/badge/platform-Android%2024%2B-brightgreen)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![License](https://img.shields.io/badge/license-CC%20BY--NC%204.0-lightgrey)
![Version](https://img.shields.io/badge/version-1.1.0-blue)

FunSportWorld 的 Android 移植与二次开发版本：登录运动世界校园版、读取校园打卡点、
按学校策略生成跑步方案、预览/提交、查看跑步记录，并把桌面端的“手绘路线”重新设计为
适合触屏的交互。

> **非官方客户端。** 本项目与运动世界校园版官方没有任何关联，仅供学习研究与技术交流。
> 请遵守所在学校规定与平台用户协议，使用产生的一切后果由使用者自行承担。
>
> **非商业许可。** 本项目及其上游 FunSportWorld 均按 **CC BY-NC 4.0**
> （署名—非商业性使用 4.0 国际）发布，禁止任何商业用途。

---

## 上游与致谢

本仓库是以下项目的 Android 二次开发版本，**完整保留上游署名**：

- **[FunSportWorld](https://github.com/Funrestan/FunSportWorld)** — 作者 **YanamiNeko**
  （GitHub: [Funrestan](https://github.com/Funrestan)），Python CLI / tkinter 桌面端，
  **CC BY-NC 4.0**。本项目的 Python 协议、信封加密、轨迹生成、方案与业务逻辑来自该仓库。
- **[NekoSportsWorldTool](https://github.com/YanamiNeko/NekoSportsWorldTool)** —
  作者 **YanamiNeko**。FunSportWorld 的协议层、轨迹生成器、卡路里公式等核心逻辑参考自
  该项目；本项目沿用上游致谢。
- **[@L1Xu4n](https://github.com/L1Xu4n)** — 提出接入高德地图 API 生成真实道路的改进方案，
  上游据此实现校园环；本项目的“应用到方案”手绘路线也基于同一思路。

Android 移植版由 **<YOUR NAME / GITHUB HANDLE>** 维护（发布前请替换）。
上游许可证原文与修改说明见 [LICENSE](LICENSE)、[NOTICE](NOTICE) 与
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 功能

四个主页面：**地图、路线、记录、我的**；另有全屏的 **生成方案**、**登录**、**关于**、
**手绘路线编辑器**。

### 地图与定位

- 自绘高德瓦片地图（Mercator、缩放/平移、瓦片内存+磁盘缓存、网络失败兜底网格）。
- 首次进入登录页请求定位权限，授权后自动把手机位置写为**校园基准点**；
  登录页显示当前坐标，可重新定位。
- “我的 → 校园与时间”可手动修改纬度/经度，也可“从已读取打卡点计算中心”。
- 定位只用于填写基准点，不会由应用上传。

### 手绘路线（触屏化）

- 单击地图加点；至少 3 点后自动闭合，并用 Catmull-Rom 平滑成跑步轨迹。
- 拖动蓝色标点微调、长按删除、撤销/重做。
- 按标点顺序检查打卡点覆盖，显示未覆盖点位。
- 草稿 500 ms 防抖自动保存；进程重启后恢复，并自动把视野适配到已有草稿。
- 保存到本机路线库：命名、备注、默认路线、重命名、复制、删除。
- 导入/导出与桌面端兼容的 JSON
  （`{schema:1, coord:"gcj02", vertices, points, length_m}`），支持
  `gcj02/bd09/wgs84` 坐标别名与 WGS84→GCJ02 转换，可通过系统分享面板发送。
- “应用到方案”后，生成方案时使用该闭合轨迹代替高德步行路线。

### 其它

- 登录/登出/切换本机账号、记住密码（仅本机）。
- 生成方案：自动/固定距离、配速、步频、开始时间、本地预览、在线预览与单次提交。
- 跑步记录、学期/记录详情（依赖上游接口能力）。
- 关于页保留上游来源、许可、致谢与非商业声明。

---

## 技术架构

| 桌面端 | Android 端 |
| --- | --- |
| tkinter 图形界面 | Kotlin + Jetpack Compose / Material 3 |
| Python 协议、加密、轨迹生成 | 原 Python 核心通过 Chaquopy 复用，Kotlin↔Python 只走 JSON |
| PyCryptodome AES / RSA | 纯 Python AES-128 / RSA PKCS#1 v1.5（无原生 wheel） |
| requests | 基于 `urllib` 的 requests 子集（`requests.py`） |
| OpenCV / numpy 识别 GT4 缺口 | Kotlin `GapMatcher`（灰度→高斯→Canny→NCC） |
| 鼠标手绘路线 | 触屏点击/拖动/长按 + 自动平滑 + 草稿/路线库 |
| 高德地图预览 | 自绘高德瓦片地图 |

### 目录

```
FunSportWorldAndroid/
├── app/src/main/java/com/funsportworld/android/
│   ├── backend/      PythonBridge（JSON-only）、GapMatcher（GT4 缺口）
│   ├── data/         Models、UiModels、RouteStore（路线库/草稿）
│   ├── location/     PhoneLocation、LocationSetup（定位默认值）
│   ├── map/          AmapMap（自绘瓦片地图 + 手势）
│   ├── route/        RouteMath（平滑/闭合/对齐/分析）、RouteJson
│   ├── ui/           MainScaffold、主题、组件、各页面
│   └── viewmodel/    AppViewModel、RouteEditorViewModel
├── app/src/main/python/
│   ├── android_backend.py   暴露给 Kotlin 的 JSON 接口
│   ├── requests.py          urllib 版 requests 子集
│   └── funsport/            上游 Python 核心的 Android 适配副本
├── app/src/test/java/...    RouteMath 的 26 个 JVM 单元测试
├── tools/host_checks.py     加密/HTTP 兼容性宿主机自检
├── LICENSES/                CC BY-NC 4.0、Apache 2.0、MIT、PSF 等许可原文
├── NOTICE / THIRD_PARTY_NOTICES.md
└── .github/workflows/       Android CI（构建 + 单元测试 + artifact）
```

---

## 构建

环境：

- JDK 21（Android Studio 自带 JBR 即可）
- Android SDK 35
- 无需单独安装 Python：Chaquopy 自带 CPython 3.12，且本工程 **没有任何 pip 依赖**

```bash
# Linux / macOS
./gradlew :app:assembleDebug

# Windows
gradlew.bat :app:assembleDebug
```

Gradle wrapper 首次会下载 Gradle 8.10.2。如果 `~/.gradle` 不可写（受限沙箱/CI），
请把 `GRADLE_USER_HOME` 指向可写目录；仓库内 `gradle.properties` 已设置
`org.gradle.parallel=false` 与 `org.gradle.vfs.watch=false`，避免 Windows
文件监听器/状态锁问题。

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 54 MB，含 arm64-v8a / x86_64）。

调试构建使用仓库内**公开的 debug keystore**（`app/keystore/debug.keystore`，别名
`androiddebugkey`，口令 `android`），保证任何 clone 都能生成同一 debug 签名；它**只用于
debug**，任何人都能重新签同一包，不可用于正式发布。Release 请使用自己的 release keystore
并保密，详见 [SECURITY.md](SECURITY.md)。

CI 见 [`.github/workflows/android.yml`](.github/workflows/android.yml)：
push / PR 时执行 `assembleDebug` + `testDebugUnitTest`，并上传 debug APK artifact。

---

## 安装与发布

推荐从 GitHub **Releases** 下载 APK（仓库源码不提交二进制）。

```bash
adb install -r FunSportWorld-Android-1.1.0-debug.apk
```

如果手机上已有旧版本且无法覆盖，请先卸载“FunSportWorld 安卓”再安装。登录页标题会显示
`· v1.1.0`，可用来确认版本。

> 本仓库附带的 `dist/` 仅是本机测试包，已在 `.gitignore` 中忽略；发布时请把 APK
> 放到 GitHub Release，不要 commit 进仓库。

---

## 测试

```bash
# RouteMath JVM 单元测试（26 个）
./gradlew :app:testDebugUnitTest

# 宿主机加密/HTTP 兼容性自检
# 需要桌面端 FunSportWorld 的虚拟环境（含 pycryptodome）做对照
FUNSPORT_PYTHON=/path/to/FunSportWorld/.venv/bin/python tools/host_checks.py
```

`host_checks.py` 验证：

- 纯 Python AES-128-CBC/PKCS#7 与 PyCryptodome 对 200 组随机数据一致；
- 信封 `d/h/k/p/t` 的 AES 容器与 MD5、PKCS#1 v1.5 结构；
- GT4 的 `_aes_o` / `_encrypt_data`（用临时密钥对解密验证）；
- `requests` 子集的 GET/POST/PUT、params、上下文管理器、`raise_for_status`。

### 已验证（模拟器实测）

在 `Medium_Phone_API_35` x86_64 模拟器上安装 debug APK：

- Python 初始化成功，设置从本机配置加载；高德瓦片地图加载、缩放/平移；
- 手绘路线：点击加点（4 点 → 2.79 km）、拖动蓝点、长按删除、撤销/重做、自动闭合平滑；
  保存路线、进程重启后草稿恢复并自动适配视野、路线库“默认/编辑/删除”；
- 生成方案页、登录页渲染；
- 登录页用演示账号请求服务器返回业务错误 `10175: 手机号或密码错误`，说明 Android
  上的 HTTP、信封加密、RSA 响应验签与错误解析链路实际工作；
- 模拟器 `geo fix 104.0656 30.6598` 后首次进入登录页，自动定位并写入
  `identity.json` 的基准点 `30.6598 / 104.0656`；设置页经纬度字段回填同一坐标，
  可继续手动修改。

**未验证**：真机 arm64、真实账号完整取点/生成/提交/记录流程、SAF 导入导出的逐项操作、
GT4 极验在弱网下的稳定性。

---

## 隐私与安全

- 不申请定位以外的敏感权限；定位仅用于填写校园基准点，不上传。
- 账号密码仅保存在本机私有目录，且仅在勾选“记住密码”时保存。
- 请求直接访问运动世界校园版服务器；本项目不额外收集、不上传账号数据。
- 高德瓦片仅在运行时请求并缓存到应用私有目录，不随仓库/APK 静态分发。
- 不要把真实账号、token、`identity.json`、`session.json`、`config.json`、
  keystore、`local.properties` 提交到仓库；详见 [SECURITY.md](SECURITY.md)。

---

## 已知限制

1. **非官方**：接口行为可能随 App/学校策略变化；账号风险由使用者自行承担。
2. **坐标系**：设置中的锚点沿用桌面端语义（BD-09），高德瓦片是 GCJ-02，在成都市
   附近约 500 m 偏移；手绘路线 JSON 统一使用 GCJ-02。
3. **默认基准点**：首次定位成功后会覆盖桌面端历史默认值（大连 38.901678, 121.540241）；
   拒绝定位权限后可手动填写。
4. **方案缓存**：生成方案快照保存在 Python 进程内存（TTL 600 s、提交单次有效），
   低内存杀进程后需重新生成。
5. **GT4 极验**：缺口识别为 Kotlin 实现，准确率与网络/图片相关；失败会提示并可重试。
6. **底图**：依赖 `webrd0{1-4}.is.autonavi.com`；不可用时使用网格底图与提示，
   路线/点位仍可绘制。
7. **构建**：Chaquopy 会打包 CPython，APK 约 54 MB；未做 ABI 分包。

---

## 与上游的修改说明

完整说明见 [NOTICE](NOTICE)。简要：

- UI 全部重写为 Compose；新增路线编辑器、路线库、定位、生成方案/记录/设置/关于页面；
- `app/src/main/python/funsport/` 为上游核心的 Android 适配副本，删除 tkinter GUI、
  桌面地图预览与 GT4 图片分析，新增 JSON 桥接；
- 用纯 Python AES/RSA 替代 PyCryptodome，用 urllib 实现 requests 子集；
- GT4 缺口识别改为 Kotlin `GapMatcher`；
- 新增构建工程、Gradle wrapper、JVM 测试、宿主机自检与 CI；
- 新增定位默认值与版本号/开源许可页面。

---

## 许可证

- 本仓库及其上游 FunSportWorld：**CC BY-NC 4.0**，详见 [LICENSE](LICENSE)、
  [LICENSES/CC-BY-NC-4.0.txt](LICENSES/CC-BY-NC-4.0.txt) 与
  [上游许可证原文](LICENSES/FunSportWorld-upstream-LICENSE.txt)。
- **这不是 OSI 认可的开源许可证**（含“非商业”限制）；准确表述是“源码公开、非商业许可”。
- 第三方组件（AndroidX/Compose、Kotlin、Chaquopy、Python、Gradle、JUnit 等）的许可见
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；高德地图/瓦片受高德服务条款约束。
- 必须保留上游署名：YanamiNeko / FunSportWorld、NekoSportsWorldTool、@L1Xu4n。

---

## 截图

| 文件 | 内容 |
| --- | --- |
| [`docs/screenshots/01-map.png`](docs/screenshots/01-map.png) | 地图首页 |
| [`docs/screenshots/02-editor.png`](docs/screenshots/02-editor.png) | 手绘路线编辑（4 点、2.79 km、自动平滑） |
| [`docs/screenshots/03-routes.png`](docs/screenshots/03-routes.png) | 路线库 + 自动保存草稿 |
| [`docs/screenshots/04-plan.png`](docs/screenshots/04-plan.png) | 生成方案参数页 |
| [`docs/screenshots/05-login.png`](docs/screenshots/05-login.png) | 登录页（服务器业务错误提示） |
| [`docs/screenshots/06-login-location.png`](docs/screenshots/06-login-location.png) | 登录页手机定位结果（校园基准点） |

---

## 贡献与版本

- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 安全报告：[SECURITY.md](SECURITY.md)
- 版本记录：[CHANGELOG.md](CHANGELOG.md)
- 上游同步说明：[docs/UPSTREAM_SYNC.md](docs/UPSTREAM_SYNC.md)
- 开源前检查清单：[docs/OPEN_SOURCE_CHECKLIST.md](docs/OPEN_SOURCE_CHECKLIST.md)

当前版本 **1.1.0**（versionCode 2）。
