package com.pengshi.words.importer

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxParser : WordImportParser {
    override fun parse(input: InputStream, format: ImportFormat): ImportPreview {
        require(format == ImportFormat.XLSX) { "XlsxParser only supports XLSX" }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            }
        }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: return ImportPreview(emptyList(), listOf(ImportError(1, "file", "缺少第一张工作表")), format)
        return parseRecords(sheet, shared, format, allowOptionalFields = false, mergeDuplicates = false)
    }

    override fun parseForUserDeck(input: InputStream, format: ImportFormat): ImportPreview {
        require(format == ImportFormat.XLSX) { "XlsxParser only supports XLSX" }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
            }
        }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: return ImportPreview(emptyList(), listOf(ImportError(1, "file", "缺少第一张工作表")), format)
        return parseRecords(sheet, shared, format, allowOptionalFields = true, mergeDuplicates = true, personalDeckDefaults = true)
    }

    private fun parseRecords(
        sheet: ByteArray,
        shared: List<String>,
        format: ImportFormat,
        allowOptionalFields: Boolean,
        mergeDuplicates: Boolean,
        personalDeckDefaults: Boolean = false,
    ): ImportPreview = DelimitedTextParser.mapTable(
        parseSheet(sheet, shared),
        format,
        allowOptionalFields = allowOptionalFields,
        mergeDuplicates = mergeDuplicates,
        personalDeckDefaults = personalDeckDefaults,
    )

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        return (0 until document.getElementsByTagNameNS("*", "si").length).map { index ->
            val node = document.getElementsByTagNameNS("*", "si").item(index)
            (0 until node.childNodes.length).mapNotNull { childIndex ->
                val child = node.childNodes.item(childIndex)
                if (child.localName == "t") child.textContent else null
            }.joinToString("")
        }
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val document = factory.newDocumentBuilder().parse(bytes.inputStream())
        val rowNodes = document.getElementsByTagNameNS("*", "row")
        return (0 until rowNodes.length).map { rowIndex ->
            val cells = mutableMapOf<Int, String>()
            val cellNodes = rowNodes.item(rowIndex).childNodes
            repeat(cellNodes.length) { childIndex ->
                val cell = cellNodes.item(childIndex)
                if (cell.localName != "c") return@repeat
                val ref = cell.attributes?.getNamedItem("r")?.nodeValue.orEmpty()
                val column = ref.takeWhile(Char::isLetter).fold(0) { value, char -> value * 26 + (char.uppercaseChar() - 'A' + 1) } - 1
                val type = cell.attributes?.getNamedItem("t")?.nodeValue
                val valueNode = (0 until cell.childNodes.length).map { cell.childNodes.item(it) }.firstOrNull { it.localName == "v" }
                val raw = valueNode?.textContent.orEmpty()
                cells[column] = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            List((cells.keys.maxOrNull() ?: -1) + 1) { cells[it].orEmpty() }
        }
    }

    private companion object {
        val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    }
}
