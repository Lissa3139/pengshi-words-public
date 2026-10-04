# 开发者功能地图：想改哪里，从哪里开始

这份说明按用户看得到的功能排列，再指出界面、规则、数据和素材的位置。阅读代码前，先记住三件事：

1. **手机和电脑的界面是两套代码。** Android 页面在 `app/src/main/java/com/pengshi/words/feature/`，Windows 页面在 `desktop/src/main/kotlin/com/pengshi/words/desktop/ui/`。它们都用 Kotlin Compose，但并非一份前端代码自动生成两端界面。改两端外观时，通常要分别修改。
2. **共同的学习规则在 `core/`。** 每日选词、复习调度、统计、文件导入、备份格式、GitHub 同步协议和语音接口分模块维护。`app/src/main/java/com/pengshi/words/domain/` 中的学习用例也由桌面端编译复用。
3. **目前没有独立的网页后端或云服务器。** 这里的“后端”主要是本机规则和数据库：手机用 Room，电脑用 SQLite；用户选择同步时，`core/sync/` 才与用户自己的 GitHub 私有仓库通信。

## 一、现有功能清单

以下是当前源码中能够找到的功能，按用户操作顺序归类。带“手机”或“电脑”标记的功能只在对应端有入口；两端同名页面的布局和细节也不必完全相同。

| 功能区 | 当前提供的能力 |
| --- | --- |
| 启动与导航 | 打开本地数据、加载内置词库、进入首页；首页、词库、统计、设置四个主区域；进入学习页；已配置 GitHub 同步时启动后自动尝试同步一次。手机使用底部导航，电脑使用侧边导航和独立窗口。 |
| 首页与每日计划 | 显示今日额度、已完成数量、到期复习和可学新词；复习结束后选择按仪表盘规则自动选词或跨词库手动选词；手动选词支持浏览、精准搜索和保留跨词库选择，并显示已选数量及今日剩余额度。暂不选词时首页仍显示复习完成和可学新词数；学完后显示鼓励提示。完成主任务后自动打卡，也可追加不占主额度的额外词。 |
| 学习与复习 | 展示单词、音标、词性、中文释义、例句、助记或相关词；先回想再显示答案；按记忆程度反馈并安排下次复习；查看学习进度；朗读单词和例句；收藏、搜索词条、把词加入今日学习、回看前一个词。手机学习页另有临时的“重点”标记；它目前只是页面状态，不应当作已持久保存的学习标记。 |
| 词库与个人词条 | 浏览内置词库和个人词库，按英文或中文搜索；新建个人词库；在可编辑的个人词库内添加、修改、删除词条及例句；批量粘贴或导入词条。内置词库在应用里只读，贡献者可修改仓库里的词包文件。 |
| 自动选词范围 | 在“选词仪表盘”选择参与每日新词的词库、设置词库占比、排除或恢复单词，并查看候选词、待复习词和新词数量；复习仍按已到期状态优先。 |
| 学习统计 | 遗忘曲线、学习情况、记忆持久度三个视图；个人遗忘曲线和艾宾浩斯参考线；日、周、月统计及查看日期。两端调用同一套 `core/stats/` 计算规则，各自绘图。 |
| 学习偏好与语音 | 显示每日主任务额度，切换默认学习方向；开关单词和例句自动朗读；选择英文音色、调整朗读速度。两端各内置一个离线音色，也可接入外部模型，最多同时启用三个音色。 |
| 个人数据与导入导出 | 从 TXT、CSV、XLSX 导入个人词条；手机端可导出和恢复 JSON 学习备份，并生成自动备份；电脑端可选择数据库文件夹，重新打开后使用该位置，旧位置数据不会自动转移。 |
| GitHub 私有同步 | 在设置页填写仓库所有者、名称、分支、同步密码和 Token；已配置时每次启动自动尝试同步一次，也可手动同步。同步协议使用加密快照与增量记录。未配置时可离线使用。电脑端的 GitHub 凭据配置保存在本机用户配置目录，与可选的数据库文件夹分开。 |
| 安装与发布 | Android APK、Windows MSI/EXE 的构建和打包；图标、词库及一个内置语音模型随对应安装包提供。安装包是构建产物，源码仓库的目录说明见下文。 |

### 手动批量添加的七列格式

在个人词库里点“批量添加”，先分别填写本批释义来源和例句来源，再粘贴单词。每行一个词，按“单词、音标、词性、中文释义、英文例句、例句翻译、助记”排列。**列与列之间必须用一个制表符（Tab），即使某列留空，也必须保留分隔符。** 可以先粘贴同样七列的表头。来源只在第一步填写一次，不写进每行。

