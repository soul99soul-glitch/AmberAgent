import SwiftUI
import Shared

struct ChatView: View {

    let settingsStore: SettingsStore
    @State private var viewModel: ChatViewModel
    @FocusState private var isInputFocused: Bool

    init(settingsStore: SettingsStore, localToolExecutor: IOSLocalToolExecutor? = nil) {
        self.settingsStore = settingsStore
        self._viewModel = State(
            initialValue: ChatViewModel(
                settingsStore: settingsStore,
                localToolExecutor: localToolExecutor
            )
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            messageList
            inputBar
        }
        .navigationTitle("AmberAgent")
        .background(Color(.systemGroupedBackground))
    }

    // MARK: - Message List

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(viewModel.messages, id: \.id) { message in
                        MessageBubbleView(message: message)
                            .id(message.id)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
            .onChange(of: viewModel.messages.count) { _, newCount in
                guard newCount > 0 else { return }
                withAnimation {
                    if let lastId = viewModel.messages.last?.id {
                        proxy.scrollTo(lastId, anchor: .bottom)
                    }
                }
            }
        }
    }

    // MARK: - Input Bar

    private var inputBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let preview = viewModel.pendingSelectedFilePreview {
                HStack(spacing: 8) {
                    Label(preview.fileName, systemImage: "doc.text")
                        .font(.caption)
                        .lineLimit(1)
                    Text("\(preview.bytesRead) bytes")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button {
                        viewModel.clearPendingSelectedFilePreview()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(.thinMaterial, in: Capsule())
            }

            if let error = viewModel.selectedFileContextError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .lineLimit(2)
            }

            HStack(alignment: .bottom, spacing: 10) {
                Button {
                    Task {
                        await viewModel.attachSelectedFilePreviewToNextMessage()
                    }
                } label: {
                    Image(systemName: viewModel.isAttachingSelectedFile ? "paperclip.circle.fill" : "paperclip.circle")
                        .font(.title2)
                        .symbolRenderingMode(.hierarchical)
                }
                .disabled(viewModel.isLoading || viewModel.isAttachingSelectedFile)

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
                    .disabled(
                        viewModel.inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                            viewModel.isAttachingSelectedFile
                    )
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }
}
