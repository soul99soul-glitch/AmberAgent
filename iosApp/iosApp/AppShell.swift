import SwiftUI
import UIKit

@MainActor
struct AppShell: View {

    let settingsStore: SettingsStore

    @State private var selectedTab: AppTab = .chat
    @State private var tabRouter = TabRouter()
    @State private var permissionStore: IOSPermissionStore
    @State private var documentAccessStore: DocumentAccessStore
    @State private var workspaceStore: IOSWorkspaceStore
    @State private var systemPermissionCoordinator: IOSSystemPermissionCoordinator
    @State private var localToolExecutor: IOSLocalToolExecutor
    @State private var providerRegistry: ProviderRegistryStore
    @State private var sharedSettings: IOSSharedSettingsStore
    @State private var mcpConfigStore: IOSMcpConfigStore
    @State private var conversationStore: IOSConversationStore
    @State private var chatViewModel: ChatViewModel
    @State private var councilChatViewModel: CouncilChatViewModel
    @State private var novelCreationViewModel: NovelCreationViewModel?
    @State private var novelLifecycleCoordinator: NovelWorkspaceLifecycleCoordinator
    @State private var novelCreationErrorMessage: String?
    @State private var rootRouter = RouterPath()
    @State private var pendingAgentActivityTarget: AgentActivityDeepLink.Target?
    @State private var didBootstrapConversations = false
    @State private var didRunStartupRecovery = false
    @State private var didFinalizeStaleBackgroundJobs = false
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.system.rawValue

