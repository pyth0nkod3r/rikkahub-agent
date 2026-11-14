package me.rerere.rikkahub.data.fileintel.inspectors

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class ApkInfo(
    val packageName: String?,
    val versionCode: Long?,
    val versionName: String?,
    val minSdkVersion: Int?,
    val targetSdkVersion: Int?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val dexFiles: List<String>,
    val totalDexBytes: Long,
    val nativeAbis: List<String>,
    val hasV1Signature: Boolean,
    val assetCount: Int,
)

/**
 * Inspector for Android Package (.apk) files.
 */
object ApkInspector {

    fun inspect(file: File): ApkInfo {
        if (!file.exists() || !file.isFile) {
            return ApkInfo(null, null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0L, emptyList(), false, 0)
        }

        return ZipFile(file).use { zip ->
            var packageName: String? = null
            var versionCode: Long? = null
            var versionName: String? = null
            var minSdk: Int? = null
            var targetSdk: Int? = null
            val permissions = mutableListOf<String>()
            val activities = mutableListOf<String>()
            val services = mutableListOf<String>()
            val receivers = mutableListOf<String>()
            val providers = mutableListOf<String>()
            val dexFiles = mutableListOf<String>()
            var totalDexBytes = 0L
            val abis = mutableSetOf<String>()
            var hasV1Sig = false
            var assetCount = 0

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                when {
                    name == "AndroidManifest.xml" -> {
                        runCatching {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val manifest = parseBinaryManifest(bytes)
                            packageName = manifest.packageName
                            versionCode = manifest.versionCode
                            versionName = manifest.versionName
                            minSdk = manifest.minSdk
                            targetSdk = manifest.targetSdk
                            permissions.addAll(manifest.permissions)
                            activities.addAll(manifest.activities)
                            services.addAll(manifest.services)
                            receivers.addAll(manifest.receivers)
                            providers.addAll(manifest.providers)
                        }
                    }
                    name.endsWith(".dex") -> {
                        dexFiles.add(name)
                        totalDexBytes += entry.size
                    }
                    name.startsWith("lib/") -> {
                        val parts = name.split('/')
                        if (parts.size >= 2 && parts[1].isNotBlank()) {
                            abis.add(parts[1])
                        }
                    }
                    name.startsWith("assets/") -> {
                        assetCount++
                    }
                    name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) -> {
                        hasV1Sig = true
                    }
                }
            }

