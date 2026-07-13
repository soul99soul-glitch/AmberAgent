import Foundation
@preconcurrency import Shared

struct NovelLiveModelCatalog: @unchecked Sendable {
    let currentModel: Model?
    let providers: [ProviderSetting]
}

struct NovelGrokIsolationOptions: Equatable, Sendable {
    let disableSearch: Bool
    let disableMemory: Bool

    static let novel = NovelGrokIsolationOptions(
        disableSearch: true,
        disableMemory: true
    )
}

struct NovelLiveTransportRequest: @unchecked Sendable {
    let providerSetting: ProviderSetting
    let messages: [UIMessage]
    let parameters: TextGenerationParams
    let grokIsolation: NovelGrokIsolationOptions?
}

struct NovelLiveTransportCallbacks: @unchecked Sendable {
    let onChunk: @Sendable (MessageChunk) -> Void
    let onComplete: @Sendable () -> Void
    let onFailure: @Sendable (NovelModelFailure) -> Void
}

struct NovelLiveCancellationHandle: @unchecked Sendable {
    private let action: @Sendable () -> Void

    init(_ action: @escaping @Sendable () -> Void) {
        self.action = action
    }

    func cancel() {
        action()
    }
}

typealias NovelLiveTransport = @Sendable (
    NovelLiveTransportRequest,
    NovelLiveTransportCallbacks
) -> NovelLiveCancellationHandle?

struct NovelLiveCodexHooks: @unchecked Sendable {
    let isCodex: @Sendable (ProviderSetting) -> Bool
    let resolve: @Sendable (ProviderSetting) async throws -> ProviderSetting
    let augment: @Sendable (TextGenerationParams, ProviderSetting) -> TextGenerationParams
    let diagnose: @Sendable (ProviderSetting, ProviderSetting, TextGenerationParams) -> Void

    static let production = NovelLiveCodexHooks(
        isCodex: { IOSCodexProviderResolver.isCodexProvider($0) },
        resolve: { try await IOSCodexProviderResolver.resolved($0) },
        augment: { IOSCodexProviderResolver.augmentParamsForCodex($0, provider: $1) },
        diagnose: {
            IOSCodexProviderResolver.writeRequestDiagnostic(
                originalProvider: $0,
                resolvedProvider: $1,
                params: $2
            )
        }
    )

    static let passthrough = NovelLiveCodexHooks(
        isCodex: { _ in false },
        resolve: { $0 },
        augment: { parameters, _ in parameters },
        diagnose: { _, _, _ in }
    )
}

