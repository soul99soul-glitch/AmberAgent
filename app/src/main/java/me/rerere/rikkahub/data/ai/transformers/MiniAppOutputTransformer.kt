package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.miniapp.MiniAppOutputParser
import me.rerere.rikkahub.data.agent.miniapp.MiniAppRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object MiniAppOutputTransformer : OutputMessageTransformer, KoinComponent {
    private val repository: MiniAppRepository by inject()
    private val parser = MiniAppOutputParser()

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.settings.agentRuntime.miniApp.enabled) return messages
        val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (assistantIndex < 0) return messages
        val lastUserText = messages
            .take(assistantIndex)
            .lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        if (!MiniAppPromptTransformer.isExplicitMiniAppRequest(lastUserText)) return messages
        val message = messages[assistantIndex]
        if (message.parts.any { it is UIMessagePart.MiniApp }) return messages
        val textPartIndex = message.parts.indexOfLast { it is UIMessagePart.Text }
        if (textPartIndex < 0) return messages
        val textPart = message.parts[textPartIndex] as UIMessagePart.Text
        if (!mightContainMiniApp(textPart.text)) return messages
        val output = parser.parseOrNull(textPart.text) ?: return messages
        val entity = repository.saveGenerated(
            output = output,
            sourceMessageId = message.id.toString(),
        )
        val ref = repository.toCardRef(entity)
        val updated = message.copy(
            parts = buildList {
                message.parts.forEachIndexed { index, part ->
                    if (index == textPartIndex) {
                        add(UIMessagePart.Text("已生成小应用：${entity.title}", metadata = textPart.metadata))
                        add(
                            UIMessagePart.MiniApp(
                                appId = ref.appId,
                                title = ref.title,
                                description = ref.description,
                                iconEmoji = ref.iconEmoji,
                                category = ref.category,
                                permissions = ref.permissions,
                                htmlHash = ref.htmlHash,
                                version = ref.version,
                            )
                        )
                    } else {
                        add(part)
                    }
                }
            }
        )
        return messages.toMutableList().also { it[assistantIndex] = updated }
    }

    private fun mightContainMiniApp(text: String): Boolean {
        return "\"html\"" in text && "\"title\"" in text && "\"description\"" in text
    }
}
