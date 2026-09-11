package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import app.amber.agent.RouteActivity
import app.amber.core.model.QuickMessage
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * W16-B: dynamic launcher shortcuts (iOS App Shortcuts parity, minimal set).
 *
 * Publishes up to four dynamic entries alongside the existing static camera
 * shortcut: new chat, the most recent conversations, and saved quick messages.
 * Entries launch RouteActivity through typed extras the deep-link path already
 * consumes, so routing and the chat send gate stay owned by the existing
 * screens — the shortcut layer only carries identity, never a second dispatch.
 *
 * Removed conversations / quick messages disappear on the next publish; a
 * stale tapped entry resolves to the not-found chat landing, never to another
 * conversation.
 */
object DynamicShortcutPublisher {
    private const val MAX_DYNAMIC = 4
    private const val KIND_NEW_CHAT = "new_chat"
    private const val KIND_CONVERSATION = "conversation"
    private const val KIND_QUICK_MESSAGE = "quick_message"

    const val EXTRA_SHORTCUT_KIND = "amberShortcutKind"
    const val EXTRA_SHORTCUT_CONVERSATION_ID = "shortcutConversationId"
    /**
     * Shortcut prompts use their own extra so the generic openChatPrompt
     * consumer (taskSessionScreenFromIntent) never double-routes a shortcut
     * intent: one intent, one landing screen.
     */
    const val EXTRA_SHORTCUT_PROMPT = "amberShortcutPrompt"

    suspend fun publish(
        context: Context,
        settingsStore: SettingsAggregator,
        conversationRepository: ConversationRepository,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val recent = runCatching { conversationRepository.getRecentConversationSummaries(2) }
            .getOrDefault(emptyList())
        val shortcuts = buildList<ShortcutInfoCompat> {
            add(newChatShortcut(context))
            recent.forEach { conversation ->
                conversationShortcut(context, conversation)?.let { add(it) }
            }
            val quickBudget = (MAX_DYNAMIC - size).coerceAtLeast(0)
            settings.quickMessages.asSequence()
                .filter { it.title.isNotBlank() }
                .take(quickBudget)
                .forEach { quickMessage -> add(quickMessageShortcut(context, quickMessage)) }
        }.take(MAX_DYNAMIC)
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun baseIntent(context: Context, kind: String): Intent =
        Intent(context, RouteActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("amberagent://shortcut")
            `package` = context.packageName
            putExtra(EXTRA_SHORTCUT_KIND, kind)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    internal fun newChatShortcut(context: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "dynamic_new_chat")
            .setShortLabel("新建会话")
            .setLongLabel("新建会话")
            .setIntent(baseIntent(context, KIND_NEW_CHAT))
            .build()

    private fun conversationShortcut(context: Context, conversation: Conversation): ShortcutInfoCompat? {
        val title = conversation.title.takeIf { it.isNotBlank() } ?: return null
        return ShortcutInfoCompat.Builder(context, "conversation_${conversation.id}")
            .setShortLabel(title.take(12))
            .setLongLabel(title.take(24))
            .setIntent(
                baseIntent(context, KIND_CONVERSATION).putExtra(
                    EXTRA_SHORTCUT_CONVERSATION_ID,
                    conversation.id.toString(),
                ),
            )
            .build()
    }

    private fun quickMessageShortcut(context: Context, quickMessage: QuickMessage): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "quick_message_${quickMessage.id}")
            .setShortLabel(quickMessage.title.take(12))
            .setLongLabel(quickMessage.title.take(24))
            .setIntent(
                baseIntent(context, KIND_QUICK_MESSAGE).putExtra(
                    EXTRA_SHORTCUT_PROMPT,
                    quickMessage.content.take(2000),
                ),
            )
            .build()

    /** Resolves a shortcut intent into a typed navigation route. */
    fun screenFromIntent(intent: Intent): Route? =
        routeFrom(
            kind = intent.getStringExtra(EXTRA_SHORTCUT_KIND),
            conversationId = intent.getStringExtra(EXTRA_SHORTCUT_CONVERSATION_ID),
            prompt = intent.getStringExtra(EXTRA_SHORTCUT_PROMPT),
        )

    /** Pure routing contract; [screenFromIntent] is the Intent adapter. */
    fun routeFrom(kind: String?, conversationId: String?, prompt: String?): Route? {
        when (kind ?: return null) {
            KIND_NEW_CHAT -> return Route.NewChat
            KIND_CONVERSATION -> {
                val raw = conversationId ?: return null
                val id = runCatching { Uuid.parse(raw) }.getOrNull() ?: return null
                return Route.Conversation(id)
            }
            KIND_QUICK_MESSAGE -> {
                val text = prompt?.takeIf { it.isNotBlank() } ?: return null
                return Route.QuickPrompt(text)
            }
            else -> return null
        }
    }

    sealed interface Route {
        data object NewChat : Route
        data class Conversation(val conversationId: Uuid) : Route
        data class QuickPrompt(val prompt: String) : Route
    }
}
