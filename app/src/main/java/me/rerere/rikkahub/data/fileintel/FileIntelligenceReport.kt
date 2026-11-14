package me.rerere.rikkahub.data.fileintel

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.fileintel.inspectors.AarInfo
import me.rerere.rikkahub.data.fileintel.inspectors.ApkInfo
import me.rerere.rikkahub.data.fileintel.inspectors.MediaInfo
import me.rerere.rikkahub.data.fileintel.inspectors.PdfInfo
import me.rerere.rikkahub.data.fileintel.inspectors.ZipInfo

/**
 * Complete, structured intelligence report for a file.
 */
data class FileIntelligenceReport(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val detection: FileDetectionResult,
    val apkInfo: ApkInfo? = null,
    val aarInfo: AarInfo? = null,
    val zipInfo: ZipInfo? = null,
    val pdfInfo: PdfInfo? = null,
    val mediaInfo: MediaInfo? = null,
    val warnings: List<String> = emptyList(),
) {
    fun toJsonString(): String = buildJsonObject {
        put("path", path)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("format", detection.format)
        put("category", detection.category.identifier)
        put("mimeType", detection.mimeType)
        put("isExtensionMismatch", detection.isExtensionMismatch)
        put("magicMatched", detection.magicMatched)
        if (warnings.isNotEmpty()) {
            put("warnings", buildJsonArray { warnings.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }

        apkInfo?.let { apk ->
            put("apk", buildJsonObject {
                put("packageName", apk.packageName)
                put("versionCode", apk.versionCode)
                put("versionName", apk.versionName)
                put("minSdkVersion", apk.minSdkVersion)
                put("targetSdkVersion", apk.targetSdkVersion)
                put("permissionsCount", apk.permissions.size)
                put("activitiesCount", apk.activities.size)
                put("servicesCount", apk.services.size)
                put("receiversCount", apk.receivers.size)
                put("providersCount", apk.providers.size)
                put("dexCount", apk.dexFiles.size)
                put("totalDexBytes", apk.totalDexBytes)
                put("hasV1Signature", apk.hasV1Signature)
                put("assetCount", apk.assetCount)
                put("nativeAbis", buildJsonArray { apk.nativeAbis.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("permissions", buildJsonArray { apk.permissions.take(20).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }

        aarInfo?.let { aar ->
            put("aar", buildJsonObject {
                put("packageName", aar.packageName)
                put("jarEntriesCount", aar.jarEntriesCount)
                put("hasClassesJar", aar.hasClassesJar)
                put("hasResources", aar.hasResources)
                put("hasProguardRules", aar.hasProguardRules)
                put("resourceSymbolsCount", aar.resourceSymbolsCount)
                put("jniAbis", buildJsonArray { aar.jniAbis.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }

        zipInfo?.let { zip ->
            put("zip", buildJsonObject {
                put("totalEntries", zip.totalEntries)
                put("compressedBytes", zip.totalCompressedBytes)
                put("uncompressedBytes", zip.totalUncompressedBytes)
                put("compressionRatio", zip.compressionRatio)
                put("isZipBombSuspected", zip.isZipBombSuspected)
                put("isZipSlipSuspected", zip.isZipSlipSuspected)
                put("topLevelDirs", buildJsonArray { zip.topLevelDirs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("topLevelFiles", buildJsonArray { zip.topLevelFiles.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }

        pdfInfo?.let { pdf ->
            put("pdf", buildJsonObject {
                put("version", pdf.version)
                put("pageCount", pdf.pageCount)
                put("isEncrypted", pdf.isEncrypted)
                put("isLinearized", pdf.isLinearized)
                put("title", pdf.title)
                put("author", pdf.author)
                put("creator", pdf.creator)
                put("producer", pdf.producer)
            })
        }

        mediaInfo?.let { media ->
            put("media", buildJsonObject {
                put("mediaType", media.mediaType)
                put("width", media.width)
                put("height", media.height)
                put("durationSeconds", media.durationSeconds)
                put("channels", media.channels)
                put("sampleRate", media.sampleRate)
                put("bitrateKbps", media.bitrateKbps)
                put("formatDetails", media.formatDetails)
            })
        }
    }.toString()
}
