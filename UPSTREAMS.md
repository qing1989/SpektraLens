# Upstream projects, copyright and licenses

SpektraLens is an independent Android photo editor and port. It is not an official release of SpektraLab, spektrafilm, GRLife, Kodak or Fujifilm. Original copyright and license notices are preserved; the root GPL license does not relicense the separate components or artwork below.

## Code and model sources

| Component / authors | Pinned source or resolved version | License / local notices |
|---|---|---|
| [spektrafilm](https://github.com/andreavolpato/spektrafilm), Andrea Volpato | Original film/paper model, measured profiles and LUTs carried by the pinned SpektraLab source | Program GPL; profiles and direct LUT encodings CC BY-SA 4.0. Preserve `SpektraLab/license/SPEKTRAFILM_LICENSE.txt`, profile attribution/citations and the upstream citation file |
| [SpektraLab](https://github.com/JamesQiu2005/SpektraLab), Hanze Qiu and contributors | `267ee24380091e5bee87658d00f6c33d77321194`, Git submodule `SpektraLab/` | GPL-3.0-or-later; `SpektraLab/LICENSE.txt`, `SpektraLab/license/` |
| [SpektraLab-Android](https://github.com/SAINTTaiYi/SpektraLab-Android), SAINTTaiYi and upstream contributors | v1.3.3j source ZIP, SHA-256 `924d51aeca04e26898d7557ecf75323b7879180baba6d0fcc02d2b7b90ec9179` | GPL-3.0-or-later; original ZIP and `assets/licenses/SpektraLab-Android-*` retained |
| [LibRaw](https://github.com/LibRaw/LibRaw), LibRaw LLC and listed contributors | 0.22.2, commit `b93f6e45c194f5df9b02a43b1af9a54b4f41f33f`, `third_party/LibRaw-0.22.2/` | LGPL-2.1 option; original `COPYRIGHT`, `LICENSE.LGPL`, `LICENSE.CDDL` retained. Includes Dave Coffin, Jacek Gozdz, Roland Karlsson, Adobe and other notices in its COPYRIGHT |
| [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers), Khronos Group and contributors | vulkan-sdk-1.4.341.0, commit `b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2` | Per-file Apache-2.0 / MIT choices and exceptions; `third_party/Vulkan-Headers/LICENSE.md`, `LICENSES/` |
| [volk](https://github.com/zeux/volk), Arseny Kapoulkine | Version vendored by pinned SpektraLab | MIT; `SpektraLab/engine/third_party/volk/LICENSE.md` |
| [stb](https://github.com/nothings/stb), Sean Barrett and contributors | Version vendored by pinned SpektraLab | MIT or public-domain option; complete `stb-LICENSE.txt` retained |
| [SwiftShader](https://github.com/google/swiftshader), The SwiftShader Authors | `cddb5c91b23ff70a1f607ea31610aa0d7f4b5ad5`; complete source ZIP stored with Git LFS | Apache-2.0; source SHA-256 `6d1e0f0b373979429ab15adc706087d6c447190bb18946de5192690bcc460033` |
| [Marl](https://github.com/google/marl), Google and contributors | Version inside that SwiftShader archive | Apache-2.0; `third_party/SwiftShader-LICENSES/third_party/marl/LICENSE` |
| [SPIRV-Tools](https://github.com/KhronosGroup/SPIRV-Tools) | Version inside that SwiftShader archive | Apache-2.0; original notices retained |
| [SPIRV-Headers](https://github.com/KhronosGroup/SPIRV-Headers) | Version inside that SwiftShader archive | MIT and file-specific exceptions; MIT / CC-BY-4.0 texts retained in `third_party/SwiftShader-LICENSES/` |
| [LLVM 10](https://github.com/llvm/llvm-project), LLVM contributors | Version inside SwiftShader | Apache-2.0 WITH LLVM-exception and original additional notices; `SwiftShader-NOTICES.txt`, `third_party/SwiftShader-LICENSES/` |
| Henry Spencer regex | Version carried by LLVM Support | Original permissive terms, `third_party/SwiftShader-LICENSES/third_party/llvm-10.0/llvm/lib/Support/COPYRIGHT.regex` |
| [AndroidX ExifInterface](https://developer.android.com/jetpack/androidx/releases/exifinterface), AOSP | 1.4.2 | Apache-2.0; `Apache-2.0.txt`, AOSP source attribution in third-party notices |
| [AndroidX Annotation](https://android.googlesource.com/platform/frameworks/support/), AOSP | annotation / annotation-jvm 1.8.1 | Apache-2.0; resolved dependency of ExifInterface |
| [Kotlin](https://github.com/JetBrains/kotlin), JetBrains and Kotlin contributors | kotlin-stdlib / kotlin-stdlib-common 1.7.10 | Apache-2.0; `JVM-Dependency-NOTICES.txt` preserves copyright notices from the published source artifacts |
| [JetBrains Annotations](https://github.com/JetBrains/java-annotations), JetBrains and listed contributors | 13.0 | Apache-2.0; original notices include Sascha Weinreuter and are retained in JVM dependency notices |
| [JSpecify](https://github.com/jspecify/jspecify), The JSpecify Authors | 1.0.0 | Apache-2.0; original copyright statements retained in JVM dependency notices |
| [Android NDK / LLVM runtimes](https://github.com/android/ndk), LLVM contributors | NDK 27.2.12479018, static C++ runtime and compiler runtime support | Apache-2.0 WITH LLVM-exception and per-component notices, preserved verbatim as `NDK-LLVM-runtime-NOTICE.txt` |
| zlib, Jean-loup Gailly and Mark Adler | Android platform `libz` | zlib license; accessed as a system library, not a separate redistributed binary |

Runtime notices above are under `android/app/src/main/assets/licenses/` and available from the application's About screen. Vendored source archives retain their own complete license trees, including dependencies not compiled into this Android build. Android framework APIs, platform image decoding and system fonts are used through the OS; their implementation is not copied into this repository. Gradle Wrapper, Gradle 8.11.1 and Android build tools are build dependencies, not changes to the model license.

## Android modifications and corresponding source

The upstream submodule remains unchanged. The original community patch base is `445e314e28b9b455d2457471fadaffd28c07270b`; this project adapts it to the pinned upstream through `patches/android-gpu-v0.2.3.patch`. All 44 covered paths and base hashes are recorded in `patches/android-gpu-base.json`. `scripts/prepare_engine.py` recreates the modified engine, including transfer bounds, row windows, halos, strict math fallback and result ownership fixes. Other changes live in the Android/JNI files and are identified as project contributions. No measured film/paper data is rewritten to fix a renderer issue.

LibRaw is unmodified and built from the supplied source. The provided Android CMake project allows replacing that source and relinking the application. SwiftShader's complete source, dependency licenses, exact archive checksum and Android build adapter are supplied. Ordinary image exports reopen the source file; the Android editing and rendering implementation is included. See [GPL v3](https://www.gnu.org/licenses/gpl-3.0.html), [the GNU corresponding-source guidance](https://www.gnu.org/licenses/gpl-faq.html#WhatDoesWrittenOfferValid) and the preserved component licenses for the actual terms.

## Photographs, icons and reserved material

- **Demo photograph:** *Fronalpstock big.jpg*, Hannes Röst, [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Fronalpstock_big.jpg), [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/). The bundled 1280px thumbnail is unmodified. Redistributed simulated versions of that demo are adaptations with their own attribution/share-alike obligations. This does not relicense users' own photographs.
- **Historical GRLife PNGs:** the former local/private builds used 11 separately authorized PNG labels. The images and archives containing them retain their prior distribution restrictions. Original URLs and hashes are recorded in `docs/历史胶卷图标来源.json`.
- **Current cartridge artwork:** SpektraLens draws all 20 stock configurations from the project catalog, original label palettes and native paths. The canister renderer, recipes and rendered project icons are GPL-3.0-or-later. All current builds exclude the old PNG library. Film names identify the simulated stocks.
- **S-shaped film launcher artwork:** generated for this project, with source PNG, original generation prompt and export script retained in `design/app-icon/v1/`. Offered under CC0-1.0 to the extent the project can grant rights; no exclusive rights are asserted in purely AI-generated elements. Export scripts and app integration code remain GPL-3.0-or-later. See the design README and CC0 text.
- **Synthetic chart / DNG test data:** generated by this project; offered under CC0-1.0. The rawpy Nikon test fixture is downloaded only into ignored local tooling and is not included in the APK or this repository.
- **Film parity references:** `android/app/src/androidTest/assets/film-reference/` contains synthetic reference renders from the upstream Metal engine, with model/profile attribution retained. Those reference renders are CC BY-SA 4.0; their input chart is CC0-1.0, and generator/test code is GPL-3.0-or-later. They are test assets, not application film profiles.
- **UI references:** PhotonCam was a layout/workflow reference; no PhotonCam source, screenshots, icon assets or proprietary photo filters are redistributed.

Profiles, photographs, notices and separate dependencies retain their own licenses. Copyright statements, legal text and author citations are not removed or replaced by the project's GPL notice.
