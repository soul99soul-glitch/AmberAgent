package app.amber.core.jev.baseline

import app.amber.ai.core.Tool
import app.amber.core.jev.JevCalibrationRecord
import app.amber.core.jev.JevCalibrationStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevHttpRequest
import app.amber.core.jev.JevLimits
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevToolSemanticSearch
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.feature.tools.SemanticToolCandidate
import app.amber.feature.tools.SemanticToolRankResult
import app.amber.feature.tools.ToolRegistry
import app.amber.feature.tools.ToolSearchIndex
import app.amber.feature.tools.ToolSemanticSearch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11 工具发现 baseline：语料（真实能力目录）× 生产全链。
 * 词面半边：ToolSearchIndex 的候选装配（lazy 目录语义候选顺序）；
 * Jev 半边：JevToolSemanticSearch 分块判分由语料 relevant 注记回放。
 * 冻结点：语料文件 + 本文件断言。
 */
class JevToolCorpusBaselineTest {

    @Serializable
    private data class Corpus(
        val version: Int,
        val tools: List<ToolDto>,
        val queries: List<QueryDto>,
    )

    @Serializable
    private data class ToolDto(val name: String, val description: String)

    @Serializable
    private data class QueryDto(
        val id: String,
        val text: String,
        val category: String? = null,
        val relevant: List<String>,
        val set: String,
    )

    private class RecordingSearch : ToolSemanticSearch {
        var lastCandidates: List<SemanticToolCandidate> = emptyList()

        override suspend fun rerank(
            query: String,
            category: String?,
            limit: Int,
            candidates: List<SemanticToolCandidate>,
        ): SemanticToolRankResult? {
            lastCandidates = candidates
            return null
        }
    }

