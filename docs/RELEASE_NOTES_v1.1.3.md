# FunSportWorld Android v1.1.3

登录恢复版本：支持用本机保存的密码一键重新登录。非官方、非商业（CC BY-NC 4.0）。

## 安装

- 文件：`FunSportWorld-Android-1.1.3-debug.apk`
- SHA-256：构建后补充
- 要求：Android 7.0（API 24）及以上；arm64-v8a / x86_64

**请覆盖安装，不要卸载或清除数据。** 同一签名覆盖升级会保留
`files/funsport/identity.json`（设备身份）与 `session.json`（登录状态）。

```bash
adb install -r FunSportWorld-Android-1.1.3-debug.apk
```

登录页标题显示 `· v1.1.3` 即表示安装成功。

## 更新内容

- 登录页支持**使用已保存密码一键登录**：密码留空时按钮变为“使用已保存密码登录”，
  不再强制重新输入密码。
- “我的 → 账号”新增“用保存的密码重新登录”，用于本机会话被其它客户端顶掉后的恢复。
- 说明同一账号在其它客户端登录会使本机会话失效；再次登录会顶掉对方会话。
- 空密码登录不会覆盖已保存的密码；取消“记住密码”后才清除。
- versionCode 5 / versionName 1.1.3。

## 已知限制

- 非官方客户端，账号风险由使用者自行承担；请遵守学校规定与平台协议。
- 真实账号的取点/生成/提交/记录流程需自行验证；GT4 识别受网络与图片影响。
- 手绘路线使用 GCJ-02；设置锚点沿用桌面端 BD-09 语义，地图上可能有约 500 m 偏移。
- 平台按单会话处理：其它客户端登录会令本机会话失效，本应用只能重新登录恢复。

## 许可与署名

- 上游 [FunSportWorld](https://github.com/Funrestan/FunSportWorld)：YanamiNeko，
  **CC BY-NC 4.0**；致谢 NekoSportsWorldTool（YanamiNeko）与 @L1Xu4n。
- 本移植版：**CC BY-NC 4.0（署名 · 非商业）**，禁止商业用途。
- 完整许可见仓库 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 与 `LICENSES/`。
- 高德地图/瓦片版权归高德所有，使用需遵守高德服务条款。
