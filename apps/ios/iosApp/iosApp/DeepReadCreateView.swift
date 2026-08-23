import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import Shared

/// Snapshot of a completed (possibly partial) article, kept across a retry so
/// that a retry run which ends without success (no model, no usable sources,
/// system interruption) restores the last good draft instead of leaving the
/// task failed with empty content.
struct IOSDeepReadPriorCompletion: Sendable {
    let markdown: String
    let structuredJSON: String?
    let missingSections: [String]
}

/// Shared deep-read create+generate pipeline, used by both the hot-list tap path
/// (BoardView) and the manual create form (DeepReadCreateView). Navigates to the
/// task page immediately, then runs the pipeline in-process (KeepAlive holds
/// background execution rights — same model as chat / council / novel).
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
        // Single-section retry (Android runSection parity): when the task completed
        // with missing sections, only regenerate those — seeding the stored
        // structured output so the targeted stages see the rest of the article.
        let current = store.task(id: taskId)
        let missing = current?.missingSections ?? []
        // prepareRetry below wipes the article; keep the last good draft so a
        // failed retry run can restore it instead of destroying user content.
        let priorCompletion: IOSDeepReadPriorCompletion?
        if let current, current.status == .succeeded, !missing.isEmpty {
            priorCompletion = IOSDeepReadPriorCompletion(
                markdown: current.resultMarkdown,
                structuredJSON: current.structuredJSON,
                missingSections: missing
            )
        } else {
            priorCompletion = nil
        }
        let initialOutput: IOSDeepReadOutput? = current?.structuredJSON
            .flatMap { $0.data(using: .utf8) }
            .flatMap { try? JSONDecoder().decode(IOSDeepReadOutput.self, from: $0) }
        let targetStages: Set<String>? = missing.isEmpty ? nil : Set(missing)
        store.prepareRetry(id: taskId)
        store.markRunning(id: taskId)
        let title = store.task(id: taskId)?.title ?? "深度阅读"
        IOSDeepReadBackgroundCoordinator.shared.start(
            taskId: taskId,
            title: title,
            sharedSettings: sharedSettings,
            targetStages: targetStages,
            initialOutput: initialOutput,
            priorCompletion: priorCompletion,
            onStatus: onStatus
        )
    }

    @discardableResult
    static func runExistingTask(
        taskId: String,
        sharedSettings: IOSSharedSettingsStore,
        store: IOSDeepReadStore = .shared,
        workspaceArtifactSaver: WorkspaceArtifactSaver = IOSDeepReadLauncher.defaultWorkspaceArtifactSaver,
        onStatus: StatusHandler? = nil,
        isCurrentRun: @escaping @MainActor () -> Bool = { !Task.isCancelled },
        targetStages: Set<String>? = nil,
        initialOutput: IOSDeepReadOutput? = nil,
        priorCompletion: IOSDeepReadPriorCompletion? = nil
    ) async -> Bool {
        defer {
            if isCurrentRun() {
                store.clearProgressLabel(id: taskId)
            }
        }
        guard isCurrentRun() else { return false }
        guard var running = store.task(id: taskId) else { return false }
        guard running.status == .queued || running.status == .running else { return false }

        var progressTotal: Int64 = 7
        func updateProgress(_ completed: Int64, _ subtitle: String, total: Int64? = nil) {
            guard isCurrentRun() else { return }
            if let total { progressTotal = max(1, total) }
            // Progress label is in-memory only — avoid rewriting tasks.json every tick.
            store.setProgressLabel(id: taskId, subtitle)
            BackgroundGenerationKeepAlive.shared.updateProgress(
                taskId,
                completed: completed,
                total: progressTotal,
                subtitle: subtitle
            )
        }

        store.markRunning(id: taskId)
        updateProgress(0, "准备生成")

        updateProgress(1, "正在搜索补充来源")
        let searched = await searchSourcesForDeepRead(title: running.title, settings: sharedSettings.snapshot)
        guard isCurrentRun() else { return false }

        let mergedSources = Array(dedupeSources(running.sources + searched).prefix(10))
        let scrapeBase: Int64 = 2
        let generationBase = scrapeBase + Int64(max(mergedSources.count, 1))
        progressTotal = generationBase + 5
        updateProgress(2, "正在抓取网页正文", total: progressTotal)
        let enriched = await enrichSourcesWithScrape(
            mergedSources,
            settings: sharedSettings.snapshot,
            onSourceProgress: { index, total in
                updateProgress(scrapeBase + Int64(index), "正在抓取网页正文 \(index)/\(total)")
            }
        )
        guard isCurrentRun() else { return false }

        store.replaceSources(id: taskId, sources: enriched)
        running.sources = enriched
        guard enriched.contains(where: isUsableSourceForGeneration) else {
            return failRun(
                taskId: taskId,
                message: "深度阅读生成失败：没有可用来源，请检查搜索/网页抓取配置后重试。",
                store: store,
                onStatus: onStatus,
                priorCompletion: priorCompletion
            )
        }

        let output: String
        var structuredJSON: String? = nil
        var missingSections: [String] = []
        if let (modelId, providerSetting) = sharedSettings.resolveBoardDeepReadModel(
            boardModelId: sharedSettings.todayBoard.boardModelId
        ) {
            updateProgress(generationBase, "正在生成深度阅读")
            let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
                task: running,
                providerSetting: providerSetting,
                modelId: modelId,
                onStageProgress: { label, index, _ in
                    updateProgress(generationBase + Int64(index), "正在生成\(label)")
                },
                initialOutput: initialOutput,
                targetStages: targetStages
            )
            guard isCurrentRun() else { return false }
            missingSections = result.missingSections
            switch IOSDeepReadDraftGenerator.outcome(
                for: result,
                offlineFallback: IOSDeepReadDraftGenerator.generate(task: running)
            ) {
            case .failed(let reason):
                return failRun(
                    taskId: taskId,
                    message: "深度阅读生成失败：\(IOSDeepReadUserFacingText.sanitize(reason))",
                    store: store,
                    onStatus: onStatus,
                    priorCompletion: priorCompletion
                )
            case .completed(let markdown, let json):
                output = markdown
                structuredJSON = json
            }
        } else if let priorCompletion {
            // A single-section retry with no usable model must not silently
            // replace the last good draft with a local offline draft.
            return failRun(
                taskId: taskId,
                message: "深度阅读生成失败：当前未配置可用模型，无法重新生成。",
                store: store,
                onStatus: onStatus,
                priorCompletion: priorCompletion
            )
        } else {
            updateProgress(generationBase + 4, "正在生成离线草稿")
            output = IOSDeepReadDraftGenerator.generate(task: running)
        }

        // KeepAlive expire/system-cancel may have already marked failed; don't resurrect.
        guard isCurrentRun(), store.task(id: taskId)?.status == .running else { return false }

        updateProgress(progressTotal, "正在保存结果")
        store.complete(
            id: taskId,
            markdown: output,
            structuredJSON: structuredJSON,
            missingSections: missingSections.isEmpty ? nil : missingSections
        )
        do {
            try workspaceArtifactSaver(running.title, output, .deepRead, "deep_read", running.id)
            store.clearWorkspaceSyncFailure(id: taskId)
            if missingSections.isEmpty {
                onStatus?("已生成并保存深度阅读。", false)
            } else {
                onStatus?("深度阅读已生成，但部分段落未完成（\(missingSections.joined(separator: "、"))），可重新生成。", true)
            }
        } catch {
            let message = IOSDeepReadUserFacingText.fromError(error)
            store.markWorkspaceSyncFailed(id: taskId, message: message)
            onStatus?("深度阅读已生成，但保存到 Workspace 失败：\(message)", true)
        }
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
        onStatus: StatusHandler?,
        priorCompletion: IOSDeepReadPriorCompletion? = nil
    ) -> Bool {
        if let priorCompletion {
            restorePriorCompletion(priorCompletion, taskId: taskId, store: store)
        } else {
            store.fail(id: taskId, message: message)
        }
        onStatus?(message, true)
        return false
    }

    /// Reinstates the article snapshot captured before a retry wiped the task.
    static func restorePriorCompletion(
        _ prior: IOSDeepReadPriorCompletion,
        taskId: String,
        store: IOSDeepReadStore
    ) {
        store.complete(
            id: taskId,
            markdown: prior.markdown,
            structuredJSON: prior.structuredJSON,
            missingSections: prior.missingSections
        )
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
            guard !Task.isCancelled else { break }
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
                source.metadata["scrape_error"] = String(IOSDeepReadUserFacingText.fromError(error).prefix(180))
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
final class IOSDeepReadRunRegistry {
    private struct Entry {
        let generationID: UUID
        var task: Task<Void, Never>?
    }

    private var entries: [String: Entry] = [:]

    var activeTaskIds: Set<String> { Set(entries.keys) }

    func reserve(taskId: String) -> UUID? {
        guard entries[taskId] == nil else { return nil }
        let generationID = UUID()
        entries[taskId] = Entry(generationID: generationID, task: nil)
        return generationID
    }

    func attach(_ task: Task<Void, Never>, taskId: String, generationID: UUID) {
        guard var entry = entries[taskId], entry.generationID == generationID else {
            task.cancel()
            return
        }
        entry.task = task
        entries[taskId] = entry
    }

    func isCurrent(taskId: String, generationID: UUID) -> Bool {
        entries[taskId]?.generationID == generationID
    }

    @discardableResult
    func cancel(taskId: String, generationID: UUID) -> Bool {
        guard let entry = entries[taskId], entry.generationID == generationID else { return false }
        entries.removeValue(forKey: taskId)
        entry.task?.cancel()
        return true
    }

    @discardableResult
    func finish(taskId: String, generationID: UUID) -> Bool {
        guard entries[taskId]?.generationID == generationID else { return false }
        entries.removeValue(forKey: taskId)
        return true
    }
}

struct IOSDeepReadExpirationHandlers {
    let onShortWindowExpiration: (() -> Void)?
    let onSystemTaskExpiration: () -> Void

    static func cancelOwnerOnlyOnSystemExpiration(
        _ cancelOwner: @escaping () -> Void
    ) -> IOSDeepReadExpirationHandlers {
        IOSDeepReadExpirationHandlers(
            onShortWindowExpiration: nil,
            onSystemTaskExpiration: cancelOwner
        )
    }
}

/// Starts deep-read generation immediately in-process and holds background
/// execution rights via `BackgroundGenerationKeepAlive` (same pattern as
/// chat / council / novel). Does not host the pipeline inside a BG handler.
@MainActor
final class IOSDeepReadBackgroundCoordinator {
    static let shared = IOSDeepReadBackgroundCoordinator()

    private var sharedSettings: IOSSharedSettingsStore?
    private let runRegistry = IOSDeepReadRunRegistry()
    private let durableRunStore = IOSDurableRunStore()

    private init() {}

    func configure(sharedSettings: IOSSharedSettingsStore) {
        self.sharedSettings = sharedSettings
    }

    /// Task ids currently generating in this process (for cold-start recovery exclusion).
    var activeTaskIds: Set<String> {
        runRegistry.activeTaskIds.union(BackgroundGenerationKeepAlive.shared.activeLeaseIds)
    }

    func start(
        taskId: String,
        title: String,
        sharedSettings: IOSSharedSettingsStore,
        targetStages: Set<String>? = nil,
        initialOutput: IOSDeepReadOutput? = nil,
        priorCompletion: IOSDeepReadPriorCompletion? = nil,
        onStatus: @escaping IOSDeepReadLauncher.StatusHandler
    ) {
        configure(sharedSettings: sharedSettings)
        guard let generationID = runRegistry.reserve(taskId: taskId) else { return }
        let durableRunId = "\(taskId):\(generationID.uuidString)"

        // System continued-processing cancellation is a real ownership stop.
        // The UIKit short window is not an authoritative run-owner signal; its
        // deadline may lapse while the in-process run remains valid. It therefore
        // intentionally has no cancellation callback.
        let interruptMessage = "后台生成被系统中断，可稍后重试。"
        let failIfInterrupted: () -> Void = { [weak self] in
            Task { @MainActor in
                guard let self, self.runRegistry.cancel(taskId: taskId, generationID: generationID) else { return }
                if let task = IOSDeepReadStore.shared.task(id: taskId),
                   task.status == .running || task.status == .queued {
                    if let priorCompletion {
                        IOSDeepReadLauncher.restorePriorCompletion(
                            priorCompletion, taskId: taskId, store: .shared
                        )
                    } else {
                        IOSDeepReadStore.shared.fail(id: taskId, message: interruptMessage)
                    }
                    onStatus(interruptMessage, true)
                }
                _ = try? await self.durableRunStore.transitionFromAnyActive(
                    runId: durableRunId,
                    to: .interrupted,
                    detail: "background_expiration"
                )
            }
        }
        let expirationHandlers = IOSDeepReadExpirationHandlers.cancelOwnerOnlyOnSystemExpiration(
            failIfInterrupted
        )

        BackgroundGenerationKeepAlive.shared.begin(
            taskId,
            title: "深度阅读",
            subtitle: title,
            onExpire: expirationHandlers.onShortWindowExpiration,
            onSystemTaskExpiration: expirationHandlers.onSystemTaskExpiration
        )
        BackgroundGenerationKeepAlive.shared.updateProgress(
            taskId,
            completed: 0,
            total: 7,
            subtitle: "准备生成"
        )

        let operationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.runRegistry.finish(taskId: taskId, generationID: generationID) {
                    BackgroundGenerationKeepAlive.shared.end(taskId)
                }
            }
            let didStartDurably = (try? await self.durableRunStore.ensureRunning(
                runId: durableRunId,
                descriptorId: IOSDurableRunStore.Descriptor.deepRead,
                startedAt: Int64(Date().timeIntervalSince1970 * 1_000),
                inputDigest: IOSDurableRunStore.inputDigest(title),
                inputSnapshotRef: "deep_read:\(taskId)"
            )) == true
            guard didStartDurably else {
                let message = "无法保存运行状态，深度阅读未启动。"
                if let priorCompletion {
                    IOSDeepReadLauncher.restorePriorCompletion(
                        priorCompletion, taskId: taskId, store: .shared
                    )
                } else {
                    IOSDeepReadStore.shared.fail(id: taskId, message: message)
                }
                onStatus(message, true)
                return
            }
            guard self.runRegistry.isCurrent(taskId: taskId, generationID: generationID) else {
                _ = try? await self.durableRunStore.transitionFromAnyActive(
                    runId: durableRunId,
                    to: .interrupted,
                    detail: "background_expiration"
                )
                return
            }
            _ = await IOSDeepReadLauncher.runExistingTask(
                taskId: taskId,
                sharedSettings: sharedSettings,
                onStatus: onStatus,
                isCurrentRun: { [weak self] in
                    guard !Task.isCancelled, let self else { return false }
                    return self.runRegistry.isCurrent(taskId: taskId, generationID: generationID)
                },
                targetStages: targetStages,
                initialOutput: initialOutput,
                priorCompletion: priorCompletion
            )
            // Expiration removes the registry owner and owns the interrupted
            // settlement above; do not race it with a generic failed mapping.
            guard self.runRegistry.isCurrent(taskId: taskId, generationID: generationID) else { return }
            let taskStatus = IOSDeepReadStore.shared.task(id: taskId)?.status ?? .failed
            _ = try? await self.durableRunStore.transitionFromAnyActive(
                runId: durableRunId,
                to: Self.durableStatus(for: taskStatus),
                detail: IOSDeepReadStore.shared.task(id: taskId)?.failureMessage
            )
        }
        runRegistry.attach(operationTask, taskId: taskId, generationID: generationID)
    }

    func reconcileDurableRuns() async {
        guard let runs = try? await durableRunStore.recoverableRuns(
            descriptorIds: [IOSDurableRunStore.Descriptor.deepRead]
        ) else { return }
        for run in runs {
            guard let ref = run.inputSnapshotRef, ref.hasPrefix("deep_read:") else { continue }
            let taskId = String(ref.dropFirst("deep_read:".count))
            let task = IOSDeepReadStore.shared.task(id: taskId)
            _ = try? await durableRunStore.transitionFromAnyActive(
                runId: run.runId,
                to: Self.durableStatus(for: task?.status ?? .failed),
                detail: task?.failureMessage ?? "process_restarted"
            )
        }
    }

    private static func durableStatus(for status: IOSDeepReadTaskStatus) -> AgentRunStatus {
        switch status {
        case .succeeded:
            .completed
        case .failed, .unsupported:
            .failed
        case .queued, .running:
            .interrupted
        }
    }
}

