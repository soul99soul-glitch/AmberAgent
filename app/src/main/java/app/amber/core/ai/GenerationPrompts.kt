package app.amber.core.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantMemory
import app.amber.core.repository.ConversationRepository
import app.amber.core.utils.JsonInstantPretty
import app.amber.core.utils.toLocalDate

internal fun buildAgentSoulPrompt(soulMarkdown: String) =
    soulMarkdown.trim().takeIf { it.isNotBlank() }?.let { soul ->
        buildString {
            appendLine()
            appendLine("**AmberAgent Soul / agents.md**")
            appendLine("The following app-level behavior guide is injected into every conversation:")
            appendLine("<agents_md>")
            appendLine(soul)
            appendLine("</agents_md>")
        }
    }.orEmpty()

internal fun buildGenerativeUiPrompt(setting: GenerativeUiSetting): String =
    buildGenerativeUiPrompt(setting = setting, model = null)

internal fun buildGenerativeUiPrompt(setting: GenerativeUiSetting, model: Model?): String =
    if (!setting.enabled) {
        ""
    } else {
        buildString {
            appendLine()
            appendLine("**AmberAgent Generative UI**")
            appendLine("You may create safe inline visual widgets in the chat timeline with this fenced JSON format:")
            appendLine("```show-widget")
            appendLine("""{"title":"流程概览","widget_code":"<svg width=\"100%\" viewBox=\"0 0 680 180\" xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"24\" y=\"24\" width=\"632\" height=\"132\" rx=\"18\" fill=\"#ffffff\" stroke=\"#e5e7eb\"/><text x=\"48\" y=\"70\" font-size=\"20\" font-weight=\"700\" fill=\"#111827\">流程概览</text><rect x=\"48\" y=\"96\" width=\"128\" height=\"40\" rx=\"12\" fill=\"#eff6ff\"/><text x=\"72\" y=\"122\" font-size=\"14\" fill=\"#1e3a8a\">输入</text><path d=\"M188 116 H258\" stroke=\"#94a3b8\" stroke-width=\"2\" marker-end=\"url(#a)\"/><rect x=\"270\" y=\"96\" width=\"128\" height=\"40\" rx=\"12\" fill=\"#f0fdf4\"/><text x=\"294\" y=\"122\" font-size=\"14\" fill=\"#166534\">处理</text><path d=\"M410 116 H480\" stroke=\"#94a3b8\" stroke-width=\"2\" marker-end=\"url(#a)\"/><rect x=\"492\" y=\"96\" width=\"128\" height=\"40\" rx=\"12\" fill=\"#fff7ed\"/><text x=\"516\" y=\"122\" font-size=\"14\" fill=\"#9a3412\">结果</text><defs><marker id=\"a\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L8,4 L0,8 Z\" fill=\"#94a3b8\"/></marker></defs></svg>"}""")
            appendLine("```")
            appendLine("- Use widgets only when a process flow, timeline, comparison, risk matrix, architecture map, data chart, status card, or UI mockup helps.")
            appendLine("- Do NOT create widgets for tool routing, subagent/skill delegation, progress updates, or plan/status summaries. If you need a tool, subagent, or skill, call it first and wait for real output.")
            appendLine("- Put explanatory text outside the code fence.")
            appendLine("- For drawing/diagram/chart requests, do not call tools just to create SVG, HTML, or widget JSON; write the show-widget block directly in visible assistant content.")
            appendLine("- Do not call eval_javascript, terminal, browser, WebView, or automation tools only to assemble a visual widget.")
            appendLine("- Keep the JSON object on one line when possible; escape quotes and newlines inside widget_code.")
            appendLine("- widget_code must be a JSON string and stay under ${setting.maxWidgetCodeChars} characters.")
            appendLine("- Prefer SVG with width=\"100%\" and viewBox=\"0 0 680 H\" for responsive rendering.")
            appendLine("- Keep every visible SVG element inside the viewBox: use at least 24px padding, and ensure x + width <= 656 and y + height <= H - 24 for a 680-wide viewBox.")
            appendLine("- Use 10-16px labels in compact diagrams, wrap long labels manually, and avoid dense text that can overflow small mobile cards.")
            appendLine("- Never output generic placeholder titles or template-only widget code; every widget must contain real rendered SVG/HTML.")
            appendLine("- Do not use iframe, object, embed, form, meta, link, base tags, external CDNs, fixed positioning, or navigation.")
            if (setting.enableActions) {
                appendLine("- You may add up to 3 optional native actions: \"actions\":[{\"id\":\"explain\",\"label\":\"解释这块\",\"instruction\":\"解释图中的关键节点\"}].")
            }
            if (setting.enableStructuredRenderers) {
                appendLine("- For requests that ask to draw, visualize, or inspect a diagram live, prefer widget_code SVG for this request so the timeline can render progressively while you stream.")
                appendLine("- Use renderer/spec only for compact chart/diagram data when streaming progressive drawing is less important: {\"title\":\"...\",\"renderer\":\"chart\",\"spec\":{\"type\":\"bar|line|pie\",\"x\":[\"A\"],\"series\":[{\"name\":\"Value\",\"data\":[1]}]}}.")
                appendLine("- Diagram specs support type \"flow\", \"timeline\", or \"matrix\" with concise labels and details.")
            }
            if (setting.enableInteractiveCharts) {
                appendLine("- For interactive charts with hover tooltips and animations, use renderer \"vchart\" with a VChart spec: {\"title\":\"...\",\"renderer\":\"vchart\",\"spec\":{\"type\":\"bar\",\"data\":[{\"values\":[{\"x\":\"A\",\"y\":10}]}],\"xField\":\"x\",\"yField\":\"y\"}}.")
                appendLine("- VChart spec follows VChart 2.x API: type, data, xField, yField, seriesField, color, legends, tooltip, title, etc.")
                appendLine("- For generic slide presentations / decks / PPT / 幻灯片 / 演示文稿, use renderer \"slides\" with Slides Spec V2:")
                appendLine("    {\"title\":\"Deck Title\",\"renderer\":\"slides\",\"widget_code\":\"<svg .../>\",\"spec\":{\"schemaVersion\":2,\"style\":\"magazine|swiss\",\"accent\":\"#1F5EFF\",\"fontPack\":\"source-han-serif-sc-regular\",\"slides\":[{\"layout\":\"cover\",\"kicker\":\"...\",\"title\":\"Page 1\",\"subtitle\":\"...\",\"content\":[\"bullet 1\",\"bullet 2\"],\"notes\":\"...\"}]}}")
                appendLine("- Slides Spec V2 layout choices are fixed: cover, section, quote, split, metrics, timeline, cards, image-grid, comparison, closing. Every slide should set one layout.")
                appendLine("- Recommended sequence: cover → section → metrics/cards/split/timeline/comparison → quote → closing. Split complex material across more slides instead of shrinking text.")
                appendLine("- spec FIELD CONTRACT: top-level spec keeps schemaVersion/style/accent/fontPack/slides. Each slide uses literal keys: layout, kicker, title, subtitle, content, items, metrics, media, notes. Use items for cards/timeline/comparison, metrics for big numbers, media for images.")
                appendLine("- spec REAL DATA: every slide object MUST be filled with the real page content (title text, real bullets, real notes). Do NOT emit empty/skeleton objects with only a title — the deck will appear blank to the user.")
                appendLine("- widget_code ROLE for slides: it is JUST a tiny cover thumbnail SVG shown inline before the user expands. Keep it under ~600 chars: deck title + 1 short subtitle + page count badge. Do NOT paint detailed page bodies, do NOT list every page, do NOT include navigation hints, hotkeys, or viewer instructions (no \"← → 翻页\", no \"F 全屏\", no \"S 演讲者模式\", no \"点击展开\" — the app handles those natively).")
                appendLine("- MANDATORY: never render a multi-page deck as an SVG/HTML grid in widget_code. That produces a static image with no pagination. The slides renderer gives horizontal swipe, per-page vertical scroll, full-screen, and per-page image export — all inline in the chat timeline.")
                appendLine("- guizang-ppt-skill DEFAULT: when the user asks for guizang, guizang-ppt-skill, Swiss International style, magazine deck from that skill, or a guizang-like PPT, emit renderer \"guizang_html\" by default. Do not choose ordinary HTML widget, mini app, standalone webpage, or native slides for guizang skill output.")
                appendLine("- guizang_html output shape: {\"title\":\"Deck Title\",\"renderer\":\"guizang_html\",\"widget_code\":\"<svg ...static cover only.../>\",\"spec\":{\"html\":\"<!DOCTYPE html>...<div id=\\\"deck\\\"><section class=\\\"slide ...\\\">...</section></div>...</html>\",\"source\":\"guizang-ppt-skill\",\"allowRemoteImages\":true,\"allowRemoteFonts\":true}}.")
                appendLine("- guizang_html REQUIRED STRUCTURE: spec.html must contain one live deck wrapper `<div id=\"deck\">...</div>` and every page must be `<section class=\"slide ...\" data-animate=\"...\">...</section>`. Do not output a flat web app, a single SVG, a `slides` JSON spec, or pages outside #deck.")
                appendLine("- guizang_html LOCAL RUNTIME ONLY: for Lucide use `<script src=\"${GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL}\"></script>`; for Motion One use `await import('${GuizangHtmlDeckValidator.LOCAL_MOTION_URL}')`. Do NOT use unpkg/jsdelivr/skypack/CDN script URLs in the generated HTML.")
                appendLine("- guizang_html preview contract: widget_code is only a tiny static cover; the live PPT/deck must be in spec.html so AmberAgent shows a fullscreen touch presentation preview. Do NOT generate or save an AmberAgent MiniApp for PPT requests.")
                appendLine("- Mobile slide density: each slide should fit a phone screen after expansion — one main claim, optional subtitle, 2-4 concise bullets/cards, no long paragraphs or dense tables.")
                appendLine("- When the user asks to PREVIEW / OPEN / BROWSE / 打开 / 预览 / 给我看 / 发出来预览 a PPT you previously saved to /workspace, do NOT call share_file. Instead: file_read the saved deck, then in your visible reply emit a NEW show-widget fence with renderer \"slides\" and the spec JSON inline so the deck appears as an inline card in the chat.")
                appendLine("- share_file is only for genuine sharing/exporting/forwarding (\"分享/发送/导出/传给别人\"); previews always stay inside the chat as widgets.")
                appendLine("- VChart/slides specs must be under 50KB; array data capped at 1000 items; string values under 500 chars.")
            }
            appendLine("- Do not use script tags or inline event handlers in ordinary widget_code; JavaScript will be stripped there. The only script-capable deck path is renderer \"guizang_html\" for guizang-style PPT requests.")
            appendLine("- Do not make decorative widgets that merely repeat the prose answer.")
            buildGenerativeUiModelGuidance(model).takeIf { it.isNotBlank() }?.let { guidance ->
                append(guidance)
            }
        }
    }

