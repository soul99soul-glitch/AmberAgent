import SwiftUI
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif
#if canImport(WebKit)
@preconcurrency import WebKit
#endif
@preconcurrency import Shared

extension BoardSignal: @retroactive @unchecked Sendable {}

struct BoardView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    var providerRegistry: ProviderRegistryStore? = nil

    @State private var generationState = BoardGenerationState.idle
    @State private var collectionSnapshot = IOSBoardCollectionSnapshot.empty
    @State private var deepReadStore = IOSDeepReadStore.shared
    @State private var hotListStore = IOSHotListDashboardStore.shared
    @State private var deepReadTitle = ""
    @State private var manualText = ""
    @State private var searchQuery = ""
    @State private var selectedTemplateId = IOSDeepReadTemplate.defaultId
    @State private var deepReadMessage: String?
    @State private var deepReadMessageIsError = false
    @State private var isCreatingDeepRead = false
    @State private var isImportingDeepReadFile = false
    @State private var showCustomSourceSheet = false
    @State private var showHistorySheet = false
    // Guards initial foreground refresh so returning to this page does not
    // restart the hotlist fetch loop.
    @State private var hasRestoredPersistedBoard = false

    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(DocumentAccessStore.self) private var documentStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hotListOverviewSection
                        hotTopicSection
                        hotListProviderSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
                .refreshable {
                    await refreshHotList(force: true)
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showCustomSourceSheet) {
            NavigationStack {
                ScrollView {
                    deepReadCreateSection
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .background(AmberTheme.background.ignoresSafeArea())
                .navigationTitle("自定义来源")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { showCustomSourceSheet = false }
                    }
                }
            }
            .presentationDetents([.large])
        }
        .sheet(isPresented: $showHistorySheet) {
            NavigationStack {
                ScrollView {
                    deepReadHistorySection
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                }
                .scrollIndicators(.hidden)
                .background(AmberTheme.background.ignoresSafeArea())
                .navigationTitle("深度阅读历史")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("完成") { showHistorySheet = false }
                    }
                }
            }
            .presentationDetents([.large])
        }
        .fileImporter(
            isPresented: $isImportingDeepReadFile,
            allowedContentTypes: [.item],
            allowsMultipleSelection: false
        ) { result in
            handleDeepReadFileImport(result)
        }
        .task {
            guard !hasRestoredPersistedBoard else { return }
            hasRestoredPersistedBoard = true
            selectedTemplateId = IOSDeepReadTemplate.normalizedTemplateId(sharedSettings.todayBoard.deepReadTemplateId)
            consumeWebMountHandoffIfNeeded()
            await refreshHotList(force: false)
        }
    }

    private var header: some View {
        // Title centered on the full width via overlay so the trailing buttons
        // (history + settings) don't offset it; subtitle removed per design.
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            HStack(spacing: 8) {
                AmberGlassCircleButton(systemImage: "clock.arrow.circlepath", accessibilityLabel: "深度阅读历史", size: 44, symbolSize: 17) {
                    showHistorySheet = true
                }
                AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "深度阅读设置", size: 44, symbolSize: 17) {
                    router.navigate(to: .boardSettings)
                }
            }
        }
        .overlay {
            Text("深度阅读")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("深度阅读会把你明确提供的文本、搜索结果、当前会话、文件或 WebMount 页面整理成可保存的阅读稿。无 API Key、无网络、文件不可读或页面未加载时会给出可恢复状态，不会把静态说明伪装成结果。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var hotListOverviewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "综合热点")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: hotListStore.isRefreshing ? "arrow.triangle.2.circlepath" : "flame")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 34, height: 34)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                        VStack(alignment: .leading, spacing: 3) {
                            Text("综合热点")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(hotListSummaryText)
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    AmberGlassGroup(spacing: 16) {
                        HStack(spacing: 10) {
                            Button {
                                Task { await refreshHotList(force: true) }
                            } label: {
                                Label(hotListStore.isRefreshing ? "刷新中" : "刷新", systemImage: "arrow.clockwise")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.glassProminent)
                            .disabled(hotListStore.isRefreshing)

                            Button {
                                showCustomSourceSheet = true
                            } label: {
                                Label("自定义来源", systemImage: "plus")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.glass)
                        }
                    }

                    if let message = deepReadMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(deepReadMessageIsError ? AmberTheme.accentAmber : AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }

            BoardCapabilityNote("热榜只使用 iOS 当前真实支持的公开来源；Android 默认的 B 站源不会在 iOS 上被假装可用。")
        }
    }

    private var hotTopicSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "综合热榜")
            AmberFormGroup {
                if !hotListStore.dashboard.hasEnabledSources {
                    hotListEmptyText("没有启用任何 iOS 支持的热榜来源。请到设置里选择 Hacker News、arXiv AI、InfoQ AI、36Kr、HF Papers 或 GitHub AI。")
                } else if hotListStore.dashboard.topics.isEmpty {
                    hotListEmptyText(hotListStore.isRefreshing ? "正在刷新综合热榜…" : "暂无可显示的综合热点。下拉或点击刷新重试。")
                } else {
                    ForEach(Array(hotListStore.dashboard.topics.prefix(20).enumerated()), id: \.element.id) { index, topic in
                        Button {
                            Task { await createDeepReadTask(topic: topic) }
                        } label: {
                            IOSHotTopicRow(topic: topic, isBusy: isCreatingDeepRead)
                        }
                        .buttonStyle(.plain)
                        .disabled(isCreatingDeepRead)
                        if index < min(hotListStore.dashboard.topics.count, 20) - 1 {
                            BoardCapabilityDivider()
                        }
                    }
                }
            }
        }
    }

    private var hotListProviderSection: some View {
        VStack(spacing: 0) {
            ForEach(hotListStore.dashboard.providers) { provider in
                AmberSectionLabel(text: provider.providerName)
                    .padding(.top, 10)
                AmberFormGroup {
                    if provider.items.isEmpty {
                        hotListEmptyText(provider.error ?? "这个来源暂时没有可显示内容。")
                    } else {
                        ForEach(Array(provider.items.prefix(8).enumerated()), id: \.offset) { index, item in
                            Button {
                                Task { await createDeepReadTask(topic: IOSHotListDashboardStore.topic(from: provider, item: item)) }
                            } label: {
                                IOSHotProviderItemRow(provider: provider, item: item)
                            }
                            .buttonStyle(.plain)
                            .disabled(isCreatingDeepRead)
                            if index < min(provider.items.count, 8) - 1 {
                                BoardCapabilityDivider()
                            }
                        }
                    }
                }
                if provider.stale || (provider.error ?? "").isEmpty == false {
                    BoardCapabilityNote(provider.stale ? "这个来源刷新失败，当前显示的是上次缓存。" : "刷新失败：\(provider.error ?? "未知错误")")
                }
            }
        }
    }

    private func hotListEmptyText(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
    }

    private var hotListSummaryText: String {
        let dashboard = hotListStore.dashboard
        guard dashboard.hasEnabledSources else { return "未启用热榜来源" }
        if dashboard.hasContent {
            let date = dashboard.lastUpdatedAt > 0
                ? IOSBoardDateFormatters.monthDayTime.string(from: Date(timeIntervalSince1970: TimeInterval(dashboard.lastUpdatedAt) / 1_000))
                : "尚未刷新"
            return "\(dashboard.topics.count) 个综合主题 · \(dashboard.providers.count) 个来源 · \(date)"
        }
        return hotListStore.isRefreshing ? "正在读取公开热榜" : "还没有缓存，点击刷新读取公开热榜"
    }

    private var deepReadCreateSection: some View {
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

    private var deepReadHistorySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "历史")
            AmberFormGroup {
                if deepReadStore.history.isEmpty {
                    Text("还没有深度阅读任务。创建后会保存到本机历史。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(deepReadStore.history.prefix(20).enumerated()), id: \.element.id) { index, task in
                        Button {
                            router.navigate(to: .deepReadTask(id: task.id))
                        } label: {
                            IOSDeepReadHistoryRow(task: task)
                        }
                        .buttonStyle(.plain)
                        if index < min(deepReadStore.history.count, 20) - 1 {
                            BoardCapabilityDivider()
                        }
                    }
                }
            }
        }
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
                    // Record the failed search as a distinct, machine-readable
                    // source-failure state (scrape_status=failed) instead of a
                    // plain manual source — so it is not silently fed to the model
                    // as factual content (the generator excludes it).
                    sources.append(try IOSDeepReadSourceNormalizer.searchFailureSource(
                        query: searchQuery,
                        error: error.localizedDescription
                    ))
                }
            }
            try createAndGenerateTask(title: deepReadTitle, sources: sources, templateId: selectedTemplateId)
            manualText = ""
            searchQuery = ""
            showCustomSourceSheet = false
        } catch {
            deepReadMessage = error.localizedDescription
            deepReadMessageIsError = true
        }
    }

    private func createDeepReadTask(topic: IOSHotTopic) async {
        guard !isCreatingDeepRead else { return }
        isCreatingDeepRead = true
        deepReadMessage = nil
        deepReadMessageIsError = false
        defer { isCreatingDeepRead = false }

        do {
            let baseSources = try IOSDeepReadSourceNormalizer.hotTopicSources(topic: topic)
            let sources = await enrichHotTopicSourcesWithScrape(baseSources)
            try createAndGenerateTask(
                title: topic.title,
                sources: sources,
                templateId: sharedSettings.todayBoard.deepReadTemplateId
            )
        } catch {
            deepReadMessage = error.localizedDescription
            deepReadMessageIsError = true
        }
    }

    private func createAndGenerateTask(
        title: String,
        sources: [IOSDeepReadSource],
        templateId: String
    ) throws {
        let task = try deepReadStore.createTask(
            title: title,
            sources: sources,
            templateId: templateId
        )
        deepReadStore.markRunning(id: task.id)
        guard let running = deepReadStore.task(id: task.id) else { return }

        Task { @MainActor in
            // Real LLM pipeline when a provider/key is configured; deterministic
            // offline draft otherwise (honest degradation, no fabricated output).
            let output: String
            // Resolve the board's Deep Read model + its provider from the shared
            // settings store (canonical: formal Provider UI writes it, chat reads
            // it) so a provider configured in Settings is honored — fixes the
            // dual-source bug (legacy ProviderRegistryStore was key-LESS + ignored
            // the selected model). nil → deterministic offline draft (honest
            // degradation, never a silent /chat/completions).
            let resolved = sharedSettings.resolveBoardDeepReadModel(
                boardModelId: sharedSettings.todayBoard.boardModelId
            )
            if let (modelId, providerSetting) = resolved {
                // Honest-fail state machine: when every stage threw or was empty,
                // mark the task .failed (never mark empty output .succeeded).
                let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
                    task: running,
                    providerSetting: providerSetting,
                    modelId: modelId
                )
                // The didFail → .failed decision is shared with the retry path
                // via IOSDeepReadDraftGenerator.outcome (single source of truth).
                switch IOSDeepReadDraftGenerator.outcome(
                    for: result,
                    offlineFallback: IOSDeepReadDraftGenerator.generate(task: running)
                ) {
                case .failed(let reason):
                    // Honest failure: surface it, persist nothing as a "completed"
                    // draft (the user sees a clear failure, not fake success).
                    self.deepReadStore.fail(id: task.id, message: "深度阅读生成失败：\(reason)")
                    self.deepReadMessage = "深度阅读生成失败：\(reason)"
                    self.deepReadMessageIsError = true
                    self.router.navigate(to: .deepReadTask(id: task.id))
                    return
                case .completed(let markdown):
                    output = markdown
                }
            } else {
                output = IOSDeepReadDraftGenerator.generate(task: running)
            }

            self.deepReadStore.complete(id: task.id, markdown: output)
            _ = try? IOSWorkspaceStore.shared.saveArtifact(
                title: running.title,
                content: output,
                type: .deepRead,
                sourceKind: "deep_read",
                sourceId: running.id
            )
            self.deepReadMessage = "已生成并保存深度阅读。"
            self.deepReadMessageIsError = false
            self.deepReadTitle = ""
            self.router.navigate(to: .deepReadTask(id: task.id))
        }
    }

    private func refreshHotList(force: Bool) async {
        await hotListStore.refresh(setting: sharedSettings.todayBoard, force: force)
    }

    private func enrichHotTopicSourcesWithScrape(_ sources: [IOSDeepReadSource]) async -> [IOSDeepReadSource] {
        var enriched: [IOSDeepReadSource] = []
        for var source in sources {
            guard let url = source.url, !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                source.metadata["scrape_status"] = "no_url"
                enriched.append(source)
                continue
            }
            do {
                let input = jsonString(["url": url, "max_chars": 12_000])
                let output = try await IOSSearchExecutor.execute(
                    toolName: "scrape_web",
                    toolInput: input,
                    settings: sharedSettings.snapshot
                )
                if let content = scrapeContent(from: output), !content.isEmpty {
                    source.content = IOSDeepReadSourceNormalizer.cleanMultiline(source.content + "\n\n网页正文：\n" + content)
                    source.metadata["scrape_status"] = "ok"
                } else {
                    source.metadata["scrape_status"] = "empty"
                }
            } catch {
                source.metadata["scrape_status"] = "failed"
                source.metadata["scrape_error"] = String(error.localizedDescription.prefix(180))
            }
            enriched.append(source)
        }
        return enriched
    }

    private func scrapeContent(from json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object["content"] as? String
    }

    private func jsonString(_ values: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(values),
              let data = try? JSONSerialization.data(withJSONObject: values, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    private func createFromWebMount() async {
        guard !isCreatingDeepRead else { return }
        isCreatingDeepRead = true
        defer { isCreatingDeepRead = false }
        let result = await IOSDeepReadWebMountAdapter.currentPageSource()
        switch result {
        case .success(let source):
            do {
                try createAndGenerateTask(
                    title: source.title,
                    sources: [source],
                    templateId: sharedSettings.todayBoard.deepReadTemplateId
                )
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
                        try createAndGenerateTask(
                            title: source.title,
                            sources: [source],
                            templateId: sharedSettings.todayBoard.deepReadTemplateId
                        )
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

    private func runManualGeneration() {
        generationState = .running(message: "正在整理本机线索…")
        Task {
            do {
                let setting = sharedSettings.agentRuntime.todayBoard
                let repository = IOSBoardSignalRepository.shared
                let aggregator = IOSBoardSignalAggregator(
                    repository: repository,
                    collectors: makeBoardSignalCollectors(setting: setting)
                )
                let run = await aggregator.runOnce(
                    limitPerCollector: 50,
                    agentLimit: 80,
                    enabledSources: enabledIOSBoardSources(setting: setting)
                )
                let collected = run.boardSignals

                await MainActor.run {
                    collectionSnapshot = run.snapshot
                    generationState = .running(
                        message: "已整理 \(collected.count) 条可用线索，正在调用模型…",
                        signals: BoardSignalPreviewItem.from(collected)
                    )
                }

                if collected.isEmpty {
                    repository.markSignalsProcessed(ids: run.batch.consideredIds)
                    let output = "今日暂无可用于生成线索摘要的本地信号。"
                    IOSBoardPersistence.shared.save(board: .init(
                        boardDate: IOSBoardPersistence.shared.todayBoardDate(),
                        markdown: output,
                        signalCount: 0,
                        generatedAt: Int64(Date().timeIntervalSince1970 * 1000),
                        sourceCounts: run.snapshot.sourceCounts
                    ))
                    await MainActor.run {
                        generationState = .finished(
                            message: "采集完成：暂无可用于生成的真实信号；已保存空状态。",
                            signals: [],
                            output: output
                        )
                    }
                    return
                }

                let apiKey = settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
                let modelId = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !apiKey.isEmpty else {
                    await MainActor.run {
                        generationState = .failed("已整理 \(collected.count) 条线索，但还没有配置模型 API Key。")
                    }
                    return
                }
                guard !modelId.isEmpty else {
                    await MainActor.run {
                        generationState = .failed("已整理 \(collected.count) 条线索，但还没有选择可用模型。")
                    }
                    return
                }

                let agent = factory.createAgent(
                    baseUrl: settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines),
                    apiKey: apiKey,
                    modelId: modelId,
                    chatCompletionsPath: "/chat/completions"
                )
                let output = try await generateBoard(agent: agent, signals: collected, setting: setting)
                repository.markSignalsProcessed(ids: run.batch.consideredIds)
                // [Board MVP] Persist the generated board Markdown by date so it
                // survives restart and redisplay on next launch. Scope = local
                // signal summary only (no task-flow / BoardItemEntity / dispatch).
                IOSBoardPersistence.shared.save(board: .init(
                    boardDate: IOSBoardPersistence.shared.todayBoardDate(),
                    markdown: output,
                    signalCount: collected.count,
                    generatedAt: Int64(Date().timeIntervalSince1970 * 1000),
                    sourceCounts: run.snapshot.sourceCounts
                ))
                await MainActor.run {
                    collectionSnapshot = IOSBoardCollectionSnapshot(
                        statuses: run.snapshot.statuses,
                        recentSignals: repository.recentSignals(limit: 10),
                        pendingCount: repository.countUnprocessedSignals(),
                        lastRunAt: run.snapshot.lastRunAt,
                        lastRunError: run.snapshot.lastRunError
                    )
                    generationState = .finished(
                        message: "已生成线索摘要，并保存到本机。",
                        signals: BoardSignalPreviewItem.from(collected),
                        output: output
                    )
                }
            } catch {
                await MainActor.run {
                    generationState = .failed("生成失败：\(error.localizedDescription)")
                }
            }
        }
    }

    private func consumeWebMountHandoffIfNeeded() {
        guard let handoff = IOSWebMountContentHandoffStore.shared.consumeDeepReadHandoff() else { return }
        let repository = IOSBoardSignalRepository.shared
        let outcome = repository.ingest(handoff.boardSignal)
        collectionSnapshot = IOSBoardCollectionSnapshot(
            statuses: [],
            recentSignals: repository.recentSignals(limit: 10),
            pendingCount: repository.countUnprocessedSignals(),
            lastRunAt: nil,
            lastRunError: nil
        )
        let outcomeText: String
        switch outcome {
        case .saved:
            outcomeText = "已把 \(handoff.siteName) 的网页内容转入深度阅读线索。"
        case .duplicateSourceRef, .duplicateContentHash:
            outcomeText = "\(handoff.siteName) 的网页内容已在深度阅读线索中。"
        }
        generationState = BoardGenerationState(
            isRunning: false,
            message: "\(outcomeText) 点击“生成线索摘要”开始整理。",
            signals: [],
            output: nil,
            isError: false
        )
    }

    private var factory: IosBoardFactory { IosBoardFactory.shared }

    private func makeBoardSignalCollectors(setting: TodayBoardSetting) -> [IOSBoardSignalCollector] {
        // One hotlist collector per provider (Android BuiltInHotListProviders
        // parity — iOS was HN-only before). Each fetches independently.
        let hotlistCollectors: [IOSBoardSignalCollector] = IOSHotlistProviders.all.map {
            IOSHotlistSignalCollector(provider: $0)
        }
        return [
            IOSChatHistorySignalCollector(source: conversationStore),
            IOSEventKitCalendarSignalCollector(),
            IOSEventKitReminderSignalCollector(),
        ] + hotlistCollectors + [
            IOSKMPTimeSignalCollector(setting: setting)
        ]
    }

    private func enabledIOSBoardSources(setting: TodayBoardSetting) -> Set<String> {
        var enabled = Set(setting.enabledSources.map { String(describing: $0) })
        if enabled.contains(IOSBoardSignalSourceType.calendar) {
            enabled.insert(IOSBoardSignalSourceType.reminder)
        }
        if !setting.hotListEnabledSources.isEmpty {
            enabled.insert(IOSBoardSignalSourceType.hotlist)
        }
        enabled.insert(IOSBoardSignalSourceType.time)
        return enabled
    }

    private func collectSignals(
        collector: BoardSignalCollectorInterface,
        context: BoardCollectContext
    ) async throws -> [BoardSignal] {
        try await withCheckedThrowingContinuation { continuation in
            collector.collect(context: context) { signals, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: signals ?? [])
                }
            }
        }
    }

    private func generateBoard(
        agent: BoardAgentInterface,
        signals: [BoardSignal],
        setting: TodayBoardSetting
    ) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            agent.generate(signals: signals, setting: setting) { output, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: output ?? "模型未返回线索摘要内容。")
                }
            }
        }
    }

}

