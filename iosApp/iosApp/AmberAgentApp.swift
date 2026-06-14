import SwiftUI

@main
struct AmberAgentApp: App {
    @State private var settingsStore = SettingsStore()

    var body: some Scene {
        WindowGroup {
            AppShell(settingsStore: settingsStore)
        }
    }
}
