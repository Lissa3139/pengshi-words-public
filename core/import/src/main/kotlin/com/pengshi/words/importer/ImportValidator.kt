package com.pengshi.words.importer

import java.util.Locale

class ImportValidator {
    fun validate(
        row: ImportRow,
        rowNumber: Int,
        seen: MutableSet<String>,
        requireDefinition: Boolean = true,
        rejectDuplicates: Boolean = true,
    ): ImportError? {
        val normalized = row.spelling.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return ImportError(rowNumber, "spelling", "单词不能为空")
        if (requireDefinition && row.definitionCn.isBlank()) return ImportError(rowNumber, "definitionCn", "中文释义不能为空")
        if (!seen.add(normalized) && rejectDuplicates) return ImportError(rowNumber, "spelling", "单词重复：${row.spelling}")
        return null
    }
}