private struct BoardGenerationState {
    var isRunning: Bool
    var message: String?
    var signals: [BoardSignalPreviewItem]
    var output: String?
    var isError: Bool

    static let idle = BoardGenerationState(
        isRunning: false,
        message: "尚未生成。点击按钮后会整理本机可用线索。",
        signals: [],
        output: nil,
        isError: false
    )

    static func running(message: String, signals: [BoardSignalPreviewItem] = []) -> BoardGenerationState {
        BoardGenerationState(isRunning: true, message: message, signals: signals, output: nil, isError: false)
    }

    static func finished(message: String, signals: [BoardSignalPreviewItem], output: String) -> BoardGenerationState {
        BoardGenerationState(isRunning: false, message: message, signals: signals, output: output, isError: false)
    }

    static func failed(_ message: String) -> BoardGenerationState {
        BoardGenerationState(isRunning: false, message: message, signals: [], output: nil, isError: true)
    }
}

private struct BoardSignalPreviewItem: Identifiable {
    let id = UUID()
    let sourceType: String
    let sourceRef: String
    let title: String

    static func from(_ signals: [BoardSignal]) -> [BoardSignalPreviewItem] {
        signals.map {
            BoardSignalPreviewItem(
                sourceType: $0.sourceType,
                sourceRef: $0.sourceRef,
                title: $0.title
            )
        }
    }
}

