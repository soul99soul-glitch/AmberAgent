package app.amber.feature.ui.pages.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * **T-C perf-layer scaffold** for `PerfFlags.USE_SPLIT_DEEPREAD_SCREEN`.
 *
 * The full DeepReadScreen (2102 LOC, threads ~15 collectAsState calls
 * + 6 Koin-injected repositories through a single Composable scope) is
 * structurally hard to split correctly without on-device verification.
 * This scaffold provides the dispatcher target — when the flag flips
 * true, users see this screen instead of the legacy DeepReadScreen.
 *
 * Region candidates (for the dedicated UI sprint that follows device
 * QA setup): article header / template article / running-stage notice /
 * confirmation block / immersive window effect / partial-error notice.
 * Each should collect only its own state slice.
 *
 * Defaults: flag=false → legacy path. This scaffold is a debug-screen
 * only, NEVER user-visible without the explicit flag flip.
 */
@Composable
fun DeepReadScreenSplit(
    topicId: String,
    title: String,
    sourceUrl: String? = null,
    initialForceRegenerate: Boolean = false,
    fromHistory: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "T4 DeepReadScreen scaffold active",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Flip PerfFlags.USE_SPLIT_DEEPREAD_SCREEN = false to restore " +
                    "the legacy DeepReadScreen. topicId=$topicId title=$title " +
                    "sourceUrl=$sourceUrl forceRegen=$initialForceRegenerate " +
                    "fromHistory=$fromHistory",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text(
                "TODO (post-device-QA sprint): replace this body with the 4-6 " +
                    "region Composables (header / template article / stage notice / " +
                    "confirmation / immersive effect / partial-error). Each region " +
                    "should collect only its own state slice via " +
                    "vm.<flow>.collectAsStateWithLifecycle().",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
