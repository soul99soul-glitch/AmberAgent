package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationActivityConsumeTest {

    @Test
    fun invalidConversationIdDoesNotYieldNavigationTarget() {
        val current = Uuid.parse("11111111-1111-1111-1111-111111111111")
        assertNull(activityNoticeNavigationTarget("not-a-uuid", current))
        assertNull(activityNoticeNavigationTarget("conv-1", current))
        assertNull(activityNoticeNavigationTarget(current.toString(), current))
        val other = Uuid.parse("22222222-2222-2222-2222-222222222222")
        assertEquals(other, activityNoticeNavigationTarget(other.toString(), current))
    }

    @Test
    fun chatPageScaffoldWiresVisibleConsumeAndValidNavigationMarkSeen() {
        val source = listOf(
            File("src/main/java/app/amber/feature/ui/pages/chat/ChatPageScaffold.kt"),
            File("app/src/main/java/app/amber/feature/ui/pages/chat/ChatPageScaffold.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("consumeWhileResumed"))
        assertTrue(source.contains("activityNoticeNavigationTarget"))
    }

    @Test
    fun consumeWhileResumedRunsOnlyInForeground() = runBlocking {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        withContext(Dispatchers.Main.immediate) {
            owner.registry.currentState = Lifecycle.State.CREATED
        }
        val sessions = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Main.immediate) {
            consumeConversationActivityWhileResumed(owner.lifecycle, "conv-1") {
                sessions.send(Unit)
                awaitCancellation()
            }
        }
        yield()
        assertTrue(sessions.tryReceive().isFailure)
        withContext(Dispatchers.Main.immediate) {
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        sessions.receive()
        withContext(Dispatchers.Main.immediate) {
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        yield()
        assertTrue(sessions.tryReceive().isFailure)
        withContext(Dispatchers.Main.immediate) {
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        sessions.receive()
        job.cancel()
    }
}
