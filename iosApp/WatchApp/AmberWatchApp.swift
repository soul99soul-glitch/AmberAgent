import SwiftUI

@main
struct AmberWatchApp: App {
    @StateObject private var model = WatchTaskViewModel()

    var body: some Scene {
        WindowGroup {
            WatchTaskRootView(model: model)
                .onAppear { model.start() }
        }
    }
}
