package app.amber.feature.ui.pages.sessionhome

import android.app.Application
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.amber.core.model.Conversation
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HomeVisibleConversationsTest {
    @Test fun `loaded pages survive hide undo re collection search switch and empty state`() = runTest {
        val rows = List(8) { Conversation.ofId(Uuid.random()).copy(title = "Test $it", isPinned = it == 0) }
        val hidden = MutableStateFlow<Set<Uuid>>(emptySet())
        val search = MutableStateFlow(false)
        var sources = 0
        val source = search.flatMapLatest { searching ->
            if (searching) MutableStateFlow(PagingData.from(rows.filter { it.title == "Test 7" }))
            else Pager(PagingConfig(pageSize = 2, initialLoadSize = 2, prefetchDistance = 1)) {
                sources++
                object : PagingSource<Int, Conversation>() {
                    override fun getRefreshKey(state: PagingState<Int, Conversation>): Int? = null
                    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Conversation> {
                        val start = params.key ?: 0
                        val end = minOf(start + params.loadSize, rows.size)
                        return LoadResult.Page(rows.subList(start, end),
                            prevKey = if (start == 0) null else maxOf(0, start - 2),
                            nextKey = if (end < rows.size) end else null)
                    }
                }
            }.flow
        }
        val visible = observeVisibleHomeConversations(source, hidden, backgroundScope)
        val presenter = object : PagingDataPresenter<Conversation>(StandardTestDispatcher(testScheduler)) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Conversation>) {}
        }
        var collector = backgroundScope.launch { visible.collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        // Walk through actual append pages, not PagingData.from alone.
        repeat(6) { presenter[presenter.size - 1]; runCurrent() }
        assertEquals(rows.map { it.id }, presenter.snapshot().items.map { it.id })
        hidden.value = rows.map { it.id }.toSet(); runCurrent()
        assertEquals(0, presenter.size)
        hidden.value = emptySet(); runCurrent()
        assertEquals(rows, presenter.snapshot().items)
        assertEquals(1, sources)
        collector.cancel(); runCurrent()
        hidden.value = setOf(rows[0].id); runCurrent()
        collector = backgroundScope.launch { visible.collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        assertEquals(rows.drop(1), presenter.snapshot().items)
        assertEquals(1, sources)
        search.value = true; runCurrent()
        assertEquals(listOf(rows[7]), presenter.snapshot().items)
        hidden.value = setOf(rows[7].id); runCurrent()
        assertEquals(0, presenter.size)
        hidden.value = emptySet(); runCurrent()
        assertEquals(listOf(rows[7]), presenter.snapshot().items)
        search.value = false; runCurrent()
        assertEquals(rows.take(2), presenter.snapshot().items)
        collector.cancel()
    }
}
