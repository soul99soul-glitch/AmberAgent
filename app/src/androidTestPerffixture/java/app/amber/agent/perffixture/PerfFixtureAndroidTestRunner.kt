package app.amber.agent.perffixture

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Fixture-only runner. The global AmberAgentAndroidTestRunner intentionally
 * forces android.app.Application for other suites; this runner creates the
 * real isolated perf-fixture Application so targetContext, Koin, Room and
 * SharedPreferences are the same ones the launcher uses.
 */
class PerfFixtureAndroidTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(
        cl,
        "app.amber.agent.perffixture.PerfFixtureApplication",
        context,
    )
}