    init(settingsStore: SettingsStore) {
        let permissionStore = IOSPermissionStore()
        let documentAccessStore = DocumentAccessStore()
        let workspaceStore = IOSWorkspaceStore.shared
        let systemPermissionCoordinator = IOSSystemPermissionCoordinator()
        let sharedSettingsStore = IOSSharedSettingsStore()
        let conversationStore = IOSConversationStore()
        let providerRegistry = ProviderRegistryStore(settingsStore: settingsStore)
        let localToolExecutor = IOSLocalToolExecutor(
            permissionStore: permissionStore,
            documentStore: documentAccessStore,
            workspaceStore: workspaceStore,
            systemPermissionCoordinator: systemPermissionCoordinator
        )
        let backgroundMcpManager = IOSMcpManager(sharedSettings: sharedSettingsStore, configStore: .shared)
        let backgroundToolRuntime = ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettingsStore,
            localToolExecutor: localToolExecutor,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: backgroundMcpManager,
            conversationStoreProvider: { [weak conversationStore] in conversationStore }
        )
        let chatViewModel = ChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettingsStore,
            localToolExecutor: localToolExecutor
        )
        chatViewModel.conversationStore = conversationStore
        let councilChatViewModel = CouncilChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettingsStore,
            providerRegistry: providerRegistry,
            permissionStore: permissionStore
        )
        let novelCreationViewModel: NovelCreationViewModel?
        let novelCreationErrorMessage: String?
        do {
            novelCreationViewModel = try NovelCreationComposition.makeViewModel(
                sharedSettings: sharedSettingsStore,
                toolRuntime: backgroundToolRuntime
            )
            novelCreationErrorMessage = nil
        } catch {
            novelCreationViewModel = nil
            novelCreationErrorMessage = error.localizedDescription
        }
        self.settingsStore = settingsStore
        self._permissionStore = State(initialValue: permissionStore)
        self._documentAccessStore = State(initialValue: documentAccessStore)
        self._workspaceStore = State(initialValue: workspaceStore)
        self._systemPermissionCoordinator = State(initialValue: systemPermissionCoordinator)
        self._localToolExecutor = State(initialValue: localToolExecutor)
        self._providerRegistry = State(initialValue: providerRegistry)
        self._sharedSettings = State(initialValue: sharedSettingsStore)
        self._mcpConfigStore = State(initialValue: IOSMcpConfigStore())
        self._conversationStore = State(initialValue: conversationStore)
        self._chatViewModel = State(initialValue: chatViewModel)
        self._councilChatViewModel = State(initialValue: councilChatViewModel)
        self._novelCreationViewModel = State(initialValue: novelCreationViewModel)
        self._novelLifecycleCoordinator = State(
            initialValue: NovelWorkspaceLifecycleCoordinator()
        )
        self._novelCreationErrorMessage = State(initialValue: novelCreationErrorMessage)
        IOSDeepReadBackgroundCoordinator.shared.configure(sharedSettings: sharedSettingsStore)
        IOSChatBackgroundGenerationCoordinator.shared.configure(
            conversationStore: conversationStore,
            toolRuntime: backgroundToolRuntime,
            sharedSettings: sharedSettingsStore,
            saveMiniAppIfPresent: { [chatViewModel] messages, conversationId in
                chatViewModel.applyMiniAppOutputIfPresentPublic(
                    to: messages,
                    conversationId: conversationId
                )
            }
        )
        IOSDeepReadRecoveryOnce.run()
        // Load before ChatViewModel can prepare its first provider request.
        // AppShell is MainActor-isolated, so this synchronous file read cannot
        // race the first view render or an early memory mutation.
        IOSMemoryPersistence.shared.load()
    }

    var body: some View {
        NavigationStack(path: Binding(get: { rootRouter.path }, set: { rootRouter.path = $0 })) {
            ConversationsView(
                sharedSettings: sharedSettings,
                chatViewModel: chatViewModel,
                councilChatViewModel: councilChatViewModel,
                novelCreationViewModel: novelCreationViewModel
            )
                .withAppDestinations(
                    settingsStore: settingsStore,
                    providerRegistry: providerRegistry,
                    sharedSettings: sharedSettings,
                    conversationStore: conversationStore,
                    mcpConfigStore: mcpConfigStore,
                    permissionStore: permissionStore,
                    documentStore: documentAccessStore,
                    workspaceStore: workspaceStore,
                    systemPermissionCoordinator: systemPermissionCoordinator,
                    localToolExecutor: localToolExecutor,
                    chatViewModel: chatViewModel,
                    councilChatViewModel: councilChatViewModel,
                    novelCreationViewModel: novelCreationViewModel,
                    novelCreationErrorMessage: novelCreationErrorMessage,
                    router: rootRouter
                )
        }
        .sheet(item: Binding(get: { rootRouter.presentedSheet }, set: { rootRouter.presentedSheet = $0 })) { sheet in
            sheetView(sheet)
        }
        .environment(rootRouter)
        .environment(conversationStore)
        .environment(chatViewModel)
        .environment(documentAccessStore)
        .environment(workspaceStore)
        .tint(AmberTheme.accent)
        .preferredColorScheme(preferredColorScheme)
        .onChange(of: scenePhase) { _, phase in
            handleScenePhaseChange(phase)
        }
        .onReceive(NotificationCenter.default.publisher(for: .amberWatchOpenTask)) { note in
            guard let runId = note.userInfo?["runId"] as? String,
                  let conversationId = note.userInfo?["conversationId"] as? String else { return }
            let focusRaw = note.userInfo?["focus"] as? String ?? "task"
            let focus = AgentActivityDeepLink.Focus(rawValue: focusRaw) ?? .task
            pendingAgentActivityTarget = AgentActivityDeepLink.Target(
                runId: runId,
                conversationId: conversationId,
                focus: focus
            )
            Task { await openPendingAgentActivityIfReady() }
        }
        .task {
            // 启动时引导会话存储：加载历史摘要，选最近一条或新建。
            // run recovery 不是幂等操作；在任务的第一个 await 前占位，避免 .task
            // 重启时把本进程正在运行的前台 run 改写为 interrupted。
            if !didRunStartupRecovery {
                didRunStartupRecovery = true
                let interruptedCouncilTaskIds = IOSAdvancedTaskStore.shared.markInterruptedCouncilTasks()
                CouncilRoomArchiveStore.shared.markInterrupted(taskIds: interruptedCouncilTaskIds)
                councilChatViewModel.recoverInterruptedTasks(interruptedCouncilTaskIds)
                let backgroundRunIds = IOSChatBackgroundGenerationCoordinator.shared.restorableRunIds
                let recoveredPendingApprovals = await IOSRunRecovery.recoverPendingApprovalDescriptors(
                    excludingRunIds: backgroundRunIds
                )
                await conversationStore.bootstrap()
                IOSBuiltinSkills.installIfMissing(enableWith: sharedSettings)
                await chatViewModel.reconcilePendingMiniAppMutationsAfterConversationBootstrap()
                didBootstrapConversations = true

                // A failed approval-owner query makes it unsafe to classify any
                // unfinished run: an awaiting approval could otherwise be swept
                // as an ordinary interruption with its tool output still empty.
                if let recoveredPendingApprovals {
                    await chatViewModel.terminateRecoveredPendingApprovals(recoveredPendingApprovals)
                    let pendingApprovalRunIds = Set(recoveredPendingApprovals.map(\.runId))
                    let excludedFromInterrupted = backgroundRunIds.union(pendingApprovalRunIds)
                    if let interruptedRunConversationPairs = await IOSRunRecovery.unfinishedRunConversationPairs(
                        excludingRunIds: excludedFromInterrupted
                    ) {
                        let reconciledRunIds = await chatViewModel.applyToolCallLedgerRecovery(
                            forInterruptedRuns: interruptedRunConversationPairs
                        )
                        let unreconciledRunIds = Set(interruptedRunConversationPairs.map(\.runId))
                            .subtracting(reconciledRunIds)
                        _ = await IOSRunRecovery.recoverInterruptedRuns(
                            excludingRunIds: excludedFromInterrupted.union(unreconciledRunIds)
                        )
                    }
                }
                AgentLiveActivityController.shared.restoreExistingActivity(
                    ownedRunIds: backgroundRunIds
                )
                // 冷启动路径：收口旧版本遗留的自动恢复记录，避免用户已经停止的
                // 系统后台活动在升级后再次启动。
                IOSChatBackgroundGenerationCoordinator.shared.finalizeSuspendedRunsIfNeeded()
                finalizeStaleBackgroundJobsIfNeeded()
                if scenePhase == .active, let novelCreationViewModel {
                    await novelCreationViewModel.resumeDetachedBackgroundGeneration()
                }
            } else {
                await conversationStore.bootstrap()
                didBootstrapConversations = true
            }
            sharedSettings.repairCurrentChatModelIfNeeded(settingsStore)
            WatchTaskCoordinator.shared.attach(
                chatViewModel: chatViewModel,
                reconnecting: IOSChatBackgroundGenerationCoordinator.shared.reconnectingWatchProjection
            )
            await openPendingAgentActivityIfReady()
        }
        .onOpenURL { url in
            enqueueAgentActivityURL(url)
        }
    }

    private var preferredColorScheme: ColorScheme? {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).colorScheme
    }

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        IOSBackgroundLifecycleLog.record(
            "scenePhase=\(String(describing: phase))",
            detail: IOSChatBackgroundGenerationCoordinator.shared.lifecycleSnapshotDetail
        )
        switch phase {
        case .background:
            councilChatViewModel.runtimeWillEnterBackground()
            // 握着后台执行权就别交接，让正在跑的流自己跑完。
            _ = chatViewModel.handoffGenerationToBackgroundIfNeeded(honorKeepAliveLease: true)
            guard let novelCreationViewModel else { return }
            novelLifecycleCoordinator.enterBackground(
                waitForCompletion: {
                    await novelCreationViewModel.waitForBackgroundGeneration()
                },
                interrupt: { deadline in
                    await novelCreationViewModel.interruptSessionForBackground(deadline: deadline)
                }
            )
        case .active:
            novelLifecycleCoordinator.enterForeground()
            if let novelCreationViewModel {
                Task {
                    await novelCreationViewModel.resumeDetachedBackgroundGeneration()
                }
            }
            IOSChatBackgroundGenerationCoordinator.shared.finalizeSuspendedRunsIfNeeded()
            finalizeStaleBackgroundJobsIfNeeded()
        case .inactive:
            break
        @unknown default:
            break
        }
    }

    /// App Switcher 强制关闭不会触发 continued-processing 的到期回调。
    /// 只在本进程首次进入前台时扫一次；系统为真实后台任务唤起 App 时
    /// scene 仍在 background，不会被这里误判成 stale。
    private func finalizeStaleBackgroundJobsIfNeeded() {
        guard scenePhase == .active, !didFinalizeStaleBackgroundJobs else { return }
        didFinalizeStaleBackgroundJobs = true
        IOSChatBackgroundGenerationCoordinator.shared.finalizeStalePersistedJobsIfNeeded()
    }

    private func enqueueAgentActivityURL(_ url: URL) {
        guard let target = AgentActivityDeepLink.parse(url) else { return }
        pendingAgentActivityTarget = target
        Task { await openPendingAgentActivityIfReady() }
    }

    private func openPendingAgentActivityIfReady() async {
        guard didBootstrapConversations,
              let target = pendingAgentActivityTarget else { return }
        let conversationSelectionRevision = conversationStore.conversationSwitchedRevision

        guard let summary = conversationStore.summaries.first(where: {
            $0.id.toHexDashString().caseInsensitiveCompare(target.conversationId) == .orderedSame
        }) else { return }
        let ownsActivity = AgentLiveActivityController.shared.ownsActivity(
            runId: target.runId,
            conversationId: target.conversationId
        )
        let ownsRecordedRun = ownsActivity ? true : await chatViewModel
            .recordedAgentRunBelongsToConversation(
                runId: target.runId,
                conversationId: target.conversationId
            )
        guard pendingAgentActivityTarget == target else { return }
        guard conversationStore.conversationSwitchedRevision == conversationSelectionRevision else { return }
        guard ownsRecordedRun else { return }
        if target.focus == .confirmation {
            guard chatViewModel.canOpenActivityConfirmation(runId: target.runId) else { return }
        }
        guard chatViewModel.prepareForConversationChange(to: summary.id) else { return }

        if conversationStore.currentConversation?.id != summary.id {
            guard await conversationStore.selectConversationIfAvailable(
                id: summary.id,
                commitIf: {
                    pendingAgentActivityTarget == target &&
                    conversationStore.conversationSwitchedRevision == conversationSelectionRevision
                }
            ) else { return }
        }
        guard pendingAgentActivityTarget == target else { return }
        guard conversationStore.currentConversation?.id == summary.id else { return }
        rootRouter.path = [.chat]
        pendingAgentActivityTarget = nil
    }

    @ViewBuilder
    private func sheetView(_ sheet: SheetDestination) -> some View {
        switch sheet {
        case .modelPicker:
            NavigationStack {
                PlaceholderListView(
                    title: "Models",
                    systemImage: "cpu",
                    rows: [
                        "Provider families",
                        "Capability filters",
                        "Context and reasoning presets"
                    ]
                )
            }
        case .toolPermissions:
            NavigationStack {
                ToolPermissionsView(
                    permissionStore: permissionStore,
                    documentStore: documentAccessStore,
                    systemPermissionCoordinator: systemPermissionCoordinator,
                    localToolExecutor: localToolExecutor
                )
            }
        }
    }
}

