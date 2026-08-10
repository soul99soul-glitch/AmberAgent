import Foundation

enum NovelStructuredModelTaskKind: String, Codable, Equatable, CaseIterable, Sendable {
    case stateDelta
    case stateRebuild
    case discussionArchive
    case polishDrift
    case continuityAudit
    case chapterPlanAcceptance
    case chapterPlanProposal
}

enum NovelStructuredModelTask: Equatable, Sendable {
    case stateDelta(context: String, manuscript: String)
    case stateRebuild(baseContext: String, manuscript: String)
    case discussionArchive(discussion: String)
    case polishDrift(sourceChapter: String, candidate: String)
    /// 剧情矛盾检查。`priorFindings` 是前几块已报出的问题摘要,让后一块能与前文对照;
    /// 首块传空串。`manuscript` 是本块正文,含 `# Chapter N: 标题` 标头。
    case continuityAudit(priorFindings: String, manuscript: String)
    case chapterPlanAcceptance(plan: String, candidate: String, recentHighlights: String)
    case chapterPlanProposal(context: String)
}

struct NovelStructuredModelExecutionRequest: Equatable, Sendable {
    let runID: NovelRunID
    let modelPolicy: NovelProjectModelPolicy
    let task: NovelStructuredModelTask
}

enum NovelStructuredModelOutput: Equatable, Sendable {
    case stateDelta(NovelStateDeltaV1)
    case stateRebuild(NovelStateRebuildV1)
    case discussionArchive(NovelDiscussionArchiveV1)
    case polishDrift(NovelPolishDriftV1)
    case continuityAudit(NovelContinuityAuditV1)
    case chapterPlanAcceptance(NovelChapterPlanAcceptanceV1)
    case chapterPlanProposal(NovelChapterPlanProposalV1)
}

struct NovelStructuredModelExecutionEvidence: Equatable, Sendable {
    let output: NovelStructuredModelOutput
    let resolvedModel: NovelResolvedModel
    let modelRequest: NovelModelRequest
    let requestSHA256: String
    let parameters: [String: String]
}

struct NovelStructuredModelInvocation: Equatable, Sendable {
    let executionRequest: NovelStructuredModelExecutionRequest
    let resolvedModel: NovelResolvedModel
    let modelRequest: NovelModelRequest
    let requestSHA256: String
    let parameters: [String: String]
}

struct NovelStructuredModelPreparation: Equatable, Sendable {
    let modelPolicy: NovelProjectModelPolicy
    let taskKind: NovelStructuredModelTaskKind
    let resolvedModel: NovelResolvedModel
    let parameters: NovelModelParameters
    let requestedInputBudgetTokens: Int
    let effectiveInputBudgetTokens: Int
}

struct NovelStructuredModelExecutionFailure: Error, Equatable, Sendable {
    let failure: NovelFailure
    let structuredOutputFailure: NovelStructuredOutputFailure?

    init(
        code: String,
        message: String,
        isRetryable: Bool,
        structuredOutputFailure: NovelStructuredOutputFailure? = nil
    ) {
        failure = NovelFailure(
            code: code,
            message: message,
            isRetryable: isRetryable
        )
        self.structuredOutputFailure = structuredOutputFailure
    }
}

extension NovelStructuredModelExecutionFailure: LocalizedError {
    var errorDescription: String? { failure.message }
}

private enum NovelStructuredModelRaceOutcome: Sendable {
    case succeeded(NovelStructuredModelExecutionEvidence)
    case failed(NovelStructuredModelExecutionFailure)
    case timedOut
}

private final class NovelStructuredModelRaceLatch: @unchecked Sendable {
    private let lock = NSLock()
    private var outcome: NovelStructuredModelRaceOutcome?
    private var continuation: CheckedContinuation<NovelStructuredModelRaceOutcome, Never>?