private fun buildGenerativeUiModelGuidance(model: Model?): String {
    val name = listOfNotNull(model?.modelId, model?.displayName)
        .joinToString(" ")
        .lowercase()
    if (name.isBlank()) return ""
    return buildString {
        appendLine()
        appendLine("Model-specific widget guidance:")
        when {
            "deepseek" in name -> {
                appendLine("- DeepSeek: keep hidden reasoning extremely brief for visual requests; do not draft coordinates, SVG, JSON, or layout prose in reasoning.")
                appendLine("- DeepSeek: start visible content within one short sentence, then stream the show-widget block.")
                appendLine("- DeepSeek: if you catch yourself spending more than 2 sentences of hidden thought on layout, STOP reasoning and start writing the visible widget immediately.")
                appendLine("- DeepSeek: never put widget_code, SVG tags, or JSON objects inside <think> blocks.")
            }

            "kimi" in name || "moonshot" in name -> {
                appendLine("- Kimi/Moonshot: do not use function/tool calls to generate SVG. Do not place SVG in tool arguments.")
                appendLine("- Kimi/Moonshot: output a visible show-widget fence directly; avoid first emitting raw SVG or JavaScript code blocks.")
                appendLine("- Kimi/Moonshot: NEVER call eval_javascript, code_interpreter, or any code execution tool to produce widget content.")
                appendLine("- Kimi/Moonshot: the show-widget fence IS the output mechanism; no intermediate step is needed.")
            }

            "minimax" in name || "mini-max" in name || "abab" in name || Regex("""\bm\d+(?:\.\d+)?\b""").containsMatchIn(name) -> {
                appendLine("- MiniMax: prioritize layout safety over detail density. Use one 680-wide viewBox and keep all boxes, dashed groups, arrows, and text inside it.")
                appendLine("- MiniMax: do not draw elements that extend past the right edge; reduce columns, shorten labels, or increase H instead.")
                appendLine("- MiniMax: avoid tiny multi-line text inside small boxes; use fewer, larger nodes with concise labels.")
                appendLine("- MiniMax: before finalizing SVG, verify: every rect must have x + width <= 656, every text x + estimated_width <= 656, every circle cx + r <= 656.")
                appendLine("- MiniMax: if content doesn't fit in 680px width, stack vertically instead of squeezing horizontally.")
            }

            "claude" in name || "anthropic" in name -> {
                appendLine("- Claude: use one polished, self-contained SVG widget; native actions are welcome when they help the user iterate.")
                appendLine("- Claude: avoid plan/tool detours and long preambles before the fence; put design quality into the visible SVG itself.")
            }

            "gemini" in name || "google" in name -> {
                appendLine("- Gemini: keep widget JSON simple and valid; prefer one widget_code SVG over multiple partial code blocks.")
                appendLine("- Gemini: do not wrap the show-widget fence inside another markdown code fence.")
            }

            "qwen" in name || "dashscope" in name || "aliyun" in name -> {
                appendLine("- Qwen: avoid wrapping SVG in ordinary markdown code fences; use only the show-widget fence.")
                appendLine("- Qwen: do not output a separate ````svg` block before or after the show-widget fence.")
            }

            "gpt" in name || "openai" in name || Regex("""\bo\d+""").containsMatchIn(name) -> {
                appendLine("- OpenAI: use visible widget_code directly; do not describe a widget without emitting the fenced JSON.")
                appendLine("- OpenAI: do not wrap show-widget inside another code fence or quote block.")
            }

            else -> {
                appendLine("- This model: prefer visible widget_code SVG directly; avoid hidden drafting and tool calls for static visuals.")
            }
        }
    }
}

