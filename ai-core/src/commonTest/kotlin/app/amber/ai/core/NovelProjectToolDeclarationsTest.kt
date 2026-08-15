package app.amber.ai.core

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 小说讨论会话内项目字段写工具的声明 schema 契约。 */
class NovelProjectToolDeclarationsTest {

    @Test
    fun renameProjectDeclarationPinsRequiredTitle() {
        val tool = createNovelRenameProjectToolDeclaration()
        assertEquals("novel_rename_project", tool.name)
        assertFalse(tool.needsApproval, "项目改名直接写入，不设审批门（行为由提示词模板引导先问用户）")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("title"), params.required)
        assertTrue("reason" in params.properties, "reason 可选")
        assertEquals("string", params.properties["title"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun setPolishPreferenceDeclarationPinsEmptyStringClearSemantics() {
        val tool = createNovelSetPolishPreferenceToolDeclaration()
        assertEquals("novel_set_polish_preference", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("preference"), params.required)
        val description = tool.description
        assertTrue("empty" in description, "描述必须写明空串清除语义")
        assertTrue("clear" in description, "描述必须写明清除行为")
    }

    @Test
    fun upsertUpcomingArcDeclarationPinsBeatBounds() {
        val tool = createNovelUpsertUpcomingArcToolDeclaration()
        assertEquals("novel_upsert_upcoming_arc", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("beats"), params.required)
        val beats = params.properties["beats"]!!.jsonObject
        assertEquals("array", beats["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(8, beats["maxItems"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
        val description = tool.description
        assertTrue("160" in description, "描述必须写明每条 160 字上限")
        assertTrue("8" in description, "描述必须写明 8 条上限")
        assertTrue("Replaces" in description, "描述必须写明整体替换语义")
    }

    @Test
    fun clearUpcomingArcDeclarationTakesNoArguments() {
        val tool = createNovelClearUpcomingArcToolDeclaration()
        assertEquals("novel_clear_upcoming_arc", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertTrue(params.properties.isEmpty(), "无参数工具不声明任何属性")
        assertTrue(params.required.isNullOrEmpty())
    }

    @Test
    fun reviseMaterialDeclarationPinsKindEnumAndUpdateSemantics() {
        val tool = createNovelReviseMaterialToolDeclaration()
        assertEquals("novel_revise_material", tool.name)
        assertFalse(tool.needsApproval, "资料写入直接落盘（先问用户由提示词模板引导）")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("kind", "title", "content"), params.required)

        val kind = params.properties["kind"]!!.jsonObject
        val kindEnum = kind["enum"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull }
        assertEquals(
            listOf("world", "character", "relationship", "masterOutline", "writingRequirements", "custom"),
            kindEnum
        )

        val customName = params.properties["custom_name"]!!.jsonObject
        assertEquals("string", customName["type"]!!.jsonPrimitive.content)

        val description = tool.description
        assertTrue("update" in description, "描述必须写明带 material_id 更新")
        assertTrue("create" in description, "描述必须写明无 material_id 新建")
        assertTrue("ask" in description, "描述必须引导意图不明时先问用户")
    }

    @Test
    fun proposeChapterPlanDeclarationPinsDraftOnlySemantics() {
        val tool = createNovelProposeChapterPlanToolDeclaration()
        assertEquals("novel_propose_chapter_plan", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(
            listOf(
                "outline_placement", "goal_and_conflict", "must_happen",
                "must_not_happen", "ending_hook", "visible_facts"
            ),
            params.required
        )
        val mustHappen = params.properties["must_happen"]!!.jsonObject
        assertEquals("array", mustHappen["type"]?.jsonPrimitive?.contentOrNull)

        val description = tool.description
        assertTrue("draft" in description, "描述必须写明永远存为草稿")
        assertTrue("confirmed" in description, "描述必须写明需人工确认")
        assertTrue("ghostwrite" in description, "描述必须说明草稿与代笔的关系")
    }

    @Test
    fun setChapterTitleDeclarationPinsTitleAndOptionalSelectors() {
        val tool = createNovelSetChapterTitleToolDeclaration()
        assertEquals("novel_set_chapter_title", tool.name)
        assertFalse(tool.needsApproval)

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("title"), params.required)
        assertTrue("chapter_ordinal" in params.properties)
        assertTrue("chapter_id" in params.properties)
        val description = tool.description
        assertTrue("title" in description)
        assertTrue("without rewriting" in description || "without rewriting the chapter body" in description)
        assertTrue("last" in description || "latest" in description)
    }

    @Test
    fun listChaptersDeclarationIsReadOnlyWithNoArguments() {
        val tool = createNovelListChaptersToolDeclaration()
        assertEquals("novel_list_chapters", tool.name)
        assertFalse(tool.needsApproval, "列章只读，不设审批门")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertTrue(params.properties.isEmpty(), "列章无参数")
        assertTrue(params.required.isNullOrEmpty())
        assertTrue("read-only" in tool.description || "does not change" in tool.description)
    }

    @Test
    fun readChapterDeclarationPinsOptionalSelectorsAndRange() {
        val tool = createNovelReadChapterToolDeclaration()
        assertEquals("novel_read_chapter", tool.name)
        assertFalse(tool.needsApproval, "读章只读，不设审批门")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertTrue(params.required.isNullOrEmpty(), "选择器和段落区间都是可选")
        for (key in listOf("chapter_ordinal", "chapter_id", "start_paragraph", "end_paragraph")) {
            assertTrue(key in params.properties, "读章必须声明 $key")
        }
        assertTrue("paragraph" in tool.description)
        assertTrue("read-only" in tool.description || "does not change" in tool.description)
    }

    @Test
    fun reviseChapterDeclarationRequiresRangeAndApprovalCard() {
        val tool = createNovelReviseChapterToolDeclaration()
        assertEquals("novel_revise_chapter", tool.name)
        assertTrue(tool.needsApproval, "改正文必须走审批卡")
        assertFalse(tool.allowsAutoApproval, "改正文不得自动批准")

        val params = tool.parameters()
        assertIs<InputSchema.Obj>(params)
        assertEquals(listOf("start_paragraph", "end_paragraph", "new_text"), params.required)
        assertTrue("chapter_ordinal" in params.properties)
        assertTrue("chapter_id" in params.properties)
        assertTrue("reason" in params.properties)
        val description = tool.description
        assertTrue("approval" in description, "描述必须写明审批卡")
        assertTrue("author" in description || "approve" in description)
    }

    @Test
    fun novelDeclarationsAreNotPartOfTheIosToolCatalog() {
        val novelNames = listOf(
            "novel_rename_project",
            "novel_set_polish_preference",
            "novel_upsert_upcoming_arc",
            "novel_clear_upcoming_arc",
            "novel_revise_material",
            "novel_propose_chapter_plan",
            "novel_set_chapter_title",
            "novel_list_chapters",
            "novel_read_chapter",
            "novel_revise_chapter",
        )
        val catalogNames = iosToolDeclarations(novelNames).map { it.name }
        assertTrue(catalogNames.isEmpty(), "小说写工具不得进入 iosToolDeclaration 注册表: $catalogNames")
    }
}