enum AppTab: String, CaseIterable, Identifiable {
    case chat
    case workspace
    case assistants
    case settings

    var id: String { rawValue }

    @MainActor
    @ViewBuilder
    func rootView(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        localToolExecutor: IOSLocalToolExecutor,
        documentStore: DocumentAccessStore? = nil,
        workspaceStore: IOSWorkspaceStore = .shared,
        chatViewModel: ChatViewModel? = nil
    ) -> some View {
        switch self {
        case .chat:
            ChatView(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: localToolExecutor,
                documentStore: documentStore,
                workspaceStore: workspaceStore,
                viewModel: chatViewModel
            )
        case .workspace:
            if sharedSettings.isCapabilityGateEnabled(.workspace) {
                WorkspaceView(workspaceStore: workspaceStore)
            } else {
                CapabilityGateLockedView(gate: .workspace)
            }
        case .assistants:
            if sharedSettings.isCapabilityGateEnabled(.workspace) {
                AssistantsView()
            } else {
                CapabilityGateLockedView(gate: .workspace)
            }
        case .settings:
            SettingsHomeView(settingsStore: settingsStore, sharedSettings: sharedSettings)
        }
    }

    @ViewBuilder
    var label: some View {
        switch self {
        case .chat:
            Label("Chat", systemImage: "bubble.left.and.bubble.right")
        case .workspace:
            Label("Workspace", systemImage: "square.grid.2x2")
        case .assistants:
            Label("Amber", systemImage: "sparkles")
        case .settings:
            Label("Settings", systemImage: "gearshape")
        }
    }
}