    private class ReplayTransport(
        private val relevantNames: Set<String>,
    ) : JevTransport {
        val askedNames = mutableListOf<String>()
        val requestBodies = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            requestBodies += request.body
            val names = Regex("""Is the tool named ([^"]+) in state\.tools""")
                .findAll(request.body).map { it.groupValues[1] }.toList()
            assertTrue("chunk asked no candidates", names.isNotEmpty())
            askedNames += names
            val answer = names.joinToString(",") { name ->
                """"$name":{"type":"noul","noul":${if (name in relevantNames) 0.9 else 0.1}}"""
            }
            return JevTransportResponse.Http(200, """{"model":"jev-test","answers":{$answer}}""".toByteArray(), null)
        }
    }

    private class FreshCalibration : JevCalibrationStore {
        val records = mutableListOf<JevCalibrationRecord>()
        override fun append(record: JevCalibrationRecord) {
            records += record
        }
        override fun readAll(): List<JevCalibrationRecord> = records.toList()
        override fun clear() = records.clear()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun corpus(): Corpus = json.decodeFromString(
        Corpus.serializer(),
        javaClass.classLoader.getResourceAsStream("jev-corpus/tools.json")!!
            .readBytes().decodeToString(),
    )

    private fun registry(corpus: Corpus): ToolRegistry = ToolRegistry.from(
        corpus.tools.map { dto ->
            Tool(name = dto.name, description = dto.description, execute = { emptyList() })
        },
    )

    private fun activeRuntime(calibration: FreshCalibration, transport: JevTransport): JevRuntime = JevRuntime(
        coordinator = JevDecisionCoordinator(
            client = JevClient(transport = transport),
            apiKeyProvider = { "key" },
            clock = { 1_000_000L },
        ),
        settingsProvider = {
            Settings(
                jev = JevSetting(
                    enabled = true,
                    purposes = mapOf(JevPurpose.TOOL_DISCOVERY to JevMode.ACTIVE),
                    dataScopes = setOf(JevDataScope.TOOL_METADATA, JevDataScope.TASK_TEXT),
                ),
            )
        },
        calibration = calibration,
    )

    @Test
    fun corpusVersionAndScaleAreFrozen() {
        val corpus = corpus()
        assertEquals(1, corpus.version)
        assertTrue("tool corpus scale", corpus.tools.size >= 40)
        assertEquals("tool names must be unique", corpus.tools.size, corpus.tools.map { it.name }.distinct().size)
        assertTrue("query corpus scale", corpus.queries.size >= 10)
        val names = corpus.tools.map { it.name }.toSet()
        corpus.queries.forEach { query ->
            assertTrue("query ${query.id}: no relevant annotation", query.relevant.isNotEmpty())
            assertTrue(
                "query ${query.id}: relevant references unknown tools ${query.relevant.filterNot(names::contains)}",
                names.containsAll(query.relevant),
            )
        }
        val sets = corpus.queries.groupBy { it.`set` }.keys
        assertTrue("tuning/frozen split required", setOf("tuning", "frozen").all(sets::contains))
    }

    @Test
    fun lexicalCandidatesIncludeAnnotatedRelevant() = runTest {
        val corpus = corpus()
        val searchIndex = ToolSearchIndex(registry(corpus))
        corpus.queries.forEach { query ->
            val probe = RecordingSearch()
            searchIndex.searchPayloadWithSemantic(query.text, query.category, 5, probe)
            val candidateNames = probe.lastCandidates.map { it.name }.toSet()
            assertTrue(
                "query ${query.id}: relevant ${query.relevant} missed the candidate set",
                candidateNames.any { it in query.relevant },
            )
        }
    }

    @Test
    fun semanticReplayRanksRelevantAndRecordsCalibration() = runTest {
        val corpus = corpus()
        corpus.queries.forEach { query ->
            val calibration = FreshCalibration()
            val transport = ReplayTransport(query.relevant.toSet())
            val search = JevToolSemanticSearch(activeRuntime(calibration, transport))
            val candidates = candidatesFor(corpus, query)

            val result = search.rerank(query.text, query.category, 5, candidates)
            val asked = transport.askedNames.toSet()

            assertTrue("query ${query.id}: rerank must apply", result?.applied == true)
            assertTrue("query ${query.id}: no candidates asked", asked.isNotEmpty())
            val ranked = result!!.rankedNames.toSet()
            val poolRelevant = asked intersect query.relevant.toSet()
            assertTrue(
                "query ${query.id}: relevant-in-pool missing from ranking: ${poolRelevant - ranked}",
                ranked.containsAll(poolRelevant),
            )
            assertTrue(
                "query ${query.id}: irrelevant candidates leaked into ranking",
                (asked - query.relevant.toSet()).none { it in ranked },
            )

            val record = calibration.records.single()
            assertEquals(JevPurpose.TOOL_DISCOVERY, record.purpose)
            assertEquals(JevMode.ACTIVE, record.mode)
            assertEquals(
                "query ${query.id}: calibration scores must cover exactly the asked candidates",
                asked, record.scores.keys,
            )
            assertEquals(
                "query ${query.id}: incumbent must be the incoming first candidate",
                candidates.first().name, record.incumbentTop1,
            )
            assertTrue(
                "query ${query.id}: jevTop1 ${record.jevTop1} not annotated relevant",
                record.jevTop1 in query.relevant,
            )
        }
    }

    @Test
    fun gibberishQueryStillFillsCatalogAndStaysWithinBudget() = runTest {
        val corpus = corpus()
        val calibration = FreshCalibration()
        val transport = ReplayTransport(emptySet())
        val search = JevToolSemanticSearch(activeRuntime(calibration, transport))
        val probe = RecordingSearch()
        ToolSearchIndex(registry(corpus)).searchPayloadWithSemantic("zzqq 錯誤査詢 xkcdq", null, 5, probe)
        val candidates = probe.lastCandidates

        val result = search.rerank("zzqq 錯誤査詢 xkcdq", null, 5, candidates)
        assertNull("no relevant answer should apply", result)
        // 2 块 = ⌈min(catalog-1, TOOL_SEARCH_SEMANTIC_CANDIDATE_CAP=64) / JevLimits.MAX_QUESTIONS_PER_REQUEST=32⌉；
        // 任一常量或语料规模（>=41 才填满 64）变动都会改变分块数。
        assertEquals(
            "catalog fill must chunk the full candidate set (cap 64 / chunk 32)",
            2, transport.requestBodies.size,
        )
        transport.requestBodies.forEach { body ->
            assertTrue(
                "outbound body ${body.length} chars over budget",
                body.toByteArray(Charsets.UTF_8).size <= JevLimits.MAX_REQUEST_BODY_BYTES,
            )
        }
    }

    /** 生产候选装配：走 ToolSearchIndex 的语义入口抓真实候选序。 */
    private suspend fun candidatesFor(corpus: Corpus, query: QueryDto): List<SemanticToolCandidate> {
        val probe = RecordingSearch()
        ToolSearchIndex(registry(corpus)).searchPayloadWithSemantic(query.text, query.category, 5, probe)
        return probe.lastCandidates
    }
}

private typealias Settings = app.amber.core.settings.Settings