    func resolve(_ proposed: NovelStructuredModelRaceOutcome) {
        let waiting: CheckedContinuation<NovelStructuredModelRaceOutcome, Never>? = lock.withLock {
            guard outcome == nil else { return nil }
            outcome = proposed
            defer { continuation = nil }
            return continuation
        }
        waiting?.resume(returning: proposed)
    }

    func wait() async -> NovelStructuredModelRaceOutcome {
        await withCheckedContinuation { waiting in
            let resolved: NovelStructuredModelRaceOutcome? = lock.withLock {
                if let outcome { return outcome }
                continuation = waiting
                return nil
            }
            if let resolved { waiting.resume(returning: resolved) }
        }
    }
}

private final class NovelStructuredModelNoOutputHeartbeat: @unchecked Sendable {
    private let lock = NSLock()
    private var lastOutputUptime = ProcessInfo.processInfo.systemUptime

    func recordOutput() {
        lock.withLock {
            lastOutputUptime = ProcessInfo.processInfo.systemUptime
        }
    }

    func hasTimedOut(after timeout: TimeInterval) -> Bool {
        lock.withLock {
            ProcessInfo.processInfo.systemUptime - lastOutputUptime >= timeout
        }
    }
}

/// Executes the module's hidden JSON-only model calls. It deliberately sits
/// above `NovelModelRunning`, so live and scripted transports share terminal
/// handling and strict decoding before a reducer can see model output.
struct NovelStructuredModelExecutor: Sendable {
    static let maximumInternalInputBudgetTokens = 64_000
    static let unknownWindowFallbackInputBudgetTokens = 16_000

    let modelRunner: any NovelModelRunning
    /// 剧情同步是否允许推理。默认读小说设置；测试可注入。
    var stateSyncReasoningEnabled: @Sendable () -> Bool = {
        NovelCreationModelPreferences.shared.stateSyncReasoningEnabled
    }

    func prepare(
        modelPolicy: NovelProjectModelPolicy,
        taskKind: NovelStructuredModelTaskKind,
        requestedInputBudgetTokens: Int
    ) async throws -> NovelStructuredModelPreparation {
        let model = try await resolveModel(for: modelPolicy)
        let parameters = taskKind.parameters(
            stateSyncReasoningEnabled: stateSyncReasoningEnabled()
        )
        return NovelStructuredModelPreparation(
            modelPolicy: modelPolicy,
            taskKind: taskKind,
            resolvedModel: model,
            parameters: parameters,
            requestedInputBudgetTokens: requestedInputBudgetTokens,
            effectiveInputBudgetTokens: try effectiveInputBudget(
                requestedInputBudgetTokens: requestedInputBudgetTokens,
                model: model,
                outputReservationTokens: taskKind.outputReservationTokens
            )
        )
    }

    func execute(
        _ request: NovelStructuredModelExecutionRequest
    ) async throws -> NovelStructuredModelOutput {
        try await executeWithEvidence(request).output
    }

    func executeWithEvidence(
        _ request: NovelStructuredModelExecutionRequest
    ) async throws -> NovelStructuredModelExecutionEvidence {
        let model = try await resolveModel(for: request.modelPolicy)
        let invocation = try makeInvocation(
            request,
            resolvedModel: model,
            parameters: liveParameters(for: request.task)
        )
        return try await executePrepared(invocation)
    }

    func executeWithEvidence(
        _ request: NovelStructuredModelExecutionRequest,
        noOutputTimeout: TimeInterval
    ) async throws -> NovelStructuredModelExecutionEvidence {
        let model = try await resolveModel(for: request.modelPolicy)
        let invocation = try makeInvocation(
            request,
            resolvedModel: model,
            parameters: liveParameters(for: request.task)
        )
        return try await executePrepared(
            invocation,
            noOutputTimeout: noOutputTimeout
        )
    }

    private func liveParameters(for task: NovelStructuredModelTask) -> NovelModelParameters {
        task.parameters(stateSyncReasoningEnabled: stateSyncReasoningEnabled())
    }

