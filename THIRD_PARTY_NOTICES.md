# 来源与许可

公开仓库版本的统一组件清单、版权和来源索引见 [UPSTREAMS.md](UPSTREAMS.md)，项目贡献声明见 [NOTICE](NOTICE)。各上游原始许可证保持原样，不因本项目采用 GPL 而改变。

## 当前胶卷图标与历史交付

SpektraLens 的 20 款胶卷图标由本项目根据胶片目录绘制。开发和公开版本均使用同一套代码和配色。历史版本曾获授权使用 11 张 GRLife PNG，这些图片及含有原图的历史交付物继续受原授权限制。历史来源与校验值见 [历史胶卷图标来源](docs/历史胶卷图标来源.json)。

AndroidX ExifInterface 的实际运行时依赖还包括 AndroidX Annotation/annotation-jvm 1.8.1、Kotlin stdlib/common 1.7.10、JetBrains Annotations 13.0、JSpecify 1.0.0，均保留 Apache-2.0 条款及原作者声明。新补充的 `JVM-Dependency-NOTICES.txt` 从官方发布的源码制品提取版权行，包括 JetBrains、Kotlin contributors、Sascha Weinreuter 与 JSpecify Authors。NDK 静态 C++ 运行库与编译器运行时的原始 LLVM/第三方声明保留于 `NDK-LLVM-runtime-NOTICE.txt`，包括 LLVM exception。完整许可可从应用“关于 → 许可全文”查看。

本项目生成的启动图标和合成测试图像按 CC0-1.0 开放（以可授予的权利为限）；图标提示词与源图保留。图标导出脚本、公开文字标签绘制代码及 Android 修改仍采用 GPL-3.0-or-later。

## 物理模型与数据

Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 提供原始光谱胶片/印相模型与彩色胶片、相纸数据。程序许可为 GPL；JSON 配置注明 CC BY-SA 4.0。各文件内的来源、版权和 citation 保持原样，完整许可见 `SpektraLab/license/` 与 `android/app/src/main/assets/licenses/`。

## 原生引擎

[JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab)，作者 Hanze Qiu 及贡献者，提供 C++ 原生模型与 Vulkan 后端。此移植使用当前本地 checkout：

```
267ee24380091e5bee87658d00f6c33d77321194
```

该版本的 `engine/` 文件保持原样；v0.2.2 在 Android CMake 对 `vulkan_gpu.cpp` 的单个 loader 调用采用编译宏重定向，接入独立进程的诊断与随包 CPU loader。胶片数据、着色器与数值自检保持原样。Android 的 CMake 仅编译其中的可移植核心、管线、Vulkan 后端和非 Apple 适配；不编译 Metal/SwiftUI 或桌面窗口；v0.2 开始编译其便携 RAW 解码与输出编码模块。许可 GPL-3.0-or-later。Android 层新增 JNI、系统图像解码、sRGB 输出、图库和界面。

Khronos [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) 固定 `vulkan-sdk-1.4.341.0`，commit `b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2`；许可见 `third_party/Vulkan-Headers/LICENSE.md` 与 `LICENSES/`。Volk 来自 SpektraLab 的第三方目录，MIT 许可。未向 APK 链接 metal-cpp。

## 示例照片

Fronalpstock big.jpg，作者 Hannes Röst，来源 [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Fronalpstock_big.jpg)。采用 CC BY-SA 3.0 Unported：<https://creativecommons.org/licenses/by-sa/3.0/>。打包的是 Wikimedia 的 1280px 缩略版本，未进一步修改文件；应用内显影预览、首页裁切展示和示例导出属于该照片的改编。传播这些示例输出时应保留署名并遵守对应的分享条款。用户自己的照片不因此改变许可。

## 胶卷图标

胶卷外壳、卷芯、标签、片头和推片标记由本项目以几何路径绘制。绘制代码、型号定义和当前渲染出的图标使用 GPL-3.0-or-later。Kodak、Fujifilm 及型号文字用于识别模拟材料。参见 [图标设计与型号清单](docs/图标映射.md)。

Gradle Wrapper 按 Gradle 项目的 Apache-2.0 条款使用；其 launcher 文件头保留原始版权说明。

## v0.2 RAW、调色和编码

