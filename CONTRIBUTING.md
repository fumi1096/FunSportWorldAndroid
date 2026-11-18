# 贡献指南

感谢参与。本仓库是 FunSportWorld 的 Android 二次开发版本，**非官方、非商业**。

## 许可与署名

- 提交贡献即表示你同意贡献内容按本仓库的 **CC BY-NC 4.0** 许可发布。
- 禁止将本项目或其衍生作品用于商业目的（售卖、收费代做、广告变现、商业产品集成等）。
- 保留上游 FunSportWorld / NekoSportsWorldTool / @L1Xu4n 的署名与许可声明；
  不要删除 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 与 `LICENSES/`。

## 开发环境

- JDK 21（Android Studio 自带 JBR 即可）
- Android SDK 35
- 不需要单独安装 Python：Chaquopy 自带 CPython 3.12，且本工程没有 pip 依赖

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

宿主机加密/HTTP 兼容性自检需要桌面端 FunSportWorld 的虚拟环境（含 pycryptodome）：

```powershell
$env:FUNSPORT_PYTHON = "path\to\FunSportWorld\.venv\Scripts\python.exe"
& $env:FUNSPORT_PYTHON tools/host_checks.py
```

## 代码约定

- Kotlin：4 空格缩进，遵循现有 Compose 组件与 `AppViewModel` 状态流写法；
  新增界面文案使用中文；不要引入 Google Play Services 等非必要依赖。
- Python：保持 JSON-only 桥接；`android_backend.py` 只暴露简单 JSON 接口；
  避免新增需要原生 wheel 的依赖（当前无 pip 依赖）。
- 不提交任何账号、密码、token、设备指纹、`identity.json`、`session.json`、
  `config.json`、`points_cache.json`、keystore、`local.properties`、APK 或构建缓存。
- 新增/修改文件请在 PR 描述中说明是否来自上游以及许可情况。

## Pull Request

1. Fork 并创建分支。
2. 保持改动聚焦；UI 变更附截图；算法/坐标变更附测试或验证说明。
3. 确认 `./gradlew :app:assembleDebug :app:testDebugUnitTest` 通过。
4. 说明是否修改了上游派生文件（`app/src/main/python/funsport/`）。

## 安全问题

请阅读 `SECURITY.md`，不要公开提交包含真实账号信息的 Issue。
