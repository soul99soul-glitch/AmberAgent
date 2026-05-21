package me.rerere.rikkahub.data.agent.board.hotlist.deepread

object DeepReadPrompt {
    fun buildStage(
        topicTitle: String,
        sources: List<DeepReadSource>,
        stage: DeepReadGenerationStage,
        previousJson: String? = null,
        compact: Boolean = false,
    ): String = buildString {
        appendLine("你是 AmberAgent 的深度阅读编辑。请分阶段生成高端 News 杂志 App 的结构化深读稿。")
        appendLine("话题：$topicTitle")
        appendLine("当前阶段：${stage.label}")
        appendLine()
        appendLine("## 阶段要求")
        when (stage) {
            DeepReadGenerationStage.OVERVIEW -> {
                appendLine("- 只完成 topic_type、summary、key_entities。")
                appendLine("- summary 要像杂志导语，120-200 字，说明为什么值得读。")
                appendLine("- 本阶段不要输出图片、引用、扩展阅读、时间轴或分析字段；这些由后续阶段补齐。")
                appendLine("- 不要编造来源之外的事实。")
            }
            DeepReadGenerationStage.NARRATIVE -> {
                appendLine("- 在已有概览基础上补齐 timeline 和 core_points。")
                appendLine("- timeline 要讲清「早期背景 → 直接导火索 → 当前事件 → 后续影响」。")
                appendLine("- core_points 写消化后的关键脉络，不是来源列表。")
            }
            DeepReadGenerationStage.ANALYSIS -> {
                appendLine("- 在已有概览和叙事基础上补齐 analysis。")
                appendLine("- core_dispute 回答各方到底在争什么；perspectives 区分不同当事方或利益方；implications 写影响。")
            }
            DeepReadGenerationStage.EXTENDED_READING -> {
                appendLine("- 做最后整理：保留已有 summary/timeline/core_points/analysis，补齐 extended_reading 与 references。")
                appendLine("- 输出完整 DeepRead JSON。不要删掉上一阶段已完成内容。")
            }
        }
        appendLine("- 输出合法 JSON 对象，不要代码围栏、不要解释。")
        appendLine("- 用户可见文本必须是简体中文；url 原样保留。")
        appendLine("- hero_image_url、image_assets、timeline.image_url、core_points.image_url 只能使用来源 images 中出现过的 URL。")
        appendLine("- 正文不能写「来源不足」「链接见扩展阅读」来冒充分析；如果某事实来源未覆盖，就保守跳过。")
        appendLine()
        previousJson?.takeIf { it.isNotBlank() }?.let {
            appendLine("## 上一阶段 JSON")
            appendLine(it.take(stage.previousJsonLimit()))
            appendLine()
        }
        appendStageSchema(stage)
        appendSources(
            sources = sources,
            sourceLimit = if (compact) stage.compactSourceLimit() else stage.sourceLimit(),
            excerptLimit = if (compact) stage.compactExcerptLimit() else stage.excerptLimit(),
        )
    }

    fun build(topicTitle: String, sources: List<DeepReadSource>): String = buildString {
        appendLine("你是 AmberAgent 的深度阅读编辑，目标是生成高端 News 杂志 App 风格的结构化深读稿。")
        appendLine("话题：$topicTitle")
        appendLine()
        appendLine("## 任务")
        appendLine("- 判断 topic_type：event / opinion / product / person。")
        appendLine("- 基于给定来源写简体中文深读，不要编造来源之外的事实。")
        appendLine("- 你的首要目标是补齐读者不知道的来龙去脉：起因、背景、关键转折、核心矛盾、当前进展、后续影响。")
        appendLine("- 信息量必须达到深度阅读标准：不能只说「多个来源关注」「仍需更多材料」「链接见扩展阅读」；如果只能写这些，说明生成不合格。")
        appendLine("- 对产品/模型发布类话题，必须尽量覆盖：发布场景、官方定位、可用入口、价格/成本、性能或跑分、与前代/竞品差异、外界评价和争议。")
        appendLine("- 对事件/诉讼/争议/人物关系类话题，timeline 必须覆盖「早期背景 → 直接导火索 → 当前事件 → 后续影响」，不要只复述当天新闻。")
        appendLine("- 如果来源不足以还原某段历史，明确写成「目前来源未覆盖」或在分析中保守处理，不要脑补。")
        appendLine("- analysis.core_dispute 要回答「各方到底在争什么」；perspectives 要区分不同当事方/利益方。")
        appendLine("- core_points 是你消化来源后的中文关键脉络，不是来源清单。每条必须是一个被综合后的判断、转折或背景信息，并用中文解释为什么重要。")
        appendLine("- 绝对禁止在 summary、timeline、core_points、analysis 中写「原文来源 1/2/3」「这条来源主要是英文内容」「已保留原文链接」「重新生成时会继续尝试」这类占位话术。")
        appendLine("- 绝对禁止把来源域名列表、热榜排名、搜索命中本身当成关键脉络；这些只能出现在 extended_reading/references。")
        appendLine("- 不要按来源逐条复述；先读懂多个来源，再合并同类信息，输出读者真正需要的中文解释。")
        appendLine("- references 和 extended_reading 才能承载来源列表；正文区域只承载消化后的内容。")
        appendLine("- summary、timeline、core_points、analysis、extended_reading.title、hero_caption、references.title 全部必须是中文；原始英文页面只保留在 url。")
        appendLine("- 如果来源是英文，请先理解后转写成中文，不要直接裸露英文段落。")
        appendLine("- 输出合法 JSON 对象，不要代码围栏、不要前后解释。")
        appendLine("- 不要输出 null；没有内容时用空字符串或空数组。")
        appendLine("- 如果不是事件型但存在明显演化过程，也可以给 timeline；如果没有可靠引用，quotes 用空数组。")
        appendLine("- extended_reading 使用来源里的 title/url/source。")
        appendLine("- hero_image_url 只能从来源 images 列表中选择；没有可靠图片时留空字符串。")
        appendLine("- image_assets 只能从来源 images 列表中选择，最多 6 张；图片说明必须中文。")
        appendLine("- timeline[].image_url 和 core_points[].image_url 只能引用 image_assets 中的 url；不确定关联时留空。")
        appendLine()
        appendSchema()
        appendSources(sources)
    }