最方便的做法是在表格软件里按七列编辑，选中多行复制，然后直接粘贴进应用。用空格把“单词 音标 释义”写在一行会被识别成一列，并显示行号和错误原因。TXT 文件遵守相同规则；CSV 和 XLSX 文件可以使用带列名的表格。解析规则在 [`DelimitedTextParser.kt`](../core/import/src/main/kotlin/com/pengshi/words/importer/DelimitedTextParser.kt)，两端入口分别在 [`DecksScreen.kt`](../app/src/main/java/com/pengshi/words/feature/decks/DecksScreen.kt) 和 [`DesktopDecksScreen.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopDecksScreen.kt)。

## 二、界面从哪里改

**改文字、布局、按钮、颜色时，先找页面文件。改按钮按下后发生的事，再找页面连接层。改跨设备一致的规则，继续找 `core/`。**

| 想修改的区域 | Android 手机界面 | Windows 电脑界面 | 行为和数据入口 |
| --- | --- | --- | --- |
| 首页、今日任务、打卡、额外词 | [`HomeScreen.kt`](../app/src/main/java/com/pengshi/words/feature/home/HomeScreen.kt)、`ExtraWordsDialog.kt` | [`DesktopHomeScreen.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopHomeScreen.kt) | 手机 `feature/home/HomeViewModel.kt`、`app/domain/`；电脑 `DesktopApp.kt`、`DesktopContainer.kt`；共用 `core/scheduler/` |
| 复习后的选词弹窗、跨词库浏览和精准搜索、学习完成提示 | [`NewWordSelectionDialog.kt`](../app/src/main/java/com/pengshi/words/feature/home/NewWordSelectionDialog.kt)、`App.kt` | [`DesktopNewWordSelectionDialog.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopNewWordSelectionDialog.kt)、`DesktopApp.kt` | 候选词与剩余额度在 `core/model/ManualWordSelection.kt`；自动和手动加入规则在 `app/domain/AddNewWordsUseCase.kt`，桌面端复用。 |
| 学习卡片、答案、反馈、收藏、搜索 | [`StudyScreen.kt`](../app/src/main/java/com/pengshi/words/feature/study/StudyScreen.kt)、`StudyComponents.kt` | [`DesktopStudyScreen.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopStudyScreen.kt) | 手机 `StudyViewModel.kt`、`app/domain/SubmitFeedbackUseCase.kt`；电脑 `DesktopApp.kt`、`DesktopContainer.kt`；共用 `core/scheduler/`、`core/model/` |
| 词库列表、词库详情、个人词条与例句 | [`DecksScreen.kt`](../app/src/main/java/com/pengshi/words/feature/decks/DecksScreen.kt) | [`DesktopDecksScreen.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopDecksScreen.kt)；词条详情也用 `DesktopStudyScreen.kt` 中的面板 | 手机 `DecksViewModel.kt`、`core/database/`；电脑 `desktop/storage/`、`DesktopContainer.kt` |
| 选词仪表盘、词库占比、排除词 | [`SelectionDashboardScreen.kt`](../app/src/main/java/com/pengshi/words/feature/decks/SelectionDashboardScreen.kt) | `DesktopDecksScreen.kt` 中的 `DesktopSelectionDashboard` | 手机 `SelectionDashboardController.kt`、`AndroidWordPoolStore.kt`；电脑 `desktop/wordpool/DesktopWordPoolStore.kt`；共用 `core/model/WordPool.kt`、`core/scheduler/WeightedDeckAllocator.kt` |
| 遗忘曲线、学习图表、持久度 | [`StatsScreen.kt`](../app/src/main/java/com/pengshi/words/feature/stats/StatsScreen.kt) | [`DesktopStatsScreen.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopStatsScreen.kt) | `core/stats/` 计算；手机 `StatsViewModel.kt`，电脑 `desktop/stats/DesktopStatsModels.kt` |
| 设置页与各项表单 | [`SettingsScreen.kt`](../app/src/main/java/com/pengshi/words/feature/settings/SettingsScreen.kt) | [`DesktopSettingsScreen.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopSettingsScreen.kt) | 手机 `SettingsViewModel.kt`、`App.kt`；电脑 `DesktopApp.kt`、`DesktopContainer.kt` |
| 主导航、启动画面、窗口 | [`AppNavHost.kt`](../app/src/main/java/com/pengshi/words/navigation/AppNavHost.kt)、`MainActivity.kt`、`startup/` | [`DesktopNavigation.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopNavigation.kt)、`DesktopMain.kt` | 手机 `App.kt`；电脑 `DesktopApp.kt` |

