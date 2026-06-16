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
    }

    var body: some View {
        NavigationStack(path: Binding(get: { rootRouter.path }, set: { rootRouter.path = $0 })) {
            ConversationsView()
                .withAppDestinations(
                    settingsStore: settingsStore,
                    providerRegistry: providerRegistry,
                    sharedSettings: sharedSettings,
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
        .tint(AmberTheme.accent)
        .preferredColorScheme(preferredColorScheme)
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
    func rootView(settingsStore: SettingsStore, localToolExecutor: IOSLocalToolExecutor) -> some View {
        switch self {
        case .chat:
            ChatView(settingsStore: settingsStore, localToolExecutor: localToolExecutor)
        case .workspace:
            WorkspaceView()
        case .assistants:
            AssistantsView()
        case .settings:
            SettingsView(settingsStore: settingsStore)
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
    case search
    case account
    case settings
    case appearance
    case displayFont
    case conversationStorage
    case syncBackup
    case capabilities
    case agentsMd
    case memoryEdit(text: String, scope: String, pinned: Bool)
    case skills
    case skillAdd
    case mcpServers
    case mcpImport
    case mcpAdd
    case skillDetail(name: String)
    case execution
    case providers
    case providerAdd
    case providerDetail(name: String, endpoint: String, kind: ProviderRouteKind)
    case providerKeyEditor(name: String)
    case modelAdd
    case modelEdit
    case modelCustomHeaders
    case modelCustomBody
    case modelDefaults
    case searchServices
    case searchProvider
    case ttsSettings
    case ttsAdd
    case board
    case boardSettings
    case miniApps
    case miniAppSettings
    case miniAppRunner(title: String)
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
        permissionStore: IOSPermissionStore,
        documentStore: DocumentAccessStore,
        systemPermissionCoordinator: IOSSystemPermissionCoordinator,
        localToolExecutor: IOSLocalToolExecutor
    ) -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .chat:
                ChatView(settingsStore: settingsStore, localToolExecutor: localToolExecutor)
            case .search:
                SearchView()
            case .account:
                AccountView(sharedSettings: sharedSettings)
            case .settings:
                SettingsHomeView(settingsStore: settingsStore)
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
            case .agentsMd:
                AgentsMarkdownView()
            case .memoryEdit(let text, let scope, let pinned):
                MemoryEditView(initialText: text, initialScope: scope, initialPinned: pinned)
            case .skills:
                SkillsView()
            case .skillAdd:
                SkillAddView()
            case .mcpServers:
                McpServersView(sharedSettings: sharedSettings)
            case .mcpImport:
                McpImportView()
            case .mcpAdd:
                McpAddView()
            case .skillDetail(let name):
                SkillDetailView(skillName: name)
            case .execution:
                ExecutionSettingsView(sharedSettings: sharedSettings)
            case .providers:
                ProvidersView(settingsStore: settingsStore, providerRegistry: providerRegistry)
            case .providerAdd:
                ProviderAddView()
            case .providerDetail(let name, let endpoint, let kind):
                ProviderDetailView(settingsStore: settingsStore, providerRegistry: providerRegistry, providerName: name, endpoint: endpoint, providerKind: kind)
            case .providerKeyEditor(let name):
                ProviderKeyEditView(providerRegistry: providerRegistry, providerName: name)
            case .modelAdd:
                ModelEditView(isAdding: true)
            case .modelEdit:
                ModelEditView()
            case .modelCustomHeaders:
                ModelCustomFieldsView(kind: .headers)
            case .modelCustomBody:
                ModelCustomFieldsView(kind: .body)
            case .modelDefaults:
                ModelDefaultsView(settingsStore: settingsStore, sharedSettings: sharedSettings)
            case .searchServices:
                SearchServicesView(sharedSettings: sharedSettings)
            case .searchProvider:
                SearchProviderView()
            case .ttsSettings:
                TTSSettingsView(sharedSettings: sharedSettings)
            case .ttsAdd:
                TTSAddView()
            case .board:
                BoardView(sharedSettings: sharedSettings)
            case .boardSettings:
                BoardSettingsView(sharedSettings: sharedSettings)
            case .miniApps:
                MiniAppListView()
            case .miniAppSettings:
                MiniAppSettingsView(sharedSettings: sharedSettings)
            case .miniAppRunner(let title):
                MiniAppRunnerView(title: title)
            case .webMount:
                WebMountView()
            case .webMountSite(let site):
                WebMountSiteView(site: site)
            case .workspace:
                WorkspaceView()
            case .memory:
                MemoryOverviewView(sharedSettings: sharedSettings)
            case .council:
                CouncilView(sharedSettings: sharedSettings)
            case .councilSettings:
                CouncilSettingsView(sharedSettings: sharedSettings)
            case .seatEditor:
                SeatEditorView()
            case .subagents:
                SubAgentsView(sharedSettings: sharedSettings)
            case .subAgentRole(let name, let roleId):
                SubAgentRoleView(name: name, roleId: roleId)
            case .sandbox:
                RuntimeEnvironmentView(settingsStore: settingsStore)
            case .conversation(let id):
                PlaceholderDetailView(title: "Conversation", subtitle: id, systemImage: "text.bubble")
            case .assistant(let id):
                PlaceholderDetailView(title: "Assistant", subtitle: id, systemImage: "person.crop.circle")
            case .workspaceItem(let id):
                PlaceholderDetailView(title: "Workspace Item", subtitle: id, systemImage: "folder")
            case .settingsPlaceholder(let title, let subtitle, let systemImage):
                PlaceholderDetailView(title: title, subtitle: subtitle, systemImage: systemImage)
            case .providerSettings:
                SettingsView(settingsStore: settingsStore)
            case .toolPermissions:
                PermissionsApprovalView(permissionStore: permissionStore)
            }
        }
    }
}
