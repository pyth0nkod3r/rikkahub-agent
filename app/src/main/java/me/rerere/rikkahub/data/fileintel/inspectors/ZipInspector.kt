package me.rerere.rikkahub.data.fileintel.inspectors

import java.io.File
import java.util.zip.ZipFile

data class ZipInfo(
    val totalEntries: Int,
    val totalCompressedBytes: Long,
    val totalUncompressedBytes: Long,
    val compressionRatio: Double,
    val topLevelDirs: List<String>,
    val topLevelFiles: List<String>,
    val fileCountsByExt: Map<String, Int>,
    val isZipBombSuspected: Boolean,
    val isZipSlipSuspected: Boolean,
    val suspiciousEntries: List<String>,
)

/**
 * Robust archive inspector with security analysis (Zip Bomb and Zip Slip detection).
 */
object ZipInspector {

    private const val ZIP_BOMB_RATIO_THRESHOLD = 100.0
    private const val ZIP_BOMB_MAX_ENTRIES = 50_000

    fun inspect(file: File): ZipInfo {
        if (!file.exists() || !file.isFile) {
            return ZipInfo(0, 0L, 0L, 1.0, emptyList(), emptyList(), emptyMap(), false, false, emptyList())
        }

        return ZipFile(file).use { zip ->
            var count = 0
            var compressedBytes = 0L
            var uncompressedBytes = 0L
            val topLevelDirs = mutableSetOf<String>()
            val topLevelFiles = mutableSetOf<String>()
            val extCounts = mutableMapOf<String, Int>()
            val suspicious = mutableListOf<String>()

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                val cSize = entry.compressedSize.coerceAtLeast(0L)
                val uSize = entry.size.coerceAtLeast(0L)
                compressedBytes += cSize
                uncompressedBytes += uSize

                val name = entry.name
                val normalized = name.trimStart('/').trimStart('\\')
                val parts = normalized.split('/', '\\')

                // Zip Slip check: path traversal segment or root-relative prefix
                if (parts.any { it == ".." } || name.startsWith("/") || name.startsWith("\\")) {
                    suspicious.add(name)
                }
                if (parts.size == 1 && !entry.isDirectory) {
                    topLevelFiles.add(parts[0])
                } else if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    topLevelDirs.add(parts[0])
                }

                if (!entry.isDirectory) {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isNotBlank()) {
                        extCounts[ext] = (extCounts[ext] ?: 0) + 1
                    }
                }
            }

            val ratio = if (compressedBytes > 0) {
                uncompressedBytes.toDouble() / compressedBytes.toDouble()
            } else 1.0

            val isBomb = ratio > ZIP_BOMB_RATIO_THRESHOLD || count > ZIP_BOMB_MAX_ENTRIES
            val isSlip = suspicious.isNotEmpty()

            ZipInfo(
                totalEntries = count,
                totalCompressedBytes = compressedBytes,
                totalUncompressedBytes = uncompressedBytes,
                compressionRatio = ((ratio * 100).toLong()) / 100.0,
                topLevelDirs = topLevelDirs.sorted(),
                topLevelFiles = topLevelFiles.sorted(),
                fileCountsByExt = extCounts,
                isZipBombSuspected = isBomb,
                isZipSlipSuspected = isSlip,
                suspiciousEntries = suspicious.take(10),
            )
        }
    }
}
