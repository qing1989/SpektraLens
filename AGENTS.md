# SpektraLens 开发交接

- Android：Java，包名 `app.spektrafilm`，minSdk 29 / targetSdk 35。保持现有 GPL-3.0-or-later SPDX 头和 UI 风格。
- 本地、无账号、不上传照片。网络仅用于检查更新和下载 APK；不得加入照片上传、设备标识或额外遥测，不引入更新 SDK。
- 更新清单固定 URL：`https://github.com/qing1989/SpektraLens/releases/latest/download/latest.json`。不得改为 GitHub API；仅接受 HTTPS，按 versionCode 比较，安装前必须校验 SHA-256。
- 发布必须同时上传固定名 `latest.json` 和 `SpektraLens-v{version}.apk`，清单的哈希、大小必须对应实际上传的 APK。流程见 [应用内更新与发版](docs/应用内更新与发版.md)。
- 每次发版必须使用与已有安装包相同的签名密钥（包括曾以 debug 签名发行的渠道）；不能通过代码改签名。相同包名、同签名、递增 versionCode 可覆盖安装，无需清除数据。不得建议用户为更新先卸载。
- 当前分支的更新功能留待下一版本发行；不要将其描述成已发布的 v0.3.9 热更新。
- 纯 Java 测试：`python3 scripts/test_updates.py`（JDK 17+，无需 Android SDK）；或在 Android 构建环境运行 `cd android && ./gradlew :app:testUpdatePolicy`，已挂入 `check`。
- 更新使用专用 cache/updates 目录与 PackageInstaller.Session，不复用只允许导出图片的 ShareProvider。未知来源授权返回后重新校验；系统确认安装，不静默安装。
