package app.amber.core.ai.transformers

import android.content.Context
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.R
import app.amber.feature.miniapp.MiniAppOutputParser
import app.amber.feature.miniapp.MiniAppRepository
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
        val lastUserIndex = messages.take(assistantIndex).indexOfLast { it.role == MessageRole.USER }
        val lastUserText = if (lastUserIndex >= 0) {
            messages[lastUserIndex].parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
        } else {
            ""
        }
        if (!MiniAppPromptTransformer.isExplicitMiniAppRequest(lastUserText)) return messages
        val message = messages[assistantIndex]
        if (message.parts.any { it is UIMessagePart.MiniApp }) return messages
        val textPartIndex = message.parts.indexOfLast { it is UIMessagePart.Text }
        if (textPartIndex < 0) return messages
        val textPart = message.parts[textPartIndex] as UIMessagePart.Text
        if (!mightContainMiniApp(textPart.text)) return messages
        val output = parser.parseOrNull(textPart.text) ?: return messages
        val revisionAppId = MiniAppPromptTransformer.revisionAppId(lastUserText)
        val revisionVersion = MiniAppPromptTransformer.revisionVersion(lastUserText)
        val entity = if (revisionAppId != null) {
            repository.saveRevision(
                appId = revisionAppId,
                output = output,
                expectedBaseVersion = revisionVersion,
                sourceMessageId = message.id.toString(),
                changeNote = revisionChangeNote(lastUserText),
            ) ?: return revisionFailed(ctx.context, messages, assistantIndex, message, textPartIndex, textPart)
        } else {
            repository.saveGenerated(
                output = output,
                sourceMessageId = message.id.toString(),
            )
        }
        val ref = repository.toCardRef(entity)
        val statusText = if (revisionAppId != null) {
            ctx.context.getString(R.string.miniapp_status_updated, entity.title, entity.version)
        } else {
            ctx.context.getString(R.string.miniapp_status_generated, entity.title)
        }
        val updated = message.copy(
            parts = buildList {
                message.parts.forEachIndexed { index, part ->
                    if (index == textPartIndex) {
                        add(UIMessagePart.Text(statusText, metadata = textPart.metadata))
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

    private fun revisionChangeNote(text: String): String {
        val marker = "用户修改意见："
        return text.substringAfter(marker, text)
            .lineSequence()
            .takeWhile { !it.startsWith("请基于") }
            .joinToString("\n")
            .trim()
            .ifBlank { "MiniApp revision" }
            .take(240)
    }

    private fun revisionFailed(
        context: Context,
        messages: List<UIMessage>,
        assistantIndex: Int,
        message: UIMessage,
        textPartIndex: Int,
        textPart: UIMessagePart.Text,
    ): List<UIMessage> {
        val updated = message.copy(
            parts = message.parts.mapIndexed { index, part ->
                if (index == textPartIndex) {
                    UIMessagePart.Text(
                        text = context.getString(R.string.miniapp_status_update_failed),
                        metadata = textPart.metadata,
                    )
                } else {
                    part
                }
            }
        )
        return messages.toMutableList().also { it[assistantIndex] = updated }
    }
}
