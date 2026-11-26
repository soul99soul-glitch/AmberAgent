package app.amber.core.automation.display

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * In-process telemetry shared between [IsolationProbeMainActivity] (Display 0),
 * [IsolationProbeTargetActivity] (virtual display), and [IsolationProbeRunner].
 */
internal object IsolationProbeChannel {
    private val activeToken = AtomicReference<String>("")
    private val markerRgb = AtomicInteger(0xFF19D3A2.toInt())
    private val expectedDisplayId = AtomicInteger(Display.INVALID_DISPLAY)

    private val injectionBurstActive = AtomicBoolean(false)
    private val disconnectPhaseActive = AtomicBoolean(false)

    private val targetResumedDisplayId = AtomicInteger(Display.INVALID_DISPLAY)
    private val targetTapCount = AtomicInteger(0)
    private val targetText = AtomicReference<String>("")

    private val mainScreenInjectedEvents = AtomicInteger(0)
    private val mainFocusLost = AtomicBoolean(false)
    private val residualAfterDisconnect = AtomicInteger(0)
    private val leakedToDefaultOnDestroy = AtomicBoolean(false)

    private val markerPalette = intArrayOf(
        0xFFE63946.toInt(),
        0xFF2A9D8F.toInt(),
        0xFFE9C46A.toInt(),
        0xFF457B9D.toInt(),
        0xFF9B5DE5.toInt(),
        0xFFF15BB5.toInt(),
    )

    fun resetForNewRun(): Pair<String, Int> {
        val token = UUID.randomUUID().toString().take(8)
        val rgb = markerPalette[Random.nextInt(markerPalette.size)]
        activeToken.set(token)
        markerRgb.set(rgb)
        expectedDisplayId.set(Display.INVALID_DISPLAY)
        injectionBurstActive.set(false)
        disconnectPhaseActive.set(false)
        targetResumedDisplayId.set(Display.INVALID_DISPLAY)
        targetTapCount.set(0)
        targetText.set("")
        mainScreenInjectedEvents.set(0)
        mainFocusLost.set(false)
        residualAfterDisconnect.set(0)
        leakedToDefaultOnDestroy.set(false)
        return token to rgb
    }

    fun currentToken(): String = activeToken.get()
    fun currentMarkerRgb(): Int = markerRgb.get()

    fun setExpectedDisplayId(displayId: Int) {
        expectedDisplayId.set(displayId)
    }

    fun setInjectionBurstActive(active: Boolean) {
        injectionBurstActive.set(active)
    }

    fun enterDisconnectPhase() {
        injectionBurstActive.set(false)
        disconnectPhaseActive.set(true)
    }

    fun recordTargetResumed(displayId: Int) {
        targetResumedDisplayId.set(displayId)
        if (displayId == Display.DEFAULT_DISPLAY) {
            leakedToDefaultOnDestroy.set(true)
        }
    }

    fun recordTargetTap(displayId: Int) {
        if (disconnectPhaseActive.get()) {
            residualAfterDisconnect.incrementAndGet()
            return
        }
        val expected = expectedDisplayId.get()
        if (displayId > Display.DEFAULT_DISPLAY && (expected == Display.INVALID_DISPLAY || displayId == expected)) {
            targetTapCount.incrementAndGet()
        } else if (displayId == Display.DEFAULT_DISPLAY) {
            mainScreenInjectedEvents.incrementAndGet()
        }
    }

    fun recordTargetText(text: String) {
        targetText.set(text)
    }

    fun recordMainScreenInjectedTouch() {
        if (disconnectPhaseActive.get()) {
            residualAfterDisconnect.incrementAndGet()
        } else if (injectionBurstActive.get()) {
            mainScreenInjectedEvents.incrementAndGet()
        }
    }

    fun recordMainFocusLost() {
        if (injectionBurstActive.get() || targetResumedDisplayId.get() > Display.DEFAULT_DISPLAY) {
            mainFocusLost.set(true)
        }
    }

    fun recordLeakedToDefaultDisplay() {
        leakedToDefaultOnDestroy.set(true)
    }

    fun targetDisplayId(): Int = targetResumedDisplayId.get()
    fun targetTapCount(): Int = targetTapCount.get()
    fun targetText(): String = targetText.get()
    fun mainScreenInjectedEvents(): Int = mainScreenInjectedEvents.get()
    fun isMainFocusStable(): Boolean = !mainFocusLost.get()
    fun residualAfterDisconnect(): Int = residualAfterDisconnect.get()
    fun leakedToDefaultOnDestroy(): Boolean = leakedToDefaultOnDestroy.get()
}