`App.kt` 是 Android 的较大连接文件，负责把页面操作接到数据库、学习用例、语音、词包导入和同步；`DesktopApp.kt` 扮演电脑端页面连接层，`DesktopContainer.kt` 管理电脑端服务和数据。它们不是“所有前端都写在一个文件里”。页面排版主要在上表中的 `*Screen.kt`。

## 三、规则、存储、同步分别在哪

| 想修改什么 | 去哪里看 | 修改时要留意 |
| --- | --- | --- |
| 单词、词库、学习记录、状态的结构 | [`core/model/`](../core/model/src/main/kotlin/com/pengshi/words/model/) | 改模型可能同时影响数据库、备份与跨端同步。 |
| 词条释义与例句的来源显示 | `core/model/ContentSource.kt`、两端学习页及词库详情页 | 来源跟随词条和例句保存在数据库、备份中；无法确认的历史内容显示“来源待核实”。 |
| 每日 30 词、复习顺序、记忆调度、词库权重 | [`core/scheduler/`](../core/scheduler/src/main/kotlin/com/pengshi/words/scheduler/)、[`app/domain/`](../app/src/main/java/com/pengshi/words/domain/) | 每日最大词数在 `DailyPlanBuilder.kt`；两端的默认设置和 UI 文案也需一起检查。 |
| 统计口径、遗忘拟合、图表数据 | [`core/stats/`](../core/stats/src/main/kotlin/com/pengshi/words/stats/) | 两端界面分别负责如何画图；不要只改图像而留下旧计算口径。 |
| TXT、CSV、XLSX 的识别和错误提示 | [`core/import/`](../core/import/src/main/kotlin/com/pengshi/words/importer/) | 导入按钮与文件选择器分别在两端设置页和词库页。 |
| JSON 备份格式和校验 | [`core/backup/`](../core/backup/src/main/kotlin/com/pengshi/words/backup/) | 手机的自动备份在 `app/backup/AutomaticBackupStore.kt`。 |
| GitHub API、加密快照、增量合并 | [`core/sync/`](../core/sync/src/main/kotlin/com/pengshi/words/sync/) | 手机接线在 `App.kt` 与 `app/sync/`；电脑接线在 `DesktopContainer.kt` 与 `desktop/sync/`。 |
| 手机数据库、表、查询、迁移 | [`core/database/`](../core/database/src/main/kotlin/com/pengshi/words/database/) | Room 的历史结构在 `core/database/schemas/`。 |
| 电脑数据库、词库与学习记录 | [`desktop/storage/`](../desktop/src/main/kotlin/com/pengshi/words/desktop/storage/) | 采用 SQLite；选库和用户目录规则由 `DesktopContainer.kt`、`DesktopAppSettingsStore.kt` 控制。 |
| 语音接口、偏好与内置音色标识 | [`core/speech-api/`](../core/speech-api/src/main/kotlin/com/pengshi/words/speech/) | 实际播报分别由 Android、Windows 的语音引擎实现。 |

代码修改的一个简单判断方法：**“按钮长什么样”在两端页面；“按下去做什么”在两端连接层；“两个设备都应遵守什么规则”在 `core/` 或共用的 `app/domain/`。**

## 四、Logo、背景、颜色和窗口资源

