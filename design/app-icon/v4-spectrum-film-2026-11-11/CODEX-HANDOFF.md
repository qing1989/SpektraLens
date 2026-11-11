# 交给 Codex 的任务说明（可整份粘贴）

你正在继续 Qing 的 Android 应用 **SpektraLens**（包名 `app.spektrafilm`，仓库 https://github.com/qing1989/SpektraLens）。请把已经定稿的新应用图标接入工程，替换当前正在使用的胶片 S 形图标。不要重绘图标，不要改版本号（当前已是 0.4.0 / versionCode 19），不要从过期的 origin/main 重新开分支。

工作语言用中文。代码与资源按仓库现有风格。做完后简要说明改了哪些文件、如何验证，并给出与预览图对照的结果。

---

## 1. 定稿是什么

2026-11-11 Qing 已确认 v4 图标，方案简称 **C**：

- 构图：底部是 35mm 胶片齿孔条（干净的黑底片、矩形齿孔），一条柔和的七彩色束向上打进淡粉–杏色–冰青的圆形光斑。
- 圆形图形约占 1024 方稿的 **78%**，四周留出光感黑底（带轻微彩色溢光和细颗粒），不要再放大或缩小图形。
- 齿孔必须干净。后期加光时曾把颗粒叠脏，已换回干净齿孔；不要再次给齿孔叠脏斑或粗颗粒。
- 白色底方案已搁置，不要做、不要接入。

主图（不得替换、不得再生成一张「接近」的图）：

```
design/app-icon/v4-spectrum-film-2026-11-11/icon-master-1024.png
```

SHA-256：

```
7583eb6073d6b82d87a1091898cc5365e8b4d36ae5ff547b606a852159a1824a
```

若该文件不在当前工作树，到本交接目录或询问后再开始，不要用旧版 `design/app-icon/v1` 的胶片 S，也不要用 v2/v3 概念图。

Qing 已核准的观感应对照同一目录：

- `preview-icon-sheet.png`
- `preview-homescreen.png`、`preview-homescreen-dark.png`、`preview-homescreen-light.png`
- `preview-film-detail.png`（齿孔干净程度以此为准）

---

## 2. 仓库与分支

- 请在 Qing 的 Mac Mini 本地仓库工作：`/Users/mao/Documents/ChatGPT/胶片模拟 App`。
- 当前检出在 `feature/in-app-update`，比 `origin/feature/in-app-update` 超前若干提交，工作区可能还有未提交改动（关于页、更新测试、文档等）。先跑 `git status`，**不要** reset、不要丢弃或覆盖这些未提交文件，也不要从过期的 `origin/main` 重新开分支——GitHub 上的 `main` 可能仍停在 0.3.9，那样会丢掉本地已完成的 0.4.0 工作。
- 当前版本已经是 **0.4.0 / versionCode 19**（见 `android/app/build.gradle`）。本次只换图标，**不要** 改 versionName、versionCode，也不要回退到 0.3.9 / 18。发版与再升号等 Qing 明确要求。
- 建议从当前本地 `HEAD` 拉出 `feature/app-icon-v4` 再改图标；若工作区不干净，先把未提交改动保留在原处（提交或暂时搁在工作区均可，但必须能恢复）。不要把图标做成一份脱离 0.4.0 代码的独立补丁。
- 0.4.0 已切换到正式 release 签名（见 `AGENTS.md` 与 `deliverables/handoff-signing-0.4.0.md`）。不要改签名配置，不要用 debug 密钥重签。
- `design/app-icon/` 里已有未采用的概念稿：`v4-glass-2026-11-09/`、`v5-bloom-2026-11-11/` 及若干 zip。不要删除或覆盖它们。本次定稿目录是 `v4-spectrum-film-2026-11-11/`。
- 包名保持 `app.spektrafilm`。

---

## 3. 现有图标是怎么接的

沿用现有资源名，做替换即可，不必改应用清单里的引用（除非你发现引用已损坏）。

- 清单：`AndroidManifest.xml` 使用 `@mipmap/ic_spektra_film_s` 和 `@mipmap/ic_spektra_film_s_round`。
- 自适应 XML：`android/app/src/main/res/mipmap-anydpi/ic_spektra_film_s.xml` 与 `_round.xml`。
- 前景：`drawable-mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi/ic_spektra_film_s_foreground.png`，尺寸分别为 108 / 162 / 216 / 324 / 432。
- 背景色：`values/app_icon.xml` 中的 `spektra_icon_background`，现在是 `#111113`。新图标光感黑底接近纯黑，请改为 `#000000`，以免遮罩边缘露出偏灰的旧底色。
- 单色图层：目前与彩色前景是同一张图。新图标是彩色光斑，直接拿来当 monochrome 会被系统染色且发糊。请另外做一张简化的 **alpha 单色稿**（齿孔条 + 向上光束的剪影即可），只用于 `<monochrome>`，不要把彩色主图填进去。单色稿的构图位置必须与彩色稿对齐（同样落在可见的 72 dp 里）。

