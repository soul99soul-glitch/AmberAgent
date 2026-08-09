package app.amber.feature.novel

import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelBackgroundRunRegistryTest {
    @Test
    fun exactClaimIsIdempotentAndRejectsConflictingOwners() {
        val registry = NovelBackgroundRunRegistry()
        val projectId = NovelProjectId.generate()
        val runId = NovelRunId.generate()

        assertTrue(registry.claim("worker-a", projectId, runId))
        assertTrue(registry.claim("worker-a", projectId, runId))
        assertFalse(registry.claim("worker-b", projectId, runId))
        assertFalse(registry.claim("worker-a", projectId, NovelRunId.generate()))
        assertEquals(setOf(runId), registry.ownedRunIds(projectId))
    }

    @Test
    fun staleReleaseCannotRemoveANewerOwner() {
        val registry = NovelBackgroundRunRegistry()
        val projectId = NovelProjectId.generate()
        val runId = NovelRunId.generate()

        assertTrue(registry.claim("epoch-1", projectId, runId))
        assertTrue(registry.release("epoch-1", projectId, runId))
        assertTrue(registry.claim("epoch-2", projectId, runId))

        assertFalse(registry.release("epoch-1", projectId, runId))
        assertEquals(setOf(runId), registry.ownedRunIds(projectId))
        assertTrue(registry.release("epoch-2", projectId, runId))
        assertTrue(registry.ownedRunIds(projectId).isEmpty())
    }
}