| 资源 | 位置及修改方式 |
| --- | --- |
| Android 应用图标和首页 Logo | [`app/src/main/res/drawable/pengshi_logo.png`](../app/src/main/res/drawable/pengshi_logo.png)。应用图标由 `app/src/main/AndroidManifest.xml` 指向此图；首页与启动画面也引用它。替换后检查不同尺寸下的清晰度。 |
| Windows 侧栏 Logo | [`desktop/src/main/resources/pengshi_logo.png`](../desktop/src/main/resources/pengshi_logo.png)，由 `DesktopNavigation.kt` 加载。 |
| Windows 窗口与安装程序图标 | [`pengshi_logo_compact.png`](../desktop/src/main/resources/pengshi_logo_compact.png) 用于窗口/任务栏；[`pengshi_logo.ico`](../desktop/src/main/resources/pengshi_logo.ico) 由 `desktop/build.gradle.kts` 用于安装包图标。改品牌时应同步更新三份 Windows 图标和 Android 图标。 |
| Android 全局颜色 | [`app/ui/theme/Theme.kt`](../app/src/main/java/com/pengshi/words/ui/theme/Theme.kt)。部分页面仍有自己的颜色，例如统计页的曲线配色在 `StatsScreen.kt`。 |
| Windows 全局颜色、卡片、背景 | [`desktop/ui/DesktopTheme.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/ui/DesktopTheme.kt) 中的 `DesktopPalette`、`DesktopTheme`、`DesktopPanel`；侧栏底色等在 `DesktopNavigation.kt`。部分图表色在 `DesktopStatsScreen.kt`。 |
| 字体、插图、背景图片 | 目前主要使用系统字体和代码绘制的纯色背景，没有统一的“背景图文件”。要新增图片，Android 放入 `app/src/main/res/drawable/`，Windows 放入 `desktop/src/main/resources/`，再从页面代码引用。 |

## 五、内置词库和例句怎样修订

**随安装包分发的内置词库源文件**位于 [`app/src/main/assets/wordpacks/`](../app/src/main/assets/wordpacks/)。电脑端构建脚本会把 `app/src/main/assets/` 作为资源目录读取，因此两端使用同一套源文件。

| 目录 | 内容 |
| --- | --- |
| `cet6/` | 六级核心词汇，当前 2219 词；`words.csv`、`examples.csv`，以及来源、许可、频率信息。 |
| `cet4-core/` | 四级核心词汇，当前 2000 词。 |
| `cet4-all/` | 四级全部词汇，当前 4533 词。 |
| `cet6-all/` | 六级全部词汇，当前 5407 词。 |
| `kaoyan-shared/` | 内置只读的「考研英语(2024大纲)」，英语一、英语二共用，去重后 5528 词；基础词保留供搜索，但不参与自动每日新词；另有来源和非商业使用许可说明。 |
| `generated-examples/` | 额外配套例句，保存在 `examples.csv`，不构成独立词库。 |

修改词条时，打开对应的 `words.csv`。主要列是 `spelling`（英文）、`phonetic`（音标）、`part_of_speech`（词性）、`definition_cn`（中文释义）、`example_en`、`example_cn`、`tags`。现有词包的 `source:ecdict` 标签标明释义来自 ECDICT；新词也应写明来源。`cet6/examples.csv` 用 `source_sentence_id`、`license` 标明 Tatoeba 例句；`generated-examples/examples.csv` 用 `source_tag` 标明 AI 生成例句。应用会逐条保存并展示释义、例句来源；无法追溯的旧条目标为“来源待核实”。保留 CSV 表头、引号转义和 UTF-8 编码。

修订或增添内置内容时，按这个顺序处理：

1. 在词包的 CSV 中改词条或例句；核对每个词的拼写、释义和例句对应关系。新增词库时创建独立目录和 `words.csv`，并给每条释义添加可追溯的 `source:...` 标签。增添例句时保留句子 ID、许可或 `source_tag`，不要把 AI 生成内容写成引用的句子。
2. 更新词库目录的 `manifest.json`、`LICENSE.txt`、`ATTRIBUTION.txt` 等来源资料；新增例句也标明来源和许可。`generated-examples/` 当前只有 `manifest.json` 和例句文件。修改 `cet6/words.csv` 后还须把 `manifest.json` 中的 SHA-256 校验值更新为新文件的值，否则词包校验会失败。词库、例句和语音模型的许可彼此独立。
3. 找到手机端 [`App.kt`](../app/src/main/java/com/pengshi/words/App.kt) 中的 `seedIfNeeded`、`BUILTIN_ASSET_PACKS` 和例句版本号；找到电脑端 [`DesktopSeedLoader.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/DesktopSeedLoader.kt) 中的 `BUILTIN_PACKS` 和例句导入。**新增词包须两端登记；只放进目录不会自动出现在应用里。** 手机端部分词包还在代码中写了预期行数。
4. 为已有安装用户考虑内容更新：手机端会跳过已经达到预期词数的内置包；电脑端主要检查词库名称、数量和拼写。**只修订一个现有词的中文释义或例句，升级安装包后未必会覆盖用户已有数据库里的内容。** 需要设计版本化更新或迁移，并保留用户的学习记录；手机端例句导入还受 `CET6_EXAMPLES_VERSION`、`GENERATED_EXAMPLES_VERSION` 控制。

用户在应用里创建或导入的个人词库属于个人数据，不在上述 `wordpacks/` 中。想给所有新用户提供词库，应改源词包；想给自己导入词库，应使用应用中的“导入本地词库”。

## 六、语音从哪里改

