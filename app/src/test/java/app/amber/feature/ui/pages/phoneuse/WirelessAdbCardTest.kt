package app.amber.feature.ui.pages.phoneuse

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.agent.R
import app.amber.core.automation.display.EmbeddedAdbDisplayBridge
import app.amber.core.automation.display.ShizukuDisplayBridge
import app.amber.core.automation.display.VirtualDisplayProbeStore
import app.amber.core.automation.display.adb.LocalAdbKeyStore
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import org.junit.Rule
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w420dp-h900dp")
class WirelessAdbCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pairedDeviceOffersOnlyConnectUntilAutomaticConnectionFails() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS, true)
        LocalAdbKeyStore(app).recordPaired("test-device")
        val di = koinApplication {
            modules(module {
                single { VirtualDisplayProbeStore(app) }
                single { ShizukuDisplayBridge(app) }
                single { EmbeddedAdbDisplayBridge { error("test connection unavailable") } }
            })
        }
        try {
            compose.setContent {
                KoinIsolatedContext(di) {
                    CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                        SettingPhoneUseDisplayPage()
                    }
                }
            }
            val connect = app.getString(R.string.vd_adb_connect)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(connect).fetchSemanticsNodes().isNotEmpty() }
            for (id in listOf(R.string.vd_adb_manual_connect, R.string.vd_adb_manual_pair, R.string.vd_adb_go_pair)) {
                compose.onNodeWithText(app.getString(id)).assertDoesNotExist()
            }
            compose.onNodeWithText(connect).performClick()
            val manual = app.getString(R.string.vd_adb_manual_connect)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(manual).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(manual).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.vd_adb_manual_pair)).assertDoesNotExist()
        } finally {
            di.close()
        }
    }

    @Test
    fun recoveryAppearsOnlyForTheFailedOperation() {
        assertNull(adbRecoveryAction(0, paired = false, requestingPair = true))
        assertNull(adbRecoveryAction(0, paired = true, requestingPair = false))
        assertEquals(R.string.vd_adb_manual_pair, adbRecoveryAction(R.string.vd_adb_pair_error, false, true))
        assertEquals(R.string.vd_adb_manual_pair, adbRecoveryAction(R.string.vd_adb_permission_hint, true, true))
        assertEquals(R.string.vd_adb_manual_connect, adbRecoveryAction(R.string.vd_adb_permission_hint, true, false))
        assertEquals(R.string.vd_adb_manual_connect, adbRecoveryAction(R.string.vd_adb_connect_error, true, false))
        assertEquals(R.string.vd_adb_retry_pair, adbRecoveryAction(R.string.vd_adb_retry_pair_hint, true, false))
    }
}
