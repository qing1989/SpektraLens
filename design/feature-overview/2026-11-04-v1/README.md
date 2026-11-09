# 光谱暗房 · SPEKTRA 特色一览 v1

> 项目现名为 **SpektraLens**（2026-11-05 起）。本目录的历史图片、生成提示词与交付文件名保留制作时的记录；后续项目展示使用新名称。

制作日期：2026-11-04。根据用户提供的 GRLife 功能拼贴图参考版式，为本项目制作单张竖版功能海报。采用内置 imagegen 工具生成，未使用 CLI/API 回退。

## 成品

- `SPEKTRA-特色一览-v1.png`：最终 PNG，1086 × 1448，3:4 比例。
- 项目 `deliverables/光谱暗房-SPEKTRA-特色一览-v1.jpg`：同尺寸、JPEG 质量 94 的分享副本。
- `generation-prompt.txt`：首次生成的完整提示词。
- `refinement-prompt.txt`：仅明确 JPEG 8 位、PNG/TIFF 16 位标注的修订提示词。

## 配图与呈现

使用用户提供的三张照片：Zac Taheriyan（`zac-taheriyan-l7b9WMeBGHw-unsplash.jpg`）、Yunus Tug（`yunus-tug-hQAyTS-7nng-unsplash.jpg`）、Bruno BD（`bruno-bd-BmTcWtu9V-o-unsplash.jpg`）。底部保留作者及 Unsplash 署名。参考图仅用于版式，不沿用其品牌、照片和功能声明。

图中的胶片色彩、颗粒、肤色、片边、日期、对比示意均由图像生成工具合成，未通过本项目显影引擎输出，因此明确标注“配图效果为视觉示意，非应用实测输出”。主视觉的四款胶片名来自当前项目胶片目录。

原始 JPEG 输入首次被生成工具拒绝；随后使用 macOS `sips -Z 2048 -s format png` 制作上传用缩略副本后成功。缩略副本位于 Git 忽略的 `generated_images/feature-overview-inputs/`，未改写用户原始照片。最终 PNG 直接复制工具输出，JPEG 使用 `sips` 编码，无额外重绘。

## 文案核对

按当前工作区 `README.md`、`android/app/src/main/assets/catalog.json` 及 `android/app/src/main/java/app/spektrafilm/MainActivity.java` 核对功能：20 款胶片、8 款印相材料、光谱计算、RAW 导入、曲线/HSL/色彩平衡、颗粒/光晕/柔化、片边日期、原图对比、逐照片保存与撤销重做、本机显影、负片与反转片、原尺寸 JPEG 8 位及 PNG/TIFF 16 位导出。

已目视核对最终版中文、主要数字、输出位深、署名、视觉示意说明及卡片边界；使用 `sips` 校验两份成品格式与尺寸。仅新增宣传素材，未修改应用实现或现有设计。
