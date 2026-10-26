package me.rerere.rikkahub.subagent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "SubAgentEngine"
private const val MAX_RECURSION_DEPTH = 3

/**
 * Turn a wait-for-completion outcome into a stop decision, stopping the still-running
 * generation via [stop] when the wait timed out. Returns true on timeout, false on
 * natural completion. The generation itself is NOT cancelled by withTimeoutOrNull — that
 * only abandons the wait, leaving the LLM call running in ChatService's own session job.
 * Left uncalled, a timed-out sub-agent keeps burning tokens (and, if it later succeeds,
 * races a duplicate parallel run against whatever the parent does next). [stop] is
 * responsible for its own failure handling (see the runCatching wrapper around
 * chatService.stopGeneration at the call site in [SubAgentEngine.executeRun]) — kept out
 * of this pure function so it stays testable without touching android.util.Log, which
 * isn't mocked in this module's plain-JVM unit tests. Split out the same way
 * CronJobWorker.finishRunLlm is, so a JVM test can pin the stop-on-timeout contract
 * without a live ChatService.
 */
internal suspend fun finishSubAgentWait(completed: Boolean, stop: suspend () -> Unit): Boolean {
    if (!completed) {
        stop()
        return true
    }
    return false
}

private val ProviderSetting.providerTypeName: String
    get() = when (this) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
        is ProviderSetting.ChatGPT -> "chatgpt"
        is ProviderSetting.Grok -> "grok"
        is ProviderSetting.AICore -> "aicore"
        is ProviderSetting.LiteRtLocal -> "local_litert"
        is ProviderSetting.LlamaCppLocal -> "local_llamacpp"
        is ProviderSetting.Codex -> "codex"
        is ProviderSetting.GeminiOAuth -> "gemini_oauth"
    }

private val ProviderSetting.endpointUrl: String
    get() = when (this) {
        is ProviderSetting.ApiKey -> baseUrl
        is ProviderSetting.Account -> credential?.accountId ?: "oauth"
        else -> "local"
    }

/**
 * Resolves subagent_dispatch's `model_id` (uuid, provider model id, or display name) against
 * the CHAT-type models of ENABLED providers. #28: `model_id` was parsed, stored and
 * echoed back but never used to pick a model - the sub-agent silently inherited the parent's.
 * That silent fallback is the bug; this resolver fails loudly instead.
 */
