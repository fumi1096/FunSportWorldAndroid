# 安全说明

## 项目性质

本项目是第三方学习/研究性质的 Android 客户端，**不是**运动世界校园版官方客户端，
与官方没有任何关联。使用本项目的账号风险由使用者自行承担；请遵守所在学校规定。

## 不要提交的内容

请勿在 Issue、PR、截图或仓库中提交：

- 真实手机号、密码、短信验证码、token、Cookie、会话文件
- `identity.json`、`session.json`、`config.json`、`points_cache.json`
- keystore / 签名文件、`local.properties`、含个人路径的日志
- 高德 Web 服务 Key 或其他 API Key

本仓库的 `.gitignore` 已忽略这些文件；提交前仍建议运行一次密钥扫描。

## 调试签名

`app/keystore/debug.keystore` 是**故意公开**的 Android debug 签名（别名
`androiddebugkey`，口令 `android`），只用于生成本仓库的 debug APK，**不可用于正式发布**。
任何人都可以用它重新签名 debug 包，因此不要把它当作安全边界；正式分发请使用你自己的
release keystore，并确保 release keystore 不被提交。

## 敏感设计

- 学生账号密码仅在本机私有目录保存，且仅在用户勾选“记住密码”时保存。
- 手机定位只用于填写校园基准点，不会由应用上传。
- 协议请求直接访问运动世界校园版服务器；本项目不会额外收集或上传账号数据。
- 地图瓦片在运行时从高德请求并缓存到应用私有目录。

## 报告漏洞

如果发现会影响用户账号/隐私/设备安全的问题：

- 请通过 GitHub 仓库的 **Security → Report a vulnerability** 私下报告，或直接联系维护者；
- 不要在公开 Issue 中贴出真实账号、token 或可复现的敏感数据；
- 提供版本号、Android 版本、复现步骤与影响范围，避免附带真实个人信息。

维护者会在确认后尽快回复，并在修复发布后致谢（如你愿意）。
