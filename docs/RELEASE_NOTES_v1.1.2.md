# FunSportWorld Android v1.1.2

手绘路线加点修复版本。非官方、非商业（CC BY-NC 4.0）。

## 安装

- 文件：`FunSportWorld-Android-1.1.2-debug.apk`
- SHA-256：`953cc8ef596ac06fe0c50493b3b8ca1d85f1058a0f25b8ce1c601f1124b1d34f`
- 要求：Android 7.0（API 24）及以上；arm64-v8a / x86_64

**请覆盖安装，不要卸载或清除数据。** 同一签名覆盖升级会保留
`files/funsport/identity.json`（设备身份）与 `session.json`（登录状态）。

```bash
adb install -r FunSportWorld-Android-1.1.2-debug.apk
```

登录页标题显示 `· v1.1.2` 即表示安装成功。

## 更新内容

- 修复“加点点不了”：慢速点击不再被长按分支吞掉，空地图长按后松手仍会加点。
- 地图平移需要更大位移；手指轻微抖动仍按点击处理，不会变成拖动地图。
- 加点模式下每次点击都放置标点，包括点在已有点附近；点选/拖动标点改为在“调整”模式。
- 手势回调改为 `rememberUpdatedState`，切换加点/调整后立即生效。
- versionCode 4 / versionName 1.1.2。

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
