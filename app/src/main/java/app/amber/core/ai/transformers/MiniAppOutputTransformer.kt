package app.amber.core.ai.transformers

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
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
            ) ?: return revisionFailed(messages, assistantIndex, message, textPartIndex, textPart)
        } else {
            repository.saveGenerated(
                output = output,
                sourceMessageId = message.id.toString(),
            )
        }
        val ref = repository.toCardRef(entity)
        val statusText = if (revisionAppId != null) {
            "已更新小应用：${entity.title} v${entity.version}"
        } else {
            "已生成小应用：${entity.title}"
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
                        text = "小应用更新失败：目标小应用不存在，或已经被更新。请打开最新的小应用卡片后重新点击「修改」。",
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
