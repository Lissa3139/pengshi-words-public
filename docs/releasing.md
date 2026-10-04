# 发布指南

## 首发版本

本次公开首发统一为 Android `versionName = "1.0.0"`、`versionCode = 1`，Windows MSI `packageVersion = "1.0.0"`。以后每次 Android 发版递增 versionCode；修改展示版本号不能代替递增 versionCode。Windows MSI 也要同步更新 `packageVersion`。

发行安装包通过 GitHub Releases 提供，不提交 APK、MSI、EXE、签名文件或历史安装包到源码仓库。当前 `release/` 仅用于本地验收，二进制被 `.gitignore` 排除。Android 与 Windows 安装包各内置一个 LJSpeech 模型；其他三个推荐音色由用户自行下载。

## 发布准备

1. 创建项目公开仓库，用本目录的独立 Git 历史作为首发内容。不要把含本地工作记录或用户数据的旧仓库历史一并推送。
2. 确定维护者及贡献接收方式，把 README 的安装入口改为真实仓库 Releases 链接。
3. 使用自己的发行签名；保存好密钥与密码，后续升级必须使用相同签名。密钥与凭据保持在源码目录外。
4. 在 ARM64 Android 设备上确认首次启动、离线内置朗读、可选音色的应用内下载与删除、三个音色上限、默认音色保存，以及备份和恢复操作；在 Windows 10/11 x64 上确认 MSI/EXE 安装及启动、原版学习功能、两端考研词库、静态例句、本地数据、GitHub 同步与外部 Piper 模型接入。
5. 提供 GPLv3 对应源码及第三方组件的来源、许可、构建配置；确认用于重建本地库的源码与实际二进制匹配。
6. 为 APK、MSI 和 EXE 生成 SHA-256，在发行说明写明平台要求、两端音色安装入口与其他已知限制。

普通贡献者可分别运行 `:app:assembleDebug` 和 `:desktop:packageMsi`。项目没有把维护者签名放入构建脚本；Android 发行可通过 Android Studio 的 **Generate Signed Bundle / APK** 选择 APK，并从项目目录外选择自己的 keystore。Windows MSI 在 Windows 10/11 x64 构建；使用纯英文构建路径，例如 `-PdesktopBuildDir=C:/pengshi-desktop-build`。

## 首发资产

- `PengshiWords-1.0.0-arm64-v8a.apk`：正式签名的 Android 安装包。
- `PengshiWords-1.0.0-windows-x64.msi`：Windows 10/11 x64 桌面安装包。
- `PengshiWords-1.0.0-windows-x64.exe`：Windows 10/11 x64 的另一种安装格式。
- `SHA256SUMS.txt`：发行文件的 SHA-256。
- 对应版本的源码与必要的第三方对应源码、构建说明和许可证。GitHub 自动生成的项目源码压缩包不包含 JNI 二进制的外部依赖源码，不能把它当成完整的第三方对应源码包。
- 简洁的更新说明：主要功能、Android 8.0+ / ARM64 要求、外部语音安装指南链接。

当前 `release/` 中的 APK 使用本机 debug 开发签名，MSI/EXE 尚未进行 Windows 代码签名；这些文件供本地验收，不等同于正式首发安装包。

## 语音运行库来源

目前 JNI 库与 ONNX Runtime 库取自 Sherpa-ONNX 官方 1.13.8 ARM64 TTS 引擎 APK，哈希与来源在 `assets/speech-runtime/SOURCE.json`。Kotlin JNI 接口取自同版本上游源码。

重建运行库应从对应版本 Sherpa-ONNX 源码出发，依据其 Android 构建脚本获取匹配的 ONNX Runtime 和 eSpeak / Piper 依赖，并保留准确版本、构建参数及相关修改。取得的对应源码可作为独立发行附件提供；不要把设备备份或个人工作缓存混入源码附件。

目前记录的上游版本：Sherpa-ONNX commit `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`，ONNX Runtime `1.28.2`，eSpeak Piper 适配 commit `ed530aa113046142eb5115cf2fc9157854d0ffe1`。集成依赖以该 Sherpa 版本的 CMake 配置为准；更新 JNI 二进制时必须同步更新这些记录。

## 持续构建

仓库中的 GitHub Actions 当前构建 Android debug APK；Windows MSI 可由贡献者在 Windows 上本地构建。正式签名和创建 Release 由维护者操作，不从 Pull Request 取得或暴露发行密钥。

建议在仓库设置中启用分支保护及 GitHub 的私密漏洞报告入口，检查 README 和文档链接能从仓库首页访问。
