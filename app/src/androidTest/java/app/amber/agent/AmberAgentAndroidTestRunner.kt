package app.amber.agent

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Default runner uses a plain [Application] so lock-safe Room / JSON / ART tests
 * do not start production jobs. [TestReminderReceiverIsolation] installs a no-op
 * [app.amber.feature.reminder.ReminderScheduler] Koin (empty store, no alarms),
 * disables [app.amber.feature.reminder.ReminderRescheduleReceiver], and restores
 * the exact previous enabled setting in [finish].
 *
 * Select this runner by component:
 * `am instrument …/app.amber.agent.AmberAgentAndroidTestRunner`.
 * Foreground S9 tests use [ChatDeviceFixtureRunner] instead.
 */
class AmberAgentAndroidTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        context?.let { appContext ->
            TestReminderReceiverIsolation.installNoOpReminderKoin(appContext)
            TestReminderReceiverIsolation.disable(appContext)
        }
        Log.i(TAG, "newApplication app=${Application::class.java.name}")
        return super.newApplication(cl, Application::class.java.name, context)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try {
            TestReminderReceiverIsolation.restore(targetContext)
        } finally {
            super.finish(resultCode, results)
        }
    }

    companion object {
        private const val TAG = "AmberS9Runner"
    }
}
