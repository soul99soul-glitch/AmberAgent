package app.amber.feature.ui.pages.webmount

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import java.io.File
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh")
class WebMountSessionHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longAddressScrollsBesideTakeoverWithoutReadyRow() {
        val address = "https://www.icloud.com/iclouddrive/very-long-folder-name/another-folder"
        var takeovers = 0
        var renderedView: View? = null
        compose.setContent {
            renderedView = LocalView.current
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    SessionHeader(
                        metadata = WebMountSessionMetadata("wm_test", redactedUrl = address),
                        loadState = SessionHandle.LoadState.idle().copy(status = SessionHandle.LoadStatus.READY),
                        selectedPopup = null,
                        lease = null,
                        acquiring = false,
                        closing = false,
                        failure = null,
                        onTakeover = { takeovers++ },
                        onRelease = {},
                        onReopen = {},
                    )
                }
            }
        }
        File("build/reports/webmount-ui").mkdirs()
        compose.runOnIdle {
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/webmount-ui/address-bar.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val urlBounds = compose.onNodeWithText(address).fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithText("接管").fetchSemanticsNode().boundsInRoot
        assertEquals(urlBounds.center.y, actionBounds.center.y, 1f)
        assertTrue(urlBounds.right <= actionBounds.left)
        compose.onNodeWithText("已就绪").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performTouchInput { swipeLeft() }
        assertTrue(compose.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        compose.onNodeWithText("接管").performClick()
        assertEquals(1, takeovers)
    }
}
