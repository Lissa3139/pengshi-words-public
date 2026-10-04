package com.pengshi.words.model

const val SOURCE_UNVERIFIED = "来源待核实"
const val SOURCE_ECDICT = "ECDICT"
const val SOURCE_NETEM = "NETEMVocabulary（CC BY-NC-SA 4.0）"
const val SOURCE_TATOEBA = "Tatoeba"
const val SOURCE_QWEN = "Qwen 生成"
const val SOURCE_CHATGPT = "ChatGPT 生成"

fun isUnverifiedSource(value: String): Boolean = value.trim().isEmpty() || value.trim() == SOURCE_UNVERIFIED

fun definitionSourceFromTags(tags: String): String = when {
    tags.split(Regex("\\s+")).any { it.equals("source:ecdict", ignoreCase = true) } -> SOURCE_ECDICT
    tags.split(Regex("\\s+")).any { it.equals("source:netem-2024", ignoreCase = true) } -> SOURCE_NETEM
    else -> SOURCE_UNVERIFIED
}

fun exampleSourceFromPack(
    sourceTag: String?,
    @Suppress("UNUSED_PARAMETER") sourceSentenceId: String? = null,
    @Suppress("UNUSED_PARAMETER") license: String? = null,
): String {
    return when (sourceTag?.trim()?.lowercase()) {
        "source:ai-generated" -> SOURCE_QWEN
        "source:tatoeba" -> SOURCE_TATOEBA
        null, "" -> SOURCE_UNVERIFIED
        else -> SOURCE_UNVERIFIED
    }
}

fun inlineExampleSourceFromTags(tags: String, exampleEn: String?): String {
    val hasCet6Tag = tags.split(Regex("\\s+")).any { it.equals("cet6-core", ignoreCase = true) }
    return if (hasCet6Tag && !exampleEn.isNullOrBlank()) SOURCE_QWEN else SOURCE_UNVERIFIED
}

/** True only for empty/unverified values and source labels emitted by older app versions. */
fun sourceLabelNeedsUpgrade(current: String, expected: String): Boolean {
    val label = current.trim()
    if (label.isEmpty() || label == SOURCE_UNVERIFIED) return true
    return when (expected) {
        SOURCE_ECDICT -> label in setOf("ECDICT（MIT）", "ECDICT (MIT)")
        SOURCE_TATOEBA -> label == "Tatoeba（句子编号待核实）" || label.startsWith("Tatoeba ·")
        SOURCE_QWEN -> label == "AI 生成（Qwen3:4B）" || label == "AI-generated (Qwen3:4B)"
        else -> false
    }
}
