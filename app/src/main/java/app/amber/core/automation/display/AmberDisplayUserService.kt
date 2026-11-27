package app.amber.core.automation.display

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.util.Log
import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Shizuku UserService running in the privileged shell process (UID 2000 ONLY).
 *
 * Security & isolation invariants:
 * - Rejects any process UID != 2000 (root UID 0 and ordinary app UIDs are both denied).
 * - Requires API 34+ and ALL mandatory virtual display flags:
 *   PUBLIC | OWN_CONTENT_ONLY | OWN_FOCUS | DESTROY_CONTENT_ON_REMOVAL.
 *   Never uses AUTO_MIRROR or SECURE, and never silently downgrades any required flag.
 * - Rejects DEFAULT_DISPLAY (0) for any launch or input injection.
 * - Verifies setDisplayId(displayId) via immediate reflective getDisplayId() readback before
 *   every single MotionEvent and KeyEvent injection; mismatch immediately aborts injection.
 * - Only accepts typed package names (or the hardcoded internal isolation probe target token);
 *   exposes no shell execution, arbitrary Intent, or URI routing.
 */
@Keep
class AmberDisplayUserService internal constructor(
    private val backend: DisplayServiceBackend,
    private val onDestroyProcess: () -> Unit,
) : IAmberDisplayService.Stub() {

    @Keep
    constructor() : this(
        backend = AndroidDisplayServiceBackend(context = null),
        onDestroyProcess = { exitProcess(0) },
    )

    @Keep
    constructor(context: Context) : this(
        backend = AndroidDisplayServiceBackend(context = context),
        onDestroyProcess = { exitProcess(0) },
    )

    private val lock = Any()
    private var activeDisplay: ActiveVirtualDisplay? = null
    private var destroyed: Boolean = false

    private data class ActiveVirtualDisplay(
        val runId: String,
        val generation: Long,
        val displayId: Int,
        val handle: VirtualDisplayHandle,
        val authorizedPackageName: String,
        var launched: Boolean = false,
    )

    override fun uid(): Int = backend.uid()

    override fun capabilities(): Bundle {
        val caps = backend.probeCapabilities()
        return Bundle().apply {
            putInt(CAP_API_LEVEL, caps.apiLevel)
            putInt(CAP_UID, caps.uid)
            putBoolean(CAP_OWN_FOCUS, caps.ownFocus)
            putBoolean(CAP_DESTROY_CONTENT_ON_REMOVAL, caps.destroyContentOnRemoval)
            putBoolean(CAP_TRUSTED, caps.trusted)
            putBoolean(CAP_SECONDARY_ACTIVITIES, caps.secondaryActivities)
        }
    }

    override fun createDisplay(
        runId: String?,
        authorizedPackageName: String?,
        generation: Long,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
    ): Int = synchronized(lock) {
        if (destroyed) return ERR_DESTROYED
        if (backend.uid() != SHELL_UID) {
            Log.w(TAG, "Refusing createDisplay: uid=${backend.uid()} != $SHELL_UID")
            return ERR_INVALID_UID
        }
        val caps = backend.probeCapabilities()
        if (caps.apiLevel < MIN_API_LEVEL) {
            return ERR_API_UNSUPPORTED
        }
        if (runId.isNullOrBlank() || generation <= 0L || width <= 0 || height <= 0 || dpi <= 0 || surface == null || !surface.isValid) {
            return ERR_INVALID_ARGS
        }
        if (authorizedPackageName == null || !isAllowedLaunchTarget(authorizedPackageName)) {
            return ERR_INVALID_PACKAGE
        }
        if (!caps.secondaryActivities) {
            return ERR_NO_SECONDARY_ACTIVITIES
        }
        val resolvedFlags = backend.resolveVirtualDisplayFlags()
        val ownFocusFlag = resolvedFlags.ownFocusFlag
        val destroyContentFlag = resolvedFlags.destroyContentOnRemovalFlag
        if (ownFocusFlag == null || destroyContentFlag == null) {
            return ERR_MISSING_REQUIRED_FLAG
        }

        val requiredFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            ownFocusFlag or
            destroyContentFlag

        // Hard guard: AUTO_MIRROR and SECURE must never be set.
        if ((requiredFlags and FORBIDDEN_FLAGS_MASK) != 0) {
            return ERR_FORBIDDEN_FLAG
        }

        releaseActiveLocked("replaced_by_new_display")

        val trustedFlag = resolvedFlags.trustedFlag?.takeIf { (it and FORBIDDEN_FLAGS_MASK) == 0 }
        val created = if (trustedFlag != null) {
            when (val withTrusted = backend.createVirtualDisplay("amber_vd_${generation}", width, height, dpi, surface, requiredFlags or trustedFlag)) {
                is CreateDisplayBackendResult.Created -> withTrusted
                is CreateDisplayBackendResult.FlagRejected -> {
                    // TRUSTED is optional; retry strictly with ALL required flags intact (no downgrade of required flags).
                    backend.createVirtualDisplay("amber_vd_${generation}", width, height, dpi, surface, requiredFlags)
                }
                is CreateDisplayBackendResult.Failed -> withTrusted
            }
        } else {
            backend.createVirtualDisplay("amber_vd_${generation}", width, height, dpi, surface, requiredFlags)
        }

        return when (created) {
            is CreateDisplayBackendResult.Created -> {
                val displayId = created.handle.displayId
                if (displayId <= Display.DEFAULT_DISPLAY) {
                    runCatching { created.handle.release() }
                    ERR_INVALID_DISPLAY_ID
                } else {
                    activeDisplay = ActiveVirtualDisplay(
                        runId = runId,
                        generation = generation,
                        displayId = displayId,
                        handle = created.handle,
                        authorizedPackageName = authorizedPackageName,
                    )
                    displayId
                }
            }
            is CreateDisplayBackendResult.FlagRejected -> ERR_FLAG_REJECTED
            is CreateDisplayBackendResult.Failed -> ERR_CREATE_FAILED
        }
    }

    override fun launchOnDisplay(generation: Long, displayId: Int, packageName: String?): Int = synchronized(lock) {
        if (destroyed) return ERR_DESTROYED
        if (backend.uid() != SHELL_UID) return ERR_INVALID_UID
        if (displayId <= Display.DEFAULT_DISPLAY) return ERR_INVALID_DISPLAY_ID
        val active = activeDisplay ?: return ERR_STALE_GENERATION
        if (active.generation != generation || active.displayId != displayId) {
            return ERR_STALE_GENERATION
        }
        val target = packageName.orEmpty()
        if (target.isEmpty() || !isAllowedLaunchTarget(target)) {
            return ERR_INVALID_PACKAGE
        }
        if (target != active.authorizedPackageName) {
            return ERR_PACKAGE_MISMATCH
        }
        active.launched = backend.launchPackageOnDisplay(displayId, target)
        if (active.launched) {
            0
        } else {
            ERR_LAUNCH_FAILED
        }
    }

    override fun injectMotion(generation: Long, displayId: Int, observedPackageName: String?, event: MotionEvent?): Boolean {
        if (event == null) return false
        return injectInputInternal(generation, displayId, observedPackageName, event)
    }

    override fun injectKey(generation: Long, displayId: Int, observedPackageName: String?, event: KeyEvent?): Boolean {
        if (event == null) return false
        return injectInputInternal(generation, displayId, observedPackageName, event)
    }

    private fun injectInputInternal(generation: Long, displayId: Int, observedPackageName: String?, event: InputEvent): Boolean = synchronized(lock) {
        if (destroyed) return false
        if (backend.uid() != SHELL_UID) return false
        if (displayId <= Display.DEFAULT_DISPLAY) return false
        val active = activeDisplay ?: return false
        if (active.generation != generation || active.displayId != displayId) {
            return false
        }
        val target = active.authorizedPackageName
        // The non-exported probe Activity can also be launched by its own app UID.
        if (!active.launched && target != probeTargetToken(app.amber.agent.BuildConfig.APPLICATION_ID)) return false
        if (observedPackageName != target.removeSuffix(PROBE_TARGET_SUFFIX)) return false
        // Mandatory setDisplayId + immediate readback verification before injection.
        val verifiedDisplayId = backend.setAndReadBackDisplayId(event, displayId)
        if (verifiedDisplayId != displayId || verifiedDisplayId <= Display.DEFAULT_DISPLAY) {
            Log.w(TAG, "Aborting input injection: displayId readback mismatch expected=$displayId actual=$verifiedDisplayId")
            return false
        }
        return backend.injectInputEvent(event)
    }

    override fun release(generation: Long, reason: String?) {
        synchronized(lock) {
            val active = activeDisplay ?: return
            if (active.generation == generation) {
                releaseActiveLocked(reason.orEmpty())
            }
        }
    }

    override fun destroy() {
        synchronized(lock) {
            destroyed = true
            releaseActiveLocked("service_destroy")
        }
        onDestroyProcess()
    }

    private fun releaseActiveLocked(reason: String) {
        val active = activeDisplay ?: return
        activeDisplay = null
        runCatching { active.handle.release() }
            .onFailure { Log.w(TAG, "Failed releasing virtual display ${active.displayId} ($reason)", it) }
    }

    companion object {
        private const val TAG = "AmberDisplayUserService"
        const val SHELL_UID = 2000
        const val MIN_API_LEVEL = 34
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1600
        const val DEFAULT_DPI = 320

        const val CAP_API_LEVEL = "apiLevel"
        const val CAP_UID = "uid"
        const val CAP_OWN_FOCUS = "ownFocus"
        const val CAP_DESTROY_CONTENT_ON_REMOVAL = "destroyContentOnRemoval"
        const val CAP_TRUSTED = "trusted"
        const val CAP_SECONDARY_ACTIVITIES = "secondaryActivities"

        const val PROBE_TARGET_SUFFIX = ":isolation_probe_target"

        const val ERR_INVALID_UID = -1
        const val ERR_API_UNSUPPORTED = -2
        const val ERR_INVALID_ARGS = -3
        const val ERR_NO_SECONDARY_ACTIVITIES = -4
        const val ERR_MISSING_REQUIRED_FLAG = -5
        const val ERR_FLAG_REJECTED = -6
        const val ERR_INVALID_DISPLAY_ID = -7
        const val ERR_CREATE_FAILED = -8
        const val ERR_FORBIDDEN_FLAG = -9
        const val ERR_STALE_GENERATION = -10
        const val ERR_INVALID_PACKAGE = -11
        const val ERR_LAUNCH_FAILED = -12
        const val ERR_PACKAGE_MISMATCH = -13
        const val ERR_DESTROYED = -14

        private const val FORBIDDEN_FLAGS_MASK =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE

        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

        fun probeTargetToken(appPackageName: String): String = "$appPackageName$PROBE_TARGET_SUFFIX"

        internal fun isAllowedLaunchTarget(target: String): Boolean {
            return PACKAGE_NAME_REGEX.matches(target) || target == probeTargetToken(app.amber.agent.BuildConfig.APPLICATION_ID)
        }

        fun describeError(code: Int): String = when (code) {
            ERR_INVALID_UID -> "invalid_uid_not_shell_2000"
            ERR_API_UNSUPPORTED -> "api_below_34"
            ERR_INVALID_ARGS -> "invalid_display_arguments"
            ERR_NO_SECONDARY_ACTIVITIES -> "secondary_activities_unsupported"
            ERR_MISSING_REQUIRED_FLAG -> "missing_required_display_flag"
            ERR_FLAG_REJECTED -> "display_flag_rejected"
            ERR_INVALID_DISPLAY_ID -> "invalid_display_id"
            ERR_CREATE_FAILED -> "virtual_display_create_failed"
            ERR_FORBIDDEN_FLAG -> "forbidden_display_flag"
            ERR_STALE_GENERATION -> "stale_generation"
            ERR_INVALID_PACKAGE -> "invalid_package_name"
            ERR_LAUNCH_FAILED -> "launch_on_display_failed"
            ERR_PACKAGE_MISMATCH -> "package_mismatch_for_generation"
            ERR_DESTROYED -> "user_service_destroyed"
            else -> "unknown_error_$code"
        }
    }
}