    func executeWithEvidence(
        _ request: NovelStructuredModelExecutionRequest,
        preparation: NovelStructuredModelPreparation
    ) async throws -> NovelStructuredModelExecutionEvidence {
        guard preparation.modelPolicy == request.modelPolicy,
              preparation.taskKind == request.task.kind else {
            throw NovelError.invalidInput(
                "The structured model preparation does not match its request."
            )
        }
        return try await executePrepared(try makeInvocation(
            request,
            resolvedModel: preparation.resolvedModel,
            parameters: preparation.parameters
        ))
    }

    func prepareInvocation(
        _ request: NovelStructuredModelExecutionRequest,
        preparation: NovelStructuredModelPreparation
    ) throws -> NovelStructuredModelInvocation {
        guard preparation.modelPolicy == request.modelPolicy,
              preparation.taskKind == request.task.kind else {
            throw NovelError.invalidInput(
                "The structured model preparation does not match its request."
            )
        }
        return try makeInvocation(
            request,
            resolvedModel: preparation.resolvedModel,
            parameters: preparation.parameters
        )
    }

    private func resolveModel(
        for policy: NovelProjectModelPolicy
    ) async throws -> NovelResolvedModel {
        do {
            return try await modelRunner.resolveModel(for: policy)
        } catch let failure as NovelModelFailure {
            throw NovelStructuredModelExecutionFailure(
                code: failure.code,
                message: failure.message,
                isRetryable: failure.isRetryable
            )
        } catch {
            throw NovelStructuredModelExecutionFailure(
                code: "model_unavailable",
                message: error.localizedDescription,
                isRetryable: true
            )
        }
    }

    func executePrepared(
        _ invocation: NovelStructuredModelInvocation
    ) async throws -> NovelStructuredModelExecutionEvidence {
        try await executePrepared(invocation, onTextOutput: nil)
    }

    private func executePrepared(
        _ invocation: NovelStructuredModelInvocation,
        onTextOutput: (@Sendable () -> Void)?
    ) async throws -> NovelStructuredModelExecutionEvidence {
        let request = invocation.executionRequest
        let modelRequest = invocation.modelRequest
        let stream: AsyncStream<NovelModelEvent>
        do {
            stream = try await modelRunner.start(modelRequest)
        } catch let failure as NovelModelFailure {
            throw NovelStructuredModelExecutionFailure(
                code: failure.code,
                message: failure.message,
                isRetryable: failure.isRetryable
            )
        } catch {
            throw NovelStructuredModelExecutionFailure(
                code: "model_start_failed",
                message: error.localizedDescription,
                isRetryable: true
            )
        }

        let text: String
        do {
            text = try await withTaskCancellationHandler {
                try await consume(stream, onTextOutput: onTextOutput)
            } onCancel: {
                Task { await modelRunner.cancel(runID: request.runID) }
            }
        } catch let failure as NovelStructuredModelExecutionFailure {
            throw failure
        } catch is CancellationError {
            throw NovelStructuredModelExecutionFailure(
                code: "cancelled",
                message: "模型任务已取消，可以重试。",
                isRetryable: true
            )
        } catch {
            throw NovelStructuredModelExecutionFailure(
                code: "model_stream_failed",
                message: error.localizedDescription,
                isRetryable: true
            )
        }

        do {
            let output = try request.task.decode(text)
            return NovelStructuredModelExecutionEvidence(
                output: output,
                resolvedModel: invocation.resolvedModel,
                modelRequest: modelRequest,
                requestSHA256: invocation.requestSHA256,
                parameters: invocation.parameters
            )
        } catch let failure as NovelStructuredOutputFailure {
            throw NovelStructuredModelExecutionFailure(
                code: "invalid_structured_output",
                message: failure.message,
                isRetryable: true,
                structuredOutputFailure: failure
            )
        } catch {
            throw NovelStructuredModelExecutionFailure(
                code: "invalid_structured_output",
                message: error.localizedDescription,
                isRetryable: true
            )
        }
    }

