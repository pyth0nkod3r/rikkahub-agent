package me.rerere.rikkahub.data.fileintel.inspectors

import java.io.File

data class PdfInfo(
    val version: String,
    val pageCount: Int,
    val isEncrypted: Boolean,
    val isLinearized: Boolean,
    val title: String?,
    val author: String?,
    val creator: String?,
    val producer: String?,
    val creationDate: String?,
)

/**
 * Inspector for Portable Document Format (.pdf) files.
 */
object PdfInspector {

    fun inspect(file: File): PdfInfo {
        if (!file.exists() || !file.isFile) {
            return PdfInfo("Unknown", 0, false, false, null, null, null, null, null)
        }

        val text = runCatching {
            // Read first 64KB and last 64KB to cover header, info dict and trailer
            val len = file.length()
            if (len <= 128 * 1024) {
                file.readText(Charsets.ISO_8859_1)
            } else {
                val headerBytes = ByteArray(64 * 1024)
                val trailerBytes = ByteArray(64 * 1024)
                file.inputStream().use { stream ->
                    stream.read(headerBytes)
                }
                file.inputStream().use { stream ->
                    stream.skip((len - 64 * 1024).coerceAtLeast(0L))
                    stream.read(trailerBytes)
                }
                String(headerBytes, Charsets.ISO_8859_1) + "\n...\n" + String(trailerBytes, Charsets.ISO_8859_1)
            }
        }.getOrDefault("")

        // 1. PDF version
        val versionMatch = Regex("""%PDF-(\d+\.\d+)""").find(text)
        val version = versionMatch?.groupValues?.get(1) ?: "1.4"

        // 2. Encryption
        val isEncrypted = text.contains("/Encrypt")

        // 3. Linearized (fast web view)
        val isLinearized = text.contains("/Linearized")

        // 4. Page count
        val countMatch = Regex("""/Type\s*/Pages\b.*?/Count\s+(\d+)""", RegexOption.DOT_MATCHES_ALL).find(text)
        var pageCount = countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (pageCount == 0) {
            // Count /Type /Page matches
            val pageMatches = Regex("""/Type\s*/Page\b""").findAll(text).count()
            pageCount = if (pageMatches > 0) pageMatches else if (text.contains("%PDF")) 1 else 0
        }

        // 5. Metadata fields
        val title = extractPdfMeta(text, "Title")
        val author = extractPdfMeta(text, "Author")
        val creator = extractPdfMeta(text, "Creator")
        val producer = extractPdfMeta(text, "Producer")
        val creationDate = extractPdfMeta(text, "CreationDate")

        return PdfInfo(
            version = "PDF-$version",
            pageCount = pageCount,
            isEncrypted = isEncrypted,
            isLinearized = isLinearized,
            title = title,
            author = author,
            creator = creator,
            producer = producer,
            creationDate = creationDate,
        )
    }

    private fun extractPdfMeta(text: String, key: String): String? {
        val regex = Regex("""/$key\s*\(([^)]+)\)""")
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }
}