@MainActor
@Observable
final class TabRouter {
    private var routers: [AppTab: RouterPath] = [:]

    func router(for tab: AppTab) -> RouterPath {
        if let router = routers[tab] {
            return router
        }
        let router = RouterPath()
        routers[tab] = router
        return router
    }

    func binding(for tab: AppTab) -> Binding<[Route]> {
        let router = router(for: tab)
        return Binding(
            get: { router.path },
            set: { router.path = $0 }
        )
    }

    func sheetBinding(for tab: AppTab) -> Binding<SheetDestination?> {
        let router = router(for: tab)
        return Binding(
            get: { router.presentedSheet },
            set: { router.presentedSheet = $0 }
        )
    }
}

@MainActor
@Observable
final class RouterPath {
    var path: [Route] = []
    var presentedSheet: SheetDestination?

    func navigate(to route: Route) {
        // 防止快速连点堆叠重复页面
        guard path.last != route else { return }
        path.append(route)
    }

    func goBack() {
        guard !path.isEmpty else { return }
        path.removeLast()
    }

    func reset() {
        path = []
        presentedSheet = nil
    }
}

enum Route: Hashable {
    case chat
    case chatMessage(anchor: ChatMessageAnchor)
    case search(initialQuery: String)
    case account
    case settings
    case appearance
    case displayFont
    case conversationStorage
    case syncBackup
    case capabilities
    case memoryEdit(recordId: Int?, text: String, scope: String, pinned: Bool)
    case skills
    case skillAdd
    case mcpServers
    case mcpImport
    case mcpAdd
    case skillDetail(name: String, dirName: String?)
    case execution
    case providers
    case providerAdd
    case providerDetail(id: String)
    case providerKeyEditor(name: String)
    case modelDefaults
    case searchServices
    case searchProvider
    case ttsSettings
    case board
    case boardSettings
    case deepReadTask(id: String)
    case miniApps
    case miniAppSettings
    case miniAppRunner(appId: String)
    case novelCreation
    case novelCreationSettings
    case novelProject(id: NovelProjectID)
    case webMount
    case webMountSite(site: WebMountSiteRoute)
    case workspace
    case memory
    case council
    case councilSettings
    case seatEditor
    case subagents
    case subAgentRole(name: String, roleId: String)
    case sandbox
    case conversation(id: String)
    case assistant(id: String)
    case workspaceItem(id: String)
    case settingsPlaceholder(title: String, subtitle: String, systemImage: String)
    case providerSettings
    case toolPermissions
    case promptInjection
}

