package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — `app_launched` / `app_closed` triggers, fed by the existing accessibility
 * service's foreground-app event stream.
 *
 * The accessibility service calls [AppForegroundDispatcher.onForegroundChange] on every
 * `TYPE_WINDOW_STATE_CHANGED` event with the new package name. This family de-dupes
 * (only fire on actual transition between packages), then matches against the matching
 * workflows.
 *
 * Battery hygiene: the accessibility service subscribes to `TYPE_WINDOW_STATE_CHANGED`
 * only while [AppForegroundDispatcher.hasConsumers] is true or an interactive tool
 * session is active, so zero workflows = zero accessibility event deliveries.
 */
internal class AppForegroundTriggerFamily(
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "app_foreground"

    @Volatile private var matching: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null
    @Volatile private var lastForegroundPackage: String? = null

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.AppLaunched || spec is TriggerSpec.AppClosed

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.matching = matching
        this.fireCallback = callback
        AppForegroundDispatcher.bind(this)
    }

    override suspend fun shutdown() {
        matching = emptyList()
        fireCallback = null
        lastForegroundPackage = null
    }

    /** Called by [AppForegroundDispatcher.onForegroundChange] from accessibility service. */
    fun onForegroundChange(newPackage: String?) {
        if (newPackage.isNullOrBlank()) return
        val prev = lastForegroundPackage
        lastForegroundPackage = newPackage
        if (prev == newPackage) return  // no transition
        val cb = fireCallback ?: return
        val snap = matching
        if (snap.isEmpty()) return

        val fires = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in snap) {
            when (val t = wf.trigger) {
                is TriggerSpec.AppLaunched ->
                    if (t.packageName == newPackage) fires += wf.id to t
                is TriggerSpec.AppClosed ->
                    if (prev != null && t.packageName == prev) fires += wf.id to t
                else -> Unit
            }
        }
        if (fires.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for ((wfId, spec) in fires) {
                runCatching { cb.onFire(wfId, spec) }.onFailure {
                    Log.w(TAG, "app_foreground: fire failed for wf=$wfId", it)
                }
            }
        }
    }

    companion object { private const val TAG = "WorkflowTrigger" }
}

/** Bridge from [me.rerere.rikkahub.service.RikkaAccessibilityService] to the family. */
object AppForegroundDispatcher {
    @Volatile private var family: AppForegroundTriggerFamily? = null
    @Volatile private var foregroundConsumerCount: Int = 0
    internal fun bind(f: AppForegroundTriggerFamily) { family = f }

    /**
     * Track how many workflows currently NEED the foreground-package cache. The trigger
     * registry calls this on every resync — if zero workflows have an `app_launched`/`_closed`
     * trigger AND no condition uses `foreground_app_*`, the dispatcher's hot path can no-op
     * entirely and [me.rerere.rikkahub.service.RikkaAccessibilityService] unsubscribes from
     * window events when idle.
     */
    internal fun setForegroundConsumerCount(n: Int) {
        val prev = foregroundConsumerCount
        foregroundConsumerCount = n
        if ((prev > 0) != (n > 0)) {
            me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.reconcileServiceInfo()
        }
    }

    fun hasConsumers(): Boolean = foregroundConsumerCount > 0

    /** Called from accessibility service's TYPE_WINDOW_STATE_CHANGED handler. */
    fun onForegroundChange(packageName: String?) {
        // Hot-path gate — skip the volatile write entirely when no workflow needs it.
        // This is called from the AccessibilityService's dispatcher thread for every
        // window-state-change. Cheap rejection here matters.
        if (foregroundConsumerCount == 0 || family == null) return
        // De-dupe: don't re-write the cache or fan out if the package didn't change.
        val prev = AppForegroundLastKnown.value
        if (prev == packageName) return
        AppForegroundLastKnown.value = packageName
        family?.onForegroundChange(packageName)
    }
}

/** Last-known foreground package, exposed for ContextProvider's foreground_app_* conditions. */
object AppForegroundLastKnown {
    @Volatile var value: String? = null
}
