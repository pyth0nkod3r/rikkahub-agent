package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.ai.AgentMode

class SlashCommandCompletionProvider(
    private val currentMode: () -> AgentMode,
) : ChatCompletionProvider {
    override val id: String = "slash_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val command = findSlashCommand(context.text, context.cursor) ?: return null
        val query = command.query.lowercase()
        val mode = currentMode()

        val items = buildList {
            when (mode) {
                AgentMode.BUILD -> {
                    if ("plan".startsWith(query)) {
                        add(
                            ChatCompletionItem(
                                label = "/plan",
                                insertText = "/plan",
                                detail = "Switch to Plan mode (analyze and design without modifying files)",
                                icon = HugeIcons.Edit03,
                                sortScore = 1000,
                            )
                        )
                    }
                }
                AgentMode.PLAN -> {
                    if ("build".startsWith(query)) {
                        add(
                            ChatCompletionItem(
                                label = "/build",
                                insertText = "/build",
                                detail = "Switch to Build mode (default agent execution and editing)",
                                icon = HugeIcons.Tools,
                                sortScore = 1000,
                            )
                        )
                    }
                }
            }
            if ("compact".startsWith(query)) {
                add(
                    ChatCompletionItem(
                        label = "/compact",
                        insertText = "/compact",
                        detail = "Compress older conversation history into a summary to free context tokens",
                        icon = HugeIcons.Tools,
                        sortScore = 950,
                    )
                )
            }
        }

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = command.range,
            items = items,
        )
    }

    private data class SlashCommand(
        val query: String,
        val range: TextRange,
    )

    private fun findSlashCommand(text: String, cursor: Int): SlashCommand? {
        if (cursor < 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('/')
        if (start < 0) return null
        // Command slash must be at start of input or after whitespace/newline
        if (start > 0 && !prefix[start - 1].isWhitespace()) return null

        val query = prefix.substring(start + 1)
        if (query.any { it.isWhitespace() }) return null
        return SlashCommand(
            query = query,
            range = TextRange(start, cursor),
        )
    }
}