enum SheetDestination: Identifiable, Hashable {
    case modelPicker
    case toolPermissions

    var id: String {
        switch self {
        case .modelPicker: "model-picker"
        case .toolPermissions: "tool-permissions"
        }
    }
}

private extension View {
    func withAppDestinations(
        settingsStore: SettingsStore,
        providerRegistry: ProviderRegistryStore,
        sharedSettings: IOSSharedSettingsStore,
        conversationStore: IOSConversationStore,
        mcpConfigStore: IOSMcpConfigStore,
        permissionStore: IOSPermissionStore,
        documentStore: DocumentAccessStore,
        workspaceStore: IOSWorkspaceStore,
        systemPermissionCoordinator: IOSSystemPermissionCoordinator,
        localToolExecutor: IOSLocalToolExecutor,
        chatViewModel: ChatViewModel,
        councilChatViewModel: CouncilChatViewModel,
        novelCreationViewModel: NovelCreationViewModel?,
        novelCreationErrorMessage: String?,
        router: RouterPath
    ) -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .chat:
                ChatView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    localToolExecutor: localToolExecutor,
                    documentStore: documentStore,
                    workspaceStore: workspaceStore,
                    viewModel: chatViewModel
                )
            case .chatMessage(let anchor):
                ChatView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    localToolExecutor: localToolExecutor,
                    documentStore: documentStore,
                    workspaceStore: workspaceStore,
                    viewModel: chatViewModel,
                    initialMessageAnchor: anchor
                )
            case .search(let initialQuery):
                if sharedSettings.isCapabilityGateEnabled(.standaloneSearch) {
                    SearchView(initialQuery: initialQuery)
                } else {
                    CapabilityGateLockedView(gate: .standaloneSearch)
                }
            case .account:
                AccountView(sharedSettings: sharedSettings)
            case .settings:
                SettingsHomeView(settingsStore: settingsStore, sharedSettings: sharedSettings)
            case .appearance:
                AppearanceSettingsView()
            case .displayFont:
                DisplayFontSettingsView(sharedSettings: sharedSettings)
            case .conversationStorage:
                ConversationStorageView(sharedSettings: sharedSettings)
            case .syncBackup:
                SyncBackupView(
                    sharedSettings: sharedSettings,
                    conversationStore: conversationStore
                )
            case .capabilities:
                ToolPermissionsView(
                    permissionStore: permissionStore,
                    documentStore: documentStore,
                    systemPermissionCoordinator: systemPermissionCoordinator,
                    localToolExecutor: localToolExecutor
                )
            case .memoryEdit(let recordId, let text, let scope, let pinned):
                MemoryEditView(recordId: recordId, initialText: text, initialScope: scope, initialPinned: pinned)
            case .skills:
                if sharedSettings.isCapabilityGateEnabled(.skills) {
                    SkillsView(sharedSettings: sharedSettings)
                } else {
                    CapabilityGateLockedView(gate: .skills)
                }
            case .skillAdd:
                if sharedSettings.isCapabilityGateEnabled(.skills) {
                    SkillAddView(sharedSettings: sharedSettings)
                } else {
                    CapabilityGateLockedView(gate: .skills)
                }
            case .mcpServers:
                if sharedSettings.isCapabilityGateEnabled(.mcp) {
                    McpServersView(sharedSettings: sharedSettings, configStore: mcpConfigStore)
                } else {
                    CapabilityGateLockedView(gate: .mcp)
                }
            case .mcpImport:
                if sharedSettings.isCapabilityGateEnabled(.mcp) {
                    McpImportView(configStore: mcpConfigStore)
                } else {
                    CapabilityGateLockedView(gate: .mcp)
                }
            case .mcpAdd:
                if sharedSettings.isCapabilityGateEnabled(.mcp) {
                    McpAddView(configStore: mcpConfigStore)
                } else {
                    CapabilityGateLockedView(gate: .mcp)
                }
            case .skillDetail(let name, let dirName):
                if sharedSettings.isCapabilityGateEnabled(.skills) {
                    SkillDetailView(sharedSettings: sharedSettings, skillName: name, dirName: dirName)
                } else {
                    CapabilityGateLockedView(gate: .skills)
                }
            case .execution:
                ExecutionSettingsView(sharedSettings: sharedSettings)
            case .providers:
                ProvidersView(settingsStore: settingsStore, providerRegistry: providerRegistry, sharedSettings: sharedSettings)
            case .providerAdd:
                ProviderAddView(settingsStore: settingsStore, providerRegistry: providerRegistry, sharedSettings: sharedSettings)
            case .providerDetail(let id):
                ProviderDetailView(settingsStore: settingsStore, providerRegistry: providerRegistry, sharedSettings: sharedSettings, providerId: id)
            case .providerKeyEditor(let name):
                ProviderKeyEditView(providerRegistry: providerRegistry, sharedSettings: sharedSettings, providerName: name)
            case .modelDefaults:
                ModelDefaultsView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry
                )
            case .searchServices:
                SearchServicesView(sharedSettings: sharedSettings)
            case .searchProvider:
                SearchProviderView(sharedSettings: sharedSettings)
            case .ttsSettings:
                TTSSettingsView(sharedSettings: sharedSettings)
            case .board:
                BoardView(settingsStore: settingsStore, sharedSettings: sharedSettings, providerRegistry: providerRegistry)
            case .boardSettings:
                BoardSettingsView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry
                )
            case .deepReadTask(let id):
                IOSDeepReadTaskDetailView(
                    taskId: id,
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry
                )
            case .miniApps:
                MiniAppListView()
            case .miniAppSettings:
                MiniAppSettingsView(sharedSettings: sharedSettings)
            case .miniAppRunner(let appId):
                MiniAppRunnerView(
                    appId: appId,
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    chatViewModel: chatViewModel
                )
            case .novelCreation:
                if let novelCreationViewModel {
                    NovelProjectListView(
                        viewModel: novelCreationViewModel,
                        onOpen: { projectID in
                            router.navigate(to: .novelProject(id: projectID))
                        },
                        onOpenSettings: {
                            router.navigate(to: .novelCreationSettings)
                        }
                    )
                    .novelCreationErrorAlert(viewModel: novelCreationViewModel)
                } else {
                    ContentUnavailableView(
                        "小说创作暂不可用",
                        systemImage: "exclamationmark.triangle",
                        description: Text(novelCreationErrorMessage ?? "无法打开项目存储。")
                    )
                }
            case .novelCreationSettings:
                if let novelCreationViewModel {
                    NovelCreationSettingsView(
                        sharedSettings: sharedSettings,
                        viewModel: novelCreationViewModel
                    )
                    .novelCreationErrorAlert(viewModel: novelCreationViewModel)
                } else {
                    NovelCreationSettingsView(
                        sharedSettings: sharedSettings,
                        viewModel: nil
                    )
                }
            case .novelProject(let projectID):
                if let novelCreationViewModel {
                    NovelProjectWorkspaceView(
                        viewModel: novelCreationViewModel,
                        sharedSettings: sharedSettings,
                        projectID: projectID
                    )
                    .novelCreationErrorAlert(viewModel: novelCreationViewModel)
                } else {
                    ContentUnavailableView(
                        "小说创作暂不可用",
                        systemImage: "exclamationmark.triangle",
                        description: Text(novelCreationErrorMessage ?? "无法打开项目存储。")
                    )
                }
            case .webMount:
                WebMountView()
            case .webMountSite(let site):
                WebMountSiteView(site: site)
            case .workspace:
                if sharedSettings.isCapabilityGateEnabled(.workspace) {
                    WorkspaceView(workspaceStore: workspaceStore)
                } else {
                    CapabilityGateLockedView(gate: .workspace)
                }
            case .memory:
                MemoryOverviewView(sharedSettings: sharedSettings)
            case .council:
                // 议会入口直接进 room 房间（CouncilChatRuntimeView 在
                // 70dc5b309 已是完整议会体验：成员、设置、讨论流都在房间内）。
                // 跳过旧的 CouncilView 中间页（说明 + 单按钮 + 历史）。
                CouncilChatRuntimeView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry,
                    permissionStore: permissionStore,
                    viewModel: councilChatViewModel
                )
            case .councilSettings:
                CouncilSettingsView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry
                )
            case .seatEditor:
                SeatEditorView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry
                )
            case .subagents:
                SubAgentsView(sharedSettings: sharedSettings)
            case .subAgentRole(let name, let roleId):
                SubAgentRoleView(sharedSettings: sharedSettings, name: name, roleId: roleId)
            case .sandbox:
                if sharedSettings.isCapabilityGateEnabled(.remoteRuntime) {
                    RuntimeEnvironmentView(settingsStore: settingsStore, sharedSettings: sharedSettings)
                } else {
                    CapabilityGateLockedView(gate: .remoteRuntime)
                }
            case .conversation(let id):
                PlaceholderDetailView(title: "Conversation", subtitle: id, systemImage: "text.bubble")
            case .assistant:
                PlaceholderDetailView(
                    title: "Amber Assistant",
                    subtitle: "iOS 只保留一个 Amber Assistant。",
                    systemImage: "sparkles"
                )
            case .workspaceItem(let id):
                if sharedSettings.isCapabilityGateEnabled(.workspace) {
                    WorkspaceView(workspaceStore: workspaceStore, focusedItemId: id)
                } else {
                    CapabilityGateLockedView(gate: .workspace)
                }
            case .settingsPlaceholder(let title, let subtitle, let systemImage):
                PlaceholderDetailView(title: title, subtitle: subtitle, systemImage: systemImage)
            case .providerSettings:
                if sharedSettings.isCapabilityGateEnabled(.remoteRuntime) {
                    SettingsView(settingsStore: settingsStore, sharedSettings: sharedSettings)
                } else {
                    CapabilityGateLockedView(gate: .remoteRuntime)
                }
            case .toolPermissions:
                PermissionsApprovalView(permissionStore: permissionStore)
            case .promptInjection:
                PromptInjectionEditorView(sharedSettings: sharedSettings)
            }
        }
    }
}

// Re-enable iOS's interactive pop (edge-swipe-to-go-back) gesture app-wide. NavigationStack pages
// that hide the nav bar / use a custom back button (`navigationBarBackButtonHidden(true)` +
// `toolbar(.hidden, for: .navigationBar)`) otherwise lose the default swipe-back, leaving only the
// top-left button. We become the gesture's delegate and only allow it when there is a page to pop,
// so the root view is unaffected and no horizontal content gestures are hijacked.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
