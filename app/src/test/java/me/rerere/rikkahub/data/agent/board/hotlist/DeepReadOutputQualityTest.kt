package me.rerere.rikkahub.data.agent.board.hotlist

import me.rerere.rikkahub.data.agent.board.hotlist.deepread.CorePoint
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepAnalysis
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadImageAsset
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadOutput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.Perspective
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.ReadingLink
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.TimelineEvent
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.hasEnoughChinese
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.hasReadableArticle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepReadOutputQualityTest {
    @Test
    fun rejectsEmptyModelObjectEvenWhenLinksExist() {
        val output = DeepReadOutput(
            summary = "",
            extendedReading = listOf(ReadingLink("source", "https://example.com", "example.com")),
        )

        assertFalse(output.hasReadableArticle())
    }

    @Test
    fun rejectsThinGeneratedBody() {
        val output = DeepReadOutput(
            summary = "这是一个有明确上下文和可读正文的深度阅读摘要。",
            corePoints = listOf(CorePoint("核心论点", "支撑材料")),
            analysis = DeepAnalysis(),
        )

        assertFalse(output.hasReadableArticle())
    }

    @Test
    fun acceptsArticleWithTimelinePointsAndAnalysis() {
        val output = DeepReadOutput(
            summary = "Gemini 3.5 Flash 是 Google 在 I/O 期间推出的高效率模型，核心看点不只是发布本身，而是它被放进搜索、开发工具和企业平台后，可能改变 Google AI 产品的默认体验。",
            timeline = listOf(
                TimelineEvent("早期背景", "Google 在 Gemini 3 系列后继续把 Flash 定位成高频、低延迟、适合大规模调用的模型层。"),
                TimelineEvent("Google I/O", "Google 在 I/O 上把 Gemini 3.5 Flash 放到 Gemini 应用、AI Mode 和开发工具链中，强化默认模型入口。"),
            ),
            corePoints = listOf(
                CorePoint("Flash 层级开始承担接近旗舰模型的任务", "这意味着 Google 不只是发布一个便宜模型，而是在把高频使用场景从 Pro 层迁移到更快的 Flash 层。"),
                CorePoint("价格和延迟会决定开发者评价", "如果输出成本、首 token 延迟和长上下文表现不能匹配宣传，开发者会把它视为一次性发布噱头而不是长期主力模型。"),
            ),
            analysis = DeepAnalysis(
                coreDispute = "关键分歧在于 Gemini 3.5 Flash 是否真正实现了高性能和高效率兼得，还是只是在 benchmark 上接近旗舰模型。",
                perspectives = listOf(
                    Perspective("Google 强调它适合大规模 agentic 任务和产品默认入口。", "Google"),
                    Perspective("开发者更关心实际延迟、稳定性、API 价格和工具调用可靠性。", "开发者"),
                ),
                implications = "如果实际体验成立，Google 可以把 AI 搜索、移动端助手和开发者工具统一到一个更经济的模型底座上。",
            ),
        )

        assertTrue(output.hasReadableArticle())
    }

    @Test
    fun rejectsLowInformationFallbackText() {
        val output = DeepReadOutput(
            summary = "围绕 Gemini 3.5 Flash，当前深度阅读已先基于热榜和搜索来源整理出基础脉络，当前页面会优先呈现可确认的中文脉络，来源链接统一收在扩展阅读中。",
            timeline = listOf(
                TimelineEvent("当前", "当前可抓取信息仍偏薄，暂时只能确认话题热度和若干来源线索。"),
                TimelineEvent("后续", "后续深读应围绕话题补齐因果链。"),
            ),
            corePoints = listOf(
                CorePoint("可用信息不足以形成稳定脉络", "当前还没有抓到足够的来源正文，因此先保留话题入口。"),
                CorePoint("这个话题已经进入多来源关注区", "系统已捕捉到相关线索，但现有摘要仍偏碎片化。"),
            ),
            analysis = DeepAnalysis(
                coreDispute = "当前可抓取信息仍偏薄，尚不足以支撑完整因果链判断。",
                implications = "来源链接统一收在扩展阅读中。",
            ),
        )

        assertFalse(output.hasReadableArticle())
    }

    @Test
    fun rejectsCurrentlyMissingSourceCoverageAsDeepRead() {
        val output = DeepReadOutput(
            summary = "这是一段看似很长的中文摘要，用来说明某个科技产品发布之后仍然需要更多材料才能形成完整判断，但它没有真正提供足够信息。",
            timeline = listOf(
                TimelineEvent("背景", "目前来源未覆盖早期背景，因此无法还原这件事情的前因。"),
                TimelineEvent("当前", "目前来源未覆盖价格、跑分和发布时间，只能等待更多材料。"),
            ),
            corePoints = listOf(
                CorePoint("目前来源未覆盖核心事实", "这条内容只是说明资料不足，没有提供价格、性能、发布入口或真实评价。"),
                CorePoint("链接见扩展阅读", "这条内容把阅读责任交给链接，没有把来源信息消化成中文判断。"),
            ),
            analysis = DeepAnalysis(
                coreDispute = "目前来源未覆盖各方争议，因此不能判断它和竞品之间的真实差异。",
                implications = "更多材料出现后才可以继续判断，现在无法形成有效结论。",
            ),
        )

        assertFalse(output.hasReadableArticle())
    }

    @Test
    fun detectsMostlyEnglishDeepReadOutput() {
        val output = DeepReadOutput(
            summary = "The model returned a mostly English article that should be repaired before display.",
            corePoints = listOf(CorePoint("A long English point", "More English detail about the source and background.")),
            analysis = DeepAnalysis(implications = "This should be rewritten into Chinese."),
            extendedReading = listOf(ReadingLink("Original English title", "https://example.com", "example")),
        )

        assertFalse(output.hasEnoughChinese())
    }

    @Test
    fun languageCheckIgnoresInjectedReadingTitlesAndCaptions() {
        val output = DeepReadOutput(
            summary = "This model output is still mostly English and should not pass only because source metadata is Chinese.",
            timeline = listOf(
                TimelineEvent("Launch", "Google announced a faster Flash model and the article body remains English."),
                TimelineEvent("Pricing", "The generated body still needs Chinese rewriting before display."),
            ),
            corePoints = listOf(
                CorePoint("English point one", "The generated body is still in English and requires repair."),
                CorePoint("English point two", "The model did not digest the sources into Chinese."),
            ),
            analysis = DeepAnalysis(
                coreDispute = "The body is mostly English even though metadata contains Chinese.",
                implications = "This should trigger Chinese repair instead of passing.",
            ),
            extendedReading = listOf(
                ReadingLink("关于 Gemini 3.5 Flash 的中文来源标题", "https://example.com/1", "示例来源"),
                ReadingLink("更多中文扩展阅读标题用于测试", "https://example.com/2", "示例来源"),
            ),
            imageAssets = listOf(
                DeepReadImageAsset("https://example.com/image.jpg", caption = "中文图片说明不应该让正文语言检查通过"),
            ),
            references = listOf(
                ReadingLink("中文参考来源标题也不应该计入正文", "https://example.com/3", "示例来源"),
            ),
        )

        assertFalse(output.hasEnoughChinese())
    }

    @Test
    fun acceptsChineseDeepReadOutput() {
        val output = DeepReadOutput(
            summary = "这是一段中文深度阅读摘要，包含足够多的中文内容用于展示。",
            corePoints = listOf(CorePoint("核心判断", "这里是中文支撑材料。")),
            analysis = DeepAnalysis(implications = "这会影响后续产品和产业判断。"),
        )

        assertTrue(output.hasEnoughChinese())
    }
}
