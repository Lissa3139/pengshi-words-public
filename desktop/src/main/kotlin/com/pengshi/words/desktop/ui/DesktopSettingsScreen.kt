package com.pengshi.words.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pengshi.words.desktop.sync.DesktopSyncSettingsState
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.StudyMode
import com.pengshi.words.speech.SpeechVoiceOption
import com.pengshi.words.speech.SpeechVoiceCatalog
import com.pengshi.words.speech.SpeechModelDownloads

@Composable
fun DesktopSettingsScreen(
    defaultMode: StudyMode,
    message: String?,
    voices: List<SpeechVoiceOption>,
    voiceCatalog: SpeechVoiceCatalog,
    modelInstallDirectory: String,
    selectedVoiceKey: String?,
    savedSpeechRate: Float,
    autoPlayWord: Boolean,
    autoPlaySentence: Boolean,
    dailyQuota: Int,
    onSaveDailyQuota: (Int) -> Unit,
    onImport: () -> Unit,
    onSwitchMode: () -> Unit,
    onSelectVoice: (SpeechVoiceOption) -> Unit,
    onSetVoiceEnabled: (SpeechVoiceOption, Boolean) -> Unit,
    onRefreshVoices: () -> Unit,
    onPreviewVoice: (SpeechVoiceOption) -> Unit,
    onOpenModelDirectory: () -> Unit,
    onChooseModelDirectory: () -> Unit,
    onOpenModelGuide: () -> Unit,
    onDownloadModel: (String) -> Unit,
    onSaveSpeechSettings: (Float) -> Unit,
    onSaveAutoPlaySettings: (Boolean, Boolean) -> Unit,
    onSyncNow: () -> Unit,
    localRepositoryPath: String,
    selectedDataDirectory: String,
    dataDirectoryChangePending: Boolean,
    onChooseDataDirectory: () -> Unit,
    onCancelDataDirectoryChange: () -> Unit,
    onExitForDataDirectoryChange: () -> Unit,
    syncSettingsState: DesktopSyncSettingsState,
    onSaveSyncSettings: (String, String, String, String, String) -> Boolean,
    syncSummary: String? = null,
) {
    var selectedRate by remember(savedSpeechRate) { mutableStateOf(savedSpeechRate) }
    var selectedVoice by remember(selectedVoiceKey) { mutableStateOf(selectedVoiceKey) }
    var autoPlayWordState by remember(autoPlayWord) { mutableStateOf(autoPlayWord) }
    var autoPlaySentenceState by remember(autoPlaySentence) { mutableStateOf(autoPlaySentence) }
    var dailyQuotaInput by remember(dailyQuota) { mutableStateOf(dailyQuota.toString()) }
    var githubOwner by remember(syncSettingsState.owner) { mutableStateOf(syncSettingsState.owner) }
    var githubRepository by remember(syncSettingsState.repository) { mutableStateOf(syncSettingsState.repository) }
    var githubBranch by remember(syncSettingsState.branch) { mutableStateOf(syncSettingsState.branch) }
    var githubPassword by remember { mutableStateOf("") }
    var githubToken by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(38.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("设置", style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
        Text("本地词库与学习偏好。", color = DesktopPalette.muted)
        DesktopPanel(Modifier.fillMaxWidth()) {
            Text("词库与数据", style = MaterialTheme.typography.h6)
            Text("导入 CSV、TXT 或 XLSX 词库，只添加或补充词条。",
                modifier = Modifier.padding(top = 10.dp), color = DesktopPalette.muted)
            Button(onClick = onImport, modifier = Modifier.padding(top = 18.dp)) { Text("导入本地词库") }
            message?.let { Text(it, color = DesktopPalette.teal, modifier = Modifier.padding(top = 12.dp)) }
        }
        DesktopPanel(Modifier.fillMaxWidth()) {
            Text("学习偏好", style = MaterialTheme.typography.h6)
            Text("每日学习额度（1–${DailyQuota.MAX} 个主计划单词）", modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = dailyQuotaInput,
                    onValueChange = { input -> if (input.all(Char::isDigit) && input.length <= 3) dailyQuotaInput = input },
                    label = { Text("每天学习多少词") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                val parsedQuota = dailyQuotaInput.toIntOrNull()?.takeIf { it in DailyQuota.MIN..DailyQuota.MAX }
                Button(onClick = { parsedQuota?.let(onSaveDailyQuota) }, enabled = parsedQuota != null) {
                    Text("保存额度")
                }
            }
            Text("提高额度时，未完成的复习会立即补入计划；复习完成后可按新额度选择新词。降低额度时优先移除尚未开始的新词，再调整未开始的复习词。已开始、完成、手动选择和额外词会保留。",
                modifier = Modifier.padding(top = 6.dp), color = DesktopPalette.muted)
            Button(onClick = onSwitchMode, modifier = Modifier.padding(top = 12.dp)) {
                Text("默认模式：${if (defaultMode == StudyMode.EN_TO_CN) "英译中" else "中译英"}")
            }
            Button(
                onClick = {
                    autoPlayWordState = !autoPlayWordState
                    onSaveAutoPlaySettings(autoPlayWordState, autoPlaySentenceState)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("自动朗读单词：${if (autoPlayWordState) "开启" else "关闭"}") }
            Button(
                onClick = {
                    autoPlaySentenceState = !autoPlaySentenceState
                    onSaveAutoPlaySettings(autoPlayWordState, autoPlaySentenceState)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("自动朗读第一条例句：${if (autoPlaySentenceState) "开启" else "关闭"}") }
            Text("本地语音模型", modifier = Modifier.padding(top = 18.dp), fontWeight = FontWeight.SemiBold)
            Text("已启用 ${voices.size}/${SpeechVoiceCatalog.MAX_ENABLED_VOICES} 个音色。安装更多模型后可在这里启用，学习页最多显示三个音色。",
                modifier = Modifier.padding(top = 6.dp), color = DesktopPalette.muted)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenModelDirectory) { Text("打开模型文件夹") }
                OutlinedButton(onClick = onChooseModelDirectory) { Text("更换模型文件夹") }
                OutlinedButton(onClick = onRefreshVoices) { Text("刷新音色") }
            }
            Text(modelInstallDirectory, modifier = Modifier.padding(top = 6.dp), color = DesktopPalette.muted)
            voiceCatalog.message?.let { Text(it, color = DesktopPalette.teal, modifier = Modifier.padding(top = 6.dp)) }
            voiceCatalog.voices.forEach { voice ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = voices.any { it.key == voice.key }
                    Checkbox(checked = enabled, onCheckedChange = { onSetVoiceEnabled(voice, it) })
                    Text(voice.engineLabel, modifier = Modifier.weight(1f).padding(top = 9.dp))
                    OutlinedButton(onClick = { onPreviewVoice(voice) }) { Text("试听") }
                    Button(onClick = { selectedVoice = voice.key; onSelectVoice(voice) }, enabled = enabled) {
                        Text(if (voice.key == selectedVoice) "默认 ✓" else "设为默认")
                    }
                }
            }
            Text("下载模型压缩包并解压，把整个模型文件夹放入上方目录，再点击刷新。文件夹里需要有 .onnx 和 tokens.txt。",
                modifier = Modifier.padding(top = 12.dp), color = DesktopPalette.muted)
            SpeechModelDownloads.models.forEach { (label, model) ->
                OutlinedButton(onClick = { onDownloadModel(model) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("下载 $label")
                }
            }
            OutlinedButton(onClick = onOpenModelGuide, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("查看更多英语语音模型")
            }
            Text("朗读速度：${"%.2f".format(selectedRate)}×", modifier = Modifier.padding(top = 16.dp))
            Slider(
                value = selectedRate,
                onValueChange = { selectedRate = it },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSaveSpeechSettings(selectedRate) }) {
                Text("保存语音设置")
            }
        }
        DesktopPanel(Modifier.fillMaxWidth()) {
            Text("数据与同步", style = MaterialTheme.typography.h6)
            Text(
                "词库、学习记录和设置保存在所选文件夹；GitHub 配置保存在本机，不随数据库位置变化。",
                modifier = Modifier.padding(top = 10.dp),
                color = DesktopPalette.muted,
            )
            Text(
                localRepositoryPath,
                modifier = Modifier.padding(top = 14.dp),
                fontWeight = FontWeight.SemiBold,
                color = DesktopPalette.teal,
            )
            Button(onClick = onChooseDataDirectory, modifier = Modifier.padding(top = 12.dp)) {
                Text("选择数据库文件夹")
            }
            if (dataDirectoryChangePending) {
                Text("新位置：$selectedDataDirectory", modifier = Modifier.padding(top = 10.dp), color = DesktopPalette.teal)
                Text(
                    "退出并重新打开应用后生效。当前数据库不会移动或复制；新位置已有数据库时会直接打开，否则会新建数据库。",
                    modifier = Modifier.padding(top = 6.dp),
                    color = DesktopPalette.muted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = onExitForDataDirectoryChange) { Text("退出并应用新位置") }
                    OutlinedButton(onClick = onCancelDataDirectoryChange) { Text("取消位置更改") }
                }
            }

            Text("GitHub 私有仓库", modifier = Modifier.padding(top = 20.dp), fontWeight = FontWeight.SemiBold)
            Text(
                if (syncSettingsState.hasToken && syncSettingsState.hasPassword) {
                    "已保存：${syncSettingsState.owner}/${syncSettingsState.repository}（${syncSettingsState.branch}）"
                } else {
                    "填写个人私有仓库信息后，可以在此手动同步。"
                },
                modifier = Modifier.padding(top = 6.dp),
                color = DesktopPalette.muted,
            )
            OutlinedTextField(
                value = githubOwner,
                onValueChange = { githubOwner = it },
                label = { Text("GitHub 用户名或组织") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = githubRepository,
                onValueChange = { githubRepository = it },
                label = { Text("私有仓库名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                value = githubBranch,
                onValueChange = { githubBranch = it },
                label = { Text("分支") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                value = githubPassword,
                onValueChange = { githubPassword = it },
                label = {
                    Text(if (syncSettingsState.hasPassword) "同步密码（已保存，留空保留）" else "同步密码（所有设备必须相同）")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            OutlinedTextField(
                value = githubToken,
                onValueChange = { githubToken = it },
                label = {
                    Text(if (syncSettingsState.hasToken) "GitHub Token（已保存，留空保留）" else "GitHub Fine-grained Token")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = {
                    if (onSaveSyncSettings(githubOwner, githubRepository, githubBranch, githubPassword, githubToken)) {
                        githubPassword = ""
                        githubToken = ""
                    }
                }) { Text("保存 GitHub 配置") }
                Button(onClick = onSyncNow, enabled = !dataDirectoryChangePending) { Text("立即同步") }
            }
            syncSummary?.let {
                Text(it, modifier = Modifier.padding(top = 12.dp), color = DesktopPalette.teal)
            }
        }
    }
}
