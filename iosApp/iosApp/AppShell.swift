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
    @State private var novelCreationViewModel: NovelCreationViewModel?
    @State private var novelLifecycleCoordinator: NovelWorkspaceLifecycleCoordinator
    @State private var novelCreationErrorMessage: String?
    @State private var rootRouter = RouterPath()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.system.rawValue

    init(settingsStore: SettingsStore) {
        let permissionStore = IOSPermissionStore()
        let documentAccessStore = DocumentAccessStore()
        let workspaceStore = IOSWorkspaceStore.shared
        let systemPermissionCoordinator = IOSSystemPermissionCoordinator()
        let sharedSettingsStore = IOSSharedSettingsStore()
        let conversationStore = IOSConversationStore()
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
            mcpManager: backgroundMcpManager
        )
        let chatViewModel = ChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettingsStore,
            localToolExecutor: localToolExecutor
        )
        let novelCreationViewModel: NovelCreationViewModel?
        let novelCreationErrorMessage: String?
        do {
            novelCreationViewModel = try NovelCreationComposition.makeViewModel(
                sharedSettings: sharedSettingsStore
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
        self._providerRegistry = State(initialValue: ProviderRegistryStore(settingsStore: settingsStore))
        self._sharedSettings = State(initialValue: sharedSettingsStore)
        self._mcpConfigStore = State(initialValue: IOSMcpConfigStore())
        self._conversationStore = State(initialValue: conversationStore)
        self._chatViewModel = State(initialValue: chatViewModel)
        self._novelCreationViewModel = State(initialValue: novelCreationViewModel)
        self._novelLifecycleCoordinator = State(
            initialValue: NovelWorkspaceLifecycleCoordinator()
        )
        self._novelCreationErrorMessage = State(initialValue: novelCreationErrorMessage)
        IOSDeepReadBackgroundCoordinator.shared.configure(sharedSettings: sharedSettingsStore)
        IOSChatBackgroundGenerationCoordinator.shared.configure(
            conversationStore: conversationStore,
            toolRuntime: backgroundToolRuntime,
            sharedSettings: sharedSettingsStore
        )
        IOSDeepReadRecoveryOnce.run()
        // [Slice 6] Load persisted memories (Documents/memories/memories.json)
        // into the IosMemoryFactory store before any view reads it. Missing or
        // corrupt file is a no-op (store keeps its seed/empty state).
        Task { @MainActor in IOSMemoryPersistence.shared.load() }
    }

    var body: some View {
        NavigationStack(path: Binding(get: { rootRouter.path }, set: { rootRouter.path = $0 })) {
            ConversationsView(sharedSettings: sharedSettings, chatViewModel: chatViewModel)
                .withAppDestinations(
                    settingsStore: settingsStore,
                    providerRegistry: providerRegistry,
                    sharedSettings: sharedSettings,
                    mcpConfigStore: mcpConfigStore,
                    permissionStore: permissionStore,
                    documentStore: documentAccessStore,
                    workspaceStore: workspaceStore,
                    systemPermissionCoordinator: systemPermissionCoordinator,
                    localToolExecutor: localToolExecutor,
                    chatViewModel: chatViewModel,
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
        .task {
            // 启动时引导会话存储：加载历史摘要，选最近一条或新建。
            // 仅做一次——SwiftUI .task 在 view identity 存活期间重启时会重跑，
            // 但 bootstrap 是幂等的（选最近一条 / 无历史新建）。
            await conversationStore.bootstrap()
            sharedSettings.repairCurrentChatModelIfNeeded(settingsStore)
        }
    }

    private var preferredColorScheme: ColorScheme? {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).colorScheme
    }

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        switch phase {
        case .background:
            _ = chatViewModel.handoffGenerationToBackgroundIfNeeded()
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
        case .inactive:
            break
        @unknown default:
            break
        }
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
        path.append(route)
    }

    func reset() {
        path = []
        presentedSheet = nil
    }
}

enum Route: Hashable {
    case chat
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
    case novelProject(id: NovelProjectID)
    case webMount
    case webMountSite(site: WebMountSiteRoute)
    case workspace
    case memory
    case council
    case councilChat
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
        mcpConfigStore: IOSMcpConfigStore,
        permissionStore: IOSPermissionStore,
        documentStore: DocumentAccessStore,
        workspaceStore: IOSWorkspaceStore,
        systemPermissionCoordinator: IOSSystemPermissionCoordinator,
        localToolExecutor: IOSLocalToolExecutor,
        chatViewModel: ChatViewModel,
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
                SyncBackupView(sharedSettings: sharedSettings)
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
                MiniAppRunnerView(appId: appId, settingsStore: settingsStore, sharedSettings: sharedSettings)
            case .novelCreation:
                if let novelCreationViewModel {
                    NovelProjectListView(
                        viewModel: novelCreationViewModel,
                        onOpen: { projectID in
                            router.navigate(to: .novelProject(id: projectID))
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
                    permissionStore: permissionStore
                )
            case .councilChat:
                CouncilChatRuntimeView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    providerRegistry: providerRegistry,
                    permissionStore: permissionStore
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
