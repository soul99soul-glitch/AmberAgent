package app.amber.agent.data.agent.board.hotlist

import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.hotlist.deepread.DeepReadSource
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisTemplate
import app.amber.feature.board.hotlist.deepread.template.DeepReadSynthesisWriter
import app.amber.feature.board.hotlist.deepread.template.DeepReadTemplateArticle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadSynthesisWriterTest {

    private fun source(id: Int, content: String = "来源$id 的正文内容"): DeepReadSource =
        DeepReadSource(
            title = "来源标题$id",
            url = "https://site$id.example.com/a$id",
            source = "站点$id",
            content = content,
            publishedAt = null,
            images = emptyList(),
        )

    private val sources = (1..4).map { source(it) }
    private val numbered = DeepReadSynthesisWriter.numbered(sources)

    // MARK: parsePick — iOS auto selection semantics

    @Test
    fun parsePickReturnsConcreteTemplate() {
        val reply = """{"template":"deepread_timeline","reason":"事件有多个时间节点"}"""
        assertEquals(DeepReadSynthesisTemplate.TIMELINE, DeepReadSynthesisWriter.parsePick(reply))
    }

    @Test
    fun parsePickFallsBackForMagazineAutoAndGarbage() {
        assertNull(DeepReadSynthesisWriter.parsePick("""{"template":"magazine"}"""))
        assertNull(DeepReadSynthesisWriter.parsePick("""{"template":"deepread_auto"}"""))
        assertNull(DeepReadSynthesisWriter.parsePick("""{"template":"deepread_unknown"}"""))
        assertNull(DeepReadSynthesisWriter.parsePick("not json at all"))
        assertNull(DeepReadSynthesisWriter.parsePick("""{"no_template":1}"""))
    }

    @Test
    fun parsePickToleratesProseAroundJson() {
        val reply = "我选择问答解读\n{\"template\":\"deepread_qa\",\"reason\":\"疑问多\"}\n完毕"
        assertEquals(DeepReadSynthesisTemplate.QA, DeepReadSynthesisWriter.parsePick(reply))
    }

    // MARK: extractJsonObject / repair

    @Test
    fun parseSkipsFencesAndStringBraces() {
        val reply = """前言{"title":"a{b}c","lede":"l","points":["x"]}后记"""
        val article = DeepReadSynthesisWriter.parse(
            reply, DeepReadSynthesisTemplate.BRIEF, "话题", numbered,
        )
        assertEquals("a{b}c", article!!.title)
    }

    @Test
    fun parseRepairsTruncatedBrief() {
        val truncated = """{"title":"简报标题","lede":"导语","points":["要点一","要点二","要点三","要点四","要点五"],"background":"背景","impact":"影响","uncertain":["""
        val article = DeepReadSynthesisWriter.parse(
            truncated, DeepReadSynthesisTemplate.BRIEF, "话题", numbered,
        )
        assertNotNull(article)
        assertEquals(5, article!!.brief!!.points.size)
    }

    // MARK: parse — per-template contracts

    @Test
    fun parseBriefRequiresPoints() {
        assertNull(
            DeepReadSynthesisWriter.parse(
                """{"title":"t","lede":"l"}""", DeepReadSynthesisTemplate.BRIEF, "话题", numbered,
            ),
        )
        val article = DeepReadSynthesisWriter.parse(
            """{"title":"t","lede":"l","points":["一","二"],"background":"b","impact":"i"}""",
            DeepReadSynthesisTemplate.BRIEF, "话题", numbered,
        )
        assertNotNull(article)
        assertEquals("deepread_brief", article!!.template)
        assertEquals("t", article.title)
        assertEquals(4, article.sources.size)
    }

    @Test
    fun parseQaKeepsCitedSourceIds() {
        val article = DeepReadSynthesisWriter.parse(
            """{"title":"t","questions":[{"q":"问一","a":"答一","sources":[1,99]},{"q":"问二","a":"答二","sources":[2]}]}""",
            DeepReadSynthesisTemplate.QA, "话题", numbered,
        )
        assertNotNull(article)
        assertEquals(listOf(1), article!!.qa!![0].sources) // 99 is not a real source id
        assertEquals(listOf(2), article.qa!![1].sources)
    }

    @Test
    fun parseDebateNeedsTwoCamps() {
        assertNull(
            DeepReadSynthesisWriter.parse(
                """{"title":"t","dispute":"d","camps":[{"stance":"pro","argument":"arg"}]}""",
                DeepReadSynthesisTemplate.DEBATE, "话题", numbered,
            ),
        )
        val article = DeepReadSynthesisWriter.parse(
            """{"title":"t","dispute":"d","camps":[{"stance":"pro","label":"支持","argument":"arg1","quote_by":"甲"},{"stance":"bogus","argument":"arg2"}],"takeaway":"tk"}""",
            DeepReadSynthesisTemplate.DEBATE, "话题", numbered,
        )
        assertNotNull(article)
        assertEquals("neutral", article!!.debate!!.camps[1].stance) // invalid stance folds to neutral
    }

    @Test
    fun parseTimelineNeedsThreeEvents() {
        assertNull(
            DeepReadSynthesisWriter.parse(
                """{"title":"t","events":[{"date":"d1","event":"e1"},{"date":"d2","event":"e2"}]}""",
                DeepReadSynthesisTemplate.TIMELINE, "话题", numbered,
            ),
        )
        val article = DeepReadSynthesisWriter.parse(
            """{"title":"t","events":[{"date":"d1","event":"e1","turning":true},{"date":"d2","event":"e2"},{"date":"d3","event":"e3"}],"turns":[{"date":"d1","why":"w"}]}""",
            DeepReadSynthesisTemplate.TIMELINE, "话题", numbered,
        )
        assertNotNull(article)
        assertTrue(article!!.timeline!!.events[0].turning)
        assertEquals(1, article.timeline!!.turns.size)
    }

    @Test
    fun parseReviewRequiresVerdict() {
        assertNull(
            DeepReadSynthesisWriter.parse(
                """{"title":"t","consensus":["c"]}""",
                DeepReadSynthesisTemplate.REVIEW, "话题", numbered,
            ),
        )
        val article = DeepReadSynthesisWriter.parse(
            """{"title":"t","verdict":"值得买","scores":[{"source":1,"score":"8.5","note":"好"},{"source":42,"score":"9"}],"specs":[{"name":"n","value":"v"}]}""",
            DeepReadSynthesisTemplate.REVIEW, "话题", numbered,
        )
        assertNotNull(article)
        assertEquals(1, article!!.review!!.scores.size) // unknown source id dropped
    }

    // MARK: source filtering

    @Test
    fun numberedDropsBlankAndUrlEchoContent() {
        val mixed = listOf(
            source(1),
            source(2, content = ""),
            source(3, content = "https://site3.example.com/a3"), // content == url
            source(4),
        )
        val kept = DeepReadSynthesisWriter.numbered(mixed)
        assertEquals(listOf(1, 4), kept.map { it.source.title.filter(Char::isDigit).toInt() })
        assertEquals(listOf(1, 2), kept.map { it.id }) // renumbered after filtering
    }

    @Test
    fun numberedCapsAtTwelveSources() {
        assertEquals(12, DeepReadSynthesisWriter.numbered((1..20).map { source(it) }).size)
    }

    // MARK: round-trip + markdown

    @Test
    fun decodeRoundTripsEncodedArticle() {
        val article = DeepReadTemplateArticle(
            template = DeepReadSynthesisTemplate.BRIEF.wireId,
            title = "标题",
            brief = DeepReadTemplateArticle.Brief(points = listOf("一")),
        )
        val decoded = DeepReadTemplateArticle.decode(article.encoded())
        assertEquals(article, decoded)
        assertNull(DeepReadTemplateArticle.decode("""{"shape":"other"}"""))
        assertNull(DeepReadTemplateArticle.decode(null))
        assertNull(DeepReadTemplateArticle.decode("junk"))
    }

    @Test
    fun markdownExportsAllSections() {
        val article = DeepReadTemplateArticle(
            template = DeepReadSynthesisTemplate.BRIEF.wireId,
            title = "标题",
            lede = "导语",
            sources = listOf(DeepReadTemplateArticle.Source(1, "来源一", "https://a.example.com", "a.example.com")),
            brief = DeepReadTemplateArticle.Brief(
                points = listOf("要点一"),
                background = "背景",
                impact = "影响",
                uncertain = listOf("待核实"),
            ),
        )
        val md = DeepReadSynthesisWriter.markdown(article)
        assertTrue(md.contains("# 标题"))
        assertTrue(md.contains("> 导语"))
        assertTrue(md.contains("## 要点"))
        assertTrue(md.contains("## 待核实"))
        assertTrue(md.contains("[来源一](https://a.example.com)"))
    }

    // MARK: template id normalization

    @Test
    fun normalizeKeepsSynthesisAndCustomIds() {
        assertEquals("deepread_qa", DeepReadTemplateIds.normalize("deepread_qa"))
        assertEquals("custom:abc", DeepReadTemplateIds.normalize("custom:abc"))
        assertEquals("compose_magazine", DeepReadTemplateIds.normalize(null))
        assertEquals("compose_magazine", DeepReadTemplateIds.normalize("ios_magazine"))
        assertEquals("editorial_slant", DeepReadTemplateIds.normalize("ios_reading"))
        assertTrue(DeepReadTemplateIds.isSynthesis("deepread_auto"))
        assertTrue(DeepReadTemplateIds.isSynthesis("deepread_review"))
        assertFalse(DeepReadTemplateIds.isSynthesis("compose_magazine"))
        assertFalse(DeepReadTemplateIds.isSynthesis("custom:x"))
    }
}
