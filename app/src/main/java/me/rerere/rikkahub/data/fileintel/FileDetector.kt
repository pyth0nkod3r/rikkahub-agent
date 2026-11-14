package me.rerere.rikkahub.data.fileintel

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Result of file detection.
 */
data class FileDetectionResult(
    val format: String,
    val category: FileCategory,
    val mimeType: String,
    val isExtensionMismatch: Boolean = false,
    val claimedExtension: String? = null,
    val magicMatched: Boolean = false,
    val details: String? = null,
)

/**
 * Robust content-based file detector with magic bytes sniffing and extension cross-checking.
 */
object FileDetector {

    private val EXTENSION_MAP = mapOf(
        // Documents
        "pdf" to ("pdf" to FileCategory.DOCUMENTS),
        "docx" to ("docx" to FileCategory.DOCUMENTS),
        "doc" to ("doc" to FileCategory.DOCUMENTS),
        "odt" to ("odt" to FileCategory.DOCUMENTS),
        "rtf" to ("rtf" to FileCategory.DOCUMENTS),
        "txt" to ("txt" to FileCategory.DOCUMENTS),
        "md" to ("md" to FileCategory.DOCUMENTS),
        "html" to ("html" to FileCategory.DOCUMENTS),
        "htm" to ("html" to FileCategory.DOCUMENTS),
        "epub" to ("epub" to FileCategory.DOCUMENTS),

        // Spreadsheets
        "xlsx" to ("xlsx" to FileCategory.SPREADSHEETS),
        "xls" to ("xls" to FileCategory.SPREADSHEETS),
        "csv" to ("csv" to FileCategory.SPREADSHEETS),
        "tsv" to ("tsv" to FileCategory.SPREADSHEETS),
        "ods" to ("ods" to FileCategory.SPREADSHEETS),

        // Presentations
        "pptx" to ("pptx" to FileCategory.PRESENTATIONS),
        "ppt" to ("ppt" to FileCategory.PRESENTATIONS),
        "odp" to ("odp" to FileCategory.PRESENTATIONS),

        // Images
        "png" to ("png" to FileCategory.IMAGES),
        "jpg" to ("jpg" to FileCategory.IMAGES),
        "jpeg" to ("jpg" to FileCategory.IMAGES),
        "webp" to ("webp" to FileCategory.IMAGES),
        "gif" to ("gif" to FileCategory.IMAGES),
        "bmp" to ("bmp" to FileCategory.IMAGES),
        "ico" to ("ico" to FileCategory.IMAGES),
        "svg" to ("svg" to FileCategory.IMAGES),
        "tiff" to ("tiff" to FileCategory.IMAGES),
        "tif" to ("tiff" to FileCategory.IMAGES),

        // Audio
        "mp3" to ("mp3" to FileCategory.AUDIO),
        "wav" to ("wav" to FileCategory.AUDIO),
        "aac" to ("aac" to FileCategory.AUDIO),
        "m4a" to ("m4a" to FileCategory.AUDIO),
        "flac" to ("flac" to FileCategory.AUDIO),
        "ogg" to ("ogg" to FileCategory.AUDIO),

        // Video
        "mp4" to ("mp4" to FileCategory.VIDEO),
        "mov" to ("mov" to FileCategory.VIDEO),
        "avi" to ("avi" to FileCategory.VIDEO),
        "mkv" to ("mkv" to FileCategory.VIDEO),
        "webm" to ("webm" to FileCategory.VIDEO),

        // Archives
        "zip" to ("zip" to FileCategory.ARCHIVES),
        "apk" to ("apk" to FileCategory.ARCHIVES),
        "aar" to ("aar" to FileCategory.ARCHIVES),
        "jar" to ("jar" to FileCategory.ARCHIVES),
        "tar" to ("tar" to FileCategory.ARCHIVES),
        "gz" to ("gz" to FileCategory.ARCHIVES),
        "7z" to ("7z" to FileCategory.ARCHIVES),
        "bz2" to ("bz2" to FileCategory.ARCHIVES),

        // Code
        "kt" to ("kt" to FileCategory.CODE),
        "kts" to ("kts" to FileCategory.CODE),
        "java" to ("java" to FileCategory.CODE),
        "py" to ("py" to FileCategory.CODE),
        "js" to ("js" to FileCategory.CODE),
        "ts" to ("ts" to FileCategory.CODE),
        "tsx" to ("tsx" to FileCategory.CODE),
        "jsx" to ("jsx" to FileCategory.CODE),
        "c" to ("c" to FileCategory.CODE),
        "cpp" to ("cpp" to FileCategory.CODE),
        "h" to ("h" to FileCategory.CODE),
        "hpp" to ("hpp" to FileCategory.CODE),
        "rs" to ("rs" to FileCategory.CODE),
        "go" to ("go" to FileCategory.CODE),
        "cs" to ("cs" to FileCategory.CODE),
        "json" to ("json" to FileCategory.CODE),
        "xml" to ("xml" to FileCategory.CODE),
        "yaml" to ("yaml" to FileCategory.CODE),
        "yml" to ("yaml" to FileCategory.CODE),
        "toml" to ("toml" to FileCategory.CODE),
        "sh" to ("sh" to FileCategory.CODE),
        "bat" to ("bat" to FileCategory.CODE),
        "ps1" to ("ps1" to FileCategory.CODE),
        "sql" to ("sql" to FileCategory.CODE),
    )

