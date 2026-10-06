package com.pengshi.words.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.StudyMode
import com.pengshi.words.speech.SpeechSettings
import com.pengshi.words.speech.SpeechVoiceOption
import com.pengshi.words.speech.SpeechVoiceCatalog
import com.pengshi.words.speech.SpeechModelDownloads
import com.pengshi.words.BuildConfig

data class SettingsUiState(
    val dailyQuota: Int = 30,
    val isUpdatingDailyQuota: Boolean = false,
    val defaultMode: StudyMode = StudyMode.EN_TO_CN,
    val autoPlayWord: Boolean = true,
    val autoPlaySentence: Boolean = true,
    val speechRate: Float = 1.0f,
    val selectedDeckId: Long? = null,
    val availableVoices: List<SpeechVoiceOption> = emptyList(),
    val allVoices: List<SpeechVoiceOption> = emptyList(),
    val voiceScanInProgress: Boolean = false,
    val speechMessage: String? = null,
    val downloadingModel: String? = null,
    val downloadPercent: Int? = null,
    val removingModel: String? = null,
    val selectedVoiceKey: String? = null,
    val githubOwner: String = "",
    val githubRepository: String = "",
    val githubBranch: String = "main",
    val hasGithubPassword: Boolean = false,
    val hasGithubToken: Boolean = false,
)

