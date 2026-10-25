package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — `notification_received` trigger. Hooks into the existing
 * [me.rerere.rikkahub.service.RikkaNotificationListenerService] via
 * [NotificationTriggerDispatcher]. We don't register a second listener; the existing
 * one calls our dispatcher on every posted notification, and the family decides whether
 * any workflow matches.
 *
 * Filter logic (AND across non-null fields):
 *   - package_name (exact match)
 *   - title_contains / text_contains (case-insensitive substring)
 *   - title_matches / text_matches (Java regex, tested with find())
 * If both a `*_contains` and a `*_matches` are set for the same field, BOTH must pass.
 * An uncompilable regex fails safe — treated as "no match" — so a bad pattern can never
 * crash notification evaluation. workflow_create validates patterns at authoring time.
 * Validator already rejected workflows with no filters (would fire on every notification).
 */
internal class NotificationTriggerFamily(
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "notification"

    private data class CompiledRule(
        val workflowId: String,
        val spec: TriggerSpec.NotificationReceived,
        val titlePattern: java.util.regex.Pattern?,
        val titleRegexInvalid: Boolean,
        val textPattern: java.util.regex.Pattern?,
        val textRegexInvalid: Boolean,
    )

    @Volatile private var compiledRules: List<CompiledRule> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.NotificationReceived

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.compiledRules = matching.mapNotNull { wf ->
            val t = wf.trigger as? TriggerSpec.NotificationReceived ?: return@mapNotNull null
            val titlePat = t.titleMatches?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { java.util.regex.Pattern.compile(raw) }.getOrNull()
            }
            val textPat = t.textMatches?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { java.util.regex.Pattern.compile(raw) }.getOrNull()
            }
            CompiledRule(
                workflowId = wf.id,
                spec = t,
                titlePattern = titlePat,
                titleRegexInvalid = !t.titleMatches.isNullOrBlank() && titlePat == null,
                textPattern = textPat,
                textRegexInvalid = !t.textMatches.isNullOrBlank() && textPat == null,
            )
        }
        this.fireCallback = callback
        NotificationTriggerDispatcher.bind(this)
    }

    override suspend fun shutdown() {
        compiledRules = emptyList()
        fireCallback = null
    }

    /**
     * Called by [NotificationTriggerDispatcher.onPosted] from the existing notification
     * listener service. All three fields are nullable / sanitised on the listener side.
     */
    fun onPosted(packageName: String?, title: String?, text: String?) {
        val cb = fireCallback ?: return
        val snap = compiledRules
        if (snap.isEmpty()) return
        val fires = mutableListOf<Pair<String, TriggerSpec>>()
        for (rule in snap) {
            if (matches(rule, packageName, title, text)) fires += rule.workflowId to rule.spec
        }
        if (fires.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for ((wfId, spec) in fires) {
                runCatching { cb.onFire(wfId, spec) }.onFailure {
                    Log.w(TAG, "notification: fire failed for wf=$wfId", it)
                }
            }
        }
    }

    private fun matches(rule: CompiledRule, pkg: String?, title: String?, text: String?): Boolean {
        val t = rule.spec
        if (rule.titleRegexInvalid || rule.textRegexInvalid) return false
        if (!t.packageName.isNullOrBlank() && !t.packageName.equals(pkg, ignoreCase = false)) return false
        if (!t.titleContains.isNullOrBlank()) {
            if (title.isNullOrBlank()) return false
            if (!title.contains(t.titleContains, ignoreCase = true)) return false
        }
        if (!t.textContains.isNullOrBlank()) {
            if (text.isNullOrBlank()) return false
            if (!text.contains(t.textContains, ignoreCase = true)) return false
        }
        if (rule.titlePattern != null) {
            if (title.isNullOrBlank()) return false
            if (!rule.titlePattern.matcher(title).find()) return false
        }
        if (rule.textPattern != null) {
            if (text.isNullOrBlank()) return false
            if (!rule.textPattern.matcher(text).find()) return false
        }
        return true
    }

    companion object {
        private const val TAG = "WorkflowTrigger"

        /**
         * True if [pattern] (Java regex) finds a match in [input]. A pattern that fails to
         * compile is treated as "no match" rather than throwing — a bad pattern must never
         * crash notification dispatch for unrelated workflows. (workflow_create already
         * rejects uncompilable patterns at authoring time via WorkflowJson.isValidRegex;
         * this guard only covers stored rows authored before validation tightened.)
         */
        internal fun regexFind(pattern: String, input: String): Boolean =
            runCatching { java.util.regex.Pattern.compile(pattern).matcher(input).find() }
                .getOrDefault(false)
    }
}

/** Bridge from RikkaNotificationListenerService.onNotificationPosted to the family. */
object NotificationTriggerDispatcher {
    @Volatile private var family: NotificationTriggerFamily? = null
    internal fun bind(f: NotificationTriggerFamily) { family = f }
    fun onPosted(packageName: String?, title: String?, text: String?) {
        family?.onPosted(packageName, title, text)
    }
}
