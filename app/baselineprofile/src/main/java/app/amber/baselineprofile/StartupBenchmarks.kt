package app.amber.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Benchmarks Amber Android cold startup into the default Personal Agent main stage as well as
 * frame timing for both the Personal Agent composer/timeline journey and the explicit legacy
 * `SessionHome` journey.
 *
 * Run on a physical device with:
 * ```
 * ./gradlew :app:baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationNone() =
        benchmarkPersonalAgentStartup(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfiles() =
        benchmarkPersonalAgentStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun personalAgentJourneyCompilationBaselineProfiles() {
        val packageName = targetAppId()
        rule.measureRepeated(
            packageName = packageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 10,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait()
                waitForPersonalAgentReady(packageName)
            },
            measureBlock = {
                exercisePersonalAgentInputAndTimeline(packageName)
            },
        )
    }

    @Test
    fun explicitSessionHomeScrollCompilationBaselineProfiles() {
        val packageName = targetAppId()
        rule.measureRepeated(
            packageName = packageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = 10,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait()
                openExplicitSessionHome(packageName)
            },
            measureBlock = {
                exerciseSessionHomeAndConversation(packageName)
            },
        )
    }

    private fun benchmarkPersonalAgentStartup(compilationMode: CompilationMode) {
        val packageName = targetAppId()
        rule.measureRepeated(
            packageName = packageName,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
            measureBlock = {
                startActivityAndWait()
                waitForPersonalAgentReady(packageName)
            },
        )
    }
}