    func executePrepared(
        _ invocation: NovelStructuredModelInvocation,
        noOutputTimeout: TimeInterval
    ) async throws -> NovelStructuredModelExecutionEvidence {
        let timeout = max(0.01, noOutputTimeout)
        let heartbeat = NovelStructuredModelNoOutputHeartbeat()
        let latch = NovelStructuredModelRaceLatch()
        let providerTask = Task.detached {
            do {
                latch.resolve(.succeeded(try await executePrepared(
                    invocation,
                    onTextOutput: { heartbeat.recordOutput() }
                )))
            } catch let failure as NovelStructuredModelExecutionFailure {
                latch.resolve(.failed(failure))
            } catch {
                latch.resolve(.failed(NovelStructuredModelExecutionFailure(
                    code: "model_stream_failed",
                    message: error.localizedDescription,
                    isRetryable: true
                )))
            }
        }
        let timeoutTask = Task.detached {
            let interval = max(0.01, min(timeout / 4, 0.1))
            while !Task.isCancelled {
                try? await Task.sleep(
                    nanoseconds: UInt64(interval * 1_000_000_000)
                )
                guard !Task.isCancelled else { return }
                if heartbeat.hasTimedOut(after: timeout) {
                    latch.resolve(.timedOut)
                    return
                }
            }
        }
        let outcome = await withTaskCancellationHandler {
            await latch.wait()
        } onCancel: {
            providerTask.cancel()
            timeoutTask.cancel()
            latch.resolve(.failed(NovelStructuredModelExecutionFailure(
                code: "cancelled",
                message: "模型任务已取消，可以重试。",
                isRetryable: true
            )))
            Task { await modelRunner.cancel(runID: invocation.executionRequest.runID) }
        }
        timeoutTask.cancel()

        switch outcome {
        case .succeeded(let evidence):
            return evidence
        case .failed(let failure):
            throw failure
        case .timedOut:
            providerTask.cancel()
            await modelRunner.cancel(runID: invocation.executionRequest.runID)
            throw NovelStructuredModelExecutionFailure(
                code: "structured_no_output_timeout",
                message: "模型持续无输出，请稍后重试。",
                isRetryable: true
            )
        }
    }

