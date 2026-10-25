package app.amber.agent.perffixture

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import app.amber.agent.RouteActivity
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Only the test launch changes: shell caller, then real in-process lifecycle ownership.
 * Never calls ActivityScenario, startActivitySync, waitForIdleSync or a fake navigator.
 */
internal class ShellHomeActivity(
    private val instrumentation: Instrumentation,
    private val token: String,
    private val checkpoint: (String, String) -> Unit,
) {
    private val application = instrumentation.targetContext.applicationContext as Application
    private val pid = Process.myPid()
    private val contract = HomeLaunchContract<RouteActivity>(pid, token)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pipes: Array<ParcelFileDescriptor>? = null
    private val readers = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "home-shell-output").apply { isDaemon = true }
    }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        private fun isRoute(activity: Activity) = activity.javaClass == RouteActivity::class.java
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (!isRoute(activity)) return
            contract.created(activity as RouteActivity, Process.myPid(), activity.componentName.flattenToString(), activity.intent.getStringExtra("homeTestToken"))
            checkpoint("lifecycle-created", "observed-pid-${Process.myPid()}")
        }
        override fun onActivityResumed(activity: Activity) {
            if (!isRoute(activity)) return
            contract.resumed(activity as RouteActivity, Process.myPid(), activity.componentName.flattenToString(), activity.intent.getStringExtra("homeTestToken"))
            checkpoint("lifecycle-resumed", "observed-pid-${Process.myPid()}")
        }
        override fun onActivityPaused(activity: Activity) { if (isRoute(activity)) contract.paused(activity as RouteActivity) }
        override fun onActivityDestroyed(activity: Activity) {
            if (!isRoute(activity)) return
            contract.destroyed(activity as RouteActivity)
            checkpoint("lifecycle-destroyed", "observed-pid-${Process.myPid()}")
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    init {
        check(application.packageName == HomeLaunchContract.PACKAGE && application.applicationInfo.uid == Process.myUid()) {
            "test adapter must run in the isolated fixture application process"
        }
        application.registerActivityLifecycleCallbacks(callbacks) // registered BEFORE shell start
    }

    fun launch() {
        check(Build.VERSION.SDK_INT >= 34) { "test shell stdout/stderr adapter requires API34+; unsupported platform, no fallback sync launch" }
        checkpoint("shell-start", "begin")
        val fds = instrumentation.uiAutomation.executeShellCommandRwe(contract.command())
        pipes = fds
        fds[1].close() // no stdin, no shell script, no permission or device setting changes
        fun read(fd: ParcelFileDescriptor): String = ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            val bytes = it.readNBytes(65_537)
            check(bytes.size <= 65_536) { "shell launch output exceeded test diagnostic bound" }
            bytes.toString(Charsets.UTF_8)
        }
        val stdout = readers.submit<String> { read(fds[0]) }
        val stderr = readers.submit<String> { read(fds[2]) }
        val out: String
        val err: String
        try {
            out = stdout.get(15, TimeUnit.SECONDS)
            err = stderr.get(1, TimeUnit.SECONDS)
        } finally {
            releaseShellPipes()
        }
        // This command contains only the isolated component/path/token. Preserve its real
        // output in the host instrumentation.txt, not a fabricated shell exit code.
        instrumentation.sendStatus(2, Bundle().apply {
            putString("homeLaunchToken", token)
            putString("homeLaunchCommand", contract.command())
            putString("homeLaunchStdout", out)
            putString("homeLaunchStderr", err)
            putString("homeLaunchExitCode", "unknown-UiAutomation-does-not-expose-exit-code")
        })
        HomeLaunchContract.validateShellResult(out, err)
        checkpoint("shell-start", "ok")
        contract.awaitResumed(3_000) // outer activity-launch phase remains 20s
        checkpoint("shell-owner", "confirmed-pid-$pid")
    }

    /** Handler + bounded Future, without Instrumentation's idle/startActivitySync waiters. */
    fun onActivity(action: (RouteActivity) -> Unit) {
        val task = FutureTask {
            val activity = contract.requireResumed()
            check(Process.myPid() == pid && activity.application === application && !activity.isDestroyed && !activity.isFinishing) {
                "test RouteActivity PID/Application/live identity changed"
            }
            action(activity)
        }
        check(main.post(task)) { "main handler rejected test owner operation" }
        try { task.get(5, TimeUnit.SECONDS) } finally { task.cancel(false) }
    }

    fun close() {
        val target = contract.ownerForClose() ?: return
        val finish = FutureTask { if (!target.isDestroyed) target.finish() }
        check(main.post(finish)) { "main handler rejected Activity finish" }
        try { finish.get(1, TimeUnit.SECONDS) } finally { finish.cancel(false) }
        contract.awaitDestroyed(3_000) // inside existing 5s cleanup stage
    }

    private fun releaseShellPipes() {
        val owned = pipes
        pipes = null
        owned?.forEach { runCatching { it.close() } }
    }

    /** Also called after a poisoned/failed stage: no wait on its blocked worker.
     * Success still requires close()/destroyed ACK; failure disposal only requests finish.
     */
    fun dispose() {
        releaseShellPipes()
        readers.shutdownNow()
        contract.ownerForClose()?.let { target -> main.post { if (!target.isDestroyed) target.finish() } }
        application.unregisterActivityLifecycleCallbacks(callbacks)
    }
}
