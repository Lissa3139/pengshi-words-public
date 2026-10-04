package com.pengshi.words.model

enum class StudyMode {
    EN_TO_CN,
    CN_TO_EN,
}

data class CardKey(
    val wordId: Long,
    val mode: StudyMode,
)
