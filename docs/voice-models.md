# 安装、选择和删除语音模型

Android 与 Windows 各内置一个 **LJSpeech · 美式女声**，开箱即可离线朗读。Lessac、Ryan、Jenny 是可选的第三方模型，不包含在本项目安装包中。两端最多同时启用三个音色；内置音色也占一个名额。

| 可选音色 | 官方模型压缩包 |
| --- | --- |
| Lessac · 美式女声 | [下载 Lessac](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2) |
| Ryan · 美式男声 | [下载 Ryan](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ryan-high.tar.bz2) |
| Jenny (Dioco) · 英式女声 | [下载 Jenny](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-jenny_dioco-medium.tar.bz2) |

这些文件来自 [Sherpa-ONNX 官方模型发布页](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)。[官方模型目录](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)还列有其他可选模型。下载前请阅读文末模型许可。

## Android：在应用内下载和删除

1. 打开「设置 → 接入 / 管理语音模型 → 下载语音模型」。
2. 点击需要的音色。应用下载模型压缩包、核对 SHA-256、解压到自身私有目录。无需另装语音引擎 App，也不必手动解压。
3. 下载完成后返回模型管理页，可「试听」、勾选启用，并在设置页选默认语音。最多同时启用三个音色；第四个模型可保留和试听，需要时切换启用。
4. 要卸载，进入模型管理页，在已下载音色旁点击「删除」并确认。删除后可重新下载；如果它是默认音色，应用会选择仍启用的音色。内置 LJSpeech 不提供删除按钮。

下载期间需联网并留出存储空间。模型文件不会随应用备份导出；清除应用数据也会删除这些文件。Android 还可识别设备上已有、标记为离线的标准英语 TTS 服务；它们由对应服务自行管理，不能从本应用删除。

### Android：导入其他英语 Piper 模型

1. 从官方模型目录下载英语 Piper 模型，并解压压缩包。
2. 在「设置 → 接入 / 管理语音模型」点击「导入其他 Piper 模型文件夹」，选择**直接包含一个 `.onnx` 文件和 `tokens.txt`** 的文件夹。
3. 应用把模型复制到自己的私有目录。导入完成后可试听、启用，也可在管理页删除。模型文件不参与 GitHub 同步，换机时请重新导入。

目前只识别直接包含上述文件的文件夹；若解压后多套了一层目录，请进入里面的模型文件夹再选择。

## Windows：下载到模型文件夹

1. 在「设置 → 本地语音模型」点击「打开模型文件夹」。新安装默认在安装目录旁的 `speech-models\external\`；若安装目录不能写入，自动使用 `%LOCALAPPDATA%\PengshiWordsOpenSource\speech-models\external\`。旧版用户目录已有模型时沿用原目录。可点「更换模型文件夹」单独选择其他位置，重新打开应用后生效。
2. 用上面的链接下载 `.tar.bz2` 文件，执行 `tar -xf 文件名.tar.bz2`，或用支持该格式的解压软件解压。
3. 把解压出的整个 `vits-piper-en_...` 文件夹放进 `external` 目录。文件夹内应直接包含 `.onnx` 模型和 `tokens.txt`，不要多套一层目录。
4. 返回应用点击「刷新音色」，试听并勾选启用，最后选默认语音。

Windows 还识别同样目录结构的其他 Piper 英语模型。要卸载外部模型，请关闭应用后从上述目录移走对应模型文件夹，再启动应用刷新。内置模型不能从设置中移除。

## 音色选择

- 学习页最多显示三个已启用音色；管理页会列出更多已下载或已识别的音色。
- 先关闭一个音色，才可启用第四个；至少保留一个启用音色。
- 停用、删除或移走当前默认音色后，会切换到仍可用的音色。
- 启用列表和默认音色保存在本机，换机后需重新下载或放入外部模型。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| Android 下载失败 | 检查网络和可用空间，再重试；应用会校验下载文件，失败不会安装不完整模型。 |
| Windows 刷新后看不到模型 | 检查文件夹是否直接包含 `.onnx` 和 `tokens.txt`。 |
| 已有四个音色，却只看到三个朗读按钮 | 在管理页关闭一个，再启用要使用的音色。 |
| 音色不能朗读 | 先试听内置音色；外部模型可删除并重新下载或解压。 |

## 模型许可

这些模型和训练数据各有许可；下载入口不等于任意商用或再分发授权。

- 内置 LJSpeech：[模型卡](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ljspeech/medium/MODEL_CARD)说明使用[公共领域 LJ Speech 数据集](https://keithito.com/LJ-Speech-Dataset/)从头训练；模型来源与校验值见内置资源的 `SOURCE.json`。
- Lessac：[模型卡](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/lessac/medium/MODEL_CARD)及 [Lessac Blizzard 数据许可](https://www.cstr.ed.ac.uk/projects/blizzard/2013/lessac_blizzard2013/license.html)包含研究用途和分发限制。本项目不捆绑或重新分发该模型。
- Ryan：[模型卡](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/ryan/high/MODEL_CARD)标注数据集为 CC BY-NC-SA 4.0，包含非商业限制。
- Jenny (Dioco)：[数据集说明](https://github.com/dioco-group/jenny-tts-dataset)要求保留 Jenny 名称；[模型卡](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_GB/jenny_dioco/medium/MODEL_CARD)同时说明该版本从 Lessac 微调，相关基础模型权利也需要考虑。

语音运行组件的许可见[第三方说明](../THIRD_PARTY_NOTICES.md)。
