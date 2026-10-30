package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.DefaultScreenOcrEngine
import app.amber.core.automation.getActiveAccessibilityController
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.PhoneTakeoverPhase
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.automation.ScreenOcrBlock
import app.amber.core.automation.ScreenOcrEngine
import app.amber.core.automation.ScreenOcrMode
import app.amber.core.automation.ScreenOcrObservation
import app.amber.core.automation.SensitiveScreenTextClassifier
import app.amber.core.jev.JevScreenGoalRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

class ScreenAutomationTools(
    private val context: Context,
    private val screenCaptureManager: ScreenCaptureManager,
    private val activityStore: AgentToolActivityStore,
    private val jevScreenGoalRunner: JevScreenGoalRunner? = null,
    private val ocrEngine: ScreenOcrEngine = DefaultScreenOcrEngine(context, screenCaptureManager),
    private val takeoverController: PhoneAutomationTakeoverController? = null,
) {
    private val screenOwner = Mutex()

    fun getTools(runId: String? = null): List<Tool> = listOf(
        screenGoalTool(runId),
        screenClickTool,
        screenLongClickTool,
        screenSwipeTool,
        screenInputTextTool,
        screenBackTool,
        screenHomeTool,
        screenOpenAppTool,
        screenOpenUrlTool,
        screenReadUiTool,
        screenFindTextTool,
        screenTapTextTool,
        screenWaitForTextTool,
        screenScrollUntilTool,
        screenScreenshotTool,
        vlmTaskTool,
    )

    private fun screenGoalTool(runId: String?) = Tool(
        name = "screen_run_goal",
        description = "Use Jev to accelerate Android Accessibility: open the specified app, then execute a bounded goal internally with fresh UI-node validation. Prefer this over repeated screen_read_ui/click/swipe for read-only navigation, locating content, scrolling, or filling supplied search text. Jev is a decision service, not JavaScript. Requires Jev screen automation and screen-content consent. Returns engine, jev_decisions, actions_dispatched, steps and visible_text; report those facts, never infer Jev usage from tool names. No sending, purchases, account changes or permission grants. On handback use the returned screen evidence and low-level tools. Each call requires approval.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("package_name", stringProp("Android package to open and stay within, e.g. tv.danmaku.bili."))
                put("goal", stringProp("One concrete screen goal with a visible completion condition; at most 1000 characters."))
                put("texts", buildJsonObject {
                    put("type", "array")
                    put("items", stringProp("Exact permitted search/draft text, at most 300 characters."))
                    put("description", "Up to 8 text candidates. Without these, typing is unavailable.")
                })
                put("max_steps", integerProp("Jev decision limit, default 6, hard maximum 8."))
                put("max_duration_ms", integerProp("Deadline, default 15000ms, hard maximum 30000ms."))
                put("dry_run", buildJsonObject { put("type", "boolean"); put("description", "Only inspect the currently open target app and judge; do not launch or execute actions.") })
                put("return_to_host", buildJsonObject { put("type", "boolean"); put("description", "Optional override for automatically returning to Amber when the goal completes.") })
                put("ocr_mode", stringProp("On-device OCR mode: 'auto' (default, zero-overhead tree-first with low-coverage fallback), 'on' (force OCR), or 'off' (disable OCR)."))
                put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
            }, required = listOf("package_name", "goal"))
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            trackScreenTool("screen_run_goal", "Jev 无障碍目标执行", input, runtime = "Jev + Android Accessibility") {
                val runner = jevScreenGoalRunner ?: error("Jev screen runner unavailable")
                val ownerRun = runId?.takeIf { it.isNotBlank() } ?: error("screen_run_goal requires a host-owned run")
                val packageName = input.requiredString("package_name")
                val goal = input.requiredString("goal")
                val texts = (input.jsonObject["texts"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                require(goal.isNotBlank() && goal.length <= 1000 && texts.size <= 8 && texts.all { it.length <= 300 })
                val dryRun = input.string("dry_run") == "true"
                val returnToHostOverride = input.booleanOrNull("return_to_host")
                val ocrMode = input.resolveOcrMode()
                val service = requireService()
                // The approval UI can have brought Amber to the front. Restore only the explicitly named app.
                val alreadyOpen = if (!dryRun && runner.canExecute()) withContext(Dispatchers.Main.immediate) {
                    service.captureScreenSnapshot()?.packageName == packageName
                } else false
                if (!dryRun && runner.canExecute() && !alreadyOpen) {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: error("No launch intent for package: $packageName")
                    withContext(Dispatchers.Main.immediate) {
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    delay(600)
                }
                val outcome = runner.run(
                    controller = service,
                    packageName = packageName,
                    goal = goal,
                    texts = texts,
                    runKey = ownerRun,
                    maxSteps = input.int("max_steps") ?: 6,
                    maxDurationMs = input.long("max_duration_ms") ?: 15_000,
                    dryRun = dryRun,
                    hostPackageName = context.packageName,
                    autoReturnToHost = returnToHostOverride,
                    returnToHostLauncher = { hostPkg -> launchHostApp(service, hostPkg) },
                    ocrMode = ocrMode,
                )
                listOf(UIMessagePart.Text(outcome.toString()))
            }
        },
    )

    private val screenClickTool = Tool(
        name = "screen_click",
        description = "Tap screen coordinates using AmberAgent Accessibility after user approval. " +
            "Optional verify_text polls the UI tree post-action (with automatic on-device OCR fallback) " +
            "so the tap effect is confirmed, not just injected.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", numberProp("X coordinate."))
                    put("y", numberProp("Y coordinate."))
                    put("verify_text", stringProp("Optional text expected to appear after the tap; result reports verify_text_found."))
                    put("verify_timeout_ms", integerProp("Post-action verify poll timeout. Defaults to 2000."))
                    put("ocr_mode", stringProp("Optional OCR mode for verify_text fallback: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("x", "y")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_click", "点击屏幕", input) {
                val service = requireService()
                val point = requireDisplayPoint(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
                    label = "screen_click"
                )
                val ok = service.tap(point.x, point.y)
                verifiedResult(service, input, ok)
            }
        }
    )

    private val screenLongClickTool = Tool(
        name = "screen_long_click",
        description = "Long-press screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", numberProp("X coordinate."))
                    put("y", numberProp("Y coordinate."))
                    put("duration_ms", integerProp("Press duration in milliseconds. Defaults to 600."))
                },
                required = listOf("x", "y")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_long_click", "长按屏幕", input) {
                val service = requireService()
                val point = requireDisplayPoint(
                    x = input.requiredFloat("x"),
                    y = input.requiredFloat("y"),
                    label = "screen_long_click"
                )
                val ok = service.longPress(
                    x = point.x,
                    y = point.y,
                    durationMillis = input.long("duration_ms") ?: 600L
                )
                result(ok)
            }
        }
    )

    private val screenSwipeTool = Tool(
        name = "screen_swipe",
        description = "Swipe between screen coordinates using AmberAgent Accessibility after user approval.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("from_x", numberProp("Start X coordinate."))
                    put("from_y", numberProp("Start Y coordinate."))
                    put("to_x", numberProp("End X coordinate."))
                    put("to_y", numberProp("End Y coordinate."))
                    put("duration_ms", integerProp("Swipe duration in milliseconds. Defaults to 350."))
                    put("verify_text", stringProp("Optional text expected to appear after the swipe; result reports verify_text_found."))
                    put("verify_timeout_ms", integerProp("Post-action verify poll timeout. Defaults to 2000."))
                    put("ocr_mode", stringProp("Optional OCR mode for verify_text fallback: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("from_x", "from_y", "to_x", "to_y")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_swipe", "滑动屏幕", input) {
                val service = requireService()
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
                verifiedResult(service, input, ok)
            }
        }
    )

    private val screenInputTextTool = Tool(
        name = "screen_input_text",
        description = "Set text on the currently focused editable field using Accessibility.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to enter."))
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_input_text", "输入文字", input) {
                val service = requireService()
                result(service.setFocusedText(input.requiredString("text")))
            }
        }
    )

    private val screenBackTool = Tool(
        name = "screen_back",
        description = "Press Android Back using Accessibility.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_back", "返回", input) {
                result(requireService().back())
            }
        }
    )

    private val screenHomeTool = Tool(
        name = "screen_home",
        description = "Press Android Home using Accessibility.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_home", "回到桌面", input) {
                result(requireService().home())
            }
        }
    )

    private val screenOpenAppTool = Tool(
        name = "screen_open_app",
        description = "Open an installed Android app by package name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package_name", stringProp("Android package name to launch."))
                },
                required = listOf("package_name")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_open_app", "打开应用", input) {
                val packageName = input.requiredString("package_name")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: error("No launch intent for package: $packageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result(true)
            }
        }
    )

    private val screenOpenUrlTool = Tool(
        name = "screen_open_url",
        description = "Open an http/https URL with Android ACTION_VIEW. Prefer webview_open for in-app visual preview; use this when the user wants the system browser/app.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", stringProp("http or https URL to open."))
                },
                required = listOf("url")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_open_url", "打开 URL", input, runtime = "Android Intent") {
                val url = input.requiredString("url")
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "Only http and https URLs are allowed"
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result(true)
            }
        }
    )

    private val screenReadUiTool = Tool(
        name = "screen_read_ui",
        description = "Read the current Accessibility UI tree for screen reasoning. Defaults to pure Accessibility tree first (zero screenshot/OCR overhead), and automatically triggers on-device OCR with normalized [0,1] coordinates when visible text coverage is low (WebView, Canvas, custom-drawn views). Supports explicit ocr_mode ('auto'|'on'|'off') or include_ocr.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("max_nodes", integerProp("Maximum nodes to include. Defaults to 120."))
                    put("ocr_mode", stringProp("On-device OCR mode: 'auto' (default, triggers only on low tree coverage), 'on' (always run OCR), or 'off' (disable OCR)."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                }
            )
        },
        execute = { input ->
            trackScreenTool("screen_read_ui", "读取 UI 树", input) {
                val service = requireService()
                val maxNodes = input.int("max_nodes") ?: 120
                val uiTree = service.dumpUiTree(maxNodes)
                val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                if (snapshot != null && takeoverController != null) {
                    if (takeoverController.state.value.phase == PhoneTakeoverPhase.RESUMING) {
                        takeoverController.confirmResumedWithFreshSnapshot(snapshot)
                    } else if (takeoverController.state.value.isActive) {
                        takeoverController.recordObservedSnapshot(
                            snapshotId = snapshot.id,
                            stepIndex = takeoverController.state.value.currentStep + 1,
                        )
                    }
                }
                val ocrMode = input.resolveOcrMode()
                val ocrObservation = ocrEngine.observe(
                    snapshot = snapshot,
                    uiTreeDump = uiTree,
                    mode = ocrMode,
                )
                textJson {
                    put("ui_tree", uiTree)
                    appendOcrObservationJson(ocrObservation)
                }
            }
        }
    )

    private val screenFindTextTool = Tool(
        name = "screen_find_text",
        description = "Find text in the current Accessibility UI tree (with automatic on-device OCR fallback when tree text coverage is low or text is inside a WebView/Canvas) and return matching bounds.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to find."))
                    put("max_nodes", integerProp("Maximum nodes to scan. Defaults to 160."))
                    put("ocr_mode", stringProp("On-device OCR mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        execute = { input ->
            trackScreenTool("screen_find_text", "查找屏幕文字", input) {
                val service = requireService()
                val query = input.requiredString("text")
                val maxNodes = input.int("max_nodes") ?: 160
                val ocrMode = input.resolveOcrMode()
                val matches = service.findTextNodes(
                    query = query,
                    maxNodes = maxNodes,
                )
                val ocrObservation = if (ocrMode != ScreenOcrMode.OFF && (matches.isEmpty() || ocrMode == ScreenOcrMode.ON)) {
                    val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                    val uiTree = if (snapshot == null) service.dumpUiTree(maxNodes) else ""
                    ocrEngine.observe(
                        snapshot = snapshot,
                        uiTreeDump = uiTree,
                        mode = ocrMode,
                        forceReason = if (matches.isEmpty()) "find_text_fallback" else null,
                    )
                } else null
                val ocrMatches = ocrObservation?.matchesQuery(query).orEmpty()
                textJson {
                    put("matches", buildJsonArray {
                        matches.forEachIndexed { index, match ->
                            add(match.toJson(index))
                        }
                        val offset = matches.size
                        ocrMatches.forEachIndexed { index, block ->
                            add(block.toMatchJson(offset + index))
                        }
                    })
                    ocrObservation?.let { appendOcrObservationJson(it) }
                }
            }
        }
    )

    private val screenTapTextTool = Tool(
        name = "screen_tap_text",
        description = "Tap the first Accessibility node (or on-device OCR fallback region) containing the requested text.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to tap."))
                    put("max_nodes", integerProp("Maximum nodes to scan. Defaults to 160."))
                    put("ocr_mode", stringProp("On-device OCR fallback mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_tap_text", "点击文字", input) {
                val service = requireService()
                val query = input.requiredString("text")
                val maxNodes = input.int("max_nodes") ?: 160
                val ocrMode = input.resolveOcrMode()
                val match = service.findTextNodes(
                    query = query,
                    maxNodes = maxNodes,
                ).firstOrNull()
                if (match != null) {
                    val ok = service.tap(match.bounds.exactCenterX(), match.bounds.exactCenterY())
                    textJson {
                        put("success", ok)
                        put("source", "accessibility_tree")
                        put("match", match.toJson(0))
                    }
                } else {
                    val ocrObservation = if (ocrMode != ScreenOcrMode.OFF) {
                        val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                        val uiTree = if (snapshot == null) service.dumpUiTree(maxNodes) else ""
                        ocrEngine.observe(
                            snapshot = snapshot,
                            uiTreeDump = uiTree,
                            mode = ocrMode,
                            forceReason = "tap_text_fallback",
                        )
                    } else null
                    val ocrMatch = ocrObservation?.matchesQuery(query)?.firstOrNull()
                        ?: error("Text not found on screen: $query")
                    val ok = service.tap(ocrMatch.centerX, ocrMatch.centerY)
                    textJson {
                        put("success", ok)
                        put("source", "ocr")
                        put("ocr_triggered", true)
                        put("match", ocrMatch.toMatchJson(0))
                    }
                }
            }
        }
    )

    private val screenWaitForTextTool = Tool(
        name = "screen_wait_for_text",
        description = "Wait until text appears in the Accessibility UI tree (with automatic on-device OCR fallback when tree text coverage is low).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to wait for."))
                    put("timeout_ms", integerProp("Timeout in milliseconds. Defaults to 10000."))
                    put("interval_ms", integerProp("Polling interval in milliseconds. Defaults to 500."))
                    put("ocr_mode", stringProp("On-device OCR fallback mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        execute = { input ->
            trackScreenTool("screen_wait_for_text", "等待屏幕文字", input) {
                val query = input.requiredString("text")
                val deadline = System.currentTimeMillis() + (input.long("timeout_ms") ?: 10_000L).coerceIn(500L, 60_000L)
                val interval = (input.long("interval_ms") ?: 500L).coerceIn(100L, 3_000L)
                val ocrMode = input.resolveOcrMode()
                val service = requireService()
                var matches = emptyList<app.amber.core.automation.AccessibilityTextMatch>()
                var ocrMatches = emptyList<ScreenOcrBlock>()
                var lastOcrObservation: ScreenOcrObservation? = null
                while (System.currentTimeMillis() < deadline) {
                    matches = service.findTextNodes(query, maxNodes = 200)
                    if (matches.isNotEmpty()) break
                    if (ocrMode != ScreenOcrMode.OFF) {
                        val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                        val obs = ocrEngine.observe(snapshot = snapshot, mode = ocrMode)
                        if (obs.triggered) {
                            lastOcrObservation = obs
                            ocrMatches = obs.matchesQuery(query)
                            if (ocrMatches.isNotEmpty()) break
                        }
                    }
                    delay(interval)
                }
                if (matches.isEmpty() && ocrMatches.isEmpty() && ocrMode != ScreenOcrMode.OFF) {
                    val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                    val obs = ocrEngine.observe(
                        snapshot = snapshot,
                        mode = ocrMode,
                        forceReason = "wait_for_text_fallback",
                    )
                    lastOcrObservation = obs
                    ocrMatches = obs.matchesQuery(query)
                }
                textJson {
                    put("found", matches.isNotEmpty() || ocrMatches.isNotEmpty())
                    put("matches", buildJsonArray {
                        matches.take(5).forEachIndexed { index, match -> add(match.toJson(index)) }
                        val offset = matches.size.coerceAtMost(5)
                        ocrMatches.take(5 - offset).forEachIndexed { index, block -> add(block.toMatchJson(offset + index)) }
                    })
                    lastOcrObservation?.let { appendOcrObservationJson(it) }
                }
            }
        }
    )

    private val screenScrollUntilTool = Tool(
        name = "screen_scroll_until",
        description = "Swipe repeatedly until target text appears in the Accessibility UI tree (or on-device OCR fallback).",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", stringProp("Text to find."))
                    put("direction", stringProp("Swipe direction: down, up, left, or right. Defaults to down."))
                    put("max_swipes", integerProp("Maximum swipes. Defaults to 8."))
                    put("ocr_mode", stringProp("On-device OCR fallback mode: 'auto' (default), 'on', or 'off'."))
                    put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                },
                required = listOf("text")
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            trackScreenTool("screen_scroll_until", "滚动查找文字", input) {
                val service = requireService()
                val maxSwipes = (input.int("max_swipes") ?: 8).coerceIn(1, 20)
                val direction = input.string("direction") ?: "down"
                val query = input.requiredString("text")
                val ocrMode = input.resolveOcrMode()
                var matches = service.findTextNodes(query, maxNodes = 200)
                var ocrMatches = emptyList<ScreenOcrBlock>()
                var lastOcrObservation: ScreenOcrObservation? = null
                var swipes = 0
                var stalled = false
                var lastTree = service.dumpUiTree(maxNodes = 200)
                if (matches.isEmpty() && ocrMode != ScreenOcrMode.OFF) {
                    val initialSnapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                    val initialObs = ocrEngine.observe(snapshot = initialSnapshot, uiTreeDump = lastTree, mode = ocrMode)
                    if (initialObs.triggered) {
                        lastOcrObservation = initialObs
                        ocrMatches = initialObs.matchesQuery(query)
                    }
                }
                var lastOcrText = lastOcrObservation?.combinedVisibleText().orEmpty()
                while (matches.isEmpty() && ocrMatches.isEmpty() && swipes < maxSwipes) {
                    val controller = takeoverController
                    if (controller != null &&
                        (controller.state.value.isPausedOrPausing ||
                            controller.state.value.phase == app.amber.core.automation.PhoneTakeoverPhase.RESUMING)
                    ) {
                        val resumed = controller.awaitResumeIfPaused(
                            currentSnapshotId = controller.state.value.lastSnapshotId,
                            controller = service,
                        )
                        check(resumed) { "Screen automation is paused for user takeover; resume before scrolling further." }
                        val fresh = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                        if (fresh != null) {
                            controller.confirmResumedWithFreshSnapshot(fresh)
                        }
                    }
                    swipeByDirection(service, direction)
                    swipes++
                    delay(450)
                    val tree = service.dumpUiTree(maxNodes = 200)
                    matches = service.findTextNodes(query, maxNodes = 200)
                    var currentOcrText = ""
                    if (matches.isEmpty() && ocrMode != ScreenOcrMode.OFF) {
                        val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                        val obs = ocrEngine.observe(snapshot = snapshot, uiTreeDump = tree, mode = ocrMode)
                        if (obs.triggered) {
                            lastOcrObservation = obs
                            ocrMatches = obs.matchesQuery(query)
                            currentOcrText = obs.combinedVisibleText()
                        }
                    }
                    if (tree == lastTree && currentOcrText == lastOcrText && matches.isEmpty() && ocrMatches.isEmpty()) {
                        // 滑到底/无位移：无障碍树与端侧 OCR 文本均无变化，不再空滑到上限。
                        stalled = true
                        break
                    }
                    lastTree = tree
                    lastOcrText = currentOcrText
                }
                textJson {
                    put("found", matches.isNotEmpty() || ocrMatches.isNotEmpty())
                    put("swipes", swipes)
                    if (stalled) put("stalled", true)
                    put("matches", buildJsonArray {
                        matches.take(5).forEachIndexed { index, match -> add(match.toJson(index)) }
                        val offset = matches.size.coerceAtMost(5)
                        ocrMatches.take(5 - offset).forEachIndexed { index, block -> add(block.toMatchJson(offset + index)) }
                    })
                    lastOcrObservation?.let { appendOcrObservationJson(it) }
                }
            }
        }
    )

    private val screenScreenshotTool = Tool(
        name = "screen_screenshot",
        description = "Capture one screen frame for VLM reasoning using Android MediaProjection after user approval.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("screen_screenshot", "截取屏幕", input, runtime = "MediaProjection") {
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

    private val vlmTaskTool = Tool(
        name = "vlm_task",
        description = "Plan a phone automation task with VLM-style trace semantics. Stage 0 records the " +
            "intent only — it performs no actions; execute via screen_read_ui plus low-level screen tools.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("goal", stringProp("Natural language phone automation goal."))
                },
                required = listOf("goal")
            )
        },
        needsApproval = true,
        execute = { input ->
            trackScreenTool("vlm_task", "规划 VLM 任务", input, runtime = "vlm-contract-stage0") {
                textJson {
                    put("status", "planned")
                    put("goal", input.requiredString("goal"))
                    put("message", "VLM orchestration contract is connected. Use screen_read_ui plus low-level screen actions until remote VLM screenshot reasoning is wired.")
                }
            }
        }
    )

    private suspend fun trackScreenTool(
        toolName: String,
        title: String,
        input: JsonElement,
        runtime: String = "Android Accessibility",
        block: suspend () -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        check(screenOwner.tryLock()) { "Another screen operation is active; wait for it to finish before observing again." }
        try {
            val toolCallId = activityStore.startTool(
                toolName = toolName,
                title = title,
                inputPreview = input.activityInputPreview(toolName),
                runtime = runtime,
            )
            return try {
                val controller = takeoverController
                val isMutatingLowLevelTool = toolName != "screen_run_goal" &&
                    toolName != "screen_read_ui" &&
                    toolName != "screen_find_text" &&
                    toolName != "vlm_task"
                if (controller != null && isMutatingLowLevelTool) {
                    val activeService = getActiveAccessibilityController()
                    if (activeService != null &&
                        (controller.state.value.isPausedOrPausing ||
                            controller.state.value.phase == app.amber.core.automation.PhoneTakeoverPhase.RESUMING)
                    ) {
                        val resumed = controller.awaitResumeIfPaused(
                            currentSnapshotId = controller.state.value.lastSnapshotId,
                            controller = activeService,
                        )
                        check(resumed) { "Screen automation is paused for user takeover; resume before dispatching $toolName." }
                        val fresh = withContext(Dispatchers.Main.immediate) { activeService.captureScreenSnapshot() }
                        if (fresh != null) {
                            controller.confirmResumedWithFreshSnapshot(fresh)
                        }
                    }
                }
                val result = block()
                activityStore.complete(toolCallId, result.previewText())
                result
            } catch (error: Throwable) {
                activityStore.fail(toolCallId, error)
                throw error
            }
        } finally {
            screenOwner.unlock()
        }
    }

    private fun requireService(): AccessibilityController =
        getActiveAccessibilityController()
            ?: error("AmberAgent Accessibility is not enabled. Open ${Settings.ACTION_ACCESSIBILITY_SETTINGS} and enable AmberAgent Accessibility.")

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
            put(xName, numberProp("X coordinate."))
            put(yName, numberProp("Y coordinate."))
        },
        required = listOf(xName, yName)
    )

    private fun result(success: Boolean) = textJson {
        put("success", success)
    }

    /**
     * 动作后验证：verify_text 提供时轮询 UI 树确认效果（手势注入 ≠ 目标生效）；
     * 当无障碍树未命中且未禁用 OCR 时，自动触发端侧 OCR 回退核验。
     * 未提供则退化为仅上报注入结果。
     */
    private suspend fun verifiedResult(
        service: AccessibilityController,
        input: JsonElement,
        success: Boolean,
    ): List<UIMessagePart> {
        val verifyText = input.string("verify_text")
        if (verifyText == null || !success) return result(success)
        val ocrMode = input.resolveOcrMode()
        val deadline = System.currentTimeMillis() +
            (input.long("verify_timeout_ms") ?: 2_000L).coerceIn(200L, 15_000L)
        var found = false
        var verifiedByOcr = false
        var ocrObservation: ScreenOcrObservation? = null
        while (System.currentTimeMillis() < deadline) {
            if (service.findTextNodes(verifyText, maxNodes = 200).isNotEmpty()) {
                found = true
                break
            }
            delay(250)
        }
        if (!found && ocrMode != ScreenOcrMode.OFF) {
            val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
            val obs = ocrEngine.observe(
                snapshot = snapshot,
                mode = ocrMode,
                forceReason = "verify_text_fallback",
            )
            ocrObservation = obs
            if (obs.matchesQuery(verifyText).isNotEmpty()) {
                found = true
                verifiedByOcr = true
            }
        }
        return textJson {
            put("success", success)
            put("verify_text_found", found)
            put("verified_by_ocr", verifiedByOcr)
            ocrObservation?.let { appendOcrObservationJson(it) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.appendOcrObservationJson(
        observation: ScreenOcrObservation,
    ) {
        put("ocr_mode", observation.mode.wireName())
        put("ocr_triggered", observation.triggered)
        put("ocr_reason", observation.triggerReason)
        put("ocr_block_count", observation.blocks.size)
        put("tree_coverage", buildJsonObject {
            put("total_nodes", observation.coverage.totalNodes)
            put("labeled_nodes", observation.coverage.labeledNodes)
            put("interactive_nodes", observation.coverage.interactiveNodes)
            put("unlabeled_interactive_nodes", observation.coverage.unlabeledInteractiveNodes)
            put("custom_canvas_or_webview_nodes", observation.coverage.customCanvasOrWebViewNodes)
            put("coverage_ratio", observation.coverage.coverageRatio.toDouble())
            put("is_low_coverage", observation.coverage.isLowCoverage)
            put("reason", observation.coverage.reason)
        })
        if (observation.triggered) {
            put("ocr_blocks", buildJsonArray {
                observation.blocks.forEach { block ->
                    add(block.toJson())
                }
            })
            if (observation.blocks.isNotEmpty()) {
                put(
                    "ocr_text",
                    observation.blocks
                        .map { SensitiveScreenTextClassifier.redactIfSensitive(it.text) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("\n")
                        .take(4_000),
                )
            }
        }
    }

    private fun ScreenOcrBlock.toJson() = buildJsonObject {
        put("id", id)
        put("text", SensitiveScreenTextClassifier.redactIfSensitive(text))
        put("confidence", confidence.toDouble())
        put("sensitive", isSensitive)
        put("bounds", buildJsonObject {
            put("left", left)
            put("top", top)
            put("right", right)
            put("bottom", bottom)
            put("center_x", centerX.toDouble())
            put("center_y", centerY.toDouble())
        })
        put("normalized_bounds", buildJsonObject {
            put("left", normalizedBounds.left.toDouble())
            put("top", normalizedBounds.top.toDouble())
            put("right", normalizedBounds.right.toDouble())
            put("bottom", normalizedBounds.bottom.toDouble())
            put("center_x", normalizedBounds.centerX.toDouble())
            put("center_y", normalizedBounds.centerY.toDouble())
        })
    }

    private fun ScreenOcrBlock.toMatchJson(index: Int) = buildJsonObject {
        put("index", index)
        put("source", "ocr")
        put("text", SensitiveScreenTextClassifier.redactIfSensitive(text))
        put("content_description", "")
        put("class_name", "ocr.TextBlock")
        put("view_id", id)
        put("clickable", true)
        put("enabled", true)
        put("confidence", confidence.toDouble())
        put("bounds", buildJsonObject {
            put("left", left)
            put("top", top)
            put("right", right)
            put("bottom", bottom)
            put("center_x", centerX.toDouble())
            put("center_y", centerY.toDouble())
        })
        put("normalized_bounds", buildJsonObject {
            put("left", normalizedBounds.left.toDouble())
            put("top", normalizedBounds.top.toDouble())
            put("right", normalizedBounds.right.toDouble())
            put("bottom", normalizedBounds.bottom.toDouble())
            put("center_x", normalizedBounds.centerX.toDouble())
            put("center_y", normalizedBounds.centerY.toDouble())
        })
    }

    private fun JsonElement.booleanOrNull(name: String): Boolean? =
        string(name)?.let { raw ->
            when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private fun JsonElement.resolveOcrMode(): ScreenOcrMode =
        ScreenOcrMode.parse(
            ocrModeRaw = string("ocr_mode"),
            includeOcrRaw = booleanOrNull("include_ocr"),
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

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun numberProp(description: String) = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    private fun integerProp(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun List<UIMessagePart>.previewText(): String =
        joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.takeLast(1_600)

    private fun launchHostApp(service: AccessibilityController, hostPackageName: String): Boolean {
        if (service.returnToHostApp(hostPackageName)) return true
        return runCatching {
            val intent = Intent(context, app.amber.agent.RouteActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(intent)
            true
        }.recoverCatching {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(hostPackageName)
                ?: return@recoverCatching false
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun JsonElement.activityInputPreview(toolName: String): String =
        when (toolName) {
            "screen_run_goal" -> buildJsonObject {
                put("package_name", string("package_name").orEmpty())
                put("goal_chars", string("goal")?.length ?: 0)
            }.toString()
            "screen_input_text" -> buildJsonObject {
                put("text_chars", string("text")?.length ?: 0)
            }.toString()
            else -> toString()
        }
}

private fun JsonElement.requiredFloat(name: String): Float =
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
