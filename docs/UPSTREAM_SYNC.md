# 与上游同步

本仓库的 Android 部分与上游 FunSportWorld 的关系是“移植 + 适配”，不是持续跟踪的
子模块。上游更新 Python 核心时，按下面的方式同步并重新验证。

## 上游信息

- 仓库：https://github.com/Funrestan/FunSportWorld
- 作者：YanamiNeko（GitHub: Funrestan）
- 许可：CC BY-NC 4.0
- 本移植版参考提交：`41359e518763e1cb5e5731249323d44378f8a124`
- 上游许可证：`LICENSES/FunSportWorld-upstream-LICENSE.txt`

## 复制范围

`app/src/main/python/funsport/` 是上游 `funsport/` 的适配副本。同步时**不要**直接整目录
覆盖，至少排除/保留以下差异：

| 上游文件/目录 | Android 处理 |
| --- | --- |
| `funsport/gui.py` | 不复制（桌面 tkinter GUI） |
| `funsport/map_preview.py` | 不复制（桌面地图预览） |
| `funsport/api/gt4image.py` | 不复制（Android 使用 Kotlin `GapMatcher`） |
| `funsport/main.py` / `__main__.py` | 不复制（Android 入口是 `android_backend.py`） |
| `funsport/crypto/envelope.py` | 已改为使用纯 Python `crypto/aes.py`、`crypto/rsa.py` |
| `funsport/api/gt4.py` | 已改为调用 Kotlin `GapMatcher`，并使用纯 Python AES/RSA |
| `funsport/config.py` | 保持上游默认值；定位默认值由 Android 登录页/设置页覆盖 |
| `android_backend.py`、`requests.py` | Android 新增，上游没有 |
| `funsport/gui_services.py` | Android 复用其中的设置校验/快照逻辑；注意桌面专属路径 |

同步步骤：

1. 对比上游提交与上次同步点，逐个文件 diff。
2. 仅把协议、加密、轨迹、业务逻辑相关改动移植到 `app/src/main/python/funsport/`；
   保留上述 Android 适配差异。
3. 用 `tools/host_checks.py` 验证加密/HTTP 兼容性：
   ```bash
   FUNSPORT_PYTHON=/path/to/desktop/.venv/bin/python tools/host_checks.py
   ```
4. 跑 `./gradlew :app:testDebugUnitTest` 和 `:app:assembleDebug`，在模拟器上回归
   登录、读取点位、生成方案、记录页与手绘路线。
5. 更新 `NOTICE` 中的“参考上游提交”与 `CHANGELOG.md`。

## 注意

- 上游禁止商业用途；同步后的代码仍然受 CC BY-NC 4.0 约束。
- 上游没有提供软件级担保；同步后必须自己完成 Android 端回归。
- 不要删除 `NOTICE`、`LICENSES/` 与上游署名。