internal object SubAgentModelResolver {
    sealed class Result {
        data object Inherit : Result()
        data class Resolved(
            val modelId: Uuid,
            val modelSlug: String = "",
            val displayName: String = "",
            val providerName: String = "",
            val providerType: String = "",
            val endpoint: String? = null,
        ) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * [modelIdInput] null/blank -> [Result.Inherit] (today's behavior unchanged). Otherwise
     * tried in order - uuid exact match, then case-insensitive exact match on [Model.modelId],
     * then case-insensitive exact match on [Model.displayName] - stopping at the first step
     * with any match. Exactly one match at a step resolves; more than one is ambiguous;
     * falling through all three with nothing is unknown. Both failure cases list the
     * candidates as "displayName (providerName) -> uuid" so the caller can retry unambiguously.
     */
    fun resolve(modelIdInput: String?, providers: List<ProviderSetting>): Result {
        if (modelIdInput.isNullOrBlank()) return Result.Inherit

        val chatModels: List<Pair<ProviderSetting, Model>> = providers
            .filter { it.enabled }
            .flatMap { provider -> provider.models.filter { it.type == ModelType.CHAT }.map { provider to it } }

        val asUuid = runCatching { Uuid.parse(modelIdInput) }.getOrNull()
        if (asUuid != null) {
            chatModels.firstOrNull { (_, model) -> model.id == asUuid }
                ?.let { (provider, model) ->
                    return Result.Resolved(
                        modelId = model.id,
                        modelSlug = model.modelId,
                        displayName = model.displayName,
                        providerName = provider.name,
                        providerType = provider.providerTypeName,
                        endpoint = provider.endpointUrl,
                    )
                }
        }

        val byModelId = chatModels.filter { (_, model) -> model.modelId.equals(modelIdInput, ignoreCase = true) }
        if (byModelId.size == 1) {
            val (provider, model) = byModelId[0]
            return Result.Resolved(
                modelId = model.id,
                modelSlug = model.modelId,
                displayName = model.displayName,
                providerName = provider.name,
                providerType = provider.providerTypeName,
                endpoint = provider.endpointUrl,
            )
        }
        if (byModelId.size > 1) return Result.Failed(ambiguousMessage(modelIdInput, byModelId))

        val byDisplayName = chatModels.filter { (_, model) -> model.displayName.equals(modelIdInput, ignoreCase = true) }
        if (byDisplayName.size == 1) {
            val (provider, model) = byDisplayName[0]
            return Result.Resolved(
                modelId = model.id,
                modelSlug = model.modelId,
                displayName = model.displayName,
                providerName = provider.name,
                providerType = provider.providerTypeName,
                endpoint = provider.endpointUrl,
            )
        }
        if (byDisplayName.size > 1) return Result.Failed(ambiguousMessage(modelIdInput, byDisplayName))

        return Result.Failed(unknownMessage(modelIdInput, chatModels))
    }

    fun resolveProfileModel(profile: SubAgentProfile, providers: List<ProviderSetting>): Result {
        val chatModels: List<Pair<ProviderSetting, Model>> = providers
            .filter { it.enabled }
            .flatMap { provider -> provider.models.filter { it.type == ModelType.CHAT }.map { provider to it } }

        if (profile.modelId != null) {
            val byUuid = chatModels.firstOrNull { (_, model) -> model.id == profile.modelId }
            if (byUuid != null) {
                val (provider, model) = byUuid
                return Result.Resolved(
                    modelId = model.id,
                    modelSlug = model.modelId,
                    displayName = model.displayName,
                    providerName = provider.name,
                    providerType = provider.providerTypeName,
                    endpoint = provider.endpointUrl,
                )
            }
        }
        if (!profile.modelSlug.isNullOrBlank()) {
            val bySlug = chatModels.firstOrNull { (_, model) -> model.modelId.equals(profile.modelSlug, ignoreCase = true) }
            if (bySlug != null) {
                val (provider, model) = bySlug
                return Result.Resolved(
                    modelId = model.id,
                    modelSlug = model.modelId,
                    displayName = model.displayName,
                    providerName = provider.name,
                    providerType = provider.providerTypeName,
                    endpoint = provider.endpointUrl,
                )
            }
        }
        return Result.Failed(
            "Sub-agent profile '${profile.name}' configured model (${profile.modelSlug ?: profile.modelId}) " +
                "is not available or its provider is disabled. Refusing silent fallback."
        )
    }

    private fun candidateLine(candidate: Pair<ProviderSetting, Model>): String {
        val (provider, model) = candidate
        return "${model.displayName} (${provider.name}) -> ${model.id}"
    }

    private fun ambiguousMessage(input: String, matches: List<Pair<ProviderSetting, Model>>): String =
        "model_id \"$input\" matches multiple models - retry with one of these uuids:\n" +
            matches.joinToString("\n") { candidateLine(it) }

    private fun unknownMessage(input: String, available: List<Pair<ProviderSetting, Model>>): String =
        if (available.isEmpty()) {
            "model_id \"$input\" did not match any model, and no chat models are available from enabled providers"
        } else {
            "model_id \"$input\" did not match any model. Available models:\n" +
                available.joinToString("\n") { candidateLine(it) }
        }
}

/**
 * #36: resolves subagent_dispatch's `agent` (a [SubAgentProfile] name) against the
 * user's configured profiles. Mirrors [SubAgentModelResolver]'s contract: no input is not an
 * error (nothing was requested), an unknown or disabled name fails loudly with the list of
 * valid names rather than silently falling back to the parent's model - the same
 * silent-inheritance bug `model_id` had before #28 fixed it.
 */
internal object SubAgentProfileResolver {
    sealed class Result {
        data object NotRequested : Result()
        data class Resolved(val profile: SubAgentProfile) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Profiles eligible for dispatch or for listing in `subagent_dispatch`'s description - a
     * disabled profile is neither resolvable nor discoverable, so it behaves exactly as if it
     * didn't exist. Shared by [resolve] and the tool description built in SubAgentTools.kt so
     * there's a single definition of "eligible" to test.
     */
    fun enabledProfiles(profiles: List<SubAgentProfile>): List<SubAgentProfile> =
        profiles.filter { it.enabled }

