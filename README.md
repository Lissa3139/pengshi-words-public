# 彭式背单词

一款以本地词库、间隔复习和英语朗读为基础的 Android 与 Windows 背单词应用。

**当前版本：1.0.0 · versionCode 1**

**支持设备：Android 8.0 及以上 ARM64（arm64-v8a）设备；Windows 10/11 x64 电脑。**

## 普通用户从这里开始

正式发行的安装包会放在本仓库的 **Releases** 页面；源码压缩包不能直接安装。仓库根目录的 `release/` 用于本地验收，安装包不会随源码提交。

**Android：**在手机上打开 APK，按系统提示允许安装；首次启动后在「词库」中选择四级、六级、**考研英语(2024大纲)** 或自己的词库。应用内置 LJSpeech 美式女声音色。

**Windows：**打开 MSI 安装包并按提示安装。桌面版同样内置 LJSpeech；学习数据默认保存在当前 Windows 账户的本地数据目录，也可在设置中更换数据库文件夹。

换机或重装前，Android 用户可导出应用备份；Windows 用户请备份所选数据库文件夹。两端都可连接自己的 GitHub 私有仓库同步加密学习数据；不配置时可离线使用。

详细步骤见[使用指南](docs/user-guide.md)和[语音模型安装指南](docs/voice-models.md)。开发者可查看[功能地图](docs/PROJECT_STRUCTURE.md)，按功能定位两端页面、规则和资源。

## 可以做什么

- 使用内置四级、六级核心及完整词表，以及只读的 **考研英语(2024大纲)** 词表；也可导入 CSV、TXT、XLSX 自建词库。
- 每日主任务先复习到期词，再按选词规则自动安排或手动挑选新词；可调整每日额度，支持英译中、中译英，以及基于 FSRS 的间隔安排。
- 管理参与选词的词库与占比、熟词、个人词条和例句，查看学习统计、遗忘曲线和记忆持久度。
- 两端都可离线朗读单词和英文例句，各内置一个音色，并可接入外部模型；最多同时启用三个音色。
- 两端均可连接自己的 GitHub 私有仓库加密同步；Android 另支持导出、恢复 JSON 学习备份，Windows 可选择数据库文件夹。

内置词库、学习和内置朗读可以离线使用。缺失例句的补充可能请求 Tatoeba；下载外部模型、配置 GitHub 同步时也需要网络。连接说明见[隐私与数据](docs/privacy.md)。

考研英语词表基于 2024 年英语一大纲，去重后 5,528 词，英语一、英语二共用。自动安排每日新词时，跳过其中的小学常见词和基础功能词；这些词仍保留在词库里，可搜索和手动加入学习。五个内置词包均已补齐词性；已有安装会补齐空缺词性并更新词库名称，不清除学习记录。补充例句已作为静态数据随项目提供，安装和运行应用不需要部署或调用语言模型。词表数据单独采用 CC BY-NC-SA 4.0，需署名、限非商业使用；代码采用 GPLv3。详见[第三方资源与版权说明](THIRD_PARTY_NOTICES.md)。

## 语音模型

| 音色 | Android | Windows |
| --- | --- | --- |
| LJSpeech · 美式女声 | 随应用内置 | 随应用内置 |
| Lessac · 美式女声 | 在应用内下载、使用和删除 | 下载并解压 Piper 模型 |
| Ryan · 美式男声 | 在应用内下载、使用和删除 | 下载并解压 Piper 模型 |
| Jenny (Dioco) · 英式女声 | 在应用内下载、使用和删除 | 下载并解压 Piper 模型 |

Android 可在「设置 → 接入 / 管理语音模型」直接下载预设模型，也可导入其他英语 Piper 模型文件夹；模型可在应用内删除，无需安装另一款 App。Windows 可更换模型文件夹，并从官方模型目录选择其他模型。两端都只在学习界面显示已启用的最多三个音色；安装第四个后，可先关闭一个已启用音色再切换。语音模型文件仅保存在当前设备，不参与学习数据同步。

下载链接、安装说明和各模型许可见[语音模型指南](docs/voice-models.md)。外部模型不包含在本仓库或应用安装包中。

## 开发者从这里开始

需要 JDK 17、Android SDK Platform 34，以及网络连接以取得构建依赖。项目包含 Gradle Wrapper，可在 Android Studio 中打开仓库根目录，按提示配置 SDK。

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

Windows 桌面 MSI：

```powershell
.\gradlew.bat :desktop:packageMsi "-PdesktopBuildDir=C:/pengshi-desktop-build"
```

MSI 位于 `C:/pengshi-desktop-build/compose/binaries/main/msi/`。构建目录使用纯英文路径，可避免部分 JDK 在中文路径下生成 Windows 安装包时失败。

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/`。它使用本机开发签名；正式发行需要维护者自己的发行签名，详情见[开发指南](docs/development.md)和[发布指南](docs/releasing.md)。

## 项目分类

| 目录 | 用途 |
| --- | --- |
| `app/` | Android 界面、页面导航、功能组装及内置资源 |
| `desktop/` | Compose Desktop Windows 客户端、SQLite 存储、加密同步和离线语音适配 |
| `core/model/` | 单词、学习状态、计划等领域模型 |
| `core/database/` | Room 数据库存储及结构迁移 |
| `core/scheduler/` | FSRS、每日计划及当天复习安排 |
| `core/import/` | CSV / TXT / XLSX 词库解析与内容校验 |
| `core/speech-api/` | 朗读接口、音色目录及启用数量约束 |
| `core/speech/` | 内置离线合成、安卓应用内模型下载与删除、已安装语音引擎接入 |
| `core/backup/` | 学习备份格式、读写及恢复校验 |
| `core/sync/` | 加密同步协议与 GitHub 存储适配 |
| `core/stats/` | 学习统计计算 |
| `docs/` | 使用、语音、隐私、开发与发布说明 |
| `licenses/` | 第三方组件许可文本 |

当前源码提供 Android 与 Windows 客户端，均支持可选的 GitHub 私有仓库加密同步。

## 参与开发

欢迎提交问题和改进建议，流程见[贡献指南](CONTRIBUTING.md)。提交问题时请说明设备、版本和复现步骤，不要上传自己的数据库、学习备份、同步密码或访问令牌。

## 许可与致谢

项目代码采用 **GNU GPL v3.0**，完整条款见 [LICENSE](LICENSE)。分发应用或修改版时，需要保留许可与版权说明，并按 GPLv3 提供对应源码。

词表、词典、例句、语音模型和第三方运行组件各自保留原有许可；项目代码许可证不改变它们的条款。具体来源见[第三方说明](THIRD_PARTY_NOTICES.md)。
