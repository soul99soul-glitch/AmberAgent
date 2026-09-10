package app.amber.feature.novel.workspace

import android.app.Application
import android.content.Intent
import app.amber.agent.Screen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NovelWorkspaceNotificationRouteTest {
    @Test fun notificationRetainsProjectBranchAndJobWithoutCollidingWithAnotherBatch() {
        val context = RuntimeEnvironment.getApplication()
        val project = "66dda1f4-38c7-49eb-9d8d-0c8b9d05dddb"
        val intent = NovelWorkspaceNotificationRoute.intent(context, project, "支线", "job-1")
        assertEquals(Screen.NovelMarkdown(project, "支线", "job-1"), NovelWorkspaceNotificationRoute.screenFrom(intent))
        val other = NovelWorkspaceNotificationRoute.intent(context, project, "支线", "job-2")
        assertFalse(intent.filterEquals(other))
    }

    @Test fun malformedOrUnrelatedIntentsCannotChooseWorkspaceFiles() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(NovelWorkspaceNotificationRoute.screenFrom(Intent()))
        assertNull(NovelWorkspaceNotificationRoute.screenFrom(
            NovelWorkspaceNotificationRoute.intent(context, "not-a-project", "主线", "job-1")))
        assertNull(NovelWorkspaceNotificationRoute.screenFrom(
            NovelWorkspaceNotificationRoute.intent(context, "66dda1f4-38c7-49eb-9d8d-0c8b9d05dddb", "../other", "job-1")))
    }
}