[LibRaw/LibRaw](https://github.com/LibRaw/LibRaw) 固定 0.22.2：commit `b93f6e45c194f5df9b02a43b1af9a54b4f41f33f`，tree `0fd7d10c62714db90d9fa922230916a030c2d6d3`。保留全部源码于 `third_party/LibRaw-0.22.2`，采用项目提供的 LGPL-2.1 许可选项（COPYRIGHT 与 LICENSE.LGPL 同时随 APK 和源码包交付）。没有修改 LibRaw 源码；静态库通过 CMake 重新构建，接收者可替换源目录并重新链接整个 GPL 应用。解码集成复用 SpektraLab `raw_decoder` 的 compatible16 策略。

JPEG/TIFF 编码、ICC/EXIF 和色彩空间描述复用 SpektraLab 的 host 输出模块；`output_spaces.cpp` 摘取其 `image_io.cpp` 中两个色彩空间描述，保留来源说明和 GPL 许可。stb_image / stb_image_write 来自该 checkout 的 `engine/third_party/stb`，完整双许可文本见 APK `licenses/stb-LICENSE.txt`。PNG16 编码调用 Android NDK 系统 zlib。扫描侧调色次序参考 SpektraLab Canvas shader，在 Android 适配层实现；触控曲线、裁切框、片边与日期布局为本项目代码，未复制 uCrop 源码。

[AndroidX ExifInterface 1.4.2](https://developer.android.com/jetpack/androidx/releases/exifinterface) 用于读取普通图像的 EXIF；Apache-2.0 许可，来源为 [Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/exifinterface/)。相应 Apache-2.0 文本随 APK 交付。其依赖的 AndroidX Annotation 与 JSpecify 注解沿用原始条款与包内声明。

本地验证用 Nikon D3S 样片 `iss030e122639.NEF` 来自 [rawpy 官方测试目录](https://github.com/letmaik/rawpy/tree/main/test)，只在 `.tooling/raw-fixtures` 使用，不随 APK 或交付源码打包。测试 DNG `bayer-no-preview.dng` 由本项目脚本生成，没有内嵌预览；生成的色卡像素与测试元数据以 CC0 提供，不含相机用户照片。


## v0.2.2 CPU 兼容显影

[Google SwiftShader](https://github.com/google/swiftshader) 固定 commit `cddb5c91b23ff70a1f607ea31610aa0d7f4b5ad5`，Apache-2.0。Copyright The SwiftShader Authors。随包的 `libvk_swiftshader.so` 为本项目从固定源码构建的无窗口 Android CPU Vulkan 库，并非 Google 发布的预编译 Android 产品。未修改原始源码；Android NDK 构建在生成的 Ninja 文件中适配 linker 路径、Bionic pthread 与原有 Android 匿名 JIT 内存路径。具体参数和适配见 `scripts/build_swiftshader.py` 与 `docs/兼容显影实现.md`。

固定源 ZIP `third_party/SwiftShader-cddb5c91-source.zip` 来自该 commit 的 `git archive`，SHA-256：`6d1e0f0b373979429ab15adc706087d6c447190bb18946de5192690bcc460033`。完整固定源码及其中版权声明随源码交付；APK 包含 SwiftShader、Marl、SPIRV-Tools（Apache-2.0）、SPIRV-Headers（其 MIT 及文件例外）、LLVM 10（Apache-2.0 WITH LLVM-exception）及 LLVM Support 附加声明，路径为 `assets/licenses/SwiftShader-NOTICES.txt`。Henry Spencer regex 的版权与条件同样保留。LLVM 10 顶层许可补自 [llvmorg-10.0.0 官方许可](https://github.com/llvm/llvm-project/blob/llvmorg-10.0.0/llvm/LICENSE.TXT)，未替换或改写其文本。

本版本不编译 SwiftShader 的 ASTC、测试、基准、窗口系统或 Android 私有 HAL/gralloc 模块。SwiftShader 在私有 CPU 显影进程运行，通过本项目 loader 接口被加载；其许可证保持独立，不改授为 GPL。没有添加联网、上传照片或动态下载引擎的行为。

## v0.2.3 社区移动 GPU 适配

来源：[SAINTTaiYi/SpektraLab-Android v1.3.3j](https://github.com/SAINTTaiYi/SpektraLab-Android/tree/v1.3.3j)，GPL-3.0-or-later；保留社区与上游贡献者声明。固定原始源码 ZIP 随完整源码包提供：`third_party/SpektraLab-Android-v1.3.3j-source.zip`，SHA-256 `924d51aeca04e26898d7557ecf75323b7879180baba6d0fcc02d2b7b90ec9179`。社区原始 `patches/engine-android.patch` SHA-256 `50af4a64e8a666f37cbc9c9335a6e78fb150b12c741ff40607198a935fed4788`，其中 GPU 部分以 SpektraLab `445e314e28b9b455d2457471fadaffd28c07270b` 为基础。

本项目原始 SpektraLab checkout 仍固定 `267ee24380091e5bee87658d00f6c33d77321194`。通过独立补丁适配上述移动驱动、分块与窗口着色器；修改注明版本，全部对应源文件可由 `scripts/prepare_engine.py` 重建。补齐缺失的 tiling helper，并修正逻辑容量、halo 对齐、尾部线程、帧坐标及映射结果生命周期；不提升驱动报告的上限，不修改测量材料数据。没有引入社区客户端、遥测或更新检查。APK `licenses/SpektraLab-Android-*` 保留社区许可与原模型声明；完整原始社区源码及本项目修改补丁随源码包交付。详细基线与修改范围见 `patches/android-gpu-base.json`、`docs/移动GPU移植.md`。

## v0.2.4 补绘胶卷标签

历史版本曾在授权 GRLife 标签的既有版式上，为未匹配的 7 款胶片绘制独立标签：C200、Provia 100F、Velvia 100、X-Tra 400、Verita 200D、Vision3 200T、Vision3 50D。文字与配色由本项目定义，当时的绘制代码为 `FilmLabelArtwork.java`，随项目按 GPL-3.0-or-later 提供；原始 11 张 GRLife PNG 没有改写。新标签和原标签使用同一 `FilmBadge` 胶卷外壳、片头和状态标记。型号、来源及对应厂商资料见 `docs/图标映射.md`。