    fun repairChinese(topicTitle: String, outputJson: String): String = buildString {
        appendLine("你是 AmberAgent 的中文深度阅读修稿编辑。")
        appendLine("话题：$topicTitle")
        appendLine()
        appendLine("请把下面 JSON 中所有用户可见文本改写为自然、简洁的简体中文。")
        appendLine("- 保持 JSON schema、数组结构、url、hero_image_url、image_url 不变。")
        appendLine("- extended_reading.title 和 references.title 也要中文化；原英文标题不要直接输出。")
        appendLine("- image_assets.caption、timeline.image_caption、core_points.image_caption 也要中文化。")
        appendLine("- 不要加入来源外的新事实。")
        appendLine("- 仅输出合法 JSON，不要解释、不要代码围栏。")
        appendLine()
        appendLine(outputJson)
    }

    fun repairJson(topicTitle: String, rawOutput: String): String = buildString {
        appendLine("你是 AmberAgent 的深度阅读 JSON 修复器。")
        appendLine("话题：$topicTitle")
        appendLine()
        appendLine("下面是一次深度阅读生成的原始输出，可能被截断、包含代码围栏、尾逗号或前后解释。")
        appendLine("请尽最大可能修复为一个完整、合法、可解析的 JSON 对象，并保持原有事实与中文正文。")
        appendLine("- 只输出 JSON，不要代码围栏、不要解释。")
        appendLine("- 不要新增来源外事实。")
        appendLine("- 如果某个数组最后一项被截断，删除该残缺项，而不是编造补齐。")
        appendLine("- 不要输出低信息量兜底文案，不要写「当前信息不足」「来源见扩展阅读」来冒充正文。")
        appendLine("- 保持字段名与 DeepRead schema 一致。")
        appendLine()
        appendLine(rawOutput)
    }

    private fun StringBuilder.appendSchema() {
        appendLine("## JSON Schema")
        appendLine(
            """
            {
              "topic_type": "event|opinion|product|person",
              "summary": "200字以内摘要",
              "key_entities": ["实体"],
              "timeline": [{"date":"日期或时间","event":"事件","is_highlight":true,"image_url":"可为空","image_caption":"可为空"}],
              "core_points": [{"point":"核心论点/亮点","supporting":"支撑材料","image_url":"可为空","image_caption":"可为空"}],
              "analysis": {
                "core_dispute": "核心分歧，可为空",
                "perspectives": [{"viewpoint":"观点","holder":"持有方"}],
                "implications": "影响分析，可为空",
                "quotes": [{"text":"短引用，不超过40字","attribution":"来源或人物"}]
              },
              "extended_reading": [{"title":"标题","url":"URL","source":"来源"}],
              "hero_image_query": "适合查找真实新闻配图的搜索词",
              "hero_image_url": "只能使用来源 images 中的 URL，可为空",
              "hero_caption": "图片说明，可为空",
              "image_assets": [{"url":"来源图片URL","caption":"中文图注","source":"来源","related_entities":["实体"],"related_timeline_index":0,"quality_hint":"hero|timeline|context"}],
              "references": [{"title":"标题","url":"URL","source":"来源"}]
            }
            """.trimIndent()
        )
        appendLine()
    }