    /**
     * [agentName] null/blank -> [Result.NotRequested]. Otherwise matched case-insensitively by
     * [SubAgentProfile.name] against only the ENABLED profiles. More than one enabled profile
     * sharing a name (case-insensitively) is ambiguous and fails loudly naming the duplicate,
     * mirroring [SubAgentModelResolver]'s ambiguous-`model_id` handling - a sub-agent must never
     * silently run on whichever of two same-named profiles happened to come first.
     */
    fun resolve(agentName: String?, profiles: List<SubAgentProfile>): Result {
        if (agentName.isNullOrBlank()) return Result.NotRequested

        val enabled = enabledProfiles(profiles)
        val matches = enabled.filter { it.name.equals(agentName, ignoreCase = true) }
        if (matches.size > 1) {
            return Result.Failed(
                "agent \"$agentName\" matches multiple sub-agent profiles - rename one of these " +
                    "duplicates: " + matches.joinToString(", ") { it.name }
            )
        }
        val match = matches.firstOrNull()
        if (match != null) return Result.Resolved(match)

        return Result.Failed(
            if (enabled.isEmpty()) {
                "agent \"$agentName\" did not match any sub-agent profile, and no profiles are configured"
            } else {
                "agent \"$agentName\" did not match any enabled sub-agent profile. Available: " +
                    enabled.joinToString(", ") { it.name }
            }
        )
    }
}

/**
 * #36: combines `model_id`'s resolution with an `agent` profile's model - `model_id`
 * always wins when it resolved to something (or failed - a bad explicit model_id must surface,
 * not be papered over by falling back to the profile's model). Only when `model_id` was never
 * given ([SubAgentModelResolver.Result.Inherit]) does the profile's model get a chance, and only
 * if [profile] is non-null and its `modelId` is set - otherwise this is a no-op, exactly
 * preserving the behavior before named sub-agent profiles when no agent was requested. Split
 * out as a pure function (same rationale as [finishSubAgentWait]) so the precedence rule is
 * unit-testable without a live [SubAgentEngine].
 */
internal fun resolveSubAgentModel(
    modelResolution: SubAgentModelResolver.Result,
    profile: SubAgentProfile?,
    providers: List<ProviderSetting>? = null,
): SubAgentModelResolver.Result = when (modelResolution) {
    is SubAgentModelResolver.Result.Inherit -> {
        if (profile != null && (profile.modelId != null || !profile.modelSlug.isNullOrBlank())) {
            if (providers != null) {
                SubAgentModelResolver.resolveProfileModel(profile, providers)
            } else {
                profile.modelId?.let { SubAgentModelResolver.Result.Resolved(it) } ?: modelResolution
            }
        } else {
            modelResolution
        }
    }
    else -> modelResolution
}

/**
 * Phase 11 — sub-agent dispatch engine.
 *
 * The engine reuses the existing cron-headless dispatch pattern (mark conv headless,
 * sendMessage, await generation flow's terminal state). It deliberately does NOT
 * re-implement [me.rerere.rikkahub.data.ai.GenerationHandler] — that path is already
 * battle-tested and any duplicate would diverge.
 *
 * Recursion guard: SubAgentEngine permits sub-agent recursion only when the calling
 * conversation has been explicitly granted the `Recursive Sub-Agents` ("subagent")
 * capability, capped at a maximum nesting depth of [MAX_RECURSION_DEPTH].
 *
 * Concurrency caps:
 *  - Per-assistant cap from [me.rerere.rikkahub.data.model.Assistant.maxConcurrentSubAgents]
 *  - Global cap from [SubAgentDefaults.GLOBAL_CONCURRENCY_CAP]
 *  - Both enforced at dispatch entry — over-cap requests fail fast (background) or block
 *    up to 30s waiting for a slot before failing (foreground, per spec).
 */
class SubAgentEngine(
    private val registry: SubAgentRegistry,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    /**
     * Phase 24 — unified AgentRun ledger writer. The [SubAgentRegistry] is in-memory only,
     * so a backgrounded sub-agent does NOT survive process death — its registry entry is
     * gone on restart. Writing each sub-agent run to the persistent ledger closes that gap:
     * a run left `running` when the process dies is flipped to `process_lost` by
     * [me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery] on next start, so the user
     * (and `subagent_get`, via the ledger) can see what actually happened.
     */
    private val agentRunRepo: AgentRunRepository,
) {

    private val chatService: ChatService by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<ChatService>()
    }

