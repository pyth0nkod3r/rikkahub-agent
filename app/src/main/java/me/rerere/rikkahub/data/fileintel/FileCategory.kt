package me.rerere.rikkahub.data.fileintel

/**
 * High-level categories for detected files.
 */
enum class FileCategory(val identifier: String) {
    DOCUMENTS("documents"),
    SPREADSHEETS("spreadsheets"),
    PRESENTATIONS("presentations"),
    IMAGES("images"),
    AUDIO("audio"),
    VIDEO("video"),
    ARCHIVES("archives"),
    CODE("code"),
    EXECUTABLES("executables"),
    UNKNOWN("unknown");

    companion object {
        fun fromIdentifier(id: String): FileCategory =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) } ?: UNKNOWN
    }
}