    func executePrepared(
        _ invocation: NovelStructuredModelInvocation,
        timeout: TimeInterval
    ) async throws -> NovelStructuredModelExecutionEvidence {
        let latch = NovelStructuredModelRaceLatch()
        let providerTask = Task.detached {
            do {
                latch.resolve(.succeeded(try await executePrepared(invocation)))
            } catch let failure as NovelStructuredModelExecutionFailure {
                latch.resolve(.failed(failure))
            } catch is CancellationError {
                latch.resolve(.failed(NovelStructuredModelExecutionFailure(
                    code: "cancelled",
                    message: "状态同步已取消。",
                    isRetryable: true
                )))
            } catch {
                latch.resolve(.failed(NovelStructuredModelExecutionFailure(
                    code: "model_stream_failed",
                    message: error.localizedDescription,
                    isRetryable: true
                )))
            }
        }
        let timeoutTask = Task.detached {
            let nanoseconds = UInt64(max(0.01, timeout) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            latch.resolve(.timedOut)
        }
        let outcome = await withTaskCancellationHandler {
            await latch.wait()
        } onCancel: {
            providerTask.cancel()
            timeoutTask.cancel()
            latch.resolve(.failed(NovelStructuredModelExecutionFailure(
                code: "cancelled",
                message: "状态同步已取消。",
                isRetryable: true
            )))
            Task { await modelRunner.cancel(runID: invocation.executionRequest.runID) }
        }
        timeoutTask.cancel()

        switch outcome {
        case .succeeded(let evidence):
            return evidence
        case .failed(let failure):
            throw failure
        case .timedOut:
            providerTask.cancel()
            throw NovelStructuredModelExecutionFailure(
                code: "structured_request_timeout",
                message: "状态同步请求超时，请稍后重试。",
                isRetryable: true
            )
        }
    }

    private func makeInvocation(
        _ request: NovelStructuredModelExecutionRequest,
        resolvedModel: NovelResolvedModel,
        parameters: NovelModelParameters
    ) throws -> NovelStructuredModelInvocation {
        let modelRequest = NovelModelRequest(
            runID: request.runID,
            model: resolvedModel,
            purpose: request.task.purpose,
            messages: request.task.messages,
            parameters: parameters
        )
        return NovelStructuredModelInvocation(
            executionRequest: request,
            resolvedModel: resolvedModel,
            modelRequest: modelRequest,
            requestSHA256: try modelRequest.canonicalSHA256(),
            parameters: modelRequest.parameters.evidenceDictionary
        )
    }

    private func effectiveInputBudget(
        requestedInputBudgetTokens: Int,
        model: NovelResolvedModel,
        outputReservationTokens: Int
    ) throws -> Int {
        guard requestedInputBudgetTokens > 0 else {
            throw NovelError.invalidInput("The structured model input budget must be positive.")
        }
        guard let window = model.contextWindowTokens else {
            return min(
                requestedInputBudgetTokens,
                Self.unknownWindowFallbackInputBudgetTokens
            )
        }
        // 用**预留值**而非 `parameters.maxOutputTokens` 反推输入预算。
        // 两者语义不同,过去共用一个字段是本轮故障的根源:
        //   · 发给 provider 的 maxTokens = 硬上限,撞上即截断 → 已改为 nil(不截断模型);
        //   · 这里需要的是「给输出留多少空间」,是纯本地算术,绝不发给 provider。
        let output = outputReservationTokens
        let available = max(0, window - output - 1_024)
        guard available > 0 else {
            throw NovelError.injectionBudgetExceeded(
                required: 1,
                limit: available,
                items: [NovelInjectionBudgetItem(
                    label: "Minimum structured input context",
                    estimatedTokens: 1
                )]
            )
        }
        return min(requestedInputBudgetTokens, available)
    }

    private func consume(
        _ stream: AsyncStream<NovelModelEvent>,
        onTextOutput: (@Sendable () -> Void)? = nil
    ) async throws -> String {
        var text = ""
        var terminal: NovelModelEvent?

        for await event in stream {
            try Task.checkCancellation()
            if terminal != nil {
                throw NovelStructuredModelExecutionFailure(
                    code: "duplicate_model_terminal",
                    message: "The model emitted data after its structured response ended.",
                    isRetryable: true
                )
            }
            switch event {
            case .activity:
                onTextOutput?()
            case .textDelta(let delta):
                if !delta.isEmpty { onTextOutput?() }
                text += delta
            case .textReplacement(let replacement):
                if !replacement.isEmpty { onTextOutput?() }
                text = replacement
            case .usage:
                break
            case .responseFrame(let frame):
                for frameEvent in frame.events {
                    switch frameEvent {
                    case .activity:
                        onTextOutput?()
                    case .textDelta(let delta):
                        if !delta.isEmpty { onTextOutput?() }
                        text += delta
                    case .textReplacement(let replacement):
                        if !replacement.isEmpty { onTextOutput?() }
                        text = replacement
                    case .usage:
                        break
                    }
                }
            case .responseDisconnected(let failure):
                throw NovelStructuredModelExecutionFailure(
                    code: failure.code,
                    message: failure.message,
                    isRetryable: true
                )
            case .askUser:
                throw NovelStructuredModelExecutionFailure(
                    code: "unexpected_ask_user",
                    message: "Ask User is only available in discussion mode.",
                    isRetryable: true
                )
            case .completed:
                terminal = event
            case .failed(let failure):
                terminal = event
                throw NovelStructuredModelExecutionFailure(
                    code: failure.code,
                    message: failure.message,
                    isRetryable: failure.isRetryable
                )
            }
        }

        guard terminal != nil else {
            throw NovelStructuredModelExecutionFailure(
                code: "incomplete_model_stream",
                message: "模型流式响应在完成结构化输出前中断。",
                isRetryable: true
            )
        }
        return text
    }
}

extension NovelStructuredModelTask {
    static func polishDriftMessages(
        sourceChapter: String,
        candidate: String
    ) -> [NovelModelMessage] {
        let prompt = NovelPromptCatalog.template(for: .polishDriftV1)
        return [
            .init(role: .system, content: prompt.systemText),
            .init(
                role: .user,
                content: "SOURCE CHAPTER\n" + sourceChapter +
                    "\n\nPOLISHED CANDIDATE\n" + candidate
            ),
        ]
    }
}

private extension NovelStructuredModelTask {
    var kind: NovelStructuredModelTaskKind {
        switch self {
        case .stateDelta: .stateDelta
        case .stateRebuild: .stateRebuild
        case .discussionArchive: .discussionArchive
        case .polishDrift: .polishDrift
        case .continuityAudit: .continuityAudit
        case .chapterPlanAcceptance: .chapterPlanAcceptance
        case .chapterPlanProposal: .chapterPlanProposal
        }
    }

