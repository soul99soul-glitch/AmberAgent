package app.amber.feature.ui.pages.sessionhome

import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import app.amber.core.model.Conversation
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

internal fun observeVisibleHomeConversations(
    source: Flow<PagingData<Conversation>>,
    hiddenIds: Flow<Set<Uuid>>,
    scope: CoroutineScope,
): Flow<PagingData<Conversation>> = source.cachedIn(scope)
    .combine(hiddenIds) { page, hidden -> page.filter { it.id !in hidden } }
    .cachedIn(scope)