internal interface VirtualDisplayHandle {
    val displayId: Int
    fun release()
}

internal data class ResolvedDisplayFlags(
    val ownFocusFlag: Int?,
    val destroyContentOnRemovalFlag: Int?,
    val trustedFlag: Int?,
)

internal sealed interface CreateDisplayBackendResult {
    data class Created(val handle: VirtualDisplayHandle) : CreateDisplayBackendResult
    data class FlagRejected(val detail: String) : CreateDisplayBackendResult
    data class Failed(val detail: String) : CreateDisplayBackendResult
}

internal interface DisplayServiceBackend {
    fun uid(): Int
    fun probeCapabilities(): DisplayCapabilities
    fun resolveVirtualDisplayFlags(): ResolvedDisplayFlags
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): CreateDisplayBackendResult

    fun launchPackageOnDisplay(displayId: Int, target: String): Boolean
    fun setAndReadBackDisplayId(event: InputEvent, displayId: Int): Int?
    fun injectInputEvent(event: InputEvent): Boolean
}

/**
 * Production backend executed exclusively inside the Shizuku UID 2000 UserService process.
 * Hidden API reflection stays strictly inside this UserService process — never in the app process.
 */
internal class AndroidDisplayServiceBackend(
    private val context: Context?,
) : DisplayServiceBackend {

    private val resolvedContext: Context? by lazy {
        context ?: resolveSystemContextReflectively()
    }

    override fun uid(): Int = Os.getuid()

    override fun probeCapabilities(): DisplayCapabilities {
        val flags = resolveVirtualDisplayFlags()
        val apiLevel = Build.VERSION.SDK_INT
        val secondaryActivities = runCatching {
            resolvedContext?.packageManager?.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS,
            ) == true
        }.getOrDefault(false)
        return DisplayCapabilities(
            apiLevel = apiLevel,
            uid = uid(),
            ownFocus = apiLevel >= AmberDisplayUserService.MIN_API_LEVEL && flags.ownFocusFlag != null,
            destroyContentOnRemoval = flags.destroyContentOnRemovalFlag != null,
            trusted = flags.trustedFlag != null,
            secondaryActivities = secondaryActivities,
        )
    }

    override fun resolveVirtualDisplayFlags(): ResolvedDisplayFlags = ResolvedDisplayFlags(
        ownFocusFlag = readDisplayManagerIntConstant("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS"),
        destroyContentOnRemovalFlag = readDisplayManagerIntConstant("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL"),
        trustedFlag = readDisplayManagerIntConstant("VIRTUAL_DISPLAY_FLAG_TRUSTED"),
    )

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): CreateDisplayBackendResult {
        val dm = resolvedContext?.getSystemService(DisplayManager::class.java)
            ?: return CreateDisplayBackendResult.Failed("display_manager_unavailable")
        return try {
            val vd: VirtualDisplay = dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
                ?: return CreateDisplayBackendResult.Failed("create_virtual_display_returned_null")
            val displayId = vd.display?.displayId ?: Display.INVALID_DISPLAY
            CreateDisplayBackendResult.Created(
                object : VirtualDisplayHandle {
                    override val displayId: Int = displayId
                    override fun release() {
                        vd.release()
                    }
                },
            )
        } catch (se: SecurityException) {
            CreateDisplayBackendResult.FlagRejected(se.message ?: "security_exception")
        } catch (iae: IllegalArgumentException) {
            CreateDisplayBackendResult.FlagRejected(iae.message ?: "illegal_argument")
        } catch (t: Throwable) {
            CreateDisplayBackendResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    override fun launchPackageOnDisplay(displayId: Int, target: String): Boolean {
        val ctx = resolvedContext ?: return false
        val intent = if (target.endsWith(AmberDisplayUserService.PROBE_TARGET_SUFFIX)) {
            val appPkg = target.removeSuffix(AmberDisplayUserService.PROBE_TARGET_SUFFIX)
            Intent().apply {
                component = ComponentName(appPkg, IsolationProbeTargetActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        } else {
            ctx.packageManager.getLaunchIntentForPackage(target)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return false
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        return runCatching {
            ctx.startActivity(intent, options.toBundle())
            true
        }.getOrDefault(false)
    }

    override fun setAndReadBackDisplayId(event: InputEvent, displayId: Int): Int? = runCatching {
        val setMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        val getMethod = InputEvent::class.java.getMethod("getDisplayId")
        setMethod.invoke(event, displayId)
        getMethod.invoke(event) as? Int
    }.getOrNull()

    override fun injectInputEvent(event: InputEvent): Boolean = runCatching {
        val target = resolveInputManagerInstance() ?: return@runCatching false
        val injectMethod = target.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        (injectMethod.invoke(target, event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT) as? Boolean) == true
    }.getOrDefault(false)

    private fun resolveInputManagerInstance(): Any? {
        runCatching {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInstance = globalClass.getMethod("getInstance")
            getInstance.invoke(null)
        }.getOrNull()?.let { return it }

        runCatching {
            val getInstance = InputManager::class.java.getMethod("getInstance")
            getInstance.invoke(null)
        }.getOrNull()?.let { return it }

        return runCatching {
            resolvedContext?.getSystemService(InputManager::class.java)
        }.getOrNull()
    }

    private fun readDisplayManagerIntConstant(fieldName: String): Int? = runCatching {
        val field = DisplayManager::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.getInt(null)
    }.getOrNull()

    private fun resolveSystemContextReflectively(): Context? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val current = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            ?: activityThreadClass.getMethod("systemMain").invoke(null)
        activityThreadClass.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()

    companion object {
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
    }
}
