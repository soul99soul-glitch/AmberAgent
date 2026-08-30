package app.amber.feature.novelworkspace

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Job mode 扩展的序列化契约：旧 job JSON（无 mode/endOrdinal 字段）必须解码为
 * Write（带默认值字段），否则升级后存量批次会从磁盘上「消失」。job 文件是宿主
 * 私有状态，不跨端，但 durable 兼容性同样不可破。
 */
class NovelWorkspaceGhostwriteJobModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    /** Exact shape the previous app version persisted (no mode, no endOrdinal). */
    private val legacyJson = """
        {
          "id": "J-LEGACY",
          "branchSlug": "主线",
          "targetChapterCount": 5,
          "startOrdinal": 3,
          "status": "running",
          "createdAt": "2026-08-20T00:00:00Z",
          "updatedAt": "2026-08-20T01:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `legacy job json without a mode field decodes as Write`() {
        val job = json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), legacyJson)
        assertEquals(NovelWorkspaceGhostwriteMode.Write, job.mode)
        assertEquals(0, job.endOrdinal)
        assertEquals("J-LEGACY", job.id)
        assertEquals(5, job.targetChapterCount)
        assertEquals(3, job.startOrdinal)
        assertFalse(job.isVersionBound)
    }

    @Test
    fun `write mode round-trips without emitting default mode noise`() {
        val job = NovelWorkspaceGhostwriteJob(
            id = "J-W",
            branchSlug = "主线",
            targetChapterCount = 2,
            startOrdinal = 1,
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
        )
        val encoded = json.encodeToString(NovelWorkspaceGhostwriteJob.serializer(), job)
        assertTrue(!encoded.contains("\"mode\"")) // default omitted, old readers stay compatible
        assertEquals(job, json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), encoded))
    }

    @Test
    fun `version-bound write job round-trips its plan and owned receipts`() {
        val job = NovelWorkspaceGhostwriteJob(
            id = "J-BOUND",
            executionId = "E-1",
            branchSlug = "主线",
            targetChapterCount = 2,
            startOrdinal = 1,
            branchId = "B-1",
            baseHeadId = "C-1",
            expectedHeadId = "C-2",
            baseTreeDigest = "tree-1",
            expectedTreeDigest = "tree-2",
            planId = "PLAN-1",
            planDigest = "plan-digest",
            confirmedPlan = "第二章计划",
            currentChapterOrdinal = 3,
            stage = NovelWorkspaceGhostwriteStage.Reviewing,
            rewriteAttempt = 1,
            pendingCandidate = NovelWorkspaceGhostwriteCandidate(
                id = "CAND-1",
                chapterOrdinal = 3,
                planId = "PLAN-1",
                planDigest = "plan-digest",
                attempt = 1,
                title = "陈桥",
                body = "第三章候选正文",
                repairInstructions = listOf("补足动机"),
                createdAt = Instant.parse("2026-08-20T00:00:30Z"),
                updatedAt = Instant.parse("2026-08-20T00:00:40Z"),
            ),
            receipts = listOf(
                NovelWorkspaceGhostwriteReceipt("C-2", 2, "PLAN-1", "plan-digest"),
            ),
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T00:01:00Z"),
        )

        val decoded = json.decodeFromString(
            NovelWorkspaceGhostwriteJob.serializer(),
            json.encodeToString(NovelWorkspaceGhostwriteJob.serializer(), job),
        )

        assertEquals(job, decoded)
        assertTrue(decoded.isVersionBound)
    }

    @Test
    fun `bound write progress counts only owned receipts on branch ancestry`() {
        val dir = tempFolder.root.resolve("bound-progress")
        assertTrue(dir.mkdirs())
        val store = NovelWorkspaceStore(dir)
        val t0 = Instant.parse("2026-08-20T00:00:00Z")
        val ledger = NovelWorkspaceLedgerStore(
            head = "C-EXTERNAL",
            heads = mapOf("B-1" to "C-EXTERNAL"),
            commits = listOf(
                NovelWorkspaceLedger.makeCommit(
                    id = "C-1",
                    parentId = null,
                    files = mapOf("branches/主线/chapters/001-a.md" to "h1"),
                    message = NovelWorkspaceLedger.Message.INITIAL,
                    createdAt = t0,
                ),
                NovelWorkspaceLedger.makeCommit(
                    id = "C-OWNED",
                    parentId = "C-1",
                    files = mapOf(
                        "branches/主线/chapters/001-a.md" to "h1",
                        "branches/主线/chapters/002-b.md" to "h2",
                    ),
                    message = NovelWorkspaceLedger.Message.COLLECTION,
                    createdAt = t0.plusSeconds(1),
                ),
                NovelWorkspaceLedger.makeCommit(
                    id = "C-EXTERNAL",
                    parentId = "C-OWNED",
                    files = mapOf(
                        "branches/主线/chapters/001-a.md" to "h1",
                        "branches/主线/chapters/002-b.md" to "h2",
                        "branches/主线/chapters/003-c.md" to "h3",
                    ),
                    message = NovelWorkspaceLedger.Message.MANUAL_EDIT,
                    createdAt = t0.plusSeconds(2),
                ),
            ),
        )
        NovelWorkspaceLedger.save(ledger, dir)
        val job = NovelWorkspaceGhostwriteJob(
            id = "J-BOUND",
            branchSlug = "主线",
            targetChapterCount = 2,
            startOrdinal = 1,
            branchId = "B-1",
            planId = "PLAN-1",
            planDigest = "digest",
            confirmedPlan = "第二章计划",
            receipts = listOf(
                NovelWorkspaceGhostwriteReceipt("C-OWNED", 2, "PLAN-1", "digest"),
                NovelWorkspaceGhostwriteReceipt("C-OTHER-BRANCH", 3, "PLAN-1", "digest"),
            ),
            createdAt = t0,
            updatedAt = t0,
        )

        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, store))
    }

    @Test
    fun `polish mode persists its name and round-trips`() {
        val job = NovelWorkspaceGhostwriteJob(
            id = "J-P",
            branchSlug = "主线",
            targetChapterCount = 3,
            startOrdinal = 2,
            endOrdinal = 4,
            mode = NovelWorkspaceGhostwriteMode.Polish,
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T00:00:00Z"),
        )
        val encoded = json.encodeToString(NovelWorkspaceGhostwriteJob.serializer(), job)
        assertTrue(encoded.contains("\"mode\":\"polish\""))
        val decoded = json.decodeFromString(NovelWorkspaceGhostwriteJob.serializer(), encoded)
        assertEquals(NovelWorkspaceGhostwriteMode.Polish, decoded.mode)
        assertEquals(2, decoded.startOrdinal)
        assertEquals(4, decoded.endOrdinal)
        assertEquals("polish", decoded.mode.value)
        assertEquals("write", NovelWorkspaceGhostwriteMode.Write.value)
    }

    @Test
    fun `polish progress counts polish commits since the job started`() {
        val dir = tempFolder.root.resolve("project")
        assertTrue(dir.mkdirs())
        val store = NovelWorkspaceStore(dir)
        val t0 = Instant.parse("2026-08-20T00:00:00Z")
        val ledger = NovelWorkspaceLedgerStore(
            head = "P-1",
            heads = mapOf("B-1" to "P-1"),
            commits = listOf(
                NovelWorkspaceLedger.makeCommit(
                    id = "C-INIT", parentId = null,
                    files = mapOf("branches/主线/chapters/001-a.md" to "h1"),
                    message = NovelWorkspaceLedger.Message.INITIAL,
                    createdAt = t0,
                ),
                NovelWorkspaceLedger.makeCommit(
                    id = "P-1", parentId = "C-INIT",
                    files = mapOf(
                        "branches/主线/chapters/001-a.md" to "h2",
                        "branches/主线/plot/current.md" to "p2",
                    ),
                    message = NovelWorkspaceLedger.Message.POLISH,
                    createdAt = t0.plusSeconds(60),
                ),
            ),
        )
        NovelWorkspaceLedger.save(ledger, dir)

        val job = NovelWorkspaceGhostwriteJob(
            id = "J-P",
            branchSlug = "主线",
            targetChapterCount = 3,
            startOrdinal = 1,
            endOrdinal = 3,
            mode = NovelWorkspaceGhostwriteMode.Polish,
            createdAt = t0,
            updatedAt = t0,
        )
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, store))
        // A pre-job polish commit is not this job's progress.
        assertEquals(
            0,
            NovelWorkspaceGhostwriteJobs.progress(job.copy(createdAt = t0.plusSeconds(120)), store),
        )
        // Write-mode progress semantics unchanged (head chapter 1 - start 0... start=1 here).
        val writeJob = job.copy(mode = NovelWorkspaceGhostwriteMode.Write, startOrdinal = 0)
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(writeJob, store))
    }

    /**
     * G1 取舍钉：边界用精确 createdAt 比较 + 只统计 [startOrdinal, endOrdinal] 范围内
     * 的润色 commit。生产形态：commit 的 createdAt 持久化即秒截断（本测试经磁盘存取
     * 复现），job 侧保留内存中的亚秒精度。旧代码把 job 边界向下取整到秒 → job 创建前
     * 同一秒内落库的旧润色 commit 被计入新 job（取消旧批次 1 秒内新建时静默跳过范围
     * 首章）；精确比较则排除。边界本身含等号（同秒重启时旧 commit 至多多润一章一次，
     * 安全方向）；范围外章节与解析不出序号的润色 commit 一律忽略。
     */
    @Test
    fun `polish progress uses the exact job boundary and the job ordinal range`() {
        val dir = tempFolder.root.resolve("project-boundary")
        assertTrue(dir.mkdirs())
        val store = NovelWorkspaceStore(dir)
        val t0 = Instant.parse("2026-08-20T00:00:00Z")
        val ledger = NovelWorkspaceLedgerStore(
            head = "P-9",
            heads = mapOf("B-1" to "P-9"),
            commits = listOf(
                NovelWorkspaceLedger.makeCommit(
                    id = "C-INIT", parentId = null,
                    files = mapOf(
                        "branches/主线/chapters/001-a.md" to "h1",
                        "branches/主线/chapters/004-d.md" to "h4",
                    ),
                    message = NovelWorkspaceLedger.Message.INITIAL,
                    createdAt = t0,
                ),
                // 旧批次的润色，其持久化（秒截断）时间戳落在 t0 这一秒：job 在同秒稍晚
                // 创建（t0.5）时，旧 floor 比较会计入它，精确比较排除。
                NovelWorkspaceLedger.makeCommit(
                    id = "P-EARLY", parentId = "C-INIT",
                    files = mapOf("branches/主线/chapters/001-a.md" to "h2"),
                    message = NovelWorkspaceLedger.Message.POLISH,
                    createdAt = t0,
                ),
                // 范围外章节（job 范围 [1,3]）：不计。
                NovelWorkspaceLedger.makeCommit(
                    id = "P-RANGE", parentId = "P-EARLY",
                    files = mapOf("branches/主线/chapters/004-d.md" to "h5"),
                    message = NovelWorkspaceLedger.Message.POLISH,
                    createdAt = t0.plusSeconds(1),
                ),
                // 范围内且不早于 job 创建：计入。
                NovelWorkspaceLedger.makeCommit(
                    id = "P-IN", parentId = "P-RANGE",
                    files = mapOf("branches/主线/chapters/002-b.md" to "h3"),
                    message = NovelWorkspaceLedger.Message.POLISH,
                    createdAt = t0.plusSeconds(2),
                ),
                // 润色 commit 但不改任何可解析序号的章节路径：忽略。
                NovelWorkspaceLedger.makeCommit(
                    id = "P-JUNK", parentId = "P-IN",
                    files = mapOf("branches/主线/notes.md" to "n1"),
                    message = NovelWorkspaceLedger.Message.POLISH,
                    createdAt = t0.plusSeconds(3),
                ),
            ),
        )
        NovelWorkspaceLedger.save(ledger, dir)

        val job = NovelWorkspaceGhostwriteJob(
            id = "J-R",
            branchSlug = "主线",
            targetChapterCount = 3,
            startOrdinal = 1,
            endOrdinal = 3,
            mode = NovelWorkspaceGhostwriteMode.Polish,
            createdAt = t0.plusMillis(500),
            updatedAt = t0.plusMillis(500),
        )
        assertEquals(1, NovelWorkspaceGhostwriteJobs.progress(job, store))
        // 边界含等号：恰等于 job.createdAt（如 job 已被磁盘截断到整秒）的 commit 计入。
        assertEquals(
            2,
            NovelWorkspaceGhostwriteJobs.progress(job.copy(createdAt = t0), store),
        )
        // 范围整体错开（job [5,6]）时，范围外的润色一概不计。
        assertEquals(
            0,
            NovelWorkspaceGhostwriteJobs.progress(job.copy(startOrdinal = 5, endOrdinal = 6, targetChapterCount = 2), store),
        )
    }

    @Test
    fun `latest helpers stay mode-agnostic over the same store`() {
        val dir = tempFolder.root.resolve("project2")
        assertTrue(dir.mkdirs())
        val t0 = Instant.parse("2026-08-20T00:00:00Z")
        val failedPolish = NovelWorkspaceGhostwriteJob(
            id = "J-PF",
            branchSlug = "主线",
            targetChapterCount = 2,
            startOrdinal = 1,
            endOrdinal = 2,
            mode = NovelWorkspaceGhostwriteMode.Polish,
            status = NovelWorkspaceGhostwriteJob.STATUS_FAILED,
            reason = "boom",
            createdAt = t0,
            updatedAt = t0,
        )
        NovelWorkspaceGhostwriteJobs.save(failedPolish, dir)
        assertEquals("J-PF", NovelWorkspaceGhostwriteJobs.latestFailed(dir, "主线")?.id)
        // A terminal (failed) job is not active; the mode still round-trips from disk.
        assertTrue(NovelWorkspaceGhostwriteJobs.listActive(dir).isEmpty())
        assertEquals(
            NovelWorkspaceGhostwriteMode.Polish,
            NovelWorkspaceGhostwriteJobs.load(dir, "J-PF")?.mode,
        )
    }
}
