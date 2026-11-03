# v0.2.3 移动 GPU 移植

本项目以 SpektraLab `267ee24380091e5bee87658d00f6c33d77321194` 的材料模型及应用状态格式为基线，选择性适配 SAINTTaiYi/SpektraLab-Android v1.3.3j。社区 GPU patch 基于上游 `445e314e28b9b455d2457471fadaffd28c07270b`。固定社区源码与许可包含于 `third_party/SpektraLab-Android-v1.3.3j-source.zip`；SHA-256 与版权见根目录来源说明。

构建只在 `.tooling/android-engine-source` 生成修改版，原 `SpektraLab/` 文件保持不变。`scripts/prepare_engine.py` 逐文件验证 `patches/android-gpu-base.json`，应用 `patches/android-gpu-v0.2.3.patch`；CMake 与 shader 编译均引用生成结果。44 个适配路径含 C ABI、GPU、管线及 GLSL；资源 JSON/NPY 没有修改。完整补丁包含在源码交付中。

## 采用与修正

- 使用较新的便携 Vulkan 驱动与完整数值探针。优先验证 FMA，失败时独立验证 exact Dekker split；两者均失败则拒绝 GPU，由主机进入 CPU。保持 `-fno-fast-math -ffp-contract=off`，没有放宽探针容差。
- staging 传输最多 64 MiB，上传/读取分块。实际测试强制 4 KiB，校验 float64 到 float32 窄化的全部样本。
- 显式声明每个 buffer 的像素跨度、halo、逻辑范围。补齐社区发布遗漏的 `pipeline/tiling.hpp`；按 `maxStorageBufferRange` 与 2^24 索引精度边界划分行窗口。偏移遵守设备 alignment，halo 起点向前对齐。短图没有合法内部边界时，仅在完整绑定仍合法的条件下整体执行。
- 保留 GPU 返回的真实范围上限，没有采用社区扩大物理 GPU 范围的探测方法。IIR 或跨窗口读取等路径仍可能要求完整绑定；超限时由 Java 主机重新打开原图并切到 CPU，不能据此声称支持任意尺寸 GPU 显影。
- 缓冲区池容量与本次逻辑长度分开；descriptor 使用逻辑范围。每次 dispatch 明确实际 count，末尾工作组的填充线程提前返回。全局帧 origin 与窗口内索引分开，颗粒坐标跨块连续。
- 映射输出避免多一次 readback。`spk_result.owner` 持有结果和引擎生命周期；先销毁引擎时延后释放 GPU，最后 `spk_result_free` 才完成销毁。引用计数的增减共用引擎锁。结果对象清零后可再次 free。外部调用方仍必须恰好管理自己取得的每个结果，不能复制 owner 后独立释放。

## Android 主机

`RendererService` 新增私有 `OPEN_FILE=10`：在子进程通过 ImageDecoder 读取原文件、软件分配、转换 sRGB/ARGB8888，再调用 JNI。`SpectralRenderer` 保存最近打开源与参数，GPU 请求遇到明确驱动/范围错误、死亡或超时后只重试一次 CPU。格式或参数错误直接报告。关闭图像或 renderer 时清理源引用。

自动/GPU/CPU 选择由本地偏好保存；GPU 选择表示重新尝试本机驱动，同样保留严格自检与故障回退。诊断与导出配方包含实际后端、`mobile-v0.2.3` 标识及设备范围信息。既有 CPU 偏好不会在覆盖升级时强行重置。

没有接入社区客户端的界面、遥测、联网更新、发布脚本、胶片库扩展或全套 streaming 流程。当前已验证范围以 `验证记录.md` 为准。未在实体 Adreno/Mali、OPPO/API 37 上执行；模拟器的软件 Vulkan 不证明手机性能或兼容性。

## 对应源码重建

按 README 配置 Android SDK/NDK/JDK 后执行 `python3 scripts/build.py`，脚本会自动生成 overlay 并编译 35 个 SPIR-V。补丁、基线原文件、固定社区源 ZIP 和 CPU 源 ZIP 均在完整源码包，不依赖 `.tooling` 中的开发脚本或 Git 历史。`scripts/prepare_engine.py` 的内容指纹变化会重建生成目录；不要把生成目录里的临时修改当成已保存补丁。
