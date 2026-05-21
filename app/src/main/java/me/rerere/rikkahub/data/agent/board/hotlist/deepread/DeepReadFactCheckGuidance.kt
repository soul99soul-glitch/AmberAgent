package me.rerere.rikkahub.data.agent.board.hotlist.deepread

internal object DeepReadFactCheckGuidance {
    val prompt: String = """
        ## 内置验真 Skill：deep-read-fact-check
        在调用任何 deep_read_write_* 写入工具前，你必须执行这套验真流程。AmberAgent 深度阅读隐藏链路通常只暴露 search_web / scrape_web；不要声称使用了未暴露的 browser、terminal、xurl 或 GitHub 专用工具。

        语言规则：
        - 用户话题来自中文看板，用户可见内容和验真判断使用中文。
        - 来源 URL 和引用保持原文，不翻译 URL。
        - 不确定就写「不确定」并解释证据缺口，禁止用「可能」「应该」暗示确定。

        长文章/报道验证：
        1. 先用 search_web 做多角度检索；snippet 不够时，对关键 URL 调用 scrape_web 提取正文。
        2. 把待写段落拆成核心子声明，优先验证影响最大的 3-5 个。
        3. 如果关键子声明被证伪，必须修正或不写该声明；不得把错误声明包装成观点。
        4. 不把 AI 摘要当来源；不把多个引用同一来源的报道算作多个独立来源。
        5. 必须检查时间线：旧来源不能验证新的事实。

        动态/反爬页面：
        - scrape_web 为空时，改搜同主题的其他来源；只有当前工具列表暴露 browser/webview 时才能使用渲染页面方案。
        - 如果只能基于二手来源，正文或 references 中要体现「未能访问原始内容，基于二手来源」。

        技术/产品声明：
        - 产品发布时间、型号状态、价格、版本、GitHub/package 时间线等时效事实，必须用当前搜索或可提取来源确认。
        - 不允许凭训练记忆写「尚未发布」「已经发布」「官方确认」这类结论。

        写入门槛：
        - 每个段落写入前至少有 2 个可解释来源；重大国际/政策/产品发布话题至少包含中文 query 和英文 query 的交叉核查。
        - 反面证据搜索是必须步骤；如果发现反证，优先修正稿件，不要继续沿用原假设。
    """.trimIndent()
}
