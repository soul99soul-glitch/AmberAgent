import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import Shared

/// Shared deep-read create+generate pipeline, used by both the hot-list tap path
/// (BoardView) and the manual create form (DeepReadCreateView). Navigates to the
/// task page immediately (real-time generation), then runs the LLM pipeline (or a
/// deterministic offline draft) and completes/fails the task — honest degradation,
/// never a faked success.
@MainActor
enum IOSDeepReadLauncher {
    static func createAndGenerate(
        title: String,
        sources: [IOSDeepReadSource],
        templateId: String,
        sharedSettings: IOSSharedSettingsStore,
        navigate: @escaping (String) -> Void,
        onStatus: @escaping (String, Bool) -> Void
    ) throws {
        let store = IOSDeepReadStore.shared
        let task = try store.createTask(title: title, sources: sources, templateId: templateId)
        store.markRunning(id: task.id)
        guard let running = store.task(id: task.id) else { return }
        navigate(task.id)

        Task { @MainActor in
            let output: String
            var structuredJSON: String? = nil
            let resolved = sharedSettings.resolveBoardDeepReadModel(
                boardModelId: sharedSettings.todayBoard.boardModelId
            )
            if let (modelId, providerSetting) = resolved {
                let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
                    task: running,
                    providerSetting: providerSetting,
                    modelId: modelId
                )
                switch IOSDeepReadDraftGenerator.outcome(
                    for: result,
                    offlineFallback: IOSDeepReadDraftGenerator.generate(task: running)
                ) {
                case .failed(let reason):
                    store.fail(id: task.id, message: "深度阅读生成失败：\(reason)")
                    onStatus("深度阅读生成失败：\(reason)", true)
                    return
                case .completed(let markdown, let json):
                    output = markdown
                    structuredJSON = json
                }
            } else {
                output = IOSDeepReadDraftGenerator.generate(task: running)
            }

            store.complete(id: task.id, markdown: output, structuredJSON: structuredJSON)
            _ = try? IOSWorkspaceStore.shared.saveArtifact(
                title: running.title,
                content: output,
                type: .deepRead,
                sourceKind: "deep_read",
                sourceId: running.id
            )
            onStatus("已生成并保存深度阅读。", false)
        }
    }
}

