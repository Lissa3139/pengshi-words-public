package com.pengshi.words.desktop.sync

import com.pengshi.words.sync.GitHubSyncSettings
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Properties

data class DesktopSyncSettingsState(
    val owner: String = "",
    val repository: String = "",
    val branch: String = "main",
    val hasPassword: Boolean = false,
    val hasToken: Boolean = false,
)

/** Stores the GitHub token and sync password protected by the current Windows user. */
class DesktopSyncSettingsStore(
    private val path: Path,
    private val fallbackPaths: List<Path> = emptyList(),
) {
    fun state(): DesktopSyncSettingsState {
        val properties = load()
        return DesktopSyncSettingsState(
            owner = properties.getProperty(OWNER_KEY, ""),
            repository = properties.getProperty(REPOSITORY_KEY, ""),
            branch = properties.getProperty(BRANCH_KEY, "main"),
            hasPassword = readProtected(properties, PASSWORD_KEY) != null,
            hasToken = readProtected(properties, TOKEN_KEY) != null,
        )
    }

    fun settings(): GitHubSyncSettings? {
        val properties = load()
        val owner = properties.getProperty(OWNER_KEY, "").trim()
        val repository = properties.getProperty(REPOSITORY_KEY, "").trim()
        val branch = properties.getProperty(BRANCH_KEY, "main").trim()
        val password = readProtected(properties, PASSWORD_KEY)?.takeIf(String::isNotBlank) ?: return null
        if (owner.isBlank() || repository.isBlank()) return null
        return runCatching { GitHubSyncSettings(owner, repository, branch, password) }.getOrNull()
    }

    fun token(): String? = readProtected(load(), TOKEN_KEY)

    fun save(settings: GitHubSyncSettings, token: String) = save(
        owner = settings.owner,
        repository = settings.repository,
        branch = settings.branch,
        passwordInput = settings.syncPassword,
        tokenInput = token,
    )

    fun save(
        owner: String,
        repository: String,
        branch: String,
        passwordInput: String,
        tokenInput: String,
    ) {
        val properties = load()
        val password = passwordInput.takeIf(String::isNotBlank)
            ?: readProtected(properties, PASSWORD_KEY)
            ?: throw IllegalArgumentException("请填写同步密码")
        val token = tokenInput.takeIf(String::isNotBlank)
            ?: readProtected(properties, TOKEN_KEY)
            ?: throw IllegalArgumentException("请填写 GitHub Token")
        val settings = GitHubSyncSettings(
            owner = owner.trim(),
            repository = repository.trim(),
            branch = branch.trim().ifBlank { "main" },
            syncPassword = password,
        )
        properties.setProperty(OWNER_KEY, settings.owner)
        properties.setProperty(REPOSITORY_KEY, settings.repository)
        properties.setProperty(BRANCH_KEY, settings.branch)
        properties.setProperty(PASSWORD_KEY, protect(settings.syncPassword))
        properties.setProperty(TOKEN_KEY, protect(token))
        write(properties)
    }

    private fun load(): Properties {
        val sources = listOf(path) + fallbackPaths
        for (source in sources) {
            if (!Files.isRegularFile(source)) continue
            val properties = runCatching {
                Properties().also { values -> Files.newInputStream(source).use(values::load) }
            }.getOrNull() ?: continue
            if (listOf(OWNER_KEY, REPOSITORY_KEY, BRANCH_KEY, PASSWORD_KEY, TOKEN_KEY)
                    .any(properties::containsKey)
            ) return properties
        }
        return Properties()
    }

    private fun write(properties: Properties) {
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { properties.store(it, "PengshiWords GitHub sync settings") }
    }

    private fun readProtected(properties: Properties, key: String): String? = runCatching {
        val encoded = properties.getProperty(key)?.takeIf(String::isNotBlank) ?: return@runCatching null
        val encrypted = Base64.getDecoder().decode(encoded)
        Crypt32Util.cryptUnprotectData(encrypted).toString(StandardCharsets.UTF_8)
    }.getOrNull()

    private fun protect(value: String): String = Base64.getEncoder().encodeToString(
        Crypt32Util.cryptProtectData(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private companion object {
        const val OWNER_KEY = "github.owner"
        const val REPOSITORY_KEY = "github.repository"
        const val BRANCH_KEY = "github.branch"
        const val PASSWORD_KEY = "github.syncPassword"
        const val TOKEN_KEY = "github.token"
    }
}
