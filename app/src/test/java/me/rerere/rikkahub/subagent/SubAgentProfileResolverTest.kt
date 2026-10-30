package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #36: subagent_dispatch's `agent` parameter resolves a named [SubAgentProfile] by
 * name. Pins the resolver's contract: exact / case-insensitive match, loud failure (never
 * silent parent-model inheritance) on an unknown or disabled name, and that disabled profiles
 * are invisible to resolution - the same discipline [SubAgentModelResolver] applies to
 * `model_id`.
 */
class SubAgentProfileResolverTest {

    private val researcher = SubAgentProfile(
        name = "Researcher",
        description = "Looks things up.",
        systemPrompt = "You are a careful researcher.",
        enabled = true,
    )
    private val disabledProfile = SubAgentProfile(
        name = "Retired",
        description = "No longer used.",
        systemPrompt = "You are retired.",
        enabled = false,
    )
    private val profiles = listOf(researcher, disabledProfile)

    @Test fun `null agent is not requested`() {
        val result = SubAgentProfileResolver.resolve(null, profiles)
        assertTrue(result is SubAgentProfileResolver.Result.NotRequested)
    }

    @Test fun `blank agent is not requested`() {
        val result = SubAgentProfileResolver.resolve("   ", profiles)
        assertTrue(result is SubAgentProfileResolver.Result.NotRequested)
    }

    @Test fun `resolves by exact name`() {
        val result = SubAgentProfileResolver.resolve("Researcher", profiles)
        val resolved = result as SubAgentProfileResolver.Result.Resolved
        assertEquals(researcher.id, resolved.profile.id)
    }

    @Test fun `resolves case-insensitively`() {
        val result = SubAgentProfileResolver.resolve("rEsEaRcHeR", profiles)
        val resolved = result as SubAgentProfileResolver.Result.Resolved
        assertEquals(researcher.id, resolved.profile.id)
    }

