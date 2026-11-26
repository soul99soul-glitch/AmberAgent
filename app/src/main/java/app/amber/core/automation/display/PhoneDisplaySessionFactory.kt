package app.amber.core.automation.display

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import app.amber.agent.BuildConfig
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.getActiveAccessibilityController
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Factory opening either a [PhysicalDisplaySession] (unchanged physical path) or a
 * generation-bound [VirtualDisplaySession] gated by API 34+, Shizuku UID 2000, required flags,
 * and device-local [VirtualDisplayProbeStore] verification.
 *
 * When [PhoneDisplayMode.VIRTUAL] is requested and any gate fails, always returns
 * [DisplaySessionOutcome.Unavailable] — NEVER silently falls back to [PhysicalDisplaySession].
 */
class PhoneDisplaySessionFactory internal constructor(
    private val probeStore: VirtualDisplayProbeStore,
    private val displayBridge: DisplayBridge,
    private val virtualDisplayBuildAllowed: () -> Boolean,
    private val apiLevelProvider: () -> Int,
    private val secondaryActivitiesFeatureProvider: () -> Boolean,
    private val physicalControllerProvider: () -> AccessibilityController?,
    private val physicalSessionCreator: (String, AccessibilityController) -> PhysicalDisplaySession,
    private val virtualWindowsProvider: (Int) -> List<AccessibilityWindowInfo>,
    private val accessibilityServiceAvailableProvider: () -> Boolean,
    private val frameSourceCreator: (Int, Int) -> VirtualDisplayFrameSource,
    private val displayWatcherFactory: (displayId: Int, width: Int, height: Int, onInvalidated: (String) -> Unit) -> Closeable?,
) {

    constructor(
        context: Context,
        probeStore: VirtualDisplayProbeStore,
        displayBridge: DisplayBridge = ShizukuDisplayBridge(context),
    ) : this(
        probeStore = probeStore,
        displayBridge = displayBridge,
        virtualDisplayBuildAllowed = { BuildConfig.PHONE_USE_VIRTUAL_DISPLAY_ALLOWED },
        apiLevelProvider = { Build.VERSION.SDK_INT },
        secondaryActivitiesFeatureProvider = {
            try {
                context.packageManager.hasSystemFeature(
                    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
                )
            } catch (_: Throwable) {
                false
            }
        },
        physicalControllerProvider = { getActiveAccessibilityController() },
        physicalSessionCreator = { packageName, controller ->
            PhysicalDisplaySession(context, packageName, controller)
        },
        virtualWindowsProvider = { displayId ->
            AmberAccessibilityService.getActiveService()?.windowsOnDisplay(displayId).orEmpty()
        },
        accessibilityServiceAvailableProvider = { AmberAccessibilityService.getActiveService() != null },
        frameSourceCreator = { width, height -> VirtualDisplayFrameSource(width, height) },
        displayWatcherFactory = { displayId, expectedW, expectedH, onInvalidated ->
            PhoneDisplaySessionFactory.registerDisplayChangeWatcher(context, displayId, expectedW, expectedH, onInvalidated)
        },
    )

    private val generationCounter = AtomicLong(0L)

    suspend fun open(
        runId: String,
        packageName: String,
        requested: PhoneDisplayMode,
    ): DisplaySessionOutcome = when (requested) {
        PhoneDisplayMode.PHYSICAL -> openPhysical(packageName)
        PhoneDisplayMode.VIRTUAL -> openVirtualInternal(
            runId = runId,
            packageName = packageName,
            width = AmberDisplayUserService.DEFAULT_WIDTH,
            height = AmberDisplayUserService.DEFAULT_HEIGHT,
            dpi = AmberDisplayUserService.DEFAULT_DPI,
            requireProbeVerified = true,
        )
    }

    /**
     * Opens a virtual display session specifically for [IsolationProbeRunner], bypassing the
     * `probeStore.compatibility() == VERIFIED` precondition while keeping every security and
     * capability gate (API 34+, UID 2000, OWN_FOCUS, DESTROY_CONTENT_ON_REMOVAL) strictly enforced.
     */
    internal suspend fun openForProbe(
        runId: String,
        targetPackageToken: String,
        width: Int = AmberDisplayUserService.DEFAULT_WIDTH,
        height: Int = AmberDisplayUserService.DEFAULT_HEIGHT,
        dpi: Int = AmberDisplayUserService.DEFAULT_DPI,
    ): DisplaySessionOutcome = openVirtualInternal(
        runId = runId,
        packageName = targetPackageToken,
        width = width,
        height = height,
        dpi = dpi,
        requireProbeVerified = false,
    )

    private fun openPhysical(packageName: String): DisplaySessionOutcome {
        val controller = physicalControllerProvider()
            ?: return DisplaySessionOutcome.Unavailable("accessibility_service_unavailable")
        return DisplaySessionOutcome.Ready(physicalSessionCreator(packageName, controller))
    }

    private suspend fun openVirtualInternal(
        runId: String,
        packageName: String,
        width: Int,
        height: Int,
        dpi: Int,
        requireProbeVerified: Boolean,
    ): DisplaySessionOutcome {
        if (!virtualDisplayBuildAllowed()) {
            return DisplaySessionOutcome.Unavailable("virtual_display_disabled_by_build")
        }
        if (runId.isBlank() || packageName.isBlank()) {
            return DisplaySessionOutcome.Unavailable("invalid_run_or_package")
        }
        val localApi = apiLevelProvider()
        if (localApi < AmberDisplayUserService.MIN_API_LEVEL) {
            return DisplaySessionOutcome.Unavailable("api_below_34:$localApi")
        }
        if (!secondaryActivitiesFeatureProvider()) {
            return DisplaySessionOutcome.Unavailable("secondary_activities_unsupported")
        }
        if (requireProbeVerified) {
            when (val compat = probeStore.compatibility()) {
                VirtualDisplayCompatibility.VERIFIED -> Unit
                VirtualDisplayCompatibility.UNSUPPORTED ->
                    return DisplaySessionOutcome.Unavailable("device_unsupported")
                VirtualDisplayCompatibility.UNVERIFIED ->
                    return DisplaySessionOutcome.Unavailable("isolation_probe_unverified")
                VirtualDisplayCompatibility.FAILED ->
                    return DisplaySessionOutcome.Unavailable("isolation_probe_failed")
            }
        }
        if (!accessibilityServiceAvailableProvider()) {
            return DisplaySessionOutcome.Unavailable("accessibility_service_unavailable")
        }

        val sessionRef = AtomicReference<VirtualDisplaySession?>(null)
        val connected = displayBridge.connect { disconnectReason ->
            sessionRef.get()?.invalidate(disconnectReason)
        }.getOrElse { error ->
            return DisplaySessionOutcome.Unavailable(
                "shizuku_unavailable:${error.message ?: error.javaClass.simpleName}",
            )
        }

        val remoteUid = connected.uid()
        if (remoteUid != AmberDisplayUserService.SHELL_UID) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("invalid_shizuku_uid:$remoteUid")
        }

        val caps = connected.capabilities()
        if (caps.apiLevel < AmberDisplayUserService.MIN_API_LEVEL) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("service_api_below_34:${caps.apiLevel}")
        }
        if (caps.uid != AmberDisplayUserService.SHELL_UID) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("invalid_shizuku_uid:${caps.uid}")
        }
        if (!caps.ownFocus) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("missing_own_focus_flag")
        }
        if (!caps.destroyContentOnRemoval) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("missing_destroy_content_on_removal_flag")
        }
        if (!caps.secondaryActivities) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("secondary_activities_unsupported")
        }

        val frameSource = try {
            frameSourceCreator(width, height)
        } catch (t: Throwable) {
            connected.disconnect()
            return DisplaySessionOutcome.Unavailable("frame_source_init_failed:${t.message}")
        }

        val generation = generationCounter.incrementAndGet()
        val displayId = connected.createDisplay(
            runId = runId,
            generation = generation,
            width = width,
            height = height,
            dpi = dpi,
            surface = frameSource.surface,
        )
        if (displayId <= Display.DEFAULT_DISPLAY) {
            runCatching { frameSource.close() }
            connected.disconnect()
            val detail = if (displayId < 0) {
                AmberDisplayUserService.describeError(displayId)
            } else {
                "invalid_display_id_default"
            }
            return DisplaySessionOutcome.Unavailable("create_display_failed:$detail")
        }

        val watcher = displayWatcherFactory(displayId, width, height) { changeReason ->
            sessionRef.get()?.invalidate(changeReason)
        }

        val session = VirtualDisplaySession(
            runId = runId,
            packageName = packageName,
            displayId = displayId,
            generation = generation,
            width = width,
            height = height,
            dpi = dpi,
            bridge = connected,
            frameSource = frameSource,
            windowsProvider = virtualWindowsProvider,
            displayWatcherCloser = watcher,
        )
        sessionRef.set(session)
        return DisplaySessionOutcome.Ready(session)
    }

    companion object {
        private fun registerDisplayChangeWatcher(
            context: Context,
            targetDisplayId: Int,
            expectedWidth: Int,
            expectedHeight: Int,
            onInvalidated: (String) -> Unit,
        ): Closeable? {
            val dm = context.getSystemService(DisplayManager::class.java) ?: return null
            val initialRotation = dm.getDisplay(targetDisplayId)?.rotation
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) {
                    if (displayId == targetDisplayId) {
                        onInvalidated("virtual_display_removed")
                    }
                }

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != targetDisplayId) return
                    val display = dm.getDisplay(displayId)
                    if (display == null) {
                        onInvalidated("virtual_display_missing")
                        return
                    }
                    val mode = display.mode
                    val rotationChanged = initialRotation != null && display.rotation != initialRotation
                    val sizeChanged = mode != null &&
                        (mode.physicalWidth != expectedWidth || mode.physicalHeight != expectedHeight)
                    if (rotationChanged || sizeChanged) {
                        onInvalidated("virtual_display_geometry_changed")
                    }
                }
            }
            val handler = Handler(Looper.getMainLooper())
            dm.registerDisplayListener(listener, handler)
            return Closeable {
                runCatching { dm.unregisterDisplayListener(listener) }
            }
        }
    }
}
