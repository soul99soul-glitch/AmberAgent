package app.amber.feature.board.hotlist.deepread.template

import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import app.amber.feature.board.hotlist.deepread.DeepAnalysis
import app.amber.feature.board.hotlist.deepread.Perspective
import app.amber.feature.board.hotlist.deepread.ReadingLink
import app.amber.feature.board.hotlist.deepread.TimelineEvent
import app.amber.feature.board.hotlist.deepread.CorePoint
import app.amber.feature.board.hotlist.deepread.DeepReadDiagram
import app.amber.feature.board.hotlist.deepread.displayHeroCaption
import app.amber.feature.board.hotlist.deepread.displayHeroImageUrl
import app.amber.feature.board.hotlist.deepread.errorOf
import app.amber.feature.board.hotlist.deepread.statusOf
import app.amber.feature.board.hotlist.deepread.verifiedImageUrls
import app.amber.core.agent.utils.markdown.CjkCompatibleGfmFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.util.Locale

object DeepReadTemplateRenderer {
    private val markdownFlavour by lazy {
        CjkCompatibleGfmFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    }
    private val markdownParser by lazy { MarkdownParser(markdownFlavour) }
    private val markdownSafelist by lazy {
        Safelist.none()
            .addTags(
                "p",
                "br",
                "strong",
                "b",
                "em",
                "i",
                "del",
                "s",
                "blockquote",
                "ul",
                "ol",
                "li",
                "code",
                "pre",
                "a",
                "h2",
                "h3",
                "h4",
                "hr",
                "table",
                "thead",
                "tbody",
                "tr",
                "th",
                "td",
            )
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https")
    }
    private val markdownOutputSettings by lazy {
        Document.OutputSettings().prettyPrint(false)
    }
    private val singleParagraphRegex = Regex(
        pattern = """^<p>(.*)</p>$""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun renderSafeMarkdownHtml(markdown: String): String =
        markdown.markdownBlockHtml()

    fun sampleOutput(locale: Locale = Locale.CHINESE): DeepReadOutput {
        val chinese = locale.isChineseLocale()
        return DeepReadOutput(
        topicType = "event",
        generationComplete = true,
        summary = if (chinese) {
            "当模型开始理解空间、物体与人的意图，机器人不再只是工具，而可能成为家庭场景里的新成员。这篇样稿用于预览模板版式，不代表真实新闻内容。"
        } else {
            "When models learn to understand spaces, objects, and human intent, robots may stop being mere tools and become new members of the home. This sample previews the template layout and does not represent real news."
        },
        keyEntities = if (chinese) {
            listOf("具身智能", "家庭机器人", "大模型")
        } else {
            listOf("Embodied AI", "Home robots", "Foundation models")
        },
        timeline = listOf(
            TimelineEvent(
                if (chinese) "早期背景" else "Early background",
                if (chinese) {
                    "大模型把语言理解能力带入机器人系统，研究焦点从单一动作控制转向环境理解与任务规划。"
                } else {
                    "Foundation models brought language understanding into robotic systems, shifting research from isolated motion control toward environmental understanding and task planning."
                },
            ),
            TimelineEvent(
                if (chinese) "关键转折" else "Key shift",
                if (chinese) {
                    "多模态模型开始接入视觉、语音和传感器数据，让机器人能在复杂家庭环境中识别对象、理解指令并调整动作。"
                } else {
                    "Multimodal models began connecting vision, speech, and sensor data, helping robots identify objects, understand instructions, and adjust actions in complex homes."
                },
            ),
            TimelineEvent(
                if (chinese) "当前进展" else "Current progress",
                if (chinese) {
                    "产业公司尝试把机器人从实验室带到家庭与服务场景，但成本、安全和泛化能力仍是落地门槛。"
                } else {
                    "Companies are taking robots from laboratories into homes and service settings, but cost, safety, and generalization remain barriers to deployment."
                },
            ),
        ),
        corePoints = listOf(
            CorePoint(
                if (chinese) "真正的变化不是机器人学会行走，而是它开始理解人的日常。" else "The real change is not that robots can walk, but that they are beginning to understand everyday human life.",
                if (chinese) {
                    "家庭场景高度不确定，模型需要把环境、任务和人的意图放在同一个上下文里判断。"
                } else {
                    "Home environments are highly uncertain, so models must reason about surroundings, tasks, and human intent in one context."
                },
            ),
            CorePoint(
                if (chinese) "评价标准正在从单点能力转向长期协作。" else "The standard is shifting from isolated capabilities to long-term collaboration.",
                if (chinese) {
                    "一次成功演示不能证明可用性，稳定、可解释和安全的连续行为更重要。"
                } else {
                    "A successful demo does not prove usability; stable, explainable, and safe behavior over time matters more."
                },
            ),
        ),
        analysis = DeepAnalysis(
            coreDispute = if (chinese) {
                "核心分歧在于：具身智能到底已经进入产品化拐点，还是仍停留在高成本演示阶段。"
            } else {
                "The central dispute is whether embodied AI has reached a productization inflection point or remains a costly demonstration."
            },
            perspectives = listOf(
                Perspective(
                    if (chinese) "技术公司强调模型能力带来的泛化提升。" else "Technology companies emphasize the generalization gains delivered by stronger models.",
                    if (chinese) "模型厂商" else "Model companies",
                ),
                Perspective(
                    if (chinese) "硬件团队更关注可靠性、成本和安全冗余。" else "Hardware teams focus more on reliability, cost, and safety redundancy.",
                    if (chinese) "机器人厂商" else "Robot makers",
                ),
                Perspective(
                    if (chinese) "普通用户真正需要的是少打扰、能交付结果的家庭助手。" else "Users want a home assistant that causes little disruption and reliably delivers results.",
                    if (chinese) "消费者" else "Consumers",
                ),
            ),
            implications = if (chinese) {
                "如果具身智能继续进步，家庭设备可能从被动执行命令转向主动理解场景；但在此之前，产品仍需要把边界讲清楚。"
            } else {
                "If embodied AI keeps improving, home devices may move from passively following commands to actively understanding context; products still need clear boundaries first."
            },
        ),
        extendedReading = listOf(
            ReadingLink(
                if (chinese) "具身智能为什么重新成为焦点" else "Why embodied AI is back in focus",
                "https://example.com/embodied-ai",
                "Amber Sample",
            ),
            ReadingLink(
                if (chinese) "家庭机器人落地的三道门槛" else "Three barriers to deploying home robots",
                "https://example.com/home-robot",
                "Amber Sample",
            ),
        ),
        references = listOf(
            ReadingLink(
                if (chinese) "样稿来源：具身智能专题" else "Sample source: embodied AI",
                "https://example.com/source",
                "Amber Sample",
            ),
        ),
        )
    }

    /** Sample structured article for the template-settings preview (one per kind). */
    fun sampleTemplateArticle(
        template: DeepReadSynthesisTemplate,
        locale: Locale = Locale.CHINESE,
    ): DeepReadTemplateArticle {
        val chinese = locale.isChineseLocale()
        val sources = listOf(
            DeepReadTemplateArticle.Source(1, if (chinese) "厂商发布会纪要" else "Launch briefing", "https://example.com/a", if (chinese) "示例站" else "Example"),
            DeepReadTemplateArticle.Source(2, if (chinese) "媒体上手评测" else "Hands-on review", "https://example.com/b", if (chinese) "评测站" else "ReviewSite"),
            DeepReadTemplateArticle.Source(3, if (chinese) "社区讨论帖" else "Community thread", null, if (chinese) "论坛" else "Forum"),
        )
        val base = DeepReadTemplateArticle(
            template = template.wireId,
            title = if (chinese) "新一代移动芯片发布" else "Next-gen mobile chip unveiled",
            lede = if (chinese) "厂商在发布会上公布了新一代移动芯片，主打端侧 AI 算力与能效提升，多家媒体给出了不一致的评价。" else "The launch focuses on on-device AI performance and efficiency; reviewers disagree on the real-world gains.",
            sources = sources,
        )
        return when (template) {
            DeepReadSynthesisTemplate.AUTO, DeepReadSynthesisTemplate.BRIEF -> base.copy(
                template = DeepReadSynthesisTemplate.BRIEF.wireId,
                brief = DeepReadTemplateArticle.Brief(
                    points = if (chinese) listOf(
                        "NPU 算力提升约 40%，主打本地大模型推理",
                        "能效比优化，官方宣称续航提升 15%",
                        "首批搭载机型下月上市",
                        "影像管线新增 RAW 域 AI 处理",
                        "售价区间与上代基本持平",
                    ) else listOf(
                        "NPU gains ~40% for on-device inference",
                        "Efficiency up; maker claims 15% better battery",
                        "First phones ship next month",
                        "RAW-domain AI processing for the camera",
                        "Pricing roughly flat vs last generation",
                    ),
                    background = if (chinese) "端侧 AI 是今年旗舰芯片竞争的主线，各家都在把大模型推理搬进 SoC。" else "On-device AI is this year's flagship battleground.",
                    impact = if (chinese) "如果实测兑现，换机周期可能缩短；开发者将获得更统一的本地推理接口。" else "If benchmarks hold, upgrade cycles may shorten and apps get a common inference path.",
                    uncertain = if (chinese) listOf("实测续航提升未经第三方验证", "AI 功能在各地区上线时间不同") else listOf("Battery claims are unverified", "AI features roll out by region"),
                ),
            )
            DeepReadSynthesisTemplate.QA -> base.copy(
                qa = listOf(
                    DeepReadTemplateArticle.Answer(
                        if (chinese) "这次升级最大的变化是什么？" else "What's the biggest change?",
                        if (chinese) "NPU 从协处理器升级为独立计算单元，支持更大参数量级的模型常驻。" else "The NPU is now a standalone unit able to keep larger models resident.",
                        listOf(1, 2),
                    ),
                    DeepReadTemplateArticle.Answer(
                        if (chinese) "续航真的会提升吗？" else "Will battery life actually improve?",
                        if (chinese) "官方口径提升 15%，但媒体的续航模型测试只观察到 8% 左右，仍需更多实测。" else "The maker claims 15%; one review bench saw ~8%, so real gains remain uncertain.",
                        listOf(2),
                    ),
                    DeepReadTemplateArticle.Answer(
                        if (chinese) "值不值得首发入手？" else "Should you buy at launch?",
                        if (chinese) "如果看重 AI 功能可以等首批评测；单纯性能提升不足以构成换机理由。" else "Wait for reviews if AI features matter; raw performance alone isn't a reason to upgrade.",
                        listOf(2, 3),
                    ),
                ),
            )
            DeepReadSynthesisTemplate.DEBATE -> base.copy(
                debate = DeepReadTemplateArticle.Debate(
                    dispute = if (chinese) "端侧 AI 是真需求还是营销噱头？" else "Is on-device AI a real need or marketing?",
                    camps = listOf(
                        DeepReadTemplateArticle.Debate.Camp(
                            stance = "pro",
                            label = if (chinese) "支持方" else "Supporters",
                            holders = if (chinese) listOf("芯片厂商", "部分开发者") else listOf("Chip makers", "Some developers"),
                            argument = if (chinese) "本地推理保护隐私且离线可用，是交互范式的升级。" else "Local inference is private and works offline — a genuine interaction upgrade.",
                            quote = if (chinese) "这是十年来最重要的一次架构变化" else "The biggest architecture shift in a decade",
                            quoteBy = if (chinese) "厂商 CEO" else "The CEO",
                            sources = listOf(1),
                        ),
                        DeepReadTemplateArticle.Debate.Camp(
                            stance = "con",
                            label = if (chinese) "质疑方" else "Skeptics",
                            holders = if (chinese) listOf("评测媒体") else listOf("Reviewers"),
                            argument = if (chinese) "目前的 AI 功能停留在演示层面，日常感知不强，功耗成本却真实存在。" else "AI features feel like demos today while the silicon cost is real.",
                            quote = if (chinese) "我关掉这些开关后，体验没有任何不同" else "I toggled them off and noticed nothing",
                            quoteBy = if (chinese) "评测编辑" else "A reviewer",
                            sources = listOf(2),
                        ),
                        DeepReadTemplateArticle.Debate.Camp(
                            stance = "neutral",
                            label = if (chinese) "观望方" else "Wait-and-see",
                            holders = if (chinese) listOf("社区用户") else listOf("Community"),
                            argument = if (chinese) "关键看第三方应用是否跟进；生态成熟前不宜下结论。" else "It hinges on third-party apps; too early to judge.",
                            sources = listOf(3),
                        ),
                    ),
                    takeaway = if (chinese) "如果你已经有明确要用的 AI 应用，这次升级值得关注；否则可以等生态成熟。" else "Worth attention if you already use AI apps; otherwise wait for the ecosystem.",
                ),
            )
            DeepReadSynthesisTemplate.TIMELINE -> base.copy(
                timeline = DeepReadTemplateArticle.Timeline(
                    events = listOf(
                        DeepReadTemplateArticle.Timeline.Event(if (chinese) "3 月" else "March", if (chinese) "厂商预热新架构" else "Maker teases the new architecture", false, listOf(3)),
                        DeepReadTemplateArticle.Timeline.Event(if (chinese) "5 月" else "May", if (chinese) "发布会正式公布芯片与首批机型" else "Launch event details chip and first phones", true, listOf(1)),
                        DeepReadTemplateArticle.Timeline.Event(if (chinese) "6 月" else "June", if (chinese) "首批媒体评测解禁" else "First reviews drop", false, listOf(2)),
                    ),
                    turns = listOf(
                        DeepReadTemplateArticle.Timeline.Turn(if (chinese) "5 月" else "May", if (chinese) "发布会把竞争焦点从跑分转向端侧 AI 场景，此后所有报道都围绕这一点展开。" else "The launch reframed the race around on-device AI; coverage has followed since."),
                    ),
                ),
            )
            DeepReadSynthesisTemplate.REVIEW -> base.copy(
                review = DeepReadTemplateArticle.Review(
                    verdict = if (chinese) "性能稳步提升，AI 卖点有待验证" else "Steady gains; AI pitch unproven",
                    consensus = if (chinese) listOf("CPU/GPU 性能稳步提升", "发热控制优于上代", "AI 功能演示多于实用") else listOf("Steady CPU/GPU gains", "Better thermals", "AI features demo more than deliver"),
                    splits = listOf(
                        DeepReadTemplateArticle.Review.Split(
                            if (chinese) "续航" else "Battery",
                            listOf(
                                DeepReadTemplateArticle.Review.View(1, if (chinese) "官方宣称提升 15%" else "Maker claims +15%"),
                                DeepReadTemplateArticle.Review.View(2, if (chinese) "实测约 8%" else "Measured ~8%"),
                            ),
                        ),
                    ),
                    specs = listOf(
                        DeepReadTemplateArticle.Review.Spec("NPU", if (chinese) "45 TOPS" else "45 TOPS"),
                        DeepReadTemplateArticle.Review.Spec(if (chinese) "制程" else "Process", "3nm"),
                        DeepReadTemplateArticle.Review.Spec(if (chinese) "首批机型" else "First phones", if (chinese) "3 款" else "3 models"),
                    ),
                    scores = listOf(
                        DeepReadTemplateArticle.Review.Score(2, "8.5", if (chinese) "编辑评分" else "Editor's score"),
                    ),
                    conclusion = if (chinese) "如果你需要端侧 AI 可以首发入手；否则建议等价格调整。" else "Buy at launch for on-device AI; otherwise wait for a price cut.",
                ),
            )
        }
    }

    fun renderCustom(
        title: String,
        output: DeepReadOutput,
        templateHtml: String,
        fontCss: String = DEFAULT_FONT_CSS,
        darkTheme: Boolean = false,
        locale: Locale = Locale.CHINESE,
    ): DeepReadRenderedTemplate {
        DeepReadTemplateRepository.validateCustomTemplate(templateHtml)
        val safeImages = output.safeImageUrls()
        val hero = output.safeHeroUrl(safeImages).orEmpty()
        val runtimeCss = TEMPLATE_RUNTIME_CSS
        val darkCss = if (darkTheme) DARK_TEMPLATE_CSS else ""
        val placeholders = mapOf(
            "title" to title.escapeHtml(),
            "summary" to output.summary.escapeHtml(),
            "topic_type" to output.topicType.uppercase().escapeHtml(),
            "source_label" to output.sourceLabel().escapeHtml(),
            "hero_image_url" to hero.escapeHtml(),
            "hero_caption" to output.displayHeroCaption(hero).orEmpty().escapeHtml(),
            "narrative_html" to output.narrativeHtml(safeImages, locale),
            "timeline_html" to output.timelineHtml(safeImages, locale),
            "core_points_html" to output.corePointsHtml(safeImages, locale),
            "diagram_html" to output.diagramHtml(locale),
            "analysis_html" to output.analysisHtml(locale),
            "extended_reading_html" to output.extendedReadingHtml(locale),
            "font_css" to fontCss + "\n" + runtimeCss,
        )
        val html = Regex("\\{\\{([a-z_]+)\\}\\}").replace(templateHtml) { match ->
            placeholders[match.groupValues[1]] ?: match.value
        }.withRuntimeCss(fontCss + "\n" + runtimeCss, trailingCss = darkCss)
        return DeepReadRenderedTemplate(
            html = html,
            allowedImageUrls = safeImages,
            allowedLinkUrls = output.safeLinkUrls(),
        )
    }

    fun renderEditorialSlant(
        title: String,
        output: DeepReadOutput,
        fontCss: String = DEFAULT_FONT_CSS,
        darkTheme: Boolean = false,
        locale: Locale = Locale.CHINESE,
    ): DeepReadRenderedTemplate {
        val safeImages = output.safeImageUrls()
        val hero = output.safeHeroUrl(safeImages)
        val body = buildString {
            appendLine("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style>")
            appendLine(fontCss)
            appendLine(BASE_CSS)
            appendLine(templateRuntimeCss(darkTheme))
            appendLine("</style></head><body>")
            appendLine("<article>")
            if (hero != null) {
                appendLine("<figure class=\"hero\"><img src=\"${hero.escapeHtml()}\"/><div class=\"hero-cut\"><div><span class=\"hero-type\">${output.topicType.uppercase().escapeHtml()}</span><span class=\"hero-source\">${output.sourceLabel().escapeHtml()}</span></div><figcaption>${output.displayHeroCaption(hero).orEmpty().escapeHtml()}</figcaption></div></figure>")
            }
            appendLine("<section class=\"headline\">${if (hero == null) "<p class=\"kicker\">${output.topicType.uppercase().escapeHtml()} · DEEP READ</p>" else ""}<h1>${title.escapeHtml()}</h1>${output.summaryHtml(locale)}</section>")
            output.timelineHtml(safeImages, locale).takeIf { it.isNotBlank() }?.let {
                appendLine("<section><p class=\"section\">${locale.sectionLabel("timeline")}</p>")
                appendLine(it)
                appendLine("</section>")
            }
            output.corePointsHtml(safeImages, locale).takeIf { it.isNotBlank() }?.let {
                appendLine("<section><p class=\"section\">${locale.sectionLabel("core_points")}</p>")
                appendLine(it)
                appendLine("</section>")
            }
            output.diagramHtml(locale).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine("<section><p class=\"section\">${locale.sectionLabel("analysis")}</p>")
            appendLine(output.analysisHtml(locale))
            appendLine("</section>")
            appendLine("<section><p class=\"section\">${locale.sectionLabel("extended_reading")}</p>")
            appendLine(output.extendedReadingHtml(locale))
            appendLine("</section>")
            appendLine("</article></body></html>")
        }
        return DeepReadRenderedTemplate(
            html = body,
            allowedImageUrls = safeImages,
            allowedLinkUrls = output.safeLinkUrls(),
        )
    }

    /**
     * Renders a structured synthesis article — iOS `DeepReadTemplateArticleRenderer`
     * parity. Shares the magazine head/fonts/skeleton styles and adds the
     * template-specific blocks: numbered points, Q&A, camps, timeline, review
     * specs/scores, and the numbered source list with `[n]` citations.
     */
    fun renderTemplateArticle(
        article: DeepReadTemplateArticle,
        fontCss: String = DEFAULT_FONT_CSS,
        darkTheme: Boolean = false,
        locale: Locale = Locale.CHINESE,
    ): DeepReadRenderedTemplate {
        val sites = article.sources.associate { it.id to it.site }
        val html = buildString {
            appendLine("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style>")
            appendLine(fontCss)
            appendLine(BASE_CSS)
            appendLine(templateRuntimeCss(darkTheme))
            appendLine(SYNTHESIS_CSS)
            if (darkTheme) appendLine(SYNTHESIS_DARK_CSS)
            appendLine("</style></head><body>")
            append("<article class=\"template\">")
            append("<section class=\"headline\"><p class=\"kicker\">${locale.synthesisTemplateName(article.kind).escapeHtml()}</p><h1>${article.title.escapeHtml()}</h1>")
            if (article.lede.isNotBlank()) {
                append("<div class=\"summary markdown-body\"><p>${article.lede.markdownInlineHtml()}</p></div>")
            }
            append("</section>")

            article.brief?.let { brief ->
                if (brief.points.isNotEmpty()) {
                    append(synthesisSection(locale.synthesisLabel("points"), "<ol class=\"numbered\">" + brief.points.joinToString("") { "<li>${it.markdownInlineHtml()}</li>" } + "</ol>"))
                }
                if (brief.background.isNotBlank()) {
                    append(synthesisSection(locale.synthesisLabel("background"), synthesisBlock(brief.background)))
                }
                if (brief.impact.isNotBlank()) {
                    append(synthesisSection(locale.synthesisLabel("impact"), synthesisBlock(brief.impact)))
                }
                if (brief.uncertain.isNotEmpty()) {
                    append("<section class=\"uncertain\"><p class=\"section\">${locale.synthesisLabel("uncertain")}</p><ul>" + brief.uncertain.joinToString("") { "<li>${it.markdownInlineHtml()}</li>" } + "</ul></section>")
                }
            }
            article.qa?.let { qa ->
                if (qa.isNotEmpty()) {
                    append("<section class=\"qa\">")
                    qa.forEachIndexed { index, item ->
                        append("<div class=\"qa-item\"><p class=\"q\"><span class=\"qn\">Q${index + 1}</span><span>${item.question.markdownInlineHtml()}</span></p>")
                        append("<div class=\"a\">${synthesisBlock(item.answer, item.sources)}</div></div>")
                    }
                    append("</section>")
                }
            }
            article.debate?.let { debate ->
                if (debate.dispute.isNotBlank()) {
                    append("<section class=\"fact\"><p class=\"section\">${locale.synthesisLabel("dispute")}</p><p class=\"line\">${debate.dispute.markdownInlineHtml()}</p></section>")
                }
                append("<section class=\"camps\"><p class=\"section\">${locale.synthesisLabel("camps")}</p>")
                debate.camps.forEach { camp ->
                    val stanceLabel = when (camp.stance) {
                        "pro" -> locale.synthesisLabel("stance_pro")
                        "con" -> locale.synthesisLabel("stance_con")
                        else -> locale.synthesisLabel("stance_neutral")
                    }
                    append("<div class=\"camp ${camp.stance.escapeHtml()}\"><p class=\"camp-head\"><span class=\"badge\">$stanceLabel</span><b>${camp.label.escapeHtml()}</b></p>")
                    if (camp.holders.isNotEmpty()) {
                        append("<p class=\"holders\">${camp.holders.joinToString("、").escapeHtml()}</p>")
                    }
                    append(synthesisBlock(camp.argument, camp.sources))
                    if (camp.quote.isNotBlank()) {
                        append("<blockquote><p>“${camp.quote.escapeHtml()}”</p>")
                        if (camp.quoteBy.isNotBlank()) append("<small>—— ${camp.quoteBy.escapeHtml()}</small>")
                        append("</blockquote>")
                    }
                    append("</div>")
                }
                append("</section>")
                if (debate.takeaway.isNotBlank()) {
                    append("<section class=\"argument\"><div class=\"claim\"><b>${locale.synthesisLabel("takeaway")}</b>${synthesisBlock(debate.takeaway)}</div></section>")
                }
            }
            article.timeline?.let { timeline ->
                append("<section class=\"events\"><p class=\"section\">${locale.synthesisLabel("timeline")}</p><ol>")
                timeline.events.forEach { event ->
                    append("<li${if (event.turning) " class=\"turn\"" else ""}><span class=\"date\">${event.date.escapeHtml()}</span><p>${event.event.markdownInlineHtml()}${synthesisCite(event.sources)}</p></li>")
                }
                append("</ol></section>")
                if (timeline.turns.isNotEmpty()) {
                    append("<section class=\"parties\"><p class=\"section\">${locale.synthesisLabel("turns")}</p>")
                    timeline.turns.forEach { turn ->
                        append("<div class=\"party\"><b>${turn.date.escapeHtml()}</b>${synthesisBlock(turn.why)}</div>")
                    }
                    append("</section>")
                }
            }
            article.review?.let { review ->
                append("<section class=\"verdict\"><p class=\"section\">${locale.synthesisLabel("verdict")}</p><p class=\"line\">${review.verdict.markdownInlineHtml()}</p></section>")
                if (review.consensus.isNotEmpty()) {
                    append("<section class=\"proscons\"><p class=\"section\">${locale.synthesisLabel("consensus")}</p><div class=\"pair\"><div class=\"good\"><b>${locale.synthesisLabel("agree")}</b><ul>")
                    append(review.consensus.joinToString("") { "<li>${it.markdownInlineHtml()}</li>" })
                    append("</ul></div></div></section>")
                }
                review.splits.forEach { split ->
                    append("<section class=\"parties\"><p class=\"section\">${locale.synthesisLabel("split")} · ${split.topic.escapeHtml()}</p>")
                    split.views.forEach { view ->
                        append("<div class=\"party\"><b>${sites[view.source].orEmpty().escapeHtml()}</b><p>${view.view.markdownInlineHtml()}${synthesisCite(listOf(view.source))}</p></div>")
                    }
                    append("</section>")
                }
                if (review.specs.isNotEmpty()) {
                    append("<section class=\"specs\"><p class=\"section\">${locale.synthesisLabel("specs")}</p><div class=\"table-wrap\"><table>")
                    review.specs.forEach { spec ->
                        append("<tr><td>${spec.name.escapeHtml()}</td><td>${spec.value.escapeHtml()}</td></tr>")
                    }
                    append("</table></div></section>")
                }
                if (review.scores.isNotEmpty()) {
                    append("<section class=\"scores\"><p class=\"section\">${locale.synthesisLabel("scores")}</p><div class=\"score-grid\">")
                    review.scores.forEach { score ->
                        append("<div><span class=\"score\">${score.score.escapeHtml()}</span><b>${sites[score.source].orEmpty().escapeHtml()}</b>")
                        if (score.note.isNotBlank()) append("<small>${score.note.escapeHtml()}</small>")
                        append("</div>")
                    }
                    append("</div></section>")
                }
                if (review.conclusion.isNotBlank()) {
                    append("<section class=\"argument\"><div class=\"claim\"><b>${locale.synthesisLabel("worth_it")}</b>${synthesisBlock(review.conclusion)}</div></section>")
                }
            }

            if (article.sources.isNotEmpty()) {
                append("<section class=\"refs\"><p class=\"section\">${locale.synthesisLabel("sources")}</p><ol>")
                article.sources.forEach { source ->
                    append("<li><span class=\"n\">[${source.id}]</span><div>")
                    val title = source.title.escapeHtml()
                    val url = source.url
                    append(if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        "<a href=\"${url.escapeHtml()}\">$title</a>"
                    } else title)
                    append("<small>${source.site.escapeHtml()}</small></div></li>")
                }
                append("</ol></section>")
            }
            append("</article></body></html>")
        }
        return DeepReadRenderedTemplate(
            html = html,
            allowedImageUrls = emptySet(),
            allowedLinkUrls = article.sources.mapNotNull { it.url }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .toSet(),
        )
    }

    private fun synthesisSection(title: String, body: String): String =
        "<section><p class=\"section\">${title.escapeHtml()}</p>$body</section>"

    /// Long fields may hold lists or several paragraphs, so they render as blocks;
    /// citations trail the last paragraph (iOS `block(_:cite:)` parity).
    private fun synthesisBlock(text: String, cite: List<Int> = emptyList()): String {
        val html = text.markdownBlockHtml()
        val marks = synthesisCite(cite)
        if (marks.isEmpty()) return html
        val close = html.lastIndexOf("</p>")
        return if (close >= 0 && html.substring(close + 4).isBlank()) {
            html.substring(0, close) + marks + "</p>"
        } else {
            html + "<p>$marks</p>"
        }
    }

    private fun synthesisCite(ids: List<Int>): String =
        if (ids.isEmpty()) "" else "<sup class=\"cite\">${ids.joinToString("") { "[$it]" }}</sup>"

    private fun Locale.synthesisTemplateName(template: DeepReadSynthesisTemplate): String =
        if (isChineseLocale()) template.displayNameZh else when (template) {
            DeepReadSynthesisTemplate.AUTO -> "Auto"
            DeepReadSynthesisTemplate.BRIEF -> "Brief"
            DeepReadSynthesisTemplate.QA -> "Q&A"
            DeepReadSynthesisTemplate.DEBATE -> "Debate"
            DeepReadSynthesisTemplate.TIMELINE -> "Timeline"
            DeepReadSynthesisTemplate.REVIEW -> "Review"
        }

    private fun Locale.synthesisLabel(key: String): String = if (isChineseLocale()) {
        when (key) {
            "points" -> "要点"
            "background" -> "背景"
            "impact" -> "影响"
            "uncertain" -> "待核实"
            "dispute" -> "核心争议"
            "camps" -> "各方阵营"
            "takeaway" -> "你可以怎么看"
            "timeline" -> "时间线"
            "turns" -> "转折点"
            "verdict" -> "结论"
            "consensus" -> "各家共识"
            "agree" -> "一致认为"
            "split" -> "分歧"
            "specs" -> "关键规格"
            "scores" -> "打分对照"
            "worth_it" -> "买不买"
            "sources" -> "来源"
            "stance_pro" -> "支持"
            "stance_con" -> "反对"
            "stance_neutral" -> "中立"
            else -> key
        }
    } else {
        when (key) {
            "points" -> "Key points"
            "background" -> "Background"
            "impact" -> "Impact"
            "uncertain" -> "Unverified"
            "dispute" -> "Core dispute"
            "camps" -> "Camps"
            "takeaway" -> "How to read it"
            "timeline" -> "Timeline"
            "turns" -> "Turning points"
            "verdict" -> "Verdict"
            "consensus" -> "Consensus"
            "agree" -> "All agree"
            "split" -> "Split"
            "specs" -> "Key specs"
            "scores" -> "Scores"
            "worth_it" -> "Worth it?"
            "sources" -> "Sources"
            "stance_pro" -> "Support"
            "stance_con" -> "Oppose"
            "stance_neutral" -> "Neutral"
            else -> key
        }
    }

    private fun DeepReadOutput.safeImageUrls(): Set<String> = verifiedImageUrls()

    private fun DeepReadOutput.safeHeroUrl(safeImages: Set<String>): String? =
        displayHeroImageUrl()?.takeIf { it in safeImages }

    private fun DeepReadOutput.safeLinkUrls(): Set<String> =
        (extendedReading + references)
            .map { it.url }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .toSet()

    private fun DeepReadOutput.sourceLabel(): String {
        val count = (references.ifEmpty { extendedReading })
            .map { it.source ?: it.url }
            .filter { it.isNotBlank() }
            .distinct()
            .size
        return if (count > 0) "$count SOURCES" else "DEEP READ"
    }

    private fun DeepReadOutput.summaryHtml(locale: Locale): String {
        val chinese = locale.isChineseLocale()
        if (statusOf(DeepReadGenerationStage.OVERVIEW) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.OVERVIEW,
                runningText = if (chinese) "正在写入概览、关键实体和真实来源图片" else "Writing the overview, key entities, and verified source images",
                pendingText = if (chinese) "概览会先出现，随后补齐叙事、分析和扩展阅读" else "The overview comes first, followed by narrative, analysis, and extended reading",
                locale = locale,
            )
        }
        val summaryText = summary.trim()
        if (summaryText.isNotEmpty()) {
            return "<div class=\"summary markdown-body\">${summaryText.markdownBlockHtml()}</div>"
        }
        return sectionStateHtml(
            stage = DeepReadGenerationStage.OVERVIEW,
            runningText = if (chinese) "正在写入概览、关键实体和真实来源图片" else "Writing the overview, key entities, and verified source images",
            pendingText = if (chinese) "概览会先出现，随后补齐叙事、分析和扩展阅读" else "The overview comes first, followed by narrative, analysis, and extended reading",
            locale = locale,
        )
    }

    private fun DeepReadOutput.narrativeHtml(safeImages: Set<String>, locale: Locale): String {
        val chinese = locale.isChineseLocale()
        if (statusOf(DeepReadGenerationStage.NARRATIVE) != DeepReadSectionStatus.READY) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = if (chinese) "正在组织时间轴、关键脉络和中文叙事" else "Organizing the timeline, key points, and narrative",
                pendingText = if (chinese) "等待概览完成后补写事件脉络" else "The narrative will be added after the overview is ready",
                locale = locale,
            )
        }
        val timeline = timelineHtml(safeImages, locale)
        val points = corePointsHtml(safeImages, locale)
        if (timeline.isBlank() && points.isBlank()) return ""
        return buildString {
            if (timeline.isNotBlank()) {
                append("<div class=\"narrative-part\"><p class=\"section\">${locale.sectionLabel("timeline")}</p>")
                append(timeline)
                append("</div>")
            }
            if (points.isNotBlank()) {
                append("<div class=\"narrative-part\"><p class=\"section\">${locale.sectionLabel("core_points")}</p>")
                append(points)
                append("</div>")
            }
        }
    }

    private fun DeepReadOutput.timelineHtml(safeImages: Set<String>, locale: Locale): String {
        val chinese = locale.isChineseLocale()
        if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = if (chinese) "正在组织时间轴叙事或故事性脉络" else "Organizing the timeline and narrative arc",
                pendingText = if (chinese) "等待概览完成后补写事件脉络" else "The timeline will be added after the overview is ready",
                locale = locale,
            )
        }
        val events = timeline.orEmpty()
        if (events.isEmpty()) {
            if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) return ""
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = if (chinese) "正在组织时间轴叙事或故事性脉络" else "Organizing the timeline and narrative arc",
                pendingText = if (chinese) "等待概览完成后补写事件脉络" else "The timeline will be added after the overview is ready",
                locale = locale,
            )
        }
        return events.joinToString("\n") { event ->
            buildString {
                append("<div class=\"timeline-item\"><div class=\"timeline-marker\"></div><div class=\"timeline-body\"><p class=\"timeline-date\">")
                append(event.date.escapeHtml())
                append("</p><div class=\"timeline-copy markdown-body\">")
                append(event.event.markdownBlockHtml())
                append("</div>")
                event.imageUrl?.takeIf { it in safeImages }?.let { url ->
                    append("<figure><img src=\"")
                    append(url.escapeHtml())
                    append("\"/><figcaption>")
                    append(event.imageCaption.orEmpty().escapeHtml())
                    append("</figcaption></figure>")
                }
                append("</div></div>")
            }
        }
    }

    private fun DeepReadOutput.corePointsHtml(safeImages: Set<String>, locale: Locale): String {
        val chinese = locale.isChineseLocale()
        if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = if (chinese) "正在把来源消化成中文关键脉络" else "Turning the sources into synthesized key points",
                pendingText = if (chinese) "稍后会写入综合判断，而不是来源清单" else "Synthesized judgments will follow instead of a source list",
                locale = locale,
            )
        }
        val points = corePoints.orEmpty()
        if (points.isEmpty()) {
            if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) return ""
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = if (chinese) "正在把来源消化成中文关键脉络" else "Turning the sources into synthesized key points",
                pendingText = if (chinese) "稍后会写入综合判断，而不是来源清单" else "Synthesized judgments will follow instead of a source list",
                locale = locale,
            )
        }
        return points.joinToString("\n") { point ->
            buildString {
                append("<div class=\"core-point\"><h2>")
                append(point.point.markdownInlineHtml())
                append("</h2>")
                val supporting = point.supporting.orEmpty().markdownBlockHtml()
                if (supporting.isNotBlank()) {
                    append("<div class=\"core-support markdown-body\">")
                    append(supporting)
                    append("</div>")
                }
                point.imageUrl?.takeIf { it in safeImages }?.let { url ->
                    append("<figure><img src=\"")
                    append(url.escapeHtml())
                    append("\"/><figcaption>")
                    append(point.imageCaption.orEmpty().escapeHtml())
                    append("</figcaption></figure>")
                }
                append("</div>")
            }
        }
    }

    private fun DeepReadOutput.diagramHtml(locale: Locale): String =
        diagram?.takeIf { it.nodes.size >= 2 }?.renderDiagramHtml(locale).orEmpty()

    private fun DeepReadDiagram.renderDiagramHtml(locale: Locale): String {
        val visibleNodes = nodes.take(6)
        val nodeLabels = visibleNodes.associate { it.id to it.label }
        val visibleEdges = edges
            .filter { it.from in nodeLabels && it.to in nodeLabels }
            .take(6)
        val typeLabel = locale.diagramLabel(type)
        val body = when (type) {
            "causal_chain", "process_flow" -> renderDiagramSteps(visibleNodes)
            else -> renderDiagramCards(visibleNodes)
        }
        return """
            <section class="diagram-block">
              <p class="section">$typeLabel</p>
              <h2>${title.markdownInlineHtml()}</h2>
              <div class="diagram-frame">
                $body
                ${renderDiagramRelations(visibleEdges, nodeLabels, locale)}
              </div>
              ${caption?.takeIf { it.isNotBlank() }?.let { "<p class=\"diagram-caption\">${it.escapeHtml()}</p>" }.orEmpty()}
            </section>
        """.trimIndent()
    }

    private fun renderDiagramSteps(nodes: List<app.amber.feature.board.hotlist.deepread.DeepReadDiagramNode>): String =
        nodes.mapIndexed { index, node ->
            """
            <li class="diagram-step">
              <span class="diagram-step-index">${"%02d".format(index + 1)}</span>
              <div>
                ${node.group?.takeIf { it.isNotBlank() }?.let { "<small class=\"diagram-group\">${it.escapeHtml()}</small>" }.orEmpty()}
                <h3>${node.label.markdownInlineHtml()}</h3>
                ${node.note?.takeIf { it.isNotBlank() }?.let { "<div class=\"diagram-note markdown-body\">${it.markdownBlockHtml()}</div>" }.orEmpty()}
              </div>
            </li>
            """.trimIndent()
        }.joinToString(prefix = "<ol class=\"diagram-steps\">", postfix = "</ol>", separator = "\n")

    private fun renderDiagramCards(nodes: List<app.amber.feature.board.hotlist.deepread.DeepReadDiagramNode>): String =
        nodes.map { node ->
            """
            <div class="diagram-card">
              ${node.group?.takeIf { it.isNotBlank() }?.let { "<small class=\"diagram-group\">${it.escapeHtml()}</small>" }.orEmpty()}
              <h3>${node.label.markdownInlineHtml()}</h3>
              ${node.note?.takeIf { it.isNotBlank() }?.let { "<div class=\"diagram-note markdown-body\">${it.markdownBlockHtml()}</div>" }.orEmpty()}
            </div>
            """.trimIndent()
        }.joinToString(prefix = "<div class=\"diagram-grid\">", postfix = "</div>", separator = "\n")

    private fun renderDiagramRelations(
        edges: List<app.amber.feature.board.hotlist.deepread.DeepReadDiagramEdge>,
        nodeLabels: Map<String, String>,
        locale: Locale,
    ): String {
        if (edges.isEmpty()) return ""
        return edges.joinToString(prefix = "<ul class=\"diagram-relations\">", postfix = "</ul>", separator = "\n") { edge ->
            val from = nodeLabels[edge.from] ?: edge.from
            val to = nodeLabels[edge.to] ?: edge.to
            val label = edge.label?.takeIf { it.isNotBlank() }?.let {
                if (locale.isChineseLocale()) ":${it.escapeHtml()}" else ": ${it.escapeHtml()}"
            }.orEmpty()
            "<li><span>${from.escapeHtml()}</span><b>→</b><span>${to.escapeHtml()}</span>$label</li>"
        }
    }

    private fun DeepReadOutput.analysisHtml(locale: Locale): String = buildString {
        val chinese = locale.isChineseLocale()
        if (statusOf(DeepReadGenerationStage.ANALYSIS) == DeepReadSectionStatus.FAILED) {
            append(
                sectionStateHtml(
                    stage = DeepReadGenerationStage.ANALYSIS,
                    runningText = if (chinese) "正在继续写核心分歧、各方立场和影响判断" else "Writing the central dispute, viewpoints, and implications",
                    pendingText = if (chinese) "等脉络完成后开始深度分析" else "Deep analysis starts after the narrative is ready",
                    locale = locale,
                )
            )
            return@buildString
        }
        val hasAnalysis = !analysis.coreDispute.isNullOrBlank() ||
            analysis.perspectives.any { it.viewpoint.isNotBlank() } ||
            !analysis.implications.isNullOrBlank()
        if (!hasAnalysis) {
            append(
                sectionStateHtml(
                    stage = DeepReadGenerationStage.ANALYSIS,
                    runningText = if (chinese) "正在继续写核心分歧、各方立场和影响判断" else "Writing the central dispute, viewpoints, and implications",
                    pendingText = if (chinese) "等脉络完成后开始深度分析" else "Deep analysis starts after the narrative is ready",
                    locale = locale,
                )
            )
            return@buildString
        }
        analysis.coreDispute?.takeIf { it.isNotBlank() }?.let {
            append("<blockquote class=\"markdown-body\">")
            append(it.markdownBlockHtml())
            append("</blockquote>")
        }
        analysis.perspectives.take(6).forEach { perspective ->
            append("<div class=\"perspective\"><p class=\"holder\">")
            append(perspective.holder.orEmpty().escapeHtml())
            append("</p><div class=\"markdown-body\">")
            append(perspective.viewpoint.markdownBlockHtml())
            append("</div></div>")
        }
        analysis.implications?.takeIf { it.isNotBlank() }?.let {
            append("<div class=\"markdown-body\">")
            append(it.markdownBlockHtml())
            append("</div>")
        }
    }

    private fun DeepReadOutput.extendedReadingHtml(locale: Locale): String {
        val chinese = locale.isChineseLocale()
        if (statusOf(DeepReadGenerationStage.EXTENDED_READING) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.EXTENDED_READING,
                runningText = if (chinese) "正在整理可点击的来源与延伸阅读" else "Organizing clickable sources and further reading",
                pendingText = if (chinese) "最后会把引用和相关阅读写入缓存" else "Citations and further reading will be saved last",
                locale = locale,
            )
        }
        if (extendedReading.isEmpty()) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.EXTENDED_READING,
                runningText = if (chinese) "正在整理可点击的来源与延伸阅读" else "Organizing clickable sources and further reading",
                pendingText = if (chinese) "最后会把引用和相关阅读写入缓存" else "Citations and further reading will be saved last",
                locale = locale,
            )
        }
        return extendedReading.take(10).joinToString("\n") { link ->
            "<a class=\"reading-link\" href=\"${link.url.escapeHtml()}\"><p>${link.title.escapeHtml()}</p><small>${(link.source ?: link.url).escapeHtml()}</small></a>"
        }
    }

    private fun DeepReadOutput.sectionStateHtml(
        stage: DeepReadGenerationStage,
        runningText: String,
        pendingText: String,
        locale: Locale,
    ): String {
        val status = statusOf(stage)
        val chinese = locale.isChineseLocale()
        val label = when (status) {
            DeepReadSectionStatus.RUNNING -> runningText
            DeepReadSectionStatus.FAILED -> {
                val failure = errorOf(stage).orEmpty().ifBlank {
                    if (chinese) "请稍后重试" else "Please try again later"
                }
                if (chinese) "${stage.label}生成失败：$failure"
                else "${locale.stageLabel(stage)} failed: $failure"
            }
            DeepReadSectionStatus.READY -> ""
            DeepReadSectionStatus.PENDING -> pendingText
        }
        val tone = when (status) {
            DeepReadSectionStatus.FAILED -> " failed"
            DeepReadSectionStatus.RUNNING -> " running"
            else -> ""
        }
        return """
            <div class="section-state$tone">
              <div class="state-row"><span class="state-dot"></span><p>${label.escapeHtml()}</p></div>
              <div class="skeleton-line wide"></div>
              <div class="skeleton-line"></div>
              <div class="skeleton-line short"></div>
            </div>
        """.trimIndent()
    }

    private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

    private fun Locale.sectionLabel(key: String): String = if (isChineseLocale()) {
        when (key) {
            "timeline" -> "时间轴"
            "core_points" -> "关键脉络"
            "analysis" -> "深度分析"
            "extended_reading" -> "扩展阅读"
            else -> key
        }
    } else {
        when (key) {
            "timeline" -> "Timeline"
            "core_points" -> "Key points"
            "analysis" -> "Analysis"
            "extended_reading" -> "Extended reading"
            else -> key
        }
    }

    private fun Locale.diagramLabel(type: String): String = if (isChineseLocale()) {
        when (type) {
            "causal_chain" -> "因果链"
            "process_flow" -> "流程图"
            "stakeholder_map" -> "关系图"
            "system_structure" -> "结构图"
            "comparison_matrix" -> "对比图"
            else -> "图解"
        }
    } else {
        when (type) {
            "causal_chain" -> "Causal chain"
            "process_flow" -> "Process flow"
            "stakeholder_map" -> "Stakeholder map"
            "system_structure" -> "System structure"
            "comparison_matrix" -> "Comparison"
            else -> "Diagram"
        }
    }

    private fun Locale.stageLabel(stage: DeepReadGenerationStage): String = if (isChineseLocale()) {
        stage.label
    } else {
        when (stage) {
            DeepReadGenerationStage.OVERVIEW -> "Overview"
            DeepReadGenerationStage.NARRATIVE -> "Narrative"
            DeepReadGenerationStage.ANALYSIS -> "Analysis"
            DeepReadGenerationStage.EXTENDED_READING -> "Extended reading"
        }
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.markdownBlockHtml(): String {
        val source = trim()
        if (source.isBlank()) return ""
        val tree = markdownParser.buildMarkdownTreeFromString(source)
        val html = HtmlGenerator(source, tree, markdownFlavour).generateHtml()
        return Jsoup.clean(html, "", markdownSafelist, markdownOutputSettings)
    }

    private fun String.markdownInlineHtml(): String {
        val html = markdownBlockHtml().trim()
        return singleParagraphRegex.matchEntire(html)?.groupValues?.get(1) ?: html
    }

    private fun String.withRuntimeCss(css: String, trailingCss: String = ""): String {
        val hasCss = css.trim() in this
        val hasTrailingCss = trailingCss.isBlank() || trailingCss.trim() in this
        val hasImageFallback = "img:not([src])" in this
        if (hasCss && hasTrailingCss && hasImageFallback) return this
        val styleCss = buildString {
            if (!hasCss) appendLine(css)
            if (!hasTrailingCss) appendLine(trailingCss)
            if (!hasImageFallback) appendLine(EMPTY_IMAGE_FALLBACK_CSS)
        }
        val styleTag = "<style>$styleCss</style>"
        return when {
            "</head>" in this -> replace("</head>", "$styleTag</head>")
            "<body" in this -> replaceFirst(Regex("""<body([^>]*)>""", RegexOption.IGNORE_CASE), "<body\$1>$styleTag")
            else -> "$styleTag$this"
        }
    }

    private const val DEFAULT_FONT_CSS = """
        :root{
          --deep-read-serif:"Noto Serif SC","Source Han Serif SC","Songti SC",serif;
          --deep-read-sans:"PingFang SC","Source Han Sans SC","Noto Sans SC",system-ui,sans-serif;
          --deep-read-font-scale:1;
        }
    """

    private const val BASE_CSS = """
        html,body{margin:0;padding:0;background:#fafaf8;color:#191919;font-family:var(--deep-read-serif);}
        article{padding-bottom:34px;}
        .hero{margin:0 0 8px 0;position:relative;background:#f0f0ec;min-height:310px;overflow:hidden;}
        .hero img{display:block;width:100%;height:265px;object-fit:cover;}
        .hero-cut{height:106px;background:#fafaf8;clip-path:polygon(0 24%,100% 0,100% 100%,0 100%);margin-top:-54px;position:relative;padding:46px 22px 0;box-sizing:border-box;}
        .hero-cut>div{display:flex;align-items:center;justify-content:space-between;gap:14px;}
        .hero-type{font-family:var(--deep-read-sans);letter-spacing:.24em;color:#991b1b;font-size:10px;}
        .hero-source{font-family:var(--deep-read-sans);letter-spacing:.18em;color:#6b7280;font-size:10px;white-space:nowrap;}
        figcaption{font-size:9px;color:#6b7280;line-height:1.45;margin:10px 0 0;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
        .headline,section{padding:0 22px;}
        .kicker,.section,.date,.holder,small{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:#6b7280;font-size:10px;}
        h1{font-weight:500;font-size:32px;line-height:1.13;margin:12px 0 16px;}
        h2{font-weight:500;font-size:18px;line-height:1.34;margin:0 0 6px;}
        p{font-size:15px;line-height:1.68;margin:0 0 13px;}
        .summary{font-size:15px;line-height:1.68;}
        section{margin-top:28px;}
        .timeline{display:grid;grid-template-columns:32px 1fr;gap:10px;padding:11px 0;border-top:1px solid #ddd;}
        .num{font-family:var(--deep-read-sans);color:#ef4444;letter-spacing:.12em;font-size:12px;padding-top:4px;}
        .timeline-item{display:grid;grid-template-columns:32px minmax(0,1fr);gap:10px;padding:11px 0;border-top:1px solid #ddd;}
        .timeline-marker{width:18px;height:18px;border-radius:50%;border:1px solid #ef4444;margin-top:3px;}
        .timeline-body{min-width:0;}
        .timeline-date{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:#ef4444;font-size:10px;margin-bottom:4px;}
        .core-point{padding:12px 0;border-top:1px solid #ddd;}
        .inline{margin:12px 0 4px;background:#f0f0ec;}
        .inline img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
        .inline figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        .timeline-item figure,.core-point figure{margin:12px 0 4px;background:#f0f0ec;}
        .timeline-item img,.core-point img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
        .timeline-item figcaption,.core-point figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        .diagram-block{padding:0 22px;margin-top:30px;}
        .diagram-block h2{margin:4px 0 14px;font-size:18px;}
        .diagram-frame{background:#f4f1ec;border-top:1px solid #ddd;border-bottom:1px solid #ddd;padding:8px 12px 10px;}
        .diagram-steps{list-style:none;margin:0;padding:0;}
        .diagram-step{display:grid;grid-template-columns:34px minmax(0,1fr);gap:10px;padding:12px 0;border-top:1px solid rgba(107,114,128,.18);}
        .diagram-step:first-child{border-top:0;}
        .diagram-step-index{font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.12em;color:#ef4444;padding-top:2px;}
        .diagram-grid{display:grid;grid-template-columns:1fr;gap:8px;margin:0;}
        .diagram-card{background:#fafaf8;border:1px solid #ddd8cf;padding:11px 12px;}
        .diagram-step h3,.diagram-card h3{font-size:15px;line-height:1.42;margin:0 0 5px;font-weight:500;}
        .diagram-step p,.diagram-card p{font-family:var(--deep-read-sans);font-size:12px;line-height:1.58;color:#6b7280;margin:0;}
        .diagram-group{display:block;font-family:var(--deep-read-sans);letter-spacing:.14em;text-transform:uppercase;color:#991b1b;font-size:9px;margin-bottom:4px;}
        .diagram-relations{list-style:none;margin:10px 0 0;padding:8px 0 0;border-top:1px solid rgba(107,114,128,.18);}
        .diagram-relations li{font-family:var(--deep-read-sans);font-size:11px;line-height:1.55;color:#6b7280;margin:4px 0;}
        .diagram-relations b{font-weight:500;color:#ef4444;margin:0 5px;}
        .diagram-caption{font-family:var(--deep-read-sans);font-size:11px;line-height:1.5;color:#6b7280;margin:10px 0 0;}
        blockquote{font-size:18px;line-height:1.48;margin:0 0 16px;padding-left:12px;border-left:3px solid #ef4444;}
        .reading{display:grid;grid-template-columns:30px 1fr;gap:10px;border-top:1px solid #ddd;padding:10px 0;text-decoration:none;color:inherit;}
        .reading span{font-family:var(--deep-read-sans);color:#ef4444;font-size:12px;letter-spacing:.12em;}
        .reading p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        .reading small{letter-spacing:.08em;font-size:9px;}
        .reading-link{display:block;border-top:1px solid #ddd;padding:10px 0;text-decoration:none;color:inherit;}
        .reading-link p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
        .reading-link small{font-family:var(--deep-read-sans);letter-spacing:.08em;text-transform:uppercase;color:#6b7280;font-size:9px;}
    """

    private const val TEMPLATE_RUNTIME_CSS = """
        @keyframes deepReadPulse{0%,100%{opacity:.36}50%{opacity:.76}}
        .section-state{border-radius:18px;background:#f7f2f2;padding:18px 18px 16px;margin:10px 0;color:#6b7280;font-family:var(--deep-read-sans);}
        .section-state.running .state-dot{background:#ef4444;box-shadow:0 0 0 8px rgba(239,68,68,.12);}
        .section-state.failed{background:#fff1f2;color:#9f1239;}
        .state-row{display:flex;align-items:flex-start;gap:12px;margin-bottom:14px;}
        .state-row p{font-family:var(--deep-read-sans);font-size:13px;line-height:1.5;margin:0;color:inherit;}
        .state-dot{width:9px;height:9px;border-radius:50%;background:#cbd5e1;margin-top:6px;flex:0 0 auto;animation:deepReadPulse 1.4s ease-in-out infinite;}
        .skeleton-line{height:9px;border-radius:999px;background:#d8dee6;margin:9px 0;animation:deepReadPulse 1.4s ease-in-out infinite;}
        .skeleton-line.wide{width:92%;}
        .skeleton-line{width:74%;}
        .skeleton-line.short{width:42%;}
        .diagram-block{padding:0 22px;margin-top:30px;}
        .diagram-block h2{margin:4px 0 14px;font-size:18px;}
        .diagram-frame{background:#f4f1ec;border-top:1px solid #ddd;border-bottom:1px solid #ddd;padding:8px 12px 10px;}
        .diagram-steps{list-style:none;margin:0;padding:0;}
        .diagram-step{display:grid;grid-template-columns:34px minmax(0,1fr);gap:10px;padding:12px 0;border-top:1px solid rgba(107,114,128,.18);}
        .diagram-step:first-child{border-top:0;}
        .diagram-step-index{font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.12em;color:#ef4444;padding-top:2px;}
        .diagram-grid{display:grid;grid-template-columns:1fr;gap:8px;margin:0;}
        .diagram-card{background:#fafaf8;border:1px solid #ddd8cf;padding:11px 12px;}
        .diagram-step h3,.diagram-card h3{font-size:15px;line-height:1.42;margin:0 0 5px;font-weight:500;}
        .diagram-step p,.diagram-card p{font-family:var(--deep-read-sans);font-size:12px;line-height:1.58;color:#6b7280;margin:0;}
        .diagram-group{display:block;font-family:var(--deep-read-sans);letter-spacing:.14em;text-transform:uppercase;color:#991b1b;font-size:9px;margin-bottom:4px;}
        .diagram-relations{list-style:none;margin:10px 0 0;padding:8px 0 0;border-top:1px solid rgba(107,114,128,.18);}
        .diagram-relations li{font-family:var(--deep-read-sans);font-size:11px;line-height:1.55;color:#6b7280;margin:4px 0;}
        .diagram-relations b{font-weight:500;color:#ef4444;margin:0 5px;}
        .diagram-caption{font-family:var(--deep-read-sans);font-size:11px;line-height:1.5;color:#6b7280;margin:10px 0 0;}
        .markdown-body>:first-child{margin-top:0;}
        .markdown-body>:last-child{margin-bottom:0;}
        .markdown-body strong,.markdown-body b{font-weight:650;color:inherit;}
        .markdown-body em,.markdown-body i{font-style:italic;}
        .markdown-body s,.markdown-body del{text-decoration:line-through;}
        .markdown-body h2,.markdown-body h3,.markdown-body h4{font-weight:500;line-height:1.35;margin:14px 0 7px;}
        .markdown-body h2{font-size:18px;}
        .markdown-body h3{font-size:16px;}
        .markdown-body h4{font-size:15px;}
        .markdown-body ul,.markdown-body ol{font-size:15px;line-height:1.68;margin:0 0 13px 1.25em;padding:0;}
        .markdown-body li{margin:0 0 6px;padding-left:2px;}
        .markdown-body li>p{margin:0 0 6px;}
        .markdown-body code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88em;background:rgba(107,114,128,.12);padding:0 .22em;border-radius:4px;}
        .markdown-body pre{overflow:auto;background:#f0f0ec;padding:10px 12px;border-radius:10px;margin:0 0 13px;}
        .markdown-body pre code{background:transparent;padding:0;border-radius:0;}
        .markdown-body a{color:#991b1b;text-decoration:none;border-bottom:1px solid currentColor;}
        .markdown-body table{width:100%;border-collapse:collapse;font-family:var(--deep-read-sans);font-size:12px;line-height:1.5;margin:0 0 13px;}
        .markdown-body th,.markdown-body td{border-top:1px solid #ddd;padding:7px 6px;text-align:left;vertical-align:top;}
        .markdown-body blockquote{margin:0 0 13px;padding-left:10px;border-left:2px solid #ef4444;font-size:15px;line-height:1.68;}
        blockquote.markdown-body p{font-size:18px;line-height:1.48;}
        .timeline-copy,.core-support{min-width:0;}
        .diagram-note.markdown-body p{font-family:var(--deep-read-sans);font-size:12px;line-height:1.58;color:#6b7280;margin:0;}
    """

    private fun templateRuntimeCss(darkTheme: Boolean): String =
        if (darkTheme) TEMPLATE_RUNTIME_CSS + "\n" + DARK_TEMPLATE_CSS else TEMPLATE_RUNTIME_CSS

    private const val DARK_TEMPLATE_CSS = """
        :root{color-scheme:dark;}
        html,body{background:#0b0a09;color:#f1ece3;}
        article{background:#0b0a09;}
        .hero{background:#14110e;}
        .hero-cut{background:#0b0a09;}
        .hero-type,.diagram-group{color:#d18752;}
        .hero-source,figcaption,.kicker,.section,.date,.holder,small,.diagram-step p,.diagram-card p,.diagram-relations li,.diagram-caption,.reading-link small{color:#a89d90;}
        .timeline,.timeline-item,.core-point,.reading,.reading-link{border-top-color:#3a332b;}
        .timeline-marker{border-color:#d18752;}
        .num,.timeline-date,.diagram-step-index,.diagram-relations b,.reading span{color:#d18752;}
        blockquote{border-left-color:#d18752;}
        .inline,.timeline-item figure,.core-point figure,.diagram-frame{background:#181410;}
        .diagram-frame{border-top-color:#3a332b;border-bottom-color:#3a332b;}
        .diagram-step,.diagram-relations{border-top-color:rgba(168,157,144,.24);}
        .diagram-card{background:#120f0c;border-color:#3a332b;}
        .section-state{background:#181410;color:#a89d90;}
        .section-state.running .state-dot{background:#d18752;box-shadow:0 0 0 8px rgba(209,135,82,.15);}
        .section-state.failed{background:#281515;color:#f2b8b5;}
        .state-dot{background:#62574c;}
        .skeleton-line{background:#322b24;}
        .markdown-body code{background:rgba(168,157,144,.16);}
        .markdown-body pre{background:#181410;}
        .markdown-body a{color:#d18752;}
        .markdown-body th,.markdown-body td{border-top-color:#3a332b;}
        .markdown-body blockquote{border-left-color:#d18752;}
    """

    // iOS DeepReadTemplateArticleRenderer.css with the Android palette inlined
    // (accent #ef4444 / dark #d18752, border #ddd / #3a332b, muted #6b7280 / #a89d90).
    private const val SYNTHESIS_CSS = """
        .template section p{margin:0 0 10px;}
        .numbered{margin:0;padding-left:1.4em;}
        .numbered li{font-size:15px;line-height:1.65;margin:0 0 8px;}
        .cite{font-family:var(--deep-read-sans);font-size:10px;color:#ef4444;margin-left:2px;}
        .uncertain ul{margin:0;padding-left:1.4em;}
        .uncertain li{font-size:15px;line-height:1.65;margin:0 0 8px;color:#6b7280;}
        .qa-item{padding:14px 0;border-top:1px solid #ddd;}
        .qa-item .q{font-size:17px;line-height:1.5;font-weight:600;margin:0 0 8px;display:flex;gap:10px;}
        .qa-item .q .qn{font-family:var(--deep-read-sans);font-size:12px;font-weight:700;color:#ef4444;padding-top:3px;flex:0 0 auto;}
        .qa-item .a{padding-left:30px;}
        .qa-item .a p{font-size:15px;line-height:1.75;}
        .camp{border:1px solid #ddd;border-left:4px solid #6b7280;border-radius:12px;padding:12px 14px;margin:0 0 10px;}
        .camp.pro{border-left-color:#15803d;} .camp.con{border-left-color:#b91c1c;} .camp.neutral{border-left-color:#6b7280;}
        .camp.pro .badge{color:#15803d;} .camp.con .badge{color:#b91c1c;} .camp.neutral .badge{color:#6b7280;}
        .camp-head{display:flex;gap:8px;align-items:center;margin:0 0 4px;}
        .camp-head .badge{font-family:var(--deep-read-sans);font-size:11px;font-weight:700;}
        .camp-head b{font-size:16px;}
        .camp p{font-size:15px;line-height:1.7;}
        .camp p.holders{font-family:var(--deep-read-sans);font-size:12px;color:#6b7280;margin:0 0 8px;letter-spacing:0;text-transform:none;}
        .camp p.camp-head{margin:0 0 4px;}
        .camp blockquote{margin:8px 0 0;padding-left:10px;border-left:2px solid #ddd;}
        .camp blockquote p{font-style:italic;margin:0;font-size:15px;line-height:1.6;}
        .camp blockquote small{font-family:var(--deep-read-sans);font-size:11px;color:#6b7280;letter-spacing:0;text-transform:none;}
        .events ol{list-style:none;margin:0;padding:0;}
        .events li{padding:11px 0;border-top:1px solid #ddd;}
        .events li .date{display:block;font-family:var(--deep-read-sans);letter-spacing:.14em;text-transform:uppercase;color:#ef4444;font-size:10px;margin-bottom:4px;}
        .events li p{font-size:15px;line-height:1.65;margin:0;}
        .events li.turn .date::before{content:"● ";color:#ef4444;}
        .events li.turn p{font-weight:600;}
        .parties .party{padding:10px 0;border-top:1px solid #ddd;}
        .parties .party b{display:block;font-family:var(--deep-read-sans);font-size:12px;color:#991b1b;margin-bottom:3px;}
        .fact .line,.verdict .line{font-size:18px;line-height:1.5;font-weight:500;}
        .proscons .pair .good{border:1px solid #ddd;border-radius:12px;padding:12px 14px;}
        .proscons .pair .good>b{display:block;font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:#15803d;margin-bottom:8px;}
        .proscons ul{margin:0;padding-left:1.4em;}
        .proscons li{font-size:15px;line-height:1.65;margin:0 0 6px;}
        .specs .table-wrap{overflow-x:auto;}
        .specs table{width:100%;border-collapse:collapse;font-family:var(--deep-read-sans);font-size:13px;line-height:1.5;}
        .specs td{border-top:1px solid #ddd;padding:8px 8px 8px 0;vertical-align:top;}
        .specs td:first-child{color:#6b7280;white-space:nowrap;}
        .argument .claim{border:1px solid #ddd;border-left:4px solid #ef4444;border-radius:12px;padding:12px 14px;}
        .argument .claim>b{display:block;font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.12em;text-transform:uppercase;color:#ef4444;margin-bottom:8px;}
        .score-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:10px;}
        .score-grid div{border:1px solid #ddd;border-radius:12px;padding:12px;text-align:center;}
        .score-grid .score{display:block;font-size:26px;font-weight:700;color:#ef4444;}
        .score-grid b{display:block;font-family:var(--deep-read-sans);font-size:12px;margin-top:2px;}
        .score-grid small{display:block;font-family:var(--deep-read-sans);font-size:11px;color:#6b7280;margin-top:4px;letter-spacing:0;text-transform:none;}
        .refs ol{list-style:none;margin:0;padding:0;}
        .refs li{display:grid;grid-template-columns:30px minmax(0,1fr);gap:6px;padding:8px 0;border-top:1px solid #ddd;}
        .refs .n{font-family:var(--deep-read-sans);font-size:11px;color:#ef4444;padding-top:2px;}
        .refs a{font-size:14px;line-height:1.5;color:#191919;text-decoration:none;}
        .refs small{display:block;font-family:var(--deep-read-sans);font-size:11px;color:#6b7280;letter-spacing:0;text-transform:none;}
    """

    private const val SYNTHESIS_DARK_CSS = """
        .cite{color:#d18752;}
        .uncertain li{color:#a89d90;}
        .qa-item,.events li,.parties .party,.refs li{border-top-color:#3a332b;}
        .qa-item .q .qn{color:#d18752;}
        .camp{border-color:#3a332b;}
        .camp.pro{border-left-color:#4c9a72;} .camp.con{border-left-color:#d18752;} .camp.neutral{border-left-color:#a89d90;}
        .camp.pro .badge{color:#4c9a72;} .camp.con .badge{color:#d18752;} .camp.neutral .badge{color:#a89d90;}
        .camp p.holders,.camp blockquote small{color:#a89d90;}
        .camp blockquote{border-left-color:#3a332b;}
        .events li .date,.events li.turn .date::before{color:#d18752;}
        .parties .party b{color:#d18752;}
        .proscons .pair .good{border-color:#3a332b;}
        .proscons .pair .good>b{color:#4c9a72;}
        .specs td{border-top-color:#3a332b;}
        .specs td:first-child{color:#a89d90;}
        .argument .claim{border-color:#3a332b;border-left-color:#d18752;}
        .argument .claim>b{color:#d18752;}
        .score-grid div{border-color:#3a332b;}
        .score-grid .score{color:#d18752;}
        .score-grid small{color:#a89d90;}
        .refs .n{color:#d18752;}
        .refs a{color:#f1ece3;}
        .refs small{color:#a89d90;}
    """

    private const val EMPTY_IMAGE_FALLBACK_CSS = """
        img:not([src]),img[src=""]{display:none!important;}
        figure:has(> img:not([src])),figure:has(> img[src=""]){display:none!important;}
    """
}

data class DeepReadRenderedTemplate(
    val html: String,
    val allowedImageUrls: Set<String>,
    val allowedLinkUrls: Set<String> = emptySet(),
)