    @Test fun `unknown name fails loudly and lists valid names`() {
        val result = SubAgentProfileResolver.resolve("no-such-agent", profiles)
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("no-such-agent"))
        assertTrue(failed.message.contains("Researcher"))
        // A silent fallback to the parent model would look like a Resolved/NotRequested
        // result here - it must not.
    }

    @Test fun `disabled profile is not resolvable and is absent from the error's valid names`() {
        val result = SubAgentProfileResolver.resolve("Retired", profiles)
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("Retired"))
        assertTrue(!failed.message.contains("Researcher") || failed.message.indexOf("Retired") >= 0)
        // Precisely: the available-names list must not include the disabled profile's name.
        val availableSection = failed.message.substringAfter("Available:", missingDelimiterValue = "")
        assertTrue(!availableSection.contains("Retired"))
    }

    @Test fun `ambiguous name fails loudly and names the duplicate`() {
        val secondResearcher = SubAgentProfile(
            name = "researcher",
            description = "A second profile with the same name, different case.",
            systemPrompt = "You are also a researcher.",
            enabled = true,
        )
        val result = SubAgentProfileResolver.resolve("Researcher", listOf(researcher, secondResearcher))
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("Researcher"))
        assertTrue(failed.message.contains("researcher"))
        // An ambiguous name must never quietly resolve to whichever profile came first.
    }

    @Test fun `empty profile list fails with a message noting none are configured`() {
        val result = SubAgentProfileResolver.resolve("anything", emptyList())
        val failed = result as SubAgentProfileResolver.Result.Failed
        assertTrue(failed.message.contains("no profiles are configured"))
    }

    @Test fun `enabledProfiles filters out disabled profiles`() {
        assertEquals(listOf(researcher), SubAgentProfileResolver.enabledProfiles(profiles))
    }

    // resolveSubAgentModel: the model_id vs agent precedence rule.

    @Test fun `resolveSubAgentModel - model_id wins over the profile's model when both resolve`() {
        val explicitModelId = Uuid.random()
        val profileModelId = Uuid.random()
        val profile = researcher.copy(modelId = profileModelId)

        val combined = resolveSubAgentModel(
            SubAgentModelResolver.Result.Resolved(explicitModelId),
            profile,
        )

        assertEquals(SubAgentModelResolver.Result.Resolved(explicitModelId), combined)
    }

    @Test fun `resolveSubAgentModel - a failed model_id is not papered over by the profile's model`() {
        val profile = researcher.copy(modelId = Uuid.random())
        val failure = SubAgentModelResolver.Result.Failed("model_id \"bogus\" did not match any model")

        val combined = resolveSubAgentModel(failure, profile)

        assertEquals(failure, combined)
    }

    @Test fun `resolveSubAgentModel - falls back to the profile's model when model_id was not given`() {
        val profileModelId = Uuid.random()
        val profile = researcher.copy(modelId = profileModelId)

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, profile)

        assertEquals(SubAgentModelResolver.Result.Resolved(profileModelId), combined)
    }

    @Test fun `resolveSubAgentModel - no agent and no model_id leaves the tool working exactly as before`() {
        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, profile = null)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }

    @Test fun `resolveSubAgentModel - a profile with no model set also inherits, exactly as before`() {
        val profileWithNoModel = researcher.copy(modelId = null)

        val combined = resolveSubAgentModel(SubAgentModelResolver.Result.Inherit, profileWithNoModel)

        assertTrue(combined is SubAgentModelResolver.Result.Inherit)
    }

    @Test fun `resolveProfileModel resolves by modelSlug case-insensitively`() {
        val model = me.rerere.ai.provider.Model(
            modelId = "gemini-3.8-flash-high",
            displayName = "Gemini 3.8 Flash High",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val provider = me.rerere.ai.provider.ProviderSetting.Google(
            name = "Google AI",
            enabled = true,
            models = listOf(model),
        )
        val profile = researcher.copy(modelSlug = "Gemini-3.8-Flash-High")
        val res = SubAgentModelResolver.resolveProfileModel(profile, listOf(provider))
        assertTrue(res is SubAgentModelResolver.Result.Resolved)
        val resolved = res as SubAgentModelResolver.Result.Resolved
        assertEquals(model.id, resolved.modelId)
        assertEquals("gemini-3.8-flash-high", resolved.modelSlug)
    }

    @Test fun `resolveProfileModel fails loudly when provider is disabled or model removed`() {
        val model = me.rerere.ai.provider.Model(
            modelId = "gemini-3.8-flash-high",
            displayName = "Gemini 3.8 Flash High",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val provider = me.rerere.ai.provider.ProviderSetting.Google(
            name = "Google AI",
            enabled = false,
            models = listOf(model),
        )
        val profile = researcher.copy(modelSlug = "gemini-3.8-flash-high")
        val res = SubAgentModelResolver.resolveProfileModel(profile, listOf(provider))
        assertTrue(res is SubAgentModelResolver.Result.Failed)
        val failed = res as SubAgentModelResolver.Result.Failed
        assertTrue(failed.message.contains("Refusing silent fallback"))
    }

    @Test fun `resolveProfileModel matches exact modelId first even when duplicate modelSlug exists in other providers`() {
        val modelZen = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerZen = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Opencode Zen",
            enabled = true,
            models = listOf(modelZen),
        )
        val modelKios = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerKios = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Kiosapi-FreePro",
            enabled = true,
            models = listOf(modelKios),
        )

        // Profile points to Kiosapi-FreePro modelId (UUID), but modelSlug is shared
        val profile = researcher.copy(
            modelId = modelKios.id,
            modelSlug = "glm-5.3-flash-free",
            providerId = providerKios.id,
        )

        val res = SubAgentModelResolver.resolveProfileModel(profile, listOf(providerZen, providerKios))
        assertTrue(res is SubAgentModelResolver.Result.Resolved)
        val resolved = res as SubAgentModelResolver.Result.Resolved
        assertEquals(modelKios.id, resolved.modelId)
        assertEquals("Kiosapi-FreePro", resolved.providerName)
    }

    @Test fun `resolveProfileModel falls back to providerId affinity when modelId UUID is stale after catalog refresh`() {
        val modelZen = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerZen = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Opencode Zen",
            enabled = true,
            models = listOf(modelZen),
        )
        val refreshedModelKios = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerKios = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Kiosapi-FreePro",
            enabled = true,
            models = listOf(refreshedModelKios),
        )

        // Stale modelId UUID that no longer matches, but providerId and modelSlug match
        val profile = researcher.copy(
            modelId = Uuid.random(),
            modelSlug = "glm-5.3-flash-free",
            providerId = providerKios.id,
        )

        val res = SubAgentModelResolver.resolveProfileModel(profile, listOf(providerZen, providerKios))
        assertTrue(res is SubAgentModelResolver.Result.Resolved)
        val resolved = res as SubAgentModelResolver.Result.Resolved
        assertEquals(refreshedModelKios.id, resolved.modelId)
        assertEquals("Kiosapi-FreePro", resolved.providerName)
    }

    @Test fun `resolveProfileModel fails loudly when modelSlug is ambiguous across multiple providers and no providerId or modelId matches`() {
        val modelZen = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash (Zen)",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerZen = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Opencode Zen",
            enabled = true,
            models = listOf(modelZen),
        )
        val modelKios = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash (Kios)",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerKios = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Kiosapi-FreePro",
            enabled = true,
            models = listOf(modelKios),
        )

        // Ambiguous: modelSlug matches both, but no modelId or providerId matches
        val profile = researcher.copy(
            modelId = null,
            modelSlug = "glm-5.3-flash-free",
            providerId = null,
        )

        val res = SubAgentModelResolver.resolveProfileModel(profile, listOf(providerZen, providerKios))
        assertTrue(res is SubAgentModelResolver.Result.Failed)
        val failed = res as SubAgentModelResolver.Result.Failed
        assertTrue(failed.message.contains("is ambiguous across multiple providers (2 matches)"))
        assertTrue(failed.message.contains("Opencode Zen"))
        assertTrue(failed.message.contains("Kiosapi-FreePro"))
    }

    @Test fun `resolveProfileModel succeeds with unambiguous unique modelSlug when providerId is null`() {
        val modelZen = me.rerere.ai.provider.Model(
            modelId = "glm-5.3-flash-free",
            displayName = "GLM 5.3 Flash",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerZen = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Opencode Zen",
            enabled = true,
            models = listOf(modelZen),
        )
        val modelUnique = me.rerere.ai.provider.Model(
            modelId = "unique-kios-model",
            displayName = "Unique Kios Model",
            type = me.rerere.ai.provider.ModelType.CHAT,
        )
        val providerKios = me.rerere.ai.provider.ProviderSetting.OpenAI(
            name = "Kiosapi-FreePro",
            enabled = true,
            models = listOf(modelUnique),
        )

        val profile = researcher.copy(
            modelId = null,
            modelSlug = "unique-kios-model",
            providerId = null,
        )

        val res = SubAgentModelResolver.resolveProfileModel(profile, listOf(providerZen, providerKios))
        assertTrue(res is SubAgentModelResolver.Result.Resolved)
        val resolved = res as SubAgentModelResolver.Result.Resolved
        assertEquals(modelUnique.id, resolved.modelId)
        assertEquals("Kiosapi-FreePro", resolved.providerName)
    }
}
