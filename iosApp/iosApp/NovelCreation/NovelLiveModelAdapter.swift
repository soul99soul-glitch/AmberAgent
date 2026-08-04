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

    static let discussion = NovelGrokIsolationOptions(
        disableSearch: false,
        disableMemory: true
    )

    static func forPurpose(
        _ purpose: NovelModelPurpose,
        searchEnabled: Bool
    ) -> NovelGrokIsolationOptions {
        purpose == .discussion && searchEnabled ? .discussion : .novel
    }
}

struct NovelLiveTransportRequest: @unchecked Sendable {
    let providerSetting: ProviderSetting
    let messages: [UIMessage]
    let parameters: TextGenerationParams
    let grokIsolation: NovelGrokIsolationOptions?
}

struct NovelLiveTransportCallbacks: @unchecked Sendable {
    let onChunk: @Sendable (MessageChunk) -> Void
    let onCheckpoint: @Sendable (NovelResponsesResumeCursor) -> Void
    let onAskUser: @Sendable (NovelAskUserPrompt, String) -> Void
    let onComplete: @Sendable () -> Void
    let onFailure: @Sendable (NovelModelFailure) -> Void
    let onDisconnected: @Sendable (NovelModelFailure) -> Void

    init(
        onChunk: @escaping @Sendable (MessageChunk) -> Void,
        onCheckpoint: @escaping @Sendable (NovelResponsesResumeCursor) -> Void = { _ in },
        onAskUser: @escaping @Sendable (NovelAskUserPrompt, String) -> Void,
        onComplete: @escaping @Sendable () -> Void,
        onFailure: @escaping @Sendable (NovelModelFailure) -> Void,
        onDisconnected: @escaping @Sendable (NovelModelFailure) -> Void = { _ in }
    ) {
        self.onChunk = onChunk
        self.onCheckpoint = onCheckpoint
        self.onAskUser = onAskUser
        self.onComplete = onComplete
        self.onFailure = onFailure
        self.onDisconnected = onDisconnected
    }
}

struct NovelLiveCancellationHandle: @unchecked Sendable {
    private let action: @Sendable () -> Void
    private let remoteAction: @Sendable () -> Void

    init(
        _ action: @escaping @Sendable () -> Void,
        remoteAction: @escaping @Sendable () -> Void = {}
    ) {
        self.action = action
        self.remoteAction = remoteAction
    }

    func cancel() {
        action()
    }

    func cancelRemote() {
        remoteAction()
    }
}

typealias NovelLiveTransport = @Sendable (
    NovelLiveTransportRequest,
    NovelLiveTransportCallbacks
) -> NovelLiveCancellationHandle?

