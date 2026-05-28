package app.amber.feature.board.hotlist

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.amber.feature.board.hotlist.providers.CustomHotListFieldMapping
import app.amber.feature.board.hotlist.providers.CustomHotListProvider
import app.amber.feature.board.hotlist.providers.CustomHotListSourceTypes
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class CustomHotListProviderTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun jsonCustomSourceUsesConfiguredFieldMapping() {
        server.createContext("/hot.json") { exchange ->
            val body = """
                {"data":{"list":[
                  {"headline":"OpenAI 发布新模型","link":"https://example.com/a","score":"热","cover":"https://example.com/a.jpg"},
                  {"headline":"AI 芯片需求增长","link":"https://example.com/b","score":"新"}
                ]}}
            """.trimIndent()
            exchange.sendResponseHeaders(200, body.encodeToByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.encodeToByteArray()) }
        }

        val provider = provider(
            type = CustomHotListSourceTypes.JSON,
            url = "$baseUrl/hot.json",
            mapping = CustomHotListFieldMapping(
                itemsPath = "data.list",
                titlePath = "headline",
                urlPath = "link",
                heatPath = "score",
                imagePath = "cover",
            ),
        )
        val result = kotlinx.coroutines.runBlocking { provider.fetch(limit = 10) }

        assertEquals(2, result.items.size)
        assertEquals("OpenAI 发布新模型", result.items[0].title)
        assertEquals("热", result.items[0].heat)
        assertEquals(listOf("https://example.com/a.jpg"), result.items[0].images)
    }

    @Test
    fun rssCustomSourceParsesItems() {
        server.createContext("/feed.xml") { exchange ->
            val body = """
                <rss><channel>
                  <item><title><![CDATA[第一条新闻]]></title><link>https://example.com/1</link></item>
                  <item><title>第二条新闻</title><link>https://example.com/2</link></item>
                </channel></rss>
            """.trimIndent()
            exchange.sendResponseHeaders(200, body.encodeToByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.encodeToByteArray()) }
        }

        val provider = provider(type = CustomHotListSourceTypes.RSS, url = "$baseUrl/feed.xml")
        val result = kotlinx.coroutines.runBlocking { provider.fetch(limit = 10) }

        assertEquals(2, result.items.size)
        assertTrue(result.items.any { it.title == "第一条新闻" })
    }

    @Test
    fun atomCustomSourceParsesHrefLinksAndImageEnclosures() {
        server.createContext("/atom.xml") { exchange ->
            val body = """
                <feed>
                  <entry>
                    <title>Atom 新闻</title>
                    <link rel="self" href="https://example.com/feed-entry"/>
                    <link href="https://example.com/atom"/>
                    <link rel="alternate" type="text/html" href="https://example.com/article"/>
                    <enclosure type="image/jpeg" url="https://example.com/cover.jpg"/>
                  </entry>
                </feed>
            """.trimIndent()
            exchange.sendResponseHeaders(200, body.encodeToByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.encodeToByteArray()) }
        }

        val provider = provider(type = CustomHotListSourceTypes.RSS, url = "$baseUrl/atom.xml")
        val result = kotlinx.coroutines.runBlocking { provider.fetch(limit = 10) }

        assertEquals("https://example.com/article", result.items.single().url)
        assertEquals(listOf("https://example.com/cover.jpg"), result.items.single().images)
    }

    @Test
    fun rejectsOversizedResponsesBeforeParsing() {
        server.createContext("/large.json") { exchange ->
            val body = " ".repeat((2 * 1024 * 1024) + 8)
            exchange.sendResponseHeaders(200, body.encodeToByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.encodeToByteArray()) }
        }

        val provider = provider(type = CustomHotListSourceTypes.JSON, url = "$baseUrl/large.json")
        val error = kotlinx.coroutines.runBlocking {
            runCatching { provider.fetch(limit = 10) }.exceptionOrNull()
        }

        assertTrue(error?.message?.contains("too large") == true)
    }

    private fun provider(
        type: String,
        url: String,
        mapping: CustomHotListFieldMapping = CustomHotListFieldMapping(),
    ): CustomHotListProvider =
        CustomHotListProvider(
            source = HotListSourceEntity(
                id = "custom:test",
                displayName = "Test",
                sourceType = type,
                url = url,
                enabled = true,
                fieldMappingJson = json.encodeToString(mapping),
                createdAt = 1L,
                updatedAt = 1L,
            ),
            client = OkHttpClient(),
            json = json,
        )
}
