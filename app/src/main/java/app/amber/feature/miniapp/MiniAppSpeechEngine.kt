package app.amber.feature.miniapp

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * P4 W11: per-runner TTS owner. Android TTS has no native pause, so pause
 * saves the unspoken remainder (tracked via onRangeStart word boundaries) and
 * resume re-speaks it; the result reports `paused`/`resumed` honestly instead
 * of pretending a native pause happened. speak() returns once the utterance is
 * accepted by the engine (same as iOS {speaking:true}), not when audio ends.
 *
 * TTS calls happen on the main thread; init is awaited with a deferred
 * completed from the engine's own main-looper callback, so the coroutine
 * suspends instead of blocking the main thread.
 */
class MiniAppSpeechEngine(
    context: Context,
    private val foregroundProvider: () -> Boolean = { true },
    private val onSpeechFinished: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Int>? = null
    private var initStatus: Int = TextToSpeech.ERROR

    // Speech state guarded by the main thread.
    private var currentText: String = ""
    private var spokenUpTo: Int = 0
    private var pausedRemainder: String? = null
    private var currentVolume: Float = 1f
    private var foregroundEpoch: Long = 0L
    private var utteranceSequence: Long = 0L
    private var activeUtteranceId: String? = null

    private suspend fun engine(): TextToSpeech = withContext(Dispatchers.Main) {
        if (closed.get()) {
            throw MiniAppBridgeException("runner_closed", "MiniApp speech engine is closed")
        }
        tts?.let { return@withContext it }
        val deferred = CompletableDeferred<Int>()
        val engine = TextToSpeech(appContext) { status ->
            initStatus = status
            deferred.complete(status)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                postToMain {
                    if (utteranceId == activeUtteranceId) spokenUpTo = 0
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                postToMain {
                    if (utteranceId == activeUtteranceId) spokenUpTo = start
                }
            }

            override fun onDone(utteranceId: String?) {
                finishUtteranceOnMain(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishUtteranceOnMain(utteranceId)
        })
        tts = engine
        initDeferred = deferred
        val status = try {
            kotlinx.coroutines.withTimeout(INIT_TIMEOUT_MS) { deferred.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            TextToSpeech.ERROR
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // The waiting coroutine itself was cancelled (runner closed):
            // release the half-initialized engine and propagate cancellation.
            engine.shutdown()
            if (tts === engine) {
                tts = null
                initDeferred = null
            }
            throw cancelled
        } catch (error: Throwable) {
            TextToSpeech.ERROR
        }
        if (closed.get()) {
            engine.shutdown()
            if (tts === engine) {
                tts = null
                initDeferred = null
            }
            throw MiniAppBridgeException("runner_closed", "MiniApp speech engine is closed")
        }
        if (status != TextToSpeech.SUCCESS) {
            engine.shutdown()
            tts = null
            initDeferred = null
            throw MiniAppValidationException("Speech engine failed to initialize")
        }
        engine
    }

    suspend fun dispatch(method: String, params: JsonObject): JsonElement = mutex.withLock {
        when (method) {
            "speech.getVoices" -> withContext(Dispatchers.Main) { voicesJson(engine()) }
            "speech.speak" -> withContext(Dispatchers.Main) {
                val requestEpoch = foregroundEpoch
                requireForeground(requestEpoch)
                val speechEngine = engine()
                requireForeground(requestEpoch)
                speakLocked(speechEngine, params)
            }
            "speech.stop" -> withContext(Dispatchers.Main) {
                buildJsonObject { put("stopped", stopLocked(tts)) }
            }
            "speech.pause" -> withContext(Dispatchers.Main) { pauseLocked(tts) }
            "speech.resume" -> withContext(Dispatchers.Main) {
                val requestEpoch = foregroundEpoch
                requireForeground(requestEpoch)
                val speechEngine = engine()
                requireForeground(requestEpoch)
                resumeLocked(speechEngine)
            }
            else -> throw MiniAppValidationException("Unsupported speech method: $method")
        }
    }

    private fun voicesJson(engine: TextToSpeech): JsonElement {
        val voices = engine.voices.orEmpty().map { voice ->
            buildJsonObject {
                put("identifier", voice.name.take(120))
                put("name", voice.name.take(120))
                put("language", voice.locale.toLanguageTag().take(35))
                put("quality", voice.quality)
                put("latency", voice.latency)
            }
        }
        return JsonArray(voices)
    }

    private fun speakLocked(engine: TextToSpeech, params: JsonObject): JsonElement {
        val text = params.stringParam("text") ?: throw MiniAppValidationException("Missing parameter: text")
        if (text.isEmpty() || text.length > 4_000) {
            throw MiniAppValidationException("text must contain 1...4000 characters")
        }
        val rate = params.numberParam("rate") ?: 0.5f
        if (rate !in 0f..1f) throw MiniAppValidationException("rate must be in 0...1")
        val pitch = params.numberParam("pitch") ?: 1f
        if (pitch !in 0.5f..2f) throw MiniAppValidationException("pitch must be in 0.5...2")
        val volume = params.numberParam("volume") ?: 1f
        if (volume !in 0f..1f) throw MiniAppValidationException("volume must be in 0...1")
        params.stringParam("language")?.let { language ->
            val locale = runCatching { java.util.Locale.forLanguageTag(language) }.getOrNull()
            if (locale == null || engine.isLanguageAvailable(locale) == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw MiniAppValidationException("No speech voice is available for language $language.")
            }
            engine.language = locale
        }
        // Bridge contract is the iOS-style 0..1 rate (0.5 = normal); Android
        // setSpeechRate is a plain multiplier (1.0 = normal), so map across.
        engine.setSpeechRate(rate * 2f)
        engine.setPitch(pitch)
        currentText = text
        spokenUpTo = 0
        pausedRemainder = null
        currentVolume = volume
        val utteranceId = nextUtteranceId()
        activeUtteranceId = utteranceId
        val extras = Bundle()
        extras.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        val started = engine.speak(text, TextToSpeech.QUEUE_FLUSH, extras, utteranceId)
        if (started != TextToSpeech.SUCCESS) {
            activeUtteranceId = null
            throw MiniAppValidationException("Speech failed to start")
        }
        return buildJsonObject { put("speaking", true) }
    }

    private fun stopLocked(engine: TextToSpeech?): Boolean {
        val wasSpeaking = engine?.isSpeaking == true
        currentText = ""
        spokenUpTo = 0
        pausedRemainder = null
        activeUtteranceId = null
        engine?.stop()
        return wasSpeaking
    }

    private fun pauseLocked(engine: TextToSpeech?): JsonElement {
        if (engine == null || !engine.isSpeaking) {
            return buildJsonObject { put("paused", false) }
        }
        val remainder = currentText.substring(minOf(currentText.length, spokenUpTo))
        activeUtteranceId = null
        engine.stop()
        pausedRemainder = remainder.takeIf { it.isNotEmpty() }
        return buildJsonObject { put("paused", pausedRemainder != null) }
    }

    private fun resumeLocked(engine: TextToSpeech): JsonElement {
        val remainder = pausedRemainder
        if (remainder.isNullOrEmpty()) {
            return buildJsonObject { put("resumed", false) }
        }
        pausedRemainder = null
        currentText = remainder
        spokenUpTo = 0
        val utteranceId = nextUtteranceId()
        activeUtteranceId = utteranceId
        // Re-apply the original utterance volume: it is per-call state that
        // the engine does not retain between speak() invocations.
        val extras = Bundle()
        extras.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
        val started = engine.speak(remainder, TextToSpeech.QUEUE_FLUSH, extras, utteranceId)
        if (started != TextToSpeech.SUCCESS) {
            activeUtteranceId = null
            throw MiniAppValidationException("Speech failed to resume")
        }
        return buildJsonObject { put("resumed", true) }
    }

    /** Stops speech without waiting; called by the runner owner on background. */
    fun suspendForBackground() {
        runOnMain {
            foregroundEpoch++
            activeUtteranceId = null
            val engine = tts ?: return@runOnMain
            currentText = ""
            spokenUpTo = 0
            pausedRemainder = null
            engine.stop()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runOnMain {
            foregroundEpoch++
            activeUtteranceId = null
            val engine = tts
            tts = null
            initDeferred = null
            initStatus = TextToSpeech.ERROR
            currentText = ""
            spokenUpTo = 0
            pausedRemainder = null
            engine?.stop()
            engine?.shutdown()
        }
    }

    /** Runs immediately on the main thread, otherwise posts; never blocks main. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun requireForeground(requestEpoch: Long) {
        if (closed.get()) {
            throw MiniAppBridgeException("runner_closed", "MiniApp speech engine is closed")
        }
        if (requestEpoch != foregroundEpoch || !foregroundProvider()) {
            throw MiniAppBridgeException("not_foreground", "MiniApp runner is not in the foreground")
        }
    }

    private fun nextUtteranceId(): String {
        utteranceSequence += 1
        return "amber-miniapp-$utteranceSequence"
    }

    private fun finishUtteranceOnMain(utteranceId: String?) {
        postToMain {
            if (utteranceId != activeUtteranceId) return@postToMain
            spokenUpTo = currentText.length
            pausedRemainder = null
            activeUtteranceId = null
            onSpeechFinished()
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun JsonObject.stringParam(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.numberParam(key: String): Float? =
        this[key]?.jsonPrimitive?.floatOrNull

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
    }
}
