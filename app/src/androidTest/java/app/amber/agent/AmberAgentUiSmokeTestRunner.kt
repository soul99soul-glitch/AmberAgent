package app.amber.agent

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Test-only runner for the visual UI smoke test.
 *
 * The default androidTest runner deliberately uses a plain Application so the
 * existing device tests do not start the full runtime. This dedicated runner
 * is only registered as a separate instrumentation entry for the UI canary,
 * which needs the real Koin graph.
 */
class AmberAgentUiSmokeTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(cl, AmberAgentApp::class.java.name, context)
    }
}
