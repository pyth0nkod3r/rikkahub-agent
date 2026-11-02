package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputTruncatorTest {
    @Test
    fun `truncateStructured preserves head and tail lines when line count exceeds limit`() {
        val lines = (1..400).map { "line-$it" }
        val input = lines.joinToString("\n")

        val truncated = ToolOutputTruncator.truncateStructured(
            text = input,
            headLines = 100,
            tailLines = 150,
            maxChars = 32_000,
        )

        assertTrue(truncated.contains("line-1"))
        assertTrue(truncated.contains("line-100"))
        assertTrue(truncated.contains("[... 150 lines"))
        assertTrue(truncated.contains("line-251"))
        assertTrue(truncated.contains("line-400"))
        assertTrue(!truncated.contains("line-150\n"))
    }

    @Test
    fun `decayAgedToolOutputs folds older tool outputs while keeping latest 4 verbatim`() {
        val longOutput = (1..80).joinToString("\n") { "verbose output line $it with extra text to exceed threshold" }
        val messages = (1..6).map { idx ->
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-$idx",
                        toolName = "workspace_shell",
                        input = "{\"cmd\":\"echo $idx\"}",
                        output = listOf(UIMessagePart.Text(longOutput)),
                    )
                ),
            )
        }

        val decayed = ToolOutputTruncator.decayAgedToolOutputs(
            messages = messages,
            keepLatestToolResults = 4,
            agedCharThreshold = 500,
        )

        val firstToolText = (decayed[0].parts.single() as UIMessagePart.Tool)
            .output.filterIsInstance<UIMessagePart.Text>().single().text
        val secondToolText = (decayed[1].parts.single() as UIMessagePart.Tool)
            .output.filterIsInstance<UIMessagePart.Text>().single().text
        val thirdToolText = (decayed[2].parts.single() as UIMessagePart.Tool)
            .output.filterIsInstance<UIMessagePart.Text>().single().text
        val sixthToolText = (decayed[5].parts.single() as UIMessagePart.Tool)
            .output.filterIsInstance<UIMessagePart.Text>().single().text

        assertTrue(firstToolText.contains("aged tool output decayed"))
        assertTrue(secondToolText.contains("aged tool output decayed"))
        assertEquals(longOutput, thirdToolText)
        assertEquals(longOutput, sixthToolText)
    }
}