sealed interface SettingsAction {
    data object ToggleAutoPlayWord : SettingsAction
    data object ToggleAutoPlaySentence : SettingsAction
    data object SwitchMode : SettingsAction
    data class SetDailyQuota(val quota: Int) : SettingsAction
    data object IncreaseSpeechRate : SettingsAction
    data object DecreaseSpeechRate : SettingsAction
    data class SetSpeechRate(val rate: Float) : SettingsAction
    data object RefreshVoices : SettingsAction
    data class DownloadSpeechModel(val model: String) : SettingsAction
    data class ImportSpeechModel(val folder: Uri) : SettingsAction
    data class UninstallSpeechModel(val model: String) : SettingsAction
    data class SelectSpeechVoice(val option: SpeechVoiceOption?) : SettingsAction
    data class SetVoiceEnabled(val option: SpeechVoiceOption, val enabled: Boolean) : SettingsAction
    data class PreviewSpeechVoice(val option: SpeechVoiceOption) : SettingsAction
    data object Export : SettingsAction
    data object Restore : SettingsAction
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit = {},
    onImportWordList: (Uri) -> Unit = {},
    onExportBackup: (Uri) -> Unit = {},
    onRestoreBackup: (Uri) -> Unit = {},
    onSaveGitHubSync: (owner: String, repository: String, branch: String, password: String, token: String) -> Unit = { _, _, _, _, _ -> },
    onSyncNow: () -> Unit = {},
    syncSummary: String? = null,
    message: String? = null,
) {
    var voiceDialogOpen by rememberSaveable { mutableStateOf(false) }
    var modelManagerOpen by rememberSaveable { mutableStateOf(false) }
    var modelDownloadsOpen by rememberSaveable { mutableStateOf(false) }
    var downloadError by rememberSaveable { mutableStateOf<String?>(null) }
    var modelToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var licenseDialogOpen by rememberSaveable { mutableStateOf(false) }
    var showThirdPartyLicenses by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val licenseText = remember(licenseDialogOpen, showThirdPartyLicenses) {
        if (!licenseDialogOpen) "" else runCatching {
            val file = if (showThirdPartyLicenses) "THIRD_PARTY_NOTICES.md" else "LICENSE"
            context.assets.open("legal/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("许可文件无法读取，请查看项目发布页中的许可证。")
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, modelManagerOpen) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && modelManagerOpen) onAction(SettingsAction.RefreshVoices)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var githubOwner by rememberSaveable(state.githubOwner) { mutableStateOf(state.githubOwner) }
    var githubRepository by rememberSaveable(state.githubRepository) { mutableStateOf(state.githubRepository) }
    var githubBranch by rememberSaveable(state.githubBranch) { mutableStateOf(state.githubBranch) }
    var githubPassword by rememberSaveable { mutableStateOf("") }
    var githubToken by rememberSaveable { mutableStateOf("") }
    var dailyQuotaInput by rememberSaveable(state.dailyQuota) { mutableStateOf(state.dailyQuota.toString()) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportWordList)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onRestoreBackup)
    }
    val modelFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onAction(SettingsAction.ImportSpeechModel(it)) }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(onExportBackup)
    }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text("词库与数据", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("导入本地词库只会新增或补充词条，不会清空你的学习进度。支持 CSV、TXT、XLSX。", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("导入本地词库") }
        OutlinedButton(onClick = { exportLauncher.launch("pengshi-backup.json") }, modifier = Modifier.fillMaxWidth()) {
            Text("导出学习备份（换机/保护进度）")
        }
        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("恢复学习备份")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Text("GitHub 同步", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        syncSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("使用 GitHub 私有仓库保存加密完整快照和增量；没有配置时不会联网。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = githubOwner,
            onValueChange = { githubOwner = it },
            label = { Text("GitHub 用户名或组织") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = githubRepository,
            onValueChange = { githubRepository = it },
            label = { Text("私有仓库名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = githubBranch,
            onValueChange = { githubBranch = it },
            label = { Text("分支") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = githubPassword,
            onValueChange = { githubPassword = it },
            label = { Text(if (state.hasGithubPassword) "同步密码（已安全保存）" else "同步密码（两台设备必须一致）") },
            placeholder = { if (state.hasGithubPassword) Text("留空则保留原密码") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = githubToken,
            onValueChange = { githubToken = it },
            label = { Text(if (state.hasGithubToken) "GitHub Token（已安全保存）" else "GitHub Fine-grained Token") },
            placeholder = { if (state.hasGithubToken) Text("留空则保留原 Token") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSaveGitHubSync(githubOwner, githubRepository, githubBranch, githubPassword, githubToken) },
                modifier = Modifier.weight(1f),
            ) { Text("保存连接") }
            OutlinedButton(onClick = onSyncNow, modifier = Modifier.weight(1f)) { Text("立即同步") }
        }
        Text("学习偏好", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("每日学习额度：${state.dailyQuota} 个主计划单词")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dailyQuotaInput,
                onValueChange = { input ->
                    if (input.all(Char::isDigit) && input.length <= 3) dailyQuotaInput = input
                },
                label = { Text("每日词数") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            val parsedQuota = dailyQuotaInput.toIntOrNull()?.takeIf { it in DailyQuota.MIN..DailyQuota.MAX }
            Button(
                onClick = { parsedQuota?.let { onAction(SettingsAction.SetDailyQuota(it)) } },
                enabled = parsedQuota != null && !state.isUpdatingDailyQuota,
            ) { Text(if (state.isUpdatingDailyQuota) "保存中…" else "保存额度") }
        }
        Text("范围 1–${DailyQuota.MAX}。降低额度时先移除尚未开始的新词，再按优先级移除未开始的复习词；已开始或完成、手动选词和额外词会保留。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { onAction(SettingsAction.SwitchMode) }, modifier = Modifier.fillMaxWidth()) {
            Text("默认模式：${if (state.defaultMode == StudyMode.EN_TO_CN) "英译中" else "中译英"}")
        }
        OutlinedButton(onClick = { onAction(SettingsAction.ToggleAutoPlayWord) }, modifier = Modifier.fillMaxWidth()) {
            Text("自动朗读单词：${if (state.autoPlayWord) "开启" else "关闭"}")
        }
        OutlinedButton(onClick = { onAction(SettingsAction.ToggleAutoPlaySentence) }, modifier = Modifier.fillMaxWidth()) {
            Text("自动朗读例句：${if (state.autoPlaySentence) "开启" else "关闭"}")
        }
        Text("朗读速度：${"%.1f".format(state.speechRate)}")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAction(SettingsAction.DecreaseSpeechRate) }, modifier = Modifier.weight(1f)) { Text("−") }
            Slider(
                value = state.speechRate,
                onValueChange = { onAction(SettingsAction.SetSpeechRate(it)) },
                valueRange = SpeechSettings.MIN_RATE..SpeechSettings.MAX_RATE,
                modifier = Modifier.weight(4f),
            )
            OutlinedButton(onClick = { onAction(SettingsAction.IncreaseSpeechRate) }, modifier = Modifier.weight(1f)) { Text("+") }
        }
        Text("本地语音模型", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("已启用 ${state.availableVoices.size}/${SpeechVoiceCatalog.MAX_ENABLED_VOICES} 个音色。可以安装更多模型，再选择学习页使用的音色。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { modelManagerOpen = true; onAction(SettingsAction.RefreshVoices) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("接入 / 管理语音模型") }
        state.speechMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        val selectedVoice = state.availableVoices.firstOrNull { it.key == state.selectedVoiceKey }
        OutlinedButton(onClick = { voiceDialogOpen = true; onAction(SettingsAction.RefreshVoices) }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedVoice?.let { "默认语音：${it.engineLabel}${if (it.networkRequired) " · 在线" else " · 本地"}" } ?: "选择默认语音")
        }
        Text("关于应用", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("彭式背单词 ${BuildConfig.VERSION_NAME} · GPLv3")
        TextButton(onClick = { showThirdPartyLicenses = false; licenseDialogOpen = true }) { Text("开源许可与第三方说明") }
    }
    if (voiceDialogOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { voiceDialogOpen = false },
            title = { Text("选择英语音色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.availableVoices.isEmpty()) {
                        Text("暂时没有可用音色，请前往语音模型管理刷新。")
                    } else {
                        state.availableVoices.forEach { voice ->
                            OutlinedButton(
                                onClick = { onAction(SettingsAction.SelectSpeechVoice(voice)); voiceDialogOpen = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("${voice.engineLabel} · ${if (voice.networkRequired) "在线" else "本地"}") }
                        }
                    }
                    OutlinedButton(onClick = { onAction(SettingsAction.RefreshVoices) }, enabled = !state.voiceScanInProgress, modifier = Modifier.fillMaxWidth()) { Text(if (state.voiceScanInProgress) "扫描中…" else "刷新音色") }
                }
            },
            confirmButton = { OutlinedButton(onClick = { voiceDialogOpen = false }) { Text("关闭") } },
        )
    }
    if (modelManagerOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { modelManagerOpen = false },
            title = { Text("语音模型管理") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已启用 ${state.availableVoices.size}/3 个音色；至少保留一个。未启用的模型仍可试听。")
                    Text("下载的模型保存在本应用中，可随时删除；内置音色无法删除。也可使用设备已有的离线英语语音引擎。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { modelDownloadsOpen = true; downloadError = null }) { Text("下载语音模型") }
                    TextButton(onClick = { modelFolderLauncher.launch(null) }) { Text("导入其他 Piper 模型文件夹") }
                    if (state.voiceScanInProgress) Text("正在扫描可用音色…")
                    state.speechMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.allVoices.forEach { voice ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.availableVoices.any { it.key == voice.key },
                                    onCheckedChange = { onAction(SettingsAction.SetVoiceEnabled(voice, it)) },
                                    enabled = !state.voiceScanInProgress,
                                )
                                Text(voice.engineLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onAction(SettingsAction.PreviewSpeechVoice(voice)) }, enabled = !state.voiceScanInProgress) { Text("试听") }
                                if (voice.enginePackageName.startsWith("com.pengshi.tts.downloaded.")) {
                                    TextButton(
                                        onClick = { modelToRemove = voice.voiceName },
                                        enabled = state.downloadingModel == null && state.removingModel == null,
                                    ) { Text(if (state.removingModel == voice.voiceName) "删除中…" else "删除") }
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(SettingsAction.RefreshVoices) }, enabled = !state.voiceScanInProgress) { Text("刷新音色") }
            },
            confirmButton = { TextButton(onClick = { modelManagerOpen = false }) { Text("完成") } },
        )
    }
    if (modelDownloadsOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { modelDownloadsOpen = false },
            title = { Text("下载语音模型到本应用") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("点击模型后会在应用内下载、校验并保存；下载期间请保持网络连接。完成后可直接试听，最多同时启用三个音色。")
                    Text("应用已内置 LJSpeech。下方是第三方可选模型，请按各自许可使用。", style = MaterialTheme.typography.bodySmall)
                    Text("也可到官方模型目录下载其他英语 Piper 模型，解压后在模型管理中导入包含 .onnx 和 tokens.txt 的文件夹。", style = MaterialTheme.typography.bodySmall)
                    SpeechModelDownloads.models.forEach { (label, model) ->
                        val installed = state.allVoices.any { it.voiceName == model &&
                            it.enginePackageName.startsWith("com.pengshi.tts.downloaded.") }
                        OutlinedButton(
                            onClick = { onAction(SettingsAction.DownloadSpeechModel(model)) },
                            enabled = !installed && state.downloadingModel == null && state.removingModel == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(when {
                                installed -> "$label · 已在本应用中"
                                state.downloadingModel == model -> "$label · 下载中${state.downloadPercent?.let { " $it%" }.orEmpty()}"
                                else -> "下载 $label"
                            })
                        }
                    }
                    state.speechMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = {
                        runCatching { uriHandler.openUri(SpeechModelDownloads.guideUrl) }
                            .onFailure { downloadError = "请使用浏览器打开 Sherpa-ONNX 官方模型目录。" }
                    }) { Text("查看模型来源和许可") }
                    downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { modelDownloadsOpen = false }) { Text("返回模型管理") } },
        )
    }
    modelToRemove?.let { name ->
        val label = SpeechModelDownloads.models.firstOrNull { it.second == name }?.first ?: name
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { modelToRemove = null },
            title = { Text("删除语音模型？") },
            text = { Text("将从本应用删除 $label 的模型文件。需要时可重新下载或导入。") },
            confirmButton = { TextButton(onClick = {
                onAction(SettingsAction.UninstallSpeechModel(name))
                modelToRemove = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { modelToRemove = null }) { Text("取消") } },
        )
    }
    if (licenseDialogOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { licenseDialogOpen = false },
            title = { Text(if (showThirdPartyLicenses) "第三方说明" else "GNU GPL v3.0") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                }
            },
            dismissButton = {
                TextButton(onClick = { showThirdPartyLicenses = !showThirdPartyLicenses }) {
                    Text(if (showThirdPartyLicenses) "项目许可证" else "第三方说明")
                }
            },
            confirmButton = { TextButton(onClick = { licenseDialogOpen = false }) { Text("关闭") } },
        )
    }

}