/**
 * Orchestrates the device-local dual-display isolation self-test (R3 L12).
 *
 * Procedure:
 * 1. Create a virtual display (720x1600 / 320dpi) via Shizuku UID 2000 with all mandatory flags.
 * 2. Launch [IsolationProbeTargetActivity] onto that virtual display.
 * 3. Verify rendered frame marker color via [VirtualDisplayFrameSource].
 * 4. Inject 20 taps + 1 Chinese `ACTION_SET_TEXT` into the virtual display while Display 0 holds focus.
 * 5. Disconnect Shizuku / tear down virtual display, verify zero post-disconnect residual events and
 *    zero Activity leakage onto Display 0.
 * 6. Persist the [IsolationProbeResult] into [VirtualDisplayProbeStore].
 */
class IsolationProbeRunner(
    private val context: Context,
    private val sessionFactory: PhoneDisplaySessionFactory,
    private val probeStore: VirtualDisplayProbeStore,
) {

    suspend fun run(
        hostActivity: Activity? = null,
        onProgress: (String) -> Unit = {},
    ): IsolationProbeResult {
        val (token, expectedRgb) = IsolationProbeChannel.resetForNewRun()
        val runId = "isolation-probe-$token"
        onProgress("1/6 创建虚拟屏 (720x1600 / 320dpi)...")

        val targetToken = AmberDisplayUserService.probeTargetToken(context.packageName)
        val outcome = sessionFactory.openForProbe(
            runId = runId,
            targetPackageToken = targetToken,
            width = AmberDisplayUserService.DEFAULT_WIDTH,
            height = AmberDisplayUserService.DEFAULT_HEIGHT,
            dpi = AmberDisplayUserService.DEFAULT_DPI,
        )
        val session = when (outcome) {
            is DisplaySessionOutcome.Ready -> outcome.session as? VirtualDisplaySession
            is DisplaySessionOutcome.Unavailable -> {
                return recordAndReturn(
                    IsolationProbeResult(
                        passed = false,
                        mainScreenInjectedEvents = 0,
                        targetReceivedEvents = 0,
                        textReadBackOk = false,
                        frameMarkerOk = false,
                        focusStable = true,
                        residualAfterDisconnect = 0,
                        leakedToDefaultOnDestroy = false,
                        detail = "open_virtual_display_failed:${outcome.reason}",
                    ),
                )
            }
        } ?: return recordAndReturn(
            IsolationProbeResult(
                passed = false,
                mainScreenInjectedEvents = 0,
                targetReceivedEvents = 0,
                textReadBackOk = false,
                frameMarkerOk = false,
                focusStable = true,
                residualAfterDisconnect = 0,
                leakedToDefaultOnDestroy = false,
                detail = "unexpected_non_virtual_session",
            ),
        )

        IsolationProbeChannel.setExpectedDisplayId(session.displayId)
        var postDisconnectTapRejected = true
        var detail = "ok"

        try {
            onProgress("2/6 在虚拟屏 displayId=${session.displayId} 启动探针页...")
            val launchedViaService = session.launchTarget()
            if (!launchedViaService) {
                launchProbeTargetFromHost(hostActivity, session.displayId, token)
            }

            val resumedOnVirtual = waitUntil(timeoutMs = TARGET_RESUME_TIMEOUT_MS, pollMs = 100L) {
                IsolationProbeChannel.targetDisplayId() == session.displayId
            }
            if (!resumedOnVirtual) {
                detail = "target_did_not_resume_on_virtual_display(actual=${IsolationProbeChannel.targetDisplayId()})"
            }

            onProgress("3/6 校验虚拟屏帧缓冲随机色标...")
            val frameMarkerOk = if (resumedOnVirtual) {
                waitUntil(timeoutMs = FRAME_MARKER_TIMEOUT_MS, pollMs = 100L) {
                    val stats = session.frameSource.latestStats.value
                    stats != null &&
                        !stats.unobservable &&
                        session.frameSource.containsMarkerColor(expectedRgb)
                }
            } else {
                false
            }

            onProgress("4/6 注入 20 次点击 + 1 次中文 ACTION_SET_TEXT...")
            IsolationProbeChannel.setInjectionBurstActive(true)
            val tapX = session.width * 0.5f
            val tapY = session.height * 0.45f
            if (resumedOnVirtual) {
                repeat(IsolationProbeResult.REQUIRED_PROBE_TAPS) {
                    session.controller.tap(tapX, tapY, durationMillis = 40L)
                    delay(45L)
                }
                delay(250L)
            }

            val textReadBackOk = if (resumedOnVirtual) {
                val controllerOk = withContext(Dispatchers.Main) {
                    session.controller.setFocusedText(PROBE_CHINESE_TEXT)
                }
                val targetMatched = waitUntil(timeoutMs = 1_500L, pollMs = 80L) {
                    IsolationProbeChannel.targetText() == PROBE_CHINESE_TEXT
                }
                controllerOk && targetMatched
            } else {
                false
            }
            IsolationProbeChannel.setInjectionBurstActive(false)

            onProgress("5/6 断开 Shizuku 并校验无残留输入与无主屏回流...")
            IsolationProbeChannel.enterDisconnectPhase()
            session.close("isolation_probe_disconnect_test")
            // Attempt one extra tap after disconnect — MUST be rejected.
            postDisconnectTapRejected = !session.controller.tap(tapX, tapY, durationMillis = 40L)
            delay(POST_DESTROY_SETTLE_MS)

            val mainInjected = IsolationProbeChannel.mainScreenInjectedEvents()
            val targetReceived = IsolationProbeChannel.targetTapCount()
            val focusStable = IsolationProbeChannel.isMainFocusStable()
            val residual = IsolationProbeChannel.residualAfterDisconnect() + if (postDisconnectTapRejected) 0 else 1
            val leakedToDefault = IsolationProbeChannel.leakedToDefaultOnDestroy()

            val passed = resumedOnVirtual &&
                mainInjected == 0 &&
                targetReceived >= IsolationProbeResult.REQUIRED_PROBE_TAPS &&
                textReadBackOk &&
                frameMarkerOk &&
                focusStable &&
                residual == 0 &&
                !leakedToDefault

            if (!passed && detail == "ok") {
                detail = buildFailureDetail(
                    mainInjected = mainInjected,
                    targetReceived = targetReceived,
                    textReadBackOk = textReadBackOk,
                    frameMarkerOk = frameMarkerOk,
                    focusStable = focusStable,
                    residual = residual,
                    leakedToDefault = leakedToDefault,
                )
            }

            onProgress("6/6 写入探测结论...")
            return recordAndReturn(
                IsolationProbeResult(
                    passed = passed,
                    mainScreenInjectedEvents = mainInjected,
                    targetReceivedEvents = targetReceived,
                    textReadBackOk = textReadBackOk,
                    frameMarkerOk = frameMarkerOk,
                    focusStable = focusStable,
                    residualAfterDisconnect = residual,
                    leakedToDefaultOnDestroy = leakedToDefault,
                    detail = detail,
                ),
            )
        } finally {
            IsolationProbeChannel.setInjectionBurstActive(false)
            runCatching { session.close("isolation_probe_finally") }
        }
    }

    private suspend fun launchProbeTargetFromHost(
        hostActivity: Activity?,
        displayId: Int,
        token: String,
    ) {
        if (displayId <= Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(context, IsolationProbeTargetActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_PROBE_TOKEN, token)
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        withContext(Dispatchers.Main) {
            runCatching {
                val launcher: Context = hostActivity ?: context
                launcher.startActivity(intent, options.toBundle())
            }.onFailure {
                Log.w(TAG, "Host-side launch onto virtual display $displayId failed", it)
            }
        }
    }

    private suspend fun waitUntil(
        timeoutMs: Long,
        pollMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(pollMs)
        }
        return predicate()
    }

    private fun recordAndReturn(result: IsolationProbeResult): IsolationProbeResult {
        probeStore.record(result)
        Log.i(TAG, "IsolationProbeResult=$result compatibility=${probeStore.compatibility()}")
        return result
    }

    private fun buildFailureDetail(
        mainInjected: Int,
        targetReceived: Int,
        textReadBackOk: Boolean,
        frameMarkerOk: Boolean,
        focusStable: Boolean,
        residual: Int,
        leakedToDefault: Boolean,
    ): String = buildList {
        if (mainInjected != 0) add("mainScreenInjectedEvents=$mainInjected")
        if (targetReceived < IsolationProbeResult.REQUIRED_PROBE_TAPS) {
            add("targetReceivedEvents=$targetReceived/${IsolationProbeResult.REQUIRED_PROBE_TAPS}")
        }
        if (!textReadBackOk) add("textReadBackOk=false")
        if (!frameMarkerOk) add("frameMarkerOk=false")
        if (!focusStable) add("focusStable=false")
        if (residual != 0) add("residualAfterDisconnect=$residual")
        if (leakedToDefault) add("leakedToDefaultOnDestroy=true")
    }.joinToString(",")

    companion object {
        private const val TAG = "IsolationProbe"
        const val EXTRA_PROBE_TOKEN = "probe_token"
        const val PROBE_CHINESE_TEXT = "琥珀双屏隔离测试中文"
        private const val TARGET_RESUME_TIMEOUT_MS = 4_000L
        private const val FRAME_MARKER_TIMEOUT_MS = 2_500L
        private const val POST_DESTROY_SETTLE_MS = 600L
    }
}
