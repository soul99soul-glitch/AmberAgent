package me.rerere.rikkahub.data.ai.generative

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.GenerativeUiSetting

/**
 * Visual-output routing.
 *
 * The assistant has three rendering paths for "make me a visual" requests:
 *
 *  - **SVG / HTML show-widget** (this Planner's original output) — best for
 *    flowcharts, architecture diagrams, mind maps, schematics, line-art logos,
 *    icons, math / data plots. Structural content where precision and
 *    editability matter.
 *  - **`generate_image` tool call** — best for photographic, painted,
 *    illustrated, textured imagery. Aesthetic content where vector code
 *    can't fake depth / lighting / texture.
 *  - **renderer show-widget for slides** — multi-slide presentations.
 *
 * Layer 1 (strong keywords) deterministically classifies obvious requests
 * either way. Layer 3 (the AMBIGUOUS prompt) gives the model criteria to
 * route when keywords disagree.
 */
enum class VisualRoute {
    /** Strong diagram / chart / schematic signal — emit show-widget SVG. Suppress tools. */
    DIAGRAM_WIDGET,

    /** Strong photographic / artistic signal — call generate_image (if available). Keep tools enabled. */
    IMAGE_GEN,

    /**
     * Visual intent detected but neither side has a clean signal ("画一只猫",
     * "make me a picture of X"). Give the model the routing criteria and let
     * it decide — including asking the user a clarifying question when truly
     * stuck. Keep tools enabled; no widget guard.
     */
    AMBIGUOUS_VISUAL,

    /** Not a visual request — answer in normal markdown. */
    PROSE,
}

object GenerativeUiPlanner {
    fun buildPrompt(
        setting: GenerativeUiSetting,
        messages: List<UIMessage>,
        hasImageGenTool: Boolean = false,
    ): String {
        if (!setting.enabled) return ""
        val latestUserText = latestUserText(messages)
        if (latestUserText.isBlank()) return ""

        val route = classifyRoute(latestUserText, hasImageGenTool)
        val toolMediated = isToolMediatedRequest(latestUserText)
        return buildString {
            appendLine()
            appendLine("**Generative UI Planner**")
            when (route) {
                VisualRoute.DIAGRAM_WIDGET -> {
                    if (toolMediated) {
                        appendLine("The user asked for a visual artifact but also requested tools, skills, subagents, files, or external context.")
                        appendLine("Use the requested tool/skill/subagent first. Do NOT create a widget for routing, progress, plan, or status summaries.")
                        appendLine("Only emit a show-widget after the real tool/skill/subagent result exists and the widget is the final requested artifact.")
                    } else {
                        appendLine("The user asked for a structural diagram / chart / schematic. Emit a concise show-widget SVG block to answer.")
                        appendLine("Start with one short sentence, then output the show-widget block immediately.")
                        appendLine("Prefer widget_code SVG for streaming; avoid renderer/spec unless the answer truly needs an interactive chart or slides.")
                        appendLine("Keep the SVG inside its viewBox with 24px padding; do not draw outside the card.")
                    }
                }

                VisualRoute.IMAGE_GEN -> {
                    if (hasImageGenTool) {
                        appendLine("The user asked for a photographic / painted / illustrated image. Call the `generate_image` tool with a detailed English prompt.")
                        appendLine("Specify subject, style, composition, lighting, mood. DO NOT emit a show-widget SVG — vector code cannot capture the depth and texture the user is asking for.")
                        appendLine("After the tool returns, briefly comment on what was generated; offer to refine if appropriate.")
                    } else {
                        // Image gen wanted but not configured. Tell the user
                        // why instead of silently fall back to a bad SVG.
                        appendLine("The user asked for a photographic / painted image but no image-generation model is configured.")
                        appendLine("Briefly tell them to set one in Settings → 模型 → 生图模型 (or per-assistant), then offer a quick SVG sketch as a temporary alternative if it would still be useful.")
                    }
                }

                VisualRoute.AMBIGUOUS_VISUAL -> {
                    appendLine("The user wants a visual but didn't specify the medium. Pick ONE of:")
                    if (hasImageGenTool) {
                        appendLine("  1. Call `generate_image` for photographic / painted / textured imagery (landscapes, characters, posters, illustrations, concept art).")
                    }
                    appendLine("  2. Emit a show-widget SVG for diagrams, charts, schematics, simple icons, line-art logos.")
                    appendLine("  3. Emit a show-widget renderer for slides if multi-slide presentation is the right form.")
                    appendLine("Use the subject and any style cues in the request to decide. If genuinely 50/50, ask once: \"想要矢量草图（精确、可编辑）还是生成的图像（纹理丰富、写实感）?\"")
                    if (toolMediated) {
                        appendLine("If the user delegated to a tool/skill/subagent in the same message, run that first and only emit a visual when the visual itself is the final artifact.")
                    }
                }

                VisualRoute.PROSE -> {
                    appendLine("Answer in normal Markdown. Do NOT create a widget unless the user explicitly asks for a visual.")
                }
            }
            appendLine("Never put show-widget JSON, SVG, HTML, renderer/spec, or widget code in hidden reasoning/thinking content.")
            appendLine("Avoid decorative widgets, oversized hero layouts, repeated prose inside the graphic, and visuals that duplicate the surrounding answer.")
        }
    }

