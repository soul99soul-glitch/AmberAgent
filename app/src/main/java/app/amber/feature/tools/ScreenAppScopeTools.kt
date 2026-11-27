package app.amber.feature.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import app.amber.agent.BuildConfig
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.CapturedScreenBitmap
import app.amber.core.automation.DefaultScreenOcrEngine
import app.amber.core.automation.MlKitScreenTextRecognizer
import app.amber.core.automation.ScreenOcrCapabilities
import app.amber.core.automation.getActiveAccessibilityController
import app.amber.core.automation.display.ActiveVirtualDisplayRun
import app.amber.core.automation.display.DisplaySessionOutcome
import app.amber.core.automation.display.PhoneDisplayMode
import app.amber.core.automation.display.PhoneDisplaySession
import app.amber.core.automation.display.PhoneDisplaySessionFactory
import app.amber.core.automation.display.VirtualDisplayCompatibility
import app.amber.core.automation.display.VirtualDisplayFrameSource
import app.amber.core.automation.display.VirtualDisplayProbeStore
import app.amber.core.automation.display.VirtualDisplayRunNotifier
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.display.VirtualDisplaySession
import app.amber.core.automation.display.VirtualDisplaySessionState
import app.amber.core.automation.display.isPhoneUseVirtualDisplayEnabled
import app.amber.core.jev.JevImageAsset
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.jev.ScreenshotAttempt
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Installed-app scope, run controls and the bounded Jev goal entry. */
internal class ScreenAppScopeTools(
    private val context: Context,
    private val settingsStore: SettingsAggregator?,
    private val jevScreenGoalRunner: JevScreenGoalRunner?,
    private val operations: ScreenOperationOwner,
    private val displaySessionFactory: PhoneDisplaySessionFactory? = null,
    private val probeStore: VirtualDisplayProbeStore? = null,
    private val virtualDisplayRunNotifier: VirtualDisplayRunNotifier? = null,
    private val virtualDisplayAllowedProvider: () -> Boolean = { BuildConfig.PHONE_USE_VIRTUAL_DISPLAY_ALLOWED },
    private val virtualDisplayEnabledProvider: () -> Boolean = { isPhoneUseVirtualDisplayEnabled(context) },
    private val probeCompatibilityProvider: () -> VirtualDisplayCompatibility = {
        probeStore?.compatibility() ?: VirtualDisplayCompatibility.UNSUPPORTED
    },
    private val sessionOpener: (suspend (runId: String, packageName: String, mode: PhoneDisplayMode) -> DisplaySessionOutcome)? =
        displaySessionFactory?.let { factory ->
            { r, p, m -> factory.open(r, p, m) }
        },
    private val virtualLaunchObserveRetries: Int = 5,
    private val virtualLaunchObserveDelayMs: Long = 200L,
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
        description = "Open the approved package and use Jev to observe progress toward a goal. " +
            "A proposed click, text entry or scroll is returned without dispatch. Request that step " +
            "through an individual screen tool for approval, then observe again. Dry run never opens an app.",
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
                put("return_to_host", buildJsonObject { put("type", "boolean"); put("description", "Legacy option; the target stays in front during individually approved steps.") })
                put("ocr_mode", screenStringProp("On-device OCR mode: 'auto' (default, zero-overhead tree-first with low-coverage fallback), 'on' (force OCR), or 'off' (disable OCR)."))
                put("include_ocr", buildJsonObject { put("type", "boolean"); put("description", "Optional boolean alias to explicitly enable or disable on-device OCR.") })
                put("display_mode", screenStringProp("Target display mode: 'auto' (default), 'physical', or 'virtual'. 'auto' uses virtual only when allowed, enabled in settings, and verified on this device; 'virtual' fails closed with display_unavailable if unavailable and never falls back to physical."))
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
                val maxSteps = input.int("max_steps") ?: 15
                val maxDurationMs = input.long("max_duration_ms") ?: 180_000

                // Resolve display mode BEFORE requireScreenAccessibility() and BEFORE context.startActivity().
                val requestedDisplayModeRaw = input.string("display_mode")?.trim()?.lowercase().orEmpty().ifEmpty { "auto" }
                val resolvedMode: PhoneDisplayMode = when (requestedDisplayModeRaw) {
                    "physical" -> PhoneDisplayMode.PHYSICAL
                    "auto" -> {
                        val autoVirtualReady = virtualDisplayAllowedProvider() &&
                            virtualDisplayEnabledProvider() &&
                            probeCompatibilityProvider() == VirtualDisplayCompatibility.VERIFIED &&
                            sessionOpener != null
                        if (autoVirtualReady) PhoneDisplayMode.VIRTUAL else PhoneDisplayMode.PHYSICAL
                    }
                    "virtual" -> PhoneDisplayMode.VIRTUAL
                    else -> {
                        return@trackScreenTool displayUnavailableJson(
                            packageName = packageName,
                            requestedMode = requestedDisplayModeRaw,
                            reason = "invalid_display_mode:$requestedDisplayModeRaw",
                        )
                    }
                }

                if (resolvedMode == PhoneDisplayMode.VIRTUAL) {
                    return@trackScreenTool executeVirtualScreenGoal(
                        runner = runner,
                        ownerRun = ownerRun,
                        packageName = packageName,
                        goal = goal,
                        texts = texts,
                        maxSteps = maxSteps,
                        maxDurationMs = maxDurationMs,
                        dryRun = dryRun,
                        ocrMode = ocrMode,
                        requestedModeRaw = requestedDisplayModeRaw,
                    )
                }

                // PHYSICAL path — strictly identical to prior production behavior.
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
                    maxSteps = maxSteps,
                    maxDurationMs = maxDurationMs,
                    dryRun = dryRun,
                    proposeNextAction = true,
                    hostPackageName = context.packageName,
                    autoReturnToHost = returnToHostOverride,
                    returnToHostLauncher = { hostPkg -> launchHostApp(service, hostPkg) },
                    ocrMode = ocrMode,
                )
                listOf(UIMessagePart.Text(outcome.toString()))
            }
        },
    )

    private suspend fun executeVirtualScreenGoal(
        runner: JevScreenGoalRunner,
        ownerRun: String,
        packageName: String,
        goal: String,
        texts: List<String>,
        maxSteps: Int,
        maxDurationMs: Long,
        dryRun: Boolean,
        ocrMode: app.amber.core.automation.ScreenOcrMode,
        requestedModeRaw: String,
    ): List<UIMessagePart> {
        if (!virtualDisplayAllowedProvider()) {
            return displayUnavailableJson(packageName, requestedModeRaw, "virtual_display_disabled_by_build")
        }
        if (!virtualDisplayEnabledProvider()) {
            return displayUnavailableJson(packageName, requestedModeRaw, "virtual_display_setting_disabled")
        }
        val opener = sessionOpener
            ?: return displayUnavailableJson(packageName, requestedModeRaw, "virtual_display_factory_unavailable")

        val sessionOutcome = opener(ownerRun, packageName, PhoneDisplayMode.VIRTUAL)
        if (sessionOutcome is DisplaySessionOutcome.Unavailable) {
            // Fail closed: NEVER silently fall back to PHYSICAL and NEVER call context.startActivity.
            return displayUnavailableJson(packageName, requestedModeRaw, sessionOutcome.reason)
        }
        val session = (sessionOutcome as DisplaySessionOutcome.Ready).session
        if (session.mode != PhoneDisplayMode.VIRTUAL) {
            runCatching { session.close("unexpected_session_mode") }
            return displayUnavailableJson(packageName, requestedModeRaw, "unexpected_session_mode")
        }

        val controlInterruptReason = AtomicReference<String?>(null)
        val controlInterruptOutcomeUnknown = java.util.concurrent.atomic.AtomicBoolean(false)
        val runnerJobRef = AtomicReference<Job?>(null)
        val virtualOcrEngine = buildVirtualOcrEngine(session)
        val activeRun = ActiveVirtualDisplayRun(
            runId = ownerRun,
            packageName = packageName,
            goal = goal,
            session = session,
            frameSource = (session as? VirtualDisplaySession)?.frameSource,
            onPauseRequested = {
                val takeover = operations.takeoverController
                if (takeover != null) {
                    takeover.requestUserTakeover(
                        reason = "virtual_display_user_paused",
                        controller = session.controller,
                    )
                } else {
                    (session as? VirtualDisplaySession)?.invalidate(
                        reason = "virtual_display_user_paused",
                        targetState = VirtualDisplaySessionState.SAFE_PAUSED,
                    )
                }
            },
            onResumeRequested = {
                operations.takeoverController?.requestResume()
                virtualDisplayRunNotifier?.showRunning(it, paused = false)
            },
            onTakeoverRequested = {
                controlInterruptReason.compareAndSet(null, "user_takeover")
                if ((session as? VirtualDisplaySession)?.state?.value == VirtualDisplaySessionState.OUTCOME_UNKNOWN) {
                    controlInterruptOutcomeUnknown.set(true)
                }
                (session as? VirtualDisplaySession)?.invalidate(
                    reason = "user_takeover",
                    targetState = VirtualDisplaySessionState.SAFE_PAUSED,
                )
                runnerJobRef.get()?.cancel(CancellationException("virtual_display_user_takeover"))
                operations.takeoverController?.finishSession(ownerRun)
                session.close("user_takeover")
                virtualDisplayRunNotifier?.cancel(it)
            },
            onEmergencyStopRequested = {
                controlInterruptReason.compareAndSet(null, "emergency_stop")
                if ((session as? VirtualDisplaySession)?.state?.value == VirtualDisplaySessionState.OUTCOME_UNKNOWN) {
                    controlInterruptOutcomeUnknown.set(true)
                }
                operations.stop(ownerRun)
                (session as? VirtualDisplaySession)?.invalidate(
                    reason = "emergency_stop",
                    targetState = VirtualDisplaySessionState.SAFE_PAUSED,
                )
                runnerJobRef.get()?.cancel(CancellationException("virtual_display_emergency_stop"))
                operations.takeoverController?.finishSession(ownerRun)
                session.close("emergency_stop")
                virtualDisplayRunNotifier?.cancel(it)
            },
        )

        VirtualDisplayRunRegistry.register(activeRun)
        virtualDisplayRunNotifier?.showRunning(activeRun)

        var closeReason = "completed"
        try {
            if (!dryRun && runner.canExecute()) {
                // Launch strictly on the virtual display — ZERO context.startActivity on Display 0.
                val launched = session.launchTarget()
                if (!launched) {
                    val fault = virtualSessionFault(session)
                    val faultStatus = fault?.first ?: "SAFE_PAUSED"
                    val faultReason = fault?.second ?: "virtual_display_launch_failed"
                    closeReason = faultReason
                    return virtualFaultJson(
                        status = faultStatus,
                        packageName = packageName,
                        session = session,
                        reason = faultReason,
                    )
                }

                // R3 L3: Confirm target package window actually landed on this virtual display before
                // dispatching or handing control to Jev.
                var targetObserved = false
                val attempts = virtualLaunchObserveRetries.coerceAtLeast(1)
                for (attempt in 0 until attempts) {
                    virtualSessionFault(session)?.let { (faultStatus, faultReason) ->
                        closeReason = faultReason
                        return virtualFaultJson(
                            status = faultStatus,
                            packageName = packageName,
                            session = session,
                            reason = faultReason,
                        )
                    }
                    val observedPkg = withContext(Dispatchers.Main.immediate) {
                        session.controller.captureScreenSnapshot()?.packageName
                    }
                    if (observedPkg == packageName) {
                        targetObserved = true
                        break
                    }
                    if (attempt + 1 < attempts && virtualLaunchObserveDelayMs > 0L) {
                        delay(virtualLaunchObserveDelayMs)
                    }
                }
                if (!targetObserved) {
                    val fault = virtualSessionFault(session)
                    val faultStatus = fault?.first ?: "SAFE_PAUSED"
                    val faultReason = fault?.second ?: "virtual_display_target_not_observed"
                    closeReason = faultReason
                    return virtualFaultJson(
                        status = faultStatus,
                        packageName = packageName,
                        session = session,
                        reason = faultReason,
                    )
                }
            }

            virtualSessionFault(session)?.let { (faultStatus, faultReason) ->
                closeReason = faultReason
                return virtualFaultJson(
                    status = faultStatus,
                    packageName = packageName,
                    session = session,
                    reason = faultReason,
                )
            }

            val virtualScreenshotProvider = buildVirtualScreenshotProvider(session)
            val outcome: JsonObject = try {
                coroutineScope {
                    val job = async {
                        runner.run(
                            controller = session.controller,
                            packageName = packageName,
                            goal = goal,
                            texts = texts,
                            runKey = ownerRun,
                            maxSteps = maxSteps,
                            maxDurationMs = maxDurationMs,
                            dryRun = dryRun,
                            proposeNextAction = true,
                            hostPackageName = context.packageName,
                            autoReturnToHost = false,
                            returnToHostLauncher = null,
                            ocrMode = ocrMode,
                            screenshotProviderOverride = virtualScreenshotProvider,
                            ocrEngineOverride = virtualOcrEngine,
                            displayFaultProvider = { virtualSessionFault(session) },
                        )
                    }
                    runnerJobRef.set(job)
                    job.await()
                }
            } catch (ce: CancellationException) {
                val interruptReason = controlInterruptReason.get()
                if (interruptReason != null) {
                    currentCoroutineContext().ensureActive()
                    val faultStatus = if (controlInterruptOutcomeUnknown.get() ||
                        (session as? VirtualDisplaySession)?.state?.value == VirtualDisplaySessionState.OUTCOME_UNKNOWN
                    ) {
                        "outcome_unknown"
                    } else {
                        "SAFE_PAUSED"
                    }
                    closeReason = interruptReason
                    return virtualFaultJson(
                        status = faultStatus,
                        packageName = packageName,
                        session = session,
                        reason = interruptReason,
                    )
                }
                closeReason = "cancelled"
                throw ce
            }

            val postFault = virtualSessionFault(session)
            val reconciled = reconcileVirtualOutcome(outcome, session, postFault)
            closeReason = reconciled["status"]?.jsonPrimitive?.contentOrNull ?: "completed"
            return listOf(UIMessagePart.Text(reconciled.toString()))
        } finally {
            withContext(NonCancellable) {
                runCatching { virtualOcrEngine?.close() }
                VirtualDisplayRunRegistry.unregister(ownerRun, activeRun)
                virtualDisplayRunNotifier?.cancel(activeRun)
                runCatching { session.close(closeReason) }
            }
        }
    }

    private fun virtualSessionFault(session: PhoneDisplaySession): Pair<String, String>? {
        val vd = session as? VirtualDisplaySession ?: return null
        val frameStats = vd.frameSource.latestStats.value
        if (frameStats?.unobservable == true && vd.state.value == VirtualDisplaySessionState.OPEN) {
            vd.invalidate("virtual_display_frame_unobservable", VirtualDisplaySessionState.UNOBSERVABLE)
        }
        return when (vd.state.value) {
            VirtualDisplaySessionState.OPEN -> null
            VirtualDisplaySessionState.SAFE_PAUSED ->
                "SAFE_PAUSED" to (vd.invalidationReason ?: "virtual_display_paused")
            VirtualDisplaySessionState.OUTCOME_UNKNOWN ->
                "outcome_unknown" to (vd.invalidationReason ?: "virtual_display_outcome_unknown")
            VirtualDisplaySessionState.UNOBSERVABLE ->
                "UNOBSERVABLE" to (vd.invalidationReason ?: "virtual_display_unobservable")
            VirtualDisplaySessionState.CLOSED ->
                "SAFE_PAUSED" to (vd.invalidationReason ?: "virtual_display_closed")
        }
    }

    private fun reconcileVirtualOutcome(
        outcome: JsonObject,
        session: PhoneDisplaySession,
        fault: Pair<String, String>?,
    ): JsonObject {
        val existingStatus = outcome["status"]?.jsonPrimitive?.contentOrNull
        val overrides = linkedMapOf<String, JsonPrimitive>(
            "display_mode" to JsonPrimitive("virtual"),
            "display_id" to JsonPrimitive(session.displayId),
            "display_generation" to JsonPrimitive(session.generation),
            "auto_return_to_host" to JsonPrimitive(false),
        )
        if (fault != null &&
            existingStatus != "SAFE_PAUSED" &&
            existingStatus != "outcome_unknown" &&
            existingStatus != "UNOBSERVABLE"
        ) {
            overrides["status"] = JsonPrimitive(fault.first)
            overrides["reason"] = JsonPrimitive(fault.second)
            overrides["goal_verified"] = JsonPrimitive(false)
        }
        return JsonObject(outcome + overrides)
    }

    private fun buildVirtualScreenshotProvider(
        session: PhoneDisplaySession,
    ): (suspend (reference: String) -> ScreenshotAttempt) {
        val frameSource: VirtualDisplayFrameSource? = (session as? VirtualDisplaySession)?.frameSource
        return provider@{ reference ->
            if (frameSource == null) {
                return@provider ScreenshotAttempt.Skipped("virtual_frame_source_unavailable")
            }
            if (frameSource.latestStats.value?.unobservable == true) {
                (session as? VirtualDisplaySession)?.markUnobservable("virtual_display_frame_unobservable")
                return@provider ScreenshotAttempt.Skipped("virtual_display_unobservable")
            }
            val bitmap = withContext(Dispatchers.Default) { frameSource.copyLatestBitmap() }
                ?: return@provider ScreenshotAttempt.Skipped("virtual_frame_not_ready")
            try {
                if (bitmap.width > 1_568 || bitmap.height > 1_568) {
                    return@provider ScreenshotAttempt.Skipped("image_edge_out_of_range")
                }
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                }
                runCatching {
                    ScreenshotAttempt.Attached(
                        JevImageAsset(
                            reference = reference,
                            format = "jpeg",
                            data = bytes,
                            widthPx = bitmap.width,
                            heightPx = bitmap.height,
                        ),
                    )
                }.getOrElse {
                    ScreenshotAttempt.Skipped("image_edge_out_of_range")
                }
            } finally {
                if (!bitmap.isRecycled) {
                    runCatching { bitmap.recycle() }
                }
            }
        }
    }

    private fun buildVirtualOcrEngine(session: PhoneDisplaySession): DefaultScreenOcrEngine? {
        val vd = session as? VirtualDisplaySession ?: return null
        return DefaultScreenOcrEngine(
            displaySizeProvider = { vd.width to vd.height },
            screenshotProvider = { _, _ ->
                if (vd.frameSource.latestStats.value?.unobservable == true) {
                    vd.markUnobservable("virtual_display_frame_unobservable")
                    null
                } else {
                    val bitmap = withContext(Dispatchers.Default) { vd.frameSource.copyLatestBitmap() }
                    bitmap?.let {
                        CapturedScreenBitmap(
                            bitmap = it,
                            source = "virtual_display",
                            width = it.width,
                            height = it.height,
                        )
                    }
                }
            },
            recognizerId = ScreenOcrCapabilities.RECOGNIZER_ID,
            ownedRecognizer = MlKitScreenTextRecognizer(),
        )
    }

    private fun displayUnavailableJson(
        packageName: String,
        requestedMode: String,
        reason: String,
    ): List<UIMessagePart> = textJson {
        put("status", "display_unavailable")
        put("display_mode", "virtual")
        put("requested_display_mode", requestedMode)
        put("package_name", packageName)
        put("reason", reason)
        put("fallback_attempted", false)
    }

    private fun virtualFaultJson(
        status: String,
        packageName: String,
        session: PhoneDisplaySession,
        reason: String,
    ): List<UIMessagePart> = textJson {
        put("status", status)
        put("display_mode", "virtual")
        put("display_id", session.displayId)
        put("display_generation", session.generation)
        put("package_name", packageName)
        put("reason", reason)
        put("goal_verified", false)
        put("returned_to_host", false)
    }

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
