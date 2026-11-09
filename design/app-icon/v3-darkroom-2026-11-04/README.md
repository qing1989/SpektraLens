# 光谱暗房 · 真实暗房器具图标选型

> 项目现名为 **SpektraLens**（2026-11-05 起）。本目录的历史图片、生成提示词与交付文件名保留制作时的记录；后续项目展示使用新名称。

日期：2026-11-04。本轮根据“希望跟真实的胶片冲洗中用到的元素有关”的要求，从实际器具和流程中提取轮廓，并延续简洁、少细节的方向。

| 编号 | 名称 | 真实来源 | 提炼方式 |
| --- | --- | --- | --- |
| A | 片芯螺旋 | 显影罐中的装片片芯 | 螺旋导轨、中心孔与装片入口 |
| B | 冲洗罐 | 日光型胶片冲洗罐 | 密封盖、漏斗肩部与短圆筒 |
| C | 显影盘 | 暗房相纸显影用浅盘 | 倒液口、药液和浸入的相纸 |
| D | 暗房红灯 | 黑白相纸暗房的安全灯 | 灯罩、支座和红色透光罩 |
| E | 放大机 | 底片放大制作照片的设备 | 机头、立柱、投射光束与相纸 |
| F | 晾片夹 | 冲洗后悬挂底片的夹子 | 夹持结构与短段垂直底片 |

其中 A、B、F 对应胶片冲洗及干燥，C、D、E 来自相纸暗房的显影、照明和放大流程。

六组原图使用内置 `image_gen` 生成。`generation-prompts.json` 保留完整提示词及参考资料链接；每组 `source.png` 保留生成原始文件。`contact-sheet.png` 为六组总览，`detail-sheet.png` 汇总圆角、圆形与小尺寸预览。

每组目录还包括 `icon-1024.png`、`preview-rounded.png`、`preview-circle.png` 和 96/64/48/32px PNG。`export-previews.swift` 仅做缩放、预览遮罩与排版，不重绘原始图案。

参考资料用于核对器具与流程，没有复制任何厂商品牌标记或产品照片：

- [Paterson 冲洗罐说明书](https://patersonphotographic.com/wp-content/uploads/2025/05/Tanks.pdf)
- [ILFORD 暗房入门](https://www.ilfordphoto.com/beginners-guide-darkrooms/)
- [ILFORD 放大制作准备](https://www.ilfordphoto.com/preparing-to-print/)

重新导出预览：

```sh
swift design/app-icon/v3-darkroom-2026-11-04/export-previews.swift
```

本轮为选型稿，选定方向后再制作正式 Android 自适应图标图层。
