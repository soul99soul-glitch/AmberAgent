import SwiftUI

@main
struct AmberAgentApp: App {
    @State private var settingsStore = SettingsStore()

    var body: some Scene {
        WindowGroup {
            TabView {
                Tab("Chat", systemImage: "bubble.left.and.bubble.right") {
                    ChatView(settingsStore: settingsStore)
                }
                Tab("Settings", systemImage: "gearshape") {
                    SettingsView(settingsStore: settingsStore)
                }
            }
        }
    }
}
