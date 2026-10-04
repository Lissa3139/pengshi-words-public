package com.pengshi.words.importer

import java.io.InputStream

typealias ImportRow = com.pengshi.words.model.ImportRow
typealias ImportError = com.pengshi.words.model.ImportError
typealias ImportPreview = com.pengshi.words.model.ImportPreview
typealias ImportResult = com.pengshi.words.model.ImportResult

interface WordImportParser {
    fun parse(input: InputStream, format: ImportFormat): ImportPreview

    fun parseForUserDeck(input: InputStream, format: ImportFormat): ImportPreview = parse(input, format)
}

interface ImportRepository {
    suspend fun importDeck(preview: ImportPreview): ImportResult
}
