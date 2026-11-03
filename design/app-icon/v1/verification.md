# 新启动图标验证记录

日期：2026-11-03。

- `:app:assembleDebug` 构建成功，APK 图标引用解析为新自适应资源。
- `:app:lintDebug` 成功，0 个错误；保留 7 个界面 Java 代码警告，图标资源无警告。
- APK ZIP 完整性检查通过，包含 5 种密度的透明前景与自适应图标资源。
- Android 15 / API 35 arm64 模拟器覆盖安装成功；在应用列表与应用信息页确认新图标，见 `emulator-launcher.png` 和 `emulator-app-info.png`。安装使用 `adb install -r`，保留原有应用数据。
- 图案、圆形/圆角遮罩和 96/64/48/32px 预览已目视检查；有效 alpha 按中心直径 60dp 缩放，位于 66dp 安全区内。
- 主题单色资源已加入；`preview-themed.png` 为示意，未切换模拟器主题进行验证。实体手机及其他厂商桌面未验证。

构建日志：项目根目录 `.tooling/build-film-s-icon.log`。

图标接入阶段 APK：`android/app/build/outputs/apk/debug/app-debug.apk`，当时为开发版 0.3.1。后续 Spektra 命名和实机测试包记录见项目验证记录。

图标接入阶段 APK SHA-256：`1fa9ebaf8a7c901e182de18b850cbe423d45cc8ef9f8f3af7a4e93b65bc38020`。

原图由内置 image_gen 生成；完整提示词见 `generation-prompt.txt`。原图与 Android 导出资源均已保存到本项目。
