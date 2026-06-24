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
    @State private var topicActionTarget: IOSHotTopic?
    // Guards initial foreground refresh so returning to this page does not
    // restart the hotlist fetch loop.
    @State private var hasRestoredPersistedBoard = false

    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
    @Environment(DocumentAccessStore.self) private var documentStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
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
        // 榜单条目的操作改为从底部滑入的 Liquid Glass 面板(原生 confirmationDialog 在
        // iOS 26 会渲染成居中灰卡、无下沉动效)。.sheet + 小尺寸 detent = 原生上滑动效。
        .sheet(isPresented: Binding(
            get: { topicActionTarget != nil },
            set: { if !$0 { topicActionTarget = nil } }
        )) {
            if let topic = topicActionTarget {
                let sourceURL = topic.sources
                    .compactMap(\.url)
                    .first(where: { !$0.isEmpty })
                    .flatMap { URL(string: $0) }
                let rowCount = 2 + (sourceURL != nil ? 1 : 0)
                TopicActionSheet(
                    title: topic.title,
                    sourceURL: sourceURL,
                    onDeepRead: {
                        topicActionTarget = nil
                        Task { await createDeepReadTask(topic: topic) }
                    },
                    onOpenSource: {
                        if let sourceURL {
                            topicActionTarget = nil
                            openURL(sourceURL)
                        }
                    },
                    onRegenerate: {
                        topicActionTarget = nil
                        Task { await createDeepReadTask(topic: topic) }
                    }
                )
                .presentationDetents([.height(CGFloat(96 + rowCount * 64))])
                .presentationDragIndicator(.visible)
                .presentationBackground(.regularMaterial)
                .presentationCornerRadius(30)
            }
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
                            topicActionTarget = topic
                        } label: {
                            IOSHotTopicRow(topic: topic, isBusy: isCreatingDeepRead)
                        }
                        .buttonStyle(.plain)
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
                                topicActionTarget = IOSHotListDashboardStore.topic(from: provider, item: item)
                            } label: {
                                IOSHotProviderItemRow(provider: provider, item: item)
                            }
                            .buttonStyle(.plain)
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
                            // 先收起历史 sheet，再在主导航栈上跳转到文章。两者是独立状态，
                            // 文章已 push 到 sheet 之下，收起 sheet 即直接露出文章，避免
                            // 「点了没反应、反复点」的错觉。
                            showHistorySheet = false
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
            // 先用基础来源建任务并「立刻」跳转到生成中页面,消除点击后数秒的等待;
            // 网页抓取增强(enrichHotTopicSourcesWithScrape,逐源联网、耗时)挪到后台
            // 任务里、生成之前进行(见 createAndGenerateTask 的 enrichHotTopic)。
            let baseSources = try IOSDeepReadSourceNormalizer.hotTopicSources(topic: topic)
            try createAndGenerateTask(
                title: topic.title,
                sources: baseSources,
                templateId: sharedSettings.todayBoard.deepReadTemplateId,
                enrichHotTopic: true
            )
        } catch {
            deepReadMessage = error.localizedDescription
            deepReadMessageIsError = true
        }
    }

    private func createAndGenerateTask(
        title: String,
        sources: [IOSDeepReadSource],
        templateId: String,
        enrichHotTopic: Bool = false
    ) throws {
        let task = try deepReadStore.createTask(
            title: title,
            sources: sources,
            templateId: templateId
        )
        deepReadStore.markRunning(id: task.id)
        guard let running = deepReadStore.task(id: task.id) else { return }
        // Open the task page immediately so the user watches generation happen in
        // real time, instead of silent background work that only pops the page
        // open when it finishes.
        router.navigate(to: .deepReadTask(id: task.id))

        Task { @MainActor in
            var running = running
            if enrichHotTopic {
                // 后台抓取网页正文增强来源(此前在导航前同步进行 → 点击后卡几秒)。
                // 抓完写回任务来源(来源卡片会反映 scrape_status),再用于生成。
                let enriched = await enrichHotTopicSourcesWithScrape(running.sources)
                deepReadStore.replaceSources(id: task.id, sources: enriched)
                running.sources = enriched
            }
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
    @State private var editorialHeight: CGFloat = 600
    @Environment(\.colorScheme) private var colorScheme
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
                            actionRow(task)
                            failureBanner(task)
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

    // 顶部「状态」卡片已移除(状态已在导航栏标题下显示)。完成后用一条贴右的玻璃胶囊
    // 行承载「复制 / 发回聊天」,失败时单独一条浅色横幅,正文则是全宽阅读面。
    @ViewBuilder
    private func actionRow(_ task: IOSDeepReadTask) -> some View {
        if !task.resultMarkdown.isEmpty {
            HStack(spacing: 10) {
                Spacer(minLength: 0)
                DeepReadActionChip(icon: "doc.on.doc", title: "复制", enabled: true) {
                    copyResult(task)
                }
                DeepReadActionChip(icon: "bubble.left.and.bubble.right", title: "发回聊天", enabled: true) {
                    Task { await saveToChat(task) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 2)
            .padding(.bottom, 6)
        }
    }

    @ViewBuilder
    private func failureBanner(_ task: IOSDeepReadTask) -> some View {
        if let failure = task.failureMessage, !failure.isEmpty {
            Text(failure)
                .font(.footnote)
                .foregroundStyle(AmberTheme.accentAmber)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(AmberTheme.accentAmber.opacity(0.09), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
        }
    }

    @ViewBuilder
    private func resultSection(_ task: IOSDeepReadTask) -> some View {
        if task.resultMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // 生成中:杂志骨架放在浅色容器里。
            AmberFormGroup {
                DeepReadGeneratingPlaceholder(isRunning: task.status == .running)
            }
        } else if let html = customTemplateHTML(task) {
            IOSDeepReadTemplateWebView(html: html)
                .frame(minHeight: 560)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
        } else {
            // 完成:Android 式 HTML 杂志阅读器(IOSDeepReadEditorialRenderer → WKWebView)。
            // 关掉 WebView 内部滚动、按内容高度自适应,让整篇随详情页一起滚动(HTML 自带
            // 22px 侧边距,故这里不再加水平内边距)。来源仍由下方 SwiftUI sourcesSection 承载。
            IOSDeepReadEditorialWebView(html: editorialHTML(task), contentHeight: $editorialHeight)
                .frame(height: editorialHeight)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 12)
                .id(task.id)
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

    /// Builds the Android-style editorial HTML for a completed deep read: title
    /// headline + magazine-typeset Markdown body, with the diagonal hero figure when a
    /// source carries an image (e.g. a Brave thumbnail, stashed in source metadata).
    private func editorialHTML(_ task: IOSDeepReadTask) -> String {
        let hero = task.sources
            .compactMap { $0.metadata["hero_image_url"] }
            .first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
        return IOSDeepReadEditorialRenderer.renderHTML(
            IOSDeepReadEditorialRenderer.Input(
                title: task.title,
                markdown: task.resultMarkdown,
                heroImageURL: hero,
                sourceLabel: hero == nil ? nil : "\(task.sources.count) 来源",
                dark: colorScheme == .dark
            )
        )
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

#if canImport(WebKit)
/// A WKWebView host for the Deep Read editorial reader that grows to its content
/// height instead of scrolling internally — so the whole magazine article scrolls
/// as part of the detail page (no nested scroll). Reports the laid-out content
/// height through `contentHeight`; tapped source links open in the system browser.
struct IOSDeepReadEditorialWebView: UIViewRepresentable {
    let html: String
    @Binding var contentHeight: CGFloat

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        // Serve the app-bundled reader fonts (Noto Serif SC / JetBrains Mono) to the
        // page's @font-face via a custom scheme.
        configuration.setURLSchemeHandler(IOSDeepReadFontSchemeHandler(), forURLScheme: IOSDeepReadFontSchemeHandler.scheme)
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false   // the detail page scrolls the article
        webView.scrollView.bounces = false
        context.coordinator.observeContentSize(of: webView.scrollView)
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        guard context.coordinator.loadedHTML != html else { return }
        context.coordinator.loadedHTML = html
        // Load with the bundled-font scheme's base URL so @font-face requests are
        // same-origin with the served fonts.
        webView.loadHTMLString(html, baseURL: URL(string: IOSDeepReadFontSchemeHandler.baseURL))
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onHeight: { [binding = $contentHeight] in binding.wrappedValue = $0 })
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        private let onHeight: @MainActor (CGFloat) -> Void
        var loadedHTML: String?
        private var sizeObservation: NSKeyValueObservation?

        init(onHeight: @escaping @MainActor (CGFloat) -> Void) {
            self.onHeight = onHeight
        }

        /// Content height settles after layout (and again if web fonts reflow), so
        /// observe contentSize rather than reading it once on didFinish.
        func observeContentSize(of scrollView: UIScrollView) {
            sizeObservation = scrollView.observe(\.contentSize, options: [.new]) { [weak self] scrollView, _ in
                MainActor.assumeIsolated {
                    let height = scrollView.contentSize.height
                    guard height > 0 else { return }
                    self?.onHeight(height)
                }
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            // The initial loadHTMLString is `.other` → allow; a tapped source link
            // opens in the system browser instead of navigating inside the reader.
            if navigationAction.navigationType == .linkActivated {
                if let url = navigationAction.request.url {
                    UIApplication.shared.open(url)
                }
                decisionHandler(.cancel)
                return
            }
            decisionHandler(navigationAction.navigationType == .other ? .allow : .cancel)
        }
    }
}
#else
struct IOSDeepReadEditorialWebView: View {
    let html: String
    @Binding var contentHeight: CGFloat

    var body: some View {
        Text("当前平台不支持 HTML 阅读器。")
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

// MARK: - 榜单条目操作面板(底部滑入)

private struct TopicActionSheet: View {
    let title: String
    let sourceURL: URL?
    let onDeepRead: () -> Void
    let onOpenSource: () -> Void
    let onRegenerate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 22)
                .padding(.top, 26)
                .padding(.bottom, 16)

            VStack(spacing: 10) {
                TopicActionRow(icon: "book.pages", title: "深度阅读", prominent: true, action: onDeepRead)
                if sourceURL != nil {
                    TopicActionRow(icon: "safari", title: "打开原文", action: onOpenSource)
                }
                TopicActionRow(icon: "arrow.clockwise", title: "重新生成", action: onRegenerate)
            }
            .padding(.horizontal, 16)

            Spacer(minLength: 0)
        }
    }
}

private struct TopicActionRow: View {
    let icon: String
    let title: String
    var prominent: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            rowLabel
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var rowLabel: some View {
        let base = HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .frame(width: 22)
            Text(title)
                .font(.body.weight(.semibold))
            Spacer(minLength: 0)
        }
        .foregroundStyle(prominent ? Color.white : AmberTheme.foreground)
        .padding(.horizontal, 18)
        .frame(height: 54)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())

        if prominent {
            base.amberProminentGlass(cornerRadius: 16, tint: AmberTheme.accent)
        } else {
            base.amberGlass(cornerRadius: 16)
        }
    }
}

// MARK: - 详情页:生成中骨架 + 玻璃操作胶囊

private struct DeepReadActionChip: View {
    let icon: String
    let title: String
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                Text(title)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(enabled ? AmberTheme.accent : AmberTheme.muted2)
            .padding(.horizontal, 16)
            .frame(height: 38)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: 12)
        .opacity(enabled ? 1 : 0.55)
        .disabled(!enabled)
    }
}