/// The production bridge from the Novel domain request to the existing provider
/// runtimes. It owns only transport state; prompts, persistence, and terminal
/// project mutations remain in the Novel module.
actor NovelLiveModelAdapter: NovelModelRunning {
    private struct Route: @unchecked Sendable {
        let provider: ProviderSetting
        let model: Model
        let resolved: NovelResolvedModel
    }

    private struct ActiveRun {
        let continuation: AsyncStream<NovelModelEvent>.Continuation
        var cancellationHandle: NovelLiveCancellationHandle?
        var handleInstalled: Bool
    }

    private let catalogProvider: @Sendable () async -> NovelLiveModelCatalog
    private let kmpTransport: NovelLiveTransport
    private let grokTransport: NovelLiveTransport
    private let codex: NovelLiveCodexHooks

    private var activeRuns: [NovelRunID: ActiveRun] = [:]
    private var seenRunIDs: Set<NovelRunID> = []
    private var cancelledBeforeStart: Set<NovelRunID> = []
    private var terminalBeforeHandleInstall: Set<NovelRunID> = []

    @MainActor
    init(
        sharedSettings: IOSSharedSettingsStore,
        streamingProvider: any IOSAgentStreamingProvider = OpenAIKmpProviderAdapter(),
        grokTransport: NovelLiveTransport? = nil
    ) {
        let settingsSource = NovelSharedSettingsSource(sharedSettings)
        self.catalogProvider = {
            await settingsSource.catalog()
        }
        self.kmpTransport = Self.kmpTransport(using: streamingProvider)
        self.grokTransport = grokTransport ?? Self.productionGrokTransport
        self.codex = .production
    }

    init(
        catalogProvider: @escaping @Sendable () async -> NovelLiveModelCatalog,
        kmpTransport: @escaping NovelLiveTransport,
        grokTransport: NovelLiveTransport? = nil,
        codex: NovelLiveCodexHooks = .passthrough
    ) {
        self.catalogProvider = catalogProvider
        self.kmpTransport = kmpTransport
        self.grokTransport = grokTransport ?? Self.isolatedGrokUnavailableTransport
        self.codex = codex
    }

    func resolveModel(for policy: NovelProjectModelPolicy) async throws -> NovelResolvedModel {
        try await route(for: policy).resolved
    }

    func start(_ request: NovelModelRequest) async throws -> AsyncStream<NovelModelEvent> {
        guard !seenRunIDs.contains(request.runID) else {
            throw NovelModelAdapterError.duplicateRunID(request.runID)
        }
        seenRunIDs.insert(request.runID)

        guard cancelledBeforeStart.remove(request.runID) == nil else {
            throw Self.cancelledFailure
        }

        let pair = AsyncStream<NovelModelEvent>.makeStream(bufferingPolicy: .unbounded)
        pair.continuation.onTermination = { [weak self] termination in
            guard case .cancelled = termination else { return }
            Task { await self?.cancel(runID: request.runID) }
        }
        activeRuns[request.runID] = ActiveRun(
            continuation: pair.continuation,
            cancellationHandle: nil,
            handleInstalled: false
        )

        do {
            let route = try await route(for: .fixed(
                providerID: request.model.ownerProviderID,
                modelID: request.model.modelID
            ))
            try ensureRoute(route, stillMatches: request.model)
            try ensureRunStillActive(request.runID)

            let messages = Self.makeMessages(request.messages)
            var parameters = try Self.makeParameters(
                request.parameters,
                model: route.model
            )
            var effectiveProvider = route.provider
            if codex.isCodex(route.provider) {
                effectiveProvider = try await codex.resolve(route.provider)
                try ensureRunStillActive(request.runID)
                parameters = codex.augment(parameters, effectiveProvider)
                codex.diagnose(route.provider, effectiveProvider, parameters)
            }

            let callbackSink = NovelLiveCallbackSink(
                runID: request.runID,
                owner: self
            )
            let callbacks = NovelLiveTransportCallbacks(
                onChunk: { callbackSink.send(.chunk($0)) },
                onComplete: { callbackSink.send(.completed) },
                onFailure: { callbackSink.send(.failed($0)) }
            )
            let isGrokWeb = IOSGrokWebProviderResolver.isGrokWebProvider(route.provider)
            let transportRequest = NovelLiveTransportRequest(
                providerSetting: effectiveProvider,
                messages: messages,
                parameters: parameters,
                grokIsolation: isGrokWeb ? .novel : nil
            )
            let handle = (isGrokWeb ? grokTransport : kmpTransport)(
                transportRequest,
                callbacks
            )
            try install(handle, for: request.runID)
            return pair.stream
        } catch {
            abandonRun(request.runID)
            throw error
        }
    }

    func cancel(runID: NovelRunID) async {
        cancelledBeforeStart.insert(runID)
        guard let active = activeRuns.removeValue(forKey: runID) else { return }
        active.continuation.finish()
        active.cancellationHandle?.cancel()
    }

    private func route(for policy: NovelProjectModelPolicy) async throws -> Route {
        let catalog = await catalogProvider()
        let ownerProvider: ProviderSetting
        let model: Model

        switch policy {
        case .global:
            guard let currentModel = catalog.currentModel else {
                throw Self.failure(
                    code: "global_model_missing",
                    message: "还没有可用于小说创作的全局聊天模型。"
                )
            }
            guard let currentProvider = Self.ownerProvider(
                for: currentModel,
                providers: catalog.providers
            ) else {
                throw Self.failure(
                    code: "global_provider_missing",
                    message: "全局聊天模型对应的服务商已不存在。"
                )
            }
            ownerProvider = currentProvider
            model = currentModel

        case .fixed(let providerID, let modelID):
            guard let fixedProvider = catalog.providers.first(where: {
                Self.sameStableID($0.id.description(), providerID)
            }) else {
                throw Self.failure(
                    code: "fixed_provider_missing",
                    message: "项目固定的服务商已不存在，请重新选择模型。"
                )
            }
            guard let fixedModel = fixedProvider.models.first(where: {
                Self.sameStableID($0.id.description(), modelID)
            }) else {
                throw Self.failure(
                    code: "fixed_model_missing",
                    message: "项目固定的模型已不存在，请重新选择模型。"
                )
            }
            ownerProvider = fixedProvider
            model = fixedModel
        }

        guard let provider = ChatProviderConfiguration.provider(
            for: model,
            providers: catalog.providers
        ) else {
            throw Self.failure(
                code: "effective_provider_missing",
                message: "当前模型对应的服务商配置已不存在。"
            )
        }

        guard provider.enabled else {
            throw Self.failure(
                code: "provider_disabled",
                message: "当前服务商已停用，请启用后再继续创作。"
            )
        }
        guard model.type == ModelType.chat else {
            throw Self.failure(
                code: "model_not_chat",
                message: "当前模型不是聊天模型，不能用于小说创作。"
            )
        }
        if let issue = ChatProviderConfiguration.issue(for: model, provider: provider) {
            throw Self.failure(
                code: Self.configurationCode(issue),
                message: issue.message
            )
        }

        let wireModelID = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        let displayName = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return Route(
            provider: provider,
            model: model,
            resolved: NovelResolvedModel(
                providerID: provider.id.description(),
                ownerProviderID: ownerProvider.id.description(),
                modelID: model.id.description(),
                wireModelID: wireModelID,
                displayName: displayName.isEmpty ? wireModelID : displayName,
                contextWindowTokens: model.contextWindowTokens.map { Int(truncating: $0) }
            )
        )
    }

    private func ensureRoute(_ route: Route, stillMatches expected: NovelResolvedModel) throws {
        guard Self.sameStableID(route.resolved.providerID, expected.providerID),
              Self.sameStableID(route.resolved.ownerProviderID, expected.ownerProviderID),
              Self.sameStableID(route.resolved.modelID, expected.modelID),
              route.resolved.wireModelID == expected.wireModelID else {
            throw Self.failure(
                code: "resolved_model_changed",
                message: "模型配置在生成开始前发生了变化，请重试。",
                isRetryable: true
            )
        }
    }

    private func ensureRunStillActive(_ runID: NovelRunID) throws {
        guard activeRuns[runID] != nil else {
            throw Self.cancelledFailure
        }
    }

    private func install(_ handle: NovelLiveCancellationHandle?, for runID: NovelRunID) throws {
        guard var active = activeRuns[runID] else {
            if terminalBeforeHandleInstall.remove(runID) != nil {
                handle?.cancel()
                return
            }
            handle?.cancel()
            throw Self.cancelledFailure
        }
        active.cancellationHandle = handle
        active.handleInstalled = true
        activeRuns[runID] = active
    }

    private func abandonRun(_ runID: NovelRunID) {
        guard let active = activeRuns.removeValue(forKey: runID) else { return }
        active.continuation.finish()
        active.cancellationHandle?.cancel()
    }

    fileprivate func receive(_ frame: NovelLiveCallbackFrame, runID: NovelRunID) {
        guard let active = activeRuns[runID] else { return }

        switch frame {
        case .chunk(let chunk):
            for event in Self.events(from: chunk) {
                active.continuation.yield(event)
            }
        case .completed:
            activeRuns.removeValue(forKey: runID)
            if !active.handleInstalled {
                terminalBeforeHandleInstall.insert(runID)
            }
            active.continuation.yield(.completed)
            active.continuation.finish()
        case .failed(let failure):
            activeRuns.removeValue(forKey: runID)
            if !active.handleInstalled {
                terminalBeforeHandleInstall.insert(runID)
            }
            active.continuation.yield(.failed(failure))
            active.continuation.finish()
        }
    }
}

