package app.amber.agent

import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelNotificationRouteTest {
    @Test
    fun `complete notification identity opens the matching novel workspace`() {
        val projectId = NovelProjectId.generate()
        val runId = NovelRunId.generate()

        assertEquals(
            Screen.NovelWorkspace(projectId.rawValue),
            novelWorkspaceScreenFromNotification(projectId = projectId.toString(), runId = runId.toString()),
        )
    }

    @Test
    fun `missing or malformed project or run identity is ignored`() {
        val projectId = NovelProjectId.generate().toString()
        val runId = NovelRunId.generate().toString()

        assertNull(novelWorkspaceScreenFromNotification(projectId = null, runId = runId))
        assertNull(novelWorkspaceScreenFromNotification(projectId = "not-a-project-id", runId = runId))
        assertNull(novelWorkspaceScreenFromNotification(projectId = projectId, runId = null))
        assertNull(novelWorkspaceScreenFromNotification(projectId = projectId, runId = "not-a-run-id"))
    }
}
