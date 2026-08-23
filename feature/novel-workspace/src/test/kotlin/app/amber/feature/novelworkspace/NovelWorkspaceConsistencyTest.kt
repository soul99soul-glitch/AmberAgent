package app.amber.feature.novelworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceConsistencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): NovelWorkspaceStore = NovelWorkspaceStore(tempFolder.newFolder("book"))

    private fun seedBook(store: NovelWorkspaceStore) {
        store.write("manifest.yaml", "format: amber.novel.workspace\nformatVersion: 1\n")
        store.write("project.md", "---\nkind: project\ntitle: 赵大来了\n---\n")
        store.write(
            "setting/characters/赵匡胤.md",
            """
            ---
            kind: material
            materialKind: character
            title: 赵匡胤
            status: 殿前司都点检，暗中结交军将
            aliases:
              - 官家
            relations:
              - {with: 赵大, type: 结拜兄弟}
              - {with: 汴京, type: 驻地}
            ---
            后周禁军将领。
            """.trimIndent(),
        )
        store.write(
            "setting/characters/赵大.md",
            """
            ---
            kind: material
            materialKind: character
            title: 赵大
            status: 游历归来
            relations:
              - {with: 赵匡胤, type: 结拜兄弟}
            ---
            主角。
            """.trimIndent(),
        )
        store.write(
            "setting/world/汴京.md",
            """
            ---
            kind: material
            materialKind: world
            title: 汴京
            ---
            后周都城。
            """.trimIndent(),
        )
        store.write(
            "setting/log/不杀士大夫.md",
            """
            ---
            kind: material
            materialKind: decisionLog
            title: 不杀士大夫
            ---
            太祖立誓，后世不得违。
            """.trimIndent(),
        )
        store.write(
            "branches/主线/plot/foreshadowing/黄龙旗.md",
            """
            ---
            kind: foreshadowing
            title: 黄龙旗
            status: open
            ---
            陈桥驿出现的黄龙旗，应在兵变夜揭晓来历。
            """.trimIndent(),
        )
        store.write(
            "branches/主线/plot/foreshadowing/旧伤.md",
            """
            ---
            kind: foreshadowing
            title: 赵大旧伤
            status: resolved
            ---
            已回收。
            """.trimIndent(),
        )
        store.write(
            "branches/主线/plot/current.md",
            "---\nkind: plot\ntitle: 当前状态\n---\n赵大已回到汴京，陈桥驿风声渐紧。",
        )
        store.write(
            "branches/主线/plan/this-chapter.md",
            "---\nkind: plan\ntitle: 本章计划\n---\n赵大与赵匡胤在汴京密会，商议兵变。",
        )
    }

    @Test
    fun `parseInlineMap handles typed relations and rejects non-maps`() {
        val parsed = NovelWorkspaceMarkdown.parseInlineMap("{with: 赵大, type: 结拜兄弟}")
        assertEquals(mapOf("with" to "赵大", "type" to "结拜兄弟"), parsed)
        assertNull(NovelWorkspaceMarkdown.parseInlineMap("普通列表项"))
        assertNull(NovelWorkspaceMarkdown.parseInlineMap("{}"))
        assertNull(NovelWorkspaceMarkdown.parseInlineMap("{missing-colon}"))
    }

    @Test
    fun `parseFile lifts relations into maps and keeps plain lists`() {
        val parsed = NovelWorkspaceMarkdown.parseFile(
            """
            ---
            title: 赵匡胤
            aliases:
              - 官家
            relations:
              - {with: 赵大, type: 结拜兄弟}
              - {with: 汴京, type: 驻地}
            ---
            正文。
            """.trimIndent(),
        )
        assertEquals(listOf("官家"), parsed.lists["aliases"])
        assertEquals(
            listOf(
                mapOf("with" to "赵大", "type" to "结拜兄弟"),
                mapOf("with" to "汴京", "type" to "驻地"),
            ),
            parsed.maps["relations"],
        )
        assertEquals("正文。", parsed.body)
    }

    @Test
    fun `nodes collect characters decisions and foreshadowing with status`() {
        val store = store()
        seedBook(store)
        val nodes = NovelWorkspaceNodes.collect(store, "主线")
        val kinds = nodes.map { it.nodeKind }.toSet()
        assertTrue(kinds.contains("character"))
        assertTrue(kinds.contains("decisionLog"))
        assertTrue(kinds.contains("foreshadowing"))
        assertFalse(kinds.contains("plot"))

        val zhao = nodes.first { it.title == "赵匡胤" }
        assertEquals("殿前司都点检，暗中结交军将", zhao.status)
        assertEquals(listOf("官家"), zhao.aliases)
        assertEquals(2, zhao.relations.size)
        assertEquals("赵大", zhao.relations[0].withRef)
        assertEquals("结拜兄弟", zhao.relations[0].type)

        val open = NovelWorkspaceNodes.openForeshadowing(nodes)
        assertEquals(listOf("黄龙旗"), open.map { it.title })
    }

    @Test
    fun `neighborhood matches plan entities and expands one relation hop`() {
        val store = store()
        seedBook(store)
        val nodes = NovelWorkspaceNodes.collect(store, "主线")
        val planText = "赵大与赵匡胤在汴京密会，商议兵变。"
        val subgraph = NovelWorkspaceNodes.neighborhood(nodes, planText)
        val titles = subgraph.map { it.title }
        // Direct matches.
        assertTrue(titles.contains("赵大"))
        assertTrue(titles.contains("赵匡胤"))
        assertTrue(titles.contains("汴京"))
        // No duplicates from mutual relations.
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `neighborhood pulls relation targets not named in the plan`() {
        val store = store()
        seedBook(store)
        val nodes = NovelWorkspaceNodes.collect(store, "主线")
        // Plan only names 赵匡胤; 赵大 is pulled in via the 结拜兄弟 relation.
        val subgraph = NovelWorkspaceNodes.neighborhood(nodes, "本章写赵匡胤的点检府")
        val titles = subgraph.map { it.title }
        assertTrue(titles.contains("赵匡胤"))
        assertTrue(titles.contains("赵大"))
    }

    @Test
    fun `assembler brief carries plot, open foreshadowing, decisions, and subgraph`() {
        val store = store()
        seedBook(store)
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue(brief.contains("当前剧情状态"))
        assertTrue(brief.contains("赵大已回到汴京"))
        assertTrue(brief.contains("未回收伏笔"))
        assertTrue(brief.contains("黄龙旗"))
        assertFalse("resolved foreshadowing must not appear", brief.contains("赵大旧伤"))
        assertTrue(brief.contains("已确认决定"))
        assertTrue(brief.contains("不杀士大夫"))
        assertTrue(brief.contains("本章相关节点"))
        assertTrue(brief.contains("赵匡胤"))
        assertTrue("status rides along", brief.contains("殿前司都点检"))
        assertTrue("relation rides along", brief.contains("赵大（结拜兄弟）"))
    }

    @Test
    fun `oversized plot is truncated so the brief stays bounded`() {
        val store = store()
        val longPlot = "剧情推进。".repeat(1_000) // 5_000 chars > the 3_000 section cap
        store.write("branches/主线/plot/current.md", "---\nkind: plot\ntitle: 当前状态\n---\n$longPlot")
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue("truncation note must appear", brief.contains("已截断"))
        assertFalse("full plot must not flow into the brief", brief.contains(longPlot))
        assertTrue("brief must stay inside its budget", brief.length <= 6_000 + 200)
    }

    @Test
    fun `assembler without plan still injects plot and foreshadowing`() {
        val store = store()
        seedBook(store)
        store.delete("branches/主线/plan/this-chapter.md")
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue(brief.contains("当前剧情状态"))
        assertTrue(brief.contains("黄龙旗"))
        assertFalse(brief.contains("本章相关节点"))
    }

    @Test
    fun `assembler on an empty tree produces an empty brief`() {
        val store = store()
        store.write("manifest.yaml", "format: amber.novel.workspace\nformatVersion: 1\n")
        assertEquals("", NovelWorkspaceContextAssembler.assemble(store, "主线"))
    }

    private fun commit(
        id: String,
        parent: String?,
        files: Map<String, String>,
        at: String,
    ) = NovelWorkspaceCommit(
        id = id,
        parentId = parent,
        createdAt = java.time.Instant.parse(at),
        message = "test",
        treeSHA256 = NovelWorkspaceLedger.treeSHA256(files),
        files = files,
    )

    @Test
    fun `plot stale follows commit order and clears when plot catches up`() {
        val store = store()
        store.write("manifest.yaml", "format: amber.novel.workspace\nformatVersion: 1\n")
        val ch1 = "branches/主线/chapters/001-山呼.md"
        val plot = "branches/主线/plot/current.md"

        // No commits -> not stale.
        NovelWorkspaceLedger.save(NovelWorkspaceLedgerStore(), store.rootDirectory)
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(store.rootDirectory), "主线"))

        // Chapter + plot in the same commit -> not stale.
        val together = mapOf(ch1 to "h1", plot to "p1")
        NovelWorkspaceLedger.save(
            NovelWorkspaceLedgerStore(head = "C1", commits = listOf(commit("C1", null, together, "2026-08-19T00:00:00Z"))),
            store.rootDirectory,
        )
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(store.rootDirectory), "主线"))

        // A later commit touches only a chapter -> stale.
        val chapterOnly = together + ("branches/主线/chapters/002-入汴.md" to "h2")
        NovelWorkspaceLedger.save(
            NovelWorkspaceLedgerStore(
                head = "C2",
                commits = listOf(
                    commit("C1", null, together, "2026-08-19T00:00:00Z"),
                    commit("C2", "C1", chapterOnly, "2026-08-19T00:01:00Z"),
                ),
            ),
            store.rootDirectory,
        )
        assertTrue(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(store.rootDirectory), "主线"))

        // A commit updating plot afterwards -> cleared.
        val plotCaughtUp = chapterOnly + (plot to "p2")
        NovelWorkspaceLedger.save(
            NovelWorkspaceLedgerStore(
                head = "C3",
                commits = listOf(
                    commit("C1", null, together, "2026-08-19T00:00:00Z"),
                    commit("C2", "C1", chapterOnly, "2026-08-19T00:01:00Z"),
                    commit("C3", "C2", plotCaughtUp, "2026-08-19T00:02:00Z"),
                ),
            ),
            store.rootDirectory,
        )
        assertFalse(NovelWorkspaceLedger.isPlotStale(NovelWorkspaceLedger.load(store.rootDirectory), "主线"))
    }

    @Test
    fun `assembler injects the mandatory freshness warning when stale`() {
        val store = store()
        seedBook(store)
        val ch1 = "branches/主线/chapters/001-山呼.md"
        // plot/current.md exists from seedBook; add a chapter-only commit afterwards.
        val chapterOnly = mapOf(ch1 to "h1")
        NovelWorkspaceLedger.save(
            NovelWorkspaceLedgerStore(head = "C1", commits = listOf(commit("C1", null, chapterOnly, "2026-08-19T00:00:00Z"))),
            store.rootDirectory,
        )
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue(brief.contains("剧情落后于正文"))
        // The warning leads the brief so it cannot be missed.
        assertTrue(brief.startsWith("## ⚠️ 剧情落后于正文"))
    }

    @Test
    fun `middle chapter edit is detected, last chapter edit is not`() {
        val store = store()
        seedBook(store)
        store.write("branches/主线/chapters/001-山呼.md", "---\ntitle: 山呼\nordinal: 1\n---\n正文一")
        store.write("branches/主线/chapters/002-入汴.md", "---\ntitle: 入汴\nordinal: 2\n---\n正文二")
        store.write("branches/主线/chapters/003-陈桥.md", "---\ntitle: 陈桥\nordinal: 3\n---\n正文三")

        val ledger = NovelWorkspaceLedgerStore()
        // Editing the middle chapter (ordinal 1 of 1..3) invalidates from ordinal 2.
        val middleEdit = commit("C1", null, mapOf("branches/主线/chapters/001-山呼.md" to "x"), "2026-08-19T00:00:00Z")
        assertEquals(2, NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(store, ledger, "主线", middleEdit))

        // Editing the newest chapter (ordinal 3) is fast-forward-safe.
        val lastEdit = commit("C2", null, mapOf("branches/主线/chapters/003-陈桥.md" to "y"), "2026-08-19T00:01:00Z")
        assertNull(NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(store, ledger, "主线", lastEdit))

        // A commit with no chapter file -> not an edit.
        val plotOnly = commit("C3", null, mapOf("branches/主线/plot/current.md" to "z"), "2026-08-19T00:02:00Z")
        assertNull(NovelWorkspaceLedger.firstUnresolvedOrdinalAfterEdit(store, ledger, "主线", plotOnly))
    }

    @Test
    fun `unresolved store set read clear`() {
        val dir = tempFolder.newFolder("unresolved-book")
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))
        NovelWorkspaceUnresolvedStore.set(dir, "主线", 5, "COMMIT-X")
        val entry = NovelWorkspaceUnresolvedStore.entryFor(dir, "主线")
        assertEquals(5, entry?.fromOrdinal)
        assertEquals("COMMIT-X", entry?.sinceCommitId)
        NovelWorkspaceUnresolvedStore.clear(dir, "主线")
        assertNull(NovelWorkspaceUnresolvedStore.entryFor(dir, "主线"))
    }

    @Test
    fun `assembler injects the unresolved gate warning when set`() {
        val store = store()
        seedBook(store)
        NovelWorkspaceUnresolvedStore.set(store.rootDirectory, "主线", 2, "C1")
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue(brief.contains("未解决的中间章修改"))
        assertTrue(brief.startsWith("## ⛔"))
    }
}
