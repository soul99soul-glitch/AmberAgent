package app.amber.core.automation.display

import android.graphics.Rect
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityWindowInfo
import app.amber.core.automation.AmberAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Generation-bound virtual display session.
 *
 * Fault & isolation semantics (R3 L5/L6/L7/L8):
 * - Bound to a single `(runId, packageName, displayId, generation)`.
 * - Any disconnect, display removal, dimension/rotation change, unobservable screen, or explicit close
 *   immediately invalidates `generation` and rejects all subsequent launch/input calls.
 * - NEVER hot-downgrades to a physical display session.
 * - Distinguishes pre-dispatch interruption (`SAFE_PAUSED`) from mid-dispatch disconnection
 *   (`OUTCOME_UNKNOWN`) so callers reconcile instead of replaying.
 */
class VirtualDisplaySession internal constructor(
    val runId: String,
    val packageName: String,
    override val displayId: Int,
    override val generation: Long,
    val width: Int,
    val height: Int,
    val dpi: Int,
    private val bridge: ConnectedDisplayBridge,
    val frameSource: VirtualDisplayFrameSource,
    private val windowsProvider: (Int) -> List<AccessibilityWindowInfo> = { id ->
        AmberAccessibilityService.getActiveService()?.windowsOnDisplay(id).orEmpty()
    },
    private val displayWatcherCloser: Closeable? = null,
    private val deviceReadyForInput: () -> Boolean = { true },
) : PhoneDisplaySession {

    init {
        require(displayId > Display.DEFAULT_DISPLAY) {
            "VirtualDisplaySession requires displayId > DEFAULT_DISPLAY (0), got $displayId"
        }
        require(generation > 0L) {
            "VirtualDisplaySession requires positive generation, got $generation"
        }
    }

    override val mode: PhoneDisplayMode = PhoneDisplayMode.VIRTUAL

    private val generationValid = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val inFlightDispatch = AtomicBoolean(false)
    private val invalidationReasonRef = AtomicReference<String?>(null)

    private val _state = MutableStateFlow(VirtualDisplaySessionState.OPEN)
    val state: StateFlow<VirtualDisplaySessionState> = _state.asStateFlow()

    val isGenerationValid: Boolean
        get() {
            if (!generationValid.get() || closed.get()) return false
            if (deviceReadyForInput()) return true
            invalidate("device_locked_or_screen_off")
            return false
        }

    val invalidationReason: String?
        get() = invalidationReasonRef.get()

    override val controller: VirtualDisplayAccessibilityController =
        VirtualDisplayAccessibilityController(
            displayId = displayId,
            generation = generation,
            expectedPackageName = packageName,
            isGenerationValid = { isGenerationValid },
            displayBoundsProvider = { Rect(0, 0, width, height) },
            motionInjector = { event -> injectMotion(event) },
            keyInjector = { event -> injectKey(event) },
            windowsProvider = windowsProvider,
        )

    override suspend fun launchTarget(): Boolean {
        if (!isGenerationValid || displayId <= Display.DEFAULT_DISPLAY || packageName.isBlank()) {
            return false
        }
        if (!packageName.endsWith(AmberDisplayUserService.PROBE_TARGET_SUFFIX)) {
            mainDisplayLaunchConflict()?.let { reason ->
                invalidate(reason)
                return false
            }
        }
        return runGuardedDispatch {
            bridge.launchOnDisplay(generation, displayId, packageName) == 0
        }
    }

    private fun mainDisplayLaunchConflict(): String? {
        val windows = runCatching { windowsProvider(Display.DEFAULT_DISPLAY) }.getOrDefault(emptyList())
        try {
            var observed = false
            for (window in windows) {
                if (window.displayId != Display.DEFAULT_DISPLAY ||
                    window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    !(window.isActive || window.isFocused)
                ) continue
                val root = window.root ?: return "main_display_unobservable"
                val foreground = try {
                    root.packageName?.toString()
                } finally {
                    runCatching { root.recycle() }
                }
                if (foreground.isNullOrBlank()) return "main_display_unobservable"
                if (foreground == packageName) return "target_already_foreground_on_main_display"
                observed = true
            }
            return if (observed) null else "main_display_unobservable"
        } finally {
            windows.forEach { window -> runCatching { window.recycle() } }
        }
    }

    fun injectMotion(event: MotionEvent): Boolean {
        val observedPackageName = controller.observedTargetPackage() ?: return false
        return runGuardedDispatch {
            bridge.injectMotion(generation, displayId, observedPackageName, event)
        }
    }

    fun injectKey(event: KeyEvent): Boolean {
        val observedPackageName = controller.observedTargetPackage() ?: return false
        return runGuardedDispatch {
            bridge.injectKey(generation, displayId, observedPackageName, event)
        }
    }

    /**
     * Called when Shizuku disconnects, the virtual display is removed, or its dimensions/rotation change.
     * Immediately invalidates `generation`; never replays or hot-downgrades to physical.
     */
    fun invalidate(reason: String, targetState: VirtualDisplaySessionState? = null) {
        val wasValid = generationValid.compareAndSet(true, false)
        invalidationReasonRef.compareAndSet(null, reason.ifBlank { "generation_invalidated" })
        controller.invalidateLatestSnapshot(invalidationReasonRef.get() ?: "generation_invalidated")
        if (!wasValid) return

        val nextState = targetState ?: if (inFlightDispatch.get()) {
            VirtualDisplaySessionState.OUTCOME_UNKNOWN
        } else {
            VirtualDisplaySessionState.SAFE_PAUSED
        }
        if (_state.value != VirtualDisplaySessionState.CLOSED) {
            _state.value = nextState
        }
        teardownResources(reason)
    }

    /**
     * Called when an all-black/blank or sensitive context makes the virtual screen unobservable (R3 L6).
     * Immediately halts observation & input and clears resources.
     */
    suspend fun markUnobservable(reason: String) {
        invalidate(reason, targetState = VirtualDisplaySessionState.UNOBSERVABLE)
        teardownResources(reason)
    }

    override suspend fun close(reason: String) {
        invalidate(reason, targetState = VirtualDisplaySessionState.CLOSED)
        _state.value = VirtualDisplaySessionState.CLOSED
        teardownResources(reason)
    }

    private fun teardownResources(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { displayWatcherCloser?.close() }
        runCatching { bridge.release(generation, reason) }
        runCatching { frameSource.close() }
        runCatching { bridge.disconnect() }
    }

    private inline fun runGuardedDispatch(block: () -> Boolean): Boolean {
        if (!isGenerationValid) return false
        inFlightDispatch.set(true)
        return try {
            val result = block()
            // If disconnected mid-dispatch, never claim success cleanly without signaling unknown state.
            if (!isGenerationValid) {
                if (_state.value != VirtualDisplaySessionState.CLOSED &&
                    _state.value != VirtualDisplaySessionState.UNOBSERVABLE
                ) {
                    _state.value = VirtualDisplaySessionState.OUTCOME_UNKNOWN
                }
                false
            } else {
                result
            }
        } catch (t: Throwable) {
            invalidate("dispatch_exception:${t.javaClass.simpleName}", VirtualDisplaySessionState.OUTCOME_UNKNOWN)
            false
        } finally {
            inFlightDispatch.set(false)
        }
    }
}
