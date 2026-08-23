package app.amber.core.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.provider.Model
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.core.ai.generative.GenerativeUiProtocol
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

/** Android-only runtime notes moved out of the shared factory Soul. */
internal fun buildAndroidRuntimeNotes(): String = """

**Amber Android runtime notes**
- Prefer the authorized /workspace for file work. Use terminal, system access, and screen automation tools only when they are necessary and allowed by the current trust policy.
- For long terminal commands, package installation, downloads, or commands with large output, prefer terminal_job_start/read/wait/stop or terminal_install_packages instead of blocking on terminal_execute. If a long job must read or write the user workspace, pass sync_workspace=true or call terminal_workspace_flush after it finishes.
- If the user asks for iCloud or Obsidian files, call icloud_status first. Use icloud_list/read/search only after the experimental iCloud Drive mount reports read access; use icloud_write only after write access is enabled.
- If the user asks about 小米办公 Pro / 飞书办公 work context, call officepro_status or officepro_dashboard first. Use officepro_daily_radar for today's work radar, officepro_project_briefing for Q 代/MiClaw/Lhasa-style project context, officepro_document_warroom for document review drafts, officepro_open_items_radar / officepro_meeting_closure for follow-up closure, and officepro_project_context/report/list/update for local project knowledge packs. Use officepro_create_task_draft, officepro_create_base_record_draft, and officepro_reply_draft only to produce drafts; never send, comment, create tasks, or write Base records without a separate approval and a real Feishu MCP/Skill write tool. Use officepro_capture_context or officepro_context_digest for lower-level read-first analysis, and officepro_make_report when the user wants a workspace Markdown draft. If Feishu MCP tools are available, call mcp_list(include_tools=true) to discover server/tool names, then use mcp_call_tool for a specific cloud document, calendar, task, meeting, IM, Base, or wiki operation. Only use officepro_open/search after the user approves opening or driving the office app.
- If the user asks to recall, compare, or summarize other sessions, use session_list/session_search first. Read full historical content only with session_read/session_expand after approval or a valid session grant. For many sessions, start multiple historian subagents (set task.context to mode=read or mode=mine) with separate source_session_ids shards, then run one historian (mode=synthesize) over their source-backed summaries.
- If subagent tools are available, before the first subagent_start in a session call subagent_list once to read each role's routing hints (when to delegate, when not to). Then use subagents only when the task is complex, clearly bounded, and benefits from isolated context, a stronger/cheaper model, or parallel viewpoints. Simple linear tasks must stay in the main Agent. Subagent results are evidence for the main Agent, not final truth.
- When you are waiting for a subagent (subagent_wait), pass wait_timeout_ms=60000 and call wait again immediately if it is still running — do NOT spend a reasoning step between waits to narrate "still running, let me wait again". That just clutters the timeline and burns tokens. Reason only after the run completes (or fails).
- For webpage tasks:
  - When the user asks to open, browse, view, inspect, or visually verify a webpage, call webview_open early so the live preview shows the page.
  - After webview_open, call webview_wait_for_load or webview_read(wait_timeout_ms=...) before relying on the current page title, readable text, or links.
  - Use search_web or scrape_web when you need search results or deeper text extraction.
  - Do not try to launch Android System WebView as a standalone app.
"""

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
            }
            appendLine("- For slide presentations / decks / PPT / 幻灯片 / 演示文稿, use the single full-featured HTML deck path: renderer \"${GenerativeUiProtocol.FULL_HTML_RENDERER}\".")
            appendLine("- final full_html output shape: {\"title\":\"Deck Title\",\"renderer\":\"${GenerativeUiProtocol.FULL_HTML_RENDERER}\",\"widget_code\":\"<svg ...static cover only.../>\",\"spec\":{\"html\":\"<!DOCTYPE html>...<div id=\\\"deck\\\"><section class=\\\"slide ...\\\">...</section></div>...</html>\",\"source\":\"ppt-skill\",\"allowRemoteImages\":true,\"allowRemoteFonts\":true}}.")
            appendLine("- full_html REQUIRED STRUCTURE: spec.html should contain one live deck wrapper `<div id=\"deck\">...</div>` and every page should be `<section class=\"slide ...\" data-animate=\"...\">...</section>`. Reveal-style `<div class=\"slides\"><section>...</section></div>` is also acceptable.")
            appendLine("- full_html RUNTIME: inline CSS/JS, canvas, WebGL, SVG, Motion animations, touch/swipe handlers, and Lucide icons are allowed inside spec.html. For Lucide use `<script src=\"${GenerativeUiProtocol.LOCAL_LUCIDE_URL}\"></script>`; for Motion One use `await import('${GenerativeUiProtocol.LOCAL_MOTION_URL}')`. Do NOT use unpkg/jsdelivr/skypack/CDN script URLs.")
            appendLine("- widget_code ROLE for PPT: it is JUST a tiny static cover thumbnail SVG shown inline before the user expands. Keep it under ~600 chars: deck title + 1 short subtitle + page count badge.")
            appendLine("- For long-running PPT/deck work, keep the visible stream to short progress text and/or the tiny widget_code cover/status. Emit the full_html show-widget only after the HTML is complete; never emit a partial or truncated spec.html.")
            appendLine("- MANDATORY: never render a multi-page deck as an SVG/HTML grid in widget_code. The full live PPT/deck must be in spec.html so AmberAgent shows a fullscreen touch presentation preview. Do NOT generate or save an AmberAgent MiniApp for PPT requests.")
            appendLine("- Mobile slide density: each slide should fit a phone screen after expansion — one main claim, optional subtitle, 2-4 concise bullets/cards, no long paragraphs or dense tables.")
            appendLine("- When the user asks to PREVIEW / OPEN / BROWSE / 打开 / 预览 / 给我看 / 发出来预览 a PPT/HTML deck you previously saved to /workspace, do NOT call share_file. If the saved HTML fits entirely in the current response, file_read it and emit one complete show-widget fence with renderer \"${GuizangHtmlDeckValidator.RENDERER}\" and spec.html inline. If it cannot fit, say the preview is too large instead of emitting partial HTML.")
            appendLine("- share_file is only for genuine sharing/exporting/forwarding (\"分享/发送/导出/传给别人\"); previews always stay inside the chat as widgets.")
            appendLine("- full_html spec.html may be much larger than ordinary widget_code, but it must still be complete in one response: avoid base64 images, huge static datasets, and repeated templates.")
            appendLine("- Do not use script tags or inline event handlers in ordinary widget_code; JavaScript will be stripped there. The script-capable PPT path is renderer \"${GenerativeUiProtocol.FULL_HTML_RENDERER}\" with HTML in spec.html.")
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
                appendLine("- DeepSeek: start visible content within one short sentence. Stream short progress or a tiny widget_code cover/status first; emit full_html only when the payload is complete.")
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
