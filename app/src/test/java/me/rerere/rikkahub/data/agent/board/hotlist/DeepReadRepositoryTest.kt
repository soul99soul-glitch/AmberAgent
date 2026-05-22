package me.rerere.rikkahub.data.agent.board.hotlist

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadImageCandidate
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadGenerationStage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionStatus
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionWriterTools
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.IMAGE_CONFIDENCE_HERO
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.IMAGE_CONFIDENCE_INLINE
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.isComplete
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.statusOf
import me.rerere.rikkahub.data.db.dao.HotListDAO
import me.rerere.rikkahub.data.db.entity.DeepReadCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListCacheEntity
import me.rerere.rikkahub.data.db.entity.HotListSourceEntity
import me.rerere.rikkahub.data.db.entity.HotTopicCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun getFreshDeepReadHonorsTtlAndDecodesJson() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val output = DeepReadOutput(
            summary = "这是一个有明确正文的深度阅读摘要。",
            corePoints = listOf(CorePoint("核心论点")),
        )

        repo.saveDeepRead("topic", "Topic", output, now = 1_000L)

        assertNotNull(repo.getFreshDeepRead("topic", now = 1_000L + HotListRepository.DEEP_READ_TTL_MS - 1))
        assertNull(repo.getFreshDeepRead("topic", now = 1_000L + HotListRepository.DEEP_READ_TTL_MS + 1))
    }

    @Test
    fun getFreshDeepReadReturnsNullForInvalidJson() = runTest {
        val dao = FakeHotListDao()
        dao.upsertDeepRead(
            DeepReadCacheEntity(
                topicId = "bad",
                title = "Bad",
                outputJson = "{not-json",
                createdAt = 1_000L,
                expiresAt = 2_000L,
                updatedAt = 1_000L,
            )
        )
        val repo = HotListRepository(dao, json)

        assertNull(repo.getFreshDeepRead("bad", now = 1_500L))
    }

    @Test
    fun getFreshDeepReadFallsBackToFreshTitleWhenTopicIdChanges() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val output = DeepReadOutput(summary = "这是缓存正文。")

        repo.saveDeepRead("old-topic-id", "同一个热榜话题", output, now = 1_000L)

        val cached = repo.getFreshDeepRead(
            topicId = "new-topic-id",
            title = "同一个热榜话题",
            now = 1_000L + HotListRepository.DEEP_READ_TTL_MS - 1,
        )

        assertEquals("这是缓存正文。", cached?.summary)
    }

    @Test
    fun materializeFreshDeepReadSkipsStaleDirectRowAndUsesFreshTitleFallback() = runTest {
        val dao = FakeHotListDao()
        val repo = HotListRepository(dao, json)
        repo.saveDeepRead("old-topic-id", "同一个热榜话题", DeepReadOutput(summary = "这是新缓存。"), now = 1_000L)
        dao.upsertDeepRead(
            DeepReadCacheEntity(
                topicId = "new-topic-id",
                title = "同一个热榜话题",
                outputJson = json.encodeToString(DeepReadOutput.serializer(), DeepReadOutput(summary = "这是过期直连缓存。")),
                createdAt = 1L,
                expiresAt = 2L,
                updatedAt = 1L,
            )
        )

        val cached = repo.materializeFreshDeepRead(
            topicId = "new-topic-id",
            title = "同一个热榜话题",
            now = 1_000L + HotListRepository.DEEP_READ_TTL_MS - 1,
        )

        assertEquals("这是新缓存。", cached?.summary)
        assertEquals("这是新缓存。", repo.getFreshDeepRead("new-topic-id", now = 1_500L)?.summary)
    }

    @Test
    fun sectionWriterToolsMergeSectionsAndMarkComplete() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(repo, "topic", "话题")
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_overview").execute(buildJsonObject {
            put("topic_type", "event")
            put("summary", "这是经过来源核查后的概览正文，说明事件是什么、为什么值得继续阅读，以及哪些关键事实已经被来源支撑。")
            put("key_entities", buildJsonArray { add("实体") })
        })
        tools.getValue("deep_read_write_narrative").execute(buildJsonObject {
            put("timeline", buildJsonArray {
                add(buildJsonObject {
                    put("date", "2026-05-21")
                    put("event", "事件进入公开讨论阶段，多个来源给出了可交叉核查的时间线。")
                    put("is_highlight", true)
                })
            })
        })
        tools.getValue("deep_read_write_analysis").execute(buildJsonObject {
            put("core_dispute", "核心分歧在于各方如何解释事件影响，以及哪些事实能够被独立来源持续支撑。")
            put("perspectives", buildJsonArray {
                add(buildJsonObject {
                    put("holder", "观察者")
                    put("viewpoint", "需要区分已确认事实、仍不确定的推断，以及来源之间尚未互相印证的内容。")
                })
            })
            put("implications", "这会影响读者对事件后续走向的判断，也会影响后续扩展阅读中应该优先核查的方向。")
        })
        tools.getValue("deep_read_write_extended_reading").execute(buildJsonObject {
            put("links", buildJsonArray {
                add(buildJsonObject {
                    put("title", "来源一")
                    put("url", "https://example.com/a")
                    put("source", "example.com")
                })
            })
        })
        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "passed")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "事件进入公开讨论阶段")
                    put("visible_excerpt", "事件进入公开讨论阶段")
                    put("status", "verified")
                    put("note", "已有来源支撑")
                    put("evidence_excerpt", "已有来源支撑并可交叉核查")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
                add(buildJsonObject {
                    put("claim", "核心分歧已经在分析段落中降格表达")
                    put("visible_excerpt", "核心分歧在于各方如何解释事件影响")
                    put("status", "verified")
                    put("note", "分析段落没有保留被证伪声明")
                    put("evidence_excerpt", "分析段落没有保留被证伪声明")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
            })
        })
        tools.getValue("deep_read_finish").execute(buildJsonObject { })

        val output = repo.getFreshDeepRead("topic", title = "话题")
        assertNotNull(output)
        assertEquals(DeepReadSectionStatus.READY, output?.statusOf(DeepReadGenerationStage.OVERVIEW))
        assertEquals(DeepReadSectionStatus.READY, output?.statusOf(DeepReadGenerationStage.NARRATIVE))
        assertEquals(DeepReadSectionStatus.READY, output?.statusOf(DeepReadGenerationStage.ANALYSIS))
        assertEquals(DeepReadSectionStatus.READY, output?.statusOf(DeepReadGenerationStage.EXTENDED_READING))
        assertTrue(output?.isComplete() == true)
        assertEquals("这是经过来源核查后的概览正文，说明事件是什么、为什么值得继续阅读，以及哪些关键事实已经被来源支撑。", output?.summary)
        assertEquals("https://example.com/a", output?.extendedReading?.single()?.url)
    }

    @Test
    fun sectionWriterRejectsWeakVerificationBeforeFinish() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(repo, "topic", "话题")
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "has_refuted")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "一个被证伪但没有修正的核心声明")
                    put("status", "refuted")
                    put("note", "来源不支持该声明")
                    put("evidence_urls", buildJsonArray { add("https://example.com/refute") })
                })
                add(buildJsonObject {
                    put("claim", "另一个核心声明")
                    put("status", "verified")
                    put("note", "来源支撑")
                    put("evidence_urls", buildJsonArray { add("https://example.com/support") })
                })
            })
        })

        assertEquals(0, writer.verificationCount)
        assertTrue(!writer.hasFreshVerification)
    }

    @Test
    fun sectionWriterRequiresTwoEvidenceBackedVerificationClaims() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(repo, "topic", "话题")
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "passed")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "一个有来源支撑的核心声明")
                    put("status", "verified")
                    put("note", "来源支撑")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
                add(buildJsonObject {
                    put("claim", "一个没有来源 URL 的核心声明")
                    put("status", "verified")
                    put("note", "没有证据 URL")
                    put("evidence_urls", buildJsonArray { })
                })
            })
        })

        assertEquals(0, writer.verificationCount)
        assertTrue(!writer.hasFreshVerification)
    }

    @Test
    fun sectionWriterRejectsUnseenEvidenceUrls() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(
            repository = repo,
            topicId = "topic",
            topicTitle = "话题",
            isEvidenceUrlAllowed = { it == "https://example.com/allowed" },
        )
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_overview").execute(buildJsonObject {
            put("summary", "这是经过来源核查后的概览正文，说明事件是什么、为什么值得继续阅读，以及哪些关键事实已经被来源支撑。")
        })
        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "passed")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "一个有来源支撑的核心声明")
                    put("visible_excerpt", "这是经过来源核查后的概览正文")
                    put("status", "verified")
                    put("note", "来源支撑")
                    put("evidence_excerpt", "来源支撑并可交叉核查")
                    put("evidence_urls", buildJsonArray { add("https://example.com/allowed") })
                })
                add(buildJsonObject {
                    put("claim", "另一个核心声明也必须来自允许来源")
                    put("visible_excerpt", "为什么值得继续阅读")
                    put("status", "verified")
                    put("note", "来源支撑")
                    put("evidence_excerpt", "来源支撑并可交叉核查")
                    put("evidence_urls", buildJsonArray { add("https://evil.example.com/made-up") })
                })
            })
        })

        assertEquals(0, writer.verificationCount)
        assertTrue(!writer.hasFreshVerification)
    }

    @Test
    fun sectionWriterRejectsUnsupportedEvidenceExcerpt() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(
            repository = repo,
            topicId = "topic",
            topicTitle = "话题",
            isEvidenceUrlAllowed = { it == "https://example.com/a" },
            evidenceContains = { _, _ -> false },
        )
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_overview").execute(buildJsonObject {
            put("summary", "这是经过来源核查后的概览正文，说明事件是什么、为什么值得继续阅读，以及哪些关键事实已经被来源支撑。")
        })
        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "passed")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "一个有来源支撑的核心声明")
                    put("visible_excerpt", "这是经过来源核查后的概览正文")
                    put("status", "verified")
                    put("note", "来源支撑")
                    put("evidence_excerpt", "来源正文里并不存在这段")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
                add(buildJsonObject {
                    put("claim", "另一个核心声明也需要证据摘录")
                    put("visible_excerpt", "为什么值得继续阅读")
                    put("status", "verified")
                    put("note", "来源支撑")
                    put("evidence_excerpt", "另一段不存在的来源文字")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
            })
        })

        assertEquals(0, writer.verificationCount)
        assertTrue(!writer.hasFreshVerification)
    }

    @Test
    fun visualsToolOnlyAcceptsCandidatePoolImages() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val hero = "https://news.example.com/hero.jpg"
        val inline = "https://news.example.com/inline.jpg"
        val writer = DeepReadSectionWriterTools(
            repository = repo,
            topicId = "topic",
            topicTitle = "话题",
            imageCandidates = listOf(
                imageCandidate(hero, IMAGE_CONFIDENCE_HERO, 80),
                imageCandidate(inline, IMAGE_CONFIDENCE_INLINE, 28),
            ),
        )
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_visuals").execute(buildJsonObject {
            put("hero_image_url", hero)
            put("hero_caption", "真实头图")
            put("hero_reason", "标题强匹配")
            put("image_assets", buildJsonArray {
                add(buildJsonObject {
                    put("url", inline)
                    put("caption", "正文图")
                    put("selection_reason", "上下文图")
                })
                add(buildJsonObject {
                    put("url", "https://invented.example.com/fake.jpg")
                    put("caption", "假图")
                })
            })
        })

        val output = repo.getFreshDeepRead("topic", title = "话题")
        assertEquals(hero, output?.heroImageUrl)
        assertEquals(IMAGE_CONFIDENCE_HERO, output?.heroImageConfidence)
        assertEquals(listOf(hero, inline), output?.imageAssets?.map { it.url })
        assertEquals(hero, output?.visualDiagnostics?.heroSelection?.imageUrl)

        tools.getValue("deep_read_write_visuals").execute(buildJsonObject {
            put("image_assets", buildJsonArray {
                add(buildJsonObject {
                    put("url", inline)
                    put("caption", "正文图二次选择")
                    put("selection_reason", "继续作为正文图")
                })
            })
        })

        val updated = repo.getFreshDeepRead("topic", title = "话题")
        assertEquals(hero, updated?.heroImageUrl)
        assertEquals(hero, updated?.visualDiagnostics?.heroSelection?.imageUrl)
    }

    @Test
    fun overviewToolDoesNotSelectHeroImage() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val hero = "https://news.example.com/hero.jpg"
        val writer = DeepReadSectionWriterTools(
            repository = repo,
            topicId = "topic",
            topicTitle = "话题",
            imageCandidates = listOf(imageCandidate(hero, IMAGE_CONFIDENCE_HERO, 80)),
        )
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_overview").execute(buildJsonObject {
            put("summary", "这是经过来源核查后的概览正文，说明事件是什么、为什么值得继续阅读，以及哪些关键事实已经被来源支撑。")
            put("hero_image_url", hero)
            put("hero_caption", "不应由 overview 写入")
        })

        val output = repo.getFreshDeepRead("topic", title = "话题")
        assertNull(output?.heroImageUrl)
        assertEquals(emptyList<String>(), output?.imageAssets?.map { it.url })
    }

    @Test
    fun overviewToolCapsSummaryAt250Characters() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(repo, "topic", "话题")
        val tools = writer.tools().associateBy { it.name }
        val longSummary = "这是一个用于验证概览长度限制的中文句子，包含事件背景、影响和关键事实。".repeat(12)

        tools.getValue("deep_read_write_overview").execute(buildJsonObject {
            put("summary", longSummary)
        })

        val output = repo.getFreshDeepRead("topic", title = "话题")
        assertTrue(output?.summary.orEmpty().length <= 250)
        assertEquals(DeepReadSectionStatus.READY, output?.statusOf(DeepReadGenerationStage.OVERVIEW))
    }

    @Test
    fun diagramToolDoesNotMarkGenerationComplete() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(repo, "topic", "话题")
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_diagram").execute(buildJsonObject {
            put("type", "process_flow")
            put("title", "流程图")
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", "a")
                    put("label", "起点")
                })
                add(buildJsonObject {
                    put("id", "b")
                    put("label", "结果")
                })
            })
            put("edges", buildJsonArray {
                add(buildJsonObject {
                    put("from", "a")
                    put("to", "b")
                    put("label", "导致")
                })
            })
        })

        val output = repo.getFreshDeepRead("topic", title = "话题")
        assertEquals("流程图", output?.diagram?.title)
        assertTrue(output?.isComplete() != true)
        assertEquals(DeepReadSectionStatus.PENDING, output?.statusOf(DeepReadGenerationStage.OVERVIEW))
    }

    @Test
    fun diagramToolCompactsDenseSpecsBeforePersisting() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val writer = DeepReadSectionWriterTools(repo, "topic", "话题")
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_diagram").execute(buildJsonObject {
            put("type", "process_flow")
            put("title", "国产大模型适配国产算力：政策与产业演进路径")
            put("nodes", buildJsonArray {
                (1..8).forEach { index ->
                    add(buildJsonObject {
                        put("id", "n$index")
                        put("label", "第${index}个很长很长的节点标题用于验证不会把整段正文塞进图解里")
                        put("note", "这是一段很长的节点说明，用来模拟模型把正文塞进图解节点导致移动端渲染拥挤的情况。".repeat(4))
                    })
                }
            })
            put("edges", buildJsonArray {
                add(buildJsonObject {
                    put("from", "n1")
                    put("to", "n5")
                    put("label", "跨层级关系应该被流程图主链路过滤掉")
                })
                (1..7).forEach { index ->
                    add(buildJsonObject {
                        put("from", "n$index")
                        put("to", "n${index + 1}")
                        put("label", "进入下一阶段并继续推进产业适配")
                    })
                }
            })
        })

        val diagram = repo.getFreshDeepRead("topic", title = "话题")?.diagram
        assertEquals(6, diagram?.nodes?.size)
        assertTrue(diagram?.nodes.orEmpty().all { it.label.length <= 34 && it.note.orEmpty().length <= 96 })
        assertEquals(listOf("n1->n2", "n2->n3", "n3->n4", "n4->n5", "n5->n6"), diagram?.edges?.map { "${it.from}->${it.to}" })
        assertTrue(diagram?.edges.orEmpty().all { it.label.orEmpty().length <= 42 })
    }

    @Test
    fun optionalVisualAndDiagramWritesRequireFreshVerificationBeforeFinish() = runTest {
        val repo = HotListRepository(FakeHotListDao(), json)
        val hero = "https://news.example.com/hero.jpg"
        val writer = DeepReadSectionWriterTools(
            repository = repo,
            topicId = "topic",
            topicTitle = "话题",
            imageCandidates = listOf(imageCandidate(hero, IMAGE_CONFIDENCE_HERO, 80)),
        )
        val tools = writer.tools().associateBy { it.name }

        tools.getValue("deep_read_write_overview").execute(buildJsonObject {
            put("summary", "这是经过来源核查后的概览正文，说明事件是什么、为什么值得继续阅读，以及哪些关键事实已经被来源支撑。")
        })
        tools.getValue("deep_read_write_narrative").execute(buildJsonObject {
            put("timeline", buildJsonArray {
                add(buildJsonObject {
                    put("date", "2026-05-21")
                    put("event", "事件进入公开讨论阶段，多个来源给出了可交叉核查的时间线。")
                })
            })
        })
        tools.getValue("deep_read_write_analysis").execute(buildJsonObject {
            put("core_dispute", "核心分歧在于各方如何解释事件影响，以及哪些事实能够被独立来源持续支撑。")
        })
        tools.getValue("deep_read_write_extended_reading").execute(buildJsonObject {
            put("links", buildJsonArray {
                add(buildJsonObject {
                    put("title", "来源一")
                    put("url", "https://example.com/a")
                    put("source", "example.com")
                })
            })
        })
        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "passed")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "事件进入公开讨论阶段")
                    put("visible_excerpt", "事件进入公开讨论阶段")
                    put("status", "verified")
                    put("note", "已有来源支撑")
                    put("evidence_excerpt", "已有来源支撑并可交叉核查")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
                add(buildJsonObject {
                    put("claim", "核心分歧已经在分析段落中降格表达")
                    put("visible_excerpt", "核心分歧在于各方如何解释事件影响")
                    put("status", "verified")
                    put("note", "分析段落没有保留被证伪声明")
                    put("evidence_excerpt", "分析段落没有保留被证伪声明")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
            })
        })
        assertTrue(writer.hasFreshVerification)

        tools.getValue("deep_read_write_visuals").execute(buildJsonObject {
            put("hero_image_url", hero)
            put("hero_caption", "真实头图")
        })
        tools.getValue("deep_read_write_diagram").execute(buildJsonObject {
            put("type", "process_flow")
            put("title", "流程图")
            put("nodes", buildJsonArray {
                add(buildJsonObject {
                    put("id", "a")
                    put("label", "起点")
                })
                add(buildJsonObject {
                    put("id", "b")
                    put("label", "结果")
                })
            })
        })

        assertTrue(!writer.hasFreshVerification)
        tools.getValue("deep_read_finish").execute(buildJsonObject { })
        assertTrue(repo.getFreshDeepRead("topic", title = "话题")?.isComplete() != true)

        tools.getValue("deep_read_verify_claims").execute(buildJsonObject {
            put("overall", "passed")
            put("corrections_applied", false)
            put("checked_claims", buildJsonArray {
                add(buildJsonObject {
                    put("claim", "图解只表达已写入的流程关系")
                    put("visible_excerpt", "核心分歧在于各方如何解释事件影响")
                    put("status", "verified")
                    put("note", "图解节点没有引入新事实")
                    put("evidence_excerpt", "图解节点没有引入新事实")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
                add(buildJsonObject {
                    put("claim", "头图来自候选池且没有使用任意外部 URL")
                    put("visible_excerpt", "这是经过来源核查后的概览正文")
                    put("status", "verified")
                    put("note", "图片 URL 已由候选池复核")
                    put("evidence_excerpt", "图片 URL 已由候选池复核")
                    put("evidence_urls", buildJsonArray { add("https://example.com/a") })
                })
            })
        })
        tools.getValue("deep_read_finish").execute(buildJsonObject { })
        assertTrue(repo.getFreshDeepRead("topic", title = "话题")?.isComplete() == true)
    }

    private fun imageCandidate(url: String, confidence: String, score: Int): DeepReadImageCandidate =
        DeepReadImageCandidate(
            imageUrl = url,
            sourceUrl = "https://news.example.com/a",
            sourceTitle = "来源标题",
            candidateKind = "article_image",
            confidence = confidence,
            score = score,
        )
}