typealias NovelDurableResumeTransport = @Sendable (
    NovelModelResumeRequest,
    ProviderSetting,
    [CustomHeader],
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
actor NovelLiveModelAdapter: NovelDurableModelRunning {
    private struct Route: @unchecked Sendable {
        let provider: ProviderSetting
        let model: Model
        let resolved: NovelResolvedModel
    }

    private struct ActiveRun {
        let attemptID: UUID
        let continuation: AsyncStream<NovelModelEvent>.Continuation
        var cancellationHandle: NovelLiveCancellationHandle?
        var handleInstalled: Bool
        var isDurable: Bool
        var pendingResponseChunks: [MessageChunk]
        var latestResponseCursor: NovelResponsesResumeCursor?
    }

    private let catalogProvider: @Sendable () async -> NovelLiveModelCatalog
    private let kmpTransport: NovelLiveTransport
    private let discussionTransport: NovelLiveTransport?
    private let durableStartTransport: NovelLiveTransport?
    private let durableResumeTransport: NovelDurableResumeTransport?
    private let discussionSearchEnabled: @Sendable () async -> Bool
    private let grokTransport: NovelLiveTransport
    private let codex: NovelLiveCodexHooks

    private var activeRuns: [NovelRunID: ActiveRun] = [:]
    private var seenRunIDs: Set<NovelRunID> = []
    private var cancelledBeforeStart: Set<NovelRunID> = []
    private var terminalBeforeHandleInstall: Set<NovelRunID> = []
    private var detachedCancellationHandles: [NovelRunID: NovelLiveCancellationHandle] = [:]

    @MainActor
    init(
        sharedSettings: IOSSharedSettingsStore,
        streamingProvider: any IOSAgentTextProvider & IOSAgentStreamingProvider = OpenAIKmpProviderAdapter(),
        toolRuntime: ChatToolRuntime? = nil,
        grokTransport: NovelLiveTransport? = nil
    ) {
        let settingsSource = NovelSharedSettingsSource(sharedSettings)
        self.catalogProvider = {
            await settingsSource.catalog()
        }
        self.kmpTransport = Self.kmpTransport(using: streamingProvider)
        self.durableStartTransport = Self.backgroundStartTransport()
        self.durableResumeTransport = Self.backgroundResumeTransport()
        self.discussionTransport = toolRuntime.map { runtime in
            Self.discussionSearchTransport(
                using: streamingProvider,
                executors: { runtime.novelDiscussionToolExecutors() }
            )
        }
        self.discussionSearchEnabled = {
            await settingsSource.webSearchEnabled()
        }
        self.grokTransport = grokTransport ?? Self.productionGrokTransport
        self.codex = .production
    }

    init(
        catalogProvider: @escaping @Sendable () async -> NovelLiveModelCatalog,
        kmpTransport: @escaping NovelLiveTransport,
        discussionTransport: NovelLiveTransport? = nil,
        durableStartTransport: NovelLiveTransport? = nil,
        durableResumeTransport: NovelDurableResumeTransport? = nil,
        discussionSearchEnabled: @escaping @Sendable () async -> Bool = { true },
        grokTransport: NovelLiveTransport? = nil,
        codex: NovelLiveCodexHooks = .passthrough
    ) {
        self.catalogProvider = catalogProvider
        self.kmpTransport = kmpTransport
        self.discussionTransport = discussionTransport
        self.durableStartTransport = durableStartTransport
        self.durableResumeTransport = durableResumeTransport
        self.discussionSearchEnabled = discussionSearchEnabled
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
        let attemptID = UUID()
        pair.continuation.onTermination = { [weak self] termination in
            guard case .cancelled = termination else { return }
            Task { await self?.cancel(runID: request.runID) }
        }
        activeRuns[request.runID] = ActiveRun(
            attemptID: attemptID,
            continuation: pair.continuation,
            cancellationHandle: nil,
            handleInstalled: false,
            isDurable: false,
            pendingResponseChunks: [],
            latestResponseCursor: nil
        )

        do {
            let route = try await route(for: .fixed(
                providerID: request.model.ownerProviderID,
                modelID: request.model.modelID
            ))
            try ensureRoute(route, stillMatches: request.model)
            try ensureRunStillActive(request.runID)

            let isGrokWeb = IOSGrokWebProviderResolver.isGrokWebProvider(route.provider)
            let userEnabledSearch: Bool
            if request.purpose == .discussion {
                userEnabledSearch = await discussionSearchEnabled()
            } else {
                userEnabledSearch = false
            }
            let searchEnabled = userEnabledSearch
                && (isGrokWeb || discussionTransport != nil)
            try ensureRunStillActive(request.runID)
            let messages = Self.makeMessages(request.messages)
            var parameters = try Self.makeParameters(
                request.parameters,
                model: route.model,
                includeSearchTools: searchEnabled && !isGrokWeb,
                includeAskUserTool: (request.purpose == .discussion || request.purpose == .quickStart) &&
                    discussionTransport != nil &&
                    !isGrokWeb
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
                attemptID: attemptID,
                owner: self
            )
            let callbacks = NovelLiveTransportCallbacks(
                onChunk: { callbackSink.send(.chunk($0)) },
                onCheckpoint: { callbackSink.send(.checkpoint($0)) },
                onAskUser: { callbackSink.send(.askUser($0, preface: $1)) },
                onComplete: { callbackSink.send(.completed) },
                onFailure: { callbackSink.send(.failed($0)) },
                onDisconnected: { callbackSink.send(.disconnected($0)) }
            )
            let transportRequest = NovelLiveTransportRequest(
                providerSetting: effectiveProvider,
                messages: messages,
                parameters: parameters,
                grokIsolation: isGrokWeb ? .forPurpose(
                    request.purpose,
                    searchEnabled: searchEnabled
                ) : nil
            )
            let useDurableTransport = Self.usesBackgroundResponses(
                provider: effectiveProvider,
                purpose: request.purpose
            ) && durableStartTransport != nil
            let transport: NovelLiveTransport
            if isGrokWeb {
                transport = grokTransport
            } else if (request.purpose == .discussion || request.purpose == .quickStart),
                      let discussionTransport {
                transport = discussionTransport
            } else if useDurableTransport, let durableStartTransport {
                transport = durableStartTransport
            } else {
                transport = kmpTransport
            }
            if useDurableTransport {
                activeRuns[request.runID]?.isDurable = true
            }
            let handle = transport(
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

    func resume(_ request: NovelModelResumeRequest) async throws -> AsyncStream<NovelModelEvent> {
        guard activeRuns[request.runID] == nil else {
            throw NovelModelAdapterError.duplicateRunID(request.runID)
        }
        guard cancelledBeforeStart.remove(request.runID) == nil else {
            throw Self.cancelledFailure
        }
        guard request.purpose == .prose || request.purpose == .polish else {
            throw Self.failure(
                code: "background_resume_unsupported_purpose",
                message: "当前小说任务类型不能恢复后台 Responses。"
            )
        }
        guard durableResumeTransport != nil else {
            throw Self.failure(
                code: "background_resume_unavailable",
                message: "当前模型运行时不支持后台 Responses 恢复。",
                isRetryable: true
            )
        }

        seenRunIDs.insert(request.runID)
        let pair = AsyncStream<NovelModelEvent>.makeStream(bufferingPolicy: .unbounded)
        let attemptID = UUID()
        pair.continuation.onTermination = { [weak self] termination in
            guard case .cancelled = termination else { return }
            Task { await self?.cancel(runID: request.runID) }
        }
        activeRuns[request.runID] = ActiveRun(
            attemptID: attemptID,
            continuation: pair.continuation,
            cancellationHandle: nil,
            handleInstalled: false,
            isDurable: true,
            pendingResponseChunks: [],
            latestResponseCursor: request.cursor
        )

        do {
            let route = try await route(for: .fixed(
                providerID: request.model.ownerProviderID,
                modelID: request.model.modelID
            ))
            try ensureRoute(route, stillMatches: request.model)
            guard Self.usesBackgroundResponses(
                provider: route.provider,
                purpose: request.purpose
            ) else {
                throw Self.failure(
                    code: "background_resume_unsupported_provider",
                    message: "当前固定模型不是官方 OpenAI Responses，无法恢复该后台响应。"
                )
            }
            try ensureRunStillActive(request.runID)

            let callbackSink = NovelLiveCallbackSink(
                runID: request.runID,
                attemptID: attemptID,
                owner: self
            )
            let callbacks = NovelLiveTransportCallbacks(
                onChunk: { callbackSink.send(.chunk($0)) },
                onCheckpoint: { callbackSink.send(.checkpoint($0)) },
                onAskUser: { callbackSink.send(.askUser($0, preface: $1)) },
                onComplete: { callbackSink.send(.completed) },
                onFailure: { callbackSink.send(.failed($0)) },
                onDisconnected: { callbackSink.send(.disconnected($0)) }
            )
            guard let durableResumeTransport else {
                throw Self.failure(
                    code: "background_resume_unavailable",
                    message: "当前模型运行时不支持后台 Responses 恢复。",
                    isRetryable: true
                )
            }
            let handle = durableResumeTransport(
                request,
                route.provider,
                route.model.customHeaders,
                callbacks
            )
            try install(handle, for: request.runID)
            if handle != nil {
                detachedCancellationHandles[request.runID] = nil
            }
            return pair.stream
        } catch {
            abandonRun(request.runID)
            throw error
        }
    }

    func cancel(runID: NovelRunID) async {
        if let active = activeRuns.removeValue(forKey: runID) {
            cancelledBeforeStart.insert(runID)
            let detached = detachedCancellationHandles.removeValue(forKey: runID)
            active.continuation.finish()
            // The transport-side cursor box is updated before the callback is
            // drained into this actor. Invoke the remote action for every durable
            // run; it is a no-op until that box has a response ID, avoiding a
            // race where cancel arrives between checkpoint receipt and actor
            // processing. During resume setup, the prior detached handle remains
            // the only remote owner until the replacement handle is installed.
            if active.isDurable {
                if let handle = active.cancellationHandle {
                    handle.cancelRemote()
                } else {
                    detached?.cancelRemote()
                }
            }
            active.cancellationHandle?.cancel()
            return
        }
        if let detached = detachedCancellationHandles.removeValue(forKey: runID) {
            cancelledBeforeStart.insert(runID)
            detached.cancelRemote()
            return
        }
        if !seenRunIDs.contains(runID) {
            cancelledBeforeStart.insert(runID)
        }
    }

    func detach(runID: NovelRunID) async {
        guard let active = activeRuns.removeValue(forKey: runID) else { return }
        if active.isDurable, let handle = active.cancellationHandle {
            detachedCancellationHandles[runID] = handle
        }
        active.continuation.finish()
        // Detach only closes the local SSE job. The server-side response stays
        // stored and can be resumed from the last atomic frame.
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

    fileprivate func receive(
        _ frame: NovelLiveCallbackFrame,
        runID: NovelRunID,
        attemptID: UUID
    ) {
        guard var active = activeRuns[runID], active.attemptID == attemptID else { return }

        switch frame {
        case .chunk(let chunk):
            if active.isDurable {
                active.pendingResponseChunks.append(chunk)
                activeRuns[runID] = active
                return
            }
            for event in Self.events(from: chunk) {
                active.continuation.yield(event)
            }
            if let failure = Self.outputLimitFailure(in: chunk) {
                receive(.failed(failure), runID: runID, attemptID: attemptID)
            }
        case .checkpoint(let cursor):
            guard active.isDurable else { return }
            let chunks = active.pendingResponseChunks
            active.pendingResponseChunks.removeAll(keepingCapacity: true)
            active.latestResponseCursor = cursor
            activeRuns[runID] = active
            let frameEvents = Self.frameEvents(from: chunks)
            active.continuation.yield(.responseFrame(NovelModelResponseFrame(
                cursor: cursor,
                events: frameEvents
            )))
            if let failure = chunks.compactMap(Self.outputLimitFailure(in:)).first {
                receive(.failed(failure), runID: runID, attemptID: attemptID)
            }
        case .completed:
            activeRuns.removeValue(forKey: runID)
            detachedCancellationHandles[runID] = nil
            if !active.handleInstalled {
                terminalBeforeHandleInstall.insert(runID)
            }
            active.continuation.yield(.completed)
            active.continuation.finish()
        case .askUser(let prompt, let preface):
            activeRuns.removeValue(forKey: runID)
            detachedCancellationHandles[runID] = nil
            if !active.handleInstalled {
                terminalBeforeHandleInstall.insert(runID)
            }
            active.continuation.yield(.askUser(prompt, preface: preface))
            active.continuation.finish()
        case .failed(let failure):
            let detachedHandle = detachedCancellationHandles.removeValue(forKey: runID)
            if active.isDurable, active.latestResponseCursor != nil {
                (active.cancellationHandle ?? detachedHandle)?.cancelRemote()
            }
            activeRuns.removeValue(forKey: runID)
            if !active.handleInstalled {
                terminalBeforeHandleInstall.insert(runID)
            }
            active.continuation.yield(.failed(failure))
            active.continuation.finish()
        case .disconnected(let failure):
            activeRuns.removeValue(forKey: runID)
            if !active.handleInstalled {
                terminalBeforeHandleInstall.insert(runID)
            }
            if active.isDurable, active.latestResponseCursor != nil {
                if let handle = active.cancellationHandle {
                    detachedCancellationHandles[runID] = handle
                }
                active.continuation.yield(.responseDisconnected(failure))
            } else {
                detachedCancellationHandles[runID] = nil
                active.continuation.yield(.failed(failure))
            }
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
        guard request.grokIsolation != nil else {
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
        guard let isolation = request.grokIsolation else {
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
                    options: IOSGrokWebRequestOptions(
                        disableSearch: isolation.disableSearch,
                        disableMemory: isolation.disableMemory
                    ),
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

    /// First-party Responses background mode is deliberately opt-in and
    /// limited to prose routes that can be resumed from a stored
    /// response. Quick Start and discussion keep their existing tool paths.
    static func usesBackgroundResponses(
        provider: ProviderSetting,
        purpose: NovelModelPurpose
    ) -> Bool {
        guard purpose == .prose || purpose == .polish,
              let openAI = provider as? ProviderSetting.OpenAI,
              openAI.useResponseApi,
              let url = URL(string: openAI.baseUrl),
              url.scheme?.lowercased() == "https",
              url.host?.lowercased() == "api.openai.com",
              url.port == nil,
              url.user == nil,
              url.password == nil else {
            return false
        }
        return true
    }

    static func backgroundStartTransport() -> NovelLiveTransport {
        let transport = OpenAIResponsesBackgroundTransport()
        return { request, callbacks in
            guard let openAI = request.providerSetting as? ProviderSetting.OpenAI else {
                callbacks.onFailure(failure(
                    code: "background_provider_invalid",
                    message: "后台 Responses 请求的服务商配置无效。",
                    isRetryable: false
                ))
                return nil
            }

            let cursorBox = NovelResponsesCursorBox()
            let job: Kotlinx_coroutines_coreJob?
            do {
                job = try transport.startBackground(
                    providerSetting: openAI,
                    messages: request.messages,
                    params: request.parameters,
                    onChunk: callbacks.onChunk,
                    onCheckpoint: { responseID, sequenceNumber in
                        let cursor = NovelResponsesResumeCursor(
                            responseID: responseID,
                            sequenceNumber: sequenceNumber.int64Value
                        )
                        cursorBox.set(cursor)
                        callbacks.onCheckpoint(cursor)
                    },
                    onComplete: callbacks.onComplete,
                    onDisconnected: { error in
                        callbacks.onDisconnected(Self.backgroundFailure(error))
                    },
                    onFailure: { error in
                        callbacks.onFailure(Self.backgroundFailure(error, disconnected: false))
                    }
                )
            } catch {
                callbacks.onFailure(Self.backgroundFailure(error, disconnected: false))
                return nil
            }

            guard let job else {
                callbacks.onFailure(Self.failure(
                    code: "background_transport_unavailable",
                    message: "后台 Responses 运行时未能启动。",
                    isRetryable: true
                ))
                return nil
            }
            let localBox = NovelKotlinJobBox(job)
            return NovelLiveCancellationHandle(
                {
                    localBox.cancel()
                },
                remoteAction: {
                    guard let cursor = cursorBox.value else { return }
                    _ = try? transport.cancelBackground(
                        providerSetting: openAI,
                        responseId: cursor.responseID,
                        customHeaders: request.parameters.customHeaders,
                        onComplete: {},
                        onError: { _ in }
                    )
                }
            )
        }
    }

    static func backgroundResumeTransport() -> NovelDurableResumeTransport {
        let transport = OpenAIResponsesBackgroundTransport()
        return { request, provider, customHeaders, callbacks in
            guard let openAI = provider as? ProviderSetting.OpenAI else {
                callbacks.onFailure(failure(
                    code: "background_provider_invalid",
                    message: "后台 Responses 恢复的服务商配置无效。",
                    isRetryable: false
                ))
                return nil
            }

            let cursorBox = NovelResponsesCursorBox(request.cursor)
            let job: Kotlinx_coroutines_coreJob?
            do {
                job = try transport.resumeBackground(
                    providerSetting: openAI,
                    responseId: request.cursor.responseID,
                    startingAfter: request.cursor.sequenceNumber,
                    customHeaders: customHeaders,
                    onChunk: callbacks.onChunk,
                    onCheckpoint: { responseID, sequenceNumber in
                        let cursor = NovelResponsesResumeCursor(
                            responseID: responseID,
                            sequenceNumber: sequenceNumber.int64Value
                        )
                        cursorBox.set(cursor)
                        callbacks.onCheckpoint(cursor)
                    },
                    onComplete: callbacks.onComplete,
                    onDisconnected: { error in
                        callbacks.onDisconnected(Self.backgroundFailure(error))
                    },
                    onFailure: { error in
                        callbacks.onFailure(Self.backgroundFailure(error, disconnected: false))
                    }
                )
            } catch {
                callbacks.onFailure(Self.backgroundFailure(error, disconnected: false))
                return nil
            }

            guard let job else {
                callbacks.onFailure(Self.failure(
                    code: "background_transport_unavailable",
                    message: "后台 Responses 恢复运行时未能启动。",
                    isRetryable: true
                ))
                return nil
            }
            let localBox = NovelKotlinJobBox(job)
            return NovelLiveCancellationHandle(
                {
                    localBox.cancel()
                },
                remoteAction: {
                    guard let cursor = cursorBox.value else { return }
                    _ = try? transport.cancelBackground(
                        providerSetting: openAI,
                        responseId: cursor.responseID,
                        customHeaders: customHeaders,
                        onComplete: {},
                        onError: { _ in }
                    )
                }
            )
        }
    }

    private static func backgroundFailure(
        _ error: Error,
        disconnected: Bool = true
    ) -> NovelModelFailure {
        NovelModelFailure(
            code: disconnected ? "provider_background_disconnected" : "provider_background_failed",
            message: (error as NSError).localizedDescription,
            isRetryable: disconnected
        )
    }

    private static func backgroundFailure(
        _ error: KotlinThrowable,
        disconnected: Bool = true
    ) -> NovelModelFailure {
        NovelModelFailure(
            code: disconnected ? "provider_background_disconnected" : "provider_background_failed",
            message: error.message ?? String(describing: error),
            isRetryable: disconnected
        )
    }
}

extension NovelLiveModelAdapter {
    /// Accumulates assistant text across the tool engine's multi-step loop.
    /// Each `streamStep` re-accumulates its own text from empty (see
    /// `IOSAgentToolEngine.StreamStepState`), so without this, a later step's
    /// `replacementChunk` (a full-replace signal) would erase any visible text
    /// the model produced in an earlier step before invoking a tool.
    /// `@unchecked Sendable` + `NSLock`: `onAssistantTurnStarted` fires from
    /// the `@MainActor` engine loop, but `onAssistantText` fires from whatever
    /// thread the KMP streaming bridge calls back on (not guaranteed MainActor).
    private final class DiscussionStepTextAccumulator: @unchecked Sendable {
        private let lock = NSLock()
        private var committedSteps: [String] = []
        private var currentStep = ""

        func startNewTurn() {
            lock.lock()
            defer { lock.unlock() }
            if !currentStep.isEmpty {
                committedSteps.append(currentStep)
            }
            currentStep = ""
        }

        func update(currentStep text: String) -> String {
            lock.lock()
            defer { lock.unlock() }
            currentStep = text
            return (committedSteps + [currentStep])
                .filter { !$0.isEmpty }
                .joined(separator: "\n\n")
        }
    }

    static func discussionSearchTransport(
        using provider: any IOSAgentTextProvider,
        executors: @escaping @MainActor @Sendable () -> [String: any IOSToolExecutor]
    ) -> NovelLiveTransport {
        { request, callbacks in
            let task = Task { @MainActor in
                let engine = IOSAgentToolEngine(
                    provider: provider,
                    executors: executors(),
                    configuration: .init(maxSteps: 4, honorApprovalPause: true)
                )
                let stepText = DiscussionStepTextAccumulator()
                let result = await engine.run(
                    providerSetting: request.providerSetting,
                    messages: request.messages,
                    params: request.parameters,
                    onAssistantTurnStarted: {
                        stepText.startNewTurn()
                    },
                    onAssistantText: { text in
                        callbacks.onChunk(replacementChunk(stepText.update(currentStep: text)))
                    }
                )
                guard !Task.isCancelled else { return }
                if let failureMessage = result.providerFailureMessage {
                    callbacks.onFailure(failure(
                        code: "discussion_provider_failed",
                        message: failureMessage,
                        isRetryable: true
                    ))
                    return
                }
                if result.hitStepLimit {
                    callbacks.onFailure(failure(
                        code: "discussion_tool_step_limit",
                        message: "讨论时连续搜索次数过多，请缩小问题后重试。",
                        isRetryable: true
                    ))
                    return
                }
                // I-5:守护停止不得伪装成正常完成——引擎因重复相同调用而停止时,
                // 与 hitStepLimit 同样按可重试失败呈现,而不是落进下面的完成分支。
                if result.guardStopped {
                    callbacks.onFailure(failure(
                        code: "discussion_tool_loop_guard",
                        message: "讨论时模型反复执行相同搜索，已停止。请换个问法后重试。",
                        isRetryable: true
                    ))
                    return
                }
                if let approval = result.pendingApproval,
                   approval.toolName == "ask_user" {
                    do {
                        let prompt = try decodeAskUserPrompt(approval.arguments)
                        callbacks.onAskUser(prompt, joinedAssistantText(in: result.messages))
                    } catch {
                        callbacks.onFailure(failure(
                            code: "discussion_ask_user_invalid",
                            message: "模型提出的问题格式无法读取，请重试。",
                            isRetryable: true
                        ))
                    }
                    return
                }
                if result.pendingApproval != nil {
                    callbacks.onFailure(failure(
                        code: "discussion_tool_approval_required",
                        message: "当前搜索需要额外确认，请检查搜索服务设置后重试。",
                        isRetryable: true
                    ))
                    return
                }
                let finalText = joinedAssistantText(in: result.messages)
                guard !finalText.isEmpty else {
                    callbacks.onFailure(failure(
                        code: "discussion_empty_response",
                        message: "模型完成了搜索，但没有返回讨论内容，请重试。",
                        isRetryable: true
                    ))
                    return
                }
                callbacks.onChunk(replacementChunk(finalText))
                callbacks.onComplete()
            }
            return NovelLiveCancellationHandle {
                task.cancel()
            }
        }
    }
}

private extension NovelLiveModelAdapter {
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
        model: Model,
        includeSearchTools: Bool = false,
        includeAskUserTool: Bool = false
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
            tools: includeSearchTools
                ? Set([BuiltInTools.Search.shared])
                : Set<BuiltInTools>(),
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
            tools: (includeAskUserTool ? [ToolKt.createAskUserToolDeclaration()] : []) +
                (includeSearchTools ? [
                    ToolKt.createSearchWebToolDeclaration(),
                    ToolKt.createScrapeWebToolDeclaration(),
                ] : []),
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
                } else if hasReasoningActivity(in: delta) {
                    events.append(.activity)
                }
            } else if let message = choice.message {
                let text = text(in: message)
                if !text.isEmpty {
                    events.append(.textReplacement(text))
                } else if hasReasoningActivity(in: message) {
                    events.append(.activity)
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

    static func frameEvents(from chunks: [MessageChunk]) -> [NovelModelFrameEvent] {
        chunks.flatMap { chunk in
            var events: [NovelModelFrameEvent] = []
            for choice in chunk.choices {
                if let delta = choice.delta {
                    let text = text(in: delta)
                    if !text.isEmpty {
                        events.append(.textDelta(text))
                    } else if hasReasoningActivity(in: delta) {
                        events.append(.activity)
                    }
                } else if let message = choice.message {
                    let text = text(in: message)
                    if !text.isEmpty {
                        events.append(.textReplacement(text))
                    } else if hasReasoningActivity(in: message) {
                        events.append(.activity)
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
    }

    static func outputLimitFailure(in chunk: MessageChunk) -> NovelModelFailure? {
        let reasons = Set(chunk.choices.compactMap { $0.finishReason?.lowercased() })
        guard !reasons.isDisjoint(with: ["length", "max_tokens", "max_output_tokens"]) else {
            return nil
        }
        return NovelModelFailure(
            code: "output_limit_reached",
            message: "模型回复达到输出上限，请重试。",
            isRetryable: true
        )
    }

    static func text(in message: UIMessage) -> String {
        message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
    }

    static func hasReasoningActivity(in message: UIMessage) -> Bool {
        message.parts.contains {
            guard let reasoning = $0 as? UIMessagePart.Reasoning else { return false }
            return !reasoning.reasoning.isEmpty
        }
    }

    static func joinedAssistantText(in messages: [UIMessage]) -> String {
        messages
            .filter { $0.role == MessageRole.assistant }
            .map(text(in:))
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
    }

    static func decodeAskUserPrompt(_ arguments: String) throws -> NovelAskUserPrompt {
        guard let data = arguments.data(using: .utf8) else {
            throw NovelError.invalidInput("Ask User arguments are not UTF-8.")
        }
        let prompt = try JSONDecoder().decode(NovelAskUserPrompt.self, from: data)
        try NovelGenerationReducer.validateAskUserPrompt(prompt)
        return prompt
    }

    static func replacementChunk(_ text: String) -> MessageChunk {
        MessageChunk(
            id: UUID().uuidString,
            model: "",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: UIMessage.companion.assistant(prompt: text),
                finishReason: nil
            )],
            usage: nil
        )
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
        case .providerDisabled: "configuration_provider_disabled"
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
    case checkpoint(NovelResponsesResumeCursor)
    case askUser(NovelAskUserPrompt, preface: String)
    case completed
    case failed(NovelModelFailure)
    case disconnected(NovelModelFailure)
}

private final class NovelLiveCallbackSink: @unchecked Sendable {
    private let lock = NSLock()
    private let runID: NovelRunID
    private let attemptID: UUID
    private weak var owner: NovelLiveModelAdapter?
    private var frames: [NovelLiveCallbackFrame] = []
    private var frameHead = 0
    private var isDraining = false

    init(runID: NovelRunID, attemptID: UUID, owner: NovelLiveModelAdapter) {
        self.runID = runID
        self.attemptID = attemptID
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
            await owner?.receive(frame, runID: runID, attemptID: attemptID)
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

private final class NovelResponsesCursorBox: @unchecked Sendable {
    private let lock = NSLock()
    private var cursor: NovelResponsesResumeCursor?

    init(_ initial: NovelResponsesResumeCursor? = nil) {
        cursor = initial
    }

    var value: NovelResponsesResumeCursor? {
        lock.lock()
        defer { lock.unlock() }
        return cursor
    }

    func set(_ next: NovelResponsesResumeCursor) {
        lock.lock()
        cursor = next
        lock.unlock()
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

    @MainActor
    func webSearchEnabled() -> Bool {
        sharedSettings.snapshot.enableWebSearch
    }
}
