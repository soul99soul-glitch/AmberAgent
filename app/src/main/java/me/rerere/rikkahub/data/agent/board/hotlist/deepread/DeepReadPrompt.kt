package me.rerere.rikkahub.data.agent.board.hotlist.deepread

object DeepReadPrompt {
    fun build(topicTitle: String, sources: List<DeepReadSource>): String = buildString {
        appendLine("你是 AmberAgent 的深度阅读编辑，目标是生成高端 News 杂志 App 风格的结构化深读稿。")
        appendLine("话题：$topicTitle")
        appendLine()
        appendLine("## 任务")
        appendLine("- 判断 topic_type：event / opinion / product / person。")
        appendLine("- 基于给定来源写中文深读，不要编造来源之外的事实。")
        appendLine("- 输出合法 JSON 对象，不要代码围栏、不要前后解释。")
        appendLine("- 如果不是事件型，timeline 可以为 null；如果没有可靠引用，quotes 为空数组。")
        appendLine("- extended_reading 使用来源里的 title/url/source。")
        appendLine()
        appendLine("## JSON Schema")
        appendLine(
            """
            {
              "topic_type": "event|opinion|product|person",
              "summary": "200字以内摘要",
              "key_entities": ["实体"],
              "timeline": [{"date":"日期或时间","event":"事件","is_highlight":true}],
              "core_points": [{"point":"核心论点/亮点","supporting":"支撑材料"}],
              "analysis": {
                "core_dispute": "核心分歧，可为空",
                "perspectives": [{"viewpoint":"观点","holder":"持有方"}],
                "implications": "影响分析，可为空",
                "quotes": [{"text":"短引用，不超过40字","attribution":"来源或人物"}]
              },
              "extended_reading": [{"title":"标题","url":"URL","source":"来源"}],
              "hero_image_query": "适合查找真实新闻配图的搜索词",
              "hero_caption": "图片说明，可为空",
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
            appendLine("- excerpt: ${source.content.take(2_000).replace("\n", " ")}")
            appendLine()
        }
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
