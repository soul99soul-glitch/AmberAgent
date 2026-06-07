package app.amber.feature.ui.components.ai

import android.app.Application
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI **interaction** tests for the Graphite composer send button (`ChatInput.kt`,
 * design §6.2 InputBar) — criterion 5.
 *
 * Why the gate is tested through a harness rather than by rendering `ChatInput` directly:
 * the public `ChatInput` composable is deeply coupled — it pulls `ProviderManager`,
 * `FilesManager`, and `OkHttpClient` from Koin via `koinInject(...)`, registers several
 * `rememberLauncherForActivityResult(...)` (which require a hosting Activity), and takes a live
 * `Conversation` / `Settings` / `ChatInputState` / `HazeState`. There is no mock framework on the
 * unit-test classpath (no mockk/mockito), and the send button is an *inline* `Box` inside
 * `ChatInput` (no extracted stateless sub-composable to render). So we exercise the **real
 * production gate** [composerSendEnabled] wired into the **exact same** `combinedClickable(enabled
 * = …)` primitive the composer uses, and assert the resulting click behavior across the composer's
 * state machine: Idle (empty → disabled), Draft (text → enabled), Sent (clears → disabled again),
 * and Stop (streaming + empty → enabled, fires cancel). This validates the decision logic that
 * governs the real button in a real interaction tree; the pixel-level rendering of the button is
 * verified on a device. Pure-branch coverage of [composerSendEnabled] also lives in
 * ComposerLogicTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ComposerInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val sendTag = "composer-send"

    /**
     * Mirrors the composer's send `Box`: a [combinedClickable] whose `enabled` is driven by the
     * real [composerSendEnabled] predicate over (isEmpty, loading). `onSend` records a tap.
     */
    private fun setSendButton(
        isEmpty: Boolean,
        loading: Boolean,
        onSend: () -> Unit,
    ) {
        compose.setContent {
            val enabled = composerSendEnabled(isEmpty = isEmpty, loading = loading)
            Text(
                text = "send",
                modifier = Modifier
                    .testTag(sendTag)
                    .size(46.dp)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = onSend,
                        onLongClick = {},
                    ),
            )
        }
    }

    @Test
    fun idle_emptyDraft_sendIsDisabled_tapDoesNothing() {
        var sends = 0
        setSendButton(isEmpty = true, loading = false) { sends++ }

        compose.onNodeWithTag(sendTag).performClick()
        assertEquals("empty + not loading → gate disabled → tap blocked", 0, sends)
    }

    @Test
    fun draft_withText_sendEnabled_tapFires() {
        var sends = 0
        setSendButton(isEmpty = false, loading = false) { sends++ }

        compose.onNodeWithTag(sendTag).performClick()
        assertEquals("draft text → gate enabled → tap sends", 1, sends)
    }

    @Test
    fun streamingEmpty_stopState_sendEnabled_tapFires() {
        // loading && empty == stop affordance: still enabled so the user can cancel.
        var taps = 0
        setSendButton(isEmpty = true, loading = true) { taps++ }

        compose.onNodeWithTag(sendTag).performClick()
        assertEquals("streaming + empty → enabled (stop) → tap fires", 1, taps)
    }

    @Test
    fun stateMachine_idleToDraftToSent_gateTogglesWithDraft() {
        // Drive the full Idle→Draft→Sent transition: typing flips the gate on, clearing flips it
        // back off, and the live `combinedClickable(enabled=…)` honors each transition.
        var sends = 0
        compose.setContent {
            var draft by remember { mutableStateOf("") }
            val enabled = composerSendEnabled(isEmpty = draft.isEmpty(), loading = false)
            Text(
                text = "send",
                modifier = Modifier
                    .testTag(sendTag)
                    .size(46.dp)
                    .combinedClickable(
                        enabled = enabled,
                        onClick = {
                            sends++
                            draft = "" // sending clears the input → back to Idle
                        },
                        onLongClick = {},
                    ),
            )
            // a separate node we tap to simulate the user typing a draft
            Text(
                text = "type",
                modifier = Modifier
                    .testTag("type")
                    .combinedClickable(onClick = { draft = "hello" }, onLongClick = {}),
            )
        }

        // Idle: empty → disabled → tap blocked
        compose.onNodeWithTag(sendTag).performClick()
        assertEquals(0, sends)

        // Draft: type → enabled → tap sends
        compose.onNodeWithTag("type").performClick()
        compose.onNodeWithTag(sendTag).performClick()
        assertEquals(1, sends)

        // Sent: input cleared → back to disabled → further tap blocked
        compose.onNodeWithTag(sendTag).performClick()
        assertEquals("after send the gate returns to disabled", 1, sends)
    }
}
