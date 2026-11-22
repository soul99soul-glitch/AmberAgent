package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.getActiveAccessibilityController
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed-app scope, run controls and the bounded Jev goal entry. */
internal class ScreenAppScopeTools(
    private val context: Context,
    private val settingsStore: SettingsAggregator?,
    private val jevScreenGoalRunner: JevScreenGoalRunner?,
    private val operations: ScreenOperationOwner,
) {
    fun screenStatusTool(runId: String?) = Tool(
        name = "screen_status",
        description = "Report AmberAgent screen-control readiness: whether the Accessibility " +
            "service is enabled, the persisted app allow-list that screen_run_goal may operate in, " +
            "whether another screen operation is currently running, and whether this run was " +
            "ended by screen_stop. Read-only: never starts or re-pairs the control service, never " +
            "launches an app. When the allow-list is empty, call screen_apps then screen_add_app first.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val allowed = allowedPackages()
            val serviceAvailable = getActiveAccessibilityController() != null
            textJson {
                put("accessibility_enabled", serviceAvailable)
                put("session_stopped", operations.isStopped(runId))
                put("stop_scope", if (operations.runKey(runId) != null) "run" else "unbound")
                put("operation_active", operations.isActive)
                put("run_bound", runId != null)
                put("allowed_packages", buildJsonArray { allowed.forEach { add(it) } })
                put("scope", if (allowed.isEmpty()) "no_scope" else "scoped")
                if (!serviceAvailable) {
                    put("remedy", "enable AmberAgent Accessibility in system accessibility settings")
                } else if (allowed.isEmpty()) {
                    put("remedy", "call screen_apps then screen_add_app with the exact package name")
                }
            }
        },
    )

    val screenAppsTool = Tool(
        name = "screen_apps",
        description = "List launchable apps installed on this device with their exact package names, " +
            "from the host's own package index. Read-only: does not launch anything and does not " +
            "start the control service. Returns items plus total, has_more and the current " +
            "allow-list; use the exact package name with screen_add_app. Names may be English; " +
            "page with offset when the query misses, do not guess a package name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", screenStringProp("Optional name or package substring; empty lists apps."))
                    put("offset", screenIntegerProp("Offset into the filtered list. Defaults to 0."))
                    put("limit", screenIntegerProp("Page size 1..100. Defaults to 50."))
                },
            )
        },
        execute = { input ->
            val query = input.string("query").orEmpty().trim()
            val offset = (input.int("offset") ?: 0).coerceAtLeast(0)
            val limit = (input.int("limit") ?: 50).coerceIn(1, 100)
            val allowed = allowedPackages()
            val matches = installedLaunchableApps()
                .filter { (pkg, label) ->
                    query.isEmpty() || pkg.contains(query, ignoreCase = true) ||
                        label.contains(query, ignoreCase = true)
                }
            val page = matches.drop(offset).take(limit)
            textJson {
                put("items", buildJsonArray {
                    page.forEach { (pkg, label) ->
                        add(buildJsonObject {
                            put("package", pkg)
                            put("label", label)
                            put("selected", pkg in allowed)
                        })
                    }
                })
                put("total", matches.size)
                put("offset", offset)
                put("has_more", offset + page.size < matches.size)
                put("allowed_packages", buildJsonArray { allowed.forEach { add(it) } })
            }
        },
    )

    fun screenAddAppTool(runId: String?) = Tool(
        name = "screen_add_app",
        description = "Add one exact package name (from screen_apps) to the persisted app allow-list " +
            "that screen_run_goal is allowed to operate in. Takes effect immediately for the " +
            "current run and is kept for later runs. Does not open the app and does not click or " +
            "type; add the app first, then use screen_run_goal or screen_open_app. Only add apps " +
            "the user actually asked to control.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package_name", screenStringProp("Exact package name returned by screen_apps."))
                },
                required = listOf("package_name"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            check(!operations.isStopped(runId)) {
                "Screen control was ended by screen_stop for this run; the allow-list is frozen."
            }
            val store = settingsStore ?: error("screen allow-list unavailable")
            val packageName = input.requiredString("package_name").trim()
            require(packageName.isNotBlank()) { "package_name is required" }
            require(installedLaunchableApps().any { it.first == packageName }) {
                "Unknown or non-launchable package: $packageName. Use screen_apps to find the exact id."
            }
            store.update { settings ->
                val current = settings.agentRuntime.screenAutomation
                if (current.allows(packageName)) settings else settings.copy(
                    agentRuntime = settings.agentRuntime.copy(
                        screenAutomation = current.copy(
                            allowedPackages = (current.allowedPackages + packageName).distinct(),
                        ),
                    ),
                )
            }
            val allowed = allowedPackages()
            textJson {
                put("package_name", packageName)
                put("allowed_packages", buildJsonArray { allowed.forEach { add(it) } })
                put("run_bound", runId != null)
            }
        },
    )

    /**
     * 与 screen_add_app 对称的撤销入口。只收窄授权，不发放任何东西，因此不需要审批；
     * 撤销后同 run 的 screen_run_goal 立即拒绝该应用。
     */
    val screenRemoveAppTool = Tool(
        name = "screen_remove_app",
        description = "Remove one package from the persisted app allow-list, narrowing what " +
            "screen_run_goal may operate in. Takes effect immediately. Does not close the app, " +
            "does not stop an action already in flight, and grants nothing; use it when the user " +
            "asks to revoke an app they previously authorized.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package_name", screenStringProp("Exact package name currently in the allow-list."))
                },
                required = listOf("package_name"),
            )
        },
        execute = { input ->
            val store = settingsStore ?: error("screen allow-list unavailable")
            val packageName = input.requiredString("package_name").trim()
            require(packageName.isNotBlank()) { "package_name is required" }
            val before = allowedPackages()
            if (packageName !in before) {
                textJson {
                    put("removed", false)
                    put("reason", "not_in_allow_list")
                    put("allowed_packages", buildJsonArray { before.forEach { add(it) } })
                }
            } else {
                store.update { settings ->
                    val scope = settings.agentRuntime.screenAutomation
                    settings.copy(
                        agentRuntime = settings.agentRuntime.copy(
                            screenAutomation = scope.copy(allowedPackages = scope.allowedPackages - packageName),
                        ),
                    )
                }
                val after = allowedPackages()
                textJson {
                    put("removed", true)
                    put("package_name", packageName)
                    put("allowed_packages", buildJsonArray { after.forEach { add(it) } })
                }
            }
        },
    )

    fun screenStopTool(runId: String?) = Tool(
        name = "screen_stop",
        description = "End screen control for this run: no later screen_run_goal call and no later allow-list " +
            "change from this run is dispatched. Individual low-level actions still need their own " +
            "approval each time, so they cannot continue silently either. Cannot undo clicks, " +
            "swipes or text already sent to the system. Safe to call twice; it does not interrupt " +
            "an action that is already in flight.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val key = operations.runKey(runId)
            if (key == null) {
                // 没有宿主拥有的 run 就无法界定"本次"，拒绝落成进程级开关。
                textJson {
                    put("stopped", false)
                    put("reason", "no_run_scope")
                    put("note", "screen_stop ends one host-owned run; it needs a run-bound tool set")
                }
            } else {
                val wasStopped = operations.isStopped(runId)
                operations.stop(key)
                textJson {
                    put("stopped", true)
                    put("already_stopped", wasStopped)
                    // 不谎称已中断：在途动作由 owner 锁持有，需等它自然结束。
                    put("operation_active", operations.isActive)
                }
            }
        },
    )

    private fun allowedPackages(): List<String> =
        settingsStore?.settingsFlow?.value?.agentRuntime?.screenAutomation?.allowedPackages.orEmpty()

    /** launcher 可见的应用：走 queryIntentActivities，与 screen_open_app 同一可见性面。 */
    private fun installedLaunchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    fun screenGoalTool(runId: String?) = Tool(
        name = "screen_run_goal",
        description = "Use Jev to accelerate Android Accessibility: open the specified app, then execute a bounded goal internally with fresh UI-node validation. Prefer this over repeated screen_read_ui/click/swipe for read-only navigation, locating content, scrolling, or filling supplied search text. Jev is a decision service, not JavaScript. Requires Jev screen automation and screen-content consent. Returns engine, jev_decisions, actions_dispatched, steps and visible_text; report those facts, never infer Jev usage from tool names. No sending, purchases, account changes or permission grants. On handback use the returned screen evidence and low-level tools. Each call requires approval.",
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {
                put("package_name", screenStringProp("Android package to open and stay within, e.g. tv.danmaku.bili."))
                put("goal", screenStringProp("One concrete screen goal with a visible completion condition; at most 1000 characters."))
                put("texts", buildJsonObject {
                    put("type", "array")
                    put("items", screenStringProp("Exact permitted search/draft text, at most 300 characters."))
                    put("description", "Up to 8 text candidates. Without these, typing is unavailable.")
                })
                put("max_steps", screenIntegerProp("Jev decision limit, default 15, hard maximum 30."))
                put("max_duration_ms", screenIntegerProp("Deadline, default 180000ms, hard maximum 180000ms."))
                put("dry_run", buildJsonObject { put("type", "boolean"); put("description", "Only inspect the currently open target app and judge; do not launch or execute actions.") })
                put("return_to_host", buildJsonObject { put("type", "boolean"); put("description", "Optional override for automatically returning to Amber when the goal completes.") })
                put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default, zero-overhead tree-first with low-coverage fallback), 'on' (force OCR), or 'off' (disable OCR)."))
                put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
            }, required = listOf("package_name", "goal"))
        },
        needsApproval = true,
        allowsAutoApproval = false,
        mandatoryApproval = true,
        execute = { input ->
            operations.trackScreenTool(
                "screen_run_goal", "Jev 无障碍目标执行", input,
                runtime = "Jev + Android Accessibility", runId = runId,
            ) {
                val runner = jevScreenGoalRunner ?: error("Jev screen runner unavailable")
                val ownerRun = runId?.takeIf { it.isNotBlank() } ?: error("screen_run_goal requires a host-owned run")
                val packageName = input.requiredString("package_name")
                val allowed = allowedPackages()
                if (settingsStore != null && packageName !in allowed) {
                    // 多步自控只进用户显式授权过的应用；未授权时引导先查再授权，而不是回退到别的方式。
                    return@trackScreenTool textJson {
                        put("status", "no_scope")
                        put("package_name", packageName)
                        put("reason", if (allowed.isEmpty()) "no_app_scope" else "package_not_allowed")
                        put("allowed_packages", buildJsonArray { allowed.forEach { add(it) } })
                        put("remedy", "call screen_apps then screen_add_app with the exact package name")
                    }
                }
                val goal = input.requiredString("goal")
                val texts = (input.jsonObject["texts"] as? kotlinx.serialization.json.JsonArray)
                    ?.map { it.jsonPrimitive.content } ?: emptyList()
                require(goal.isNotBlank() && goal.length <= 1000 && texts.size <= 8 && texts.all { it.length <= 300 })
                val dryRun = input.string("dry_run") == "true"
                val returnToHostOverride = input.booleanOrNull("return_to_host")
                val ocrMode = input.resolveOcrMode()
                val service = requireScreenAccessibility()
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
                    maxSteps = input.int("max_steps") ?: 15,
                    maxDurationMs = input.long("max_duration_ms") ?: 180_000,
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

    private fun launchHostApp(service: app.amber.core.automation.AccessibilityController, hostPackageName: String): Boolean {
        val targetPkg = hostPackageName.trim().ifBlank { context.packageName }
        if (service.returnToHostApp(targetPkg)) {
            return true
        }
        return runCatching {
            val intent = if (targetPkg == context.packageName) {
                Intent(context, app.amber.agent.RouteActivity::class.java)
            } else {
                context.packageManager.getLaunchIntentForPackage(targetPkg)
            } ?: return@runCatching false
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