| 想改的部分 | 位置 |
| --- | --- |
| “朗读单词/例句”、音色和速度设置的按钮 | 手机 `feature/study/StudyScreen.kt`、`feature/settings/SettingsScreen.kt`；电脑 `desktop/ui/DesktopStudyScreen.kt`、`DesktopSettingsScreen.kt`。 |
| 音色目录、最多启用三个的规则和下载入口 | `core/speech-api/src/main/kotlin/com/pengshi/words/speech/SpeechEngine.kt`、`SpeechSettings.kt`。 |
| 手机播放与外部引擎接入 | [`core/speech/AndroidSpeechEngine.kt`](../core/speech/src/main/kotlin/com/pengshi/words/speech/AndroidSpeechEngine.kt)、`InstalledSpeechEngines.kt`。 |
| Windows 播放与外部模型接入 | `desktop/speech/EmbeddedWindowsSpeechEngine.kt`、`WindowsSpeechEngine.kt`。外部模型放在用户目录的 `PengshiWordsOpenSource/speech-models/external/`。 |
| 内置离线模型文件 | `app/src/main/assets/vits-piper-en_US-ljspeech-medium-int8/`；电脑端通过 `desktop/build.gradle.kts` 复用。 |

新增或替换声音时，要同时检查音色目录、两端引擎的模型映射和构建资源，并核对模型目录里的 `MODEL_CARD`。外部模型的安装方式与许可见[语音模型指南](voice-models.md)。

## 七、个人数据、同步和发布边界

- **电脑数据库位置：**设置页“选择数据库文件夹”调用 `DesktopApp.kt`；位置选择和默认路径由 [`DesktopContainer.kt`](../desktop/src/main/kotlin/com/pengshi/words/desktop/DesktopContainer.kt)、`DesktopAppSettingsStore.kt` 处理，打包默认值在 `desktop/src/main/resources/desktop.properties`。默认在当前 Windows 用户的本地应用数据目录 `PengshiWordsOpenSource` 下。切换目录后需重新打开应用，原目录的数据库不会自动移动；目标目录已有数据库就打开，否则新建。`PENGSHI_WORDS_DATA_DIR` 环境变量可覆盖所选位置。
- **电脑 GitHub 配置：**表单在 `DesktopSettingsScreen.kt`，本机设置由 `desktop/sync/DesktopSyncSettingsStore.kt` 保存，敏感值受当前 Windows 用户保护。数据库目录变更不会带走 GitHub 配置。
- **手机本地数据和凭据：**数据库在 Android 私有应用目录，由 `core/database/` 的 Room 代码访问；Token 保存在 `app/sync/AndroidTokenStore.kt`，手机自动备份在 `app/backup/AutomaticBackupStore.kt`。
- **GitHub 同步规则：**加密、快照、事件合并和 GitHub 请求在 `core/sync/`。这里连接的是**每位用户自己的私有数据仓库**，不是本项目的公开源码仓库。修改同步协议时必须考虑旧客户端与已有加密数据的兼容性。
- **不要提交的内容：**个人学习数据库、备份、Token、同步密码和本机安装目录属于用户数据或构建产物，不属于源码。提交前检查文件内容；[`.gitignore`](../.gitignore) 只负责拦截常见格式，不能替代人工检查。安装包适合作为 GitHub Release 附件发布，打包步骤见[发布指南](releasing.md)。

## 八、四个常见修改例子

| 我想…… | 最短查找路线 |
| --- | --- |
| 改首页标题、说明文字或按钮排列 | 分别打开 `feature/home/HomeScreen.kt` 和 `desktop/ui/DesktopHomeScreen.kt`。若按钮行为也变了，再看 `App.kt`、`DesktopApp.kt` 和相应学习用例。 |
| 把“每日 30 词”改成别的规则 | 从 `core/scheduler/DailyPlanBuilder.kt` 的 `MAX_UNIQUE_WORDS` 开始，再检查两端默认设置、首页与设置页文案、已有每日计划如何处理。只改页面上的“30”不会改变学习规则。 |
| 修正一个内置词的释义或增加一个新词库 | 编辑 `wordpacks/` 内相应 CSV 和来源文件；新增词库还要在 `App.kt`、`DesktopSeedLoader.kt` 登记。已有用户收到纠错需要词包版本更新或数据迁移。 |
| 换 Logo、主题色或语音 | 先看第四节的图片和颜色文件；换语音再看第六节的模型、两端引擎和音色设置。 |

第一次贡献可以从一项具体需求开始：确定“用户在哪个页面看到它”，按第二节找页面，再按第三至六节找到对应规则或素材；如果这项变化会影响两个设备，就分别检查两端入口。
