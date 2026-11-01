package app.amber.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.amber.feature.reminder.ReminderAlarmOperations
import app.amber.feature.reminder.ReminderAlarmRequest
import app.amber.feature.reminder.ReminderScheduler
import app.amber.feature.reminder.ReminderStore
import java.io.File
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Test-only isolation for [app.amber.feature.reminder.ReminderRescheduleReceiver].
 *
 * Plain [android.app.Application] has no production Koin. TIME_SET can already be
 * in `onReceive` before [android.content.pm.PackageManager.setComponentEnabledSetting]
 * takes effect, so disable alone does not prevent a crash. This helper:
 *
 * 1. Starts a no-op [ReminderScheduler] Koin (empty store, no AlarmManager) so an
 *    in-flight TIME_SET cannot crash and cannot touch the owner's alarms.
 * 2. Disables this one receiver for the rest of the process.
 * 3. Persists the original enabled setting under the real app `no_backup` dir so a
 *    crash that skips [restore] does not treat leftover DISABLED as the user's
 *    original.
 * 4. Restores that exact setting in [restore]. Other components are not touched.
 *
 * Shell `pm disable` / `pm default-state` are rejected on this device
 * (`Shell cannot change component state`). Do not `pm enable` from the host
 * (DEFAULT would become ENABLED).
 */
internal object TestReminderReceiverIsolation {
    private const val RECEIVER_CLASS = "app.amber.feature.reminder.ReminderRescheduleReceiver"
    private const val TAG = "AmberS9Runner"
    private const val ORIGINAL_FILE = "amber-s9-reminder-original-setting"

    @Volatile private var originalSetting: Int? = null
    @Volatile private var component: ComponentName? = null

    fun installNoOpReminderKoin(context: Context) {
        if (GlobalContext.getOrNull() != null) return
        val emptyDir = File(context.cacheDir, "amber-s9-noop-reminders").apply { mkdirs() }
        startKoin {
            modules(
                module {
                    single {
                        ReminderScheduler(
                            store = ReminderStore(emptyDir),
                            operations = NoOpReminderAlarmOperations,
                            exactAlarmAllowed = { false },
                        )
                    }
                },
            )
        }
        Log.i(TAG, "started no-op ReminderScheduler Koin for TIME_SET isolation")
    }

    fun disable(context: Context) {
        val pm = context.packageManager
        val name = ComponentName(context.packageName, RECEIVER_CLASS)
        component = name
        val current = pm.getComponentEnabledSetting(name)
        val persisted = readPersistedOriginal(context)
        if (originalSetting == null) {
            originalSetting = persisted ?: current
            persistOriginal(context, originalSetting!!)
            Log.i(TAG, "reminder receiver original enabledSetting=$originalSetting (current=$current persisted=$persisted)")
        }
        if (current != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            pm.setComponentEnabledSetting(
                name,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                disableFlags(),
            )
            Log.i(TAG, "reminder receiver disabled for instrumentation")
        }
    }

    fun restore(context: Context?) {
        val appContext = context ?: return
        val saved = originalSetting ?: readPersistedOriginal(appContext) ?: return
        val name = component ?: ComponentName(appContext.packageName, RECEIVER_CLASS)
        val pm = appContext.packageManager
        try {
            if (pm.getComponentEnabledSetting(name) != saved) {
                pm.setComponentEnabledSetting(name, saved, disableFlags())
            }
            val restored = pm.getComponentEnabledSetting(name)
            check(restored == saved) {
                "failed to restore ReminderRescheduleReceiver enabledSetting: got $restored expected $saved"
            }
            Log.i(TAG, "reminder receiver restored enabledSetting=$restored")
            clearPersistedOriginal(appContext)
        } finally {
            originalSetting = null
            component = null
        }
    }

    private fun disableFlags(): Int =
        PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS

    private fun isolationDir(context: Context): File =
        File(context.applicationInfo.dataDir, "no_backup").apply { mkdirs() }

    private fun persistFile(context: Context) = File(isolationDir(context), ORIGINAL_FILE)

    private fun persistOriginal(context: Context, setting: Int) {
        persistFile(context).writeText(setting.toString())
    }

    private fun readPersistedOriginal(context: Context): Int? {
        val file = persistFile(context)
        if (!file.isFile) return null
        return file.readText().trim().toIntOrNull()
    }

    private fun clearPersistedOriginal(context: Context) {
        persistFile(context).delete()
    }
}

private object NoOpReminderAlarmOperations : ReminderAlarmOperations {
    override fun scheduleExact(request: ReminderAlarmRequest) = Unit
    override fun scheduleApproximate(request: ReminderAlarmRequest) = Unit
    override fun cancel(reminderId: String) = Unit
}
