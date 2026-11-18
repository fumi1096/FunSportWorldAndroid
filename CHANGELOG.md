# Changelog

## 1.1.0 — 2026-11-18

- 登录页新增“登录位置（校园基准点）”卡片，首次进入请求定位权限并自动把手机位置
  保存为校园基准点。
- 新增“我的 → 校园与时间 → 使用手机定位”按钮；纬/经度仍可手动修改并保存。
- 地图视野随基准点更新自动重新居中。
- 修复保存设置后界面配置未即时刷新的问题（`{ok, result.state}` 解包层级）。
- 版本号提升为 `versionCode 2 / versionName 1.1.0`，登录页与关于页显示版本。

## 1.0.0 — 2026-11-18

- 首个 Android 移植版本：
  - Kotlin + Jetpack Compose / Material 3 界面；
  - Chaquopy 复用 FunSportWorld Python 核心（协议、信封加密、轨迹、方案）；
  - 纯 Python AES-128 / RSA PKCS#1 v1.5、urllib 版 requests 子集；
  - Kotlin GT4 缺口识别 `GapMatcher`；
  - 自绘高德瓦片地图；
  - 触屏手绘路线：点击加点、拖动、长按删除、撤销/重做、自动闭合平滑、草稿、
    路线库、JSON 导入导出/分享、应用到方案；
  - 登录、生成方案、跑步记录、设置、关于页面；
  - 26 个 RouteMath JVM 单元测试与宿主机加密/HTTP 自检。
