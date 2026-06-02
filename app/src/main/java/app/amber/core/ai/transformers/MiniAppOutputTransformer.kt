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
        if (ctx.settings.agentRuntime.subAgent.enabled && !hasCompletedMiniAppReview(messages, lastUserIndex, assistantIndex)) {
            return reviewRequired(messages, assistantIndex, message, textPartIndex, textPart)
        }
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

    internal fun hasCompletedMiniAppReview(
        messages: List<UIMessage>,
        startUserIndex: Int,
        endAssistantIndex: Int,
    ): Boolean {
        val start = (startUserIndex + 1).coerceAtLeast(0)
        val end = endAssistantIndex.coerceAtMost(messages.lastIndex)
        if (start > end) return false
        val tools = messages.subList(start, end + 1)
            .flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Tool>() }
        val reviewRunIds = tools
            .filter { it.toolName == "subagent_start" && it.isMiniAppReviewStart() }
            .mapNotNull { it.outputText().runIdOrNull() }
            .toSet()
        return tools.any { tool ->
            tool.toolName in subAgentResultTools &&
                tool.outputText().containsCompletedStatus() &&
                tool.input.runIdOrNull()?.let { it in reviewRunIds } == true
        }
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

    private fun reviewRequired(
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
                        text = "小应用尚未保存：当前已启用 SubAgent，MiniApp 生成需要先完成一次 SubAgent review/debug。请先调用 subagent_start 审阅草案并等待完成，再根据结果输出最终 MiniApp JSON。",
                        metadata = textPart.metadata,
                    )
                } else {
                    part
                }
            }
        )
        return messages.toMutableList().also { it[assistantIndex] = updated }
    }

    private val subAgentResultTools = setOf("subagent_wait", "subagent_read")
    private val completedSubAgentStatusPattern = Regex(""""status"\s*:\s*"completed"""")
    private val runIdPattern = Regex(""""run_id"\s*:\s*"([^"]+)"""")

    private fun UIMessagePart.Tool.isMiniAppReviewStart(): Boolean =
        input.looksLikeMiniAppReview() || outputText().looksLikeMiniAppReview()

    private fun UIMessagePart.Tool.outputText(): String =
        output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun String.runIdOrNull(): String? =
        runIdPattern.find(this)?.groupValues?.getOrNull(1)

    private fun String.containsCompletedStatus(): Boolean =
        completedSubAgentStatusPattern.containsMatchIn(this)

    private fun String.looksLikeMiniAppReview(): Boolean {
        val normalized = lowercase()
        val mentionsMiniApp = "miniapp" in normalized ||
            "mini app" in normalized ||
            "miniappreviewer" in normalized ||
            "小应用" in this ||
            "小程序" in this
        val mentionsReview = "review" in normalized ||
            "debug" in normalized ||
            "runnable" in normalized ||
            "审阅" in this ||
            "调试" in this ||
            "修复" in this ||
            "运行" in this
        return mentionsMiniApp && mentionsReview
    }
}
