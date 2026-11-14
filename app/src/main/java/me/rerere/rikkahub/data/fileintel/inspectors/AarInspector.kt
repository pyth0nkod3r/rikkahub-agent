package me.rerere.rikkahub.data.fileintel.inspectors

import java.io.File
import java.util.zip.ZipFile

data class AarInfo(
    val packageName: String?,
    val jarEntriesCount: Int,
    val hasClassesJar: Boolean,
    val hasResources: Boolean,
    val hasJniLibs: Boolean,
    val jniAbis: List<String>,
    val hasProguardRules: Boolean,
    val resourceSymbolsCount: Int,
)

/**
 * Inspector for Android Archive (.aar) libraries.
 */
object AarInspector {

    fun inspect(file: File): AarInfo {
        if (!file.exists() || !file.isFile) {
            return AarInfo(null, 0, false, false, false, emptyList(), false, 0)
        }

        return ZipFile(file).use { zip ->
            var pkgName: String? = null
            var hasClassesJar = false
            var jarCount = 0
            var hasRes = false
            val abis = mutableSetOf<String>()
            var hasProguard = false
            var symbolCount = 0

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                when {
                    name == "AndroidManifest.xml" -> {
                        runCatching {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val isBinary = bytes.size >= 2 &&
                                (bytes[0].toInt() and 0xFF) == 0x03 &&
                                (bytes[1].toInt() and 0xFF) == 0x00
                            if (isBinary) {
                                val manifest = ApkInspector.inspect(file)
                                pkgName = manifest.packageName
                            } else {
                                val text = String(bytes, Charsets.UTF_8)
                                val match = Regex("""package\s*=\s*["']([^"']+)["']""").find(text)
                                pkgName = match?.groupValues?.get(1)
                            }
                        }
                    }
                    name == "classes.jar" -> {
                        hasClassesJar = true
                        jarCount++
                    }
                    name.startsWith("res/") -> {
                        hasRes = true
                    }
                    name.startsWith("jni/") -> {
                        val parts = name.split('/')
                        if (parts.size >= 2 && parts[1].isNotBlank()) {
                            abis.add(parts[1])
                        }
                    }
                    name == "proguard.txt" -> {
                        hasProguard = true
                    }
                    name == "R.txt" -> {
                        runCatching {
                            val text = zip.getInputStream(entry).use { it.reader().readText() }
                            symbolCount = text.lines().count { it.isNotBlank() }
                        }
                    }
                }
            }

            AarInfo(
                packageName = pkgName,
                jarEntriesCount = jarCount,
                hasClassesJar = hasClassesJar,
                hasResources = hasRes,
                hasJniLibs = abis.isNotEmpty(),
                jniAbis = abis.sorted(),
                hasProguardRules = hasProguard,
                resourceSymbolsCount = symbolCount,
            )
        }
    }
}
