package app.amber.feature.ui.pages.search

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.amber.agent.data.db.fts.MessageSearchResult
import app.amber.agent.data.db.fts.SearchHitSource
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository

internal const val SEARCH_RECENT_CONVERSATION_LIMIT = 20
private const val TAG = "SearchVM"
private const val SEARCH_DEBOUNCE_MILLIS = 300L

enum class SearchFilter {
    ALL,
    CONVERSATIONS,
    MESSAGES,
}

enum class SearchErrorKind {
    RECENT_CONVERSATIONS,
    MESSAGES,
    INDEX_REBUILD,
}

internal fun filterSearchResults(
    results: List<MessageSearchResult>,
    filter: SearchFilter,
): List<MessageSearchResult> = when (filter) {
    SearchFilter.ALL -> results
    SearchFilter.CONVERSATIONS -> results.filter { it.titleMatched }
    SearchFilter.MESSAGES -> results.filter { it.hitSource == SearchHitSource.BODY }
}

internal fun shouldShowRecentConversations(
    query: String,
    filter: SearchFilter,
): Boolean = query.isBlank() && filter != SearchFilter.MESSAGES

class SearchVM(
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    var searchQuery by mutableStateOf("")
        private set
    var results by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set
    var recentConversations by mutableStateOf(emptyList<Conversation>())
        private set
    var searchFilter by mutableStateOf(SearchFilter.ALL)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set
    var rebuildProgress by mutableStateOf(0 to 0)
        private set
    private var searchErrorKind by mutableStateOf<SearchErrorKind?>(null)
    private var hasRebuildError by mutableStateOf(false)
    val errorKind: SearchErrorKind?
        get() = when {
            hasRebuildError -> SearchErrorKind.INDEX_REBUILD
            else -> searchErrorKind
        }
    val hasDisplayedContent: Boolean
        get() = if (searchQuery.isBlank()) {
            recentConversations.isNotEmpty()
        } else {
            visibleResults.isNotEmpty()
        }

    private val searchRequests = LatestSearchRequestRunner(viewModelScope)

    init {
        scheduleSearch(query = "", immediate = false)
    }

    val visibleResults: List<MessageSearchResult>
        get() = filterSearchResults(results, searchFilter)

    fun onQueryChange(query: String) {
        searchQuery = query
        searchErrorKind = null
        scheduleSearch(query, immediate = false)
    }

    fun onFilterChange(filter: SearchFilter) {
        searchFilter = filter
    }

    fun search() {
        scheduleSearch(searchQuery, immediate = true)
    }

    fun rebuildIndex() {
        if (isRebuilding) return
        viewModelScope.launch {
            isRebuilding = true
            hasRebuildError = false
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
                // Refresh results against the freshly rebuilt index.
                scheduleSearch(searchQuery, immediate = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Message index rebuild failed", error)
                hasRebuildError = true
            } finally {
                isRebuilding = false
            }
        }
    }

    fun retryLastOperation() {
        when {
            hasRebuildError -> rebuildIndex()
            searchErrorKind != null -> scheduleSearch(searchQuery, immediate = true)
            else -> Unit
        }
    }

    private fun scheduleSearch(query: String, immediate: Boolean) {
        searchRequests.submit(
            delayMillis = if (immediate) 0L else SEARCH_DEBOUNCE_MILLIS,
        ) { request ->
            performSearch(query, request)
        }
    }

    private suspend fun performSearch(query: String, request: LatestSearchRequestRunner.Request) {
        if (!searchRequests.isCurrent(request)) return
        isLoading = true
        searchErrorKind = null
        try {
            if (query.isBlank()) {
                val recent = conversationRepo.getRecentConversationSummaries(
                    limit = SEARCH_RECENT_CONVERSATION_LIMIT,
                )
                if (!searchRequests.isCurrent(request)) return
                results = emptyList()
                recentConversations = recent
            } else {
                val nextResults = conversationRepo.searchMessages(query)
                if (!searchRequests.isCurrent(request)) return
                recentConversations = emptyList()
                results = nextResults
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Search failed for query", error)
            if (searchRequests.isCurrent(request)) {
                searchErrorKind = if (query.isBlank()) {
                    SearchErrorKind.RECENT_CONVERSATIONS
                } else {
                    SearchErrorKind.MESSAGES
                }
            }
        } finally {
            if (searchRequests.isCurrent(request)) {
                isLoading = false
            }
        }
    }
}

/**
 * Owns the one cancellable search request for typing, submit, and retry.
 * The generation check still protects state if a repository ignores cancellation.
 */
internal class LatestSearchRequestRunner(
    private val scope: CoroutineScope,
) {
    data class Request internal constructor(val generation: Long)

    private var generation = 0L
    private var job: Job? = null

    fun submit(delayMillis: Long, block: suspend (Request) -> Unit) {
        val request = Request(++generation)
        job?.cancel()
        job = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            block(request)
        }
    }

    fun isCurrent(request: Request): Boolean = request.generation == generation
}
