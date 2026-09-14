package app.amber.feature.ui.pages.zcode

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZCodeUrlStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): ZCodeUrlStore = ZCodeUrlStore(
        PreferenceDataStoreFactory.create {
            File(tempFolder.root, "zcode.preferences_pb")
        },
    )

    @Test
    fun `save keeps opt in and clears old session when URL changes`() = runTest {
        val store = newStore()
        val firstUrl = "https://zcode.example/remote?token=first"
        val secondUrl = "http://zcode.example/other"

        store.save("  $firstUrl  ")
        store.setAgentEnabled(true)
        assertTrue(store.recordSession(firstUrl, "session-first"))
        assertEquals(
            ZCodeConnection(firstUrl, agentEnabled = true, sessionId = "session-first"),
            store.connectionFlow.first(),
        )
        assertEquals(firstUrl, store.urlFlow.first())

        store.save(secondUrl)

        assertEquals(
            ZCodeConnection(secondUrl, agentEnabled = true),
            store.connectionFlow.first(),
        )
    }

    @Test
    fun `record session is conditional on the currently selected URL`() = runTest {
        val store = newStore()
        val selectedUrl = "https://zcode.example/remote?token=selected"
        store.save(selectedUrl)

        assertFalse(store.recordSession("https://zcode.example/old", "stale-session"))
        assertNull(store.connectionFlow.first().sessionId)
        assertTrue(store.recordSession(selectedUrl, "live-session"))
        assertEquals("live-session", store.connectionFlow.first().sessionId)
    }

    @Test
    fun `normalizes http(s) links and rejects userinfo and invalid ports`() {
        assertEquals(
            "https://example.com/path?token=secret",
            normalizeZCodeUrl("  https://example.com/path?token=secret  "),
        )
        assertEquals(
            "http://example.com:8080/path",
            normalizeZCodeUrl("http://example.com:8080/path"),
        )
        assertNull(normalizeZCodeUrl("https://user:password@example.com/path"))
        assertNull(normalizeZCodeUrl("https://example.com:0/path"))
        assertNull(normalizeZCodeUrl("https://example.com:65536/path"))
        assertNull(normalizeZCodeUrl("https://example.com:/path"))
        assertNull(normalizeZCodeUrl("https://example.com:invalid/path"))
    }
}