    var promptKind: NovelPromptKind {
        switch self {
        case .stateDelta: .stateDeltaV1
        case .stateRebuild: .manualSyncV1
        case .discussionArchive: .discussionArchiveV1
        case .polishDrift: .polishDriftV1
        case .continuityAudit: .continuityAuditV1
        case .chapterPlanAcceptance: .chapterPlanAcceptanceV1
        case .chapterPlanProposal: .chapterPlanProposalV1
        }
    }

    var purpose: NovelModelPurpose {
        switch self {
        case .stateDelta: .stateExtraction
        case .stateRebuild: .stateRebuild
        case .discussionArchive: .stateExtraction
        case .polishDrift: .driftCheck
        case .continuityAudit: .continuityAudit
        // Reuse state-extraction purpose tagging; runtime model policy is `.review`.
        case .chapterPlanAcceptance: .stateExtraction
        // Proposal uses creation model policy; purpose tag stays light-weight.
        case .chapterPlanProposal: .stateExtraction
        }
    }

    var messages: [NovelModelMessage] {
        let prompt = NovelPromptCatalog.template(for: promptKind)
        switch self {
        case .stateDelta(let context, let manuscript):
            return [
                .init(
                    role: .system,
                    content: prompt.systemText + "\n\nAUTHORITATIVE CONTEXT\n" + context
                ),
                .init(role: .user, content: "NEWLY COLLECTED MANUSCRIPT\n" + manuscript)
            ]
        case .stateRebuild(let baseContext, let manuscript):
            return [
                .init(
                    role: .system,
                    content: prompt.systemText + "\n\nBASE CHECKPOINT CONTEXT\n" + baseContext
                ),
                .init(role: .user, content: "ORDERED MANUSCRIPT INPUT\n" + manuscript)
            ]
        case .discussionArchive(let discussion):
            return [
                .init(role: .system, content: prompt.systemText),
                .init(role: .user, content: "DISCUSSION SINCE LAST ARCHIVE\n" + discussion)
            ]
        case .polishDrift(let sourceChapter, let candidate):
            return Self.polishDriftMessages(
                sourceChapter: sourceChapter,
                candidate: candidate
            )
        case .continuityAudit(let priorFindings, let manuscript):
            let system = priorFindings.isEmpty
                ? prompt.systemText
                : prompt.systemText + "\n\nISSUES ALREADY REPORTED FOR EARLIER CHAPTERS\n"
                    + priorFindings
                    + "\n\nDo not repeat an issue that already appears above."
            return [
                .init(role: .system, content: system),
                .init(role: .user, content: "MANUSCRIPT UNDER AUDIT\n" + manuscript)
            ]
        case .chapterPlanAcceptance(let plan, let candidate, let recentHighlights):
            let beats = recentHighlights.trimmingCharacters(in: .whitespacesAndNewlines)
            return [
                .init(role: .system, content: prompt.systemText),
                .init(
                    role: .user,
                    content: "RECENT WRITTEN BEATS\n" +
                        (beats.isEmpty ? "(none)" : beats) +
                        "\n\nCONFIRMED CHAPTER PLAN\n" + plan +
                        "\n\nWHOLE-CHAPTER CANDIDATE\n" + candidate
                ),
            ]
        case .chapterPlanProposal(let context):
            return [
                .init(role: .system, content: prompt.systemText),
                .init(role: .user, content: context),
            ]
        }
    }

