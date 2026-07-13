@preconcurrency import BackgroundTasks
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
    typealias StatusHandler = @MainActor (String, Bool) -> Void
    typealias WorkspaceArtifactSaver = @MainActor (
        _ title: String,
        _ content: String,
        _ type: IOSWorkspaceArtifactType,
        _ sourceKind: String,
        _ sourceId: String?
    ) throws -> Void

    static func createAndGenerate(
        title: String,
        sources: [IOSDeepReadSource],
        templateId: String,
        sharedSettings: IOSSharedSettingsStore,
        navigate: @escaping (String) -> Void,
        onStatus: @escaping StatusHandler
    ) throws {
        let store = IOSDeepReadStore.shared
        let task = try store.createTask(title: title, sources: sources, templateId: templateId)
        store.markRunning(id: task.id)
        navigate(task.id)

        IOSDeepReadBackgroundCoordinator.shared.start(
            taskId: task.id,
            title: title,
            generationId: UUID().uuidString,
            sharedSettings: sharedSettings,
            onStatus: onStatus
        )
    }

    static func retry(
        taskId: String,
        sharedSettings: IOSSharedSettingsStore,
        onStatus: @escaping StatusHandler
    ) {
        let store = IOSDeepReadStore.shared
        store.prepareRetry(id: taskId)
        store.markRunning(id: taskId)
        let title = store.task(id: taskId)?.title ?? "深度阅读"
        IOSDeepReadBackgroundCoordinator.shared.start(
            taskId: taskId,
            title: title,
            generationId: UUID().uuidString,
            sharedSettings: sharedSettings,
            onStatus: onStatus
        )
    }

    @discardableResult
    static func runExistingTask(
        taskId: String,
        requestId: String? = nil,
        sharedSettings: IOSSharedSettingsStore,
        store: IOSDeepReadStore = .shared,
        workspaceArtifactSaver: WorkspaceArtifactSaver = IOSDeepReadLauncher.defaultWorkspaceArtifactSaver,
        backgroundTask: BGContinuedProcessingTask? = nil,
        onStatus: StatusHandler? = nil
    ) async -> Bool {
        let runState = IOSDeepReadRunState()
        defer {
            IOSDeepReadBackgroundCoordinator.shared.finish(taskId: taskId, requestId: requestId)
        }
        guard var running = store.task(id: taskId) else {
            backgroundTask?.setTaskCompleted(success: false)
            return false
        }
        guard running.status == .queued || running.status == .running else {
            backgroundTask?.setTaskCompleted(success: false)
            return false
        }

        let progress = backgroundTask?.progress
        progress?.totalUnitCount = 7
        progress?.completedUnitCount = 0

        backgroundTask?.expirationHandler = {
            if runState.expire() {
                backgroundTask?.setTaskCompleted(success: false)
                Task { @MainActor in
                    store.fail(id: taskId, message: "深度阅读后台生成被系统中断，可稍后重试。")
                    onStatus?("深度阅读后台生成被系统中断，可稍后重试。", true)
                }
            }
        }

        func updateProgress(_ completed: Int64, _ subtitle: String) {
            if store.task(id: taskId)?.status == .running {
                store.markRunning(id: taskId)
            }
            progress?.completedUnitCount = min(completed, progress?.totalUnitCount ?? completed)
            backgroundTask?.updateTitle("深度阅读", subtitle: subtitle)
        }

        store.markRunning(id: taskId)
        updateProgress(0, "准备生成 \(running.title)")

        updateProgress(1, "正在搜索补充来源")
        let searched = await searchSourcesForDeepRead(title: running.title, settings: sharedSettings.snapshot)
        guard !runState.isExpired else { return false }

        let mergedSources = Array(dedupeSources(running.sources + searched).prefix(10))
        let scrapeBase: Int64 = 2
        let generationBase = scrapeBase + Int64(max(mergedSources.count, 1))
        progress?.totalUnitCount = generationBase + 5
        updateProgress(2, "正在抓取网页正文")
        let enriched = await enrichSourcesWithScrape(
            mergedSources,
            settings: sharedSettings.snapshot,
            onSourceProgress: { index, total in
                updateProgress(scrapeBase + Int64(index), "正在抓取网页正文 \(index)/\(total)")
            }
        )
        guard !runState.isExpired else { return false }

        store.replaceSources(id: taskId, sources: enriched)
        running.sources = enriched
        guard enriched.contains(where: isUsableSourceForGeneration) else {
            return failRun(
                taskId: taskId,
                message: "深度阅读生成失败：没有可用来源，请检查搜索/网页抓取配置后重试。",
                store: store,
                backgroundTask: backgroundTask,
                runState: runState,
                onStatus: onStatus
            )
        }

        let output: String
        var structuredJSON: String? = nil
        if let (modelId, providerSetting) = sharedSettings.resolveBoardDeepReadModel(
            boardModelId: sharedSettings.todayBoard.boardModelId
        ) {
            updateProgress(generationBase, "正在生成概览")
            let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
                task: running,
                providerSetting: providerSetting,
                modelId: modelId,
                onStageProgress: { label, index, _ in
                    updateProgress(generationBase + Int64(index), "正在生成\(label)")
                }
            )
            guard !runState.isExpired else { return false }
            switch IOSDeepReadDraftGenerator.outcome(
                for: result,
                offlineFallback: IOSDeepReadDraftGenerator.generate(task: running)
            ) {
            case .failed(let reason):
                return failRun(
                    taskId: taskId,
                    message: "深度阅读生成失败：\(reason)",
                    store: store,
                    backgroundTask: backgroundTask,
                    runState: runState,
                    onStatus: onStatus
                )
            case .completed(let markdown, let json):
                output = markdown
                structuredJSON = json
            }
        } else {
            updateProgress(generationBase + 4, "正在生成离线草稿")
            output = IOSDeepReadDraftGenerator.generate(task: running)
        }

        guard runState.reserveTerminal() else { return false }
        updateProgress(progress?.totalUnitCount ?? generationBase + 5, "正在保存结果")
        store.complete(id: taskId, markdown: output, structuredJSON: structuredJSON)
        do {
            try workspaceArtifactSaver(running.title, output, .deepRead, "deep_read", running.id)
            store.clearWorkspaceSyncFailure(id: taskId)
            onStatus?("已生成并保存深度阅读。", false)
        } catch {
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            store.markWorkspaceSyncFailed(id: taskId, message: message)
            onStatus?("深度阅读已生成，但保存到 Workspace 失败：\(message)", true)
        }
        backgroundTask?.setTaskCompleted(success: true)
        return true
    }

    private static func defaultWorkspaceArtifactSaver(
        title: String,
        content: String,
        type: IOSWorkspaceArtifactType,
        sourceKind: String,
        sourceId: String?
    ) throws {
        _ = try IOSWorkspaceStore.shared.saveArtifact(
            title: title,
            content: content,
            type: type,
            sourceKind: sourceKind,
            sourceId: sourceId
        )
    }

    private static func failRun(
        taskId: String,
        message: String,
        store: IOSDeepReadStore,
        backgroundTask: BGContinuedProcessingTask?,
        runState: IOSDeepReadRunState,
        onStatus: StatusHandler?
    ) -> Bool {
        guard runState.reserveTerminal() else { return false }
        store.fail(id: taskId, message: message)
        onStatus?(message, true)
        backgroundTask?.setTaskCompleted(success: false)
        return false
    }

    private static func isUsableSourceForGeneration(_ source: IOSDeepReadSource) -> Bool {
        source.metadata["scrape_status"] != "failed"
            && !IOSDeepReadSourceNormalizer.cleanMultiline(source.content).isEmpty
    }

    private static func enrichSourcesWithScrape(
        _ sources: [IOSDeepReadSource],
        settings: Settings?,
        onSourceProgress: ((_ index: Int, _ total: Int) -> Void)? = nil
    ) async -> [IOSDeepReadSource] {
        var enriched: [IOSDeepReadSource] = []
        for (index, var source) in sources.enumerated() {
            defer { onSourceProgress?(index + 1, sources.count) }
            if source.metadata["scrape_status"] == "failed" {
                enriched.append(source)
                continue
            }
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
                    settings: settings
                )
                if let content = scrapeContent(from: output), !content.isEmpty {
                    source.content = IOSDeepReadSourceNormalizer.cleanMultiline(source.content + "\n\n网页正文：\n" + content)
                    source.metadata["scrape_status"] = "ok"
                } else {
                    source.metadata["scrape_status"] = "empty"
                }
                if source.metadata["hero_image_url"] == nil,
                   let hero = scrapeFirstImage(from: output) {
                    source.metadata["hero_image_url"] = hero
                }
            } catch {
                let hasExistingContent = !IOSDeepReadSourceNormalizer.cleanMultiline(source.content).isEmpty
                source.metadata["scrape_status"] = hasExistingContent ? "scrape_failed_keep_content" : "failed"
                source.metadata["scrape_error"] = String(error.localizedDescription.prefix(180))
            }
            enriched.append(source)
        }
        return enriched
    }

    private static func searchSourcesForDeepRead(title: String, settings: Settings?) async -> [IOSDeepReadSource] {
        let queries = deepReadSearchQueries(from: title)
        guard !queries.isEmpty else { return [] }
        var byURL: [String: IOSSearchResult] = [:]
        var order: [String] = []
        for query in queries {
            do {
                let execution = try await IOSSearchExecutor.searchResults(
                    toolInput: searchToolInput(query: query, maxResults: 4),
                    settings: settings
                )
                for result in execution.results {
                    let key = result.url.lowercased().trimmingCharacters(in: .whitespaces)
                    guard !key.isEmpty, byURL[key] == nil else { continue }
                    byURL[key] = result
                    order.append(key)
                }
            } catch {
#if DEBUG
                NSLog("[AmberDeepRead] topic-search angle failed (\(query.prefix(20))…): \(error)")
#endif
            }
        }
        let merged = Array(order.prefix(12).compactMap { byURL[$0] })
#if DEBUG
        NSLog("[AmberDeepRead] topic-search angles=\(queries.count) distinct=\(byURL.count) used=\(merged.count)")
#endif
        guard !merged.isEmpty else { return [] }
        return (try? IOSDeepReadSourceNormalizer.searchSources(query: title, results: merged)) ?? []
    }

    private static func deepReadSearchQueries(from title: String) -> [String] {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count >= 2 else { return [] }
        let year = Calendar.current.component(.year, from: Date())
        let lower = t.lowercased()
        var queries = [
            t,
            "\(t) 前因后果 时间线 背景 最新进展",
            "\(t) 官方 声明 通报",
            "\(t) 核心矛盾 争议 影响 各方反应",
            "\(t) 专家解读 分析",
            "\(t) background timeline latest news \(year)",
            "\(t) 图片 现场图 截图",
        ]
        if ["gemini", "google", "openai", "claude", "deepseek", "gpt", "llm", "大模型", "模型", "发布会", "ppt", "截图"].contains(where: { lower.contains($0) || t.contains($0) }) {
            queries.append("\(t) 发布 价格 跑分 性能 评价")
            queries.append("\(t) 发布会 PPT 演示 文稿 图片")
        }
        return queries
    }

    private static func dedupeSources(_ sources: [IOSDeepReadSource]) -> [IOSDeepReadSource] {
        var seen = Set<String>()
        var result: [IOSDeepReadSource] = []
        for source in sources {
            let url = (source.url ?? "").lowercased().trimmingCharacters(in: .whitespaces)
            let key = url.isEmpty ? "t:" + source.title.lowercased() : url
            if seen.insert(key).inserted { result.append(source) }
        }
        return result
    }

    private static func scrapeContent(from json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object["content"] as? String
    }

    private static func scrapeFirstImage(from json: String) -> String? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let images = object["images"] as? [String] else {
            return nil
        }
        return images.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
    }

    private static func jsonString(_ values: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(values),
              let data = try? JSONSerialization.data(withJSONObject: values, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    private static func searchToolInput(query: String, maxResults: Int) -> String {
        let object: [String: Any] = ["query": query, "max_results": maxResults]
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return query
        }
        return string
    }
}

