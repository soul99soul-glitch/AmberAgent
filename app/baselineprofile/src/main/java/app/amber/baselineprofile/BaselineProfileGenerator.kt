package app.amber.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        val packageName = targetAppId()
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            waitForHome(packageName)
        }
    }

    @Test
    fun homeAndConversation() {
        val packageName = targetAppId()
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = false,
        ) {
            pressHome()
            startActivityAndWait()
            waitForHome(packageName)
            scrollHomeAndConversation(packageName)
        }
    }

    private fun targetAppId(): String =
        InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId not passed as instrumentation runner arg")
}
