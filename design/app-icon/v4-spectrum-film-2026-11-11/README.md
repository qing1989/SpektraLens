# SpektraLens 图标 v4 · 光谱齿孔（定稿）

定稿日期：2026-11-11（Asia/Shanghai）。

Qing 已确认这一版：胶片齿孔 + 向上的七彩色束，圆形图形约占方形画布的 78%，四周为带溢光和细颗粒的光感黑底。齿孔必须保持干净，不要再叠脏斑或颗粒。白色底方案已搁置，不要启用。

本目录只存放定稿原图、已核准的预览，以及按 Android 自适应图标正确缩放的前景图层。接入应用、改资源、开分支与提交由 Codex 按同目录的 `CODEX-HANDOFF.md` 执行。

## 主文件

- `icon-master-1024.png`：定稿方稿，1024×1024，不透明。桌面预览就是对这张图做圆角遮罩，不要再缩小图形。
- `icon-512.png`：由主图缩小，可用于商店或文档。
- `adaptive-foreground-1536.png`：108 dp 图层源。1536 画布上，把 1024 主图居中放置，正好铺满可见的 72 dp 区域；四周 256 px 为纯黑出血，供系统遮罩裁切。不要把主图铺满整个 108 dp，否则齿孔和光斑会被裁掉。
- `adaptive-foreground-1080.png`：同上构图的 1080 像素版本（10× 108 dp），主图被缩到中心 720 像素。优先使用 1536 版本再缩放，以避免一次额外缩小。
- `exported-foregrounds/`：已按 mdpi–xxxhdpi 从 1536 图层缩小的前景参考，可直接对照或拷入 `res/drawable-*`。
- `export-metadata.json`、`SHA256SUMS.txt`：尺寸约定与校验。

## 已核准预览

这些图是 Qing 看过并说「这版可以」的效果，接入后的桌面观感应与之一致：

- `preview-icon-sheet.png`：方稿 + 深色 / 浅色底上的圆角图标
- `preview-homescreen-dark.png`、`preview-homescreen-light.png`：深色、浅色 Android 桌面模拟
- `preview-film-detail.png`：齿孔特写（必须保持这样干净）
- `preview-squircle.png`：圆角遮罩放大

## 不要做的事

- 不要用 `scripts/export_app_icon.swift` 的旧逻辑（它会把图案收进 60 dp，而且假定源图是透明前景）。那会破坏已定的 78% 比例，或裁掉齿孔。
- 不要重绘、不要再生成一版「差不多」的图标替换主图。
- 不要启用白底版。
- 不要把本改动叠进「应用内更新」的 `feature/in-app-update` 或 [PR #1](https://github.com/qing1989/SpektraLens/pull/1)。
- 不要擅自递增 `versionName` / `versionCode`（当前仍为 0.3.9 / 18）。

本地工程当前版本已是 0.4.0 / versionCode 19。接入图标时不要改这两个字段，也不要从仍停在 0.3.9 的 `origin/main` 重新开分支。
