package me.rerere.rikkahub.data.agent.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewOperationStoreTest {
    @Test
    fun staleCallbacksFromOldLoadCannotOverwriteCurrentPage() {
        val store = WebViewOperationStore()
        val firstLoadId = store.open("https://a.example")
        val secondLoadId = store.open("https://b.example")

        store.updateLoading(firstLoadId, "https://a.example", 100)
        store.updateReadablePage(
            loadId = firstLoadId,
            url = "https://a.example",
            title = "A",
            readableText = "old page",
            links = listOf(WebViewLink("old", "https://a.example/old")),
        )

        val afterStaleCallback = store.state.value
        assertEquals(secondLoadId, afterStaleCallback.loadId)
        assertEquals("https://b.example", afterStaleCallback.requestedUrl)
        assertEquals("", afterStaleCallback.title)
        assertEquals("", afterStaleCallback.readableText)

        store.updateReadablePage(
            loadId = secondLoadId,
            url = "https://b.example",
            title = "B",
            readableText = "new page",
            links = listOf(WebViewLink("new", "https://b.example/new")),
        )

        val current = store.state.value
        assertEquals("B", current.title)
        assertEquals("new page", current.readableText)
        assertEquals(WebViewLoadStatus.INTERACTIVE, current.status)
    }

    @Test
    fun readablePageBecomesInteractiveBeforeFinished() {
        val store = WebViewOperationStore()
        val loadId = store.open("https://example.com")

        store.updateLoading(loadId, "https://example.com", 45)
        store.updateReadablePage(
            loadId = loadId,
            url = "https://example.com",
            title = "Example",
            readableText = "hello webview",
            links = emptyList(),
        )

        val state = store.state.value
        assertEquals(WebViewLoadStatus.INTERACTIVE, state.status)
        assertEquals("Example", state.title)
        assertEquals("hello webview", state.readableText)
    }

    @Test
    fun finishedReadablePageBecomesReady() {
        val store = WebViewOperationStore()
        val loadId = store.open("https://example.com")

        store.updateReadablePage(
            loadId = loadId,
            url = "https://example.com",
            title = "Example",
            readableText = "hello webview",
            links = emptyList(),
        )
        store.markPageFinished(loadId, "https://example.com")

        val state = store.state.value
        assertEquals(100, state.loadingProgress)
        assertEquals(WebViewLoadStatus.READY, state.status)
        assertTrue(state.hasReadableContent)
    }

    @Test
    fun loadingWithoutProgressBecomesStalled() {
        val store = WebViewOperationStore()
        val loadId = store.open("https://slow.example")
        val openedAt = store.state.value.lastProgressAtEpochMillis

        store.updateLoading(loadId, "https://slow.example", 10)
        val progressAt = store.state.value.lastProgressAtEpochMillis
        val state = store.refreshStalled(progressAt + 12_001L)

        assertTrue(progressAt >= openedAt)
        assertEquals(WebViewLoadStatus.STALLED, state.status)
        assertEquals("https://slow.example", state.displayUrl)
    }
}
