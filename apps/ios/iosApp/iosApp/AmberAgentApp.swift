import SwiftUI

@main
struct AmberAgentApp: App {
    @State private var settingsStore = SettingsStore()

    var body: some Scene {
        WindowGroup {
#if CHAT_PERF_REPLAY
            ChatPerfReplayView()
#else
            AppShell(settingsStore: settingsStore)
#endif
        }
    }
}
