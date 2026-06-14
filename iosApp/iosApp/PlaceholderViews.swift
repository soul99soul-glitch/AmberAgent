import SwiftUI

struct WorkspaceView: View {
    var body: some View {
        PlaceholderListView(
            title: "Workspace",
            systemImage: "square.grid.2x2",
            rows: [
                "Live tasks",
                "Artifacts",
                "Files",
                "Web previews"
            ]
        )
    }
}

struct AssistantsView: View {
    var body: some View {
        PlaceholderListView(
            title: "Assistants",
            systemImage: "person.2",
            rows: [
                "Assistant profiles",
                "Prompt and behavior",
                "Model defaults",
                "Memory settings"
            ]
        )
    }
}

struct PlaceholderListView: View {

    let title: String
    let systemImage: String
    let rows: [String]

    var body: some View {
        List {
            Section {
                Label(title, systemImage: systemImage)
                    .font(.headline)
                ForEach(rows, id: \.self) { row in
                    Text(row)
                }
            }
        }
        .navigationTitle(title)
    }
}

struct PlaceholderDetailView: View {

    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        ContentUnavailableView(title, systemImage: systemImage, description: Text(subtitle))
            .navigationTitle(title)
    }
}