/// The manual "自定义来源" deep-read create form. Moved out of BoardView and
/// presented from Deep Read settings (BoardSettingsView) so the main Deep Read
/// page stays focused on the hot list. Self-contained: own form state + file /
/// WebMount / search sources, all funneled through `IOSDeepReadLauncher`.
struct DeepReadCreateView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(DocumentAccessStore.self) private var documentStore
    @Environment(\.dismiss) private var dismiss

    @State private var deepReadTitle = ""
    @State private var manualText = ""
    @State private var searchQuery = ""
    @State private var selectedTemplateId = IOSDeepReadTemplate.defaultId
    @State private var isCreatingDeepRead = false
    @State private var deepReadMessage: String?
    @State private var deepReadMessageIsError = false
    @State private var isImportingDeepReadFile = false

    var body: some View {
        NavigationStack {
            ScrollView {
                createSection
                    .padding(.top, 8)
                    .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .background(AmberTheme.background.ignoresSafeArea())
            .navigationTitle("自定义来源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .fileImporter(
                isPresented: $isImportingDeepReadFile,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false
            ) { result in
                handleDeepReadFileImport(result)
            }
        }
        .presentationDetents([.large])
        .onAppear {
            selectedTemplateId = IOSDeepReadTemplate.normalizedTemplateId(sharedSettings.todayBoard.deepReadTemplateId)
        }
    }

    private var createSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "创建深度阅读")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 12) {
                    deepReadTextField(title: "标题", text: $deepReadTitle, placeholder: "要阅读的主题")

                    VStack(alignment: .leading, spacing: 6) {
                        Text("手动文本")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)
                        TextEditor(text: $manualText)
                            .font(.footnote)
                            .scrollContentBackground(.hidden)
                            .frame(minHeight: 92)
                            .padding(8)
                            .background(AmberTheme.surface2.opacity(0.65), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }

                    deepReadTextField(title: "搜索", text: $searchQuery, placeholder: "可选：搜索一个主题并纳入来源")

                    Picker("版式", selection: $selectedTemplateId) {
                        ForEach(IOSDeepReadTemplate.builtIns) { template in
                            Text(template.name).tag(template.id)
                        }
                    }
                    .pickerStyle(.segmented)

                    AmberGlassGroup(spacing: 16) {
                        HStack(spacing: 10) {
                            Button {
                                Task { await createDeepReadTask(includeConversation: true) }
                            } label: {
                                Label(isCreatingDeepRead ? "生成中" : "生成", systemImage: isCreatingDeepRead ? "clock.arrow.circlepath" : "sparkles")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.glassProminent)
                            .disabled(isCreatingDeepRead)

                            Button {
                                isImportingDeepReadFile = true
                            } label: {
                                Image(systemName: "doc.badge.plus")
                                    .frame(width: 42)
                            }
                            .buttonStyle(.glass)
                            .accessibilityLabel("从文件创建深度阅读")

                            Button {
                                Task { await createFromWebMount() }
                            } label: {
                                Image(systemName: "globe")
                                    .frame(width: 42)
                            }
                            .buttonStyle(.glass)
                            .accessibilityLabel("从当前 WebMount 页面创建深度阅读")
                        }
                    }

                    Button {
                        Task { await createDeepReadTask(includeConversation: false) }
                    } label: {
                        Label("只使用手动文本/搜索", systemImage: "text.badge.checkmark")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.glass)
                    .disabled(isCreatingDeepRead)

                    if let deepReadMessage {
                        Text(deepReadMessage)
                            .font(.footnote)
                            .foregroundStyle(deepReadMessageIsError ? AmberTheme.accentAmber : AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }

            BoardCapabilityNote("文件只读取你前台选择的 txt、md、json、csv、pdf、docx 文本预览；WebMount 只读取当前已加载页面正文。")
        }
    }

    private func deepReadTextField(title: String, text: Binding<String>, placeholder: String) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
                .frame(width: 48, alignment: .leading)
            TextField(placeholder, text: text)
                .font(.footnote)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(AmberTheme.surface2.opacity(0.65), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    private func launch(title: String, sources: [IOSDeepReadSource], templateId: String) throws {
        try IOSDeepReadLauncher.createAndGenerate(
            title: title,
            sources: sources,
            templateId: templateId,
            sharedSettings: sharedSettings,
            navigate: { router.navigate(to: .deepReadTask(id: $0)) },
            onStatus: { deepReadMessage = $0; deepReadMessageIsError = $1 }
        )
        dismiss()
    }

    private func createDeepReadTask(includeConversation: Bool) async {
        guard !isCreatingDeepRead else { return }
        isCreatingDeepRead = true
        deepReadMessage = nil
        deepReadMessageIsError = false
        defer { isCreatingDeepRead = false }

        do {
            var sources: [IOSDeepReadSource] = []
            if !manualText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                sources.append(try IOSDeepReadSourceNormalizer.manualText(title: deepReadTitle, text: manualText))
            }
            if includeConversation, let source = try? conversationStore.currentConversationDeepReadSource() {
                sources.append(source)
            }
            if !searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                do {
                    let execution = try await IOSSearchExecutor.searchResults(
                        toolInput: searchToolInput(query: searchQuery, maxResults: 5),
                        settings: sharedSettings.snapshot
                    )
                    sources.append(contentsOf: try IOSDeepReadSourceNormalizer.searchSources(
                        query: execution.request.query,
                        results: execution.results
                    ))
                } catch {
                    sources.append(try IOSDeepReadSourceNormalizer.searchFailureSource(
                        query: searchQuery,
                        error: error.localizedDescription
                    ))
                }
            }
            manualText = ""
            searchQuery = ""
            try launch(title: deepReadTitle, sources: sources, templateId: selectedTemplateId)
        } catch {
            deepReadMessage = error.localizedDescription
            deepReadMessageIsError = true
        }
    }

    private func createFromWebMount() async {
        guard !isCreatingDeepRead else { return }
        isCreatingDeepRead = true
        defer { isCreatingDeepRead = false }
        let result = await IOSDeepReadWebMountAdapter.currentPageSource()
        switch result {
        case .success(let source):
            do {
                try launch(title: source.title, sources: [source], templateId: sharedSettings.todayBoard.deepReadTemplateId)
            } catch {
                deepReadMessage = error.localizedDescription
                deepReadMessageIsError = true
            }
        case .failure(let error):
            deepReadMessage = error.localizedDescription
            deepReadMessageIsError = true
        }
    }

    private func handleDeepReadFileImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else {
                deepReadMessage = "没有选择文件。"
                deepReadMessageIsError = true
                return
            }
            documentStore.registerPickedFile(url)
            Task {
                isCreatingDeepRead = true
                defer { isCreatingDeepRead = false }
                let read = await documentStore.readSelectedDocumentForDeepRead()
                switch read {
                case .success(let source):
                    do {
                        try launch(title: source.title, sources: [source], templateId: sharedSettings.todayBoard.deepReadTemplateId)
                    } catch {
                        deepReadMessage = error.localizedDescription
                        deepReadMessageIsError = true
                    }
                case .failure(let error):
                    deepReadMessage = error.userMessageForDeepRead
                    deepReadMessageIsError = true
                }
            }
        case .failure(let error):
            deepReadMessage = "文件选择失败：\(error.localizedDescription)"
            deepReadMessageIsError = true
        }
    }

    private func searchToolInput(query: String, maxResults: Int) -> String {
        let object: [String: Any] = ["query": query, "max_results": maxResults]
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return query
        }
        return string
    }
}
