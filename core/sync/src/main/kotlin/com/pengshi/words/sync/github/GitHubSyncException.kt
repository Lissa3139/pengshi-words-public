package com.pengshi.words.sync.github

class GitHubSyncException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)