    private val ledgerIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val recent = agentRunRepo.getRecent(50).filter { it.kind == AgentRunKind.SubAgent.wire }
                val subRuns = recent.mapNotNull { it.toSubAgentRun() }
                registry.rehydrate(subRuns)
            }
        }
    }

    sealed class DispatchResult {
        data class Ok(val run: SubAgentRun) : DispatchResult()
        data class Reject(val error: String, val detail: String) : DispatchResult()
    }

    private fun getRecursionDepth(parentChatId: String): Int {
        var depth = 1
        var currParent: String? = parentChatId
        while (currParent != null && depth < 10) {
            val parentRun = registry.runs.value.values.firstOrNull { it.conversationId == currParent || it.id == currParent }
            currParent = parentRun?.parentChatId
            if (currParent != null) depth++
        }
        return depth
    }

    /**
     * Dispatch a sub-agent. For foreground runs, blocks until terminal status; for
     * background, returns immediately with a PENDING-then-RUNNING run that the caller
     * can poll via subagent_get.
     */
    suspend fun dispatch(
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ): DispatchResult = withContext(Dispatchers.Default) {
        // Recursion guard: if the caller is in a headless context already, check Recursive Sub-Agents capability
        if (parentChatId != null) {
            val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull()
            if (parentUuid != null && HeadlessConversations.isHeadless(parentUuid)) {
                val permitsSubagent = SubAgentToolPolicies.permits(parentUuid, "subagent_dispatch")
                if (!permitsSubagent) {
                    return@withContext DispatchResult.Reject(
                        "no_recursion",
                        "sub-agent dispatch is not allowed from inside another headless run unless the Recursive Sub-Agents ('subagent') capability is granted."
                    )
                }
                val depth = getRecursionDepth(parentChatId)
                if (depth >= MAX_RECURSION_DEPTH) {
                    return@withContext DispatchResult.Reject(
                        "max_recursion_depth",
                        "sub-agent reached max nesting depth of $MAX_RECURSION_DEPTH"
                    )
                }
            }
        }
        val validation = SubAgentRequestValidator.validate(request)
        if (validation is SubAgentRequestValidator.Result.Reject) {
            return@withContext DispatchResult.Reject(validation.error, validation.detail)
        }
        val cleaned = (validation as SubAgentRequestValidator.Result.Ok).request

        // Concurrency cap. Global first (cheaper), then per-assistant.
        if (registry.globalActiveCount() >= SubAgentDefaults.GLOBAL_CONCURRENCY_CAP) {
            return@withContext DispatchResult.Reject(
                "global_cap_reached",
                "max ${SubAgentDefaults.GLOBAL_CONCURRENCY_CAP} concurrent sub-agents across all assistants"
            )
        }
        val perAssistantCap = currentAssistantCap(parentAssistantId)
        if (registry.activeCountForAssistant(parentAssistantId) >= perAssistantCap) {
            val waitStart = System.currentTimeMillis()
            var acquired = false
            while (System.currentTimeMillis() - waitStart < 30_000L) {
                delay(1000L)
                if (registry.activeCountForAssistant(parentAssistantId) < perAssistantCap) {
                    acquired = true
                    break
                }
            }
            if (!acquired) {
                val activeRuns = registry.all().values.filter {
                    it.parentAssistantId == parentAssistantId &&
                        (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING)
                }
                val activeSummary = activeRuns.joinToString { r ->
                    val elapsed = (System.currentTimeMillis() - r.startedAtMs) / 1000
                    "${r.id} (${r.label.take(20)}, ${elapsed}s)"
                }
                return@withContext DispatchResult.Reject(
                    "assistant_cap_reached",
                    "Cap of $perAssistantCap active sub-agents reached after 30s wait. Active runs: [$activeSummary]. Retry when one completes."
                )
            }
        }

        val runId = Uuid.random().toString()
        val convId = Uuid.random()
        val now = System.currentTimeMillis()
        val initialRun = SubAgentRun(
            id = runId,
            conversationId = convId.toString(),
            parentChatId = parentChatId,
            parentAssistantId = parentAssistantId,
            label = cleaned.label?.takeIf { it.isNotBlank() } ?: cleaned.task.take(60),
            task = cleaned.task,
            modelId = cleaned.modelId,
            tools = cleaned.tools,
            runInBackground = cleaned.runInBackground,
            noResult = cleaned.noResult,
            timeoutSeconds = cleaned.timeoutSeconds,
            maxTrips = cleaned.maxTrips,
            status = SubAgentStatus.PENDING,
            startedAtMs = now,
            capabilities = cleaned.capabilities.orEmpty(),
        )
        registry.addPending(initialRun)

        // Phase 24 — open the cross-pillar ledger row. domain_id is the sub-agent run id.
        val ledgerId = agentRunRepo.open(
            kind = AgentRunKind.SubAgent,
            domainId = runId,
            parentRunId = parentChatId,
            status = AgentRunStatus.queued,
            metadata = buildJsonObject {
                put("label", initialRun.label)
                put("parent_assistant_id", parentAssistantId)
                put("run_in_background", cleaned.runInBackground)
                put("conversation_id", convId.toString())
            },
        )
        ledgerIds[runId] = ledgerId

        val executionJob = appScope.launch(Dispatchers.IO) {
            executeRun(runId, convId, parentAssistantId, parentChatId, cleaned)
        }
        registry.setJob(runId, executionJob)

        if (cleaned.runInBackground) {
            // Return immediately; final status delivered via registry observation.
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        } else {
            // Foreground — block until terminal.
            try {
                executionJob.join()
            } catch (t: Throwable) {
                Log.w(TAG, "foreground sub-agent join failed for $runId", t)
            }
            DispatchResult.Ok(registry.get(runId) ?: initialRun)
        }
    }

    private suspend fun currentAssistantCap(parentAssistantId: String): Int {
        val asstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull() ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        val settings = settingsStore.settingsFlow.first()
        val asst = settings.assistants.firstOrNull { it.id == asstUuid }
            ?: return SubAgentDefaults.MAX_PER_ASSISTANT_CAP
        return asst.maxConcurrentSubAgents.coerceIn(
            SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
            SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
        )
    }

    private suspend fun executeRun(
        runId: String,
        convId: Uuid,
        parentAssistantId: String,
        parentChatId: String?,
        request: SubAgentRequest,
    ) {
        registry.update(runId) { it.copy(status = SubAgentStatus.RUNNING) }
        ledgerIds[runId]?.let { agentRunRepo.setStatus(it, AgentRunStatus.running) }

        val parentAsstUuid = runCatching { Uuid.parse(parentAssistantId) }.getOrNull()
            ?: run {
                markTerminal(runId, SubAgentStatus.FAILED, "bad parent assistant id")
                return
            }
        val settings = settingsStore.settingsFlow.first()
        val profileResolution = SubAgentProfileResolver.resolve(request.agentName, settings.subAgents)
        val profile = when (profileResolution) {
            is SubAgentProfileResolver.Result.NotRequested -> null
            is SubAgentProfileResolver.Result.Resolved -> profileResolution.profile
            is SubAgentProfileResolver.Result.Failed -> {
                markTerminal(runId, SubAgentStatus.FAILED, profileResolution.message)
                return
            }
        }
        val modelResolution = resolveSubAgentModel(
            SubAgentModelResolver.resolve(request.modelId, settings.providers),
            profile,
            settings.providers,
        )
        val resolvedChatModelId = when (modelResolution) {
            is SubAgentModelResolver.Result.Inherit -> null
            is SubAgentModelResolver.Result.Resolved -> {
                registry.update(runId) {
                    it.copy(
                        resolvedModelId = modelResolution.modelId.toString(),
                        resolvedModelSlug = modelResolution.modelSlug,
                        providerName = modelResolution.providerName,
                        providerType = modelResolution.providerType,
                        endpoint = modelResolution.endpoint,
                    )
                }
                modelResolution.modelId
            }
            is SubAgentModelResolver.Result.Failed -> {
                markTerminal(runId, SubAgentStatus.FAILED, modelResolution.message)
                return
            }
        }
        val profilePrompt = profile?.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        val dispatchPrompt = request.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
        val combinedPrompt = buildList {
            if (profilePrompt != null) add(profilePrompt)
            if (dispatchPrompt != null) add(dispatchPrompt)
        }.takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n\n") ?: DEFAULT_SUBAGENT_SYSTEM_PROMPT

        val identityBanner = if (profile != null) {
            "You are \"${profile.name}\" (sub-agent profile), running as model ${modelResolution.let { if (it is SubAgentModelResolver.Result.Resolved) it.displayName.ifBlank { it.modelSlug } else "inherited" }} via provider ${modelResolution.let { if (it is SubAgentModelResolver.Result.Resolved) it.providerName else "inherited" }}."
        } else if (modelResolution is SubAgentModelResolver.Result.Resolved) {
            "You are an ad-hoc sub-agent, running as model ${modelResolution.displayName.ifBlank { modelResolution.modelSlug }} via provider ${modelResolution.providerName}."
        } else {
            "You are a focused sub-agent dispatched by a parent assistant."
        }
        val subAgentSystemPrompt = "$identityBanner\n\n$combinedPrompt"

        val parentConv = parentChatId?.let { runCatching { Uuid.parse(it) }.getOrNull() }?.let { conversationRepo.getConversationById(it) }
        val conv = Conversation.ofId(
            id = convId,
            assistantId = parentAsstUuid,
            newConversation = true,
        ).copy(
            title = "[Sub-agent] ${request.label?.take(40) ?: request.task.take(40)}",
            chatModelId = resolvedChatModelId,
            customSystemPrompt = subAgentSystemPrompt,
            workspaceCwd = parentConv?.workspaceCwd,
        )
        val toolPolicy = when (val built = buildSubAgentToolPolicy(
            roleTools = request.tools.orEmpty() + profile?.tools.orEmpty(),
            capabilities = request.capabilities.orEmpty() + profile?.capabilities.orEmpty(),
        )) {
            is SubAgentPolicyBuild.Ok -> built.policy
            is SubAgentPolicyBuild.UnknownCapability -> {
                markTerminal(runId, SubAgentStatus.FAILED, "unknown capability: ${built.names.joinToString()}")
                return
            }
        }
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        try {
            val taskWithWrapup = buildString {
                append(request.task)
                appendLine()
                appendLine()
                append("When you have finished, end with one short paragraph in plain text that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            SubAgentToolPolicies.register(conv.id, toolPolicy)
            val reply = CompletableDeferred<String?>()
            chatService.sendMessage(
                conversationId = conv.id,
                content = listOf(UIMessagePart.Text(taskWithWrapup)),
                reply = reply,
            )
            val waitResult: String? = withTimeoutOrNull(request.timeoutSeconds * 1000L) {
                runCatching { reply.await() }.getOrNull()
            }
            val completed = waitResult != null || reply.isCompleted
            val timedOut = finishSubAgentWait(completed = completed) {
                runCatching { chatService.stopGeneration(conv.id) }
                    .onFailure { Log.w(TAG, "sub-agent timeout: stopGeneration failed for $runId", it) }
            }
            if (timedOut) {
                markTerminal(runId, SubAgentStatus.TIMED_OUT, "exceeded ${request.timeoutSeconds}-second cap")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            val finalText = waitResult?.takeIf { it.isNotBlank() } ?: harvestFinalText(conv.id)
            if (finalText.isNullOrBlank()) {
                markTerminal(runId, SubAgentStatus.FAILED, "empty_result: sub-agent finished with no response or progress")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            registry.update(runId) {
                it.copy(
                    status = SubAgentStatus.SUCCEEDED,
                    result = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
            ledgerIds.remove(runId)?.let {
                agentRunRepo.markTerminal(it, AgentRunStatus.succeeded)
            }
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } catch (t: Throwable) {
            Log.w(TAG, "sub-agent run failed", t)
            val terminal = if (t is kotlinx.coroutines.CancellationException) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            markTerminal(runId, terminal, "${t::class.simpleName}: ${t.message.orEmpty()}")
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } finally {
            SubAgentToolPolicies.unregister(conv.id)
            HeadlessConversations.unmark(conv.id)
            registry.clearJob(runId)
        }
    }

    /**
     * Reinject a prompt into an existing sub-agent run that died, failed, timed out, or was
     * cancelled, allowing it to continue with its full prior context, tool call history, and findings.
     */
    suspend fun reinject(
        runId: String,
        prompt: String,
        additionalCapabilities: List<String>? = null,
        runInBackground: Boolean? = null,
        timeoutSeconds: Int? = null,
    ): DispatchResult = withContext(Dispatchers.Default) {
        val existingRun = registry.get(runId)
            ?: return@withContext DispatchResult.Reject(
                "unknown_run",
                "no sub-agent run with id $runId found in active registry"
            )

        val convIdStr = existingRun.conversationId
            ?: return@withContext DispatchResult.Reject(
                "no_conversation",
                "run $runId has no associated conversation to continue"
            )

        val convUuid = runCatching { Uuid.parse(convIdStr) }.getOrNull()
            ?: return@withContext DispatchResult.Reject(
                "invalid_conversation_id",
                "conversation id $convIdStr is invalid"
            )

        conversationRepo.getConversationById(convUuid)
            ?: return@withContext DispatchResult.Reject(
                "conversation_not_found",
                "conversation $convIdStr was not found in storage"
            )

        // Cancel previous job if still hanging
        if (existingRun.status == SubAgentStatus.RUNNING) {
            registry.requestCancel(runId)
        }

        val isBackground = runInBackground ?: existingRun.runInBackground
        val timeout = timeoutSeconds ?: existingRun.timeoutSeconds
        val effectiveCaps = (existingRun.capabilities + (additionalCapabilities.orEmpty())).distinct()

        val toolPolicy = when (val built = buildSubAgentToolPolicy(
            roleTools = existingRun.tools.orEmpty(),
            capabilities = effectiveCaps,
        )) {
            is SubAgentPolicyBuild.Ok -> built.policy
            is SubAgentPolicyBuild.UnknownCapability -> {
                return@withContext DispatchResult.Reject(
                    "unknown_capability",
                    "unknown capability: ${built.names.joinToString()}"
                )
            }
        }

        val resumedAfter = System.currentTimeMillis() - existingRun.startedAtMs
        registry.update(runId) {
            it.copy(
                status = SubAgentStatus.RUNNING,
                error = null,
                result = null,
                finishedAtMs = null,
                startedAtMs = System.currentTimeMillis(),
                runInBackground = isBackground,
                timeoutSeconds = timeout,
                capabilities = effectiveCaps,
                resumedAfterMs = resumedAfter,
            )
        }
        ledgerIds[runId]?.let { agentRunRepo.setStatus(it, AgentRunStatus.running) }

        val executionJob = appScope.launch(Dispatchers.IO) {
            executeReinject(runId, convUuid, existingRun.parentChatId, prompt, timeout, toolPolicy)
        }
        registry.setJob(runId, executionJob)

        if (isBackground) {
            DispatchResult.Ok(registry.get(runId) ?: existingRun)
        } else {
            try {
                executionJob.join()
            } catch (t: Throwable) {
                Log.w(TAG, "reinject sub-agent join failed for $runId", t)
            }
            DispatchResult.Ok(registry.get(runId) ?: existingRun)
        }
    }

    private suspend fun executeReinject(
        runId: String,
        convId: Uuid,
        parentChatId: String?,
        prompt: String,
        timeoutSeconds: Int,
        toolPolicy: SubAgentToolPolicy,
    ) {
        HeadlessConversations.mark(convId)
        try {
            val promptWithWrapup = buildString {
                append(prompt)
                appendLine()
                appendLine()
                append("When you have finished, end with one short paragraph in plain text that summarises what you did and what you found. Do NOT stop on a tool call — finish with assistant text. The dispatcher harvests only your final text reply, so this paragraph is the entire response the parent sees.")
            }
            SubAgentToolPolicies.register(convId, toolPolicy)
            val reply = CompletableDeferred<String?>()
            chatService.sendMessage(
                conversationId = convId,
                content = listOf(UIMessagePart.Text(promptWithWrapup)),
                reply = reply,
            )
            val waitResult: String? = withTimeoutOrNull(timeoutSeconds * 1000L) {
                runCatching { reply.await() }.getOrNull()
            }
            val completed = waitResult != null || reply.isCompleted
            val timedOut = finishSubAgentWait(completed = completed) {
                runCatching { chatService.stopGeneration(convId) }
                    .onFailure { Log.w(TAG, "sub-agent reinject timeout: stopGeneration failed for $runId", it) }
            }
            if (timedOut) {
                markTerminal(runId, SubAgentStatus.TIMED_OUT, "exceeded $timeoutSeconds-second cap on reinject")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            val finalText = waitResult?.takeIf { it.isNotBlank() } ?: harvestFinalText(convId)
            if (finalText.isNullOrBlank()) {
                markTerminal(runId, SubAgentStatus.FAILED, "empty_result: sub-agent reinject finished with no response or progress")
                notifyParentIfBackground(parentChatId, registry.get(runId))
                return
            }
            registry.update(runId) {
                it.copy(
                    status = SubAgentStatus.SUCCEEDED,
                    result = finalText,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
            ledgerIds.remove(runId)?.let {
                agentRunRepo.markTerminal(it, AgentRunStatus.succeeded)
            }
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } catch (t: Throwable) {
            Log.w(TAG, "sub-agent reinject failed", t)
            val terminal = if (t is kotlinx.coroutines.CancellationException) SubAgentStatus.CANCELLED else SubAgentStatus.FAILED
            markTerminal(runId, terminal, "${t::class.simpleName}: ${t.message.orEmpty()}")
            notifyParentIfBackground(parentChatId, registry.get(runId))
        } finally {
            SubAgentToolPolicies.unregister(convId)
            HeadlessConversations.unmark(convId)
            registry.clearJob(runId)
        }
    }

    private suspend fun markTerminal(runId: String, status: SubAgentStatus, error: String?) {
        registry.update(runId) {
            it.copy(
                status = status,
                error = error,
                finishedAtMs = System.currentTimeMillis(),
            )
        }
        ledgerIds.remove(runId)?.let { ledgerId ->
            val ledgerStatus = when (status) {
                SubAgentStatus.CANCELLED -> AgentRunStatus.cancelled
                SubAgentStatus.SUCCEEDED -> AgentRunStatus.succeeded
                else -> AgentRunStatus.failed
            }
            agentRunRepo.markTerminal(ledgerId, ledgerStatus, error)
        }
    }

    private suspend fun notifyParentIfBackground(parentChatId: String?, run: SubAgentRun?) {
        if (parentChatId == null || run == null || !run.runInBackground) return
        val parentUuid = runCatching { Uuid.parse(parentChatId) }.getOrNull() ?: return
        if (HeadlessConversations.isHeadless(parentUuid)) return

        val message = buildString {
            appendLine("[Sub-agent ${run.label} — ${run.status.name}]")
            appendLine("Run ID: ${run.id}")
            run.error?.takeIf { it.isNotBlank() }?.let {
                appendLine("Error: $it")
            }
            if (!run.noResult) {
                run.result?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    append(it)
                }
            }
            if (run.status != SubAgentStatus.SUCCEEDED) {
                appendLine()
                append("To continue or resume this sub-agent, call subagent_reinject(id=\"${run.id}\", prompt=\"...\").")
            }
        }.trimEnd()

        runCatching {
            withTimeoutOrNull(5 * 60_000L) {
                chatService.getGenerationJobStateFlow(parentUuid).first { it == null }
                Unit
            }
            chatService.sendMessage(parentUuid, listOf(UIMessagePart.Text(message)))
        }.onFailure {
            Log.w(TAG, "failed to notify parent $parentChatId of subagent completion", it)
        }
    }

    private suspend fun harvestFinalText(conversationId: Uuid): String {
        return runCatching {
            val conv = conversationRepo.getConversationById(conversationId) ?: return@runCatching ""
            val selectedMessages = conv.messageNodes.mapNotNull { node ->
                node.messages.getOrNull(node.selectIndex)
            }
            val assistantMessages = selectedMessages.filter { msg ->
                msg.role.name.equals("assistant", ignoreCase = true)
            }
            if (assistantMessages.isEmpty()) return@runCatching ""

            val lastTexts = assistantMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .trim()
            if (lastTexts.isNotBlank()) return@runCatching lastTexts

            assistantMessages
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
                .joinToString("\n") { it.text }
                .trim()
        }.getOrDefault("")
    }
}

private fun me.rerere.rikkahub.data.agentrun.AgentRun.toSubAgentRun(): SubAgentRun? {
    if (kind != AgentRunKind.SubAgent.wire) return null
    val meta = metadataJson?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }
    val statusEnum = when (AgentRunStatus.fromName(status)) {
        AgentRunStatus.queued -> SubAgentStatus.PENDING
        AgentRunStatus.running, AgentRunStatus.awaiting_approval -> SubAgentStatus.RUNNING
        AgentRunStatus.succeeded -> SubAgentStatus.SUCCEEDED
        AgentRunStatus.cancelled -> SubAgentStatus.CANCELLED
        AgentRunStatus.failed, AgentRunStatus.process_lost -> SubAgentStatus.FAILED
    }
    return SubAgentRun(
        id = domainId,
        parentChatId = meta?.get("parent_chat_id")?.jsonPrimitive?.contentOrNull,
        parentAssistantId = meta?.get("parent_assistant_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
        label = meta?.get("label")?.jsonPrimitive?.contentOrNull ?: "Sub-agent",
        task = meta?.get("task")?.jsonPrimitive?.contentOrNull.orEmpty(),
        modelId = meta?.get("model_id")?.jsonPrimitive?.contentOrNull,
        tools = null,
        resolvedModelSlug = meta?.get("resolved_model")?.jsonPrimitive?.contentOrNull,
        providerName = meta?.get("provider")?.jsonPrimitive?.contentOrNull,
        endpoint = meta?.get("endpoint")?.jsonPrimitive?.contentOrNull,
        runInBackground = meta?.get("run_in_background")?.jsonPrimitive?.booleanOrNull ?: false,
        timeoutSeconds = meta?.get("timeout_seconds")?.jsonPrimitive?.intOrNull ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = statusEnum,
        result = meta?.get("result")?.jsonPrimitive?.contentOrNull,
        error = lastError ?: meta?.get("error")?.jsonPrimitive?.contentOrNull,
        startedAtMs = startedAtMs ?: createdAtMs,
        finishedAtMs = finishedAtMs,
        conversationId = meta?.get("conversation_id")?.jsonPrimitive?.contentOrNull,
    )
}