@MainActor
enum IOSDeepReadRecoveryOnce {
    private static var didRun = false

    static func run() {
        guard !didRun else { return }
        didRun = true
        IOSDeepReadStore.shared.recoverInterruptedRuns(
            excluding: IOSDeepReadBackgroundCoordinator.shared.activeTaskIds
        )
        Task { @MainActor in
            await IOSDeepReadBackgroundCoordinator.shared.reconcileDurableRuns()
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
                        error: IOSDeepReadUserFacingText.fromError(error)
                    ))
                }
            }
            manualText = ""
            searchQuery = ""
            try launch(title: deepReadTitle, sources: sources, templateId: selectedTemplateId)
        } catch {
            deepReadMessage = IOSDeepReadUserFacingText.fromError(error)
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
                deepReadMessage = IOSDeepReadUserFacingText.fromError(error)
                deepReadMessageIsError = true
            }
        case .failure(let error):
            deepReadMessage = IOSDeepReadUserFacingText.fromError(error)
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
                        deepReadMessage = IOSDeepReadUserFacingText.fromError(error)
                        deepReadMessageIsError = true
                    }
                case .failure(let error):
                    deepReadMessage = error.userMessageForDeepRead
                    deepReadMessageIsError = true
                }
            }
        case .failure(let error):
            deepReadMessage = "文件选择失败：\(IOSDeepReadUserFacingText.fromError(error))"
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