private class FakeHotListDao : HotListDAO {
    private val providerCaches = MutableStateFlow<List<HotListCacheEntity>>(emptyList())
    private val hotTopics = MutableStateFlow<List<HotTopicCacheEntity>>(emptyList())
    private val sources = MutableStateFlow<List<HotListSourceEntity>>(emptyList())
    private val deepReads = mutableMapOf<String, DeepReadCacheEntity>()
    private val deepReadFlows = mutableMapOf<String, MutableStateFlow<DeepReadCacheEntity?>>()

    override fun observeProviderCaches(): Flow<List<HotListCacheEntity>> = providerCaches

    override suspend fun getProviderCache(providerId: String): HotListCacheEntity? =
        providerCaches.value.firstOrNull { it.providerId == providerId }

    override suspend fun upsertProviderCache(entity: HotListCacheEntity) {
        providerCaches.value = providerCaches.value.filterNot { it.providerId == entity.providerId } + entity
    }

    override fun observeHotTopics(limit: Int): Flow<List<HotTopicCacheEntity>> =
        hotTopics.map { it.take(limit) }

    override suspend fun getHotTopic(topicId: String): HotTopicCacheEntity? =
        hotTopics.value.firstOrNull { it.topicId == topicId }

    override suspend fun upsertHotTopics(entities: List<HotTopicCacheEntity>) {
        hotTopics.value = entities
    }

