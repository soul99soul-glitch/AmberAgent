package app.amber.feature.ui.components.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class CardGroupDynamicContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dynamicContent_replacesLoadingWithSuccessCountAndItems() {
        var content by mutableStateOf<StorageCardContent>(StorageCardContent.Loading)

        compose.setContent {
            MaterialTheme {
                CardGroup(title = { Text("存储占用") }) {
                    when (val current = content) {
                        StorageCardContent.Loading -> rawItem { Text("loading") }
                        is StorageCardContent.Success -> {
                            rawItem { Text("${current.conversationCount} 个会话") }
                            rawItem { Text("${current.messageCount} 条消息") }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("loading").assertIsDisplayed()
        compose.runOnIdle {
            content = StorageCardContent.Success(conversationCount = 1, messageCount = 1)
        }
        compose.onNodeWithText("1 个会话").assertIsDisplayed()
        compose.onNodeWithText("1 条消息").assertIsDisplayed()
        compose.onNodeWithText("loading").assertDoesNotExist()

        compose.runOnIdle {
            content = StorageCardContent.Loading
        }
        compose.onNodeWithText("loading").assertIsDisplayed()
        compose.onNodeWithText("1 个会话").assertDoesNotExist()
        compose.onNodeWithText("1 条消息").assertDoesNotExist()

        compose.runOnIdle {
            content = StorageCardContent.Success(conversationCount = 2, messageCount = 2)
        }
        compose.onNodeWithText("2 个会话").assertIsDisplayed()
        compose.onNodeWithText("2 条消息").assertIsDisplayed()
        compose.onNodeWithText("loading").assertDoesNotExist()
        compose.onNodeWithText("1 个会话").assertDoesNotExist()
        compose.onNodeWithText("1 条消息").assertDoesNotExist()
    }

    private sealed interface StorageCardContent {
        data object Loading : StorageCardContent

        data class Success(
            val conversationCount: Int,
            val messageCount: Int,
        ) : StorageCardContent
    }
}