旧的 v1 胶片 S 原文件保留在 `design/app-icon/v1/`，不要删。把 v4 目录一并纳入版本库（若尚未加入）。

---

## 4. 自适应图层的缩放（关键，不要套旧脚本）

Android 自适应图标图层是 **108 dp**，系统遮罩大约只露出中心 **72 dp**，外侧 18 dp 是出血，可能被裁掉。

Qing 核准的预览，是把 `icon-master-1024.png` **整张** 做圆角遮罩。因此：

- **1024 主图必须正好铺满可见的 72 dp**，而不是铺满 108 dp。
- 若把 1024 直接当成 108 dp 前景，齿孔和圆形上沿会被裁掉，这是错误的。
- 也不要再额外套一层「60 dp 安全区」或再次 78% 缩小，那会留白过大，和预览不符。

正确做法（已有现成文件，请优先使用）：

1. 使用 `adaptive-foreground-1536.png`：1536×1536 画布上，1024 主图居中，四周纯黑出血。1536 的中心 1024 恰好对应 72/108。
2. 再把这张图缩到各密度前景：mdpi 108、hdpi 162、xhdpi 216、xxhdpi 324、xxxhdpi 432。同目录 `exported-foregrounds/` 已有参考导出，可复制为 `ic_spektra_film_s_foreground.png` 放入对应 `drawable-*`。
3. 或使用 `adaptive-foreground-1080.png`（中心 720 对应 72 dp），效果相同，但 1536 更接近原图像素。

**不要** 运行或照搬 `scripts/export_app_icon.swift` 的旧逻辑。它针对 v1 的透明胶片 S：会按不透明像素收进 60 dp，并要求源图有大量透明通道。拿 v4 不透明方稿去跑会失败或比例错误。若要更新导出脚本，请新写（Python/PIL 即可，云电脑是 Linux，没有 AppKit），专门处理「不透明 1024 主图 → 居中放入 108 dp 图层的 72 dp 可见区」。不要改坏 v1 的复现脚本，或把 v4 逻辑单独放在 `scripts/export_app_icon_v4.py`。

---

## 5. 具体要做的事

1. 确认工作树在本地 0.4.0 代码上（`versionName` 已是 0.4.0，`versionCode` 已是 19）。从当前 `HEAD` 创建 `feature/app-icon-v4`，或在当前分支上继续，但不要基于过期的 `origin/main`。
2. 将本目录 `design/app-icon/v4-spectrum-film-2026-11-11/` 纳入仓库（含主图、预览、元数据、本说明）。不要动同级的 `v4-glass-2026-11-09/`、`v5-bloom-2026-11-11/`。
3. 替换五个密度的 `ic_spektra_film_s_foreground.png`（按第 4 节缩放）。
4. 将 `spektra_icon_background` 改为 `#000000`。
5. 新增单色前景资源，并只在两个 adaptive XML 的 `<monochrome>` 中引用；`<foreground>` 仍指向彩色图层。
6. 如有必要，更新 `AGENTS.md` 或简短设计说明，写清「现行图标为光谱齿孔定稿，导出勿用 v1 的 60 dp 逻辑」。
7. 不要改版本号（保持 0.4.0 / 19）、不要改签名、不要打包发版、不要写 latest.json。应用内更新功能已在当前分支，不要重做。
8. 本地用现有构建脚本编译调试包，并截图对比 `preview-homescreen-*.png`：圆角/圆形遮罩下，光斑比例、黑边、干净齿孔应一致。若只有构建、没有真机，说明验证范围。
9. 提交一次用意明确的提交（例如「替换应用图标为光谱齿孔定稿」）。先留在本地，不要强行把 PR 打向仍停在 0.3.9 的 `main`，除非 Qing 明确要求推送；若推送，说明目标分支并避开覆盖未提交改动。

---

## 6. 验收标准

- 桌面图标的圆形光斑大小、黑边、齿孔位置与 `preview-icon-sheet.png` / `preview-homescreen.png` 一致，没有被遮罩切掉齿孔或光斑上沿，也没有比预览明显更小一圈。
- 齿孔在放大时干净，接近 `preview-film-detail.png`，没有污斑。
- 浅色和深色壁纸上，黑底与溢光仍然成立。
- 应用清单引用有效，调试包可安装。
- 主题单色图标在支持的系统上是清晰剪影，不是模糊彩图。
- `versionName` / `versionCode` 仍为 0.4.0 / 19。
- 历史 `design/app-icon/v1`（以及 v2、v3 概念）仍在。

---

## 7. 背景与不要重做的工作

应用内检查更新已在当前本地分支完成。不要重做更新功能。0.4.0 的签名迁移也已约定，不要改密钥。本次只负责把定稿图标接入工程，不负责发版、重签或上传 APK。
