package me.rerere.rikkahub.data.fileintel.inspectors

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MediaInfo(
    val mediaType: String, // "image", "audio", "video"
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Double? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrateKbps: Int? = null,
    val formatDetails: String? = null,
)

/**
 * Inspector for Media files (images, audio, video).
 */
object MediaInspector {

    fun inspect(file: File, formatHint: String? = null): MediaInfo {
        if (!file.exists() || !file.isFile) {
            return MediaInfo("unknown")
        }

        val ext = formatHint?.lowercase() ?: file.extension.lowercase()
        val header = ByteArray(4096)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(0)

        if (read < 8) return MediaInfo("unknown")

        return when (ext) {
            "png" -> inspectPng(header, read)
            "jpg", "jpeg" -> inspectJpeg(file)
            "gif" -> inspectGif(header, read)
            "bmp" -> inspectBmp(header, read)
            "webp" -> inspectWebp(header, read)
            "wav" -> inspectWav(header, read, file.length())
            "mp3" -> inspectMp3(header, read, file.length())
            "mp4", "mov" -> inspectMp4(file)
            else -> MediaInfo(
                mediaType = if (ext in setOf("mp4", "mov", "avi", "mkv", "webm")) "video"
                else if (ext in setOf("mp3", "wav", "aac", "m4a", "flac", "ogg")) "audio"
                else "image",
                formatDetails = "Format: $ext"
            )
        }
    }

    private fun inspectPng(header: ByteArray, len: Int): MediaInfo {
        if (len < 24) return MediaInfo("image", formatDetails = "PNG")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buf.position(16) // Width starts at 16 in PNG
        val width = buf.int
        val height = buf.int
        return MediaInfo("image", width = width, height = height, formatDetails = "PNG")
    }

    private fun inspectGif(header: ByteArray, len: Int): MediaInfo {
        if (len < 10) return MediaInfo("image", formatDetails = "GIF")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(6)
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        return MediaInfo("image", width = width, height = height, formatDetails = "GIF")
    }

    private fun inspectBmp(header: ByteArray, len: Int): MediaInfo {
        if (len < 26) return MediaInfo("image", formatDetails = "BMP")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(18)
        val width = buf.int
        val height = kotlin.math.abs(buf.int)
        return MediaInfo("image", width = width, height = height, formatDetails = "BMP")
    }

    private fun inspectWebp(header: ByteArray, len: Int): MediaInfo {
        if (len < 30) return MediaInfo("image", formatDetails = "WEBP")
        val sub = String(header, 12, 4, Charsets.US_ASCII)
        if (sub == "VP8 ") {
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(26)
            val w = buf.short.toInt() and 0x3FFF
            val h = buf.short.toInt() and 0x3FFF
            return MediaInfo("image", width = w, height = h, formatDetails = "WEBP (VP8)")
        } else if (sub == "VP8X") {
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(24)
            val rawW = (buf.get().toInt() and 0xFF) or ((buf.get().toInt() and 0xFF) shl 8) or ((buf.get().toInt() and 0xFF) shl 16)
            val w = rawW + 1
            val rawH = (buf.get().toInt() and 0xFF) or ((buf.get().toInt() and 0xFF) shl 8) or ((buf.get().toInt() and 0xFF) shl 16)
            val h = rawH + 1
            return MediaInfo("image", width = w, height = h, formatDetails = "WEBP (VP8X Extended)")
        }
        return MediaInfo("image", formatDetails = "WEBP")
    }

    private fun inspectJpeg(file: File): MediaInfo {
        return runCatching {
            val bytes = file.inputStream().use { stream ->
                val buf = ByteArray(65536)
                val read = stream.read(buf)
                buf.copyOf(read.coerceAtLeast(0))
            }
            var i = 0
            while (i < bytes.size - 8) {
                if ((bytes[i].toInt() and 0xFF) == 0xFF) {
                    val marker = bytes[i + 1].toInt() and 0xFF
                    // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2)
                    if (marker in listOf(0xC0, 0xC1, 0xC2)) {
                        val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                        val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                        return MediaInfo("image", width = w, height = h, formatDetails = "JPEG")
                    }
                    if (marker in 0xD0..0xD9 || marker == 0x01) {
                        i += 2
                    } else {
                        val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                        i += 2 + length
                    }
                } else {
                    i++
                }
            }
            MediaInfo("image", formatDetails = "JPEG")
        }.getOrDefault(MediaInfo("image", formatDetails = "JPEG"))
    }

    private fun inspectWav(header: ByteArray, len: Int, totalSize: Long): MediaInfo {
        if (len < 36) return MediaInfo("audio", formatDetails = "WAV")
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(22)
        val channels = buf.short.toInt() and 0xFFFF
        val sampleRate = buf.int
        val byteRate = buf.int
        val duration = if (byteRate > 0) totalSize.toDouble() / byteRate.toDouble() else null
        return MediaInfo(
            mediaType = "audio",
            channels = channels,
            sampleRate = sampleRate,
            bitrateKbps = if (byteRate > 0) (byteRate * 8) / 1000 else null,
            durationSeconds = duration?.let { ((it * 100).toLong()) / 100.0 },
            formatDetails = "WAV PCM"
        )
    }

    private fun inspectMp3(header: ByteArray, len: Int, totalSize: Long): MediaInfo {
        // Average MP3 bitrate assumption 128 kbps for estimate if header simple
        val duration = if (totalSize > 0) (totalSize.toDouble() * 8.0) / (128.0 * 1000.0) else null
        return MediaInfo(
            mediaType = "audio",
            bitrateKbps = 128,
            durationSeconds = duration?.let { ((it * 100).toLong()) / 100.0 },
            formatDetails = "MP3 Audio"
        )
    }

    private fun inspectMp4(file: File): MediaInfo {
        return MediaInfo(
            mediaType = "video",
            formatDetails = "MPEG-4 Container"
        )
    }
}