private extension NovelLiveModelAdapter {
    static let cancelledFailure = NovelModelFailure(
        code: "cancelled",
        message: "模型请求已取消。",
        isRetryable: false
    )

    static let isolatedGrokUnavailableTransport: NovelLiveTransport = { request, callbacks in
        guard request.grokIsolation == .novel else {
            callbacks.onFailure(failure(
                code: "grok_isolation_missing",
                message: "Grok Web 小说请求缺少隔离选项。"
            ))
            return nil
        }
        callbacks.onFailure(failure(
            code: "grok_isolation_unavailable",
            message: "当前 Grok Web 运行时还不能关闭网页搜索与网页记忆，请先改用 OpenAI、Claude 或 Codex 模型。"
        ))
        return nil
    }

    static let productionGrokTransport: NovelLiveTransport = { request, callbacks in
        guard request.grokIsolation == .novel else {
            callbacks.onFailure(failure(
                code: "grok_isolation_missing",
                message: "Grok Web 小说请求缺少隔离选项。"
            ))
            return nil
        }
        guard let openAI = request.providerSetting as? ProviderSetting.OpenAI else {
            callbacks.onFailure(failure(
                code: "grok_provider_invalid",
                message: "Grok Web 小说请求的服务商配置无效。"
            ))
            return nil
        }

        let task = Task { @MainActor in
            do {
                let providerID = IOSGrokWebProviderResolver.providerKey(openAI)
                try await IOSGrokWebClient(providerId: providerID).streamText(
                    messages: request.messages,
                    params: request.parameters,
                    options: .novel,
                    onChunk: callbacks.onChunk
                )
                guard !Task.isCancelled else { return }
                callbacks.onComplete()
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                callbacks.onFailure(NovelModelFailure(
                    code: "grok_web_stream_failed",
                    message: (error as NSError).localizedDescription,
                    isRetryable: true
                ))
            }
        }
        return NovelLiveCancellationHandle {
            task.cancel()
        }
    }