    private fun StringBuilder.appendStageSchema(stage: DeepReadGenerationStage) {
        appendLine("## 本阶段 JSON 字段")
        appendLine(
            when (stage) {
                DeepReadGenerationStage.OVERVIEW -> """
                    {
                      "topic_type": "event|opinion|product|person",
                      "summary": "120-200字中文杂志导语",
                      "key_entities": ["关键实体"]
                    }
                """.trimIndent()
                DeepReadGenerationStage.NARRATIVE -> """
                    {
                      "timeline": [{"date":"日期或时间","event":"连贯叙事事件","is_highlight":true,"image_url":"可为空","image_caption":"可为空"}],
                      "core_points": [{"point":"关键脉络","supporting":"为什么重要","image_url":"可为空","image_caption":"可为空"}],
                      "references": [{"title":"中文标题","url":"URL","source":"来源"}]
                    }
                """.trimIndent()
                DeepReadGenerationStage.ANALYSIS -> """
                    {
                      "analysis": {
                        "core_dispute": "核心分歧，可为空",
                        "perspectives": [{"viewpoint":"观点","holder":"持有方"}],
                        "implications": "影响分析，可为空",
                        "quotes": [{"text":"短引用，不超过40字","attribution":"来源或人物"}]
                      },
                      "references": [{"title":"中文标题","url":"URL","source":"来源"}]
                    }
                """.trimIndent()
                DeepReadGenerationStage.EXTENDED_READING -> """
                    {
                      "extended_reading": [{"title":"中文标题","url":"URL","source":"来源"}],
                      "hero_image_query": "适合查找真实新闻配图的搜索词",
                      "hero_image_url": "只能使用来源 images 中的 URL，可为空",
                      "hero_caption": "图片说明，可为空",
                      "image_assets": [],
                      "references": [{"title":"中文标题","url":"URL","source":"来源"}]
                    }
                """.trimIndent()
            }
        )
        appendLine()
        appendLine()
    }

    private fun StringBuilder.appendSources(
        sources: List<DeepReadSource>,
        sourceLimit: Int = sources.size,
        excerptLimit: Int = 2_000,
    ) {
        appendLine("## 来源")
        sources.take(sourceLimit).forEachIndexed { index, source ->
            appendLine("### [${index + 1}] ${source.title}")
            appendLine("- url: ${source.url}")
            appendLine("- source: ${source.source ?: "-"}")
            if (source.publishedAt != null) appendLine("- published_at: ${source.publishedAt}")
            if (source.images.isNotEmpty()) appendLine("- images: ${source.images.joinToString(", ")}")
            appendLine("- excerpt: ${source.content.take(excerptLimit).replace("\n", " ")}")
            appendLine()
        }
    }
}

enum class DeepReadGenerationStage(val label: String) {
    OVERVIEW("概览"),
    NARRATIVE("时间轴叙事"),
    ANALYSIS("深度分析"),
    EXTENDED_READING("扩展阅读"),
}

private fun DeepReadGenerationStage.sourceLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 5
    DeepReadGenerationStage.NARRATIVE -> 10
    DeepReadGenerationStage.ANALYSIS -> 10
    DeepReadGenerationStage.EXTENDED_READING -> 12
}

private fun DeepReadGenerationStage.excerptLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 800
    DeepReadGenerationStage.NARRATIVE -> 1_400
    DeepReadGenerationStage.ANALYSIS -> 1_600
    DeepReadGenerationStage.EXTENDED_READING -> 520
}

private fun DeepReadGenerationStage.compactSourceLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 3
    DeepReadGenerationStage.NARRATIVE -> 6
    DeepReadGenerationStage.ANALYSIS -> 6
    DeepReadGenerationStage.EXTENDED_READING -> 6
}

private fun DeepReadGenerationStage.compactExcerptLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 360
    DeepReadGenerationStage.NARRATIVE -> 760
    DeepReadGenerationStage.ANALYSIS -> 760
    DeepReadGenerationStage.EXTENDED_READING -> 300
}

private fun DeepReadGenerationStage.previousJsonLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 0
    DeepReadGenerationStage.NARRATIVE -> 5_000
    DeepReadGenerationStage.ANALYSIS -> 8_000
    DeepReadGenerationStage.EXTENDED_READING -> 12_000
}

data class DeepReadSource(
    val title: String,
    val url: String,
    val source: String?,
    val content: String,
    val publishedAt: String?,
    val images: List<String>,
)
