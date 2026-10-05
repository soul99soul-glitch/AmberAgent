package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatTailGrowthHoldTest {
    @get:Rule val compose = createComposeRule()

    /** Issue #21：补偿滚动跨越 item 回收边界时，不能在 OnPositioned 分发中途改动布局树。 */
    @Test
    fun tailResizeAcrossManyItemsWhileReadingAwayFromBottomDoesNotCrash() {
        var tailHeight by mutableIntStateOf(600)
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            LazyColumn(Modifier.height(300.dp), state = state, reverseLayout = true) {
                items(300, key = { it }) { index ->
                    val height = if (index == 0) tailHeight else 4
                    Box(
                        Modifier
                            .then(if (index == 0) Modifier.holdReadingOnTailGrowth(state, 0, 0) else Modifier)
                            .fillMaxWidth()
                            .height(height.dp),
                    )
                }
            }
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 400) } }

        // 尾消息变矮（如思考块折叠）：上方涌入的 item 会被同帧反向补偿再挤出，超出复用池即被移除。
        repeat(3) {
            compose.runOnIdle { tailHeight -= 160 }
            compose.waitForIdle()
            // 补偿生效：尾 item 露出部分不变，上方已读内容留在原位。
            compose.runOnIdle { assertEquals(240, state.firstVisibleItemScrollOffset) }
            compose.runOnIdle { tailHeight += 160 }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(400, state.firstVisibleItemScrollOffset) }
        }

        compose.runOnIdle { assertEquals(0, state.firstVisibleItemIndex) }
    }
}
