package app.amber.feature.board.hotlist.deepread.template

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import kotlin.uuid.Uuid

class DeepReadTemplateAgent(
    private val settingsStore: SettingsAggregator,
    private val providerManager: ProviderManager,
    private val repository: DeepReadTemplateRepository,
    private val json: Json,
) {
    suspend fun generate(name: String, brief: String): Result<DeepReadTemplatePackage> {
        val draft = generateDraft(name, brief)
        return draft.fold(
            onSuccess = { runCatching { saveGeneratedTemplate(it, name) } },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun generateDraft(name: String, brief: String): Result<DeepReadTemplatePackage> =
        generateTemplateDraft(
            name = name,
            prompt = buildPrompt(name, brief),
            repairPrompt = { raw, error -> buildRepairPrompt(name, brief, raw, error) },
        )

    suspend fun reviseDraft(
        currentTemplate: DeepReadTemplatePackage,
        instruction: String,
    ): Result<DeepReadTemplatePackage> =
        generateTemplateDraft(
            name = currentTemplate.name,
            prompt = buildRevisionPrompt(currentTemplate, instruction),
            repairPrompt = { raw, error ->
                buildRevisionRepairPrompt(currentTemplate, instruction, raw, error)
            },
        )

    private suspend fun generateTemplateDraft(
        name: String,
        prompt: String,
        repairPrompt: (previousOutput: String, validationError: String) -> String,
    ): Result<DeepReadTemplatePackage> {
        val settings = settingsStore.settingsFlow.value
        val model = settings.agentRuntime.todayBoard.boardModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
            ?: settings.resolveTaskChatModel(settings.chatModelId)
            ?: return Result.failure(IllegalStateException("请先配置聊天模型"))
        val provider = model.findProvider(settings.providers)
            ?: return Result.failure(IllegalStateException("模型 ${model.displayName} 的提供商不可用"))

        return try {
            val params = TextGenerationParams(
                model = model,
                maxTokens = 3600,
                customHeaders = model.boardRequestHeaders(settings.providers),
                customBody = model.boardRequestBodies(settings.providers),
            )
            val providerInstance = providerManager.getProviderByType(provider)
            val systemMessage = UIMessage.system(
                "你是移动端 Editorial UI 设计总监兼前端工程师，专门为新闻深度阅读生成静态 HTML/CSS 模板。只输出合法 JSON，不要解释。"
            )
            val raw = withTimeout(MODEL_TIMEOUT_MS) {
                providerInstance.generateText(
                    providerSetting = provider,
                    messages = listOf(systemMessage, UIMessage.user(prompt)),
                    params = params,
                )
            }.choices.firstOrNull()?.message?.toText().orEmpty()
            val draft = runCatching { parseAndNormalizeDraft(raw, name) }
                .recoverCatching { firstError ->
                    val repairedRaw = withTimeout(REPAIR_TIMEOUT_MS) {
                        providerInstance.generateText(
                            providerSetting = provider,
                            messages = listOf(
                                systemMessage,
                                UIMessage.user(repairPrompt(raw, firstError.message.orEmpty())),
                            ),
                            params = params,
                        )
                    }.choices.firstOrNull()?.message?.toText().orEmpty()
                    parseAndNormalizeDraft(repairedRaw, name)
                }.getOrThrow()
            Result.success(draft)
        } catch (error: DeepReadTemplateValidationException) {
            Result.failure(IllegalStateException(error.userMessage()))
        } catch (error: IllegalArgumentException) {
            Result.failure(IllegalStateException(error.userMessage()))
        } catch (error: IllegalStateException) {
            Result.failure(IllegalStateException(error.userMessage()))
        } catch (error: TimeoutCancellationException) {
            Result.failure(IllegalStateException("模板生成超时，请稍后重试"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun normalizeDraftTemplate(
        template: DeepReadTemplatePackage,
        requestedName: String,
    ): DeepReadTemplatePackage {
        val normalized = template.copy(
            id = "draft",
            name = requestedName.ifBlank { template.name }.trim().take(48).ifBlank { "自定义模板" },
            description = template.description.trim().take(160),
            html = template.html.trim(),
            createdByAi = true,
            schemaVersion = 1,
        )
        DeepReadTemplateRepository.validateCustomTemplate(normalized.html)
        return normalized
    }

    private fun parseAndNormalizeDraft(
        raw: String,
        requestedName: String,
    ): DeepReadTemplatePackage {
        val decoded = parsePackage(raw)
            ?: throw IllegalStateException("模型没有输出可用模板 JSON")
        return normalizeDraftTemplate(decoded, requestedName)
    }

    private suspend fun saveGeneratedTemplate(
        template: DeepReadTemplatePackage,
        requestedName: String,
    ): DeepReadTemplatePackage =
        repository.saveTemplate(
            template.copy(
                id = DeepReadTemplateIds.custom(Uuid.random().toString()),
                name = requestedName.ifBlank { template.name },
                createdByAi = true,
            )
        )

    private fun buildRepairPrompt(
        name: String,
        brief: String,
        previousOutput: String,
        validationError: String,
    ): String =
        """
        你刚才输出的深度阅读模板没有通过 App 校验。

        校验错误：
        ${validationError.ifBlank { "未知校验错误" }}

        请基于原始需求重新输出一份完整、合法、可保存的 JSON。只输出 JSON，不要解释。

        原始名称：${name.ifBlank { "自定义模板" }}
        原始设计方向：${brief.ifBlank { "高端 News 杂志 App，强排版、全幅图片、留白、时间轴、引用块、扩展阅读。" }}

        必须遵守：
        - html 必须是完整 HTML 文档，内联 CSS。
        - 禁止 JavaScript、iframe、form、button、SVG、canvas、math、audio/video、外链 CSS、@import、CSS url()、真实 href/src 外链。
        - 禁止 srcset、poster、事件处理器、交互控件和任何脚本动画。
        - 必须包含 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}。
        - 必须包含 {{narrative_html}}；若要兼容旧式分栏，也可以额外使用 {{timeline_html}} 或 {{core_points_html}}。
        - 块级占位符只能放在标签内容里，不能放进属性或 style。
        - 图片只能使用 <img src="{{hero_image_url}}" ...>。
        - 不要写真实新闻正文，只写模板结构和 CSS。

        上一次输出如下，仅供你修正，不要照抄错误：
        ${previousOutput.take(9000)}
        """.trimIndent()

    private fun buildRevisionPrompt(
        currentTemplate: DeepReadTemplatePackage,
        instruction: String,
    ): String =
        """
        请基于当前 AmberAgent 深度阅读模板，按用户的新要求修改版式。只输出完整 JSON，不要解释。

        当前模板名：${currentTemplate.name.ifBlank { "自定义模板" }}
        用户修改要求：${instruction.ifBlank { "在保持可读性和信息密度的前提下优化版式。" }}

        当前 HTML：
        ${currentTemplate.html.take(36000)}

        输出 JSON：
        {
          "id": "draft",
          "name": "模板名",
          "description": "一句话说明",
          "html": "<!doctype html>...",
          "createdByAi": true,
          "schemaVersion": 1
        }

        必须遵守：
        - 输出完整 HTML 文档和内联 CSS，不要输出片段或 diff。
        - 模板只负责版式，不写真实新闻正文、不写示例新闻事实。
        - 禁止 JavaScript、事件处理器、iframe、form、button、SVG、canvas、math、audio/video/source/picture、srcset、poster、@import、CSS url()、真实 href/src 外链。
        - 必须保留 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}。
        - 必须保留 {{narrative_html}}；兼容旧模板时可额外保留 {{timeline_html}} 或 {{core_points_html}}。
        - 推荐保留 {{diagram_html}} 和 {{font_css}}；App 会在 WebView 层统一处理字体和字号，不要写 JS 调字号。
        - 图片只能使用 <img src="{{hero_image_url}}" ...>。
        - 块级占位符不能放进标签属性或 style。
        """.trimIndent()

    private fun buildRevisionRepairPrompt(
        currentTemplate: DeepReadTemplatePackage,
        instruction: String,
        previousOutput: String,
        validationError: String,
    ): String =
        """
        你刚才修订的深度阅读模板没有通过 App 校验。

        校验错误：
        ${validationError.ifBlank { "未知校验错误" }}

        请基于当前模板和用户修改要求，重新输出一份完整、合法、可预览的 JSON。只输出 JSON，不要解释。

        当前模板名：${currentTemplate.name.ifBlank { "自定义模板" }}
        用户修改要求：${instruction.ifBlank { "优化模板版式" }}

        必须遵守：
        - html 必须是完整 HTML 文档，内联 CSS。
        - 禁止 JavaScript、事件处理器、iframe、form、button、SVG、canvas、math、audio/video/source/picture、srcset、poster、@import、CSS url()、真实 href/src 外链。
        - 必须包含 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}。
        - 必须包含 {{narrative_html}}；可以额外使用 {{timeline_html}} 或 {{core_points_html}}。
        - 推荐包含 {{diagram_html}} 和 {{font_css}}。
        - 图片只能使用 <img src="{{hero_image_url}}" ...>。
        - 块级占位符只能放在标签内容里，不能放进属性或 style。
        - 模板内容只负责版式，不生成新闻事实。

        当前模板 HTML：
        ${currentTemplate.html.take(7000)}

        上一次无效输出：
        ${previousOutput.take(9000)}
        """.trimIndent()

    private fun Throwable.userMessage(): String {
        val text = message.orEmpty()
        return when {
            "Syntax error in regexp" in text -> "模板校验器异常，已修复后请重新生成"
            "missing placeholders" in text -> "模板缺少必要占位符，请重新生成"
            "Block placeholder" in text -> "模板把内容占位符放错了位置，请重新生成"
            "Hard-coded external" in text -> "模板包含外链资源，请重新生成"
            "External" in text || "not allowed" in text || "CSS URLs" in text -> "模板包含不允许的 HTML/CSS 能力，请重新生成"
            text.isBlank() -> "模板生成失败，请重试"
            else -> text
        }
    }

    private fun parsePackage(raw: String): DeepReadTemplatePackage? {
        val cleaned = raw
            .trim()
            .removePrefix("```json")
            .removePrefix("```html")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        return runCatching { json.decodeFromString<DeepReadTemplatePackage>(cleaned) }.getOrNull()
    }

    private fun buildPrompt(name: String, brief: String): String =
        """
        请生成一个 AmberAgent 深度阅读静态 HTML 模板，名称：${name.ifBlank { "自定义模板" }}。
        设计方向：${brief.ifBlank { "高端 News 杂志 App，强排版、全幅图片、留白、时间轴、引用块、扩展阅读。" }}

        ## 设计能力注入
        你不是普通网页生成器，而是移动端杂志阅读模板设计师。请先在心里完成设计系统，再输出模板：
        - 明确一个强概念方向，例如：经典报刊、斜切新闻、科技长文、学术期刊、冷静高端、暗色实验室。不要同时混用多个风格。
        - 版式必须像“画布切割”，不是卡片堆叠：允许全幅、斜切、非对称网格、强留白、细线、编号、引言、时间轴。
        - Typography 是主角：标题用衬线 display，正文用高可读衬线，元信息用小号无衬线；禁止所有字号都很大。
        - 移动端信息密度要合理：正文 14-15px，扩展阅读 12-13px，metadata 9-10px，标题可大但不能挤压内容。
        - 使用 8pt spacing rhythm，section 间距有节奏；不要用大圆角卡片、紫蓝渐变、玻璃拟态、emoji、图标堆砌、假数据装饰。
        - 必须考虑 loading/partial 内容：占位符区域即使暂时为空，也要保持版式优雅，不要大面积空白或塌陷。
        - 视觉语言应接近高端中文新闻杂志 App：克制、留白、纸媒感、可读，不要像营销落地页。

        ## 可用固定样稿预览机制
        App 会用固定中文样稿预览模板：标题、摘要、时间轴、关键脉络、分析、扩展阅读都会被注入占位符。
        所以 html 里不要写任何真实新闻正文或样例内容，只负责结构、CSS 和占位符位置。
        用户选择模板时只看样稿版式；真实深度阅读内容由另一个 Agent 生成。

        输出 JSON：
        {
          "id": "draft",
          "name": "模板名",
          "description": "一句话说明",
          "html": "<!doctype html>...",
          "createdByAi": true,
          "schemaVersion": 1
        }

        硬性要求：
        - html 必须是完整 HTML 文档，内联 CSS，禁止 JavaScript、事件处理器、iframe、form、button、SVG、canvas、math、audio/video/source/picture、srcset、poster、外链 CSS、@import、CSS url()。
        - 必须包含占位符 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}，且 {{narrative_html}} / {{timeline_html}} / {{core_points_html}} 至少包含一个。
        - 推荐支持这些占位符：{{topic_type}}、{{source_label}}、{{hero_image_url}}、{{hero_caption}}、{{narrative_html}}、{{timeline_html}}、{{core_points_html}}、{{diagram_html}}、{{analysis_html}}、{{extended_reading_html}}、{{font_css}}。
        - 如果使用 hero 图片，只能写 <img src="{{hero_image_url}}" ...>；不要写真实图片 URL。
        - 块级占位符 {{narrative_html}}、{{timeline_html}}、{{core_points_html}}、{{diagram_html}}、{{analysis_html}}、{{extended_reading_html}} 必须放在正文节点中，不能放进标签属性或 style。
        - 不要写任何固定 href/src 外链；扩展阅读链接只能来自 {{extended_reading_html}}。
        - 模板内容只负责版式，不生成新闻事实，不写示例新闻正文。
        - 移动端优先，正文 14-15px、line-height 1.68 左右；可用斜切 Hero、红色时间轴、灰色 metadata。
        - 不要把 {{extended_reading_html}} 包在会裁切文字的固定高度容器里；扩展阅读必须可点击、可完整显示两行标题。
        - 如果有 {{hero_image_url}}，必须给无图状态留出纯排版 fallback：例如用 CSS 让空 img 不占巨大空间，或把 hero 区和 headline 分开。
        - 输出的 CSS 必须包含清晰的移动端宽度约束、字号层级和 section rhythm；不要让 WebView 默认字号接管。
        """.trimIndent()

    companion object {
        private const val MODEL_TIMEOUT_MS = 120_000L
        private const val REPAIR_TIMEOUT_MS = 90_000L
    }
}