    override suspend fun clearHotTopics() {
        hotTopics.value = emptyList()
    }

    override suspend fun getDeepRead(topicId: String): DeepReadCacheEntity? = deepReads[topicId]

    override suspend fun getFreshDeepReadByTitle(title: String, now: Long): DeepReadCacheEntity? =
        deepReads.values
            .filter { it.title == title && it.expiresAt >= now }
            .maxByOrNull { it.updatedAt }

    override fun observeDeepRead(topicId: String): Flow<DeepReadCacheEntity?> =
        deepReadFlows.getOrPut(topicId) { MutableStateFlow(deepReads[topicId]) }

    override suspend fun upsertDeepRead(entity: DeepReadCacheEntity) {
        deepReads[entity.topicId] = entity
        deepReadFlows.getOrPut(entity.topicId) { MutableStateFlow(null) }.value = entity
    }

    override suspend fun deleteDeepRead(topicId: String) {
        deepReads.remove(topicId)
        deepReadFlows.getOrPut(topicId) { MutableStateFlow(null) }.value = null
    }

    override suspend fun pruneExpiredDeepReads(now: Long): Int {
        val expired = deepReads.values.filter { it.expiresAt < now }
        expired.forEach { deleteDeepRead(it.topicId) }
        return expired.size
    }

    override fun observeSources(): Flow<List<HotListSourceEntity>> = sources

    override suspend fun getEnabledSources(): List<HotListSourceEntity> =
        sources.value.filter { it.enabled }

    override suspend fun upsertSource(entity: HotListSourceEntity) {
        sources.value = sources.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun deleteSource(id: String) {
        sources.value = sources.value.filterNot { it.id == id }
    }
}
