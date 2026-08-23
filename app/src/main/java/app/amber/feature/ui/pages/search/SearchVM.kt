package app.amber.feature.ui.pages.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import app.amber.agent.data.db.fts.MessageSearchResult
import app.amber.agent.data.db.fts.SearchHitSource
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository

internal const val SEARCH_RECENT_CONVERSATION_LIMIT = 20

enum class SearchFilter(val label: String) {
    ALL("All"),
    CONVERSATIONS("Conversations"),
    MESSAGES("Messages"),
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
    private val _searchQuery = MutableStateFlow("")

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

    init {
        viewModelScope.launch {
            _searchQuery
                .debounce(300L)
                .collectLatest { query -> performSearch(query) }
        }
    }

    val visibleResults: List<MessageSearchResult>
        get() = filterSearchResults(results, searchFilter)

    fun onQueryChange(query: String) {
        searchQuery = query
        _searchQuery.value = query
    }

    fun onFilterChange(filter: SearchFilter) {
        searchFilter = filter
    }

    fun search() {
        viewModelScope.launch {
            performSearch(searchQuery)
        }
    }

    fun rebuildIndex() {
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private suspend fun performSearch(query: String) {
        isLoading = true
        try {
            if (query.isBlank()) {
                results = emptyList()
                recentConversations = conversationRepo.getRecentConversationSummaries(
                    limit = SEARCH_RECENT_CONVERSATION_LIMIT,
                )
            } else {
                recentConversations = emptyList()
                results = conversationRepo.searchMessages(query)
            }
        } finally {
            isLoading = false
        }
    }
}
