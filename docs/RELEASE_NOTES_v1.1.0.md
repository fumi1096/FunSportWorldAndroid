# FunSportWorld Android v1.1.0

Android 移植版（非官方、非商业）首个包含手机定位的版本。

## 安装

- 文件：`FunSportWorld-Android-1.1.0-debug.apk`
- SHA-256：`1d01dd573628c90d5f3b49a212ae1a7667f18d0062a892ab223e270ee86d2a1d`
- 要求：Android 7.0（API 24）及以上；arm64-v8a / x86_64
- 安装：`adb install -r FunSportWorld-Android-1.1.0-debug.apk`
  或把 APK 传到手机点击安装；已有旧版无法覆盖时先卸载“FunSportWorld 安卓”。

登录页标题会显示 `· v1.1.0` 以确认版本。

## 更新内容

- 登录页新增“登录位置（校园基准点）”卡片：首次进入请求定位权限，授权后自动把手机
  位置保存为校园基准点；显示当前坐标并支持重新定位。
- “我的 → 校园与时间”新增“使用手机定位”，纬度/经度仍可手动修改后保存。
- 地图视野随基准点更新自动重新居中。
- 修复保存设置后配置未即时刷新的问题。
- 版本号：versionCode 2 / versionName 1.1.0。

## 已知限制

- 非官方客户端；账号风险由使用者自行承担；请遵守学校规定与平台协议。
- 需联网访问运动世界校园版、高德路径/瓦片、GT4 验证码等上游服务。
- 真实账号的取点/生成/提交/记录流程需自行验证；GT4 识别受网络与图片影响。
- 手绘路线使用 GCJ-02；设置中的锚点沿用桌面端 BD-09 语义，地图上可能有约 500 m 偏移。

## 许可与署名

- 上游 [FunSportWorld](https://github.com/Funrestan/FunSportWorld)：作者 YanamiNeko，
  **CC BY-NC 4.0**。
- 致谢 NekoSportsWorldTool（YanamiNeko）与 @L1Xu4n。
- 本移植版：**CC BY-NC 4.0（署名 · 非商业）**；禁止售卖、收费代做、广告变现或集成到
  商业产品。
- 完整许可与第三方组件见仓库根目录 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md`
  与 `LICENSES/`；发布 APK 时请一并附带这些文件。
- 高德地图/瓦片版权归高德所有，使用需遵守高德服务条款。
