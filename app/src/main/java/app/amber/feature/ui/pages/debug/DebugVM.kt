package app.amber.feature.ui.pages.debug

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.CapabilityFlagsData
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretStore
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.ConversationRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.random.Random
import kotlin.uuid.Uuid
import app.amber.agent.R

class DebugVM(
    private val context: Application,
    private val settingsStore: SettingsAggregator,
    private val conversationRepository: ConversationRepository,
    private val capabilityFlags: CapabilityFlags,
    private val secretStore: SecretStore,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val capabilities: StateFlow<CapabilityFlagsData> = capabilityFlags.flow
        .stateIn(viewModelScope, SharingStarted.Lazily, CapabilityFlagsData())

    /** P1-01: SecretStore 迁移版本（debug 页 schema version 展示用）。 */
    val secretMigrationVersion: Int
        get() = secretStore.migrationVersion()

    /** P1-02: ledger 表 schema 版本。 */
    val ledgerVersion: Int
        get() = app.amber.feature.runtime.TOOL_EFFECT_LEDGER_SCHEMA_VERSION

    /** P1-03: run terminal 表 schema 版本。 */
    val terminalVersion: Int
        get() = app.amber.feature.runtime.RUN_TERMINAL_SCHEMA_VERSION

    /** P4-02: thread graph 表 schema 版本。 */
    val threadGraphVersion: Int
        get() = app.amber.feature.runtime.THREAD_GRAPH_SCHEMA_VERSION

    /** P1-04: final token fit receipts（裁剪原因，debug 页可查看）。 */
    val tokenFitReceipts: StateFlow<List<app.amber.core.context.TokenFitReceipt>> =
        app.amber.core.context.TokenBudgetFitter.receipts

    fun setCapability(capability: Capability, enabled: Boolean) {
        viewModelScope.launch {
            capabilityFlags.setEnabled(capability, enabled)
        }
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 创建一个超大的对话用于测试 CursorWindow 限制
     * @param sizeMB 目标大小（MB）
     */
    fun createOversizedConversation(sizeMB: Int = 3) {
        viewModelScope.launch {
            val targetSize = sizeMB * 1024 * 1024
            val messageNodes = mutableListOf<MessageNode>()
            var currentSize = 0

            // 生成大量消息直到达到目标大小
            var index = 0
            while (currentSize < targetSize) {
                // 生成一个包含大量文本的消息（约 100KB 每条）
                val largeText = buildString {
                    repeat(100) {
                        append(context.getString(R.string.debug_oversized_test_text))
                        append(context.getString(R.string.debug_cursor_window_error_text))
                        append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. ")
                        append("Index: $index, Block: $it. ")
                    }
                }

                val userMessage = UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(largeText)),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )
                val assistantMessage = UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text(
                            context.getString(R.string.debug_assistant_reply_prefix, largeText)
                        )
                    ),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )

                messageNodes.add(MessageNode.of(userMessage))
                messageNodes.add(MessageNode.of(assistantMessage))

                currentSize += largeText.length * 2 * 2 // 大约估算
                index++
            }

            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = AMBER_AGENT_ID,
                title = context.getString(R.string.debug_oversized_conversation_title, sizeMB),
                messageNodes = messageNodes,
            )

            conversationRepository.insertConversation(conversation)
        }
    }

    fun createConversationWithMessages(messageCount: Int = 1024) {
        viewModelScope.launch {
            val messageNodes = ArrayList<MessageNode>(messageCount)
            val timeZone = TimeZone.currentSystemDefault()
            repeat(messageCount) { index ->
                val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
                val message = UIMessage(
                    id = Uuid.random(),
                    role = role,
                    parts = listOf(UIMessagePart.Text(randomMessageText(index, role))),
                    createdAt = Clock.System.now().toLocalDateTime(timeZone),
                )
                messageNodes.add(MessageNode.of(message))
            }

            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = AMBER_AGENT_ID,
                title = context.getString(R.string.debug_messages_conversation_title, messageCount),
                messageNodes = messageNodes,
            )

            conversationRepository.insertConversation(conversation)
        }
    }

    private fun randomMessageText(index: Int, role: MessageRole): String {
        val fragments = context.getString(R.string.debug_random_message_fragments).split('|')
        val wordCount = Random.nextInt(6, 14)
        val prefix = if (role == MessageRole.USER) {
            context.getString(R.string.debug_user_prefix)
        } else {
            context.getString(R.string.debug_assistant_prefix)
        }
        val body = List(wordCount) { fragments.random() }.joinToString(" ")
        return "$prefix#${index + 1}: $body"
    }
}
