package app.amber.feature.novel.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelGhostwriteFailureNotificationTest {

    @Test
    fun `uses the book title and the failed chapter ordinal when both are known`() {
        val copy = NovelGhostwriteFailureNotification.content("长安十二时辰", 7, "本章生成超时（8 分钟无完成）")
        assertEquals("长安十二时辰", copy.title)
        assertEquals("第 7 章失败：本章生成超时（8 分钟无完成）", copy.text)
    }

    @Test
    fun `blank book title falls back to the generic ghostwrite title`() {
        assertEquals("小说代笔", NovelGhostwriteFailureNotification.content(null, 1, "boom").title)
        assertEquals("小说代笔", NovelGhostwriteFailureNotification.content("   ", 1, "boom").title)
    }

    @Test
    fun `polish batches carry the polish label`() {
        val copy = NovelGhostwriteFailureNotification.content(
            bookTitle = "长安十二时辰",
            chapterOrdinal = 4,
            reason = "本章润色超时（8 分钟无完成）",
            taskLabel = "润色",
        )
        assertEquals("长安十二时辰", copy.title)
        assertEquals("第 4 章失败：本章润色超时（8 分钟无完成）", copy.text)
        // Unknown ordinal: the label replaces 代笔 in the fallback line and title.
        val noOrdinal = NovelGhostwriteFailureNotification.content(null, null, "未配置聊天模型", taskLabel = "润色")
        assertEquals("小说润色", noOrdinal.title)
        assertEquals("润色失败：未配置聊天模型", noOrdinal.text)
    }

    @Test
    fun `unknown ordinal drops the chapter prefix but keeps the reason`() {
        val copy = NovelGhostwriteFailureNotification.content("长安十二时辰", null, "未配置聊天模型")
        assertEquals("代笔失败：未配置聊天模型", copy.text)
    }

    @Test
    fun `blank reason still yields a usable line`() {
        assertEquals(
            "代笔失败：原因未知",
            NovelGhostwriteFailureNotification.content("书名", null, null).text,
        )
        assertEquals(
            "第 2 章失败：原因未知",
            NovelGhostwriteFailureNotification.content("书名", 2, "  \n ").text,
        )
    }

    @Test
    fun `reason collapses to one bounded line`() {
        val multiline = "第一行\n第二行\t更多   空格"
        assertEquals(
            "第 3 章失败：第一行 第二行 更多 空格",
            NovelGhostwriteFailureNotification.content("书名", 3, multiline).text,
        )
        val long = "长".repeat(200)
        val compacted = NovelGhostwriteFailureNotification.content("书名", 4, long).text
        val prefix = "第 4 章失败："
        assertEquals(prefix.length + 80, compacted.length)
        assertEquals(prefix + "长".repeat(79) + "…", compacted)
    }

    /** 两个窗口共用 [NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED]，前缀不可能漂移。 */
    @Test
    fun `polish pointer-commit window reports the polished chapter, not the next one`() {
        // 润色 commit 已落地（progress 已计入本章）、指针未落地：通知应指向刚润色的章。
        assertEquals(
            3,
            NovelGhostwriteFailureNotification.failedChapterOrdinal(
                polishMode = true,
                startOrdinal = 1,
                ledgerProgress = 3,
                newestCommittedOrdinal = 4,
                reason = NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED + "：磁盘写入失败",
            ),
        )
    }

    @Test
    fun `polish ordinary failures report the chapter being attempted`() {
        // 普通失败（本章润色 commit 未落地）：startOrdinal + progress 就是正在尝试的章。
        assertEquals(
            4,
            NovelGhostwriteFailureNotification.failedChapterOrdinal(
                polishMode = true,
                startOrdinal = 1,
                ledgerProgress = 3,
                newestCommittedOrdinal = 3,
                reason = "本章润色超时（8 分钟无完成），已中止本轮",
            ),
        )
        // 指针失败但 progress 为 0 的防御分支：不回退到范围前一章。
        assertEquals(
            1,
            NovelGhostwriteFailureNotification.failedChapterOrdinal(
                polishMode = true,
                startOrdinal = 1,
                ledgerProgress = 0,
                newestCommittedOrdinal = null,
                reason = NovelWorkspaceGhostwriteCoordinator.REASON_POLISH_POINTER_COMMIT_FAILED,
            ),
        )
    }

    @Test
    fun `write failures report manuscript head plus one`() {
        assertEquals(
            8,
            NovelGhostwriteFailureNotification.failedChapterOrdinal(
                polishMode = false,
                startOrdinal = 0,
                ledgerProgress = 0,
                newestCommittedOrdinal = 7,
                reason = "provider 429",
            ),
        )
        assertNull(
            NovelGhostwriteFailureNotification.failedChapterOrdinal(
                polishMode = false,
                startOrdinal = 0,
                ledgerProgress = 0,
                newestCommittedOrdinal = null,
                reason = null,
            ),
        )
    }
}
