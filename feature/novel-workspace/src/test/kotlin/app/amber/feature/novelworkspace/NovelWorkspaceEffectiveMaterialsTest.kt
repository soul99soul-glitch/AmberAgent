package app.amber.feature.novelworkspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceEffectiveMaterialsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store() = NovelWorkspaceStore(tempFolder.newFolder())

    private fun material(
        id: String,
        title: String,
        body: String,
        kind: String = "character",
        injection: String = "smart",
        override: Boolean = false,
        aliases: List<String> = emptyList(),
    ) = NovelWorkspaceMarkdown.render(
        fields = listOf(
            "id" to id,
            "kind" to "material",
            "materialKind" to kind,
            "title" to title,
            "injection" to injection,
            "override" to override.toString(),
        ),
        aliases = aliases,
        body = body,
    )

    @Test
    fun `current branch override replaces same id across different filenames in context and catalog`() {
        val store = store()
        store.write("setting/characters/旧名字.md", material("character-id", "旧名字", "主线设定"))
        val overridePath = "branches/支线/setting/characters/新名字.md"
        store.write(overridePath, material("character-id", "新名字", "支线设定", override = true))
        store.write("branches/其他/setting/characters/名字.md", material("character-id", "其他名字", "其他设定", override = true))
        store.write("branches/支线/plan/this-chapter.md", "新名字登场。")

        val nodes = NovelWorkspaceNodes.collect(store, "支线")
        assertEquals(listOf("新名字"), nodes.map { it.title })
        val catalog = NovelWorkspaceCatalog.load(store, NovelWorkspaceLedgerStore(), "支线")
        assertEquals("characters", catalog.settingGroups.single().directory)
        assertEquals(overridePath, catalog.settingGroups.single().entries.single().path)
        val brief = NovelWorkspaceContextAssembler.assemble(store, "支线")
        assertTrue(brief.contains("支线设定"))
        assertFalse(brief.contains("主线设定"))
        assertFalse(brief.contains("其他设定"))
        assertEquals("旧名字", NovelWorkspaceNodes.collect(store, "主线").single().title)
    }

    @Test
    fun `always is present without plan while off is excluded from matches relations and decisions`() {
        val store = store()
        store.write("setting/world/底线.md", material("pinned", "固定底线", "固定资料内容", "world", "always"))
        store.write("setting/characters/隐藏人物.md", material("hidden", "隐藏人物", "禁用人物内容", injection = "off", aliases = listOf("隐者")))
        store.write("setting/log/禁用决定.md", material("decision", "禁用决定", "禁用决定内容", "decisionLog", "off"))
        store.write("setting/characters/登场人物.md", """
            ---
            kind: character
            title: 登场人物
            relations:
              - {with: 隐藏人物, type: 朋友}
            ---
            可以注入。
        """.trimIndent())
        val noPlan = NovelWorkspaceContextAssembler.assemble(store, "主线", maxChars = 1)
        assertTrue(noPlan.contains("固定资料内容"))
        assertFalse(noPlan.contains("登场人物"))

        store.write("branches/主线/plan/this-chapter.md", "登场人物寻找隐者，遵守禁用决定。")
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue(brief.contains("可以注入"))
        assertFalse(brief.contains("禁用人物内容"))
        assertFalse(brief.contains("禁用决定内容"))
        val neighborhood = NovelWorkspaceNodes.neighborhood(NovelWorkspaceNodes.collect(store, "主线"), "登场人物与隐者")
        assertEquals(listOf("登场人物"), neighborhood.map { it.title })
        val catalog = NovelWorkspaceCatalog.load(store, NovelWorkspaceLedgerStore(), "主线")
        assertTrue(catalog.settingGroups.flatMap { it.entries }.any { it.title == "隐藏人物" })
    }

    @Test
    fun `writing preference uses branch override and off is editable without entering prompt`() {
        val store = store()
        store.write("setting/writing/写作要求.md", material("writing-id", "写作要求", "全局要求", "writingRequirements", "always"))
        val path = "branches/支线/setting/custom/本分支要求.md"
        store.write(path, material("writing-id", "支线要求", "支线要求内容", "writingRequirements", "off", override = true))
        assertEquals(path, NovelWorkspaceEffectiveMaterials.writingPreference(store, "支线")?.path)
        assertEquals("支线要求内容", NovelWorkspaceEffectiveMaterials.writingPreference(store, "支线")?.parsed?.body)
        assertEquals("", NovelWorkspaceEffectiveMaterials.writingPreferenceForPrompt(store, "支线"))
        assertEquals("全局要求", NovelWorkspaceEffectiveMaterials.writingPreferenceForPrompt(store, "主线"))
    }

    @Test
    fun `plain writing card remains readable and idless branch card replaces same relative path`() {
        val store = store()
        store.write("setting/writing/要求.md", "原有无元数据的写作要求。")
        assertEquals("原有无元数据的写作要求。", NovelWorkspaceEffectiveMaterials.writingPreferenceForPrompt(store, "主线"))
        store.write("branches/支线/setting/writing/要求.md", "分支要求。")
        assertEquals("分支要求。", NovelWorkspaceEffectiveMaterials.writingPreferenceForPrompt(store, "支线"))
        assertEquals(1, NovelWorkspaceEffectiveMaterials.collect(store, "支线").size)
    }

    @Test
    fun `always materials respect the corresponding context toggles`() {
        val store = store()
        store.write("setting/world/世界.md", material("world", "世界", "固定世界内容", "world", "always"))
        store.write("setting/log/决定.md", material("decision", "决定", "固定决定内容", "decisionLog", "always"))
        store.write("branches/主线/plot/foreshadowing/伏笔.md", material("foreshadow", "伏笔", "固定伏笔内容", "foreshadowing", "always"))
        val disabled = NovelWorkspaceInjectionFlags(plot = false, foreshadowing = false, neighborhood = false, decisions = false)
        assertEquals("", NovelWorkspaceContextAssembler.assemble(store, "主线", flags = disabled))

        val world = NovelWorkspaceContextAssembler.assemble(store, "主线", flags = disabled.copy(neighborhood = true))
        assertTrue(world.contains("固定世界内容"))
        assertFalse(world.contains("固定决定内容"))
        assertFalse(world.contains("固定伏笔内容"))
        val decision = NovelWorkspaceContextAssembler.assemble(store, "主线", flags = disabled.copy(decisions = true))
        assertTrue(decision.contains("固定决定内容"))
        assertFalse(decision.contains("固定世界内容"))
        assertFalse(decision.contains("固定伏笔内容"))
        val foreshadow = NovelWorkspaceContextAssembler.assemble(store, "主线", flags = disabled.copy(foreshadowing = true))
        assertTrue(foreshadow.contains("固定伏笔内容"))
        assertFalse(foreshadow.contains("固定世界内容"))
        assertFalse(foreshadow.contains("固定决定内容"))
    }
}
