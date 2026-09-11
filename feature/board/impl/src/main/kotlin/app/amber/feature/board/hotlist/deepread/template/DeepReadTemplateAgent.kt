package app.amber.feature.board.hotlist.deepread.template

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.UIMessage
import app.amber.feature.board.DeepReadTemplateIds
import app.amber.feature.board.boardRequestBodies
import app.amber.feature.board.boardRequestHeaders
import app.amber.core.settings.findProvider
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.resolveTaskChatModel
import java.util.Locale
import kotlin.uuid.Uuid

class DeepReadTemplateAgent(
    private val settingsStore: SettingsAggregator,
    private val providerCatalog: ProviderCatalog,
    private val repository: DeepReadTemplateRepository,
    private val json: Json,
) {
    suspend fun generate(
        name: String,
        brief: String,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadTemplatePackage> {
        val draft = generateDraft(name, brief, locale)
        return draft.fold(
            onSuccess = { runCatching { saveGeneratedTemplate(it, name) } },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun generateDraft(
        name: String,
        brief: String,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadTemplatePackage> =
        generateTemplateDraft(
            name = name,
            locale = locale,
            prompt = buildPrompt(name, brief, locale),
            repairPrompt = { raw, error -> buildRepairPrompt(name, brief, raw, error, locale) },
        )

    suspend fun reviseDraft(
        currentTemplate: DeepReadTemplatePackage,
        instruction: String,
        locale: Locale = Locale.getDefault(),
    ): Result<DeepReadTemplatePackage> =
        generateTemplateDraft(
            name = currentTemplate.name,
            locale = locale,
            prompt = buildRevisionPrompt(currentTemplate, instruction, locale),
            repairPrompt = { raw, error ->
                buildRevisionRepairPrompt(currentTemplate, instruction, raw, error, locale)
            },
        )

    private suspend fun generateTemplateDraft(
        name: String,
        locale: Locale,
        prompt: String,
        repairPrompt: (previousOutput: String, validationError: String) -> String,
    ): Result<DeepReadTemplatePackage> {
        val settings = settingsStore.settingsFlow.value
        val model = settings.agentRuntime.todayBoard.boardModelId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?.let { settings.resolveTaskChatModel(it) }
            ?: settings.resolveTaskChatModel(settings.chatModelId)
            ?: return Result.failure(
                IllegalStateException(
                    if (locale.isChineseLocale()) "请先配置聊天模型" else "Configure a chat model in Settings > Models."
                )
            )
        val provider = model.findProvider(settings.providers)
            ?: return Result.failure(
                IllegalStateException(
                    if (locale.isChineseLocale()) {
                        "模型 ${model.displayName} 的提供商不可用"
                    } else {
                        "The provider for model ${model.displayName} is unavailable"
                    }
                )
            )

        return try {
            val params = TextGenerationParams(
                model = model,
                maxTokens = 3600,
                customHeaders = model.boardRequestHeaders(settings.providers),
                customBody = model.boardRequestBodies(settings.providers),
            )
            val providerInstance = providerCatalog.text(provider)
            val systemMessage = UIMessage.system(
                if (locale.isChineseLocale()) {
                    "你是移动端 Editorial UI 设计总监兼前端工程师，专门为新闻深度阅读生成静态 HTML/CSS 模板。只输出合法 JSON，不要解释。"
                } else {
                    "You are a mobile editorial UI director and frontend engineer creating static HTML/CSS templates for deep news reading. Output valid JSON only; do not explain."
                }
            )
            val raw = withTimeout(MODEL_TIMEOUT_MS) {
                providerInstance.complete(
                    providerSetting = provider,
                    messages = listOf(systemMessage, UIMessage.user(prompt)),
                    params = params,
                )
            }.choices.firstOrNull()?.message?.toText().orEmpty()
            val draft = runCatching { parseAndNormalizeDraft(raw, name, locale) }
                .recoverCatching { firstError ->
                    val repairedRaw = withTimeout(REPAIR_TIMEOUT_MS) {
                        providerInstance.complete(
                            providerSetting = provider,
                            messages = listOf(
                                systemMessage,
                                UIMessage.user(repairPrompt(raw, firstError.message.orEmpty())),
                            ),
                            params = params,
                        )
                    }.choices.firstOrNull()?.message?.toText().orEmpty()
                    parseAndNormalizeDraft(repairedRaw, name, locale)
                }.getOrThrow()
            Result.success(draft)
        } catch (error: DeepReadTemplateValidationException) {
            Result.failure(IllegalStateException(error.userMessage(locale)))
        } catch (error: IllegalArgumentException) {
            Result.failure(IllegalStateException(error.userMessage(locale)))
        } catch (error: IllegalStateException) {
            Result.failure(IllegalStateException(error.userMessage(locale)))
        } catch (error: TimeoutCancellationException) {
            Result.failure(
                IllegalStateException(
                    if (locale.isChineseLocale()) "模板生成超时，请稍后重试" else "Template generation timed out. Try again later."
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun normalizeDraftTemplate(
        template: DeepReadTemplatePackage,
        requestedName: String,
        locale: Locale,
    ): DeepReadTemplatePackage {
        val normalized = template.copy(
            id = "draft",
            name = requestedName.ifBlank { template.name }.trim().take(48).ifBlank {
                if (locale.isChineseLocale()) "自定义模板" else "Custom template"
            },
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
        locale: Locale,
    ): DeepReadTemplatePackage {
        val decoded = parsePackage(raw)
            ?: throw IllegalStateException("模型没有输出可用模板 JSON")
        return normalizeDraftTemplate(decoded, requestedName, locale)
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
        locale: Locale,
    ): String {
        val chinese = locale.isChineseLocale()
        return """
        ${if (chinese) "你刚才输出的深度阅读模板没有通过 App 校验。" else "The deep-reading template you just output failed App validation."}

        ${if (chinese) "校验错误：" else "Validation error:"}
        ${validationError.ifBlank { if (chinese) "未知校验错误" else "Unknown validation error" }}

        ${if (chinese) "请基于原始需求重新输出一份完整、合法、可保存的 JSON。只输出 JSON，不要解释。" else "Regenerate a complete, valid, savable JSON object from the original request. Output JSON only; do not explain."}

        ${if (chinese) "原始名称：" else "Original name: "}${name.ifBlank { if (chinese) "自定义模板" else "Custom template" }}
        ${if (chinese) "原始设计方向：" else "Original design direction: "}${brief.ifBlank {
            if (locale.isChineseLocale()) {
                "高端 News 杂志 App，强排版、全幅图片、留白、时间轴、引用块、扩展阅读。"
            } else {
                "A premium news-magazine app with strong typography, full-width imagery, whitespace, timelines, pull quotes, and extended reading."
            }
        }}

        ${locale.templateLanguageInstruction()}

        ${if (chinese) "必须遵守：" else "Requirements:"}
        ${if (chinese) "- html 必须是完整 HTML 文档，内联 CSS。" else "- html must be a complete HTML document with inline CSS."}
        ${if (chinese) "- 禁止 JavaScript、iframe、form、button、SVG、canvas、math、audio/video、外链 CSS、@import、CSS url()、真实 href/src 外链。" else "- Forbid JavaScript, iframe, form, button, SVG, canvas, math, audio/video, external CSS, @import, CSS url(), and hard-coded external href/src links."}
        ${if (chinese) "- 禁止 srcset、poster、事件处理器、交互控件和任何脚本动画。" else "- Forbid srcset, poster, event handlers, interactive controls, and scripted animations."}
        ${if (chinese) "- 必须包含 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}。" else "- Include {{title}}, {{summary}}, {{analysis_html}}, and {{extended_reading_html}}."}
        ${if (chinese) "- 必须包含 {{narrative_html}}；若要兼容旧式分栏，也可以额外使用 {{timeline_html}} 或 {{core_points_html}}。" else "- Include {{narrative_html}}; for legacy split layouts, you may also include {{timeline_html}} or {{core_points_html}}."}
        ${if (chinese) "- 块级占位符只能放在标签内容里，不能放进属性或 style。" else "- Put block placeholders only in element content, never in attributes or style."}
        ${if (chinese) "- 图片只能使用 <img src=\"{{hero_image_url}}\" ...>。" else "- Images may use only <img src=\"{{hero_image_url}}\" ...>."}
        ${if (chinese) "- 不要写真实新闻正文，只写模板结构和 CSS。" else "- Do not write real news copy; provide only the template structure and CSS."}

        ${if (chinese) "上一次输出如下，仅供你修正，不要照抄错误：" else "The previous output is below for correction only; do not copy its errors:"}
        ${previousOutput.take(9000)}
        """.trimIndent()
    }

    private fun buildRevisionPrompt(
        currentTemplate: DeepReadTemplatePackage,
        instruction: String,
        locale: Locale,
    ): String {
        val chinese = locale.isChineseLocale()
        return """
        ${if (chinese) "请基于当前 AmberAgent 深度阅读模板，按用户的新要求修改版式。只输出完整 JSON，不要解释。" else "Revise the current AmberAgent Deep Read template according to the user's new request. Output a complete JSON object only; do not explain."}

        ${if (chinese) "当前模板名：" else "Current template name: "}${currentTemplate.name.ifBlank { if (chinese) "自定义模板" else "Custom template" }}
        ${if (chinese) "用户修改要求：" else "User revision request: "}${instruction.ifBlank {
            if (chinese) "在保持可读性和信息密度的前提下优化版式。"
            else "Improve the layout while preserving readability and information density."
        }}

        ${locale.templateLanguageInstruction()}

        ${if (chinese) "当前 HTML：" else "Current HTML:"}
        ${currentTemplate.html.take(36000)}

        ${if (chinese) "输出 JSON：" else "Output JSON:"}
        {
          "id": "draft",
          "name": "${if (locale.isChineseLocale()) "模板名" else "Template name"}",
          "description": "${if (locale.isChineseLocale()) "一句话说明" else "One-sentence description"}",
          "html": "<!doctype html>...",
          "createdByAi": true,
          "schemaVersion": 1
        }

        ${if (chinese) "必须遵守：" else "Requirements:"}
        ${if (chinese) "- 输出完整 HTML 文档和内联 CSS，不要输出片段或 diff。" else "- Output a complete HTML document with inline CSS, not a fragment or diff."}
        ${if (chinese) "- 模板只负责版式，不写真实新闻正文、不写示例新闻事实。" else "- The template is for layout only; do not write real news copy or example news facts."}
        ${if (chinese) "- 禁止 JavaScript、事件处理器、iframe、form、button、SVG、canvas、math、audio/video/source/picture、srcset、poster、@import、CSS url()、真实 href/src 外链。" else "- Forbid JavaScript, event handlers, iframe, form, button, SVG, canvas, math, audio/video/source/picture, srcset, poster, @import, CSS url(), and hard-coded external href/src links."}
        ${if (chinese) "- 必须保留 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}。" else "- Preserve {{title}}, {{summary}}, {{analysis_html}}, and {{extended_reading_html}}."}
        ${if (chinese) "- 必须保留 {{narrative_html}}；兼容旧模板时可额外保留 {{timeline_html}} 或 {{core_points_html}}。" else "- Preserve {{narrative_html}}; for legacy templates, you may also preserve {{timeline_html}} or {{core_points_html}}."}
        ${if (chinese) "- 推荐保留 {{diagram_html}} 和 {{font_css}}；App 会在 WebView 层统一处理字体和字号，不要写 JS 调字号。" else "- Prefer preserving {{diagram_html}} and {{font_css}}; the App handles fonts and sizing in the WebView, so do not add JavaScript for font sizing."}
        ${if (chinese) "- 图片只能使用 <img src=\"{{hero_image_url}}\" ...>。" else "- Images may use only <img src=\"{{hero_image_url}}\" ...>."}
        ${if (chinese) "- 块级占位符不能放进标签属性或 style。" else "- Do not put block placeholders in tag attributes or style."}
        """.trimIndent()
    }

    private fun buildRevisionRepairPrompt(
        currentTemplate: DeepReadTemplatePackage,
        instruction: String,
        previousOutput: String,
        validationError: String,
        locale: Locale,
    ): String {
        val chinese = locale.isChineseLocale()
        return """
        ${if (chinese) "你刚才修订的深度阅读模板没有通过 App 校验。" else "The revised deep-reading template you just output failed App validation."}

        ${if (chinese) "校验错误：" else "Validation error:"}
        ${validationError.ifBlank { if (chinese) "未知校验错误" else "Unknown validation error" }}

        ${if (chinese) "请基于当前模板和用户修改要求，重新输出一份完整、合法、可预览的 JSON。只输出 JSON，不要解释。" else "Regenerate a complete, valid, previewable JSON object from the current template and the user's revision request. Output JSON only; do not explain."}

        ${if (chinese) "当前模板名：" else "Current template name: "}${currentTemplate.name.ifBlank { if (chinese) "自定义模板" else "Custom template" }}
        ${if (chinese) "用户修改要求：" else "User revision request: "}${instruction.ifBlank { if (chinese) "优化模板版式" else "Improve the template layout." }}

        ${locale.templateLanguageInstruction()}

        ${if (chinese) "必须遵守：" else "Requirements:"}
        ${if (chinese) "- html 必须是完整 HTML 文档，内联 CSS。" else "- html must be a complete HTML document with inline CSS."}
        ${if (chinese) "- 禁止 JavaScript、事件处理器、iframe、form、button、SVG、canvas、math、audio/video/source/picture、srcset、poster、@import、CSS url()、真实 href/src 外链。" else "- Forbid JavaScript, event handlers, iframe, form, button, SVG, canvas, math, audio/video/source/picture, srcset, poster, @import, CSS url(), and hard-coded external href/src links."}
        ${if (chinese) "- 必须包含 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}。" else "- Include {{title}}, {{summary}}, {{analysis_html}}, and {{extended_reading_html}}."}
        ${if (chinese) "- 必须包含 {{narrative_html}}；可以额外使用 {{timeline_html}} 或 {{core_points_html}}。" else "- Include {{narrative_html}}; you may also use {{timeline_html}} or {{core_points_html}}."}
        ${if (chinese) "- 推荐包含 {{diagram_html}} 和 {{font_css}}。" else "- Prefer including {{diagram_html}} and {{font_css}}."}
        ${if (chinese) "- 图片只能使用 <img src=\"{{hero_image_url}}\" ...>。" else "- Images may use only <img src=\"{{hero_image_url}}\" ...>."}
        ${if (chinese) "- 块级占位符只能放在标签内容里，不能放进属性或 style。" else "- Put block placeholders only in element content, never in attributes or style."}
        ${if (chinese) "- 模板内容只负责版式，不生成新闻事实。" else "- The template is for layout only and must not generate news facts."}

        ${if (chinese) "当前模板 HTML：" else "Current template HTML:"}
        ${currentTemplate.html.take(7000)}

        ${if (chinese) "上一次无效输出：" else "Previous invalid output:"}
        ${previousOutput.take(9000)}
        """.trimIndent()
    }

    private fun Throwable.userMessage(locale: Locale): String {
        val chinese = locale.isChineseLocale()
        val text = message.orEmpty()
        return when {
            "Syntax error in regexp" in text -> if (chinese) "模板校验器异常，已修复后请重新生成" else "The template validator failed. Regenerate the template."
            "missing placeholders" in text -> if (chinese) "模板缺少必要占位符，请重新生成" else "The template is missing required placeholders. Regenerate it."
            "Block placeholder" in text -> if (chinese) "模板把内容占位符放错了位置，请重新生成" else "A block placeholder is in the wrong location. Regenerate the template."
            "Hard-coded external" in text -> if (chinese) "模板包含外链资源，请重新生成" else "The template contains external resources. Regenerate it."
            "External" in text || "not allowed" in text || "CSS URLs" in text -> if (chinese) "模板包含不允许的 HTML/CSS 能力，请重新生成" else "The template uses disallowed HTML/CSS features. Regenerate it."
            "模型没有输出可用模板 JSON" in text -> if (chinese) text else "The model did not return usable template JSON."
            text.isBlank() -> if (chinese) "模板生成失败，请重试" else "Template generation failed. Try again."
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

    private fun buildPrompt(name: String, brief: String, locale: Locale): String {
        val chinese = locale.isChineseLocale()
        return """
        ${if (chinese) "请生成一个 AmberAgent 深度阅读静态 HTML 模板，名称：" else "Generate an AmberAgent Deep Read static HTML template named: "}${name.ifBlank { if (chinese) "自定义模板" else "Custom template" }}${if (chinese) "。" else "."}
        ${if (chinese) "设计方向：" else "Design direction: "}${brief.ifBlank {
            if (chinese) {
                "高端 News 杂志 App，强排版、全幅图片、留白、时间轴、引用块、扩展阅读。"
            } else {
                "A premium news-magazine app with strong typography, full-width imagery, whitespace, timelines, pull quotes, and extended reading."
            }
        }}

        ${locale.templateLanguageInstruction()}

        ${if (chinese) "## 设计能力注入" else "## Design guidance"}
        ${if (chinese) "你不是普通网页生成器，而是移动端杂志阅读模板设计师。请先在心里完成设计系统，再输出模板：" else "You are not an ordinary web generator; you are a mobile magazine-reading template designer. Complete the design system mentally before outputting the template:"}
        ${if (chinese) "- 明确一个强概念方向，例如：经典报刊、斜切新闻、科技长文、学术期刊、冷静高端、暗色实验室。不要同时混用多个风格。" else "- Choose one strong concept, such as broadsheet, diagonal news, long-form technology, academic journal, quiet luxury, or dark lab. Do not mix multiple styles."}
        ${if (chinese) "- 版式必须像“画布切割”，不是卡片堆叠：允许全幅、斜切、非对称网格、强留白、细线、编号、引言、时间轴。" else "- Make the layout feel like a cut canvas rather than stacked cards: full-bleed areas, diagonal cuts, asymmetric grids, generous whitespace, rules, numbering, pull quotes, and timelines are welcome."}
        ${if (chinese) "- Typography 是主角：标题用衬线 display，正文用高可读衬线，元信息用小号无衬线；禁止所有字号都很大。" else "- Typography is the protagonist: use a serif display face for headlines, a highly readable serif for body copy, and a small sans serif for metadata; do not make every type size large."}
        ${if (chinese) "- 移动端信息密度要合理：正文 14-15px，扩展阅读 12-13px，metadata 9-10px，标题可大但不能挤压内容。" else "- Keep mobile information density reasonable: body text 14-15px, extended reading 12-13px, metadata 9-10px; headlines may be large but must not crowd the content."}
        ${if (chinese) "- 使用 8pt spacing rhythm，section 间距有节奏；不要用大圆角卡片、紫蓝渐变、玻璃拟态、emoji、图标堆砌、假数据装饰。" else "- Use an 8pt spacing rhythm with intentional section intervals; avoid large rounded cards, purple-blue gradients, glassmorphism, emoji, icon piles, and decorative fake data."}
        ${if (chinese) "- 必须考虑 loading/partial 内容：占位符区域即使暂时为空，也要保持版式优雅，不要大面积空白或塌陷。" else "- Account for loading/partial content: placeholder areas should remain elegant even when temporarily empty, without large blank gaps or collapsed sections."}
        ${if (chinese) "- 视觉语言应接近高端中文新闻杂志 App：克制、留白、纸媒感、可读，不要像营销落地页。" else "- The visual language should resemble a premium English-language news magazine app: restrained, spacious, tactile, and readable rather than a marketing landing page."}

        ${if (chinese) "## 可用固定样稿预览机制" else "## Fixed sample preview mechanism"}
        ${if (chinese) "App 会用固定的中文样稿预览模板：标题、摘要、时间轴、关键脉络、分析、扩展阅读都会被注入占位符。" else "The App uses a fixed English sample preview template: title, summary, timeline, key points, analysis, and extended reading are injected into the placeholders."}
        ${if (chinese) "所以 html 里不要写任何真实新闻正文或样例内容，只负责结构、CSS 和占位符位置。" else "Therefore, do not write real news copy or sample content in the html; provide only structure, CSS, and placeholder placement."}
        ${if (chinese) "用户选择模板时只看样稿版式；真实深度阅读内容由另一个 Agent 生成。" else "Users choose a template based on the sample layout; another Agent generates the real Deep Read content."}

        ${if (chinese) "输出 JSON：" else "Output JSON:"}
        {
          "id": "draft",
          "name": "${if (locale.isChineseLocale()) "模板名" else "Template name"}",
          "description": "${if (locale.isChineseLocale()) "一句话说明" else "One-sentence description"}",
          "html": "<!doctype html>...",
          "createdByAi": true,
          "schemaVersion": 1
        }

        ${if (chinese) "硬性要求：" else "Hard requirements:"}
        ${if (chinese) "- html 必须是完整 HTML 文档，内联 CSS，禁止 JavaScript、事件处理器、iframe、form、button、SVG、canvas、math、audio/video/source/picture、srcset、poster、外链 CSS、@import、CSS url()。" else "- html must be a complete HTML document with inline CSS; forbid JavaScript, event handlers, iframe, form, button, SVG, canvas, math, audio/video/source/picture, srcset, poster, external CSS, @import, and CSS url()."}
        ${if (chinese) "- 必须包含占位符 {{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}，且 {{narrative_html}} / {{timeline_html}} / {{core_points_html}} 至少包含一个。" else "- Include {{title}}, {{summary}}, {{analysis_html}}, and {{extended_reading_html}}, plus at least one of {{narrative_html}} / {{timeline_html}} / {{core_points_html}}."}
        ${if (chinese) "- 推荐支持这些占位符：{{topic_type}}、{{source_label}}、{{hero_image_url}}、{{hero_caption}}、{{narrative_html}}、{{timeline_html}}、{{core_points_html}}、{{diagram_html}}、{{analysis_html}}、{{extended_reading_html}}、{{font_css}}。" else "- Prefer supporting these placeholders: {{topic_type}}, {{source_label}}, {{hero_image_url}}, {{hero_caption}}, {{narrative_html}}, {{timeline_html}}, {{core_points_html}}, {{diagram_html}}, {{analysis_html}}, {{extended_reading_html}}, and {{font_css}}."}
        ${if (chinese) "- 如果使用 hero 图片，只能写 <img src=\"{{hero_image_url}}\" ...>；不要写真实图片 URL。" else "- If using a hero image, write only <img src=\"{{hero_image_url}}\" ...>; do not write a real image URL."}
        ${if (chinese) "- 块级占位符 {{narrative_html}}、{{timeline_html}}、{{core_points_html}}、{{diagram_html}}、{{analysis_html}}、{{extended_reading_html}} 必须放在正文节点中，不能放进标签属性或 style。" else "- Place block placeholders {{narrative_html}}, {{timeline_html}}, {{core_points_html}}, {{diagram_html}}, {{analysis_html}}, and {{extended_reading_html}} in body nodes, never in tag attributes or style."}
        ${if (chinese) "- 不要写任何固定 href/src 外链；扩展阅读链接只能来自 {{extended_reading_html}}。" else "- Do not write hard-coded external href/src links; extended-reading links may come only from {{extended_reading_html}}."}
        ${if (chinese) "- 模板内容只负责版式，不生成新闻事实，不写示例新闻正文。" else "- The template is for layout only; do not generate news facts or write sample news copy."}
        ${if (chinese) "- 移动端优先，正文 14-15px、line-height 1.68 左右；可用斜切 Hero、红色时间轴、灰色 metadata。" else "- Optimize for mobile: body text around 14-15px with line-height around 1.68; diagonal heroes, red timelines, and gray metadata are welcome."}
        ${if (chinese) "- 不要把 {{extended_reading_html}} 包在会裁切文字的固定高度容器里；扩展阅读必须可点击、可完整显示两行标题。" else "- Do not wrap {{extended_reading_html}} in a fixed-height container that clips text; extended reading must remain clickable and show two full lines of its title."}
        ${if (chinese) "- 如果有 {{hero_image_url}}，必须给无图状态留出纯排版 fallback：例如用 CSS 让空 img 不占巨大空间，或把 hero 区和 headline 分开。" else "- When {{hero_image_url}} is present, provide a typography-only fallback for the no-image state, such as keeping an empty img from taking excessive space or separating the hero area from the headline."}
        ${if (chinese) "- 输出的 CSS 必须包含清晰的移动端宽度约束、字号层级和 section rhythm；不要让 WebView 默认字号接管。" else "- The CSS must define clear mobile width constraints, type hierarchy, and section rhythm; do not let the WebView default text size take over."}
        """.trimIndent()
    }

    companion object {
        private const val MODEL_TIMEOUT_MS = 120_000L
        private const val REPAIR_TIMEOUT_MS = 90_000L
    }
}

private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

private fun Locale.templateLanguageInstruction(): String = if (isChineseLocale()) {
    "输出语言：模板 name、description 以及任何固定的用户可见文本使用中文；占位符、CSS 选择器和 JSON 字段名保持原样。"
} else {
    "Output language: write the template name, description, and any fixed user-visible text in English; keep placeholders, CSS selectors, and JSON field names unchanged."
}
