package com.pengshi.words.model

/** Normalizes escaped line breaks found in CSV dictionary exports. */
fun String.normalizeDictionaryText(): String = replace("\\r\\n", "\n")
    .replace("\\n", "\n")
    .replace("\\r", "\n")
    .replace("\\t", "    ")
