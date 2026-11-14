package me.rerere.rikkahub.data.ai.broker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBrokerTest {

    @Test
    fun `fault isolation ensures collector crash does not suppress other sources or memories`() = runBlocking {
        val brokenCollector = ContextCollector {
            throw IllegalStateException("Simulated disk error or git failure")
        }

        val broker = ContextBroker(
            customCollectors = mapOf(
                ContextSource.PROJECT_DOC to brokenCollector,
            )
        )

        val bundle = broker.assemble(
            ContextRequest(
                assistantPrompt = "Core persona",
                projectDoc = "Broken project",
                memoryPrompt = "**Standing Memory**",
                rulesPrompt = "**Standing Rules**",
            )
        )

        // Project doc failed and recorded in errors
        assertEquals(1, bundle.errors.size)
        assertTrue(bundle.errors.containsKey(ContextSource.PROJECT_DOC))
        assertFalse(bundle.hasSource(ContextSource.PROJECT_DOC))

        // Pinned memories and rules survived completely unharmed
        assertTrue(bundle.hasSource(ContextSource.MEMORY))
        assertTrue(bundle.hasSource(ContextSource.RULES))
        assertTrue(bundle.hasSource(ContextSource.SYSTEM_INSTRUCTION))
    }

    @Test
    fun `budget trimming drops lowest priority items while preserving pinned rules and memory`() = runBlocking {
        val broker = ContextBroker()

        // Create large content to trigger budget trimming
        val hugeChats = "Recent chat message history ".repeat(200) // ~1400 tokens
        val skills = "Skill documentation and usage ".repeat(100) // ~750 tokens
        val rules = "Authoritative rule block ".repeat(50) // ~300 tokens
        val memory = "Persistent user memory ".repeat(50) // ~300 tokens

        val bundle = broker.assemble(
            ContextRequest(
                assistantPrompt = "Persona",
                skillsPrompt = skills,
                recentChatsPrompt = hugeChats,
                rulesPrompt = rules,
                memoryPrompt = memory,
                maxPromptTokens = 800, // Strict token limit
            )
        )

        assertNotNull(bundle.plan)
        assertTrue(bundle.plan!!.wasOverBudget)

        // Lowest priority item (RECENT_CHATS) was dropped
        assertTrue(bundle.plan!!.droppedSources.contains(ContextSource.RECENT_CHATS))

        // Pinned sources (RULES, MEMORY, SYSTEM_INSTRUCTION) must never be dropped
        assertTrue(bundle.hasSource(ContextSource.RULES))
        assertTrue(bundle.hasSource(ContextSource.MEMORY))
        assertTrue(bundle.hasSource(ContextSource.SYSTEM_INSTRUCTION))
    }

    @Test
    fun `renderOrder strictly dictates output sequence`() = runBlocking {
        val broker = ContextBroker()
        val bundle = broker.assemble(
            ContextRequest(
                assistantPrompt = "Persona",
                toolPrompts = listOf("Tool A"),
                recentChatsPrompt = "Recent chat",
                memoryPrompt = "Memory data",
                rulesPrompt = "Strict rules",
            )
        )

        val fullPrompt = bundle.renderFullPrompt()

        val personaIdx = fullPrompt.indexOf("Persona")
        val toolIdx = fullPrompt.indexOf("Tool A")
        val memIdx = fullPrompt.indexOf("Memory data")
        val chatsIdx = fullPrompt.indexOf("Recent chat")
        val rulesIdx = fullPrompt.indexOf("Strict rules")

        assertTrue(personaIdx < toolIdx)
        assertTrue(toolIdx < memIdx)
        assertTrue(memIdx < chatsIdx)
        assertTrue(chatsIdx < rulesIdx)
    }
}
