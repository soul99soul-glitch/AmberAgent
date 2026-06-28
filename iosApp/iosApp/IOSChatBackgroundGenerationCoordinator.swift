@preconcurrency import BackgroundTasks
import Foundation
@preconcurrency import Shared

private struct IOSChatBackgroundRuntimeJob {
    let runId: String
    let startedAt: Int64
    let inputDigest: String
    let conversationId: KotlinUuid
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let uploadMessages: [UIMessage]
    let displayMessages: [UIMessage]
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)?
}

private struct IOSChatBackgroundDependencies {
    let conversationStore: IOSConversationStore
    let toolRuntime: ChatToolRuntime
    let liveActivityController: AgentLiveActivityController
    let saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)?
}

struct IOSChatBackgroundHandoff {
    let runId: String
    let startedAt: Int64
    let inputDigest: String
    let conversationId: KotlinUuid
    let providerSetting: ProviderSetting
    let params: TextGenerationParams
    let uploadMessages: [UIMessage]
    let displayMessages: [UIMessage]
}

private struct IOSChatBackgroundProvider: IOSAgentTextProvider {
    private let openAIProvider = OpenAIKmpProvider()
    private let claudeProvider = ClaudeKmpProvider()

    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            return try await openAIProvider.generateText(providerSetting: openAI, messages: messages, params: params)
        }
        if let claude = providerSetting as? ProviderSetting.Claude {
            return try await claudeProvider.generateText(providerSetting: claude, messages: messages, params: params)
        }
        throw NSError(
            domain: "AmberAgent.ChatBackgroundGeneration",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "当前服务商类型暂不支持后台生成"]
        )
    }
}

private final class IOSChatBackgroundRunState {
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

@MainActor
final class IOSChatBackgroundGenerationCoordinator {
    static let shared = IOSChatBackgroundGenerationCoordinator()

    private var bundleIdentifier: String { Bundle.main.bundleIdentifier ?? "app.amber.ios" }
    private var permittedIdentifier: String { "\(bundleIdentifier).chat.*" }
    private var requestPrefix: String { "\(bundleIdentifier).chat." }
    private var taskMapKey: String { "\(bundleIdentifier).chat.backgroundTaskMap" }
    private var registered = false
    private var dependencies: IOSChatBackgroundDependencies?
    private var activeJobs: [String: IOSChatBackgroundRuntimeJob] = [:]
    private lazy var db: AgentRuntimeDatabase = IosDatabaseFactory.shared.createDatabase()

    private init() {}

