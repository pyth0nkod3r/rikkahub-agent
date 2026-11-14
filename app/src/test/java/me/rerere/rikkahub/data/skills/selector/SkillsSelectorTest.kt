package me.rerere.rikkahub.data.skills.selector

import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillsSelectorTest {

    private fun mockSkill(name: String, desc: String = "Test skill description", autoLoad: Boolean = false): SkillMetadata {
        return SkillMetadata(
            name = name,
            description = desc,
            autoLoad = autoLoad,
            skillDir = File("/skills/$name"),
        )
    }

    @Test
    fun `selector respects 5 tiers in strict precedence order`() {
        val requestSkill = mockSkill("code-reviewer", "Reviews pull requests")
        val projectSkill = mockSkill("cartography", "Maps code architecture")
        val enabledSkill = mockSkill("formatter", "Auto formats code")
        val relevantSkill = mockSkill("database-migration", "Postgres sql migrations and schema")
        val generalSkill = mockSkill("agent-core", "Core agent personality", autoLoad = true)

        val catalog = listOf(requestSkill, projectSkill, enabledSkill, relevantSkill, generalSkill)

        val result = SkillsSelector.select(
            catalog = catalog,
            userMessage = "Can you run code-reviewer on my postgres schema migrations?",
            projectDoc = "This project uses cartography to manage structure.",
            enabledSkillNames = setOf("formatter"),
        )

        val selectedNames = result.selected.map { it.skill.name }
        assertEquals(5, selectedNames.size)

        // Tier 1: REQUEST
        assertEquals("code-reviewer", result.selected[0].skill.name)
        assertEquals(SkillTier.REQUEST, result.selected[0].tier)

        // Tier 2: PROJECT
        assertEquals("cartography", result.selected[1].skill.name)
        assertEquals(SkillTier.PROJECT, result.selected[1].tier)

        // Tier 3: ENABLED
        assertEquals("formatter", result.selected[2].skill.name)
        assertEquals(SkillTier.ENABLED, result.selected[2].tier)

        // Tier 4: RELEVANT
        assertEquals("database-migration", result.selected[3].skill.name)
        assertEquals(SkillTier.RELEVANT, result.selected[3].tier)

        // Tier 5: GENERAL
        assertEquals("agent-core", result.selected[4].skill.name)
        assertEquals(SkillTier.GENERAL, result.selected[4].tier)
    }

    @Test
    fun `explicit disabled state beats user request`() {
        val dangerousSkill = mockSkill("rm-rf", "Deletes all files")
        val catalog = listOf(dangerousSkill)

        val result = SkillsSelector.select(
            catalog = catalog,
            userMessage = "Please run rm-rf immediately",
            disabledSkillNames = setOf("rm-rf"),
        )

        assertTrue(result.selected.isEmpty())
        assertEquals(1, result.omitted.size)
        assertEquals("rm-rf", result.omitted[0].skill.name)
        assertEquals("disabled", result.omitted[0].reason)
    }

    @Test
    fun `selector enforces maxSkillsInPrompt ceiling`() {
        val skills = (1..10).map { mockSkill("skill-$it", "Skill description $it", autoLoad = true) }
        val result = SkillsSelector.select(
            catalog = skills,
            userMessage = "Hello agent",
            maxSkillsInPrompt = 3,
        )

        assertEquals(3, result.selected.size)
        assertEquals(7, result.omitted.size)
        assertTrue(result.omitted.all { it.reason == "omitted_ceiling" })
    }

    @Test
    fun `disabled skill matching is case insensitive`() {
        val dangerousSkill = mockSkill("format-disk", "Formats all drives")
        val catalog = listOf(dangerousSkill)

        val result = SkillsSelector.select(
            catalog = catalog,
            userMessage = "format-disk please",
            disabledSkillNames = setOf("FORMAT-DISK"),
        )

        assertTrue(result.selected.isEmpty())
        assertEquals(1, result.omitted.size)
        assertEquals("format-disk", result.omitted[0].skill.name)
        assertEquals("disabled", result.omitted[0].reason)
    }
}
