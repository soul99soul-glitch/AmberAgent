import SwiftUI

@MainActor
struct AppShell: View {

    let settingsStore: SettingsStore

    @State private var selectedTab: AppTab = .chat
    @State private var tabRouter = TabRouter()
    @State private var permissionStore: IOSPermissionStore
    @State private var documentAccessStore: DocumentAccessStore
    @State private var systemPermissionCoordinator: IOSSystemPermissionCoordinator
    @State private var localToolExecutor: IOSLocalToolExecutor
    @State private var providerRegistry: ProviderRegistryStore
    @State private var sharedSettings: IOSSharedSettingsStore
    @State private var mcpConfigStore: IOSMcpConfigStore
    @State private var conversationStore: IOSConversationStore
    @State private var rootRouter = RouterPath()
    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = "light"

    init(settingsStore: SettingsStore) {
        let permissionStore = IOSPermissionStore()
        let documentAccessStore = DocumentAccessStore()
        let systemPermissionCoordinator = IOSSystemPermissionCoordinator()
        self.settingsStore = settingsStore
        self._permissionStore = State(initialValue: permissionStore)
        self._documentAccessStore = State(initialValue: documentAccessStore)
        self._systemPermissionCoordinator = State(initialValue: systemPermissionCoordinator)
        self._localToolExecutor = State(
            initialValue: IOSLocalToolExecutor(
                permissionStore: permissionStore,
                documentStore: documentAccessStore,
                systemPermissionCoordinator: systemPermissionCoordinator
            )
        )
        self._providerRegistry = State(initialValue: ProviderRegistryStore(settingsStore: settingsStore))
        self._sharedSettings = State(initialValue: IOSSharedSettingsStore())
        self._mcpConfigStore = State(initialValue: IOSMcpConfigStore())
        self._conversationStore = State(initialValue: IOSConversationStore())
        // [Slice 6] Load persisted memories (Documents/memories/memories.json)
        // into the IosMemoryFactory store before any view reads it. Missing or
        // corrupt file is a no-op (store keeps its seed/empty state).
        Task { @MainActor in IOSMemoryPersistence.shared.load() }
    }

    var body: some View {
        NavigationStack(path: Binding(get: { rootRouter.path }, set: { rootRouter.path = $0 })) {
            ConversationsView(sharedSettings: sharedSettings)
                .withAppDestinations(
                    settingsStore: settingsStore,
                    providerRegistry: providerRegistry,
                    sharedSettings: sharedSettings,
                    mcpConfigStore: mcpConfigStore,
                    permissionStore: permissionStore,
                    documentStore: documentAccessStore,
                    systemPermissionCoordinator: systemPermissionCoordinator,
                    localToolExecutor: localToolExecutor
                )
        }
        .sheet(item: Binding(get: { rootRouter.presentedSheet }, set: { rootRouter.presentedSheet = $0 })) { sheet in
            sheetView(sheet)
        }
        .environment(rootRouter)
        .environment(conversationStore)
        .tint(AmberTheme.accent)
        .preferredColorScheme(preferredColorScheme)
        .task {
            // 启动时引导会话存储：加载历史摘要，选最近一条或新建。
            // 仅做一次——SwiftUI .task 在 view identity 存活期间重启时会重跑，
            // 但 bootstrap 是幂等的（选最近一条 / 无历史新建）。
            await conversationStore.bootstrap()
        }
    }

    private var preferredColorScheme: ColorScheme? {
        (IOSAppearanceMode(rawValue: appearanceMode) ?? .light).colorScheme
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
        documentStore: DocumentAccessStore? = nil
    ) -> some View {
        switch self {
        case .chat:
            ChatView(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: localToolExecutor,
                documentStore: documentStore
            )
        case .workspace:
            if sharedSettings.isCapabilityGateEnabled(.workspace) {
                WorkspaceView()
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
            Label("Assistants", systemImage: "person.2")
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
    case memoryEdit(text: String, scope: String, pinned: Bool)
    case skills
    case skillAdd
    case mcpServers
    case mcpImport
    case mcpAdd
    case skillDetail(name: String, dirName: String?)
    case execution
    case providers
    case providerAdd
    case providerDetail(name: String, endpoint: String, kind: ProviderRouteKind)
    case providerKeyEditor(name: String)
    case modelDefaults
    case searchServices
    case searchProvider
    case ttsSettings
    case board
    case boardSettings
    case miniApps
    case miniAppSettings
    case miniAppRunner(appId: String)
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
        systemPermissionCoordinator: IOSSystemPermissionCoordinator,
        localToolExecutor: IOSLocalToolExecutor
    ) -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .chat:
                ChatView(
                    settingsStore: settingsStore,
                    sharedSettings: sharedSettings,
                    localToolExecutor: localToolExecutor,
                    documentStore: documentStore
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
            case .memoryEdit(let text, let scope, let pinned):
                MemoryEditView(initialText: text, initialScope: scope, initialPinned: pinned)
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
                ProvidersView(settingsStore: settingsStore, providerRegistry: providerRegistry)
            case .providerAdd:
                ProviderAddView(providerRegistry: providerRegistry)
            case .providerDetail(let name, let endpoint, let kind):
                ProviderDetailView(settingsStore: settingsStore, providerRegistry: providerRegistry, providerName: name, endpoint: endpoint, providerKind: kind)
            case .providerKeyEditor(let name):
                ProviderKeyEditView(providerRegistry: providerRegistry, providerName: name)
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
                BoardView(settingsStore: settingsStore, sharedSettings: sharedSettings)
            case .boardSettings:
                BoardSettingsView(sharedSettings: sharedSettings)
            case .miniApps:
                MiniAppListView()
            case .miniAppSettings:
                MiniAppSettingsView(sharedSettings: sharedSettings)
            case .miniAppRunner(let appId):
                MiniAppRunnerView(appId: appId, settingsStore: settingsStore, sharedSettings: sharedSettings)
            case .webMount:
                WebMountView()
            case .webMountSite(let site):
                WebMountSiteView(site: site)
            case .workspace:
                if sharedSettings.isCapabilityGateEnabled(.workspace) {
                    WorkspaceView()
                } else {
                    CapabilityGateLockedView(gate: .workspace)
                }
            case .memory:
                MemoryOverviewView(sharedSettings: sharedSettings)
            case .council:
                CouncilView(sharedSettings: sharedSettings)
            case .councilChat:
                CouncilChatRuntimeView(settingsStore: settingsStore, sharedSettings: sharedSettings)
            case .councilSettings:
                CouncilSettingsView(sharedSettings: sharedSettings)
            case .seatEditor:
                SeatEditorView(sharedSettings: sharedSettings)
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
            case .assistant(let id):
                if sharedSettings.isCapabilityGateEnabled(.workspace) {
                    PlaceholderDetailView(title: "Assistant", subtitle: id, systemImage: "person.crop.circle")
                } else {
                    CapabilityGateLockedView(gate: .workspace)
                }
            case .workspaceItem(let id):
                if sharedSettings.isCapabilityGateEnabled(.workspace) {
                    PlaceholderDetailView(title: "Workspace Item", subtitle: id, systemImage: "folder")
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
            }
        }
    }
}
