package me.rerere.rikkahub.data.agent.board.hotlist.deepread

object DeepReadPrompt {
    fun build(topicTitle: String, sources: List<DeepReadSource>): String = buildString {
        appendLine("你是 AmberAgent 的深度阅读编辑，目标是生成高端 News 杂志 App 风格的结构化深读稿。")
        appendLine("话题：$topicTitle")
        appendLine()
        appendLine("## 任务")
        appendLine("- 判断 topic_type：event / opinion / product / person。")
        appendLine("- 基于给定来源写简体中文深读，不要编造来源之外的事实。")
        appendLine("- 你的首要目标是补齐读者不知道的来龙去脉：起因、背景、关键转折、核心矛盾、当前进展、后续影响。")
        appendLine("- 对事件/诉讼/争议/人物关系类话题，timeline 必须覆盖「早期背景 → 直接导火索 → 当前事件 → 后续影响」，不要只复述当天新闻。")
        appendLine("- 如果来源不足以还原某段历史，明确写成「目前来源未覆盖」或在分析中保守处理，不要脑补。")
        appendLine("- analysis.core_dispute 要回答「各方到底在争什么」；perspectives 要区分不同当事方/利益方。")
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
        appendLine("## 来源")
        sources.forEachIndexed { index, source ->
            appendLine("### [${index + 1}] ${source.title}")
            appendLine("- url: ${source.url}")
            appendLine("- source: ${source.source ?: "-"}")
            if (source.publishedAt != null) appendLine("- published_at: ${source.publishedAt}")
            if (source.images.isNotEmpty()) appendLine("- images: ${source.images.joinToString(", ")}")
            appendLine("- excerpt: ${source.content.take(2_000).replace("\n", " ")}")
            appendLine()
        }
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
}

data class DeepReadSource(
    val title: String,
    val url: String,
    val source: String?,
    val content: String,
    val publishedAt: String?,
    val images: List<String>,
)
