# 开发指南

## 环境和构建

- JDK 17，Android SDK Platform 34、Build Tools 34.0.0。
- 使用仓库中的 Gradle Wrapper（8.10.2）。Android Gradle Plugin 8.2.2、Kotlin 1.9.22。
- 推荐用 Android Studio 打开项目根目录；首次打开需要下载依赖。
- 配置 `ANDROID_HOME`，或在本机 `local.properties` 中填写 SDK 路径；此文件不提交到仓库。

Windows 执行 ` .\gradlew.bat :app:assembleDebug `；macOS / Linux 执行 `./gradlew :app:assembleDebug`。产物在 `app/build/outputs/apk/debug/`。

Windows 10/11 x64 上可构建桌面 MSI：

```powershell
.\gradlew.bat :desktop:packageMsi "-PdesktopBuildDir=C:/pengshi-desktop-build"
```

MSI 会生成在 `C:/pengshi-desktop-build/compose/binaries/main/msi/`。桌面版使用 Compose Desktop、SQLite JDBC 和 Sherpa-ONNX 本地语音；首次启动时初始化四级、六级、考研英语词库及静态例句。Windows 用户数据默认保存在 `%LOCALAPPDATA%\PengshiWordsOpenSource\`，可在设置中更改位置；GitHub 私有仓库加密同步由 `core/sync` 与桌面适配层提供。

Android 安装包只包含 ARM64 本地语音运行库。增加其他架构时，需要同时补齐对应 JNI 库并调整 `abiFilters`。JDK 21 也可运行当前 Wrapper，但源码编译目标保持 Java 17。

## 代码入口

| 入口 | 职责 |
| --- | --- |
| `app/.../MainActivity.kt` | Android Activity 入口 |
| `app/.../App.kt` | 容器组装、持久化、页面状态和功能协作 |
| `desktop/.../DesktopMain.kt` | Windows 桌面入口和应用生命周期 |
| `desktop/.../DesktopContainer.kt` | 桌面 SQLite、学习仓储、初始化和 Windows 语音组装 |
| `desktop/.../ui/` | Windows 桌面页面 |
| `app/.../startup/` | 首次启动及内置词库初始化 |
| `app/.../navigation/` | 页面导航和返回规则 |
| `app/.../feature/` | home、study、decks、settings、stats 界面与状态 |
| `app/.../domain/` | 学习计划、提交反馈、恢复会话等用例 |
| `core/database/` | 数据实体、DAO、仓储及 Room 迁移 |
| `core/scheduler/` | FSRS、每日选词与当日复习策略 |
| `core/import/` | 本地词库解析 |
| `core/backup/` / `core/sync/` | 备份协议、加密与同步 |

表中 `app/...` 表示 `app/src/main/java/com/pengshi/words/`。领域模块位置见根 README 的项目分类。

`SubmitFeedbackUseCase` 用 `SameDayMemoryProgress` 决定单词是否回到当天队列。同一计划词当天第一次反馈后，后续反馈仍记录并推进队列，但保留第一次反馈生成的 FSRS 状态、到期时间和上次复习时间；重复反馈不会再次延长或缩短长期间隔。复习次数仍递增，用作同步事件版本号。

## 语音接入

`core/speech-api` 定义 `SpeechEngine`、`SpeechVoiceOption`、`SpeechVoiceCatalog` 和 `MAX_ENABLED_VOICES = 3`。音色以「引擎包名 + 音色名」区分。

`AndroidSpeechEngine` 持有音色目录的 `StateFlow`，将内置音色、应用内下载或导入的模型和系统扫描结果合并。`availableVoices()` 只返回启用音色，供学习和词条页面使用；管理页读取完整目录。启用限制同时在语音层执行，不能仅依赖界面隐藏按钮。启用列表与默认音色存放在本机 `speech-models` 偏好中，不随模型文件迁移。模型保存在 `noBackupFilesDir/speech-models`；预设资源下载后核对 SHA-256，用户导入时通过系统文件夹选择器复制包含 `.onnx` 与 `tokens.txt` 的文件。删除时释放运行实例并移除模型目录。

`InstalledSpeechEngines` 按 Android `TTS_SERVICE` 查询已安装服务，初始化后筛选已安装的离线英语音色。扫描有超时和代次隔离，播放直接绑定对应引擎；停止或释放时一并关闭语音服务。应用清单保留 TTS 包可见性查询。

内置模型由 Sherpa-ONNX VITS 执行，eSpeak 数据首次使用时解压到应用私有目录。上游 `Tts.kt` 及 ARM64 JNI 库版本固定到 1.13.8；上游 Kotlin 接口保留原包名，JNI 的类名和字段名不能被混淆。来源和哈希记录在 `app/src/main/assets/speech-runtime/SOURCE.json`，许可见 `THIRD_PARTY_NOTICES.md`。

扩展其他模型格式时，应提供实际的模型校验、生命周期管理和错误反馈，再接入同一个音色目录。当前 Android 入口支持三个固定的应用内下载模型及已安装的离线英语 TTS 引擎，不解析用户选择的裸 ONNX 文件。

Windows 的 `EmbeddedWindowsSpeechEngine` 读取随包内置的 LJSpeech，并扫描安装目录旁 `speech-models\external\` 下符合 `vits-piper-en_*` 命名、含 `.onnx` 和 `tokens.txt` 的模型目录。目录不可写时回退 `%LOCALAPPDATA%\PengshiWordsOpenSource\speech-models\external\`，也允许用户在设置中自选。启用音色与默认音色保存在当前 Windows 用户的语音设置文件中；最多启用三个，扫描出更多模型不会增加学习页按钮。

## 数据与资源分类

- `core/database/schemas/` 是数据库**结构定义**，用于迁移，不含用户词条或学习记录，应随结构更新保留。
- `app/src/main/assets/wordpacks/` 是公共词库及例句资源，各词包附带来源说明。
- `wordpacks/kaoyan-shared/` 对应只读的「考研英语(2024大纲)」，是基于 2024 英语一大纲整理的英语一/二共用词包；词目、释义、分类和排序采用 CC BY-NC-SA 4.0，音标和词性字段注明 ECDICT MIT 来源。小学常见词和基础功能词只从自动每日新词候选中排除，词条仍可搜索和手动加入。
- `wordpacks/generated-examples/` 保存预先整理的离线例句；应用构建和运行不依赖本地语言模型。
- `app/src/main/assets/vits-piper-en_US-ljspeech-medium-int8/` 是内置公共语音模型，不是个人数据。
- Windows 安装包带入 `wordpacks/` 公共词库、例句与一个 LJSpeech 模型；不会带入 Android JNI 库或外部音色。
- 应用运行后生成的数据库、凭据、快照和备份属于用户数据，不加入源码或发行包。

修改词包后同步更新 manifest 校验值和来源声明。修改数据库时补充迁移，不以清空用户数据库代替迁移。

## 协作约定

使用 Kotlin 官方代码风格；将业务规则放在对应 `core` 模块或用例中，界面主要处理呈现与交互。贡献流程见 [CONTRIBUTING.md](../CONTRIBUTING.md)。

提交前构建应用，检查修改涉及的界面与实际功能，并在变更说明中写出已确认的范围及剩余限制。不要提交设备数据、开发者绝对路径、签名密钥、访问令牌或构建缓存。
