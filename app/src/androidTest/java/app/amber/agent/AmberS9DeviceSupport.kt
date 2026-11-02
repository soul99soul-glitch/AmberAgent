package app.amber.agent

import android.Manifest
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenOcrBlock
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.getActiveAccessibilityController
import java.io.Closeable
import java.io.File
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/**
 * Isolated S9 device helpers. No production bypass, no PIN handling, no user-app
 * automation. Accessibility mutations always restore the exact original
 * `enabled_accessibility_services` string.
 */
object AmberS9DeviceSupport {
    const val AMBER_ACCESSIBILITY = "app.amber.core.automation.AmberAccessibilityService"

    private val captured = AtomicBoolean(false)
    @Volatile private var originalEnabledServices: String = ""
    @Volatile private var identityAdopted: Boolean = false

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val targetContext get() = instrumentation.targetContext
    val testPackage get() = instrumentation.context.packageName
    val targetPackage get() = targetContext.packageName

    fun amberComponent(): String = "$targetPackage/$AMBER_ACCESSIBILITY"

    fun uiAutomation(): UiAutomation =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    fun assumeUnlocked() {
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        assumeFalse(
            "device is keyguard-locked; skip until the owner unlocks and keeps the screen on",
            keyguard.isKeyguardLocked || keyguard.isDeviceLocked,
        )
    }

    fun assumeS9FixtureApplication() {
        assumeTrue(
            "requires ChatDeviceFixtureRunner so ChatDeviceFixtureApplication starts isolated Koin",
            targetContext.applicationContext is ChatDeviceFixtureApplication,
        )
    }

    fun isKeyguardLocked(): Boolean {
        val keyguard = targetContext.getSystemService(KeyguardManager::class.java)
        return keyguard.isKeyguardLocked || keyguard.isDeviceLocked
    }