    /**
     * True when we're going to forcibly retry without tools to get a widget
     * streamed back. Only kicks in for confident diagram intent — for
     * IMAGE_GEN / AMBIGUOUS_VISUAL the model is free to choose and we don't
     * want to penalise it for picking the tool.
     */
    fun needsVisibleStreamingFallback(setting: GenerativeUiSetting, messages: List<UIMessage>): Boolean =
        shouldGenerateDirectWidgetWithoutTools(setting, messages)

    /**
     * True when the request is so clearly a diagram-style direct visual that
     * we should clear the tool catalog and let the model stream the widget
     * inline (current behaviour for "flowchart" / "架构图" requests).
     *
     * For IMAGE_GEN-classified requests we KEEP the tool catalog so the model
     * can call `generate_image`. For AMBIGUOUS_VISUAL we also keep the
     * catalog so the model picks based on the routing prompt above.
     */
    fun shouldGenerateDirectWidgetWithoutTools(setting: GenerativeUiSetting, messages: List<UIMessage>): Boolean {
        if (!setting.enabled) return false
        val text = latestUserText(messages)
        if (classifyRoute(text, hasImageGenTool = false) != VisualRoute.DIAGRAM_WIDGET) return false
        if (isToolMediatedRequest(text)) return false
        val lower = text.lowercase()
        val needsExternalContext = listOf(
            "搜索", "查一下", "联网", "网页", "网址", "http://", "https://", "url",
            "读取", "文件", "workspace", "屏幕", "当前页面", "用工具", "调用工具",
        ).any { it in lower }
        return !needsExternalContext
    }

    private fun latestUserText(messages: List<UIMessage>): String = messages
        .lastOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()

