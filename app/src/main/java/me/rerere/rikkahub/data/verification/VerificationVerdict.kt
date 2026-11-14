package me.rerere.rikkahub.data.verification

/**
 * The 5 verdicts of the Durable Verification Ledger.
 *
 * "Done" is not verification:
 * A unit marked COMPLETED whose only write failed, whose command exited 1,
 * or whose tool call crashed is CONTRADICTED.
 */
enum class VerificationVerdict(val code: String, val description: String) {
    /** Not settled - nothing has been claimed yet, so there is nothing to check. */
    OPEN("open", "Not settled - execution is still in progress or unannounced."),

    /** Settled, and it says so (failed, cancelled, skipped) - nothing to contradict. */
    UNSUCCESSFUL("unsuccessful", "Settled with failure, cancellation, or skip."),

    /** Claims success, and the durable record agrees. */
    CONFIRMED("confirmed", "Claims success, and the durable record confirms it."),

    /** Claims success, but the durable record DISAGREES. */
    CONTRADICTED("contradicted", "Claims success, but evidence contradicts the claim."),

    /** Claims success, and nothing was recorded either way. */
    UNCONFIRMED("unconfirmed", "Claims success, but no durable evidence was recorded.");

    val isTrusted: Boolean get() = this == CONFIRMED

    companion object {
        fun fromCode(code: String): VerificationVerdict? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