    var parameters: NovelModelParameters {
        parameters(
            stateSyncReasoningEnabled: NovelCreationModelPreferences.shared.stateSyncReasoningEnabled
        )
    }

    func parameters(stateSyncReasoningEnabled: Bool) -> NovelModelParameters {
        kind.parameters(stateSyncReasoningEnabled: stateSyncReasoningEnabled)
    }

    func decode(_ text: String) throws -> NovelStructuredModelOutput {
        switch self {
        case .stateDelta:
            .stateDelta(try NovelStructuredOutputDecoder.decodeStateDelta(from: text))
        case .stateRebuild:
            .stateRebuild(try NovelStructuredOutputDecoder.decodeStateRebuild(from: text))
        case .discussionArchive:
            .discussionArchive(try NovelStructuredOutputDecoder.decodeDiscussionArchive(from: text))
        case .polishDrift:
            .polishDrift(try NovelStructuredOutputDecoder.decodePolishDrift(from: text))
        case .continuityAudit:
            .continuityAudit(try NovelStructuredOutputDecoder.decodeContinuityAudit(from: text))
        case .chapterPlanAcceptance:
            .chapterPlanAcceptance(
                try NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(from: text)
            )
        case .chapterPlanProposal:
            .chapterPlanProposal(
                try NovelStructuredOutputDecoder.decodeChapterPlanProposal(from: text)
            )
        }
    }
}

extension NovelStructuredModelTaskKind {
    /// 「给输出留多少上下文空间」——**纯本地算术,绝不发给 provider**。
    ///
    /// 2026-07-26 真机故障:该量此前与 `maxOutputTokens` 共用一个字段,于是"留位"同时
    /// 变成了发给模型的硬上限,推理 token 吃掉预算后结构化 JSON 还没写完就撞线,报
    /// 「模型回复达到输出上限」。现拆成两个独立概念:上限取消(见 `parameters`),
    /// 留位保留在此,数值沿用原先按任务规模估的量级。
    var outputReservationTokens: Int {
        switch self {
        case .stateRebuild: 8_192
        case .stateDelta, .discussionArchive, .polishDrift, .continuityAudit,
             .chapterPlanAcceptance, .chapterPlanProposal:
            4_096
        }
    }

    /// 默认尊重小说设置「剧情同步使用推理」（缺省关闭）。
    var parameters: NovelModelParameters {
        parameters(
            stateSyncReasoningEnabled: NovelCreationModelPreferences.shared.stateSyncReasoningEnabled
        )
    }

    /// - Parameter stateSyncReasoningEnabled: 仅影响 `.stateDelta` / `.stateRebuild`。
    ///   关闭时对 DeepSeek 等会发 `thinking: disabled`，同步更快。
    func parameters(stateSyncReasoningEnabled: Bool) -> NovelModelParameters {
        switch self {
        case .stateDelta, .stateRebuild:
            .init(
                temperature: 0.1,
                topP: 0.8,
                // 不给模型设硬上限(用户明确裁决):写多少由模型决定,不人为截断。
                // 输入侧的留位由 `outputReservationTokens` 独立承担。
                maxOutputTokens: nil,
                reasoningLevel: stateSyncReasoningEnabled ? .automatic : .off
            )
        case .discussionArchive, .chapterPlanProposal:
            .init(
                temperature: 0.1,
                topP: 0.8,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        case .polishDrift, .continuityAudit, .chapterPlanAcceptance:
            .init(
                temperature: 0,
                topP: 1,
                maxOutputTokens: nil,
                reasoningLevel: .automatic
            )
        }
    }
}