    /**
     * Layered classification:
     *  1. Layer 0 — explicit slash-command escape hatch (the QuickMessage
     *     templates prefix `[ROUTE:image]` / `[ROUTE:diagram]` / `[ROUTE:slides]`).
     *  2. Layer 1 — strong-signal keyword match on either side.
     *  3. Layer 2 — style-modifier regex ("X 风格", "in the style of X").
     *  4. Layer 3 — visual verb detected but no clear medium → AMBIGUOUS_VISUAL.
     *  5. Layer 4 — no visual signal at all → PROSE.
     */
    internal fun classifyRoute(text: String, hasImageGenTool: Boolean): VisualRoute {
        val lower = text.lowercase()

        // Layer 0: explicit slash-command route tags. Trumps everything.
        when {
            "[route:image]" in lower -> return VisualRoute.IMAGE_GEN
            "[route:diagram]" in lower -> return VisualRoute.DIAGRAM_WIDGET
            "[route:slides]" in lower -> return VisualRoute.DIAGRAM_WIDGET  // slides go through widget renderer path
        }

        val imageGenStrong = IMAGE_GEN_STRONG_KEYWORDS.any { it in lower } ||
            IMAGE_GEN_STRONG_REGEX.containsMatchIn(lower) ||
            // Style modifiers: "油画风格 / oil painting style / in the style of …"
            // are reliable image-gen tells when paired with any subject noun.
            STYLE_MODIFIER_REGEX.containsMatchIn(lower)
        val diagramStrong = DIAGRAM_STRONG_KEYWORDS.any { it in lower } ||
            DIAGRAM_STRONG_REGEX.containsMatchIn(lower)

        return when {
            // Both fired → trust the more specific signal. Diagram words are
            // usually surface terms ("流程图"); image-gen words are stylistic
            // ("油画风格"). When both, prefer image-gen ONLY if a style modifier
            // is present (someone wrote "请画一张油画风格的流程图" — they want
            // an art-style depiction, not a clean diagram).
            imageGenStrong && diagramStrong ->
                if (STYLE_MODIFIER_REGEX.containsMatchIn(lower)) VisualRoute.IMAGE_GEN
                else VisualRoute.DIAGRAM_WIDGET

            diagramStrong -> VisualRoute.DIAGRAM_WIDGET
            imageGenStrong -> VisualRoute.IMAGE_GEN

            // Generic visual verb fired with no medium clue → ambiguous.
            // (Old behaviour was to treat these as ENCOURAGE → SVG; the
            // problem was photographic requests like "画一个风景图" silently
            // routed to a bad SVG. Now we let the model decide.)
            GENERIC_VISUAL_KEYWORDS.any { it in lower } -> VisualRoute.AMBIGUOUS_VISUAL
            GENERIC_VISUAL_REGEX.containsMatchIn(lower) -> VisualRoute.AMBIGUOUS_VISUAL

            else -> VisualRoute.PROSE
        }
    }

    /** Compatibility shim — old `classify()` reduces 4 states to 2. Kept for
     *  any external callers that might still poke at it. */
    internal fun classify(text: String): WidgetUse = when (classifyRoute(text, hasImageGenTool = false)) {
        VisualRoute.DIAGRAM_WIDGET -> WidgetUse.ENCOURAGE
        VisualRoute.IMAGE_GEN, VisualRoute.AMBIGUOUS_VISUAL, VisualRoute.PROSE -> WidgetUse.DISCOURAGE
    }

    internal fun isToolMediatedRequest(text: String): Boolean {
        val lower = text.lowercase()
        val explicitDelegation = listOf(
            "@",
            "subagent",
            "agent",
            "skill",
            "技能",
            "插件",
            "工具",
            "调用",
            "委托",
            "delegate",
            "guizang",
            "workspace",
            "文件",
            "读取",
            "搜索",
            "联网",
            "网页",
            "网址",
            "http://",
            "https://",
        )
        return explicitDelegation.any { it in lower }
    }

    // ----------------------------------------------------------------------
    // Keyword tables. Kept private so the boundaries are tweakable in one
    // place when we ship more accurate misclassification examples.
    // ----------------------------------------------------------------------

