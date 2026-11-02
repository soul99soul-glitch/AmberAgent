package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.DefaultScreenOcrEngine
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.automation.ScreenOcrBlock
import app.amber.core.automation.ScreenOcrEngine
import app.amber.core.automation.ScreenOcrMode
import app.amber.core.automation.ScreenOcrObservation

/** Approved single actions, legacy reads and screenshot tools; no operation state. */
internal class ScreenPrimitiveTools(
    private val context: Context,
    private val screenCaptureManager: ScreenCaptureManager,
    private val operations: ScreenOperationOwner,
    private val ocrEngine: ScreenOcrEngine = DefaultScreenOcrEngine(context, screenCaptureManager),
) {
    val screenClickTool = Tool(
        name = "screen_click",
        description = "Tap screen coordinates using AmberAgent Accessibility after user approval. " +
            "Optional verify_text polls the UI tree post-action (with automatic on-device OCR fallback) " +
            "so the tap effect is confirmed, not just injected.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", screenNumberProp("X coordinate."))
                    put("y", screenNumberProp("Y coordinate."))
                    put("verify_text", screenStringProp("Optional text expected to appear after the tap; result reports verify_text_found."))
                    put("verify_timeout_ms", screenIntegerProp("Post-action verify poll timeout. Defaults to 2000."))
                    put("ocr_mode", screenStringProp("Optional OCR mode for verify_text fallback: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                    put("expectation", screenExpectationProp())
                },
                required = listOf("x", "y")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_click", "点击屏幕", input) {
                val service = requireScreenAccessibility()
                val point = requireDisplayPoint(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
                    label = "screen_click"
                )
                val targetVisibleBefore = wasScreenAbsenceTargetVisible(service, input)
                val ok = service.tap(point.x, point.y)
                verifiedScreenActionResult(service, input, ok, targetVisibleBefore, ocrEngine)
            }
        }
    )

    val screenLongClickTool = Tool(
        name = "screen_long_click",
        description = "Long-press screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", screenNumberProp("X coordinate."))
                    put("y", screenNumberProp("Y coordinate."))
                    put("duration_ms", screenIntegerProp("Press duration in milliseconds. Defaults to 600."))
                    put("expectation", screenExpectationProp())
                },
                required = listOf("x", "y")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_long_click", "长按屏幕", input) {
                val service = requireScreenAccessibility()
                val point = requireDisplayPoint(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
                    label = "screen_long_click"
                )
                val targetVisibleBefore = wasScreenAbsenceTargetVisible(service, input)
                val ok = service.longPress(
                    x = point.x,
                    y = point.y,
                    durationMillis = input.long("duration_ms") ?: 600L
                )
                verifiedScreenActionResult(service, input, ok, targetVisibleBefore)
            }
        }
    )

    val screenSwipeTool = Tool(
        name = "screen_swipe",
        description = "Swipe between screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("from_x", screenNumberProp("Start X coordinate."))
                    put("from_y", screenNumberProp("Start Y coordinate."))
                    put("to_x", screenNumberProp("End X coordinate."))
                    put("to_y", screenNumberProp("End Y coordinate."))
                    put("duration_ms", screenIntegerProp("Swipe duration in milliseconds. Defaults to 350."))
                    put("verify_text", screenStringProp("Optional text expected to appear after the swipe; result reports verify_text_found."))
                    put("verify_timeout_ms", screenIntegerProp("Post-action verify poll timeout. Defaults to 2000."))
                    put("ocr_mode", screenStringProp("Optional OCR mode for verify_text fallback: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("from_x", "from_y", "to_x", "to_y")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_swipe", "滑动屏幕", input) {
                val service = requireScreenAccessibility()
                val from = requireDisplayPoint(
                    x = input.requiredFloat("from_x"),
                    y = input.requiredFloat("from_y"),
                    label = "screen_swipe.from"
                )
                val to = requireDisplayPoint(
                    x = input.requiredFloat("to_x"),
                    y = input.requiredFloat("to_y"),
                    label = "screen_swipe.to"
                )
                val ok = service.swipe(
                    fromX = from.x,
                    fromY = from.y,
                    toX = to.x,
                    toY = to.y,
                    durationMillis = input.long("duration_ms") ?: 350L
                )
                verifiedScreenActionResult(service, input, ok, ocrEngine = ocrEngine)
            }
        }
    )

    val screenInputTextTool = Tool(
        name = "screen_input_text",
        description = "Set text on the currently focused editable field using Accessibility.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", screenStringProp("Text to enter."))
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_input_text", "输入文字", input) {
                val service = requireScreenAccessibility()
                screenActionResult(service.setFocusedText(input.requiredString("text")))
            }
        }
    )

    val screenBackTool = Tool(
        name = "screen_back",
        description = "Press Android Back using Accessibility.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            operations.trackScreenTool("screen_back", "返回", input) {
                screenActionResult(requireScreenAccessibility().back())
            }
        }
    )

    val screenHomeTool = Tool(
        name = "screen_home",
        description = "Press Android Home using Accessibility.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            operations.trackScreenTool("screen_home", "回到桌面", input) {
                screenActionResult(requireScreenAccessibility().home())
            }
        }
    )

    val screenOpenAppTool = Tool(
        name = "screen_open_app",
        description = "Open an installed Android app by package name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package_name", screenStringProp("Android package name to launch."))
                },
                required = listOf("package_name")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_open_app", "打开应用", input) {
                val packageName = input.requiredString("package_name")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: error("No launch intent for package: $packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                screenActionResult(true)
            }
        }
    )

    val screenOpenUrlTool = Tool(
        name = "screen_open_url",
        description = "Open an http/https URL with Android ACTION_VIEW. Prefer webview_open for in-app visual preview; use this when the user wants the system browser/app.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", screenStringProp("http or https URL to open."))
                },
                required = listOf("url")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_open_url", "打开 URL", input, runtime = "Android Intent") {
                val url = input.requiredString("url")
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "Only http and https URLs are allowed"
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                screenActionResult(true)
            }
        }
    )

    val screenReadUiTool = Tool(
        name = "screen_read_ui",
        description = "Read the current Accessibility UI tree for screen reasoning (with automatic compiled on-device OCR fallback for low-coverage/WebView/Canvas screens). OCR uses bundled ML Kit text recognition for Chinese (Simplified/Traditional) and Latin/English; `ocr_source`/`ocr_outcome` in the result distinguish real bitmap OCR from Accessibility-only metadata. For a multi-step phone goal, first discover screen_run_goal: when enabled it uses Jev inside Accessibility to select and execute validated actions, reducing model round trips. Use this raw tree tool for inspection or when that tool hands back.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("max_nodes", screenIntegerProp("Maximum nodes to include. Defaults to 120."))
                    put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default, tree-first with low-coverage fallback), 'on' (force OCR), or 'off' (disable OCR)."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                }
            )
        },
        execute = { input ->
            operations.trackScreenTool("screen_read_ui", "读取 UI 树", input) {
                val service = requireScreenAccessibility()
                val maxNodes = input.int("max_nodes") ?: 120
                val uiTree = service.dumpUiTree(maxNodes)
                val ocrMode = input.resolveOcrMode()
                val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
                val ocrObservation = ocrEngine.observe(
                    snapshot = snapshot,
                    mode = ocrMode,
                    forceReason = if (ocrMode == ScreenOcrMode.AUTO && uiTree.isBlank()) "empty_ui_tree_dump" else null,
                )
                textJson {
                    put("ui_tree", uiTree)
                    appendOcrTelemetry(ocrObservation)
                }
            }
        }
    )

    val screenFindTextTool = Tool(
        name = "screen_find_text",
        description = "Find text in the current Accessibility UI tree (with automatic on-device OCR fallback when the tree misses) and return matching node bounds.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", screenStringProp("Text to find."))
                    put("max_nodes", screenIntegerProp("Maximum nodes to scan. Defaults to 160."))
                    put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        execute = { input ->
            operations.trackScreenTool("screen_find_text", "查找屏幕文字", input) {
                val service = requireScreenAccessibility()
                val query = input.requiredString("text")
                val ocrMode = input.resolveOcrMode()
                val treeMatches = if (ocrMode == ScreenOcrMode.ON) {
                    service.findTextNodes(query = query, maxNodes = input.int("max_nodes") ?: 160)
                } else {
                    service.findTextNodes(query = query, maxNodes = input.int("max_nodes") ?: 160)
                }
                val ocrObservation = if (ocrMode == ScreenOcrMode.ON || (ocrMode == ScreenOcrMode.AUTO && treeMatches.isEmpty())) {
                    val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
                    ocrEngine.observe(
                        snapshot = snapshot,
                        mode = ocrMode,
                        forceReason = if (ocrMode == ScreenOcrMode.AUTO && treeMatches.isEmpty()) "query_not_in_tree" else null,
                    )
                } else {
                    null
                }
                val ocrMatches = ocrObservation?.findMatches(query).orEmpty()
                textJson {
                    put("matches", buildJsonArray {
                        treeMatches.forEachIndexed { index, match ->
                            add(match.toJson(index))
                        }
                        ocrMatches.forEachIndexed { offset, block ->
                            add(block.toMatchJson(treeMatches.size + offset))
                        }
                    })
                    put("tree_match_count", treeMatches.size)
                    put("ocr_match_count", ocrMatches.size)
                    put("ocr_mode", ocrMode.wireName())
                    put("ocr_triggered", ocrObservation?.triggered == true)
                    ocrObservation?.let { obs ->
                        put("ocr_reason", obs.triggerReason)
                        put("ocr_block_count", obs.blocks.size)
                    }
                }
            }
        }
    )

    val screenTapTextTool = Tool(
        name = "screen_tap_text",
        description = "Tap the first Accessibility node (or on-device OCR text block fallback) containing the requested text.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", screenStringProp("Text to tap."))
                    put("max_nodes", screenIntegerProp("Maximum nodes to scan. Defaults to 160."))
                    put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_tap_text", "点击文字", input) {
                val service = requireScreenAccessibility()
                val query = input.requiredString("text")
                val ocrMode = input.resolveOcrMode()
                val treeMatch = service.findTextNodes(
                    query = query,
                    maxNodes = input.int("max_nodes") ?: 160,
                ).firstOrNull()
                if (treeMatch != null && ocrMode != ScreenOcrMode.ON) {
                    val ok = service.tap(treeMatch.bounds.exactCenterX(), treeMatch.bounds.exactCenterY())
                    textJson {
                        put("success", ok)
                        put("source", "accessibility_tree")
                        put("ocr_mode", ocrMode.wireName())
                        put("ocr_triggered", false)
                        put("match", treeMatch.toJson(0))
                    }
                } else {
                    val ocrObservation = if (ocrMode != ScreenOcrMode.OFF) {
                        val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
                        ocrEngine.observe(
                            snapshot = snapshot,
                            mode = ocrMode,
                            forceReason = if (ocrMode == ScreenOcrMode.AUTO) "tap_text_tree_miss" else null,
                        )
                    } else {
                        null
                    }
                    val ocrMatch = ocrObservation?.findMatches(query)?.firstOrNull()
                    when {
                        treeMatch != null -> {
                            val ok = service.tap(treeMatch.bounds.exactCenterX(), treeMatch.bounds.exactCenterY())
                            textJson {
                                put("success", ok)
                                put("source", "accessibility_tree")
                                put("ocr_mode", ocrMode.wireName())
                                put("ocr_triggered", ocrObservation?.triggered == true)
                                ocrObservation?.let { put("ocr_reason", it.triggerReason) }
                                put("match", treeMatch.toJson(0))
                            }
                        }
                        ocrMatch != null -> {
                            val point = requireDisplayPoint(
                                x = ocrMatch.centerX,
                                y = ocrMatch.centerY,
                                label = "screen_tap_text.ocr",
                            )
                            val ok = service.tap(point.x, point.y)
                            textJson {
                                put("success", ok)
                                put("source", "ocr_fallback")
                                put("ocr_mode", ocrMode.wireName())
                                put("ocr_triggered", true)
                                ocrObservation?.let { put("ocr_reason", it.triggerReason) }
                                put("match", ocrMatch.toMatchJson(0))
                            }
                        }
                        else -> error("Text not found on screen: $query")
                    }
                }
            }
        }
    )

    val screenWaitForTextTool = Tool(
        name = "screen_wait_for_text",
        description = "Wait until text appears in the Accessibility UI tree (with automatic on-device OCR fallback).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", screenStringProp("Text to wait for."))
                    put("timeout_ms", screenIntegerProp("Timeout in milliseconds. Defaults to 10000."))
                    put("interval_ms", screenIntegerProp("Polling interval in milliseconds. Defaults to 500."))
                    put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        execute = { input ->
            operations.trackScreenTool("screen_wait_for_text", "等待屏幕文字", input) {
                val query = input.requiredString("text")
                val ocrMode = input.resolveOcrMode()
                val deadline = System.currentTimeMillis() + (input.long("timeout_ms") ?: 10_000L).coerceIn(500L, 60_000L)
                val interval = (input.long("interval_ms") ?: 500L).coerceIn(100L, 3_000L)
                val service = requireScreenAccessibility()
                var matches = emptyList<app.amber.core.automation.AccessibilityTextMatch>()
                var ocrMatches = emptyList<ScreenOcrBlock>()
                var lastOcrObservation: ScreenOcrObservation? = null
                while (System.currentTimeMillis() < deadline) {
                    matches = service.findTextNodes(query, maxNodes = 200)
                    if (matches.isNotEmpty() && ocrMode != ScreenOcrMode.ON) break
                    if (ocrMode != ScreenOcrMode.OFF) {
                        val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
                        val obs = ocrEngine.observe(
                            snapshot = snapshot,
                            mode = ocrMode,
                            forceReason = if (ocrMode == ScreenOcrMode.AUTO && matches.isEmpty()) "wait_for_text_tree_miss" else null,
                        )
                        lastOcrObservation = obs
                        ocrMatches = obs.findMatches(query)
                        if (matches.isNotEmpty() || ocrMatches.isNotEmpty()) break
                    }
                    delay(interval)
                }
                val found = matches.isNotEmpty() || ocrMatches.isNotEmpty()
                textJson {
                    put("found", found)
                    put("ocr_mode", ocrMode.wireName())
                    put("ocr_triggered", lastOcrObservation?.triggered == true)
                    lastOcrObservation?.let { put("ocr_reason", it.triggerReason) }
                    put("matches", buildJsonArray {
                        matches.take(5).forEachIndexed { index, match -> add(match.toJson(index)) }
                        if (matches.size < 5) {
                            ocrMatches.take(5 - matches.size).forEachIndexed { offset, block ->
                                add(block.toMatchJson(matches.size + offset))
                            }
                        }
                    })
                }
            }
        }
    )

    val screenScrollUntilTool = Tool(
        name = "screen_scroll_until",
        description = "Swipe repeatedly until target text appears in the Accessibility UI tree (with automatic on-device OCR fallback).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", screenStringProp("Text to find."))
                    put("direction", screenStringProp("Swipe direction: down, up, left, or right. Defaults to down."))
                    put("max_swipes", screenIntegerProp("Maximum swipes. Defaults to 8."))
                    put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool("screen_scroll_until", "滚动查找文字", input) {
                val service = requireScreenAccessibility()
                val maxSwipes = (input.int("max_swipes") ?: 8).coerceIn(1, 20)
                val direction = input.string("direction") ?: "down"
                val query = input.requiredString("text")
                val ocrMode = input.resolveOcrMode()
                var matches = service.findTextNodes(query, maxNodes = 200)
                var ocrMatches = emptyList<ScreenOcrBlock>()
                var lastOcrObservation: ScreenOcrObservation? = null
                if (matches.isEmpty() && ocrMode != ScreenOcrMode.OFF) {
                    val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
                    val obs = ocrEngine.observe(
                        snapshot = snapshot,
                        mode = ocrMode,
                        forceReason = if (ocrMode == ScreenOcrMode.AUTO) "scroll_until_initial_tree_miss" else null,
                    )
                    lastOcrObservation = obs
                    ocrMatches = obs.findMatches(query)
                }
                var swipes = 0
                var stalled = false
                var lastTree = service.dumpUiTree(maxNodes = 200)
                while (matches.isEmpty() && ocrMatches.isEmpty() && swipes < maxSwipes) {
                    swipeByDirection(service, direction)
                    swipes++
                    delay(450)
                    val tree = service.dumpUiTree(maxNodes = 200)
                    matches = service.findTextNodes(query, maxNodes = 200)
                    if (matches.isEmpty() && ocrMode != ScreenOcrMode.OFF) {
                        val snapshot = runCatching { service.captureScreenSnapshot() }.getOrNull()
                        val obs = ocrEngine.observe(
                            snapshot = snapshot,
                            mode = ocrMode,
                            forceReason = if (ocrMode == ScreenOcrMode.AUTO) "scroll_until_tree_miss" else null,
                        )
                        lastOcrObservation = obs
                        ocrMatches = obs.findMatches(query)
                    }
                    if (tree == lastTree && matches.isEmpty() && ocrMatches.isEmpty()) {
                        // 滑到底/无位移：树完全没变，不再空滑到上限。
                        stalled = true
                        break
                    }
                    lastTree = tree
                }
                val found = matches.isNotEmpty() || ocrMatches.isNotEmpty()
                textJson {
                    put("found", found)
                    put("swipes", swipes)
                    if (stalled) put("stalled", true)
                    put("ocr_mode", ocrMode.wireName())
                    put("ocr_triggered", lastOcrObservation?.triggered == true)
                    lastOcrObservation?.let { put("ocr_reason", it.triggerReason) }
                    put("matches", buildJsonArray {
                        matches.take(5).forEachIndexed { index, match -> add(match.toJson(index)) }
                        if (matches.size < 5) {
                            ocrMatches.take(5 - matches.size).forEachIndexed { offset, block ->
                                add(block.toMatchJson(matches.size + offset))
                            }
                        }
                    })
                }
            }
        }
    )

    /**
     * 固定语义流程：每步目标与验收条件都已确定、不需要判断服务介入的场景
     * （导航、搜索、填写草稿）。逐步从新观察重新定位，不保存 ref 或坐标；
     * 任何歧义、页面偏离或验收不满足都交回主模型，不预猜后续。
     */
    val screenScreenshotTool = Tool(
        name = "screen_screenshot",
        description = "Capture one screen frame for VLM reasoning using Android MediaProjection after user approval.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            operations.trackScreenTool("screen_screenshot", "截取屏幕", input, runtime = "MediaProjection") {
                val result = screenCaptureManager.capture()
                textJson {
                    put("status", "captured")
                    put("path", result.file.absolutePath)
                    put("width", result.width)
                    put("height", result.height)
                    put("size_bytes", result.sizeBytes)
                    put("created_at", result.createdAt.toString())
                }
            }
        }
    )

    val vlmTaskTool = Tool(
        name = "vlm_task",
        description = "Plan a phone automation task with VLM-style trace semantics. Stage 0 records the " +
            "intent only — it performs no actions; execute via screen_read_ui plus low-level screen tools.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("goal", screenStringProp("Natural language phone automation goal."))
                },
                required = listOf("goal")
            )
        },
        needsApproval = true,
        execute = { input ->
            operations.trackScreenTool("vlm_task", "规划 VLM 任务", input, runtime = "vlm-contract-stage0") {
                textJson {
                    put("status", "planned")
                    put("goal", input.requiredString("goal"))
                    put("message", "VLM orchestration contract is connected. Use screen_read_ui plus low-level screen actions until remote VLM screenshot reasoning is wired.")
                }
            }
        }
    )
    private fun requireDisplayPoint(x: Float, y: Float, label: String): ScreenPoint {
        val metrics = context.resources.displayMetrics
        return requirePointInDisplayBounds(
            x = x,
            y = y,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            label = label
        )
    }

    private fun coordinateSchema(xName: String, yName: String) = InputSchema.Obj(
        properties = buildJsonObject {
            put(xName, screenNumberProp("X coordinate."))
            put(yName, screenNumberProp("Y coordinate."))
        },
        required = listOf(xName, yName)
    )
    private suspend fun swipeByDirection(service: AccessibilityController, direction: String): Boolean {
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        val height = context.resources.displayMetrics.heightPixels.toFloat()
        return when (direction) {
            "up" -> service.swipe(width * 0.5f, height * 0.35f, width * 0.5f, height * 0.75f)
            "left" -> service.swipe(width * 0.75f, height * 0.5f, width * 0.25f, height * 0.5f)
            "right" -> service.swipe(width * 0.25f, height * 0.5f, width * 0.75f, height * 0.5f)
            else -> service.swipe(width * 0.5f, height * 0.75f, width * 0.5f, height * 0.35f)
        }
    }

    private fun app.amber.core.automation.AccessibilityTextMatch.toJson(index: Int) = buildJsonObject {
        put("index", index)
        put("text", text)
        put("content_description", contentDescription)
        put("class_name", className)
        put("view_id", viewId.orEmpty())
        put("clickable", clickable)
        put("enabled", enabled)
        put("bounds", buildJsonObject {
            put("left", bounds.left)
            put("top", bounds.top)
            put("right", bounds.right)
            put("bottom", bounds.bottom)
            put("center_x", bounds.exactCenterX().toDouble())
            put("center_y", bounds.exactCenterY().toDouble())
        })
    }

}

internal fun JsonElement.requiredFloat(name: String): Float =
    jsonObject[name]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: error("$name is required")

internal data class ScreenPoint(val x: Float, val y: Float)

internal fun requirePointInDisplayBounds(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    label: String,
): ScreenPoint {
    require(x.isFinite() && y.isFinite()) {
        "$label point out of bounds: coordinates must be finite"
    }
    require(width > 0 && height > 0) {
        "$label point out of bounds: invalid display bounds ${width}x$height"
    }
    require(x >= 0f && x < width && y >= 0f && y < height) {
        "$label point out of bounds: ($x, $y) outside ${width}x$height"
    }
    return ScreenPoint(x, y)
}