    private val MIME_MAP = mapOf(
        "pdf" to "application/pdf",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
        "aar" to "application/vnd.android.aar",
        "jar" to "application/java-archive",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "md" to "text/markdown",
        "txt" to "text/plain",
    )

    /**
     * Sniff file header and determine canonical format, category and MIME type.
     */
    fun detect(file: File): FileDetectionResult {
        if (!file.exists() || !file.isFile) {
            val ext = file.extension.lowercase()
            val (fmt, cat) = EXTENSION_MAP[ext] ?: (ext to FileCategory.UNKNOWN)
            return FileDetectionResult(
                format = fmt,
                category = cat,
                mimeType = MIME_MAP[fmt] ?: "application/octet-stream",
                claimedExtension = ext.ifBlank { null },
            )
        }

        val header = ByteArray(512)
        val readBytes = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(0)

        val ext = file.extension.lowercase()
        return detectFromBytes(header, readBytes, ext, file)
    }

    /**
     * Detect format from byte header with fallback to file structure inspection.
     */
    fun detectFromBytes(
        header: ByteArray,
        length: Int,
        extensionHint: String? = null,
        backingFile: File? = null,
    ): FileDetectionResult {
        val ext = extensionHint?.lowercase()?.trimStart('.')

        // 1. Check Magic Bytes
        val magicFmt = sniffMagicBytes(header, length)
        if (magicFmt != null) {
            // Special handling for PK (ZIP-based formats: APK, AAR, JAR, DOCX, XLSX, PPTX, ZIP)
            if (magicFmt == "zip" && backingFile != null && backingFile.exists()) {
                val refined = refineZipFormat(backingFile, ext)
                val isMismatch = ext != null && ext.isNotEmpty() && !isCompatibleExtension(ext, refined)
                return FileDetectionResult(
                    format = refined,
                    category = EXTENSION_MAP[refined]?.second ?: FileCategory.ARCHIVES,
                    mimeType = MIME_MAP[refined] ?: "application/zip",
                    isExtensionMismatch = isMismatch,
                    claimedExtension = ext,
                    magicMatched = true,
                    details = "Detected $refined via archive structure inspection",
                )
            }

            val category = EXTENSION_MAP[magicFmt]?.second ?: FileCategory.UNKNOWN
            val isMismatch = ext != null && ext.isNotEmpty() && !isCompatibleExtension(ext, magicFmt)
            return FileDetectionResult(
                format = magicFmt,
                category = category,
                mimeType = MIME_MAP[magicFmt] ?: "application/octet-stream",
                isExtensionMismatch = isMismatch,
                claimedExtension = ext,
                magicMatched = true,
            )
        }

        // 2. Extension fallback
        if (!ext.isNullOrBlank()) {
            val (fmt, cat) = EXTENSION_MAP[ext] ?: (ext to FileCategory.UNKNOWN)
            return FileDetectionResult(
                format = fmt,
                category = cat,
                mimeType = MIME_MAP[fmt] ?: "text/plain",
                isExtensionMismatch = false,
                claimedExtension = ext,
                magicMatched = false,
            )
        }

        return FileDetectionResult(
            format = "unknown",
            category = FileCategory.UNKNOWN,
            mimeType = "application/octet-stream",
        )
    }

