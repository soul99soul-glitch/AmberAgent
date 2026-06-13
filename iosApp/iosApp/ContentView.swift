// DEPRECATED: This view is not used by the app entry point.
// AppShell.swift is the actual root view. This file remains in the target
// for historical reference — remove from the Xcode target's Compile Sources
// when convenient.
import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.tint)
                Text("Amber Agent")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                Text("iOS app skeleton — shared KMP framework linked.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .navigationTitle("Amber Agent")
        }
    }
}

#Preview {
    ContentView()
}
