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
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            ForEach(AppTab.allCases) { tab in
                NavigationStack(path: tabRouter.binding(for: tab)) {
                    tab.rootView(
                        settingsStore: settingsStore,
                        localToolExecutor: localToolExecutor
                    )
                        .withAppDestinations(
                            permissionStore: permissionStore,
                            documentStore: documentAccessStore,
                            systemPermissionCoordinator: systemPermissionCoordinator,
                            localToolExecutor: localToolExecutor
                        )
                }
                .sheet(item: tabRouter.sheetBinding(for: tab)) { sheet in
                    sheetView(sheet)
                }
                .environment(tabRouter.router(for: tab))
                .tabItem { tab.label }
                .tag(tab)
            }
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
    case conversation(id: String)
    case assistant(id: String)
    case workspaceItem(id: String)
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
        permissionStore: IOSPermissionStore,
        documentStore: DocumentAccessStore,
        systemPermissionCoordinator: IOSSystemPermissionCoordinator,
        localToolExecutor: IOSLocalToolExecutor
    ) -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case .conversation(let id):
                PlaceholderDetailView(title: "Conversation", subtitle: id, systemImage: "text.bubble")
            case .assistant(let id):
                PlaceholderDetailView(title: "Assistant", subtitle: id, systemImage: "person.crop.circle")
            case .workspaceItem(let id):
                PlaceholderDetailView(title: "Workspace Item", subtitle: id, systemImage: "folder")
            case .providerSettings:
                PlaceholderDetailView(title: "Provider Settings", subtitle: "OpenAI, Google, Anthropic, and custom endpoints", systemImage: "server.rack")
            case .toolPermissions:
                ToolPermissionsView(
                    permissionStore: permissionStore,
                    documentStore: documentStore,
                    systemPermissionCoordinator: systemPermissionCoordinator,
                    localToolExecutor: localToolExecutor
                )
            }
        }
    }
}
