import SwiftUI

@main
struct AmberAgentApp: App {
    @State private var settingsStore = SettingsStore()

    var body: some Scene {
        WindowGroup {
            AppShell(settingsStore: settingsStore)
                .task {
                    // P5 startup recovery sweep: reclassify any run left in a
                    // non-terminal state ("running"/"awaiting_permission") by a
                    // hard kill to "interrupted", so the UI surfaces it honestly
                    // instead of silently losing it. (truth_matrix `recover`.)
                    _ = await IOSRunRecovery.recoverInterruptedRuns()
                }
        }
    }
}