    fun shell(command: String): String {
        val fd = uiAutomation().executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    fun evidenceDir(): File {
        val dir = targetContext.getExternalFilesDir("amber-s9")
            ?: File(targetContext.filesDir, "amber-s9")
        if (!dir.exists()) check(dir.mkdirs()) { "cannot create evidence dir $dir" }
        return dir
    }

    fun writeEvidence(name: String, text: String): File {
        val file = File(evidenceDir(), name)
        file.writeText(text)
        println("AmberS9 evidence=${file.absolutePath}")
        return file
    }

    fun savePng(bitmap: Bitmap, name: String): File {
        val file = File(evidenceDir(), name)
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        println("AmberS9 png=${file.absolutePath}")
        return file
    }

    fun takeUiScreenshot(name: String): File? {
        val bitmap = runCatching { uiAutomation().takeScreenshot() }.getOrNull() ?: return null
        return try {
            savePng(bitmap, name)
        } finally {
            bitmap.recycle()
        }
    }

    fun interactiveDisplayId(): Int {
        val dm = targetContext.getSystemService(DisplayManager::class.java)
        val on = dm.displays.filter { it.state == Display.STATE_ON }
        return on.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.displayId
            ?: on.minByOrNull { it.displayId }?.displayId
            ?: Display.DEFAULT_DISPLAY
    }

    fun dismissTransientSystemUi() {
        shell("cmd statusbar collapse")
    }

    /**
     * Framework [android.app.Instrumentation.startActivitySync], not
     * MonitoringInstrumentation's idle-gated override. ComposeTestRule's
     * Espresso idling makes the override wait 45s. Same-uid starts are still
     * MIUI BAL-aborted (result 102) when the process is background.
     */
    fun startActivitySyncRaw(intent: Intent): android.app.Activity {
        val method = android.app.Instrumentation::class.java.getDeclaredMethod(
            "startActivitySync",
            Intent::class.java,
        )
        method.isAccessible = true
        return method.invoke(instrumentation, intent) as android.app.Activity
    }

    /**
     * Shell `am start` (uid 2000) of an exported debug fixture. Same-uid
     * [startActivitySync] is BAL-aborted while Paseo holds the cover.
     */
    fun launchTargetActivity(componentClass: String, extras: String = ""): android.app.Activity {
        dismissTransientSystemUi()
        shell("input keyevent KEYCODE_HOME")
        val displayId = interactiveDisplayId()
        val extraArgs = extras.trim().let { if (it.isEmpty()) "" else " $it" }
        val monitor = instrumentation.addMonitor(componentClass, null, false)
        try {
            val startOut = shell(
                "am start -W --display $displayId --windowingMode 1 -f 0x10008000 " +
                    "-n $targetPackage/$componentClass$extraArgs",
            )
            println("AmberS9 amStart display=$displayId $startOut")
            var activity = instrumentation.waitForMonitorWithTimeout(monitor, 8_000)
            if (activity == null) {
                println("AmberS9 amStart missed monitor; fallback startActivitySyncRaw")
                val intent = Intent().setClassName(targetPackage, componentClass)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                applyShellExtras(intent, extras)
                activity = startActivitySyncRaw(intent)
            }
            instrumentation.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            awaitForegroundActivity(activity, extras)
            return activity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /**
     * MIUI may first attach an exported fixture as a tiny unfocused window.
     * Production OCR must wait until the cover display actually shows it.
     */
    fun awaitForegroundActivity(
        activity: android.app.Activity,
        extras: String = "",
        minWidth: Int = 800,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var width = 0
            var focused = false
            instrumentation.runOnMainSync {
                width = activity.window?.decorView?.width ?: 0
                focused = activity.hasWindowFocus()
            }
            println("AmberS9 awaitForeground focused=$focused w=$width extras=$extras")
            if (focused && width >= minWidth) return
            Thread.sleep(200)
        }
        error(
            "fixture not foreground focused=${activity.hasWindowFocus()} " +
                "w=${activity.window?.decorView?.width}",
        )
    }

    /**
     * Launch a debug-variant fixture that lives in the target package.
     */
    fun launchTestPackageActivity(componentClass: String, extras: String = ""): String {
        val activity = launchTargetActivity(componentClass, extras)
        val width = intArrayOf(0)
        instrumentation.runOnMainSync {
            width[0] = activity.window?.decorView?.width ?: 0
        }
        val report =
            "amStart component=${activity.componentName} display=${activity.display?.displayId} " +
                "hasFocus=${activity.hasWindowFocus()} width=${width[0]}"
        println("AmberS9 $report")
        return report
    }

    /**
     * Must run before [androidx.compose.ui.test.junit4.createEmptyComposeRule]
     * applies Espresso idling.
     */
    fun launchTargetComposeHost(): ComposeHostSession {
        assumeUnlocked()
        assumeS9FixtureApplication()
        S9ComposeHostActivity.clearInstance()
        val started = launchTargetActivity(S9ComposeHostActivity::class.java.name) as ComponentActivity
        println(
            "AmberS9 composeHost component=${started.componentName} " +
                "display=${started.display?.displayId} hasFocus=${started.hasWindowFocus()} " +
                "state=${started.lifecycle.currentState}",
        )
        return ComposeHostSession(started)
    }

    private fun applyShellExtras(intent: Intent, extras: String) {
        val parts = extras.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var index = 0
        while (index < parts.size) {
            if (parts[index] == "--es" && index + 2 < parts.size) {
                intent.putExtra(parts[index + 1], parts[index + 2])
                index += 3
            } else {
                index += 1
            }
        }
    }

    fun waitForSnapshotPackage(
        expectedPackage: String,
        timeoutMs: Long = 15_000,
        minWidth: Int = 0,
    ): ScreenSnapshot {
        var last = "(none)"
        var found: ScreenSnapshot? = null
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    while (found == null) {
                        val current = withContext(Dispatchers.Main) {
                            getActiveAccessibilityController()?.captureScreenSnapshot()
                        }
                        last = current?.packageName ?: "(null-snapshot)"
                        val wideEnough = minWidth <= 0 ||
                            current?.nodes?.any { it.right - it.left >= minWidth } == true
                        if (current != null && current.packageName == expectedPackage && wideEnough) {
                            found = current
                        } else {
                            delay(250)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "timed out waiting for snapshot package $expectedPackage; last=$last",
                e,
            )
        }
        return checkNotNull(found)
    }

    fun <T> withZeroAnimations(block: () -> T): T {
        val keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val original = keys.associateWith { key -> shell("settings get global $key").trim() }
        return try {
            keys.forEach { key -> shell("settings put global $key 0") }
            block()
        } finally {
            original.forEach { (key, value) ->
                if (value.isBlank() || value == "null") {
                    shell("settings delete global $key")
                } else {
                    shell("settings put global $key $value")
                }
            }
        }
    }

    fun <T> withAmberAccessibility(block: () -> T): T {
        enableAmberAccessibility()
        try {
            return block()
        } finally {
            restoreAmberAccessibility()
        }
    }

    suspend fun <T> withAmberAccessibilitySuspending(block: suspend () -> T): T {
        enableAmberAccessibility()
        try {
            return block()
        } finally {
            restoreAmberAccessibility()
        }
    }

    fun enableAmberAccessibility() {
        val automation = uiAutomation()
        val resolver = targetContext.contentResolver
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val component = amberComponent()
        if (!identityAdopted) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            identityAdopted = true
        }
        if (captured.compareAndSet(false, true)) {
            originalEnabledServices = android.provider.Settings.Secure.getString(resolver, key).orEmpty()
        }
        val current = android.provider.Settings.Secure.getString(resolver, key).orEmpty()
        val items = current.split(':').filter { it.isNotBlank() }
        if (component !in items) {
            writeEnabledServices((items + component).joinToString(":"))
        }
        if (getActiveAccessibilityController() == null) {
            val withAmber = readEnabledItems()
            writeEnabledServices(withAmber.filter { it != component }.joinToString(":"))
            Thread.sleep(250)
            writeEnabledServices((readEnabledItems().filter { it != component } + component).joinToString(":"))
        }
        runBlocking {
            withTimeout(20_000) {
                while (getActiveAccessibilityController() == null) delay(250)
            }
        }
    }

    fun restoreAmberAccessibility() {
        if (!captured.get()) return
        try {
            writeEnabledServices(originalEnabledServices)
            val restored = android.provider.Settings.Secure.getString(
                targetContext.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            check(restored == originalEnabledServices) {
                "failed to restore enabled_accessibility_services: got '$restored' expected '$originalEnabledServices'"
            }
        } finally {
            captured.set(false)
            originalEnabledServices = ""
            if (identityAdopted) {
                runCatching { uiAutomation().dropShellPermissionIdentity() }
                identityAdopted = false
            }
        }
    }

    fun dumpAxText(node: AccessibilityNodeInfo?): String {
        if (node == null) return "(no-root)"
        val out = StringBuilder()
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            val text = listOfNotNull(
                current.text?.toString(),
                current.contentDescription?.toString(),
                current.hintText?.toString(),
            ).filter { it.isNotBlank() }
            if (text.isNotEmpty()) {
                out.append("  ".repeat(depth)).append(current.className).append(' ')
                    .append(text.joinToString(" | ")).append('\n')
            }
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(node, 0)
        return out.toString()
    }

    fun uniqueTreeTarget(snapshot: ScreenSnapshot, block: ScreenOcrBlock): ScreenNode {
        val cx = (block.left + block.right) / 2
        val cy = (block.top + block.bottom) / 2
        val containing = snapshot.nodes.filter { node ->
            cx >= node.left && cx < node.right && cy >= node.top && cy < node.bottom
        }
        check(containing.isNotEmpty()) {
            "OCR block '${block.text}' @${block.left},${block.top}-${block.right},${block.bottom} " +
                "is not inside any current-tree node. pkg=${snapshot.packageName} nodes=${snapshot.nodes.size}"
        }
        val pool = containing.filter { it.clickable }.ifEmpty { containing }
        val smallestArea = pool.minOf { node ->
            (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0)
        }
        val ties = pool.filter { node ->
            (node.right - node.left).coerceAtLeast(0) * (node.bottom - node.top).coerceAtLeast(0) == smallestArea
        }
        check(ties.size == 1) {
            "OCR clickable region maps to ${ties.size} equal-area tree targets: " +
                ties.joinToString { "${it.ref}/${it.label}/${it.viewId}" }
        }
        return ties.single()
    }

    private fun readEnabledItems(): List<String> {
        val raw = android.provider.Settings.Secure.getString(
            targetContext.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return raw.split(':').filter { it.isNotBlank() }
    }

    private fun writeEnabledServices(value: String) {
        val resolver = targetContext.contentResolver
        val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        android.provider.Settings.Secure.putString(resolver, key, value)
        val viaSettings = android.provider.Settings.Secure.getString(resolver, key).orEmpty()
        if (viaSettings != value) {
            // executeShellCommand is not a shell: do not wrap the value in quotes.
            if (value.isEmpty()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $value")
            }
        }
    }
}

/** Wraps a target-package Activity already resumed by [Instrumentation.startActivitySync]. */
class ComposeHostSession(
    val activity: ComponentActivity,
) : Closeable {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun onActivity(block: (ComponentActivity) -> Unit) {
        instrumentation.runOnMainSync { block(activity) }
    }

    fun moveToState(state: Lifecycle.State) {
        when (state) {
            Lifecycle.State.CREATED -> {
                instrumentation.runOnMainSync {
                    activity.startActivity(
                        android.content.Intent(activity, S9LifecycleCoverActivity::class.java),
                    )
                }
                val coverDeadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < coverDeadline &&
                    S9LifecycleCoverActivity.instance == null
                ) {
                    Thread.sleep(50)
                }
            }
            Lifecycle.State.RESUMED -> instrumentation.runOnMainSync {
                S9LifecycleCoverActivity.instance?.finish()
            }
            else -> error("unsupported lifecycle state $state")
        }
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = activity.lifecycle.currentState
            val ok = when (state) {
                Lifecycle.State.CREATED -> !current.isAtLeast(Lifecycle.State.STARTED)
                Lifecycle.State.RESUMED -> current.isAtLeast(Lifecycle.State.RESUMED)
                else -> current == state
            }
            if (ok) return
            Thread.sleep(50)
        }
        error("lifecycle ${activity.lifecycle.currentState} did not reach $state")
    }

    override fun close() {
        instrumentation.runOnMainSync {
            if (!activity.isFinishing) activity.finish()
        }
        instrumentation.waitForIdleSync()
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && !activity.isDestroyed) {
            Thread.sleep(50)
        }
    }
}

/**
 * Starts [S9ComposeHostActivity] via [Instrumentation.startActivitySync] before
 * ComposeTestRule installs Espresso idling.
 */
class ComposeHostRule : TestWatcher() {
    var session: ComposeHostSession? = null
        private set

    override fun starting(description: Description) {
        session = AmberS9DeviceSupport.launchTargetComposeHost()
    }

    override fun finished(description: Description) {
        session?.close()
        session = null
    }
}
