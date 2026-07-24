package app.amber.tts.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class TtsControllerHelpersTest {

    @Test
    fun `synthesized response is removed from cache after await`() = runBlocking {
        val deferred = CompletableDeferred("audio")
        val cache = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String>>()
        cache["chunk"] = deferred

        cache.awaitAndRemove("chunk", deferred)

        assertTrue(cache.isEmpty())
    }

    @Test
    fun `pause asserted during synthesis blocks playback handoff`() = runBlocking {
        var paused = true
        val handoff = async { awaitUnpaused { paused } }
        delay(120)
        assertFalse(handoff.isCompleted)

        paused = false
        handoff.await()
        assertTrue(handoff.isCompleted)
    }
}
