package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadGenerationStage
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadSectionStatus
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.Perspective
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagram
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.displayHeroCaption
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.displayHeroImageUrl
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.errorOf
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.statusOf
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.verifiedImageUrls

object DeepReadTemplateRenderer {
    fun sampleOutput(): DeepReadOutput = DeepReadOutput(
        topicType = "event",
        generationComplete = true,
        summary = "当模型开始理解空间、物体与人的意图，机器人不再只是工具，而可能成为家庭场景里的新成员。这篇样稿用于预览模板版式，不代表真实新闻内容。",
        keyEntities = listOf("具身智能", "家庭机器人", "大模型"),
        timeline = listOf(
            TimelineEvent("早期背景", "大模型把语言理解能力带入机器人系统，研究焦点从单一动作控制转向环境理解与任务规划。"),
            TimelineEvent("关键转折", "多模态模型开始接入视觉、语音和传感器数据，让机器人能在复杂家庭环境中识别对象、理解指令并调整动作。"),
            TimelineEvent("当前进展", "产业公司尝试把机器人从实验室带到家庭与服务场景，但成本、安全和泛化能力仍是落地门槛。"),
        ),
        corePoints = listOf(
            CorePoint("真正的变化不是机器人学会行走，而是它开始理解人的日常。", "家庭场景高度不确定，模型需要把环境、任务和人的意图放在同一个上下文里判断。"),
            CorePoint("评价标准正在从单点能力转向长期协作。", "一次成功演示不能证明可用性，稳定、可解释和安全的连续行为更重要。"),
        ),
        analysis = DeepAnalysis(
            coreDispute = "核心分歧在于：具身智能到底已经进入产品化拐点，还是仍停留在高成本演示阶段。",
            perspectives = listOf(
                Perspective("技术公司强调模型能力带来的泛化提升。", "模型厂商"),
                Perspective("硬件团队更关注可靠性、成本和安全冗余。", "机器人厂商"),
                Perspective("普通用户真正需要的是少打扰、能交付结果的家庭助手。", "消费者"),
            ),
            implications = "如果具身智能继续进步，家庭设备可能从被动执行命令转向主动理解场景；但在此之前，产品仍需要把边界讲清楚。"
        ),
        extendedReading = listOf(
            ReadingLink("具身智能为什么重新成为焦点", "https://example.com/embodied-ai", "Amber Sample"),
            ReadingLink("家庭机器人落地的三道门槛", "https://example.com/home-robot", "Amber Sample"),
        ),
        references = listOf(
            ReadingLink("样稿来源：具身智能专题", "https://example.com/source", "Amber Sample"),
        ),
    )

