# SpektraLens · 胶片图标 v1

> 项目现名为 **SpektraLens**（2026-11-05 起）。本目录的历史图片、生成提示词与交付文件名保留制作时的记录；后续项目展示使用新名称。

设计日期：2026-11-03。

图标图像按 CC0-1.0 开放，以项目有权授予的范围为限；纯 AI 生成元素不主张排他版权。完整许可见 `LICENSE-CC0-1.0.txt`，规范文本位于 https://creativecommons.org/publicdomain/zero/1.0/ 。图像源、原始提示词保留，导出脚本及应用代码仍为 GPL-3.0-or-later。本地模拟器截图不随公开仓库上传。

琥珀橙色的连续胶片弯成 S 形，对应 SPEKTRALENS 名称；齿孔、透光边缘和深色底呼应胶片显影及现有黑橙界面。图案不包含文字，方便在小尺寸使用。

## 文件

- `film-s-source.png`：内置 image_gen 生成的原始透明图案，保留原始像素及透明通道。
- `generation-prompt.txt`：本次生成的完整提示词。
- `icon-1024.png`、`icon-512.png`：完整不透明方形图标，可用于设计交付或商店素材准备。
- `adaptive-foreground-1080.png`：带留白的透明 Android 前景图层；对应 108dp 图层画布。
- `preview-rounded.png`、`preview-circle.png`：圆角方形与圆形的模拟遮罩效果。
- `preview-themed.png`：主题单色示意，具体颜色由系统主题及桌面决定。
- `contact-sheet.png`：整体、遮罩及 96/64/48/32px 尺寸对照。
- `export-metadata.json`：原图尺寸、透明像素与安全区参数。

## Android 接入

应用清单使用 `@mipmap/ic_spektra_film_s` 和 `@mipmap/ic_spektra_film_s_round`。前景图层以 mdpi、hdpi、xhdpi、xxhdpi、xxxhdpi 五种密度导出；底色为 `#111113`，由系统裁切外形。项目最低 Android 10，直接使用 `mipmap-anydpi` 自适应资源，无需旧版桌面位图资源。单色图层沿用相同图案的 alpha，支持的系统与桌面负责着色；旧系统忽略该图层。原来的启动图标保存在 `previous-launcher.xml`，需要回退时可复制回 `res/drawable/ic_launcher.xml` 并恢复应用清单中的引用。

按 [Android 自适应图标文档](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) 的 108dp 图层及 66dp 安全区制作。导出脚本把图案的有效 alpha 限制在中心直径 60dp 内，为遮罩和细微边缘留出空间。主题及厂商桌面的实际表现需在相应设备确认。

在项目根目录运行：

```sh
swift scripts/export_app_icon.swift
python3 scripts/build.py :app:assembleDebug :app:lintDebug
```

Swift 脚本只做透明边界测量、缩放、图层封装和预览排版，不重绘原图。完整源码打包脚本已包含 `design` 目录，以保留原图、提示词和复现说明。
