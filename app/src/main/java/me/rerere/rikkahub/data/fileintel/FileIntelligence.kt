package me.rerere.rikkahub.data.fileintel

import me.rerere.rikkahub.data.fileintel.inspectors.AarInspector
import me.rerere.rikkahub.data.fileintel.inspectors.ApkInspector
import me.rerere.rikkahub.data.fileintel.inspectors.MediaInspector
import me.rerere.rikkahub.data.fileintel.inspectors.PdfInspector
import me.rerere.rikkahub.data.fileintel.inspectors.ZipInspector
import java.io.File

/**
 * Android File Intelligence Facade.
 * Content-based detection, deep structure inspection and security analysis.
 */
object FileIntelligence {

    fun inspect(file: File): FileIntelligenceReport {
        val detection = FileDetector.detect(file)
        val warnings = mutableListOf<String>()

        if (detection.isExtensionMismatch) {
            warnings.add("Security warning: Extension .${detection.claimedExtension} does not match detected content format (${detection.format})")
        }

        var apkInfo: me.rerere.rikkahub.data.fileintel.inspectors.ApkInfo? = null
        var aarInfo: me.rerere.rikkahub.data.fileintel.inspectors.AarInfo? = null
        var zipInfo: me.rerere.rikkahub.data.fileintel.inspectors.ZipInfo? = null
        var pdfInfo: me.rerere.rikkahub.data.fileintel.inspectors.PdfInfo? = null
        var mediaInfo: me.rerere.rikkahub.data.fileintel.inspectors.MediaInfo? = null

        when (detection.format) {
            "apk" -> {
                apkInfo = runCatching { ApkInspector.inspect(file) }.getOrNull()
                zipInfo = runCatching { ZipInspector.inspect(file) }.getOrNull()
            }
            "aar" -> {
                aarInfo = runCatching { AarInspector.inspect(file) }.getOrNull()
                zipInfo = runCatching { ZipInspector.inspect(file) }.getOrNull()
            }
            "zip", "jar" -> {
                zipInfo = runCatching { ZipInspector.inspect(file) }.getOrNull()
            }
            "pdf" -> {
                pdfInfo = runCatching { PdfInspector.inspect(file) }.getOrNull()
            }
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "wav", "mp3", "mp4", "mov" -> {
                mediaInfo = runCatching { MediaInspector.inspect(file, detection.format) }.getOrNull()
            }
        }

        if (zipInfo?.isZipBombSuspected == true) {
            warnings.add("Security warning: Suspected Zip Bomb detected (ratio=${zipInfo.compressionRatio})")
        }
        if (zipInfo?.isZipSlipSuspected == true) {
            warnings.add("Security warning: Path traversal sequences detected in archive entries (Zip Slip risk)")
        }

        return FileIntelligenceReport(
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            detection = detection,
            apkInfo = apkInfo,
            aarInfo = aarInfo,
            zipInfo = zipInfo,
            pdfInfo = pdfInfo,
            mediaInfo = mediaInfo,
            warnings = warnings,
        )
    }
}
