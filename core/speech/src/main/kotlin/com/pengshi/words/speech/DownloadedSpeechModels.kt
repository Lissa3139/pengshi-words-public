package com.pengshi.words.speech

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** Stores optional Piper voices in this application's private files, without installing another app. */
internal class DownloadedSpeechModels(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "speech-models")

    fun installedVoices(): List<SpeechVoiceOption> = SpeechModelDownloads.models.mapNotNull { (label, name) ->
        if (!isInstalled(name)) null else SpeechVoiceOption(
            enginePackageName = "com.pengshi.tts.downloaded.$name",
            engineLabel = label,
            voiceName = name,
            localeTag = if (name.contains("en_GB")) "en-GB" else "en-US",
            networkRequired = false,
        )
    } + root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("imported-") }
        .mapNotNull { directory ->
            val model = modelFile(directory.name) ?: return@mapNotNull null
            if (!File(directory, "tokens.txt").isFile) return@mapNotNull null
            val label = Properties().also { properties ->
                File(directory, "model.properties").takeIf(File::isFile)?.inputStream()?.use(properties::load)
            }.getProperty("label")?.takeIf(String::isNotBlank) ?: model.nameWithoutExtension
            SpeechVoiceOption("com.pengshi.tts.downloaded.${directory.name}", label,
                directory.name, if (label.contains("en_GB", true)) "en-GB" else "en-US", false)
        }

    fun modelDirectory(name: String): File {
        require(validName(name)) { "不支持的语音模型" }
        return File(root, name)
    }

    fun isInstalled(name: String): Boolean {
        if (!validName(name)) return false
        val directory = modelDirectory(name)
        return modelFile(name)?.isFile == true && File(directory, "tokens.txt").isFile
    }

    fun uninstall(name: String) {
        require(validName(name)) { "不支持的语音模型" }
        val directory = modelDirectory(name)
        if (directory.exists()) check(directory.deleteRecursively()) { "无法删除语音模型文件" }
    }

    fun modelFile(name: String): File? {
        if (!validName(name)) return null
        val directory = File(root, name)
        return if (name in SpeechModelDownloads.archiveSha256) File(directory, modelFileName(name))
        else directory.listFiles()?.singleOrNull { it.isFile && it.extension.equals("onnx", true) }
    }

    /** Import a selected Piper model folder into private app storage; only model assets are copied. */
    fun importFolder(tree: Uri): String {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val files = mutableMapOf<String, Uri>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(1) ?: continue
                if (name == "tokens.txt" || name.endsWith(".onnx", true) || name.endsWith(".onnx.json", true)) {
                    files[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        } ?: error("无法读取所选文件夹")
        val modelNames = files.keys.filter { it.endsWith(".onnx", true) }
        require(modelNames.size == 1 && "tokens.txt" in files) { "请选择包含一个 .onnx 模型和 tokens.txt 的英语 Piper 模型文件夹" }
        val modelName = modelNames.single()
        require(modelName.matches(Regex("[A-Za-z0-9._-]+")) && !modelName.contains("..") &&
            modelName.startsWith("en_", true)) { "请选择英语 Piper 模型，文件名应以 en_ 开头" }
        val id = "imported-${UUID.randomUUID()}"
        root.mkdirs()
        val staging = File(root, "$id.pending")
        try {
            check(staging.mkdirs()) { "无法创建模型文件夹" }
            var total = 0L
            for (name in listOf("tokens.txt", modelName, "$modelName.json")) {
                val uri = files[name] ?: continue
                val input = resolver.openInputStream(uri) ?: error("无法读取 $name")
                input.use { source ->
                    File(staging, name).outputStream().use { target ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_EXTRACTED_BYTES) { "模型文件超过 320 MB" }
                            target.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(File(staging, modelName).length() > 0 && File(staging, "tokens.txt").length() > 0) { "模型文件不完整" }
            File(staging, "model.properties").outputStream().use { output ->
                Properties().apply { setProperty("label", modelName.removeSuffix(".onnx")) }.store(output, null)
            }
            check(staging.renameTo(File(root, id))) { "无法保存语音模型" }
            return id
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun validName(name: String) = name in SpeechModelDownloads.archiveSha256 ||
        (name.startsWith("imported-") && name.length == 45 && name.drop(9).all { it.isLetterOrDigit() || it == '-' })

    fun install(name: String, onProgress: (Int) -> Unit) {
        require(name in SpeechModelDownloads.archiveSha256) { "不支持的语音模型" }
        if (isInstalled(name)) return
        root.mkdirs()
        val archive = File(root, "$name.download")
        val staging = File(root, "$name.pending")
        if (staging.exists()) staging.deleteRecursively()
        try {
            download(name, archive, onProgress)
            staging.mkdirs()
            extractModel(name, archive, staging)
            require(File(staging, modelFileName(name)).isFile && File(staging, "tokens.txt").isFile) {
                "下载文件缺少模型或词表，请重试"
            }
            val destination = modelDirectory(name)
            if (destination.exists()) check(destination.deleteRecursively()) { "无法清理未完成的旧模型" }
            check(staging.renameTo(destination)) { "无法保存语音模型，请检查设备存储空间" }
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun download(name: String, target: File, onProgress: (Int) -> Unit) {
        val connection = URL(SpeechModelDownloads.desktopArchiveUrl(name)).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "下载服务器返回 ${connection.responseCode}" }
            val length = connection.contentLengthLong
            require(length <= MAX_DOWNLOAD_BYTES) { "模型文件大小异常" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= MAX_DOWNLOAD_BYTES) { "模型文件超过大小限制" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val percent = if (length > 0) (received * 100 / length).toInt().coerceIn(0, 100) else 0
                        if (length > 0 && percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            require(length <= 0 || received == length) { "模型下载不完整，请重试" }
            val actual = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            require(actual == SpeechModelDownloads.archiveSha256.getValue(name)) { "模型校验失败，请重试" }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractModel(name: String, archive: File, staging: File) {
        val expected = setOf(modelFileName(name), "${modelFileName(name)}.json", "tokens.txt", "MODEL_CARD")
        var extractedBytes = 0L
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isDirectory) continue
                require(entry.isFile && !entry.isSymbolicLink && !entry.isLink) { "模型压缩包包含不支持的文件" }
                val relative = entry.name.removePrefix("$name/")
                if (entry.name != "$name/$relative" || relative !in expected) continue
                require(entry.size in 0..MAX_EXTRACTED_BYTES) { "模型文件大小异常" }
                extractedBytes += entry.size
                require(extractedBytes <= MAX_EXTRACTED_BYTES) { "模型解压大小超过限制" }
                File(staging, relative).outputStream().buffered().use { tar.copyTo(it) }
            }
        }
    }

    private fun modelFileName(name: String) = name.removePrefix("vits-piper-") + ".onnx"

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 160L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 320L * 1024 * 1024
    }
}