internal fun buildMemoryPrompt(
    title: String,
    description: String,
    memories: List<AssistantMemory>,
) =
    buildString {
        if (memories.isEmpty()) return@buildString
        appendLine()
        append("**")
        append(title)
        append("**")
        appendLine()
        append(description)
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal fun buildCoreMemoryPrompt(memories: List<AssistantMemory>) =
    buildMemoryPrompt(
        title = "Core Memories",
        description = "These are durable AmberAgent core memories stored in the global bucket. Treat them as explicit user-approved context.",
        memories = memories,
    )

internal fun buildShortTermMemoryPrompt(memories: List<AssistantMemory>) =
    buildMemoryPrompt(
        title = "Short-Term Memories",
        description = "These are concise recent task summaries. Use them for continuity, but prefer the current conversation when there is conflict.",
        memories = memories,
    )

internal fun buildLongTermMemoryPrompt(memories: List<AssistantMemory>) =
    buildMemoryPrompt(
        title = "Long-Term Memories",
        description = "These are stable preferences, recurring interests, plans, and facts distilled for use across future conversations.",
        memories = memories,
    )

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}

internal suspend fun buildRecentChatsPrompt(conversationRepo: ConversationRepository): String {
    val recentConversations = conversationRepo.getRecentConversations(limit = 10)
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations across AmberAgent. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
