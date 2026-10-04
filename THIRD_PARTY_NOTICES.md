# 第三方资源与版权说明

项目代码采用 GNU GPL v3.0（见根目录 `LICENSE`）。以下资源保留其原有许可；不能仅凭项目代码许可证推断语音、词典、词表或例句的使用权。

## 语音运行组件

| 组件 | 使用方式 | 许可及源码 |
| --- | --- | --- |
| Sherpa-ONNX 1.13.8 | Android Kotlin TTS 接口及 ARM64 JNI、Windows JVM 接口及 x64 JNI | Apache-2.0；[对应版本源码](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8)，许可全文见 `licenses/sherpa-onnx-APACHE-2.0.txt` |
| ONNX Runtime 1.28.2 | Android ARM64 与 Windows x64 本地推理 | MIT；[对应版本源码](https://github.com/microsoft/onnxruntime/tree/v1.28.2)，许可全文见 `licenses/onnxruntime-MIT.txt` |
| eSpeak NG / Piper 适配 | 英文音素转换及 `espeak-ng-data` | GPLv3；[上游构建引用的适配源码](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1)、[Sherpa 上游集成构建配置](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/cmake)，许可全文见 `licenses/GPL-3.0.txt` |

Android JNI 和 ONNX Runtime 二进制取自 Sherpa-ONNX 官方发布的 1.13.8 ARM64 语音引擎安装包，未修改；上游 Kotlin `Tts.kt` 保留版权声明并以源码形式纳入 `core/speech`。原始下载地址、文件哈希和集成来源记录在 `app/src/main/assets/speech-runtime/SOURCE.json`。Windows JVM/JNI JAR 的文件哈希和许可记录在 `desktop/libs/THIRD_PARTY_NOTICES.txt`。

分发含 GPL 组件的安装包时，应同时按 GPLv3 提供对应源码、构建说明、依赖来源及许可，不能只发布 APK。第三方组件源码和构建依赖版本需要与分发二进制对应，详见[发布指南](docs/releasing.md)。

## 内置英语音色

内置 `vits-piper-en_US-ljspeech-medium-int8`：

- 下载：[Sherpa-ONNX 模型发布资源](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium-int8.tar.bz2)。
- 作者和模型说明：[LJSpeech medium 模型卡](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ljspeech/medium/MODEL_CARD)，本地模型目录保留 `MODEL_CARD`。
- 训练数据：[LJ Speech Dataset](https://keithito.com/LJ-Speech-Dataset/)，模型卡标注为公共领域，并说明从头训练。
- 模型来源仓库 [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) 标注 MIT；[Piper 项目原有 MIT 许可](https://github.com/rhasspy/piper/blob/master/LICENSE.md)副本保存在 `licenses/piper-MIT.txt`。数据声明、模型卡与代码许可分别保留，不能以代码许可替代数据来源判断。
- 文件名、原始地址、模型及资源哈希见模型目录的 `SOURCE.json`。

原 Lessac、Ryan、Jenny 模型不随源码或本项目安装包分发。Android 用户可在应用内从 Sherpa-ONNX 官方发布页下载到本机私有目录，Windows 用户可自行放入模型目录；下载说明和各自限制见[语音模型指南](docs/voice-models.md)。

## 词库与例句

| 来源 | 内容 | 原有许可与说明 |
| --- | --- | --- |
| [OpenEtymology](https://github.com/openetymology/OpenEtymology) | 四级、六级词表 | CC BY-SA 4.0；[数据许可](https://github.com/openetymology/OpenEtymology/blob/main/DATA_LICENSE.md) |
| [ECDICT](https://github.com/skywind3000/ECDICT) | 中文释义、音标、词性、词形和频率信息 | MIT；许可文本见 `licenses/ecdict-MIT.txt`，词包内保留来源说明 |
| [NETEMVocabulary](https://github.com/exam-data/NETEMVocabulary) | 考研英语一/二共用词表的词目、释义、分类和排序 | CC BY-NC-SA 4.0；基于 2024 年英语一大纲整理，转换并合并大小写重复词；该词包只可非商业使用，署名与授权文本见 `wordpacks/kaoyan-shared/` 和 `licenses/CC-BY-NC-SA-4.0.txt` |
| [Tatoeba](https://tatoeba.org/) | 内置六级双语例句及可选在线补充 | CC BY 2.0 FR；内置行保留句子标识，见 `wordpacks/cet6/EXAMPLES_ATTRIBUTION.txt` |
| 预生成的补充例句 | 缺少例句词条的离线补充 | 以机器生成为主，含少量人工编写或修订；MIT，来源与人工例外见 `wordpacks/generated-examples/`；不代表人工权威释义 |

每个词包的 `LICENSE.txt`、`ATTRIBUTION.txt` 和 manifest 仍随资源保留。考研词包与 GPLv3 项目代码使用不同许可；不能把非商业限制套用到 GPLv3 代码，也不能把 GPLv3 解释为允许商业使用考研词包。补充例句以静态 CSV 随项目分发；应用构建和运行均不调用语言模型，也不要求用户安装模型。

## 复习算法与其他依赖

FSRS 算法实现参考 Open Spaced Repetition / FSRS-Kotlin，保留 MIT 致谢与许可，见 `core/scheduler/NOTICE-FSRS.txt`。

应用通过 Gradle 使用 [Kotlin](https://github.com/JetBrains/kotlin)、[AndroidX（含 Room、Compose）](https://android.googlesource.com/platform/frameworks/support/)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)、[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)、[OkHttp](https://github.com/square/okhttp) 等 Apache-2.0 组件；OkHttp 的依赖 [Okio](https://github.com/square/okio) 也采用 Apache-2.0。版本由 `gradle/libs.versions.toml` 管理；发行时应保留适用的开源许可与版权信息。构建会把根许可证及本目录已有第三方许可打入安装包的 `assets/legal/`。

Android 应用内解压下载模型使用 [Apache Commons Compress 1.28.0](https://commons.apache.org/proper/commons-compress/)，采用 Apache-2.0 许可；许可文本见 `licenses/Apache-2.0.txt`。

Windows 桌面版另使用以下组件：

| 组件 | 使用方式 | 许可及源码 |
| --- | --- | --- |
| Compose Multiplatform 1.5.12 | Windows 桌面界面与打包 | Apache-2.0；[对应版本许可](https://github.com/JetBrains/compose-multiplatform/blob/v1.5.12/LICENSE.txt)，副本见 `licenses/Apache-2.0.txt` |
| SQLite JDBC 3.46.1.0 | 桌面端本地 SQLite 数据库 | Apache-2.0；其中保留的 SQLiteJDBC 代码另含 BSD 风格条款；[对应版本元数据](https://central.sonatype.com/artifact/org.xerial/sqlite-jdbc/3.46.1.0)，许可副本见 `licenses/Apache-2.0.txt` 和 `licenses/BSD-2-Clause.txt` |

Windows 安装包会带入项目许可证、第三方说明和 `licenses/` 目录。Compose Desktop 的传递依赖还可能带有各自的版权或许可文件；分发修改版时应保留打包依赖中适用的通知。

PengshiWords project code: Copyright (C) 2026 PengshiWords contributors.
