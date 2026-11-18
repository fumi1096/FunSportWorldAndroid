# 第三方组件与许可

本仓库源码主要使用 CC BY-NC 4.0；下列第三方组件各自遵循其许可证。发布 APK 时建议
同时附带本文件（以及 `LICENSES/` 目录），以满足署名/许可文本保留要求。

| 组件 | 用途 | 许可证 | 链接 |
| --- | --- | --- | --- |
| FunSportWorld（上游） | Python 协议/加密/轨迹/业务核心 | CC BY-NC 4.0 | https://github.com/Funrestan/FunSportWorld |
| NekoSportsWorldTool（上游致谢） | 协议层、轨迹生成、卡路里公式的上游灵感 | 仓库未提供独立许可证；按上游 README 署名 | https://github.com/YanamiNeko/NekoSportsWorldTool |
| AndroidX / Jetpack Compose / Material 3 | Android UI | Apache License 2.0 | https://developer.android.com/jetpack/androidx |
| Kotlin / kotlinx.coroutines | 语言与协程 | Apache License 2.0 | https://kotlinlang.org/ |
| Chaquopy | Android 内嵌 Python 运行时 | MIT | https://chaquo.com/chaquopy/ |
| Python 3.12 | Chaquopy 内置解释器 | PSF License 2.0 | https://docs.python.org/3/license.html |
| Gradle / Gradle wrapper | 构建 | Apache License 2.0 | https://gradle.org/ |
| JUnit 4 | JVM 单元测试（仅测试） | Eclipse Public License 1.0 | https://junit.org/junit4/ |
| 高德地图瓦片/服务 | 底图与步行路径规划 | 高德开放平台服务条款（非开源数据） | https://lbs.amap.com/ |

许可文本：

- CC BY-NC 4.0：`LICENSES/CC-BY-NC-4.0.txt`
- 上游 FunSportWorld 许可证：`LICENSES/FunSportWorld-upstream-LICENSE.txt`
- Apache 2.0：`LICENSES/Apache-2.0.txt`
- Chaquopy MIT：`LICENSES/MIT-Chaquopy.txt`
- Python PSF 2.0：`LICENSES/PSF-2.0.txt`

说明：

- 高德瓦片不会被提交到仓库，也不随 APK 静态分发；App 仅在运行时请求并缓存到应用私有目录。
- NekoSportsWorldTool 仓库已归档且未声明独立许可证；本仓库不复制其代码，只按上游
  README 的致谢要求保留署名。
- 若你重新分发 APK，请保留本文件、`LICENSES/` 与上游署名。
