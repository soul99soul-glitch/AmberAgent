package app.amber.search

import kotlinx.coroutines.runBlocking
import app.amber.search.SearchResult.SearchResultItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSearchAggregatorTest {
    @After
    fun tearDown() = FreeSearchAggregator.resetCooldowns()

    @Test
    fun parsesDuckDuckGoHtmlAndUnwrapsRedirects() {
        val html = """
            <div class="result results_links result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad</a></div>
            <div class="result results_links"><div class="links_main result__body">
              <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://zhuanlan.zhihu.com/p/1">一文读懂：量子计算 - 知乎</a></h2>
              <a class="result__snippet" href="#">量子计算的<b>原理</b></a>
            </div></div>
            <div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fq&amp;rut=x">Example</a></div>
        """.trimIndent()

        val results = FreeSearchAggregator.parseDuckDuckGoHtml(html)

        assertEquals(listOf("https://zhuanlan.zhihu.com/p/1", "https://example.com/q"), results.map { it.url })
        assertEquals("量子计算的原理", results.first().text)
    }

    @Test
    fun parses360HtmlUsingOriginalUrlAndSkipsSoComPages() {
        val html = """
            <ul><li class="res-list"><h3 class="res-title"><a href="https://ai.so.com/search/x">AI 聚合</a></h3></li>
            <li class="res-list"><h3 class="res-title"><a href="https://www.so.com/link?m=abc" data-mdurl="https://blog.csdn.net/a/1">量子计算机的原理-CSDN博客</a></h3>
              <p class="res-desc">量子叠加与纠缠</p></li>
            <li class="res-list"><h3 class="g-title"><a href="https://baike.so.com/doc/1">百科</a></h3></li></ul>
        """.trimIndent()

        val results = FreeSearchAggregator.parse360Html(html)

        assertEquals(1, results.size)
        assertEquals("https://blog.csdn.net/a/1", results.single().url)
        assertEquals("量子叠加与纠缠", results.single().text)
    }

    @Test
    fun parsesBraveWebSnippetsAndSkipsBraveLinks() {
        val html = """
            <div class="snippet" data-type="web"><a href="https://search.brave.com/x">x</a>
              <a href="https://www.zhihu.com/q/1"><div class="title search-snippet-title" title="量子计算原理">量子计算原理</div></a>
              <div class="content desktop-default-regular">叠加态</div></div>
        """.trimIndent()

        val results = FreeSearchAggregator.parseBraveHtml(html)

        assertEquals("https://www.zhihu.com/q/1", results.single().url)
        assertEquals("量子计算原理", results.single().title)
        assertEquals("叠加态", results.single().text)
    }

    @Test
    fun parsesQuarkHydrateJsonWithDestUrl() {
        val html = """
            <script type="application/json" id="s-data-1" data-used-by="hydrate">{"extraData":{"sc":"ss_doc"},"data":{"initialData":{"titleProps":{"content":"<em>量子</em>计算"},"sourceProps":{"dest_url":"https://example.cn/q"},"summaryProps":{"content":"摘要"}}}}</script>
            <script type="application/json" id="s-data-2" data-used-by="hydrate">{"extraData":{"sc":"ad"},"data":{"initialData":{"title":"广告","url":"https://ad.cn"}}}</script>
        """.trimIndent()

        val results = FreeSearchAggregator.parseQuarkHtml(html)

        assertEquals(1, results.size)
        assertEquals("量子计算", results.single().title)
        assertEquals("https://example.cn/q", results.single().url)
    }

    @Test
    fun parsesWikipediaExtractsAndDropsDisambiguation() {
        val body = """
            {"query":{"pages":[
              {"index":2,"title":"量子计算 (消歧义)","fullurl":"https://zh.wikipedia.org/wiki/b","extract":"量子计算可以指："},
              {"index":1,"title":"量子计算","fullurl":"https://zh.wikipedia.org/wiki/a","extract":"量子计算是利用量子力学的计算方式。"}
            ]}}
        """.trimIndent()

        val results = FreeSearchAggregator.parseWikipediaJson(body)

        assertEquals(listOf("https://zh.wikipedia.org/wiki/a"), results.map { it.url })
    }

    @Test
    fun unwrapsBingTrackingTarget() {
        assertEquals("https://example.com/a", BingSearchService.unwrapTrackingTarget("a1aHR0cHM6Ly9leGFtcGxlLmNvbS9h"))
        assertEquals(null, BingSearchService.unwrapTrackingTarget("https://example.com"))
    }

    @Test
    fun roundRobinMergeInterleavesEnginesAndDedupes() {
        val merged = FreeSearchAggregator.roundRobinMerge(
            perEngine = mapOf(
                FreeSearchEngine.BING to listOf(item("https://a.com"), item("https://b.com"), item("https://c.com")),
                FreeSearchEngine.BRAVE to listOf(item("https://www.a.com/"), item("https://d.com")),
            ),
            order = listOf(FreeSearchEngine.BING, FreeSearchEngine.BRAVE),
            limit = 10,
        )

        assertEquals(listOf("https://a.com", "https://b.com", "https://d.com", "https://c.com"), merged.map { it.url })
    }

    @Test
    fun blockedEngineIsSkippedDuringCooldownWhileOthersStillAnswer() = runBlocking {
        val requested = mutableListOf<String>()
        val fetcher = FreeSearchFetcher { request ->
            val host = request.url.host
            synchronized(requested) { requested += host }
            when {
                host == "html.duckduckgo.com" -> FreeSearchResponse(202, "")
                host == "www.so.com" -> FreeSearchResponse(
                    200,
                    """<li class="res-list"><h3 class="res-title"><a href="https://ok.cn/1">OK</a></h3></li>""",
                )
                else -> FreeSearchResponse(500, "")
            }
        }
        var now = 0L

        val first = FreeSearchAggregator.search("量子计算", 5, fetcher) { now }
        now += 60_000
        synchronized(requested) { requested.clear() }
        FreeSearchAggregator.search("量子计算", 5, fetcher) { now }

        assertEquals(listOf("https://ok.cn/1"), first.getOrThrow().items.map { it.url })
        assertFalse("DuckDuckGo should be cooling down", "html.duckduckgo.com" in requested)
        assertTrue("www.so.com" in requested)
        // 中文查询不打 HN。
        assertFalse("hn.algolia.com" in requested)
    }

    @Test
    fun allEnginesFailingReportsEachEngine() = runBlocking {
        val result = FreeSearchAggregator.search("test", 5, { FreeSearchResponse(500, "") })

        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("Bing"))
        assertTrue(message.contains("Hacker News"))
    }

    private fun item(url: String) = SearchResultItem(title = url, url = url, text = "")
}