private struct BoardSignalPreview: View {
    let signals: [BoardSignalPreviewItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("本次使用的线索")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            ForEach(signals) { signal in
                VStack(alignment: .leading, spacing: 2) {
                    Text(signal.title)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                    Text(BoardSourceLabels.title(for: signal.sourceType))
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(AmberTheme.muted2)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(10)
        .background(AmberTheme.surface.opacity(0.45), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct IOSDeepReadHistoryRow: View {
    let task: IOSDeepReadTask

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 30, height: 30)
                .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(task.title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(2)

                Text(task.sourceSummary.isEmpty ? task.template.name : "\(task.template.name) · \(task.sourceSummary)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 3) {
                Text(task.status.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(tint)
                Text(IOSBoardDateFormatters.monthDayTime.string(from: Date(timeIntervalSince1970: TimeInterval(task.updatedAt) / 1_000)))
                    .font(.system(size: 10))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private var iconName: String {
        switch task.status {
        case .queued: "clock"
        case .running: "clock.arrow.circlepath"
        case .succeeded: "checkmark.circle"
        case .failed: "exclamationmark.triangle"
        case .unsupported: "nosign"
        }
    }

    private var tint: Color {
        switch task.status {
        case .queued, .running: AmberTheme.accentAmber
        case .succeeded: AmberTheme.accentGreen
        case .failed: AmberTheme.accentRed
        case .unsupported: AmberTheme.muted2
        }
    }
}

private struct IOSHotTopicRow: View {
    let topic: IOSHotTopic
    let isBusy: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(spacing: 2) {
                Text("#\(topic.bestRank)")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(AmberTheme.accent)
                Text("\(topic.sourceCount) 源")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(width: 44)

            VStack(alignment: .leading, spacing: 5) {
                Text(topic.title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(2)
                Text(topic.sources.prefix(4).map { "\($0.providerName) #\($0.rank)" }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct IOSHotProviderItemRow: View {
    let provider: IOSHotListProviderSnapshot
    let item: IOSHotlistItem

    var body: some View {
        HStack(spacing: 12) {
            Text("#\(item.rank)")
                .font(.caption.weight(.bold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 36, alignment: .leading)

            VStack(alignment: .leading, spacing: 4) {
                Text(item.presentationTitle)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    if let heat = item.heat ?? item.score.map(String.init), !heat.isEmpty {
                        Text("热度 \(heat)")
                    }
                    if provider.stale {
                        Text("缓存")
                    }
                    if item.url != nil {
                        Text("可抓取链接")
                    }
                }
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
    }
}

struct IOSDeepReadTaskDetailView: View {
    let taskId: String
    var settingsStore: SettingsStore? = nil
    var sharedSettings: IOSSharedSettingsStore? = nil
    var providerRegistry: ProviderRegistryStore? = nil

    @State private var store = IOSDeepReadStore.shared
    @State private var templateStore = IOSDeepReadTemplateStore.shared
    @State private var banner: String?
    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(\.dismiss) private var dismiss

    private var task: IOSDeepReadTask? {
        store.task(id: taskId)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        if let banner {
                            Text(banner)
                                .font(.caption.weight(.medium))
                                .foregroundStyle(AmberTheme.foreground2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 9)
                                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium))
                                .padding(.horizontal, 16)
                                .padding(.bottom, 10)
                        }

                        if let task {
                            statusSection(task)
                            resultSection(task)
                            sourcesSection(task)
                        } else {
                            Text("这条深度阅读历史无法读取。")
                                .font(.footnote)
                                .foregroundStyle(AmberTheme.muted)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 24)
                        }
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回深度阅读", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text(task?.title ?? "深度阅读")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(task?.status.title ?? "历史")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            AmberGlassCircleButton(systemImage: "arrow.clockwise", accessibilityLabel: "重试深度阅读", size: 44, symbolSize: 17) {
                retry()
            }
            .opacity(task?.status == .failed || task?.status == .unsupported ? 1 : 0.35)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private func statusSection(_ task: IOSDeepReadTask) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "状态")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(task.status.title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(statusTint(task.status))
                        Spacer()
                        Text(task.template.name)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.accent)
                    }
                    Text(task.sourceSummary.isEmpty ? "没有来源摘要" : task.sourceSummary)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                    if let failure = task.failureMessage, !failure.isEmpty {
                        Text(failure)
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.accentAmber)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack(spacing: 10) {
                        Button {
                            copyResult(task)
                        } label: {
                            Label("复制", systemImage: "doc.on.doc")
                        }
                        .buttonStyle(.bordered)
                        .disabled(task.resultMarkdown.isEmpty)

                        Button {
                            Task { await saveToChat(task) }
                        } label: {
                            Label("发回聊天", systemImage: "bubble.left.and.bubble.right")
                        }
                        .buttonStyle(.bordered)
                        .disabled(task.resultMarkdown.isEmpty)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
    }

    private func resultSection(_ task: IOSDeepReadTask) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "结果")
            AmberFormGroup {
                if task.resultMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(task.status == .running ? "正在生成结果…" : "还没有可显示结果。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    if let html = customTemplateHTML(task) {
                        IOSDeepReadTemplateWebView(html: html)
                            .frame(minHeight: 560)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 10)
                    } else {
                        MarkdownView(markdown: task.resultMarkdown, displaySetting: sharedSettings?.displaySetting)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                    }
                }
            }
        }
    }

    private func sourcesSection(_ task: IOSDeepReadTask) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "来源")
            AmberFormGroup {
                ForEach(Array(task.sources.enumerated()), id: \.element.id) { index, source in
                    VStack(alignment: .leading, spacing: 5) {
                        Text("\(source.kind.title) · \(source.title)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)
                        Text(source.content)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(5)
                        if let url = source.url, !url.isEmpty {
                            Text(url)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(AmberTheme.accent)
                                .lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    if index < task.sources.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }
        }
    }

    private func retry() {
        guard let task, task.status == .failed || task.status == .unsupported else { return }
        Task { @MainActor in
            store.prepareRetry(id: task.id)
            store.markRunning(id: task.id)
            if let running = store.task(id: task.id) {
                // Honor the honest-fail outcome: an all-stages-failed retry is
                // marked .failed (not completed with empty sections), parity with
                // the create path.
                switch await generateRetryOutput(task: running) {
                case .failed(let reason):
                    store.fail(id: task.id, message: "深度阅读生成失败：\(reason)")
                    banner = "深度阅读重试失败：\(reason)"
                case .completed(let markdown):
                    store.complete(id: task.id, markdown: markdown)
                    _ = try? IOSWorkspaceStore.shared.saveArtifact(
                        title: running.title,
                        content: markdown,
                        type: .deepRead,
                        sourceKind: "deep_read_retry",
                        sourceId: running.id
                    )
                    banner = "已重试并更新结果。"
                }
            }
        }
    }

    private func customTemplateHTML(_ task: IOSDeepReadTask) -> String? {
        guard task.templateId.hasPrefix(IOSDeepReadTemplate.customPrefix),
              let template = templateStore.template(id: task.templateId) else {
            return nil
        }
        let board = sharedSettings?.todayBoard
        return try? IOSDeepReadHTMLTemplateRenderer.render(
            task: task,
            template: template,
            fontScale: board?.deepReadFontScale ?? 1.0,
            fontModeWireName: board?.boardReadingFontMode.wireName ?? "serif"
        )
    }

    private func generateRetryOutput(task: IOSDeepReadTask) async -> IOSDeepReadDraftGenerator.DeepReadOutcome {
        let offline = IOSDeepReadDraftGenerator.generate(task: task)
        // Resolve the board model + provider from the shared settings store
        // (canonical source the formal Provider UI writes and chat reads), unwrap
        // to a non-optional on the MainActor (mirrors the create path's
        // concurrency shape) so the fresh provider crosses the async boundary
        // without a data race. nil → offline draft (honest degradation). Fixes the
        // retry-surface half of the dual-source bug.
        guard let resolved = sharedSettings?.resolveBoardDeepReadModel(
            boardModelId: sharedSettings?.todayBoard.boardModelId
        ) else {
            return .completed(markdown: offline)
        }
        return await IOSDeepReadDraftGenerator.retryOutcome(
            resolvedProvider: resolved.provider,
            modelId: resolved.modelId,
            task: task
        )
    }

    private func copyResult(_ task: IOSDeepReadTask) {
        #if canImport(UIKit)
        UIPasteboard.general.string = task.resultMarkdown
        banner = "已复制结果。"
        #else
        banner = "当前平台不支持剪贴板。"
        #endif
    }

    private func saveToChat(_ task: IOSDeepReadTask) async {
        let saved = await conversationStore.appendDeepReadResultToCurrentConversation(task)
        banner = saved ? "已写入当前聊天上下文。" : "当前没有可写入的聊天上下文。"
    }

    private func statusTint(_ status: IOSDeepReadTaskStatus) -> Color {
        switch status {
        case .queued, .running: AmberTheme.accentAmber
        case .succeeded: AmberTheme.accentGreen
        case .failed: AmberTheme.accentRed
        case .unsupported: AmberTheme.muted2
        }
    }
}

#if canImport(WebKit)
struct IOSDeepReadTemplateWebView: UIViewRepresentable {
    let html: String

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let validation = IOSDeepReadTemplateValidator.validateHTML(html, requirePlaceholders: false)
        let safeHTML = validation.ok ? html : "<html><body><p>模板校验失败：\(validation.error ?? "未知错误")</p></body></html>"
        webView.loadHTMLString(safeHTML, baseURL: nil)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard navigationAction.navigationType == .other else {
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }
    }
}
#else
struct IOSDeepReadTemplateWebView: View {
    let html: String

    var body: some View {
        Text("当前平台不支持 HTML 模板预览。")
            .font(.caption)
            .foregroundStyle(AmberTheme.muted)
    }
}
#endif

struct BoardCapabilityDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

struct BoardCapabilityNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private struct BoardCollectorStatusRow: View {
    let status: IOSBoardCollectorStatus

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: status.errorMessage == nil ? "checkmark.circle" : "exclamationmark.triangle")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(status.errorMessage == nil ? AmberTheme.accentGreen : AmberTheme.accentAmber)
                .frame(width: 30, height: 30)
                .background(AmberTheme.surface.opacity(0.7), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(BoardSourceLabels.title(for: status.sourceType))
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(status.ingestedCount)/\(status.collectedCount)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(status.ingestedCount > 0 ? AmberTheme.accentGreen : AmberTheme.foreground2)
                if status.duplicateCount > 0 {
                    Text("去重 \(status.duplicateCount)")
                        .font(.system(size: 10))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }

    private var subtitle: String {
        if let error = status.errorMessage, !error.isEmpty {
            return error
        }
        if let title = status.latestTitle, !title.isEmpty {
            return "最近：\(title)"
        }
        if let message = status.statusMessage, !message.isEmpty {
            return message
        }
        return "暂无新信号"
    }
}

private struct BoardRecentSignalRow: View {
    let signal: IOSBoardSignalRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(BoardSourceLabels.title(for: signal.sourceType))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                Text(signal.processed ? "已使用" : "待使用")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(signal.processed ? AmberTheme.muted2 : AmberTheme.accentAmber)
            }

            Text(signal.title)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(2)

            Text(IOSBoardDateFormatters.monthDayTime.string(from: Date(timeIntervalSince1970: TimeInterval(signal.signalTime) / 1_000)))
                .font(.system(size: 10))
                .foregroundStyle(AmberTheme.muted2)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private enum BoardSourceLabels {
    static func title(for sourceType: String) -> String {
        switch sourceType {
        case IOSBoardSignalSourceType.chatHistory:
            return "聊天历史"
        case IOSBoardSignalSourceType.calendar:
            return "日历"
        case IOSBoardSignalSourceType.reminder:
            return "提醒事项"
        case IOSBoardSignalSourceType.hotlist:
            return "热榜"
        case IOSBoardSignalSourceType.time:
            return "时间锚点"
        case IOSBoardSignalSourceType.notification:
            return "通知"
        case IOSBoardSignalSourceType.feishuMessage:
            return "飞书消息"
        case IOSBoardSignalSourceType.feishuDocument:
            return "飞书文档"
        default:
            return sourceType
        }
    }
}

#Preview {
    NavigationStack {
        BoardView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
            .environment(IOSConversationStore())
            .environment(DocumentAccessStore())
    }
}
