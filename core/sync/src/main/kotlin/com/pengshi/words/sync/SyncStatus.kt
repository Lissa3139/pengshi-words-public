package com.pengshi.words.sync

enum class SyncStatus {
    SYNCING,
    UP_TO_DATE,
    PENDING_UPLOAD,
    WAITING_FOR_DEPENDENCIES,
    OFFLINE,
    AUTH_REQUIRED,
    CONFLICT,
    FAILED,
}
