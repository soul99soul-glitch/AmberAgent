package app.amber.feature.miniapp

import android.app.Application
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MiniAppSpeechEngineTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Before
    fun resetTextToSpeechShadow() {
        ShadowTextToSpeech.reset()
    }

    @Test
    fun `completion callback only clears the active utterance`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val finished = AtomicInteger(0)
        val engine = MiniAppSpeechEngine(context, onSpeechFinished = { finished.incrementAndGet() })

        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            engine.dispatch("speech.speak", speakParams("first"))
        }
        val firstTts = ShadowTextToSpeech.getLastTextToSpeechInstance()
            ?: error("speech engine did not create TextToSpeech")
        assertTrue(!startup.isCompleted)
        shadowOf(firstTts).getOnInitListener().onInit(TextToSpeech.SUCCESS)
        startup.await()
        val listener = shadowOf(firstTts).getUtteranceProgressListener()
            ?: error("speech engine did not install a progress listener")
        listener.onDone("amber-miniapp-1")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, finished.get())

        engine.dispatch("speech.speak", speakParams("second"))
        listener.onDone("amber-miniapp-1")
        assertEquals("a late callback from the old utterance must be ignored", 1, finished.get())

        listener.onDone("amber-miniapp-2")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, finished.get())
        engine.close()
    }

    @Test
    fun `foreground guard rejects speech before creating an engine`() {
        val context = RuntimeEnvironment.getApplication()
        val engine = MiniAppSpeechEngine(context, foregroundProvider = { false })

        val failure = runBlocking {
            runCatching { engine.dispatch("speech.speak", speakParams("background")) }.exceptionOrNull()
        }

        assertTrue(failure is MiniAppBridgeException)
        assertEquals(null, ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun `pause during initialization rejects late success even when foreground again`() = runBlocking {
        val engine = MiniAppSpeechEngine(RuntimeEnvironment.getApplication())
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.dispatch("speech.speak", speakParams("late")) }
        }
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()!!
        assertTrue(!startup.isCompleted)
        engine.suspendForBackground()
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS)

        val failure = startup.await().exceptionOrNull()
        assertTrue(failure is MiniAppBridgeException)
        assertEquals("not_foreground", (failure as MiniAppBridgeException).code)
        assertEquals(null, shadowOf(tts).getLastSpokenText())
        engine.close()
    }

    @Test
    fun `close during initialization rejects late success and shuts engine down`() = runBlocking {
        val engine = MiniAppSpeechEngine(RuntimeEnvironment.getApplication())
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { engine.dispatch("speech.speak", speakParams("late")) }
        }
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()!!
        assertTrue(!startup.isCompleted)
        engine.close()
        shadowOf(tts).getOnInitListener().onInit(TextToSpeech.SUCCESS)

        val failure = startup.await().exceptionOrNull()
        assertTrue(failure is MiniAppBridgeException)
        assertEquals("runner_closed", (failure as MiniAppBridgeException).code)
        assertEquals(null, shadowOf(tts).getLastSpokenText())
        assertTrue(shadowOf(tts).isShutdown)
    }

    private fun speakParams(text: String) = buildJsonObject {
        put("text", text)
        put("rate", 0.5f)
    }
}
