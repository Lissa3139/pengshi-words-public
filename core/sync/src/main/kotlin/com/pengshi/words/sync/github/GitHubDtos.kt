package com.pengshi.words.sync.github

data class GitHubRepositoryConfig(
    val owner: String,
    val repository: String,
    val branch: String = "main",
) {
    init {
        require(owner.isNotBlank() && repository.isNotBlank()) { "GitHub owner and repository are required" }
        require(branch.isNotBlank()) { "GitHub branch is required" }
    }
}

data class GitHubTreeEntry(
    val path: String,
    val type: String,
    val sha: String,
)

data class GitHubBranchState(
    val commitSha: String,
    val treeSha: String,
    val entries: List<GitHubTreeEntry>,
)
