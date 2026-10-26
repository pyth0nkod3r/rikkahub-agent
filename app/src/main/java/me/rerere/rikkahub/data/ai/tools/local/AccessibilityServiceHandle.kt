package me.rerere.rikkahub.data.ai.tools.local

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.RikkaAccessibilityService

/**
 * Bridge between the LLM tool factories and the live AccessibilityService.
 * The service publishes itself via [RikkaAccessibilityService.instance]; this object
 * wraps the access pattern in a uniform "service-not-active" envelope so every tool
 * gets the same recovery hint.
 */
object AccessibilityServiceHandle {

    private fun getAppContext(): Context? = runCatching {
        org.koin.core.context.GlobalContext.getOrNull()?.get<Context>()
    }.getOrNull()

    /** True iff our component is listed in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. */
    fun isEnabledInSettings(ctx: Context): Boolean {
        val expected = ComponentName(ctx, RikkaAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /** True iff the service has connected and the singleton is live. */
    fun isRunning(): Boolean = RikkaAccessibilityService.instance != null

    /**
     * Runs [block] with the live service if it's connected; otherwise returns the standard
     * service-not-active envelope as a JsonObject. Tools should wrap their entire execute()
     * body in this call.
     */
    suspend fun withService(
        block: suspend (RikkaAccessibilityService) -> JsonObject
    ): JsonObject {
        val svc = RikkaAccessibilityService.instance
            ?: return notActiveEnvelope(getAppContext())
        return svc.withInteractiveSession { block(it) }
    }

    /**
     * Distinguishes between "service was killed by Android/MIUI memory management" (still
     * enabled in Settings but the singleton is null) and "service is not enabled at all".
     * MIUI/HyperOS aggressively kills accessibility services in the background; the recovery
     * hint guides the user to toggle or whitelist accordingly.
     */
    fun notActiveEnvelope(ctx: Context? = null): JsonObject = buildJsonObject {
        val context = ctx ?: getAppContext()
        val enabledInSettings = context?.let { isEnabledInSettings(it) } ?: false
        if (enabledInSettings) {
            put("error", "accessibility_service_killed")
            put(
                "recovery",
                "The Accessibility Service was terminated by Android/MIUI memory management while still enabled in system settings. " +
                    "Toggle the switch off and on in Settings → Accessibility → Installed Apps, " +
                    "or set battery management to 'No restrictions' and enable 'Autostart' for RikkaHub."
            )
        } else {
            put("error", "accessibility_service_not_enabled")
            put("recovery", "Enable RikkaHub in Android Settings → Accessibility → Installed Apps.")
        }
    }
}
