# FunSportWorld Android v1.1.1

手绘路线触摸修复 + 升级保留数据版本。非官方、非商业（CC BY-NC 4.0）。

## 安装

- 文件：`FunSportWorld-Android-1.1.1-debug.apk`
- SHA-256：`e063fd2f8c645fa0526e3cd38857eea622a14e8ff4f87b4c66d608511263bef0`
- 要求：Android 7.0（API 24）及以上；arm64-v8a / x86_64

**请覆盖安装，不要卸载或清除数据。** 同一签名覆盖升级会保留应用私有目录
`files/funsport/`，即 `identity.json`（设备身份）与 `session.json`（登录状态）不会丢失。

```bash
adb install -r FunSportWorld-Android-1.1.1-debug.apk
```

登录页标题显示 `· v1.1.1` 即表示安装成功。

## 更新内容

- 修复手绘路线双指缩放：缩放与平移合并为一次相机更新，并在手势内累计相机状态，
  避免快速捏合时缩放被覆盖。
- 修复地图放大后无法点选标点：命中半径 24dp→30dp，点选不再依赖拖动模式，
  从标点上开始触摸/拖动也会选中。
- 手绘路线页新增 **+ / − / 适应路线** 悬浮按钮，单指也能缩放。
- 明确升级策略：覆盖安装保留设备身份与登录状态；不要卸载或 `pm clear`。
- versionCode 3 / versionName 1.1.1。

## 已知限制

- 非官方客户端，账号风险由使用者自行承担；请遵守学校规定与平台协议。
- 真实账号的取点/生成/提交/记录流程需自行验证；GT4 识别受网络与图片影响。
- 手绘路线使用 GCJ-02；设置锚点沿用桌面端 BD-09 语义，地图上可能有约 500 m 偏移。

## 许可与署名

- 上游 [FunSportWorld](https://github.com/Funrestan/FunSportWorld)：YanamiNeko，
  **CC BY-NC 4.0**；致谢 NekoSportsWorldTool（YanamiNeko）与 @L1Xu4n。
- 本移植版：**CC BY-NC 4.0（署名 · 非商业）**，禁止商业用途。
- 完整许可见仓库 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 与 `LICENSES/`。
- 高德地图/瓦片版权归高德所有，使用需遵守高德服务条款。