/// 生成过渡态:不是一块呆板的灰条,而是「一篇正在排版的杂志稿」的骨架 ——
/// 报头标题 + 副标题 + 细分隔线 + 正文段落块 + 小节标题,配柔和脉冲,
/// 让等待阶段也保有阅读器的精致感。
private struct DeepReadGeneratingPlaceholder: View {
    let isRunning: Bool
    @State private var pulse = false

    var body: some View {
        if isRunning {
            VStack(alignment: .leading, spacing: 0) {
                // 报头:两行标题 + 副标题
                bar(0.82, 24)
                bar(0.54, 24).padding(.top, 9)
                bar(0.36, 12).padding(.top, 15).opacity(0.7)

                Rectangle()
                    .fill(AmberTheme.surface2)
                    .frame(height: 1)
                    .opacity(0.85)
                    .padding(.top, 16)

                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(AmberTheme.accent)
                    Text("正在生成阅读稿…")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                }
                .padding(.top, 16)

                paragraph([1.0, 1.0, 1.0, 0.62]).padding(.top, 18)
                bar(0.3, 16).padding(.top, 22)            // 小节标题
                paragraph([1.0, 1.0, 0.8, 0.46]).padding(.top, 14)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .onAppear {
                withAnimation(.easeInOut(duration: 1.15).repeatForever(autoreverses: true)) {
                    pulse = true
                }
            }
        } else {
            Text("还没有可显示结果。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
        }
    }

    private func paragraph(_ widths: [CGFloat]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(widths.enumerated()), id: \.offset) { _, w in
                bar(w, 13)
            }
        }
    }

    private func bar(_ widthFraction: CGFloat, _ height: CGFloat) -> some View {
        GeometryReader { geo in
            RoundedRectangle(cornerRadius: height >= 20 ? 7 : 4, style: .continuous)
                .fill(AmberTheme.surface2)
                .frame(width: geo.size.width * widthFraction)
                .opacity(pulse ? 0.38 : 0.85)
        }
        .frame(height: height)
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
