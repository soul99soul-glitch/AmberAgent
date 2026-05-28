package app.amber.feature.office

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import app.amber.feature.system.AgentPermissionBroker
import app.amber.feature.system.AgentPermissionStatus
import app.amber.feature.system.AmberNotificationListenerService
import app.amber.feature.workspace.WorkspaceManager
import me.rerere.rikkahub.data.automation.AmberAccessibilityService
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeishuOfficeEnhancementManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
    private val permissionBroker: AgentPermissionBroker,
    private val workspaceManager: WorkspaceManager,
    private val appScope: AppScope,
) {
    private val lastKnownTitle = MutableStateFlow<String?>(null)
    private val lastError = MutableStateFlow<String?>(null)
    private val refreshTick = MutableStateFlow(0L)

    val state = combine(settingsStore.settingsFlow, lastKnownTitle, lastError, refreshTick) { settings, title, error, _ ->
        buildState(settings.agentRuntime.feishuOfficeEnhancement, title, error)
    }.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = buildState(
            setting = settingsStore.settingsFlow.value.agentRuntime.feishuOfficeEnhancement,
            title = null,
            error = null,
        ),
    )

    fun setEnabled(enabled: Boolean) {
        appScope.launch { updateSetting { it.copy(enabled = enabled) } }
    }

    fun setTargetPackage(packageName: String) {
        appScope.launch {
            updateSetting { it.copy(targetPackage = packageName.trim().ifBlank { DEFAULT_FEISHU_OFFICE_PACKAGE }) }
        }
    }

    fun setDefaultTemplate(template: FeishuOfficeAnalysisTemplate) {
        appScope.launch { updateSetting { it.copy(defaultTemplate = template) } }
    }

    fun setIncludeNotificationsByDefault(enabled: Boolean) {
        appScope.launch {
            updateSetting {
                it.copy(
                    includeNotificationsByDefault = enabled,
                    workDashboard = it.workDashboard.copy(includeNotifications = enabled),
                )
            }
        }
    }

    fun setIncludeUsageByDefault(enabled: Boolean) {
        appScope.launch { updateSetting { it.copy(includeUsageByDefault = enabled) } }
    }

    fun setIncludeCurrentScreenByDefault(enabled: Boolean) {
        appScope.launch {
            updateSetting {
                it.copy(
                    includeCurrentScreenByDefault = enabled,
                    workDashboard = it.workDashboard.copy(includeCurrentScreen = enabled),
                )
            }
        }
    }

    fun setIncludeMcpHintsByDefault(enabled: Boolean) {
        appScope.launch {
            updateSetting {
                it.copy(
                    includeMcpHintsByDefault = enabled,
                    workDashboard = it.workDashboard.copy(includeMcpSources = enabled),
                )
            }
        }
    }

    fun setDefaultOutputDir(outputDir: String) {
        appScope.launch {
            updateSetting {
                val sanitized = sanitizeWorkspaceDir(outputDir)
                it.copy(
                    defaultOutputDir = sanitized,
                    workDashboard = it.workDashboard.copy(defaultOutputDir = sanitized),
                )
            }
        }
    }

    fun setWorkDashboardProjectKeywords(raw: String) {
        appScope.launch {
            updateSetting { setting ->
                setting.copy(
                    workDashboard = setting.workDashboard.copy(
                        defaultProjectKeywords = parseProjectKeywords(raw),
                    )
                )
            }
        }
    }

    fun setWorkDashboardIncludeModelCouncil(enabled: Boolean) {
        appScope.launch {
            updateSetting { setting ->
                setting.copy(workDashboard = setting.workDashboard.copy(includeModelCouncil = enabled))
            }
        }
    }

    fun setWorkDashboardIncludeMcpSources(enabled: Boolean) {
        appScope.launch {
            updateSetting { setting ->
                setting.copy(workDashboard = setting.workDashboard.copy(includeMcpSources = enabled))
            }
        }
    }

    fun updateProject(project: FeishuWorkProject) {
        appScope.launch { saveProject(project) }
    }

    suspend fun saveProject(project: FeishuWorkProject) {
        updateSetting { setting ->
            val currentProjects = setting.workDashboard.projects.ifEmpty { defaultFeishuWorkProjects() }
            val nextProjects = currentProjects
                .filterNot { it.id == project.id }
                .plus(project)
                .sortedBy { it.name }
                .take(24)
            setting.copy(
                workDashboard = setting.workDashboard.copy(
                    projects = nextProjects,
                    defaultProjectKeywords = nextProjects.flatMap { it.keywords }.distinct().take(24),
                )
            )
        }
    }

    fun restoreDefaultProjects() {
        appScope.launch {
            updateSetting { setting ->
                val defaults = defaultFeishuWorkProjects()
                val currentProjects = setting.workDashboard.projects
                val customProjects = currentProjects.filter { current ->
                    defaults.none { it.id == current.id }
                }
                val projects = defaults + customProjects
                setting.copy(
                    workDashboard = setting.workDashboard.copy(
                        projects = projects,
                        defaultProjectKeywords = projects.flatMap { it.keywords }.distinct().take(24),
                    )
                )
            }
        }
    }

    fun refresh() {
        refreshTick.value = System.currentTimeMillis()
    }

    suspend fun detectPackages(): List<FeishuOfficePackageCandidate> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val candidates = buildList {
            packageCandidate(DEFAULT_FEISHU_OFFICE_PACKAGE)?.let(::add)
            val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launchIntent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .forEach { packageName ->
                    if (looksLikeFeishuOffice(packageName, appLabel(packageName))) {
                        packageCandidate(packageName)?.let(::add)
                    }
                }
        }
        candidates.distinctBy { it.packageName }
            .sortedWith(compareByDescending<FeishuOfficePackageCandidate> { it.packageName == DEFAULT_FEISHU_OFFICE_PACKAGE }
                .thenBy { it.label.lowercase() })
    }

    fun chooseAndSaveBestPackage(candidates: List<FeishuOfficePackageCandidate>): FeishuOfficePackageCandidate? {
        val selected = FeishuOfficeEnhancementPlanner.chooseBestCandidate(candidates) ?: return null
        setTargetPackage(selected.packageName)
        return selected
    }

    fun openTargetApp(url: String? = null): Boolean {
        val targetPackage = state.value.targetPackage
        runCatching {
            val intent = if (!url.isNullOrBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    `package` = targetPackage
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(targetPackage)
                    ?: error("No launch intent for package: $targetPackage")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            lastError.value = it.message ?: it.toString()
            refresh()
            return false
        }
        lastError.value = null
        refresh()
        return true
    }

    fun readScreen(maxNodes: Int = 160): FeishuOfficeScreenSnapshot {
        val service = requireAccessibilityService()
        requireTargetAppInForeground(service)
        val uiTree = service.dumpUiTree(maxNodes.coerceIn(40, 260))
        val visibleText = FeishuOfficeEnhancementPlanner.extractVisibleText(uiTree)
        val title = FeishuOfficeEnhancementPlanner.guessTitle(uiTree)
        lastKnownTitle.value = title
        lastError.value = null
        refresh()
        return FeishuOfficeScreenSnapshot(
            titleGuess = title,
            visibleText = visibleText,
            uiTree = uiTree,
        )
    }

    private fun requireTargetAppInForeground(service: AmberAccessibilityService) {
        val activePackage = service.activePackageName()
        val targetPackage = state.value.targetPackage
        require(activePackage == targetPackage) {
            "out-of-target-app: current foreground package is ${activePackage ?: "unknown"}, expected $targetPackage"
        }
    }

    suspend fun openAndSearch(query: String): FeishuOfficeSearchResult {
        require(query.isNotBlank()) { "query must not be blank" }
        val opened = openTargetApp()
        if (!opened) {
            return FeishuOfficeSearchResult(
                status = "failed",
                message = lastError.value ?: "Unable to open target office app",
                searchBoxFound = false,
                textInjected = false,
            )
        }
        delay(900)
        val service = requireAccessibilityService()
        val searchMatch = SEARCH_LABELS
            .asSequence()
            .flatMap { label -> service.findTextNodes(label, maxNodes = 220).asSequence() }
            .firstOrNull()

        if (searchMatch == null) {
            lastError.value = "Search entry not found on current screen"
            return FeishuOfficeSearchResult(
                status = "needs_user_action",
                message = "Opened 小米办公 Pro, but no visible search entry was found. Ask the user to tap search, then call screen_input_text or officepro_read_screen.",
                searchBoxFound = false,
                textInjected = false,
            )
        }

        val tapped = service.tap(searchMatch.bounds.exactCenterX(), searchMatch.bounds.exactCenterY())
        delay(450)
        val textInjected = service.setFocusedText(query)
        lastKnownTitle.value = query
        lastError.value = null
        return FeishuOfficeSearchResult(
            status = if (tapped && textInjected) "search_ready" else "needs_user_action",
            message = if (tapped && textInjected) {
                "Search query entered. User should confirm the target document before reading or acting."
            } else {
                "Search entry was tapped, but text injection did not complete. User may need to focus the search field manually."
            },
            searchBoxFound = true,
            textInjected = textInjected,
        )
    }

    fun notificationSummaries(limit: Int = 20): List<FeishuOfficeNotificationSummary> {
        if (!state.value.notificationReady) return emptyList()
        val targetPackage = state.value.targetPackage
        return AmberNotificationListenerService.getActiveNotificationsSnapshot()
            .asSequence()
            .filter { it.packageName == targetPackage }
            .sortedByDescending { it.postTime }
            .take(limit.coerceIn(1, 50))
            .map { sbn ->
                val extras = sbn.notification.extras
                FeishuOfficeNotificationSummary(
                    postedAtMs = sbn.postTime,
                    title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().take(160),
                    text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty().take(240),
                )
            }
            .toList()
    }

    fun usageSummaries(limit: Int = 12): List<FeishuOfficeUsageSummary> {
        if (!state.value.usageReady) return emptyList()
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val end = System.currentTimeMillis()
        val start = end - 7L * 24L * 60L * 60L * 1000L
        val targetPackage = state.value.targetPackage
        return usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .asSequence()
            .filter { it.packageName == targetPackage && it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .take(limit.coerceIn(1, 30))
            .map { usage ->
                FeishuOfficeUsageSummary(
                    packageName = usage.packageName,
                    label = appLabel(usage.packageName),
                    lastTimeUsedMs = usage.lastTimeUsed,
                    totalTimeForegroundMs = usage.totalTimeInForeground,
                )
            }
            .toList()
    }

    suspend fun captureContext(
        workspacePaths: List<String> = emptyList(),
        includeCurrentScreen: Boolean = state.value.includeCurrentScreenByDefault,
        includeNotifications: Boolean = state.value.includeNotificationsByDefault,
        includeUsage: Boolean = state.value.includeUsageByDefault,
        includeMcpHints: Boolean = state.value.includeMcpHintsByDefault,
    ): FeishuOfficeContextBundle {
        val current = state.value
        var screenError: String? = null
        val screen = if (includeCurrentScreen) {
            runCatching { readScreen(maxNodes = 180) }
                .onFailure { screenError = it.message ?: it.toString() }
                .getOrNull()
        } else {
            null
        }
        return FeishuOfficeContextBundle(
            state = state.value,
            notifications = if (includeNotifications) notificationSummaries() else emptyList(),
            usageStats = if (includeUsage) usageSummaries() else emptyList(),
            screen = screen,
            workspaceSnippets = readWorkspaceSnippets(workspacePaths, current.maxWorkspaceDocs),
            screenError = screenError,
            mcpHints = if (includeMcpHints) FeishuOfficeEnhancementPlanner.defaultMcpHints() else emptyList(),
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    suspend fun dashboardSummary(): FeishuOfficeDashboardSummary =
        state.value.let { current ->
            FeishuOfficeEnhancementPlanner.buildDashboardSummary(
                captureContext(
                    workspacePaths = emptyList(),
                    includeCurrentScreen = current.enabled && current.includeCurrentScreenByDefault,
                    includeNotifications = current.enabled && current.includeNotificationsByDefault,
                    includeUsage = current.enabled && current.includeUsageByDefault,
                    includeMcpHints = current.includeMcpHintsByDefault,
                )
            )
        }

    suspend fun makeReport(
        template: FeishuOfficeAnalysisTemplate,
        workspacePaths: List<String>,
        title: String?,
        outputPath: String?,
        includeCurrentScreen: Boolean,
    ): FeishuOfficeReportResult {
        val current = state.value
        val reportTitle = title?.trim()?.takeIf { it.isNotBlank() }
            ?: "${template.zhLabel} - 飞书办公增强报告"
        val bundle = captureContext(
            workspacePaths = workspacePaths,
            includeCurrentScreen = includeCurrentScreen,
            includeNotifications = current.includeNotificationsByDefault,
            includeUsage = current.includeUsageByDefault,
            includeMcpHints = current.includeMcpHintsByDefault,
        )
        val digest = FeishuOfficeEnhancementPlanner.buildContextDigest(
            template = template,
            bundle = bundle,
            maxChars = current.maxReportChars,
        )
        val draft = FeishuOfficeEnhancementPlanner.buildReportMarkdown(
            title = reportTitle,
            template = template,
            digest = digest,
            capturedAtMs = bundle.capturedAtMs,
            maxChars = current.maxReportChars,
        )
        val path = outputPath?.takeIf { it.isNotBlank() }
            ?: defaultReportPath(current.defaultOutputDir, template, bundle.capturedAtMs)
        val entry = workspaceManager.writeText(path, draft.markdown)
        return FeishuOfficeReportResult(
            path = entry.path,
            title = reportTitle,
            template = template,
            truncated = draft.truncated,
            writtenAtMs = bundle.capturedAtMs,
            totalChars = draft.totalChars,
        )
    }

    private suspend fun updateSetting(block: (FeishuOfficeEnhancementSetting) -> FeishuOfficeEnhancementSetting) {
        settingsStore.update { settings ->
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(
                    feishuOfficeEnhancement = block(settings.agentRuntime.feishuOfficeEnhancement),
                )
            )
        }
    }

    private fun buildState(
        setting: FeishuOfficeEnhancementSetting,
        title: String?,
        error: String?,
    ): FeishuOfficeEnhancementState {
        val installed = packageInstalled(setting.targetPackage)
        val launchable = installed && context.packageManager.getLaunchIntentForPackage(setting.targetPackage) != null
        val accessibilityReady = AmberAccessibilityService.getActiveService() != null
        val notificationReady = permissionBroker.getStatus("notification_access") == AgentPermissionStatus.Granted
        val usageReady = permissionBroker.getStatus("usage_access") == AgentPermissionStatus.Granted
        return FeishuOfficeEnhancementState(
            enabled = setting.enabled,
            targetPackage = setting.targetPackage,
            defaultTemplate = setting.defaultTemplate,
            includeNotificationsByDefault = setting.includeNotificationsByDefault,
            includeUsageByDefault = setting.includeUsageByDefault,
            includeCurrentScreenByDefault = setting.includeCurrentScreenByDefault,
            includeMcpHintsByDefault = setting.includeMcpHintsByDefault,
            defaultOutputDir = setting.defaultOutputDir,
            maxWorkspaceDocs = setting.maxWorkspaceDocs,
            maxReportChars = setting.maxReportChars,
            installed = installed,
            launchable = launchable,
            label = if (installed) appLabel(setting.targetPackage) else null,
            accessibilityReady = accessibilityReady,
            notificationReady = notificationReady,
            usageReady = usageReady,
            capability = FeishuOfficeEnhancementPlanner.capability(
                enabled = setting.enabled,
                installed = installed,
                accessibilityReady = accessibilityReady,
                notificationReady = notificationReady,
                usageReady = usageReady,
            ),
            lastKnownTitle = title,
            lastError = error,
            updatedAtMs = System.currentTimeMillis(),
            workDashboard = setting.workDashboard,
        )
    }

    private fun packageCandidate(packageName: String): FeishuOfficePackageCandidate? {
        if (!packageInstalled(packageName)) return null
        return FeishuOfficePackageCandidate(
            packageName = packageName,
            label = appLabel(packageName),
            installed = true,
            launchable = context.packageManager.getLaunchIntentForPackage(packageName) != null,
        )
    }

    private fun packageInstalled(packageName: String): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    private fun appLabel(packageName: String): String =
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { packageName }

    private fun looksLikeFeishuOffice(packageName: String, label: String): Boolean {
        val haystack = "$packageName $label"
        return CANDIDATE_KEYWORDS.any { haystack.contains(it, ignoreCase = true) }
    }

    private fun requireAccessibilityService(): AmberAccessibilityService =
        AmberAccessibilityService.getActiveService()
            ?: error("AmberAgent Accessibility is not enabled. Enable Accessibility before reading 小米办公 Pro screens.")

    private suspend fun readWorkspaceSnippets(
        paths: List<String>,
        limit: Int,
    ): List<FeishuOfficeWorkspaceSnippet> =
        paths.take(limit.coerceIn(1, 12)).mapNotNull { path ->
            runCatching {
                val content = workspaceManager.readText(path)
                val maxChars = 5_000
                FeishuOfficeWorkspaceSnippet(
                    path = path,
                    content = content.take(maxChars),
                    totalChars = content.length,
                    truncated = content.length > maxChars,
                )
            }.getOrNull()
        }

    private fun defaultReportPath(
        outputDir: String,
        template: FeishuOfficeAnalysisTemplate,
        nowMs: Long,
    ): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "${sanitizeWorkspaceDir(outputDir)}/officepro-${template.wireName}-$timestamp.md"
    }

    private fun sanitizeWorkspaceDir(raw: String): String {
        val cleaned = raw.trim()
            .removePrefix("/workspace/")
            .removePrefix("workspace/")
            .trim('/')
            .replace('\\', '/')
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        return if (parts.isEmpty() || parts.any { it == "." || it == ".." }) {
            "officepro"
        } else {
            parts.joinToString("/")
        }
    }

    private fun parseProjectKeywords(raw: String): List<String> {
        val parsed = raw.split(',', '，', '\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        return parsed.ifEmpty { listOf("Q 代", "MiClaw", "Lhasa", "AI 办公") }
    }

    private companion object {
        val CANDIDATE_KEYWORDS = listOf("lark", "feishu", "飞书", "办公", "mioffice", "xiaomi")
        val SEARCH_LABELS = listOf("搜索", "搜索文档", "搜索联系人", "Search")
    }
}

data class FeishuOfficeSearchResult(
    val status: String,
    val message: String,
    val searchBoxFound: Boolean,
    val textInjected: Boolean,
)