    fun renderCustom(
        title: String,
        output: DeepReadOutput,
        templateHtml: String,
        fontCss: String = DEFAULT_FONT_CSS,
    ): DeepReadRenderedTemplate {
        DeepReadTemplateRepository.validateCustomTemplate(templateHtml)
        val safeImages = output.safeImageUrls()
        val hero = output.safeHeroUrl(safeImages).orEmpty()
        val placeholders = mapOf(
            "title" to title.escapeHtml(),
            "summary" to output.summary.escapeHtml(),
            "topic_type" to output.topicType.uppercase().escapeHtml(),
            "source_label" to output.sourceLabel().escapeHtml(),
            "hero_image_url" to hero.escapeHtml(),
            "hero_caption" to output.displayHeroCaption(hero).orEmpty().escapeHtml(),
            "narrative_html" to output.narrativeHtml(safeImages),
            "timeline_html" to output.timelineHtml(safeImages),
            "core_points_html" to output.corePointsHtml(safeImages),
            "diagram_html" to output.diagramHtml(),
            "analysis_html" to output.analysisHtml(),
            "extended_reading_html" to output.extendedReadingHtml(),
            "font_css" to fontCss + "\n" + TEMPLATE_RUNTIME_CSS,
        )
        val html = placeholders.entries.fold(templateHtml) { current, (key, value) ->
            current.replace("{{$key}}", value)
        }.withRuntimeCss(fontCss + "\n" + TEMPLATE_RUNTIME_CSS)
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
    ): DeepReadRenderedTemplate {
        val safeImages = output.safeImageUrls()
        val hero = output.safeHeroUrl(safeImages)
        val body = buildString {
            appendLine("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/><style>")
            appendLine(fontCss)
            appendLine(BASE_CSS)
            appendLine(TEMPLATE_RUNTIME_CSS)
            appendLine("</style></head><body>")
            appendLine("<article>")
            if (hero != null) {
                appendLine("<figure class=\"hero\"><img src=\"${hero.escapeHtml()}\"/><div class=\"hero-cut\"><div><span class=\"hero-type\">${output.topicType.uppercase().escapeHtml()}</span><span class=\"hero-source\">${output.sourceLabel().escapeHtml()}</span></div><figcaption>${output.displayHeroCaption(hero).orEmpty().escapeHtml()}</figcaption></div></figure>")
            }
            appendLine("<section class=\"headline\">${if (hero == null) "<p class=\"kicker\">${output.topicType.uppercase().escapeHtml()} · DEEP READ</p>" else ""}<h1>${title.escapeHtml()}</h1>${output.summaryHtml()}</section>")
            output.timelineHtml(safeImages).takeIf { it.isNotBlank() }?.let {
                appendLine("<section><p class=\"section\">时间轴</p>")
                appendLine(it)
                appendLine("</section>")
            }
            output.corePointsHtml(safeImages).takeIf { it.isNotBlank() }?.let {
                appendLine("<section><p class=\"section\">关键脉络</p>")
                appendLine(it)
                appendLine("</section>")
            }
            output.diagramHtml().takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine("<section><p class=\"section\">深度分析</p>")
            appendLine(output.analysisHtml())
            appendLine("</section>")
            appendLine("<section><p class=\"section\">扩展阅读</p>")
            appendLine(output.extendedReadingHtml())
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

    private fun DeepReadOutput.summaryHtml(): String {
        if (statusOf(DeepReadGenerationStage.OVERVIEW) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.OVERVIEW,
                runningText = "正在写入概览、关键实体和真实来源图片",
                pendingText = "概览会先出现，随后补齐叙事、分析和扩展阅读",
            )
        }
        val summaryText = summary.trim()
        if (summaryText.isNotEmpty()) {
            return "<p class=\"summary\">${summaryText.escapeHtml()}</p>"
        }
        return sectionStateHtml(
            stage = DeepReadGenerationStage.OVERVIEW,
            runningText = "正在写入概览、关键实体和真实来源图片",
            pendingText = "概览会先出现，随后补齐叙事、分析和扩展阅读",
        )
    }

    private fun DeepReadOutput.narrativeHtml(safeImages: Set<String>): String {
        if (statusOf(DeepReadGenerationStage.NARRATIVE) != DeepReadSectionStatus.READY) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = "正在组织时间轴、关键脉络和中文叙事",
                pendingText = "等待概览完成后补写事件脉络",
            )
        }
        val timeline = timelineHtml(safeImages)
        val points = corePointsHtml(safeImages)
        if (timeline.isBlank() && points.isBlank()) return ""
        return buildString {
            if (timeline.isNotBlank()) {
                append("<div class=\"narrative-part\"><p class=\"section\">时间轴</p>")
                append(timeline)
                append("</div>")
            }
            if (points.isNotBlank()) {
                append("<div class=\"narrative-part\"><p class=\"section\">关键脉络</p>")
                append(points)
                append("</div>")
            }
        }
    }

    private fun DeepReadOutput.timelineHtml(safeImages: Set<String>): String {
        if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = "正在组织时间轴叙事或故事性脉络",
                pendingText = "等待概览完成后补写事件脉络",
            )
        }
        val events = timeline.orEmpty()
        if (events.isEmpty()) {
            if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) return ""
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = "正在组织时间轴叙事或故事性脉络",
                pendingText = "等待概览完成后补写事件脉络",
            )
        }
        return events.joinToString("\n") { event ->
            buildString {
                append("<div class=\"timeline-item\"><div class=\"timeline-marker\"></div><div class=\"timeline-body\"><p class=\"timeline-date\">")
                append(event.date.escapeHtml())
                append("</p><p>")
                append(event.event.escapeHtml())
                append("</p>")
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

    private fun DeepReadOutput.corePointsHtml(safeImages: Set<String>): String {
        if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = "正在把来源消化成中文关键脉络",
                pendingText = "稍后会写入综合判断，而不是来源清单",
            )
        }
        val points = corePoints.orEmpty()
        if (points.isEmpty()) {
            if (statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) return ""
            return sectionStateHtml(
                stage = DeepReadGenerationStage.NARRATIVE,
                runningText = "正在把来源消化成中文关键脉络",
                pendingText = "稍后会写入综合判断，而不是来源清单",
            )
        }
        return points.joinToString("\n") { point ->
            buildString {
                append("<div class=\"core-point\"><h2>")
                append(point.point.escapeHtml())
                append("</h2><p>")
                append(point.supporting.orEmpty().escapeHtml())
                append("</p>")
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

    private fun DeepReadOutput.diagramHtml(): String =
        diagram?.takeIf { it.nodes.size >= 2 }?.renderDiagramHtml().orEmpty()

    private fun DeepReadDiagram.renderDiagramHtml(): String {
        val visibleNodes = nodes.take(6)
        val nodeLabels = visibleNodes.associate { it.id to it.label }
        val visibleEdges = edges
            .filter { it.from in nodeLabels && it.to in nodeLabels }
            .take(6)
        val typeLabel = when (type) {
            "causal_chain" -> "因果链"
            "process_flow" -> "流程图"
            "stakeholder_map" -> "关系图"
            "system_structure" -> "结构图"
            "comparison_matrix" -> "对比图"
            else -> "图解"
        }
        val body = when (type) {
            "causal_chain", "process_flow" -> renderDiagramSteps(visibleNodes)
            else -> renderDiagramCards(visibleNodes)
        }
        return """
            <section class="diagram-block">
              <p class="section">$typeLabel</p>
              <h2>${title.escapeHtml()}</h2>
              <div class="diagram-frame">
                $body
                ${renderDiagramRelations(visibleEdges, nodeLabels)}
              </div>
              ${caption?.takeIf { it.isNotBlank() }?.let { "<p class=\"diagram-caption\">${it.escapeHtml()}</p>" }.orEmpty()}
            </section>
        """.trimIndent()
    }

    private fun renderDiagramSteps(nodes: List<me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagramNode>): String =
        nodes.mapIndexed { index, node ->
            """
            <li class="diagram-step">
              <span class="diagram-step-index">${"%02d".format(index + 1)}</span>
              <div>
                ${node.group?.takeIf { it.isNotBlank() }?.let { "<small class=\"diagram-group\">${it.escapeHtml()}</small>" }.orEmpty()}
                <h3>${node.label.escapeHtml()}</h3>
                ${node.note?.takeIf { it.isNotBlank() }?.let { "<p>${it.escapeHtml()}</p>" }.orEmpty()}
              </div>
            </li>
            """.trimIndent()
        }.joinToString(prefix = "<ol class=\"diagram-steps\">", postfix = "</ol>", separator = "\n")

    private fun renderDiagramCards(nodes: List<me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagramNode>): String =
        nodes.map { node ->
            """
            <div class="diagram-card">
              ${node.group?.takeIf { it.isNotBlank() }?.let { "<small class=\"diagram-group\">${it.escapeHtml()}</small>" }.orEmpty()}
              <h3>${node.label.escapeHtml()}</h3>
              ${node.note?.takeIf { it.isNotBlank() }?.let { "<p>${it.escapeHtml()}</p>" }.orEmpty()}
            </div>
            """.trimIndent()
        }.joinToString(prefix = "<div class=\"diagram-grid\">", postfix = "</div>", separator = "\n")

    private fun renderDiagramRelations(
        edges: List<me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadDiagramEdge>,
        nodeLabels: Map<String, String>,
    ): String {
        if (edges.isEmpty()) return ""
        return edges.joinToString(prefix = "<ul class=\"diagram-relations\">", postfix = "</ul>", separator = "\n") { edge ->
            val from = nodeLabels[edge.from] ?: edge.from
            val to = nodeLabels[edge.to] ?: edge.to
            val label = edge.label?.takeIf { it.isNotBlank() }?.let { "：${it.escapeHtml()}" }.orEmpty()
            "<li><span>${from.escapeHtml()}</span><b>→</b><span>${to.escapeHtml()}</span>$label</li>"
        }
    }

    private fun DeepReadOutput.analysisHtml(): String = buildString {
        if (statusOf(DeepReadGenerationStage.ANALYSIS) == DeepReadSectionStatus.FAILED) {
            append(
                sectionStateHtml(
                    stage = DeepReadGenerationStage.ANALYSIS,
                    runningText = "正在继续写核心分歧、各方立场和影响判断",
                    pendingText = "等脉络完成后开始深度分析",
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
                    runningText = "正在继续写核心分歧、各方立场和影响判断",
                    pendingText = "等脉络完成后开始深度分析",
                )
            )
            return@buildString
        }
        analysis.coreDispute?.takeIf { it.isNotBlank() }?.let {
            append("<blockquote>")
            append(it.escapeHtml())
            append("</blockquote>")
        }
        analysis.perspectives.take(6).forEach { perspective ->
            append("<div class=\"perspective\"><p class=\"holder\">")
            append(perspective.holder.orEmpty().escapeHtml())
            append("</p><p>")
            append(perspective.viewpoint.escapeHtml())
            append("</p></div>")
        }
        analysis.implications?.takeIf { it.isNotBlank() }?.let {
            append("<p>")
            append(it.escapeHtml())
            append("</p>")
        }
    }

    private fun DeepReadOutput.extendedReadingHtml(): String {
        if (statusOf(DeepReadGenerationStage.EXTENDED_READING) == DeepReadSectionStatus.FAILED) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.EXTENDED_READING,
                runningText = "正在整理可点击的来源与延伸阅读",
                pendingText = "最后会把引用和相关阅读写入缓存",
            )
        }
        if (extendedReading.isEmpty()) {
            return sectionStateHtml(
                stage = DeepReadGenerationStage.EXTENDED_READING,
                runningText = "正在整理可点击的来源与延伸阅读",
                pendingText = "最后会把引用和相关阅读写入缓存",
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
    ): String {
        val status = statusOf(stage)
        val label = when (status) {
            DeepReadSectionStatus.RUNNING -> runningText
            DeepReadSectionStatus.FAILED -> "${stage.label}生成失败：${errorOf(stage).orEmpty().ifBlank { "请稍后重试" }}"
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

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.withRuntimeCss(css: String): String {
        val hasCss = css.trim() in this
        val hasImageFallback = "img:not([src])" in this
        if (hasCss && hasImageFallback) return this
        val styleCss = buildString {
            if (!hasCss) appendLine(css)
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
