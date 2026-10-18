package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.preferences.isWorkspaceToolName
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Opt-in capability categories for sub-agents. A sub-agent's default catalog is restricted to
 * workspace_ tools plus explicitly named role tools. Each category below must be granted
 * explicitly, through the dispatch `capabilities` argument or the sub-agent profile.
 *
 * Tool names are matched exactly. A tool that belongs to no category stays hidden from
 * sub-agents unless it is named as a role tool (fail closed).
 */
enum class SubAgentCapability(val wire: String, val toolNames: Set<String>) {
    MEMORY(
        "memory",
        setOf("memory_tool"),
    ),
    SCHEDULE(
        "schedule",
        setOf(
            "schedule_job", "list_jobs", "delete_job", "pause_job", "resume_job",
            "trigger_job_now", "get_job_history",
            "workflow_create", "workflow_list", "workflow_get", "workflow_update",
            "workflow_delete", "workflow_set_enabled", "workflow_run",
        ),
    ),
    ANDROID(
        "android",
        setOf(
            "get_battery_status", "get_audio_info", "get_telephony_info", "get_wifi_info",
            "list_sensors", "read_sensor", "get_storage_info", "show_toast", "post_notification",
            "share", "set_torch", "vibrate", "get_brightness", "set_brightness", "get_volume",
            "set_volume", "play_media", "stop_media", "pause_media", "resume_media", "seek_media",
            "get_media_status", "scan_media", "download_file", "write_text_file", "get_location",
            "search_contacts", "list_contacts", "list_call_log", "list_sms_inbox", "search_sms",
            "send_sms", "take_photo", "record_audio", "speech_to_text", "verify_fingerprint",
            "shizuku_exec", "tap", "long_press", "swipe", "read_window_tree", "find_node",
            "click_node", "set_text", "scroll", "global_action", "take_screenshot", "wake_screen",
            "launch_app", "list_installed_apps", "list_app_activities", "launch_activity",
            "open_url", "termux_run_command", "termux_session_start", "termux_session_send",
            "termux_session_read", "termux_session_kill", "termux_session_list",
            "transcribe_audio_file", "whisper_status", "list_recent_notifications",
            "list_active_notifications", "dismiss_notification", "notification_action_click",
            "notification_reply", "notification_status", "list_files", "read_file",
            "write_binary_file", "delete_file", "move_file", "copy_file", "create_directory",
            "file_info", "find_files", "show_image", "open_file", "batch_copy", "batch_move",
            "batch_delete", "set_wallpaper", "nfc_read_tag", "nfc_write_tag",
            "list_storage_volumes", "list_granted_directories", "grant_directory_access",
            "zip_files", "unzip_file", "list_zip_contents", "keyboard_type", "keyboard_read_field",
            "keyboard_press_key", "keyboard_delete", "keyboard_clear", "keyboard_editor_info",
            "keyboard_set_cursor", "keyboard_select_range", "create_calendar_event",
            "create_contact", "send_email_intent", "send_sms_intent", "open_wifi_settings",
            "show_location_on_map", "chart_display",
        ),
    ),
    SSH(
        "ssh",
        setOf(
            "ssh_exec", "save_ssh_host", "list_ssh_hosts", "delete_ssh_host",
            "ssh_forget_host_key", "ssh_exec_saved", "ssh_upload", "ssh_download",
        ),
    ),
    SUBAGENT(
        "subagent",
        setOf("subagent_dispatch", "subagent_list", "subagent_get", "subagent_cancel"),
    ),
    KEYSTORE(
        "keystore",
        setOf(
            "keystore_generate_key", "keystore_sign", "keystore_verify", "keystore_encrypt",
            "keystore_decrypt", "keystore_delete_key", "keystore_list_keys",
        ),
    );

    companion object {
        fun fromWire(raw: String): SubAgentCapability? {
            val key = raw.trim().lowercase()
            return entries.firstOrNull { it.wire == key }
        }
    }
}

/**
 * The resolved tool allow-list for one sub-agent run. workspace_ tools are always allowed.
 * Everything else must be a named role tool or belong to a granted capability.
 */
class SubAgentToolPolicy(private val allowedNames: Set<String>) {
    fun allows(toolName: String): Boolean = isWorkspaceToolName(toolName) || toolName in allowedNames
}

sealed class SubAgentPolicyBuild {
    data class Ok(val policy: SubAgentToolPolicy) : SubAgentPolicyBuild()
    data class UnknownCapability(val names: List<String>) : SubAgentPolicyBuild()
}

/**
 * Builds the policy from explicit role tools (profile tools plus dispatch `tools`, unioned)
 * and opt-in capability wire names. Blank entries are ignored. Unknown capability names fail
 * the build rather than being silently dropped, so a typo cannot quietly widen or narrow access.
 */
fun buildSubAgentToolPolicy(
    roleTools: Collection<String>,
    capabilities: Collection<String>,
): SubAgentPolicyBuild {
    val requested = capabilities.map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = requested.filter { SubAgentCapability.fromWire(it) == null }
    if (unknown.isNotEmpty()) return SubAgentPolicyBuild.UnknownCapability(unknown)

    val names = roleTools.map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    requested.forEach { wire ->
        SubAgentCapability.fromWire(wire)?.let { names += it.toolNames }
    }
    return SubAgentPolicyBuild.Ok(SubAgentToolPolicy(names))
}

/**
 * Process-scoped map from a sub-agent's conversation id to its policy. [SubAgentEngine]
 * registers the policy before the run starts and removes it when the run ends. [GenerationLoop]
 * and the tool rerun path consult it, so restrictions apply to every tool the model can see or
 * execute. Conversations with no registered policy (ordinary chats) are not restricted.
 */
@OptIn(ExperimentalUuidApi::class)
object SubAgentToolPolicies {
    private val byConversation = ConcurrentHashMap<Uuid, SubAgentToolPolicy>()

    fun register(conversationId: Uuid, policy: SubAgentToolPolicy) {
        byConversation[conversationId] = policy
    }

    fun unregister(conversationId: Uuid) {
        byConversation.remove(conversationId)
    }

    /** True when [toolName] may be offered to or executed in [conversationId]. */
    fun permits(conversationId: Uuid?, toolName: String): Boolean {
        if (conversationId == null) return true
        val policy = byConversation[conversationId] ?: return true
        return policy.allows(toolName)
    }

    fun <T> filter(conversationId: Uuid?, items: List<T>, nameOf: (T) -> String): List<T> =
        items.filter { permits(conversationId, nameOf(it)) }
}
