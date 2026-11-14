package me.rerere.rikkahub.data.verification

/**
 * Five verification checks that evaluate different durable records.
 */
enum class VerificationCheck(val identifier: String) {
    /** Reads the unit's own claim: status, error message. */
    STATUS("status"),

    /** Reads checkpoint sub-steps and milestones. */
    STEPS("steps"),

    /** Reads executed shell / terminal command records: exit codes, settled state. */
    COMMANDS("commands"),

    /** Reads executed tool call records: crashes, exceptions, error flags. */
    TOOLS("tools"),

    /** Reads pre/post SHA-256 file digests inside write and edit operations. */
    WRITES("writes");

    companion object {
        fun fromIdentifier(id: String): VerificationCheck? =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) }
    }
}
