import SwiftUI
import Shared

struct ChatView: View {

    let settingsStore: SettingsStore
    @State private var viewModel: ChatViewModel
    @FocusState private var isInputFocused: Bool

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
        self._viewModel = State(initialValue: ChatViewModel(settingsStore: settingsStore))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                messageList
                inputBar
            }
            .navigationTitle("AmberAgent")
            .background(Color(.systemGroupedBackground))
        }
    }

    // MARK: - Message List

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(Array(viewModel.messages.enumerated()), id: \.offset) { index, message in
                        MessageBubbleView(message: message)
                            .id(index)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                withAnimation {
                    proxy.scrollTo(viewModel.messages.count - 1, anchor: .bottom)
                }
            }
        }
    }

    // MARK: - Input Bar

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Message…", text: $viewModel.inputText, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .focused($isInputFocused)

            if viewModel.isLoading {
                Button {
                    viewModel.cancelGeneration()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.title2)
                        .symbolRenderingMode(.hierarchical)
                }
            } else {
                Button {
                    viewModel.sendMessage()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title2)
                        .symbolRenderingMode(.hierarchical)
                }
                .disabled(viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }
}
