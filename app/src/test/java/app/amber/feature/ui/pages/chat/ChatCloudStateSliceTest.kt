package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.feature.macgateway.CloudDialogueSubmission
import app.amber.feature.macgateway.CloudDialogueUiState
import app.amber.feature.macgateway.CloudInputDisposition
import app.amber.feature.macgateway.CloudSubmissionKind
import app.amber.feature.macgateway.CloudTaskSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ChatCloudStateSliceTest {
    @get:Rule
    val compose = createComposeRule()

    private fun task(
        workItemId: String,
        conversationId: String,
        progressText: String? = "working",
        active: Boolean = true,
        outcomeUnknown: Boolean = false,
        outcomeUnknownAbandoned: Boolean = false,
    ) = CloudTaskSession(
        workItemId = workItemId,
        topicId = "topic-$workItemId",
        conversationId = conversationId,
        title = "Task $workItemId",
        progressText = progressText,
        active = active,
        outcomeUnknown = outcomeUnknown,
        outcomeUnknownAbandoned = outcomeUnknownAbandoned,
        updatedAtMs = 1_000L,
    )

    private fun submission(
        requestId: String,
        conversationId: String,
        kind: CloudSubmissionKind = CloudSubmissionKind.FOLLOWUP,
        state: CloudInputDisposition = CloudInputDisposition.RECEIVED,
        stopTooLate: Boolean = false,
    ) = CloudDialogueSubmission(
        requestId = requestId,
        conversationId = conversationId,
        kind = kind,
        text = "msg-$requestId",
        state = state,
        stopTooLate = stopTooLate,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

    @Test
    fun cursorAndOtherConversationUpdatesProduceNoNewSlice() = runTest {
        val cloudActive = MutableStateFlow(true)
        val cloudState = MutableStateFlow(
            CloudDialogueUiState(
                enabled = true,
                activeConversationId = "conv-A",
                cursor = 1L,
                tasks = listOf(task("wA", "conv-A", progressText = "step 1")),
            ),
        )
        val localState = MutableStateFlow(CloudDialogueUiState())

        val emissions = mutableListOf<ChatComposerCloudStateSlice>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeChatComposerCloudStateSlice(
                conversationId = "conv-A",
                cloudActiveFlow = cloudActive,
                cloudDialogueStateFlow = cloudState,
                localAsyncStateFlow = localState,
            ).toList(emissions)
        }

        assertEquals(1, emissions.size)

        // 1. Cursor ticks on polling -> must NOT emit.
        cloudState.value = cloudState.value.copy(cursor = 2L)
        cloudState.value = cloudState.value.copy(cursor = 99L)
        assertEquals(1, emissions.size)

        // 2. Other conversation B task added/updated + submission + followup selection -> must NOT emit.
        cloudState.value = cloudState.value.copy(
            cursor = 100L,
            activeConversationId = "conv-B",
            selectedFollowupWorkItemId = "wB",
            lastError = "transient",
            tasks = cloudState.value.tasks + task("wB", "conv-B", progressText = "b-step-1"),
            submissions = listOf(submission("req-B", "conv-B")),
        )
        cloudState.value = cloudState.value.copy(
            cursor = 101L,
            tasks = listOf(
                task("wA", "conv-A", progressText = "step 1"),
                task("wB", "conv-B", progressText = "b-step-2"),
            ),
        )
        assertEquals(1, emissions.size)

        // 3. Inactive host (localAsyncState while cloudActive == true) changes -> must NOT emit.
        localState.value = CloudDialogueUiState(
            tasks = listOf(task("local-A", "conv-A", progressText = "local progress")),
        )
        assertEquals(1, emissions.size)

        // 4. Current conversation A progress update -> MUST emit.
        cloudState.value = cloudState.value.copy(
            cursor = 102L,
            tasks = listOf(
                task("wA", "conv-A", progressText = "step 2"),
                task("wB", "conv-B", progressText = "b-step-2"),
            ),
        )
        assertEquals(2, emissions.size)
        assertEquals("step 2", emissions.last().activeTasks.single().progressText)

        // 5. Current conversation A followup selection -> MUST emit.
        cloudState.value = cloudState.value.copy(selectedFollowupWorkItemId = "wA")
        assertEquals(3, emissions.size)
        assertEquals("wA", emissions.last().selectedFollowupWorkItemId)

        // 6. Current conversation A submission receipt -> MUST emit.
        cloudState.value = cloudState.value.copy(
            submissions = cloudState.value.submissions + submission("req-A", "conv-A"),
        )
        assertEquals(4, emissions.size)
        assertEquals("req-A", emissions.last().recentInputs.single().requestId)

        // 7. Switching host to local -> MUST emit and ignore subsequent cloud changes.
        cloudActive.value = false
        assertEquals(5, emissions.size)
        assertEquals(false, emissions.last().cloudActive)
        assertEquals("local-A", emissions.last().activeTasks.single().workItemId)

        cloudState.value = cloudState.value.copy(
            cursor = 500L,
            tasks = listOf(task("wA", "conv-A", progressText = "cloud while local")),
        )
        assertEquals(5, emissions.size)

        collectJob.cancel()
    }

    @Test
    fun toDockUiStateDeduplicatesOverlapAndScrubsIrrelevantFields() {
        val dualTask = task(
            workItemId = "w-dual",
            conversationId = "conv-A",
            active = true,
            outcomeUnknown = true,
            outcomeUnknownAbandoned = false,
        )
        val slice = projectChatComposerCloudState(
            conversationId = "conv-A",
            cloudActive = true,
            cloudDialogueState = CloudDialogueUiState(
                enabled = true,
                cursor = 777L,
                lastError = "network",
                selectedFollowupWorkItemId = "other-conv-task",
                tasks = listOf(
                    dualTask,
                    task("w-other", "conv-B", active = true),
                    task("w-done", "conv-A", active = false, outcomeUnknown = false),
                ),
            ),
            localAsyncState = CloudDialogueUiState(),
        )

        assertNull(slice.selectedFollowupWorkItemId)
        assertEquals(listOf("w-dual"), slice.activeTasks.map { it.workItemId })
        assertEquals(listOf("w-dual"), slice.outcomeUnknownTasks.map { it.workItemId })

        val dockState = slice.toDockUiState()
        assertEquals(0L, dockState.cursor)
        assertNull(dockState.lastError)
        assertEquals(listOf("w-dual"), dockState.tasks.map { it.workItemId })
    }

    @Test
    fun composerSliceRecompositionCountIgnoresCursorAndOtherConversationUpdates() {
        val cloudActive = MutableStateFlow(true)
        val cloudState = MutableStateFlow(
            CloudDialogueUiState(
                enabled = true,
                cursor = 1L,
                tasks = listOf(task("wA", "conv-A", progressText = "initial")),
            ),
        )
        val localState = MutableStateFlow(CloudDialogueUiState())
        var recompositions = 0
        var lastObservedProgress: String? = null

        compose.setContent {
            val sliceFlow = remember {
                observeChatComposerCloudStateSlice(
                    conversationId = "conv-A",
                    cloudActiveFlow = cloudActive,
                    cloudDialogueStateFlow = cloudState,
                    localAsyncStateFlow = localState,
                )
            }
            val initial = remember {
                projectChatComposerCloudState(
                    conversationId = "conv-A",
                    cloudActive = cloudActive.value,
                    cloudDialogueState = cloudState.value,
                    localAsyncState = localState.value,
                )
            }
            val slice by sliceFlow.collectAsStateWithLifecycle(initialValue = initial)
            recompositions++
            lastObservedProgress = slice.activeTasks.firstOrNull()?.progressText
        }

        compose.waitForIdle()
        assertEquals(1, recompositions)
        assertEquals("initial", lastObservedProgress)

        // Burst of cursor advances and other-conversation task updates: 0 extra recompositions.
        compose.runOnIdle {
            repeat(20) { tick ->
                cloudState.value = cloudState.value.copy(
                    cursor = 10L + tick,
                    tasks = listOf(
                        task("wA", "conv-A", progressText = "initial"),
                        task("wB", "conv-B", progressText = "other-$tick"),
                    ),
                    submissions = listOf(submission("b-$tick", "conv-B")),
                )
            }
        }
        compose.waitForIdle()
        assertEquals(
            "cursor ticks and other-conversation updates must not wake composer recomposition",
            1,
            recompositions,
        )

        // Updating current conversation A task progress wakes exactly once.
        compose.runOnIdle {
            cloudState.value = cloudState.value.copy(
                cursor = 100L,
                tasks = listOf(
                    task("wA", "conv-A", progressText = "updated"),
                    task("wB", "conv-B", progressText = "other-final"),
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(2, recompositions)
        assertEquals("updated", lastObservedProgress)
    }
}
