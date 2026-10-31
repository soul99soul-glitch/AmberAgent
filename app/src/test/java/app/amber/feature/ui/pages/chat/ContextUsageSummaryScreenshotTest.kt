package app.amber.feature.ui.pages.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.AmberAccents
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A2.4 evidence: the context detail body reports the cache-hit ratio derived from `cachedTokens`.
 * Rendered in both themes because the row uses theme tokens; the PNGs are the review artifacts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h900dp-xxhdpi")
class ContextUsageSummaryScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightThemeShowsUsageAndCacheHit() = capture(AmberBase.LIGHT, "context-usage-light.png")

    @Test
    fun darkThemeShowsUsageAndCacheHit() = capture(AmberBase.DARK, "context-usage-dark.png")

    private fun capture(base: AmberBase, fileName: String) {
        val theme = buildAmberTokens(base, AmberAccents[0].hex).toChatTheme()
        compose.setContent {
            CompositionLocalProvider(LocalChatTheme provides theme) {
                MaterialTheme {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(theme.bg)
                            .padding(16.dp),
                    ) {
                        ContextUsageSummary(
                            used = 150,
                            total = 1000,
                            progressColor = theme.contextLow,
                            lastTurnTotalTokens = 12_800,
                            lastTurnCompletionTokens = 800,
                            lastTurnCachedTokens = 9_000,
                            lastTurnPromptTokens = 12_000,
                            lastTurnElapsedMs = 2_400,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Cache hit", substring = true).assertIsDisplayed()
        export(fileName)
    }

    private fun export(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        File("build/reports/$name").apply { parentFile?.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
