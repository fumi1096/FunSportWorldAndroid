# 开源前检查清单

按顺序执行即可把 `FunSportWorldAndroid/` 作为独立 GitHub 仓库发布。

## 1. 替换占位符

- [ ] `LICENSE` 第一行：`<YOUR NAME / GITHUB HANDLE>` → 你的名字或 GitHub 昵称
- [ ] `AUTHORS.md`：维护者名字、GitHub 地址
- [ ] `README.md`：徽章/源码地址中的 `<your-github>`（当前 README 为占位）
- [ ] `AboutScreen.kt`：本移植版源码地址（占位 `github.com/<your-github>/...`）

## 2. 清理敏感/本机文件

这些文件已在 `.gitignore` 中，但如果你用压缩包上传，请手动排除：

- [ ] `local.properties`（本机 SDK 路径）
- [ ] 如不想公开调试签名，可删除 `app/keystore/debug.keystore` 并改用系统默认 debug
      keystore（本仓库为了可复现构建而**故意公开**该 debug key；它不能用于 release）
- [ ] `dist/`（APK 请放在 GitHub Releases，不要提交）
- [ ] `build/`、`app/build/`、`gradle-home/`、`android-home/`、`.gradle/`、`.kotlin/`
- [ ] 任何 `*.log`、`identity.json`、`session.json`、`config.json`、`points_cache.json`
- [ ] 截图/日志中的真实手机号、账号、token

快速检查：

```powershell
Get-ChildItem -Recurse -File | Select-String -Pattern 'ghp_|sk-|AKIA|identity\.json|session\.json|password'
```

## 3. 本地构建与测试

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

确认 `dist/FunSportWorld-Android-1.1.0-debug.apk`（仅本机）可安装，登录页显示
`v1.1.0` 与“登录位置（校园基准点）”。

## 4. 创建仓库并推送

在 `FunSportWorldAndroid/` 下执行（不要在原桌面仓库根目录执行，避免把桌面端历史一起推送）：

```bash
git init
git add .
git status          # 确认没有 local.properties / dist / build / 非 debug keystore
git commit -m "feat: FunSportWorld Android 1.1.0"
git branch -M main
git remote add origin https://github.com/<your-github>/FunSportWorldAndroid.git
git push -u origin main
```

## 5. GitHub 仓库设置

- Description：`FunSportWorld 安卓移植版（非官方，CC BY-NC 4.0，非商业）`
- Topics：`android` `kotlin` `jetpack-compose` `chaquopy` `python` `funsportworld`
- License：GitHub 不会自动识别 CC BY-NC 4.0，请在仓库信息中选择 **Other / View license**，
  指向本仓库 `LICENSE` 文件。
- 建议开启 Issues / Discussions；CI 已包含 `.github/workflows/android.yml`。

## 6. 发布 APK

- 在 GitHub **Releases** 新建 `v1.1.0`，上传 `dist/FunSportWorld-Android-1.1.0-debug.apk`
  作为 release asset（不要提交进 git）。
- Release 说明中附：
  - 上游 FunSportWorld 链接与 CC BY-NC 4.0 声明
  - `NOTICE` / `THIRD_PARTY_NOTICES.md` / `LICENSES/` 的链接
  - 安装说明与已知限制
- 注意：debug APK 使用仓库内调试签名；若要做正式分发，请使用你自己的 release keystore，
  并确保不把 release keystore 提交到仓库。

## 7. 合规提醒

- CC BY-NC 4.0 **不是 OSI 认可的开源许可证**，因为有“非商业”限制。发布时请诚实描述为
  “源码公开、非商业许可”，不要写成 MIT/Apache 或“任意用途开源”。
- 必须保留上游署名：YanamiNeko / FunSportWorld、NekoSportsWorldTool、@L1Xu4n。
- 不要移除 `LICENSE`、`NOTICE`、`THIRD_PARTY_NOTICES.md` 与 `LICENSES/`。
- 高德瓦片/服务不是开源数据，注意高德服务条款；不要把瓦片缓存提交到仓库。