@MainActor
final class IOSDeepReadBackgroundCoordinator {
    static let shared = IOSDeepReadBackgroundCoordinator()

    private var bundleIdentifier: String { Bundle.main.bundleIdentifier ?? "app.amber.ios" }
    private var permittedIdentifier: String { "\(bundleIdentifier).deepread.*" }
    private var requestPrefix: String { "\(bundleIdentifier).deepread." }
    private var taskMapKey: String { "\(bundleIdentifier).deepread.backgroundTaskMap" }
    private var registeredRequestIds: Set<String> = []
    private var sharedSettings: IOSSharedSettingsStore?

    private init() {}

    func configure(sharedSettings: IOSSharedSettingsStore) {
        self.sharedSettings = sharedSettings
        for (requestId, taskId) in taskMap() {
            if !register(requestId: requestId) {
                IOSDeepReadStore.shared.fail(id: taskId, message: "深度阅读后台任务注册失败，请重试。")
                finish(taskId: taskId, requestId: requestId)
            }
        }
    }

    func start(
        taskId: String,
        title: String,
        generationId: String,
        sharedSettings: IOSSharedSettingsStore,
        onStatus: @escaping IOSDeepReadLauncher.StatusHandler
    ) {
        configure(sharedSettings: sharedSettings)
        let requestId = requestIdentifier(for: taskId, generationId: generationId)

        guard register(requestId: requestId) else {
            finish(taskId: taskId)
            onStatus("后台任务注册失败，已以前台任务继续生成。", false)
            Task { @MainActor in
                await IOSDeepReadLauncher.runExistingTask(
                    taskId: taskId,
                    requestId: nil,
                    sharedSettings: sharedSettings,
                    onStatus: onStatus
                )
            }
            return
        }
        remember(taskId: taskId, requestId: requestId)

        let request = BGContinuedProcessingTaskRequest(
            identifier: requestId,
            title: "深度阅读",
            subtitle: title
        )
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            onStatus("已提交后台生成深度阅读。离开 App 后系统会尝试继续处理并显示进度；如被中断，可回到 App 重试。", false)
        } catch {
            finish(taskId: taskId)
            NSLog("[AmberDeepRead] BGContinuedProcessingTask submit failed: \(error)")
            onStatus("后台任务提交失败，已以前台任务继续生成。", false)
            Task { @MainActor in
                await IOSDeepReadLauncher.runExistingTask(
                    taskId: taskId,
                    requestId: nil,
                    sharedSettings: sharedSettings,
                    onStatus: onStatus
                )
            }
        }
    }

    private func register(requestId: String) -> Bool {
        guard requestId.hasPrefix(requestPrefix) else {
            NSLog("[AmberDeepRead] Refusing to register unexpected BGContinuedProcessingTask id \(requestId); expected prefix \(requestPrefix)")
            return false
        }
        guard !registeredRequestIds.contains(requestId) else { return true }

        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: requestId, using: nil) { task in
            guard let task = task as? BGContinuedProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Task { @MainActor in
                await IOSDeepReadBackgroundCoordinator.shared.handle(task)
            }
        }
        if registered {
            registeredRequestIds.insert(requestId)
        } else {
            NSLog("[AmberDeepRead] BGContinuedProcessingTask registration failed for \(requestId); permitted pattern \(permittedIdentifier)")
        }
        return registered
    }

    func finish(taskId: String, requestId: String? = nil) {
        var map = taskMap()
        if let requestId {
            map.removeValue(forKey: requestId)
        } else {
            map = map.filter { $0.value != taskId }
        }
        UserDefaults.standard.set(map, forKey: taskMapKey)
    }

    var mappedTaskIds: Set<String> {
        Set(taskMap().values)
    }

    private func handle(_ backgroundTask: BGContinuedProcessingTask) async {
        let settings = sharedSettings ?? IOSSharedSettingsStore()
        sharedSettings = settings
        guard let taskId = taskId(for: backgroundTask.identifier) else {
            failMappedTasksForUnresolvableWildcard(backgroundTask.identifier)
            backgroundTask.setTaskCompleted(success: false)
            return
        }
        await IOSDeepReadLauncher.runExistingTask(
            taskId: taskId,
            requestId: backgroundTask.identifier,
            sharedSettings: settings,
            backgroundTask: backgroundTask
        )
    }

    private func requestIdentifier(for taskId: String, generationId: String) -> String {
        let rawId = taskId + "-" + generationId
        let safeId = rawId.map { character -> Character in
            character.isLetter || character.isNumber ? character : "-"
        }
        return requestPrefix + String(safeId)
    }

    private func remember(taskId: String, requestId: String) {
        var map = taskMap()
        map[requestId] = taskId
        UserDefaults.standard.set(map, forKey: taskMapKey)
    }

    private func taskId(for requestId: String) -> String? {
        let map = taskMap()
        if let exact = map[requestId] { return exact }
        if requestId == permittedIdentifier, map.count == 1 {
            return map.values.first
        }
        return nil
    }

    private func failMappedTasksForUnresolvableWildcard(_ requestId: String) {
        guard requestId == permittedIdentifier else { return }
        let ids = Set(taskMap().values)
        guard !ids.isEmpty else { return }
        for taskId in ids {
            IOSDeepReadStore.shared.fail(id: taskId, message: "深度阅读后台任务标识无法对应到具体请求，请重试。")
            finish(taskId: taskId)
        }
    }

    private func taskMap() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: taskMapKey) as? [String: String] ?? [:]
    }
}

@MainActor
enum IOSDeepReadRecoveryOnce {
    private static var didRun = false

    static func run() {
        guard !didRun else { return }
        didRun = true
        IOSDeepReadStore.shared.recoverInterruptedRuns(
            excluding: IOSDeepReadBackgroundCoordinator.shared.mappedTaskIds
        )
    }
}

private final class IOSDeepReadRunState {
    private let lock = NSLock()
    private var expired = false
    private var terminalReserved = false

    var isExpired: Bool {
        lock.lock()
        defer { lock.unlock() }
        return expired
    }

    func expire() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !terminalReserved else { return false }
        expired = true
        return true
    }

    func reserveTerminal() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !expired, !terminalReserved else { return false }
        terminalReserved = true
        return true
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
