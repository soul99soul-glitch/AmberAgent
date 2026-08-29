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
        val html = placeholders.entries.fold(templateHtml) { current, (key, value) ->
            current.replace("{{$key}}", value)
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
