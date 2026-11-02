package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.partitionBySoftCap
import me.rerere.rikkahub.subagent.SubAgentPolicyBuild
import me.rerere.rikkahub.subagent.SubAgentToolPolicies
import me.rerere.rikkahub.subagent.buildSubAgentToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DynamicToolSelectorTest {

    private fun echoTool(name: String): Tool = Tool(
        name = name,
        description = "Description for $name",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("msg", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
                required = listOf("msg"),
            )
        },
        execute = { input ->
            val msg = input.jsonObject["msg"]?.jsonPrimitive?.content ?: "empty"
            listOf(UIMessagePart.Text("""{"invoked":"$name","msg":"$msg"}"""))
        },
    )

    @Test
    fun `enable promotes standby tools into active window on next partition`() = runTest {
        val convId = Uuid.random()
        ConversationToolSelectionState.reset(convId)
        try {
            val allTools = (1..20).map { echoTool("tool_$it") }
            val initialPartition = allTools.partitionBySoftCap(maxTools = 5)
            assertEquals(4, initialPartition.activeTools.size)
            assertEquals(16, initialPartition.standbyTools.size)

            val selector = createManageActiveToolsTool(
                conversationId = convId,
                allTools = allTools,
                partition = initialPartition,
            )

            val enableResult = selector.execute(
                buildJsonObject {
                    put("action", JsonPrimitive("enable"))
                    put("tool_names", buildJsonArray {
                        add(JsonPrimitive("tool_18"))
                        add(JsonPrimitive("tool_19"))
                    })
                    put("disable_tool_names", buildJsonArray {
                        add(JsonPrimitive("tool_1"))
                    })
                }
            )
            val resultJson = Json.parseToJsonElement((enableResult.first() as UIMessagePart.Text).text).jsonObject
            assertEquals("ok", resultJson["status"]?.jsonPrimitive?.content)
            assertTrue(resultJson["schemas"]?.jsonObject?.containsKey("tool_18") == true)

            val selection = ConversationToolSelectionState.get(convId)
            val nextPartition = allTools.partitionBySoftCap(
                maxTools = 5,
                pinnedToolNames = selection.enabled,
                demotedToolNames = selection.disabled,
            )
            val nextActiveNames = nextPartition.activeTools.map { it.name }.toSet()
            assertTrue(nextActiveNames.contains("tool_18"))
            assertTrue(nextActiveNames.contains("tool_19"))
            assertTrue("tool_1" !in nextActiveNames)
        } finally {
            ConversationToolSelectionState.reset(convId)
        }
    }

    @Test
    fun `invoke directly executes standby tool and respects subagent policy`() = runTest {
        val convId = Uuid.random()
        val policy = (buildSubAgentToolPolicy(
            roleTools = listOf("tool_15"),
            capabilities = emptyList(),
        ) as SubAgentPolicyBuild.Ok).policy
        SubAgentToolPolicies.register(convId, policy)
        ConversationToolSelectionState.reset(convId)
        try {
            val allTools = (1..20).map { echoTool("tool_$it") }
            val partition = allTools.partitionBySoftCap(maxTools = 5)
            val selector = createManageActiveToolsTool(
                conversationId = convId,
                allTools = allTools,
                partition = partition,
            )

            // Permitted tool_15 succeeds even though it was in standby
            val okParts = selector.execute(
                buildJsonObject {
                    put("action", JsonPrimitive("invoke"))
                    put("tool_name", JsonPrimitive("tool_15"))
                    put("arguments", buildJsonObject {
                        put("msg", JsonPrimitive("hello from subagent"))
                    })
                }
            )
            val okJson = Json.parseToJsonElement((okParts.first() as UIMessagePart.Text).text).jsonObject
            assertEquals("tool_15", okJson["invoked"]?.jsonPrimitive?.content)
            assertEquals("hello from subagent", okJson["msg"]?.jsonPrimitive?.content)

            // Unpermitted tool_16 is blocked by sub-agent policy
            val blockedParts = selector.execute(
                buildJsonObject {
                    put("action", JsonPrimitive("invoke"))
                    put("tool_name", JsonPrimitive("tool_16"))
                    put("arguments", JsonObject(emptyMap()))
                }
            )
            val blockedJson = Json.parseToJsonElement((blockedParts.first() as UIMessagePart.Text).text).jsonObject
            assertEquals("tool_not_allowed", blockedJson["error"]?.jsonPrimitive?.content)
        } finally {
            SubAgentToolPolicies.unregister(convId)
            ConversationToolSelectionState.reset(convId)
        }
    }
}