            ApkInfo(
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                minSdkVersion = minSdk,
                targetSdkVersion = targetSdk,
                permissions = permissions.distinct().sorted(),
                activities = activities.distinct().sorted(),
                services = services.distinct().sorted(),
                receivers = receivers.distinct().sorted(),
                providers = providers.distinct().sorted(),
                dexFiles = dexFiles.sorted(),
                totalDexBytes = totalDexBytes,
                nativeAbis = abis.sorted(),
                hasV1Signature = hasV1Sig,
                assetCount = assetCount,
            )
        }
    }

    private data class ParsedManifest(
        val packageName: String? = null,
        val versionCode: Long? = null,
        val versionName: String? = null,
        val minSdk: Int? = null,
        val targetSdk: Int? = null,
        val permissions: List<String> = emptyList(),
        val activities: List<String> = emptyList(),
        val services: List<String> = emptyList(),
        val receivers: List<String> = emptyList(),
        val providers: List<String> = emptyList(),
    )

    /**
     * Fast Android Binary XML (AXML) parser.
     */
    private fun parseBinaryManifest(bytes: ByteArray): ParsedManifest {
        if (bytes.size < 8) return ParsedManifest()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val headerMagic = buffer.short.toInt() and 0xFFFF
        if (headerMagic != 0x0003) return ParsedManifest() // Not an AXML document
        buffer.short // header size
        buffer.int   // chunk size

        val stringTable = mutableListOf<String>()
        val permissions = mutableListOf<String>()
        val activities = mutableListOf<String>()
        val services = mutableListOf<String>()
        val receivers = mutableListOf<String>()
        val providers = mutableListOf<String>()
        var packageName: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null

        while (buffer.hasRemaining()) {
            val chunkPos = buffer.position()
            if (buffer.remaining() < 8) break
            val chunkType = buffer.short.toInt() and 0xFFFF
            val headerSize = buffer.short.toInt() and 0xFFFF
            val chunkSize = buffer.int

            if (chunkSize <= 0) break

            when (chunkType) {
                0x0001 -> { // RES_STRING_POOL_TYPE
                    val stringCount = buffer.int
                    val styleCount = buffer.int
                    val flags = buffer.int
                    val stringsStart = buffer.int
                    val stylesStart = buffer.int
                    val isUtf8 = (flags and (1 shl 8)) != 0

                    val stringOffsets = IntArray(stringCount)
                    for (i in 0 until stringCount) {
                        stringOffsets[i] = buffer.int
                    }

                    val stringPoolBase = chunkPos + stringsStart
                    for (i in 0 until stringCount) {
                        val offset = stringPoolBase + stringOffsets[i]
                        if (offset < bytes.size) {
                            val str = if (isUtf8) readUtf8String(bytes, offset) else readUtf16String(bytes, offset)
                            stringTable.add(str)
                        } else {
                            stringTable.add("")
                        }
                    }
                }
                0x0102 -> { // RES_XML_START_ELEMENT_TYPE
                    buffer.int // lineNumber
                    buffer.int // comment
                    val nsIdx = buffer.int
                    val nameIdx = buffer.int
                    buffer.short // attributeStart
                    buffer.short // attributeSize
                    val attributeCount = buffer.short.toInt() and 0xFFFF
                    buffer.short // idIndex
                    buffer.short // classIndex
                    buffer.short // styleIndex

                    val tagName = stringTable.getOrNull(nameIdx).orEmpty()

                    for (i in 0 until attributeCount) {
                        buffer.int // attrNs
                        val attrNameIdx = buffer.int
                        val attrRawValIdx = buffer.int
                        val attrType = (buffer.short.toInt() and 0xFFFF) shr 8
                        buffer.get() // byte
                        val attrData = buffer.int

                        val attrName = stringTable.getOrNull(attrNameIdx).orEmpty()
                        val rawVal = stringTable.getOrNull(attrRawValIdx)

                        when (tagName) {
                            "manifest" -> {
                                if (attrName == "package") {
                                    packageName = rawVal
                                } else if (attrName == "versionCode") {
                                    versionCode = attrData.toLong()
                                } else if (attrName == "versionName") {
                                    versionName = rawVal
                                }
                            }
                            "uses-sdk" -> {
                                if (attrName == "minSdkVersion") {
                                    minSdk = attrData
                                } else if (attrName == "targetSdkVersion") {
                                    targetSdk = attrData
                                }
                            }
                            "uses-permission" -> {
                                if (attrName == "name" && !rawVal.isNullOrBlank()) {
                                    permissions.add(rawVal)
                                }
                            }
                            "activity" -> {
                                if (attrName == "name" && !rawVal.isNullOrBlank()) {
                                    activities.add(rawVal)
                                }
                            }
                            "service" -> {
                                if (attrName == "name" && !rawVal.isNullOrBlank()) {
                                    services.add(rawVal)
                                }
                            }
                            "receiver" -> {
                                if (attrName == "name" && !rawVal.isNullOrBlank()) {
                                    receivers.add(rawVal)
                                }
                            }
                            "provider" -> {
                                if (attrName == "name" && !rawVal.isNullOrBlank()) {
                                    providers.add(rawVal)
                                }
                            }
                        }
                    }
                }
            }

            // Move to next chunk
            buffer.position(chunkPos + chunkSize)
        }

        return ParsedManifest(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
        )
    }

    private fun readUtf8String(bytes: ByteArray, offset: Int): String {
        var pos = offset
        if (pos >= bytes.size) return ""
        // length encoded as varint
        var charLen = bytes[pos++].toInt() and 0xFF
        if ((charLen and 0x80) != 0) {
            charLen = ((charLen and 0x7F) shl 8) or (bytes[pos++].toInt() and 0xFF)
        }
        var byteLen = bytes[pos++].toInt() and 0xFF
        if ((byteLen and 0x80) != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (bytes[pos++].toInt() and 0xFF)
        }
        val end = (pos + byteLen).coerceAtMost(bytes.size)
        return String(bytes, pos, end - pos, Charsets.UTF_8)
    }

    private fun readUtf16String(bytes: ByteArray, offset: Int): String {
        var pos = offset
        if (pos + 2 > bytes.size) return ""
        val b0 = bytes[pos++].toInt() and 0xFF
        val b1 = bytes[pos++].toInt() and 0xFF
        var charLen = (b1 shl 8) or b0
        if ((charLen and 0x8000) != 0) {
            val b2 = bytes[pos++].toInt() and 0xFF
            val b3 = bytes[pos++].toInt() and 0xFF
            charLen = ((charLen and 0x7FFF) shl 16) or ((b3 shl 8) or b2)
        }
        val byteLen = charLen * 2
        val end = (pos + byteLen).coerceAtMost(bytes.size)
        return String(bytes, pos, end - pos, Charsets.UTF_16LE)
    }
}