    private fun sniffMagicBytes(bytes: ByteArray, len: Int): String? {
        if (len < 4) return null

        // PDF: %PDF
        if (bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            return "pdf"
        }

        // PNG: \x89PNG\r\n\x1a\n
        if (len >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return "png"
        }

        // JPEG: \xff\xd8\xff
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "jpg"
        }

        // GIF: GIF87a or GIF89a
        if (len >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) {
            return "gif"
        }

        // BMP: BM
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return "bmp"
        }

        // RIFF-based formats (WEBP, WAV, AVI)
        if (len >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            val sub = String(bytes, 8, 4, Charsets.US_ASCII)
            return when {
                sub == "WEBP" -> "webp"
                sub == "WAVE" -> "wav"
                sub.startsWith("AVI") -> "avi"
                else -> "riff"
            }
        }

        // MP3: ID3
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return "mp3"
        }

        // OGG: OggS
        if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()
        ) {
            return "ogg"
        }

        // FLAC: fLaC
        if (bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
        ) {
            return "flac"
        }

        // MKV / WebM: \x1a\x45\xdf\xa3
        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) {
            return "mkv"
        }

        // MP4 / MOV: offset 4 ftyp
        if (len >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
        ) {
            return "mp4"
        }

        // 7-Zip: 7z\xbc\xaf\x27\x1c
        if (len >= 6 && bytes[0] == '7'.code.toByte() && bytes[1] == 'z'.code.toByte() &&
            bytes[2] == 0xBC.toByte() && bytes[3] == 0xAF.toByte() &&
            bytes[4] == 0x27.toByte() && bytes[5] == 0x1C.toByte()
        ) {
            return "7z"
        }

        // GZIP: \x1f\x8b
        if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return "gz"
        }

        // BZIP2: BZh
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'Z'.code.toByte() && bytes[2] == 'h'.code.toByte()) {
            return "bz2"
        }

        // ZIP: PK\x03\x04 (standard entry) or PK\x05\x06 (empty archive EOCD)
        if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
             (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()))
        ) {
            return "zip"
        }

        return null
    }

    private fun refineZipFormat(file: File, claimedExt: String?): String {
        return runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                when {
                    entries.contains("AndroidManifest.xml") && entries.any { it.endsWith(".dex") } -> "apk"
                    entries.contains("AndroidManifest.xml") && entries.contains("classes.jar") -> "aar"
                    entries.contains("META-INF/MANIFEST.MF") && claimedExt == "jar" -> "jar"
                    entries.contains("[Content_Types].xml") -> {
                        when {
                            entries.any { it.startsWith("word/") } -> "docx"
                            entries.any { it.startsWith("xl/") } -> "xlsx"
                            entries.any { it.startsWith("ppt/") } -> "pptx"
                            else -> "zip"
                        }
                    }
                    claimedExt in setOf("apk", "aar", "jar") -> claimedExt!!
                    else -> "zip"
                }
            }
        }.getOrDefault("zip")
    }

    private fun isCompatibleExtension(ext: String, detectedFmt: String): Boolean {
        if (ext == detectedFmt) return true
        return when (detectedFmt) {
            "jpg" -> ext in setOf("jpg", "jpeg")
            "tiff" -> ext in setOf("tiff", "tif")
            "yaml" -> ext in setOf("yaml", "yml")
            "html" -> ext in setOf("html", "htm")
            "zip" -> ext in setOf("zip", "apk", "aar", "jar")
            "apk", "aar", "jar" -> ext == detectedFmt || ext == "zip"
            "mp4" -> ext in setOf("mp4", "mov", "m4v")
            "mkv" -> ext in setOf("mkv", "webm")
            else -> false
        }
    }
}
