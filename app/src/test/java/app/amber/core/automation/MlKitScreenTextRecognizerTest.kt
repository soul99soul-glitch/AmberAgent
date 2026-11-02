package app.amber.core.automation

import android.app.Application
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ML Kit Task lifecycle contract:
 * - a cancelled Task must resume the coroutine (not hang inside NonCancellable);
 * - close() must not close the client under an in-flight `process`;
 * - concurrent processes share the cached client and close only after the last one settles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MlKitScreenTextRecognizerTest {

    private fun bitmap() = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)

    private class FakeProcessClient(
        private val tasks: List<Task<Text>>,
    ) : TextProcessClient {
        var processCalls = 0
        var closeCalls = 0
        val started = CompletableDeferred<Unit>()

        override fun process(bitmap: Bitmap): Task<Text> {
            val task = tasks[processCalls.coerceAtMost(tasks.lastIndex)]
            processCalls++
            if (!started.isCompleted) started.complete(Unit)
            return task
        }

        override fun close() {
            closeCalls++
        }
    }

    @Test
    fun cancelledMlKitTaskResumesWithDiagnosticInsteadOfHanging() = runBlocking {
        val client = object : TextProcessClient {
            override fun process(bitmap: Bitmap): Task<Text> = Tasks.forCanceled()
            override fun close() = Unit
        }
        val recognizer = MlKitScreenTextRecognizer { client }
        val frame = bitmap()

        val result = withTimeout(5_000) { recognizer.recognize(frame) }

        assertEquals("recognition_cancelled", result.failure)
        recognizer.close()
    }

    @Test
    fun closeDefersUntilInFlightProcessSettles() = runBlocking {
        val taskSource = TaskCompletionSource<Text>()
        val client = FakeProcessClient(listOf(taskSource.task))
        val recognizer = MlKitScreenTextRecognizer { client }
        val frame = bitmap()

        val job = async { recognizer.recognize(frame) }
        withTimeout(5_000) { client.started.await() }
        recognizer.close()

        assertEquals(0, client.closeCalls)
        taskSource.setResult(Text("ready", emptyList<Text.TextBlock>()))
        val result = withTimeout(5_000) { job.await() }

        assertNull(result.failure)
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun recognizeAfterCloseFailsWithoutCreatingAClient() = runBlocking {
        var factoryCalls = 0
        val recognizer = MlKitScreenTextRecognizer {
            factoryCalls++
            FakeProcessClient(emptyList())
        }
        recognizer.close()

        val result = recognizer.recognize(bitmap())

        assertEquals("recognizer_closed", result.failure)
        assertEquals(0, factoryCalls)
    }

    @Test
    fun concurrentProcessesShareClientAndCloseAfterLastSettles() = runBlocking {
        val first = TaskCompletionSource<Text>()
        val second = TaskCompletionSource<Text>()
        val client = FakeProcessClient(listOf(first.task, second.task))
        val recognizer = MlKitScreenTextRecognizer { client }
        val frame = bitmap()

        val job1 = async { recognizer.recognize(frame) }
        withTimeout(5_000) {
            while (client.processCalls < 1) delay(5)
        }
        val job2 = async { recognizer.recognize(frame) }
        withTimeout(5_000) {
            while (client.processCalls < 2) delay(5)
        }
        recognizer.close()
        assertEquals(0, client.closeCalls)

        first.setResult(Text("one", emptyList<Text.TextBlock>()))
        assertNull(withTimeout(5_000) { job1.await() }.failure)
        assertEquals(0, client.closeCalls)

        second.setResult(Text("two", emptyList<Text.TextBlock>()))
        assertNull(withTimeout(5_000) { job2.await() }.failure)
        assertEquals(1, client.closeCalls)
        assertEquals(2, client.processCalls)
    }
}
