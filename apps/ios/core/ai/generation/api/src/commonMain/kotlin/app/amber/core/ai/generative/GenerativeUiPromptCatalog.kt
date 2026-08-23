package app.amber.core.ai.generative

import app.amber.ai.provider.Model
import app.amber.core.settings.GenerativeUiSetting

/** Cross-platform constants used by both generation prompts and native renderers. */
object GenerativeUiProtocol {
    const val FULL_HTML_RENDERER = "full_html"
    const val LOCAL_RUNTIME_BASE = "https://amberagent.local/full-html/"
    const val LOCAL_MOTION_URL = "${LOCAL_RUNTIME_BASE}motion.min.js"
    const val LOCAL_LUCIDE_URL = "${LOCAL_RUNTIME_BASE}lucide.min.js"
}

/** The model-facing show-widget contract shared by Android and iOS. */
object GenerativeUiPromptCatalog {
    fun build(setting: GenerativeUiSetting, model: Model?): String =
        if (!setting.enabled) {
            ""
        } else {
            buildString {
                appendLine()
                appendLine("**AmberAgent Generative UI**")
                appendLine("You may create safe inline visual widgets in the chat timeline with this fenced JSON format:")
                appendLine("```show-widget")
                appendLine("""{"title":"流程概览","widget_code":"<svg width=\"100%\" viewBox=\"0 0 680 180\" xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"24\" y=\"24\" width=\"632\" height=\"132\" rx=\"18\" fill=\"#ffffff\" stroke=\"#e5e7eb\"/><text x=\"48\" y=\"70\" font-size=\"20\" font-weight=\"700\" fill=\"#111827\">流程概览</text><rect x=\"48\" y=\"96\" width=\"128\" height=\"40\" rx=\"12\" fill=\"#eff6ff\"/><text x=\"72\" y=\"122\" font-size=\"14\" fill=\"#1e3a8a\">输入</text><path d=\"M188 116 H258\" stroke=\"#94a3b8\" stroke-width=\"2\"/><rect x=\"270\" y=\"96\" width=\"128\" height=\"40\" rx=\"12\" fill=\"#f0fdf4\"/><text x=\"294\" y=\"122\" font-size=\"14\" fill=\"#166534\">处理</text><path d=\"M410 116 H480\" stroke=\"#94a3b8\" stroke-width=\"2\"/><rect x=\"492\" y=\"96\" width=\"128\" height=\"40\" rx=\"12\" fill=\"#fff7ed\"/><text x=\"516\" y=\"122\" font-size=\"14\" fill=\"#9a3412\">结果</text></svg>"}""")
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
                appendLine("- The JSON title is the native card header; do not repeat the same title inside the SVG artwork.")
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
                    appendLine("- For interactive charts with hover tooltips and animations, use renderer \"vchart\" with a VChart spec.")
                }
                appendLine("- For slide presentations / decks / PPT / 幻灯片 / 演示文稿, use the single full-featured HTML deck path: renderer \"${GenerativeUiProtocol.FULL_HTML_RENDERER}\".")
                appendLine("- final full_html output shape: {\"title\":\"Deck Title\",\"renderer\":\"${GenerativeUiProtocol.FULL_HTML_RENDERER}\",\"widget_code\":\"<svg ...static cover only.../>\",\"spec\":{\"html\":\"<!DOCTYPE html>...<div id=\\\"deck\\\"><section class=\\\"slide ...\\\">...</section></div>...</html>\",\"source\":\"ppt-skill\",\"allowRemoteImages\":true,\"allowRemoteFonts\":true}}.")
                appendLine("- full_html REQUIRED STRUCTURE: spec.html should contain one live deck wrapper `<div id=\"deck\">...</div>` and every page should be `<section class=\"slide ...\" data-animate=\"...\">...</section>`. Reveal-style `<div class=\"slides\"><section>...</section></div>` is also acceptable.")
                appendLine("- full_html RUNTIME: inline CSS/JS, canvas, WebGL, SVG, Motion animations, touch/swipe handlers, and Lucide icons are allowed inside spec.html. For Lucide use `<script src=\"${GenerativeUiProtocol.LOCAL_LUCIDE_URL}\"></script>`; for Motion One use `await import('${GenerativeUiProtocol.LOCAL_MOTION_URL}')`. Do NOT use unpkg/jsdelivr/skypack/CDN script URLs.")
                appendLine("- widget_code ROLE for PPT: it is JUST a tiny static cover thumbnail SVG shown inline before the user expands. Keep it under ~600 chars: deck title + 1 short subtitle + page count badge.")
                appendLine("- For long-running PPT/deck work, keep the visible stream to short progress text and/or the tiny widget_code cover/status. Emit the full_html show-widget only after the HTML is complete; never emit a partial or truncated spec.html.")
                appendLine("- MANDATORY: never render a multi-page deck as an SVG/HTML grid in widget_code. The full live PPT/deck must be in spec.html so AmberAgent shows a fullscreen touch presentation preview. Do NOT generate or save an AmberAgent MiniApp for PPT requests.")
                appendLine("- Mobile slide density: each slide should fit a phone screen after expansion — one main claim, optional subtitle, 2-4 concise bullets/cards, no long paragraphs or dense tables.")
                appendLine("- When the user asks to PREVIEW / OPEN / BROWSE / 打开 / 预览 / 给我看 / 发出来预览 a PPT/HTML deck previously saved to /workspace, do NOT call share_file. Read it and emit one complete full_html show-widget if it fits; otherwise say the preview is too large instead of emitting partial HTML.")
                appendLine("- share_file is only for genuine sharing/exporting/forwarding; previews always stay inside the chat as widgets.")
                appendLine("- full_html spec.html may be much larger than ordinary widget_code, but it must still be complete in one response: avoid base64 images, huge static datasets, and repeated templates.")
                appendLine("- Do not use script tags or inline event handlers in ordinary widget_code; JavaScript will be stripped there. The script-capable PPT path is renderer \"${GenerativeUiProtocol.FULL_HTML_RENDERER}\" with HTML in spec.html.")
                appendLine("- Do not make decorative widgets that merely repeat the prose answer.")
                append(modelGuidance(model))
            }
        }

    fun buildRetry(requirement: GenerativeUiWidgetRequirement, previousIssue: String?): String {
        val issue = previousIssue?.takeIf { it.isNotBlank() } ?: "missing required show-widget"
        if (requirement.expectFullHtmlDeck) {
            return """
                **Visible Full HTML Deck Repair**
                The previous output did not produce a valid full_html deck: $issue
                Reply in visible content immediately with exactly one fenced `show-widget` JSON block, but do not begin the block until the full JSON can be completed in this response. Do not output a MiniApp, standalone webpage, generic HTML app, Markdown-only answer, or hidden-only reasoning.
                `renderer` must be `${GenerativeUiProtocol.FULL_HTML_RENDERER}`; `widget_code` is only a static SVG cover; `spec.html` is the full live deck HTML.
                `spec.html` must contain `<div id="deck">` and one or more `<section class="slide ...">` pages.
                Scripts may only use ${GenerativeUiProtocol.LOCAL_MOTION_URL} and ${GenerativeUiProtocol.LOCAL_LUCIDE_URL}. Do not use CDN script URLs.
                Preserve the requested content and style; keep the JSON valid, complete, and compact enough to fit. Never emit partial spec.html.
            """.trimIndent()
        }
        if (requirement.expectSlides) {
            return """
                **Visible Slide Deck Repair**
                The previous output did not produce a valid deck card: $issue
                Reply in visible content immediately with exactly one fenced `show-widget` JSON block. Use renderer `${GenerativeUiProtocol.FULL_HTML_RENDERER}` and put a complete mobile-readable HTML deck in `spec.html` with `<div id="deck">` and `<section class="slide ...">` pages.
                Do not output a MiniApp, standalone webpage, Markdown-only answer, hidden-only reasoning, or partial spec.html.
            """.trimIndent()
        }
        return """
            **Visible Generative UI Retry**
            The previous stream did not produce a visible widget: $issue
            Reply in visible content immediately with one valid fenced `show-widget` JSON block containing a small, static, self-contained `widget_code` SVG tailored to the user's request, then at most one short sentence.
            Do not use renderer/spec in this retry. Do not put widget JSON or SVG inside hidden reasoning. Do not output Markdown-only prose.
        """.trimIndent()
    }

    private fun modelGuidance(model: Model?): String {
        val name = listOfNotNull(model?.modelId, model?.displayName).joinToString(" ").lowercase()
        if (name.isBlank()) return ""
        return buildString {
            appendLine()
            appendLine("Model-specific widget guidance:")
            when {
                "deepseek" in name -> {
                    appendLine("- DeepSeek: keep hidden reasoning extremely brief for visual requests; do not draft coordinates, SVG, JSON, or layout prose in reasoning.")
                    appendLine("- DeepSeek: start visible content within one short sentence. Stream short progress or a tiny widget_code cover/status first; emit full_html only when the payload is complete.")
                    appendLine("- DeepSeek: never put widget_code, SVG tags, or JSON objects inside <think> blocks.")
                }
                "kimi" in name || "moonshot" in name -> {
                    appendLine("- Kimi/Moonshot: do not use function/tool calls to generate SVG. Do not place SVG in tool arguments.")
                    appendLine("- Kimi/Moonshot: output a visible show-widget fence directly; avoid raw SVG or JavaScript code blocks.")
                    appendLine("- Kimi/Moonshot: NEVER call eval_javascript, code_interpreter, or any code execution tool to produce widget content.")
                }
                "minimax" in name || "mini-max" in name || "abab" in name || Regex("""\bm\d+(?:\.\d+)?\b""").containsMatchIn(name) -> {
                    appendLine("- MiniMax: prioritize layout safety over detail density. Keep all boxes, dashed groups, arrows, and text inside one 680-wide viewBox.")
                    appendLine("- MiniMax: verify every rect has x + width <= 656; stack vertically instead of squeezing content horizontally.")
                }
                "claude" in name || "anthropic" in name -> {
                    appendLine("- Claude: use one polished, self-contained SVG widget; native actions are welcome when they help the user iterate.")
                    appendLine("- Claude: avoid plan/tool detours and long preambles before the fence; put design quality into the visible SVG itself.")
                }
                "gemini" in name || "google" in name -> appendLine("- Gemini: keep widget JSON simple and valid; prefer one widget_code SVG and do not nest its fence.")
                "qwen" in name || "dashscope" in name || "aliyun" in name -> appendLine("- Qwen: use only the show-widget fence; do not emit a separate SVG code block.")
                "gpt" in name || "openai" in name || Regex("""\bo\d+""").containsMatchIn(name) -> appendLine("- OpenAI: emit visible widget_code directly; do not merely describe the widget or nest its fence.")
                else -> appendLine("- This model: prefer visible widget_code SVG directly; avoid hidden drafting and tool calls for static visuals.")
            }
        }
    }
}
