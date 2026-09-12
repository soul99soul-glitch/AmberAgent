package app.amber.feature.ui.pages.sessionhome

import android.util.Log
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.core.files.FilesManager
import app.amber.core.model.Avatar
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueCandidateAggregator
import app.amber.feature.home.ContinueDismissStore
import app.amber.feature.home.DEFAULT_DISMISS_DURATION
import java.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * SessionHome 首页的 ViewModel：为会话面板提供列表所需的业务操作
 * （运行中任务、删除/置顶/重命名会话、更新设置），以及 P8-08 首页
 * 「继续」聚合（[continueCandidates] + [dismissContinueCandidate]）。
 *
 * 会话摘要由本页分页观察；首页的快速筛选只在这些摘要上运行。
 */
class SessionHomeVM(
    private val settingsStore: SettingsAggregator,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val filesManager: FilesManager,
    private val continueAggregator: ContinueCandidateAggregator,
    private val continueDismissStore: ContinueDismissStore,
) : ViewModel() {

    private val reloadRequests = MutableStateFlow(0)
    private val _hasConversationError = MutableStateFlow(false)
    val hasConversationError: StateFlow<Boolean> = _hasConversationError

    private val homeSearchRequest = MutableStateFlow(HomeSearchRequest())

    private val continueReloadRequests = MutableStateFlow(0)
    private val _hasContinueError = MutableStateFlow(false)
    val hasContinueError: StateFlow<Boolean> = _hasContinueError

    /**
     * Home's default list is paged. A non-empty quick search deliberately reads
     * the complete lightweight summary flow before applying the local predicate:
     * [filterHomeConversations] must see every row so a match after the first
     * page (or a no-match query) is not mistaken for an empty result.
     */
    val conversations: Flow<PagingData<Conversation>> =
        combine(reloadRequests, homeSearchRequest) { _, search -> search }
            .flatMapLatest { search ->
                observeConversationPaging(
                    source = {
                        val trimmedQuery = search.query.trim()
                        if (trimmedQuery.isEmpty()) {
                            conversationRepo.getConversationsPaging()
                        } else {
                            conversationRepo.getConversationSummaries()
                                .map { summaries ->
                                    PagingData.from(
                                        filterHomeConversations(
                                            conversations = summaries,
                                            query = trimmedQuery,
                                            untitledLabel = search.untitledLabel,
                                        )
                                    )
                                }
                                .flowOn(Dispatchers.Default)
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "Home conversation stream failed", error)
                        _hasConversationError.value = true
                    },
                ).onEach { _hasConversationError.value = false }
            }
            .cachedIn(viewModelScope)

    fun setHomeSearchQuery(query: String, untitledLabel: String) {
        val request = HomeSearchRequest(query = query, untitledLabel = untitledLabel)
        if (homeSearchRequest.value != request) {
            homeSearchRequest.value = request
        }
    }

    fun retryConversations() {
        _hasConversationError.value = false
        reloadRequests.value += 1
    }

    val conversationJobs: StateFlow<Map<Uuid, Job?>> = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** P8-08：首页「继续」聚合列表（持久投影，进程死亡后仍正确）。 */
    val continueCandidates: StateFlow<List<ContinueCandidate>> = continueReloadRequests
        .flatMapLatest {
            observeContinueStream(
                source = { continueAggregator.observe() },
                onValue = { _hasContinueError.value = false },
                onError = { error ->
                    Log.e(TAG, "Home continue stream failed", error)
                    _hasContinueError.value = true
                },
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 0,
            ),
            emptyList(),
        )

    fun retryContinueCandidates() {
        _hasContinueError.value = false
        continueReloadRequests.value += 1
    }

    /** P8-08：暂时隐藏一个候选（默认 24 小时，到期自动恢复）。 */
    fun dismissContinueCandidate(candidate: ContinueCandidate) {
        viewModelScope.launch {
            continueDismissStore.dismiss(
                sourceKind = candidate.sourceKind,
                sourceId = candidate.sourceId,
                until = Instant.now().plus(DEFAULT_DISMISS_DURATION),
            )
        }
    }

    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settingsStore.settingsFlow.first()
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            chatService.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.togglePinnedStatus(conversation.id)
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }
}

private const val TAG = "SessionHomeVM"

private data class HomeSearchRequest(
    val query: String = "",
    val untitledLabel: String = "",
)

/**
 * Keeps Pager construction failures and collection failures observable without
 * emitting an empty replacement that would erase the last rendered page.
 */
internal fun observeConversationPaging(
    source: () -> Flow<PagingData<Conversation>>,
    onError: (Throwable) -> Unit,
): Flow<PagingData<Conversation>> = flow {
    try {
        emitAll(source())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
    }
}

/**
 * Keeps the Continue list and its error state independent from conversation
 * loading. A transient source failure must not erase already visible cards or
 * masquerade as an empty Home state.
 */
internal fun observeContinueStream(
    source: () -> Flow<List<ContinueCandidate>>,
    onValue: (List<ContinueCandidate>) -> Unit,
    onError: (Throwable) -> Unit,
): Flow<List<ContinueCandidate>> {
    return try {
        source()
            .onEach { value -> onValue(value) }
            .catch { error ->
                if (error is CancellationException) throw error
                onError(error)
            }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
        emptyFlow()
    }
}