    func configure(
        conversationStore: IOSConversationStore? = nil,
        toolRuntime: ChatToolRuntime? = nil,
        liveActivityController: AgentLiveActivityController = .shared,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)? = nil
    ) {
        if let conversationStore, let toolRuntime {
            dependencies = IOSChatBackgroundDependencies(
                conversationStore: conversationStore,
                toolRuntime: toolRuntime,
                liveActivityController: liveActivityController,
                saveMiniAppIfPresent: saveMiniAppIfPresent
            )
        }
        guard !registered else { return }
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: permittedIdentifier, using: nil) { task in
            guard let task = task as? BGContinuedProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Task { @MainActor in
                await IOSChatBackgroundGenerationCoordinator.shared.handle(task)
            }
        }
        if !registered {
            NSLog("[AmberChatBG] BGContinuedProcessingTask registration failed for \(permittedIdentifier)")
        }
    }

    func start(
        handoff: IOSChatBackgroundHandoff,
        conversationStore: IOSConversationStore,
        toolRuntime: ChatToolRuntime,
        liveActivityController: AgentLiveActivityController,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)? = nil
    ) -> Bool {
        configure()
        guard registered else { return false }

        let requestId = requestIdentifier(for: handoff.runId)
        do {
            try persist(handoff: handoff, requestId: requestId)
        } catch {
            NSLog("[AmberChatBG] Failed to persist background payload: \(error)")
            return false
        }

        activeJobs[requestId] = runtimeJob(
            handoff: handoff,
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: liveActivityController,
            saveMiniAppIfPresent: saveMiniAppIfPresent
        )
        remember(runId: handoff.runId, requestId: requestId)

        let request = BGContinuedProcessingTaskRequest(
            identifier: requestId,
            title: "Amber 后台生成",
            subtitle: handoff.params.model.displayName
        )
        request.strategy = .fail

        do {
            try BGTaskScheduler.shared.submit(request)
            return true
        } catch {
            activeJobs.removeValue(forKey: requestId)
            finish(runId: handoff.runId, requestId: requestId)
            NSLog("[AmberChatBG] BGContinuedProcessingTask submit failed: \(error)")
            return false
        }
    }

    private func runtimeJob(
        handoff: IOSChatBackgroundHandoff,
        conversationStore: IOSConversationStore,
        toolRuntime: ChatToolRuntime,
        liveActivityController: AgentLiveActivityController,
        saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> UIMessage?)?
    ) -> IOSChatBackgroundRuntimeJob {
        IOSChatBackgroundRuntimeJob(
            runId: handoff.runId,
            startedAt: handoff.startedAt,
            inputDigest: handoff.inputDigest,
            conversationId: handoff.conversationId,
            providerSetting: handoff.providerSetting,
            params: handoff.params,
            uploadMessages: handoff.uploadMessages,
            displayMessages: handoff.displayMessages,
            conversationStore: conversationStore,
            toolRuntime: toolRuntime,
            liveActivityController: liveActivityController,
            saveMiniAppIfPresent: saveMiniAppIfPresent
        )
    }

    private func handle(_ backgroundTask: BGContinuedProcessingTask) async {
        guard let job = job(for: backgroundTask.identifier) else {
            finish(requestId: backgroundTask.identifier)
            backgroundTask.updateTitle("Amber 后台生成", subtitle: "无法恢复任务")
            backgroundTask.setTaskCompleted(success: false)
            return
        }
        let runState = IOSChatBackgroundRunState()
        let progress = backgroundTask.progress
        progress.totalUnitCount = 4
        progress.completedUnitCount = 0
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "准备上下文")
        backgroundTask.expirationHandler = { [weak self] in
            guard let self else { return }
            if runState.expire() {
                Task { @MainActor in
                    await self.fail(
                        job: job,
                        backgroundTask: backgroundTask,
                        runState: runState,
                        rawMessage: "后台生成被系统中断，可回到 App 后重试。"
                    )
                }
            }
        }

        let requestProvider: ProviderSetting
        let requestParams: TextGenerationParams
        do {
            requestProvider = try await IOSCodexProviderResolver.resolved(job.providerSetting)
            requestParams = IOSCodexProviderResolver.augmentParamsForCodex(job.params, provider: requestProvider)
        } catch {
            await fail(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState,
                rawMessage: (error as NSError).localizedDescription
            )
            return
        }

        await job.liveActivityController.update(
            runId: job.runId,
            presentation: .generatingResponse(modelName: requestParams.model.modelId),
            force: true
        )

        progress.completedUnitCount = 1
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "正在生成回复")

        let engine = IOSAgentToolEngine(
            provider: IOSChatBackgroundProvider(),
            executors: job.toolRuntime.backgroundToolExecutors(
                providerSetting: requestProvider,
                params: requestParams,
                runId: job.runId
            ),
            configuration: .init(maxSteps: 6, honorApprovalPause: false)
        )
        let result = await engine.run(
            providerSetting: requestProvider,
            messages: job.uploadMessages,
            params: requestParams
        )
        guard !runState.isExpired else { return }

        progress.completedUnitCount = 3
        backgroundTask.updateTitle("Amber 后台生成", subtitle: "正在保存结果")

        let generatedSuffix = Array(result.messages.dropFirst(job.uploadMessages.count))
        if let rawFailure = providerFailureMessage(in: generatedSuffix) {
            await fail(
                job: job,
                backgroundTask: backgroundTask,
                runState: runState,
                rawMessage: rawFailure
            )
            return
        }

        guard runState.reserveTerminal() else { return }
        var finalMessages = job.displayMessages + generatedSuffix
        if result.hitStepLimit {
            finalMessages.append(Self.assistantMessage("后台生成已达到工具循环上限，已保存当前结果。"))
        }
        if let miniAppNotice = job.saveMiniAppIfPresent?(finalMessages, job.conversationId) {
            finalMessages.append(miniAppNotice)
        }

        await job.conversationStore.saveBackgroundCompletion(
            baseMessages: job.displayMessages,
            completedMessages: finalMessages,
            to: job.conversationId
        )
        await recordRun(job.runId, startedAt: job.startedAt, status: "completed", inputDigest: job.inputDigest)
        await job.liveActivityController.end(runId: job.runId, presentation: .completed())
        progress.completedUnitCount = progress.totalUnitCount
        backgroundTask.setTaskCompleted(success: true)
        finish(runId: job.runId, requestId: backgroundTask.identifier)
    }

    private func fail(
        job: IOSChatBackgroundRuntimeJob,
        backgroundTask: BGContinuedProcessingTask,
        runState: IOSChatBackgroundRunState,
        rawMessage: String
    ) async {
        guard runState.reserveTerminal() else { return }
        let errorText = ChatViewModel.userFacingGenerationError(rawMessage, modelId: job.params.model.modelId)
        let finalMessages = job.displayMessages + [Self.assistantMessage(errorText)]
        await job.conversationStore.saveBackgroundCompletion(
            baseMessages: job.displayMessages,
            completedMessages: finalMessages,
            to: job.conversationId
        )
        await recordRun(job.runId, startedAt: job.startedAt, status: "failed", inputDigest: job.inputDigest)
        await job.liveActivityController.end(runId: job.runId, presentation: .failed())
        backgroundTask.setTaskCompleted(success: false)
        finish(runId: job.runId, requestId: backgroundTask.identifier)
    }

    private func job(for requestId: String) -> IOSChatBackgroundRuntimeJob? {
        if let job = activeJobs[requestId] { return job }
        guard let dependencies,
              let handoff = loadHandoff(requestId: requestId) else {
            return nil
        }
        let job = runtimeJob(
            handoff: handoff,
            conversationStore: dependencies.conversationStore,
            toolRuntime: dependencies.toolRuntime,
            liveActivityController: dependencies.liveActivityController,
            saveMiniAppIfPresent: dependencies.saveMiniAppIfPresent
        )
        activeJobs[requestId] = job
        return job
    }

    private func persist(handoff: IOSChatBackgroundHandoff, requestId: String) throws {
        let json = IosChatBackgroundPayloadJsonBridge.shared.encode(
            runId: handoff.runId,
            startedAt: handoff.startedAt,
            inputDigest: handoff.inputDigest,
            conversationId: handoff.conversationId,
            providerSetting: handoff.providerSetting,
            params: handoff.params,
            uploadMessages: handoff.uploadMessages,
            displayMessages: handoff.displayMessages
        )
        let directory = try jobsDirectory()
        let url = payloadURL(for: requestId, in: directory)
        try Data(json.utf8).write(to: url, options: [.atomic])
    }

    private func loadHandoff(requestId: String) -> IOSChatBackgroundHandoff? {
        do {
            let url = payloadURL(for: requestId, in: try jobsDirectory())
            let data = try Data(contentsOf: url)
            guard let json = String(data: data, encoding: .utf8) else { return nil }
            let payload = try IosChatBackgroundPayloadJsonBridge.shared.decode(json: json)
            return IOSChatBackgroundHandoff(
                runId: payload.runId,
                startedAt: payload.startedAt,
                inputDigest: payload.inputDigest,
                conversationId: payload.conversationId,
                providerSetting: payload.providerSetting,
                params: payload.params,
                uploadMessages: payload.uploadMessages,
                displayMessages: payload.displayMessages
            )
        } catch {
            NSLog("[AmberChatBG] Failed to load background payload \(requestId): \(error)")
            return nil
        }
    }

    private func removePayload(requestId: String) {
        do {
            let url = payloadURL(for: requestId, in: try jobsDirectory())
            try? FileManager.default.removeItem(at: url)
        } catch {
            NSLog("[AmberChatBG] Failed to resolve payload URL for cleanup: \(error)")
        }
    }

    private func jobsDirectory() throws -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let directory = base.appendingPathComponent("ChatBackgroundJobs", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func payloadURL(for requestId: String, in directory: URL) -> URL {
        let fileName = requestId
            .map { character -> Character in
                character.isLetter || character.isNumber || character == "." || character == "-" ? character : "-"
            }
        return directory.appendingPathComponent(String(fileName)).appendingPathExtension("json")
    }

    private func recordRun(
        _ runId: String,
        startedAt: Int64,
        status: String,
        inputDigest: String
    ) async {
        let dao = db.agentRuntimeDao()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let finishedAtValue: KotlinLong? = status == "running" ? nil : KotlinLong(value: now)
        let interruptedReason: String? = status == "interrupted" ? "user_cancelled" : nil
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: nil,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: status,
            inputDigest: inputDigest,
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: startedAt,
            finishedAt: finishedAtValue,
            interruptedReason: interruptedReason
        )

        do {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                dao.insertRun(run: run) { error in
                    if let error { cont.resume(throwing: error) }
                    else { cont.resume() }
                }
            }
        } catch {
            print("[AmberChatBG] Failed to insert agent_run: \(error)")
        }
    }

    private func providerFailureMessage(in generatedSuffix: [UIMessage]) -> String? {
        let marker = "[engine] provider error:"
        guard let text = generatedSuffix.last?.toText(),
              text.hasPrefix(marker) else { return nil }
        return String(text.dropFirst(marker.count)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func assistantMessage(_ text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func requestIdentifier(for runId: String) -> String {
        requestPrefix + String(runId.map { character -> Character in
            character.isLetter || character.isNumber ? character : "-"
        })
    }

    private func remember(runId: String, requestId: String) {
        var map = taskMap()
        map[requestId] = runId
        UserDefaults.standard.set(map, forKey: taskMapKey)
    }

    private func finish(runId: String? = nil, requestId: String? = nil) {
        var map = taskMap()
        if let requestId {
            activeJobs.removeValue(forKey: requestId)
            map.removeValue(forKey: requestId)
            removePayload(requestId: requestId)
        } else if let runId {
            let matching = map.filter { $0.value == runId }.map(\.key)
            for requestId in matching {
                activeJobs.removeValue(forKey: requestId)
                map.removeValue(forKey: requestId)
                removePayload(requestId: requestId)
            }
        }
        UserDefaults.standard.set(map, forKey: taskMapKey)
    }

    private func taskMap() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: taskMapKey) as? [String: String] ?? [:]
    }
}
