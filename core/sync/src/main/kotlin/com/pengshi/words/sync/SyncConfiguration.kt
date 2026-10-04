package com.pengshi.words.sync

data class GitHubSyncSettings(
    val owner: String,
    val repository: String,
    val branch: String = "main",
    val syncPassword: String,
) {
    init {
        require(owner.isNotBlank()) { "GitHub owner is required" }
        require(repository.isNotBlank()) { "GitHub repository is required" }
        require(branch.isNotBlank()) { "GitHub branch is required" }
        require(syncPassword.isNotBlank()) { "Sync password is required" }
    }
}