    /** Words that, alone, mean "this is a diagram / chart / schematic". */
    private val DIAGRAM_STRONG_KEYWORDS = listOf(
        // Chinese — diagrammatic compound nouns
        "流程图", "架构图", "组织图", "时序图", "思维导图", "结构图",
        "类图", "状态图", "ER 图", "er图", "用例图", "活动图", "部署图",
        "树状图", "网络拓扑图", "拓扑图", "依赖图", "调用图", "数据流图",
        "甘特图", "燃尽图", "象限图",
        // English — structural visualization terms
        "flowchart", "flow chart", "sequence diagram", "class diagram",
        "state diagram", "state machine", "mind map", "mindmap",
        "er diagram", "uml", "wireframe", "schematic", "swimlane",
        "org chart", "tree diagram", "dependency graph", "call graph",
        "gantt", "burndown", "topology",
    )
    private val DIAGRAM_STRONG_REGEX = Regex(
        """\b(diagram|flowchart|schematic|wireframe|mindmap)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Words that, alone, mean "this is a photographic / painted / illustrated
     * image". Five buckets to make the table easier to extend:
     *   1. Medium / technique
     *   2. Realism / fidelity descriptors
     *   3. Aesthetic-style words
     *   4. Use-case nouns (poster / wallpaper / cover)
     *   5. Subject + visual-form compounds (风景图 / 风景画 / landscape)
     */
    private val IMAGE_GEN_STRONG_KEYWORDS = listOf(
        // 1. Medium / technique
        "照片", "摄影", "油画", "水彩", "水墨", "丙烯", "素描", "速写",
        "工笔", "国画", "壁画", "版画", "蜡笔",
        "photograph", "photo of", "oil painting", "watercolor", "watercolour",
        "ink painting", "acrylic", "sketch of", "render of", "3d render",
        // 2. Realism / fidelity
        "写实", "写实风", "超写实", "高清照片", "真实感",
        "photorealistic", "photoreal", "hyperrealistic", "lifelike",
        // 3. Aesthetic style
        "动漫风", "动漫风格", "二次元", "二次元风格", "卡通", "卡通风格",
        "赛博朋克", "蒸汽朋克", "国风", "古风", "未来主义",
        "anime style", "manga style", "ghibli", "cyberpunk", "steampunk",
        "pixar", "disney style", "fantasy art",
        // 4. Use-case nouns
        "海报", "封面", "壁纸", "桌面", "概念图", "概念稿", "原画", "插画",
        "插图", "插图配图", "配图", "宣传图", "宣传画",
        "poster", "cover art", "wallpaper", "concept art", "concept design",
        "illustration", "key visual", "splash art",
        // 5. Subject + visual-form compounds
        "风景图", "风景画", "肖像", "肖像画", "写真", "人像", "人物图",
        "landscape painting", "scenery", "portrait", "still life",
    )
    private val IMAGE_GEN_STRONG_REGEX = Regex(
        """\b(photo(?:s|graphs?|graphic)?\s+of|painting\s+of|render(?:ing)?\s+of|portrait\s+of|illustration\s+of)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Style modifier pattern — "X 风格 / X style / in the style of X / X-style".
     * Matching this is a strong tell that the user wants a stylised image,
     * even if the subject word is otherwise diagrammatic. Anchored loosely so
     * "in the style of Studio Ghibli" matches without requiring the literal
     * word "image".
     */
    private val STYLE_MODIFIER_REGEX = Regex(
        """(?:\S{1,16}风格|in the style of\s+\S+|\bX-style\b|风格的)""",
        RegexOption.IGNORE_CASE,
    )

    /** Generic visual verbs that don't pin down medium. Match these last. */
    private val GENERIC_VISUAL_KEYWORDS = listOf(
        "画一下", "画一个", "画个", "画出", "画一张", "画一幅",
        "可视化", "做一张图", "做张图", "生成一张图", "生成张图",
        "幻灯片", "ppt", "presentation", "slides", "slide deck", "slide",
        "简报", "汇报", "演示",
    )
    private val GENERIC_VISUAL_REGEX = Regex(
        """\b(draw|sketch|chart|graph|visualize|visualise|plot)\b""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Legacy 2-state enum, kept for the internal compatibility shim only. New
 * code should use [VisualRoute].
 */
enum class WidgetUse {
    ENCOURAGE,
    DISCOURAGE,
}
