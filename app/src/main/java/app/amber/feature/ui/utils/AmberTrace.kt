package app.amber.feature.ui.utils

import android.os.Trace
import androidx.compose.runtime.Composable
import app.amber.agent.BuildConfig

/**
 * DEBUG-only composition trace section. Production builds compile the section
 * name/call away and only keep the content call.
 *
 * Used by the cold-entry diagnosis build to attribute the remaining first-frame
 * cost (scaffold / item types / tool-thought cards / input) without changing
 * behaviour.
 */
@Composable
internal fun AmberTraceSection(
    name: String,
    content: @Composable () -> Unit,
) {
    if (!BuildConfig.DEBUG) {
        content()
        return
    }
    Trace.beginSection(name)
    // Compose does not allow try/catch around composable invocations. A
    // composition exception aborts the frame anyway, so a plain section is
    // sufficient for diagnostics.
    content()
    Trace.endSection()
}
