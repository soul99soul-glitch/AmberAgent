package app.amber.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates Baseline Profiles and Startup Profiles for Amber Android:
 * 1. [startupPersonalAgent] — cold start into the default `PersonalAgent` main stage up to
 *    first-input-ready (`includeInStartupProfile = true`).
 * 2. [personalAgentInputAndTimeline] — default `PersonalAgent` journey covering first input,
 *    submission (offline / unconfigured-provider safe), and timeline scrolling.
 * 3. [explicitSessionHome] — explicit legacy `HOME` (`SessionHome`) journey via either the
 *    top-bar Sessions entry or `-Pandroid.testInstrumentationRunnerArguments.launchStartMode=HOME`.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupPersonalAgent() {
        val packageName = targetAppId()
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = true,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForPersonalAgentReady(packageName)
        }
    }

    @Test
    fun personalAgentInputAndTimeline() {
        val packageName = targetAppId()
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = false,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            exercisePersonalAgentInputAndTimeline(packageName)
        }
    }

    @Test
    fun explicitSessionHome() {
        val packageName = targetAppId()
        rule.collect(
            packageName = packageName,
            includeInStartupProfile = false,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            openExplicitSessionHome(packageName)
            exerciseSessionHomeAndConversation(packageName)
        }
    }
}
