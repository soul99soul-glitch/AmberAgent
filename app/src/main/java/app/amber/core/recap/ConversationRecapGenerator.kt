package app.amber.core.recap

import android.content.Context
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.utils.appLocaleDisplayName
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generates the session recap with the title model. Runs after each successful turn
 * and on demand when the recap panel opens without an up-to-date recap. Results are
 * written only under the conversation id that requested them.
 */
class ConversationRecapGenerator(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val conversationRepo: ConversationRepository,
    private val store: ConversationRecapStore,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * @param conversation the full conversation (all timeline pages loaded).
     * @param force regenerate even when the stored recap is fresh.
     */
    suspend fun generate(conversation: Conversation, force: Boolean = false) {
        val id = conversation.id.toString()
        if (!conversation.isRecapEligible()) {
            store.markIneligible(id)
            return
        }
        val lock = locks.getOrPut(id) { Mutex() }
        lock.withLock {
            val previous = store.ensureLoaded(id)
            if (!force && previous?.freshnessFor(conversation) == RecapFreshness.FRESH) {
                store.setGenerating(id, false)
                return
            }
            store.setGenerating(id, true)
            try {
                when (val outcome = requestRecap(conversation, previous)) {
                    RecapOutcome.NoModel -> store.markFailed(id, RecapFailure.NO_MODEL)
                    RecapOutcome.Unparseable -> store.markFailed(id, RecapFailure.ERROR)
                    is RecapOutcome.Ready -> if (conversationRepo.existsConversationById(conversation.id)) {
                        store.save(outcome.recap)
                        // A delete that finished between the check and the write leaves no orphan.
                        if (!conversationRepo.existsConversationById(conversation.id)) store.delete(id)
                    } else {
                        store.setGenerating(id, false)
                    }
                }
            } catch (error: CancellationException) {
                store.setGenerating(id, false)
                throw error
            } catch (error: Exception) {
                error.printStackTrace()
                store.markFailed(id, RecapFailure.ERROR)
            }
        }
    }

    private sealed interface RecapOutcome {
        data object NoModel : RecapOutcome
        data object Unparseable : RecapOutcome
        data class Ready(val recap: ConversationRecap) : RecapOutcome
    }

    private suspend fun requestRecap(
        conversation: Conversation,
        previous: ConversationRecap?,
    ): RecapOutcome {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.resolveTaskChatModel(settings.titleModelId) ?: return RecapOutcome.NoModel
        val provider = model.findProvider(settings.providers) ?: return RecapOutcome.NoModel
        val branchIds = conversation.currentBranchMessageIds()
        val sources = ConversationRecapPrompt.sourceMessages(conversation)
        val incremental = ConversationRecapPrompt.isIncremental(branchIds, previous)
        val prompt = ConversationRecapPrompt.build(
            title = conversation.title,
            localeName = context.appLocaleDisplayName(),
            messages = ConversationRecapPrompt.selectForPrompt(sources, previous, incremental),
            allSources = sources,
            previous = previous,
            previousIsIncremental = incremental,
        )
        val result = providerCatalog.text(provider).complete(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(
                model = model,
                reasoningLevel = ReasoningLevel.OFF,
                customHeaders = model.customHeaders,
                customBody = model.customBodies,
                sessionId = conversation.id.toString(),
            ),
        )
        val raw = result.choices.firstOrNull()?.message?.toText().orEmpty()
        val recap = ConversationRecapParser.parse(
            raw = raw,
            conversationId = conversation.id.toString(),
            sources = sources,
            branchMessageIds = branchIds,
            previous = previous.takeIf { incremental },
            nowMillis = System.currentTimeMillis(),
            tailSignature = conversation.recapTailSignature(),
        )
        return recap?.let { RecapOutcome.Ready(it) } ?: RecapOutcome.Unparseable
    }
}