    static func kmpTransport(using provider: any IOSAgentStreamingProvider) -> NovelLiveTransport {
        { request, callbacks in
            let job = provider.streamText(
                providerSetting: request.providerSetting,
                messages: request.messages,
                params: request.parameters,
                onChunk: callbacks.onChunk,
                onComplete: callbacks.onComplete,
                onError: { error in
                    callbacks.onFailure(NovelModelFailure(
                        code: "provider_stream_failed",
                        message: error.message ?? String(describing: error),
                        isRetryable: true
                    ))
                }
            )
            guard let job else { return nil }
            let box = NovelKotlinJobBox(job)
            return NovelLiveCancellationHandle {
                box.cancel()
            }
        }
    }

    static func makeMessages(_ messages: [NovelModelMessage]) -> [UIMessage] {
        messages.map { message in
            let role: MessageRole
            switch message.role {
            case .system: role = .system
            case .user: role = .user
            case .assistant: role = .assistant
            }
            return UIMessage(
                id: KotlinUuid.companion.random(),
                role: role,
                parts: [UIMessagePart.Text(text: message.content, metadata: nil)],
                annotations: [],
                createdAt: nowLocalDateTime(),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
        }
    }

    static func makeParameters(
        _ source: NovelModelParameters,
        model: Model
    ) throws -> TextGenerationParams {
        if let maxTokens = source.maxOutputTokens, maxTokens <= 0 {
            throw failure(
                code: "invalid_max_output_tokens",
                message: "最大输出 token 必须大于零。"
            )
        }
        let customBodies = sanitizedCustomBodies(model.customBodies)
        let transportModel = Model(
            modelId: model.modelId,
            displayName: model.displayName,
            id: model.id,
            type: model.type,
            customHeaders: model.customHeaders,
            customBodies: customBodies,
            inputModalities: model.inputModalities,
            outputModalities: model.outputModalities,
            abilities: model.abilities,
            tools: Set<BuiltInTools>(),
            contextWindowTokens: model.contextWindowTokens,
            providerOverwrite: model.providerOverwrite
        )
        let supportsReasoning = model.abilities.contains(.reasoning)
        return TextGenerationParams(
            model: transportModel,
            temperature: source.temperature.map { KotlinFloat(value: Float($0)) },
            topP: source.topP.map { KotlinFloat(value: Float($0)) },
            maxTokens: source.maxOutputTokens.map {
                KotlinInt(value: Int32(clamping: $0))
            },
            tools: [],
            reasoningLevel: supportsReasoning ? reasoningLevel(source.reasoningLevel) : .off,
            customHeaders: model.customHeaders,
            customBody: customBodies
        )
    }

    static func sanitizedCustomBodies(_ bodies: [CustomBody]) -> [CustomBody] {
        let reservedKeys: Set<String> = [
            "conversation", "functioncall", "functions", "input", "instructions",
            "memory", "messages", "model", "paralleltoolcalls", "previousresponseid",
            "prompt", "search", "searchparameters", "store", "stream", "streamoptions",
            "system", "systeminstruction", "toolchoice", "tools", "websearch",
            "websearchoptions",
        ]
        let reservedKeyFamilies = [
            "assistant", "context", "conversation", "function", "instruction",
            "mcp", "memory", "message", "plugin", "prompt", "response", "search", "skill",
            "system", "tool", "workspace",
        ]
        return bodies.filter { body in
            let key = body.key.lowercased().filter(\.isLetter)
            guard !reservedKeys.contains(key) else { return false }
            return !reservedKeyFamilies.contains { key.contains($0) }
        }
    }

    static func reasoningLevel(_ source: NovelModelReasoningLevel) -> ReasoningLevel {
        switch source {
        case .off: .off
        case .automatic: .auto_
        case .low: .low
        case .medium: .medium
        case .high: .high
        case .xhigh: .xhigh
        case .max: .max
        }
    }

    static func events(from chunk: MessageChunk) -> [NovelModelEvent] {
        var events: [NovelModelEvent] = []
        for choice in chunk.choices {
            if let delta = choice.delta {
                let text = text(in: delta)
                if !text.isEmpty {
                    events.append(.textDelta(text))
                }
            } else if let message = choice.message {
                let text = text(in: message)
                if !text.isEmpty {
                    events.append(.textReplacement(text))
                }
            }
        }
        if let usage = chunk.usage {
            events.append(.usage(NovelModelUsage(
                promptTokens: Int(usage.promptTokens),
                completionTokens: Int(usage.completionTokens),
                cachedTokens: Int(usage.cachedTokens),
                totalTokens: Int(usage.totalTokens)
            )))
        }
        return events
    }

    static func text(in message: UIMessage) -> String {
        message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
    }

    static func nowLocalDateTime() -> Kotlinx_datetimeLocalDateTime {
        let now = Date()
        let calendar = Calendar.current
        return Kotlinx_datetimeLocalDateTime(
            year: Int32(calendar.component(.year, from: now)),
            month: Int32(calendar.component(.month, from: now)),
            day: Int32(calendar.component(.day, from: now)),
            hour: Int32(calendar.component(.hour, from: now)),
            minute: Int32(calendar.component(.minute, from: now)),
            second: Int32(calendar.component(.second, from: now)),
            nanosecond: Int32(calendar.component(.nanosecond, from: now))
        )
    }

    static func sameStableID(_ lhs: String, _ rhs: String) -> Bool {
        lhs.trimmingCharacters(in: .whitespacesAndNewlines)
            .caseInsensitiveCompare(rhs.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
    }

    static func ownerProvider(
        for model: Model,
        providers: [ProviderSetting]
    ) -> ProviderSetting? {
        providers.first { provider in
            provider.models.contains { candidate in
                sameStableID(candidate.id.description(), model.id.description())
            }
        }
    }

    static func configurationCode(_ issue: ChatConfigurationIssue) -> String {
        switch issue {
        case .missingAPIKey: "configuration_missing_api_key"
        case .invalidBaseURL: "configuration_invalid_base_url"
        case .missingModel: "configuration_missing_model"
        case .missingProvider: "configuration_missing_provider"
        case .unsupportedProvider: "configuration_unsupported_provider"
        case .codexNotSignedIn: "configuration_codex_not_signed_in"
        case .grokNotSignedIn: "configuration_grok_not_signed_in"
        }
    }

    static func failure(
        code: String,
        message: String,
        isRetryable: Bool = false
    ) -> NovelModelFailure {
        NovelModelFailure(
            code: code,
            message: message,
            isRetryable: isRetryable
        )
    }
}

fileprivate enum NovelLiveCallbackFrame: @unchecked Sendable {
    case chunk(MessageChunk)
    case completed
    case failed(NovelModelFailure)
}

private final class NovelLiveCallbackSink: @unchecked Sendable {
    private let lock = NSLock()
    private let runID: NovelRunID
    private weak var owner: NovelLiveModelAdapter?
    private var frames: [NovelLiveCallbackFrame] = []
    private var frameHead = 0
    private var isDraining = false

    init(runID: NovelRunID, owner: NovelLiveModelAdapter) {
        self.runID = runID
        self.owner = owner
    }

    func send(_ frame: NovelLiveCallbackFrame) {
        lock.lock()
        frames.append(frame)
        guard !isDraining else {
            lock.unlock()
            return
        }
        isDraining = true
        lock.unlock()

        Task { [self] in
            await drain()
        }
    }

    private func drain() async {
        while let frame = popFirst() {
            await owner?.receive(frame, runID: runID)
        }
    }

    private func popFirst() -> NovelLiveCallbackFrame? {
        lock.lock()
        defer { lock.unlock() }
        guard frameHead < frames.count else {
            frames.removeAll(keepingCapacity: true)
            frameHead = 0
            isDraining = false
            return nil
        }
        let frame = frames[frameHead]
        frameHead += 1
        if frameHead >= 256, frameHead * 2 >= frames.count {
            frames.removeFirst(frameHead)
            frameHead = 0
        }
        return frame
    }
}

private final class NovelKotlinJobBox: @unchecked Sendable {
    private let job: Kotlinx_coroutines_coreJob

    init(_ job: Kotlinx_coroutines_coreJob) {
        self.job = job
    }

    func cancel() {
        job.cancel(cause: nil)
    }
}

private final class NovelSharedSettingsSource: @unchecked Sendable {
    private let sharedSettings: IOSSharedSettingsStore

    @MainActor
    init(_ sharedSettings: IOSSharedSettingsStore) {
        self.sharedSettings = sharedSettings
    }

    @MainActor
    func catalog() -> NovelLiveModelCatalog {
        NovelLiveModelCatalog(
            currentModel: sharedSettings.snapshot.getCurrentChatModel(),
            providers: sharedSettings.snapshot.providers
        )
    }
}
