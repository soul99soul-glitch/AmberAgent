import Foundation
@preconcurrency import Shared

// MARK: - Multi-turn tool execution engine
//
// A reusable Swift-side engine that runs a model ↔ tool execution loop:
//
//   call model → extract pending tool calls → execute each via the registry
//   → fill tool outputs in place → call model again, until no tool calls
//   remain or maxSteps is reached.
//
// This generalizes the ad-hoc single-tool-per-round loop that `ChatViewModel`
// hard-codes for the chat surface (maxToolResumeCount, messagesByFinishingToolCall,
// pendingXxxToolCall). SubAgent, Deep Read, and a more robust chat tool flow
// all build on it. It mirrors Android's `GenerationHandler.generateText` +
// `AgentToolDispatcher.executeBatch` semantics (multi-turn, batch-execute,
// approval pause) while reusing the existing KMP message/part mechanics that
// already work on iOS.
//
// No KMP changes: `UIMessagePart.Tool`, `UIMessage`, `MessageChunk`,
// `ToolApprovalState`, and `TextGenerationParams` are all commonMain types
// already exposed via Shared.framework.

/// Outcome of executing a single tool call inside the engine.
///
/// NOTE: deliberately named `IOSAgentToolOutcome` (not `IOSToolExecutionResult`)
/// to avoid colliding with the pre-existing `IOSToolExecutionResult` enum in
/// `DocumentAccessStore.swift`, which models the local-tool-executor surface
/// (selectedFilePreview / workspaceResult / etc.) and is a different concept.
public enum IOSAgentToolOutcome: Sendable {
    /// Tool produced a JSON/text result string (the same shape `ChatViewModel`
    /// feeds into `messagesByFinishingToolCall(outputText:)`).
    case filled(String)
    /// Tool produced structured parts (e.g. an image part). Engine wraps them
    /// as the tool's output list.
    case filledParts([UIMessagePart])
    /// Tool needs foreground user approval before it can run. The engine
    /// pauses and surfaces this so the caller can resume after approval.
    case needsApproval(String)
    /// Tool was denied (policy/capability gate). Output is an honest denial
    /// string rather than an error.
    case denied(String)
    /// Tool failed to execute. Output is an honest failure string.
    case failed(String)
}

/// A single tool executor. The engine routes a pending `UIMessagePart.Tool`
/// to the registered executor by `toolName`. Implementations are thin adapters
/// over the existing Swift executors (search / workspace / webmount / memory /
/// image / mcp / subagent / council) — they must NOT block the caller on UI;
/// approval is communicated via `.needsApproval`.
public protocol IOSToolExecutor: AnyObject {
    func execute(
        name: String,
        arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome
}

/// Abstraction over the model call so the engine is unit-testable without a
/// live HTTP provider. `OpenAIKmpProviderAdapter` wraps the real KMP provider
/// and dispatches to the OpenAI or Claude executor based on the provider's
/// sealed type; tests inject a scripted provider.
public protocol IOSAgentTextProvider: Sendable {
    /// Non-streaming generation. Returns the full message chunk (which may
    /// contain assistant text and/or tool calls in `choices[].message.parts`).
    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk

}

/// Optional streaming capability — only the real provider adapter conforms. The
/// engine uses it for live subagent token streaming; non-streaming conformers
/// (e.g. test doubles) fall back to a blocking `generateText` with no live
/// tokens, and the engine loop behaves identically.
public protocol IOSAgentStreamingProvider: Sendable {
    func prepareRequest(
        providerSetting: ProviderSetting,
        params: TextGenerationParams
    ) async throws -> (ProviderSetting, TextGenerationParams)

    func supportsStreaming(providerSetting: ProviderSetting) -> Bool

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping @Sendable (MessageChunk) -> Void,
        onComplete: @escaping @Sendable () -> Void,
        onError: @escaping @Sendable (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob?
}

public extension IOSAgentStreamingProvider {
    func prepareRequest(
        providerSetting: ProviderSetting,
        params: TextGenerationParams
    ) async throws -> (ProviderSetting, TextGenerationParams) {
        (providerSetting, params)
    }

    func supportsStreaming(providerSetting: ProviderSetting) -> Bool { true }
}

/// Wraps the real KMP providers behind `IOSAgentTextProvider`. Routes to
/// `OpenAIKmpProvider` or `ClaudeKmpProvider` based on the provider's sealed
/// type, so sub-agents/councils can run on either protocol.
public struct OpenAIKmpProviderAdapter: IOSAgentTextProvider, IOSAgentStreamingProvider {
    typealias GrokGenerator = @Sendable (
        ProviderSetting.OpenAI,
        [UIMessage],
        TextGenerationParams
    ) async throws -> MessageChunk

    private let openAIProvider: OpenAIKmpProvider
    private let claudeProvider: ClaudeKmpProvider
    private let grokGenerator: GrokGenerator

    public init(
        openAIProvider: OpenAIKmpProvider = OpenAIKmpProvider(),
        claudeProvider: ClaudeKmpProvider = ClaudeKmpProvider()
    ) {
        self.openAIProvider = openAIProvider
        self.claudeProvider = claudeProvider
        self.grokGenerator = { provider, messages, params in
            try await Self.generateGrokText(
                providerSetting: provider,
                messages: messages,
                params: params
            )
        }
    }

    init(
        openAIProvider: OpenAIKmpProvider = OpenAIKmpProvider(),
        claudeProvider: ClaudeKmpProvider = ClaudeKmpProvider(),
        grokGenerator: @escaping GrokGenerator
    ) {
        self.openAIProvider = openAIProvider
        self.claudeProvider = claudeProvider
        self.grokGenerator = grokGenerator
    }

    public func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        if let openAI = providerSetting as? ProviderSetting.OpenAI,
           IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI) {
            return try await grokGenerator(openAI, messages, params)
        }
        let resolvedProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
        let resolvedParams = IOSCodexProviderResolver.augmentParamsForCodex(
            params,
            provider: providerSetting
        )
        if let openAI = resolvedProvider as? ProviderSetting.OpenAI {
            return try await openAIProvider.generateText(
                providerSetting: openAI,
                messages: messages,
                params: resolvedParams
            )
        }
        if let claude = resolvedProvider as? ProviderSetting.Claude {
            return try await claudeProvider.generateText(
                providerSetting: claude,
                messages: messages,
                params: resolvedParams
            )
        }
        throw NSError(
            domain: "AmberAgent.AgentToolEngine",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: Self.unsupportedProviderMessage(providerSetting)]
        )
    }

    public func prepareRequest(
        providerSetting: ProviderSetting,
        params: TextGenerationParams
    ) async throws -> (ProviderSetting, TextGenerationParams) {
        let resolvedProvider = try await IOSCodexProviderResolver.resolved(providerSetting)
        let resolvedParams = IOSCodexProviderResolver.augmentParamsForCodex(
            params,
            provider: providerSetting
        )
        return (resolvedProvider, resolvedParams)
    }

    public func supportsStreaming(providerSetting: ProviderSetting) -> Bool {
        !IOSGrokWebProviderResolver.isGrokWebProvider(providerSetting)
    }

    public func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping @Sendable (MessageChunk) -> Void,
        onComplete: @escaping @Sendable () -> Void,
        onError: @escaping @Sendable (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob? {
        if let openAI = providerSetting as? ProviderSetting.OpenAI {
            return openAIProvider.streamTextCancellable(
                providerSetting: openAI, messages: messages, params: params,
                onChunk: onChunk, onComplete: onComplete, onError: onError
            )
        }
        if let claude = providerSetting as? ProviderSetting.Claude {
            return claudeProvider.streamTextCancellable(
                providerSetting: claude, messages: messages, params: params,
                onChunk: onChunk, onComplete: onComplete, onError: onError
            )
        }
        onError(KotlinThrowable(message: Self.unsupportedProviderMessage(providerSetting)))
        return nil
    }

    private static func unsupportedProviderMessage(_ provider: ProviderSetting) -> String {
        if provider is ProviderSetting.Google {
            return "Gemini 还不能用于子代理和 Council，请换用 OpenAI 或 Claude。"
        }
        return "当前服务商类型暂不支持子代理"
    }

    private static func generateGrokText(
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        let prompt = messages.compactMap { message -> String? in
            let text = message.parts.compactMap { part in
                (part as? UIMessagePart.Text)?.text
            }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return "[\(String(describing: message.role).lowercased())]\n\(text)"
        }
        .joined(separator: "\n\n")
        let text = try await IOSGrokWebClient(
            providerId: IOSGrokWebProviderResolver.providerKey(providerSetting)
        ).generateText(prompt: prompt, modelId: params.model.modelId)
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: params.model.id,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: "",
            model: params.model.modelId,
            choices: [
                UIMessageChoice(
                    index: 0,
                    delta: nil,
                    message: message,
                    finishReason: "stop"
                ),
            ],
            usage: nil
        )
    }
}

/// The final state of an engine run.
public struct IOSAgentToolEngineResult: Sendable {
    /// The message list after the loop completed (tools executed, outputs
    /// filled in place). Includes the final assistant turn.
    public let messages: [UIMessage]
    /// Number of model round-trips performed.
    public let stepsExecuted: Int
    /// A tool that requested foreground approval and paused the loop, if any.
    /// The caller resolves approval and re-invokes the engine to continue.
    public let pendingApproval: IOSPendingToolApproval?
    /// Whether the loop ended because `maxSteps` was reached while tool calls
    /// were still pending (the caller may treat this as a soft failure).
    public let hitStepLimit: Bool
    /// Provider failure captured by the engine. Callers that own a durable run
    /// can map this to their normal failure terminal instead of parsing the
    /// compatibility transcript message appended below.
    public let providerFailureMessage: String?
    /// Whether the provider terminated because its output budget was exhausted.
    /// This remains distinct from transport/provider failures even though both
    /// keep `providerFailureMessage` for the existing user-facing message.
    public let hitOutputLimit: Bool
    /// Whether the caller cancelled the run while the provider was in flight.
    public let wasCancelled: Bool
    /// I-5: the loop ended because `IOSToolLoopGuard` detected the model
    /// repeating an identical tool call a 3rd time and stopped the run.
    /// Distinct from `hitStepLimit` — this is "the model was stuck", not "the
    /// budget ran out" — so callers must not fold it into a normal-completion
    /// terminal (see docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md §W5, I-5).
    public let guardStopped: Bool

    public init(
        messages: [UIMessage],
        stepsExecuted: Int,
        pendingApproval: IOSPendingToolApproval?,
        hitStepLimit: Bool,
        providerFailureMessage: String? = nil,
        hitOutputLimit: Bool = false,
        wasCancelled: Bool = false,
        guardStopped: Bool = false
    ) {
        self.messages = messages
        self.stepsExecuted = stepsExecuted
        self.pendingApproval = pendingApproval
        self.hitStepLimit = hitStepLimit
        self.providerFailureMessage = providerFailureMessage
        self.hitOutputLimit = hitOutputLimit
        self.wasCancelled = wasCancelled
        self.guardStopped = guardStopped
    }
}

/// A tool call that needs foreground approval before the engine can continue.
public struct IOSPendingToolApproval: Sendable {
    public let toolCallId: String
    public let toolName: String
    public let arguments: String
    public let reason: String
}

/// P1-d: mailbox drain 的返回盒。KMP `UIMessage` 非 Sendable，@MainActor 闭包
/// 不能把 `[UIMessage]` 返回到 nonisolated 引擎上下文——照
/// `IOSChatBackgroundRetryMessages` 先例用 @unchecked Sendable 盒跨边界
/// （drain 闭包内不可变，引擎拿到后只读展开）。
public struct IOSMailboxDrainResult: @unchecked Sendable {
    /// drain 后应作为下一轮 working 的消息列表（含渲染信封追加）。
    public let values: [UIMessage]

    public init(values: [UIMessage]) {
        self.values = values
    }
}

/// A reusable, cancellable multi-turn model↔tool loop.
///
/// Thread-safety: one engine instance should drive one run at a time. The
/// engine itself is a value type over its configuration; callers keep a
/// reference to cancel an in-flight `Task`.
/// Holds a streaming turn's accumulator + one-shot resume flag. `@unchecked
/// Sendable` because the KMP streaming bridge drives the callbacks serially on a
/// single coroutine, so there is no concurrent access despite Swift 6's
/// (conservative) inability to prove it.
private final class StreamStepState: @unchecked Sendable {
    struct AssistantUpdate {
        let text: String?
        let stage: AgentActivityStage?
    }

    let accumulator: MessageStreamAccumulator
    private let lock = NSLock()
    private var assistantText = ""
    private var assistantStage: AgentActivityStage?
    private var finishReason: String?
    private var continuation: CheckedContinuation<MessageChunk, Error>?
    private var job: Kotlinx_coroutines_coreJob?
    private var cancellationRequested = false
    private var resumed = false

    init(accumulator: MessageStreamAccumulator) { self.accumulator = accumulator }

    func appendAssistantDelta(from chunk: MessageChunk) -> AssistantUpdate {
        lock.lock()
        defer { lock.unlock() }
        var textChanged = false
        var hasReasoningDelta = false
        var hasTextDelta = false
        for choice in chunk.choices {
            if let reason = choice.finishReason {
                finishReason = reason
            }
            if let delta = choice.delta, delta.role == MessageRole.assistant {
                hasReasoningDelta = hasReasoningDelta || Self.hasReasoning(in: delta)
                hasTextDelta = hasTextDelta || Self.hasText(in: delta)
                let deltaText = Self.text(in: delta)
                if !deltaText.isEmpty {
                    assistantText += deltaText
                    textChanged = true
                }
            } else if let message = choice.message, message.role == MessageRole.assistant {
                hasReasoningDelta = hasReasoningDelta || Self.hasReasoning(in: message)
                hasTextDelta = hasTextDelta || Self.hasText(in: message)
                let fullText = Self.text(in: message)
                if !fullText.isEmpty {
                    assistantText = fullText
                    textChanged = true
                }
            }
        }
        var publishedStage: AgentActivityStage?
        if let candidate = AgentActivityResponseStagePolicy.updatedStage(
            hasReasoningDelta: hasReasoningDelta,
            hasTextDelta: hasTextDelta
        ), let nextStage = AgentActivityResponseStagePolicy.nextPublishedStage(
            current: assistantStage,
            candidate: candidate
        ) {
            assistantStage = nextStage
            publishedStage = nextStage
        }
        return AssistantUpdate(
            text: textChanged && !assistantText.isEmpty ? assistantText : nil,
            stage: publishedStage
        )
    }

    func terminalFinishReason() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return finishReason
    }

    func install(continuation: CheckedContinuation<MessageChunk, Error>) {
        lock.lock()
        if cancellationRequested {
            resumed = true
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func install(job: Kotlinx_coroutines_coreJob?) {
        guard let job else { return }
        lock.lock()
        let shouldCancel = cancellationRequested
        if !shouldCancel, !resumed {
            self.job = job
        }
        lock.unlock()
        if shouldCancel {
            job.cancel(cause: nil)
        }
    }

    func resume(returning chunk: MessageChunk) {
        let continuation: CheckedContinuation<MessageChunk, Error>?
        lock.lock()
        if !resumed {
            resumed = true
            continuation = self.continuation
            self.continuation = nil
            job = nil
        } else {
            continuation = nil
        }
        lock.unlock()
        continuation?.resume(returning: chunk)
    }

    func resume(throwing error: Error) {
        let continuation: CheckedContinuation<MessageChunk, Error>?
        lock.lock()
        if !resumed {
            resumed = true
            continuation = self.continuation
            self.continuation = nil
            job = nil
        } else {
            continuation = nil
        }
        lock.unlock()
        continuation?.resume(throwing: error)
    }

    func cancel() {
        let continuation: CheckedContinuation<MessageChunk, Error>?
        let job: Kotlinx_coroutines_coreJob?
        lock.lock()
        cancellationRequested = true
        job = self.job
        self.job = nil
        if !resumed, self.continuation != nil {
            resumed = true
            continuation = self.continuation
            self.continuation = nil
        } else {
            continuation = nil
        }
        lock.unlock()
        job?.cancel(cause: nil)
        continuation?.resume(throwing: CancellationError())
    }

    private static func text(in message: UIMessage) -> String {
        message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
    }

    private static func hasText(in message: UIMessage) -> Bool {
        message.parts.contains {
            guard let text = $0 as? UIMessagePart.Text else { return false }
            return !text.text.isEmpty
        }
    }

    private static func hasReasoning(in message: UIMessage) -> Bool {
        message.parts.contains {
            guard let reasoning = $0 as? UIMessagePart.Reasoning else { return false }
            return !reasoning.reasoning.isEmpty
        }
    }
}

public final class IOSAgentToolEngine: @unchecked Sendable {

    public struct Configuration: Sendable {
        public let maxSteps: Int
        /// When true, tools returning `.needsApproval` pause the loop and
        /// surface `pendingApproval`. When false, an approval request is
        /// treated as a denial (engine does not block).
        public let honorApprovalPause: Bool

        public init(maxSteps: Int = 8, honorApprovalPause: Bool = true) {
            self.maxSteps = maxSteps
            self.honorApprovalPause = honorApprovalPause
        }
    }

    private let provider: any IOSAgentTextProvider
    /// Executor table. Frozen by default (SubAgent/Novel paths). M2: when the
    /// run carries `toolExposureBridge` + `executorRebuilder` (chat background
    /// only), the table is rebuilt from the CURRENT round's effective params
    /// after every executed batch — a tool_search hit from this round gets an
    /// executor for the next round, so exposed tools are never "declared but
    /// not executable".
    private var executors: [String: any IOSToolExecutor]
    /// M2: rebuilds `executors` from a params snapshot (same registration entry
    /// point the caller used at init — see the background coordinator). Nil
    /// keeps the frozen-table behavior for every existing caller.
    private let executorRebuilder: ((TextGenerationParams) -> [String: any IOSToolExecutor])?
    private let configuration: Configuration
    // W1 durable ledger (I-1), optional and off by default so every existing
    // caller/test keeps building an engine with zero ledger traffic. Only
    // `IOSChatBackgroundGenerationCoordinator` passes one, reusing the SAME
    // runId the foreground run already started under — a background-continued
    // tool execution should account itself against that run, not a new one.
    // `SubAgentRunner` and the (currently unused) novel-discussion executor set
    // deliberately do NOT: their inner loop has no surfaced "run" a user can
    // recover — a crash there is invisible to the user, so the durable writes
    // would buy nothing. See docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md §W1.
    private let ledger: IOSAgentRunLedgering?
    private let ledgerRunId: String?

    public init(
        provider: any IOSAgentTextProvider,
        executors: [String: any IOSToolExecutor],
        configuration: Configuration = .init(),
        ledger: IOSAgentRunLedgering? = nil,
        ledgerRunId: String? = nil,
        executorRebuilder: ((TextGenerationParams) -> [String: any IOSToolExecutor])? = nil
    ) {
        self.provider = provider
        self.executors = executors
        self.configuration = configuration
        self.ledger = ledger
        self.ledgerRunId = ledgerRunId
        self.executorRebuilder = executorRebuilder
    }

    /// Streams one model turn, accumulating chunks via the shared (500-fixed)
    /// MessageStreamAccumulator and surfacing the accumulating assistant text to
    /// [onAssistantText] token-by-token. Returns a MessageChunk wrapping the final
    /// assistant message so the rest of the loop stays unchanged.
    ///
    /// - Parameter citationTracker: P2-c. When non-nil, every incoming chunk is
    ///   passed through `IOSMemoryCitationTracker.stripped` BEFORE the
    ///   accumulator sees it (same semantics as the foreground sink: only
    ///   assistant Text deltas are stripped; tool output / harness text never
    ///   is), so the terminal snapshot carries no `<amber-mem-cite>` markers.
    ///   Default nil keeps every existing caller (SubAgent, Novel) byte-identical.
    private func streamStep(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        citationTracker: IOSMemoryCitationTracker?,
        onAssistantStage: (@Sendable (AgentActivityStage) -> Void)?,
        onAssistantText: (@Sendable (String) -> Void)?
    ) async throws -> MessageChunk {
        // Non-streaming providers (e.g. test doubles) do a single blocking
        // generate — no live tokens, identical loop behavior.
        guard let streaming = provider as? IOSAgentStreamingProvider else {
            return try await provider.generateText(providerSetting: providerSetting, messages: messages, params: params)
        }
        let (requestProvider, requestParams) = try await streaming.prepareRequest(
            providerSetting: providerSetting,
            params: params
        )
        guard streaming.supportsStreaming(providerSetting: requestProvider) else {
            return try await provider.generateText(
                providerSetting: providerSetting,
                messages: messages,
                params: params
            )
        }
        // Seed with a single NON-assistant placeholder so the streamed turn
        // forms exactly one fresh assistant message (returned as `.last`),
        // instead of merging into `messages`' trailing assistant turn — which on
        // turn ≥2 would make `run`'s `working.append(...)` duplicate that prior
        // turn. The real `messages` are still sent to the provider for context.
        // (Also avoids the accumulator's non-empty `require` on empty input.)
        let seed = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [],
            annotations: [],
            createdAt: Self.nowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        // Box the accumulator + resume flag as @unchecked Sendable: the KMP
        // streaming bridge invokes onChunk/onComplete/onError serially on a single
        // coroutine (no concurrent access), but Swift 6 can't prove it. The box
        // also makes the @Sendable callbacks legal.
        let state = StreamStepState(accumulator: MessageStreamAccumulator(initialMessages: [seed], model: nil))
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<MessageChunk, Error>) in
                state.install(continuation: continuation)
                guard !Task.isCancelled else {
                    state.cancel()
                    return
                }
                let job = streaming.streamText(
                    providerSetting: requestProvider,
                    messages: messages,
                    params: requestParams,
                    onChunk: { chunk in
                        // P2-c: 后台流在进入 accumulator 前剥离 citation 隐藏
                        // 标记（与前台 sink 同语义——所有下游消费者只见到已剥离的
                        // 可见文本；nil → 原样零变化）。
                        let strippedChunk = citationTracker?.stripped(chunk) ?? chunk
                        state.accumulator.append(chunk: strippedChunk)
                        let update = state.appendAssistantDelta(from: strippedChunk)
                        if let stage = update.stage {
                            onAssistantStage?(stage)
                        }
                        if let text = update.text {
                            onAssistantText?(text)
                        }
                    },
                    onComplete: {
                        let last = state.accumulator.snapshot().last
                        let finalMessage: UIMessage = (last?.role == MessageRole.assistant) ? last! : Self.emptyAssistant()
                        state.resume(returning: MessageChunk(
                            id: "",
                            model: "",
                            choices: [UIMessageChoice(
                                index: 0,
                                delta: nil,
                                message: finalMessage,
                                finishReason: state.terminalFinishReason() ?? "stop"
                            )],
                            usage: nil
                        ))
                    },
                    onError: { error in
                        state.resume(throwing: NSError(
                            domain: "AmberAgent.SubAgentStream",
                            code: 1,
                            userInfo: [NSLocalizedDescriptionKey: error.message ?? "stream failed"]
                        ))
                    }
                )
                state.install(job: job)
            }
        } onCancel: {
            state.cancel()
        }
    }

    private static func emptyAssistant() -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [],
            annotations: [],
            createdAt: nowLocalDateTime(),
            finishedAt: nowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    /// Runs the loop to completion (or until approval is required / the step
    /// limit is hit). Pure function over `messages`: returns the new list,
    /// never mutates the caller's array.
    ///
    /// - Parameter toolExposureBridge: P0-a Fix C. When non-nil, each executed
    ///   batch is followed by `params.replacingTools(bridge.visibleTools())`
    ///   before the NEXT model step — a `tool_search` hit expanded inside one
    ///   round becomes declared on the next. Default nil keeps every existing
    ///   caller (SubAgent, Novel) on the frozen-params behavior.
    /// - Parameter mailboxDrain: P1-d. When non-nil, after each executed batch
    ///   (same point as the toolExposureBridge refresh, before the next
    ///   `streamStep`) the engine calls this closure and appends the returned
    ///   messages (rendered mailbox envelopes) to the working list for the next
    ///   round's upload; persisting the drained envelopes into the conversation
    ///   is the closure's job (background runs have no UI, so fold = persist +
    ///   working copy only). No input parameter: KMP `UIMessage` is
    ///   non-Sendable, so the closure only ever RETURNS messages (wrapped in an
    ///   @unchecked Sendable box, same precedent as
    ///   `IOSChatBackgroundRetryMessages`) and never receives them. Default nil
    ///   keeps every existing caller (SubAgent, Novel) behavior unchanged.
    /// - Parameter citationTracker: P2-c. When non-nil, every streamed chunk in
    ///   every step is stripped of `<amber-mem-cite>` markers before
    ///   accumulation (see `streamStep`), and the run-terminal `finish()` flush
    ///   merges any unclosed-tag remainder into the final messages — one
    ///   per-run tracker, so the caller reads `citationIds` after `run`
    ///   returns. Default nil keeps every existing caller (SubAgent, Novel)
    ///   behavior byte-identical.
    public func run(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        citationTracker: IOSMemoryCitationTracker? = nil,
        toolExposureBridge: IosToolExposureBridge? = nil,
        mailboxDrain: (@Sendable () async -> IOSMailboxDrainResult)? = nil,
        onAssistantTurnStarted: (@MainActor @Sendable () async -> Void)? = nil,
        onToolExecutionStarted: (@MainActor @Sendable (String) async -> Void)? = nil,
        onAssistantStage: (@Sendable (AgentActivityStage) -> Void)? = nil,
        onAssistantText: (@Sendable (String) -> Void)? = nil,
        onMessagesUpdated: (@Sendable ([UIMessage]) -> Void)? = nil
    ) async -> IOSAgentToolEngineResult {
        let result = await runInternal(
            providerSetting: providerSetting,
            messages: messages,
            params: params,
            citationTracker: citationTracker,
            toolExposureBridge: toolExposureBridge,
            mailboxDrain: mailboxDrain,
            onAssistantTurnStarted: onAssistantTurnStarted,
            onToolExecutionStarted: onToolExecutionStarted,
            onAssistantStage: onAssistantStage,
            onAssistantText: onAssistantText,
            onMessagesUpdated: onMessagesUpdated
        )
        return Self.finishingCitation(result, tracker: citationTracker)
    }

    /// The loop body; every terminal return path funnels through the `run`
    /// wrapper's `finishingCitation` so the citation flush (P2-c) happens
    /// exactly once per run regardless of how the run ended.
    private func runInternal(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        citationTracker: IOSMemoryCitationTracker?,
        toolExposureBridge: IosToolExposureBridge?,
        mailboxDrain: (@Sendable () async -> IOSMailboxDrainResult)?,
        onAssistantTurnStarted: (@MainActor @Sendable () async -> Void)?,
        onToolExecutionStarted: (@MainActor @Sendable (String) async -> Void)?,
        onAssistantStage: (@Sendable (AgentActivityStage) -> Void)?,
        onAssistantText: (@Sendable (String) -> Void)?,
        onMessagesUpdated: (@Sendable ([UIMessage]) -> Void)?
    ) async -> IOSAgentToolEngineResult {
        var working = messages
        var effectiveParams = params
        var steps = 0
        // I-5: one guard per `run()` invocation, never carried across runs.
        // The engine instance itself is long-lived/reused (SubAgent, chat
        // background handoff), so this must be a local — a guard stored on
        // `self` would leak one run's repeat-count into the next.
        var loopGuard = IOSToolLoopGuard()

        // Background handoff parity: the input may carry assistant turns whose
        // tool calls were never executed — e.g. the model already decided to
        // generate_image, but the foreground executor was interrupted (lock
        // screen) before it ran. Execute those pre-existing empty-output tools
        // ONCE here, before any streaming, and fill their results in place.
        //
        // Without this, the loop below would first re-prompt the model, which —
        // seeing an unanswered tool call — typically re-issues it, causing the
        // executor to run the SAME call a second time. For costly side effects
        // (image generation burning a Codex credit, a network search) this is a
        // real waste and a correctness hazard. Pre-execution does not consume
        // the `steps` budget: it is finishing work the prior (foreground) run
        // already started, not a fresh reasoning round.
        let preExistingResult = await executePreExistingPendingTools(
            in: working,
            loopGuard: &loopGuard,
            onToolExecutionStarted: onToolExecutionStarted
        )
        working = preExistingResult.messages
        onMessagesUpdated?(working)
        if preExistingResult.wasCancelled {
            return IOSAgentToolEngineResult(
                messages: working,
                stepsExecuted: 0,
                pendingApproval: nil,
                hitStepLimit: false,
                wasCancelled: true
            )
        }
        if preExistingResult.guardStopped {
            return IOSAgentToolEngineResult(
                messages: working,
                stepsExecuted: 0,
                pendingApproval: nil,
                hitStepLimit: false,
                guardStopped: true
            )
        }

        while steps < configuration.maxSteps {
            let chunk: MessageChunk
            let cancelledBeforeProvider: Bool
            if Task.isCancelled {
                cancelledBeforeProvider = true
            } else {
                await onAssistantTurnStarted?()
                cancelledBeforeProvider = Task.isCancelled
            }
            if cancelledBeforeProvider {
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps,
                    pendingApproval: nil,
                    hitStepLimit: false,
                    wasCancelled: true
                )
            }
            do {
                chunk = try await streamStep(
                    providerSetting: providerSetting,
                    messages: working,
                    params: effectiveParams,
                    citationTracker: citationTracker,
                    onAssistantStage: onAssistantStage,
                    onAssistantText: onAssistantText
                )
            } catch is CancellationError {
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps,
                    pendingApproval: nil,
                    hitStepLimit: false,
                    wasCancelled: true
                )
            } catch {
                // A provider failure ends the loop. Surface the assistant
                // transcript so far plus an honest failure turn rather than
                // silently dropping the run.
                let failureMessage = UIMessage(
                    id: KotlinUuid.companion.random(),
                    role: MessageRole.assistant,
                    parts: [UIMessagePart.Text(text: "[engine] provider error: \(error.localizedDescription)", metadata: nil)],
                    annotations: [],
                    createdAt: Self.nowLocalDateTime(),
                    finishedAt: Self.nowLocalDateTime(),
                    modelId: nil,
                    usage: nil,
                    translation: nil
                )
                let failedMessages = working + [failureMessage]
                onMessagesUpdated?(failedMessages)
                return IOSAgentToolEngineResult(
                    messages: failedMessages,
                    stepsExecuted: steps,
                    pendingApproval: nil,
                    hitStepLimit: false,
                    providerFailureMessage: error.localizedDescription
                )
            }

            let rawAssistantMessage = assistantMessage(from: chunk)
            if Self.reachedOutputLimit(chunk) {
                let limitedMessages = rawAssistantMessage.map { working + [$0] } ?? working
                onMessagesUpdated?(limitedMessages)
                return IOSAgentToolEngineResult(
                    messages: limitedMessages,
                    stepsExecuted: steps + 1,
                    pendingApproval: nil,
                    hitStepLimit: false,
                    providerFailureMessage: "模型回复达到输出上限，请重试。",
                    hitOutputLimit: true
                )
            }
            let emittedTools = pendingToolCalls(in: rawAssistantMessage)
            let alreadyCompleted = completedToolKeys(in: working)
            let assistantMessage: UIMessage? = rawAssistantMessage.flatMap { message in
                guard message.role == MessageRole.assistant, !alreadyCompleted.isEmpty else {
                    return message
                }
                let retainedParts = message.parts.filter { part in
                    guard let tool = part as? UIMessagePart.Tool, tool.output.isEmpty else {
                        return true
                    }
                    return !alreadyCompleted.contains(Self.toolCallKey(tool))
                }
                guard !retainedParts.isEmpty else { return nil }
                guard retainedParts.count != message.parts.count else { return message }
                return UIMessage(
                    id: message.id,
                    role: message.role,
                    parts: retainedParts,
                    annotations: message.annotations,
                    createdAt: message.createdAt,
                    finishedAt: message.finishedAt,
                    modelId: message.modelId,
                    usage: message.usage,
                    translation: message.translation
                )
            }
            if let assistantMessage {
                working.append(assistantMessage)
                onMessagesUpdated?(working)
            }

            // Only fresh pending tools remain in the retained assistant turn.
            // Completed echoes are removed without discarding concurrent text
            // or a different tool call emitted by the same provider response.
            let pendingTools = pendingToolCalls(in: assistantMessage)
            if pendingTools.isEmpty {
                let hasAssistantContent = assistantMessage?.parts.contains { part in
                    if let text = part as? UIMessagePart.Text {
                        return !text.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    }
                    if let tool = part as? UIMessagePart.Tool {
                        return !tool.output.isEmpty
                    }
                    return true
                } ?? false
                if !emittedTools.isEmpty, !hasAssistantContent {
                    // The provider only echoed calls that were already completed.
                    // Drop any whitespace-only remainder and ask for the final answer.
                    if assistantMessage != nil {
                        working.removeLast()
                        onMessagesUpdated?(working)
                    }
                    steps += 1
                    continue
                }
                // No more tool calls — the model is done.
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps + 1,
                    pendingApproval: nil,
                    hitStepLimit: false
                )
            }

            // Execute every pending tool in this turn (batch), then fill all
            // of them in place before the next round — mirrors Android's
            // AgentToolDispatcher.executeBatch.
            let batchResult = await executeBatch(
                pendingTools,
                isUserInitiated: false,
                loopGuard: &loopGuard,
                onToolExecutionStarted: onToolExecutionStarted
            )
            if batchResult.wasCancelled {
                working = applyToolOutputs(batchResult.outputs, to: working)
                onMessagesUpdated?(working)
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps + 1,
                    pendingApproval: nil,
                    hitStepLimit: false,
                    wasCancelled: true
                )
            }
            if let approval = batchResult.pendingApproval, configuration.honorApprovalPause {
                working = applyToolOutputs(batchResult.outputs, to: working)
                onMessagesUpdated?(working)
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps + 1,
                    pendingApproval: approval,
                    hitStepLimit: false
                )
            }
            working = applyToolOutputs(batchResult.outputs, to: working)
            onMessagesUpdated?(working)

            // P0-a Fix C: re-derive the request params from the run bridge's
            // CURRENT exposure after every executed batch, so a tool_search
            // hit from THIS round is declared on the NEXT round (background
            // handoff closed loop). Without a bridge the params stay frozen.
            if let bridge = toolExposureBridge {
                effectiveParams = effectiveParams.replacingTools(bridge.visibleTools())
                // M2: same refresh point — rebuild the executor table for the
                // current round's visible tools (chat background only; the
                // rebuilder re-registers through backgroundToolExecutors with
                // the round's params/bridge captured inside the closures).
                // Without a rebuilder the table stays frozen (SubAgent/Novel).
                if let executorRebuilder {
                    executors = executorRebuilder(effectiveParams)
                }
            }

            // P1-d: mailbox drain 与 replacingTools 同点——每轮批量执行后、下一轮
            // streamStep 之前。drain 闭包把本会话信封渲染为 user 消息返回，
            // 追加进 working（下一轮 upload 折入），闭包内完成持久化（后台无 UI
            // 上屏）。SubAgent/Novel 不传 → 零影响；Room drain 的事务加固保证
            // 同会话前后台双 drain 最多折入一次（loser 返回空）。
            if let mailboxDrain {
                let drained = (await mailboxDrain()).values
                if !drained.isEmpty {
                    working.append(contentsOf: drained)
                    onMessagesUpdated?(working)
                }
            }

            // I-5: a repeated-signature stop ends the whole run here, mirroring
            // the maxSteps-exhausted return below — the stop output is already
            // filled in place above, so the caller sees a terminated loop with
            // an explicit reason, not a silent extra step.
            if batchResult.guardStopped {
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps + 1,
                    pendingApproval: nil,
                    hitStepLimit: false,
                    guardStopped: true
                )
            }

            steps += 1
        }

        // Reached the step limit with tool calls still pending.
        return IOSAgentToolEngineResult(
            messages: working,
            stepsExecuted: steps,
            pendingApproval: nil,
            hitStepLimit: true
        )
    }

    /// P2-c: run 终结统一收口——完成/失败/取消/限步/审批暂停的每个返回路径都
    /// 经 `run` 包装层走到这里。`finish()` 幂等；flush 出的剩余可见文本（未闭合
    /// 标签在 EOF 时按 codex 同款 auto-close 整段吞进 citation，不外泄）并入终态
    /// 消息快照。nil → 原样返回，SubAgent/Novel 零变化。
    private static func finishingCitation(
        _ result: IOSAgentToolEngineResult,
        tracker: IOSMemoryCitationTracker?
    ) -> IOSAgentToolEngineResult {
        guard let tracker else { return result }
        let remainder = tracker.finish()
        guard !remainder.isEmpty else { return result }
        return IOSAgentToolEngineResult(
            messages: IOSMemoryCitationTracker.appendingCitationRemainder(remainder, to: result.messages),
            stepsExecuted: result.stepsExecuted,
            pendingApproval: result.pendingApproval,
            hitStepLimit: result.hitStepLimit,
            providerFailureMessage: result.providerFailureMessage,
            hitOutputLimit: result.hitOutputLimit,
            wasCancelled: result.wasCancelled,
            guardStopped: result.guardStopped
        )
    }

    /// Executes only the pending tool calls already present in `messages`.
    /// Used by direct image-edit background handoff where no follow-up model
    /// turn should be requested after the tool result is filled.
    public func executePreExistingToolsOnly(messages: [UIMessage]) async -> [UIMessage] {
        // Standalone entry point (bypasses `run()`), so it needs its own
        // fresh, local guard — same "never carried across runs" rule as `run`.
        var loopGuard = IOSToolLoopGuard()
        return await executePreExistingPendingTools(
            in: messages,
            loopGuard: &loopGuard,
            onToolExecutionStarted: nil
        ).messages
    }

    // MARK: - Internals

    private func assistantMessage(from chunk: MessageChunk) -> UIMessage? {
        for choice in chunk.choices {
            if let message = choice.message ?? choice.delta {
                return message
            }
        }
        return nil
    }

    private static func reachedOutputLimit(_ chunk: MessageChunk) -> Bool {
        let reasons = Set(chunk.choices.compactMap { $0.finishReason?.lowercased() })
        return !reasons.isDisjoint(with: ["length", "max_tokens", "max_output_tokens"])
    }

    private func pendingToolCalls(in message: UIMessage?) -> [UIMessagePart.Tool] {
        guard let message, message.role == MessageRole.assistant else { return [] }
        return message.parts.compactMap { $0 as? UIMessagePart.Tool }.filter { $0.output.isEmpty }
    }

    /// toolCallKeys of every tool call in the transcript whose output is already
    /// filled. Used to suppress re-execution of a call the model echoes after it
    /// was completed earlier (background pre-execution or a buggy provider echo).
    private func completedToolKeys(in messages: [UIMessage]) -> Set<String> {
        var keys = Set<String>()
        for message in messages where message.role == MessageRole.assistant {
            for case let tool as UIMessagePart.Tool in message.parts where !tool.output.isEmpty {
                keys.insert(Self.toolCallKey(tool))
            }
        }
        return keys
    }

    /// Executes any empty-output tool calls that are ALREADY present in the
    /// input messages (across all assistant turns, not just the last), filling
    /// their results in place. See the call site in `run` for rationale.
    ///
    /// Routes through the same `executeBatch` + `applyToolOutputs` path as the
    /// main loop, so approval/denial/failure outcomes are handled identically.
    /// Returns the messages unchanged when there is nothing pre-existing to run.
    private struct PreExistingExecutionResult {
        let messages: [UIMessage]
        let guardStopped: Bool
        let wasCancelled: Bool
    }

    private func executePreExistingPendingTools(
        in messages: [UIMessage],
        loopGuard: inout IOSToolLoopGuard,
        onToolExecutionStarted: (@MainActor @Sendable (String) async -> Void)?
    ) async -> PreExistingExecutionResult {
        let preExisting = messages.flatMap { message -> [UIMessagePart.Tool] in
            guard message.role == MessageRole.assistant else { return [] }
            return message.parts.compactMap { $0 as? UIMessagePart.Tool }.filter { $0.output.isEmpty }
        }
        // Only pre-execute tools this engine actually knows how to run. Scoped
        // engines (e.g. a subagent whose executor map is restricted to a read-only
        // allow-list) must NOT pre-fill a parent transcript's tools they were never
        // meant to handle — that would inject `[engine] no executor registered`
        // failure text into the history. Such tools are left untouched; they will
        // either be re-issued by the model (and then hit the executor map) or
        // simply ignored.
        let executable = preExisting.filter { executors[$0.toolName] != nil }
        guard !executable.isEmpty else {
            return PreExistingExecutionResult(
                messages: messages,
                guardStopped: false,
                wasCancelled: false
            )
        }
        let batchResult = await executeBatch(
            executable,
            isUserInitiated: false,
            loopGuard: &loopGuard,
            onToolExecutionStarted: onToolExecutionStarted
        )
        // honorApprovalPause is irrelevant here: pre-existing tools handed off
        // from the foreground are not user-initiated prompts, and a background
        // run cannot surface an approval card. A .needsApproval outcome is
        // simply left unfilled (the model will re-issue it after streaming,
        // where the normal loop honors approvalPause per configuration).
        return PreExistingExecutionResult(
            messages: applyToolOutputs(batchResult.outputs, to: messages),
            guardStopped: batchResult.guardStopped,
            wasCancelled: batchResult.wasCancelled
        )
    }

    private struct BatchExecutionResult {
        /// One output per pending tool, keyed by toolCallKey (same definition
        /// as ChatViewModel.toolCallKey) so they can be applied in place.
        let outputs: [(tool: UIMessagePart.Tool, parts: [UIMessagePart])]
        let pendingApproval: IOSPendingToolApproval?
        /// I-5: set when `loopGuard` returned `.stop` for one of this batch's
        /// tools. The stopped tool's output already carries the stop
        /// explanation; remaining tools in the batch are left unfilled (same
        /// "leave the rest for the caller to decide" shape as `firstApproval`).
        let guardStopped: Bool
        /// The task was cancelled after durable setup but before the next
        /// executor could begin, so no further tool/model work should start.
        let wasCancelled: Bool
    }

    private func executeBatch(
        _ tools: [UIMessagePart.Tool],
        isUserInitiated: Bool,
        loopGuard: inout IOSToolLoopGuard,
        onToolExecutionStarted: (@MainActor @Sendable (String) async -> Void)?
    ) async -> BatchExecutionResult {
        var outputs: [(UIMessagePart.Tool, [UIMessagePart])] = []
        var firstApproval: IOSPendingToolApproval?
        var guardStopped = false

        for tool in tools {
            if firstApproval != nil {
                // Approval pauses this batch. The caller owns whether and when
                // the remaining calls resume after the user decides.
                continue
            }
            if guardStopped {
                // A guard stop is terminal, unlike an approval pause. Resolve
                // every remaining call explicitly so no later run can mistake
                // it for pending work and execute it out of context.
                outputs.append((tool, [UIMessagePart.Text(
                    text: toolLoopGuardSkippedJSON(toolName: tool.toolName),
                    metadata: nil
                )]))
                continue
            }
            // I-2 fail-closed: refuse to dispatch a tool call whose `input` cannot
            // be parsed as a JSON object, *before* consulting the executor map. A
            // gateway that double-writes a call, truncates one mid-argument, or a
            // model that emits bare non-JSON text would otherwise reach the
            // executor with silently wrong (not absent) arguments — see
            // `parseInputStrict()`. Refusing costs one visible retry via the
            // model's next turn; executing anyway costs an invisible wrong answer.
            if let invalid = tool.parseInputStrict() as? ToolInputParse.Invalid {
                outputs.append((tool, [UIMessagePart.Text(
                    text: toolArgumentsInvalidJSON(message: invalid.message, rawPrefix: invalid.rawPrefix),
                    metadata: nil
                )]))
                continue
            }

            // I-5 打转守护:在 I-2 parse 闸门之后、I-1 账本段之前判断——参数解析
            // 失败的调用从未真正执行,不该计入重复签名;stop 分支不执行工具,
            // 也不进 I-1 账本(不留 Started 记录),直接写停止说明并终止整批。
            let loopGuardVerdict = loopGuard.check(toolName: tool.toolName, input: tool.input)
            if case .stop(let reason) = loopGuardVerdict {
                outputs.append((tool, [UIMessagePart.Text(
                    text: toolLoopGuardStoppedJSON(toolName: tool.toolName, reason: reason),
                    metadata: nil
                )]))
                guardStopped = true
                continue
            }

            // I-1 durable boundary: when this engine instance carries a ledger
            // (only the chat background coordinator does — see the `ledger`
            // property doc), record Started before the executor runs. A write
            // failure here is treated exactly like the I-2 gate above: do not
            // reach the executor, fail this tool call in place instead of
            // silently executing with no durable trace. When there is no
            // ledger, this whole block is skipped (`ledger`/`ledgerRunId` are
            // both nil for SubAgent/Novel — zero overhead, matches today).
            if let ledger, let ledgerRunId {
                let effectClass = IOSToolEffectClassMapping.forToolName(
                    tool.toolName,
                    input: tool.input
                )
                let didRecordStart = await ledger.recordToolCallStarted(
                    runId: ledgerRunId,
                    toolCallId: tool.toolCallId,
                    toolName: tool.toolName,
                    argsDigest: chatInputDigest(for: tool.input),
                    effectClass: effectClass
                )
                guard didRecordStart else {
                    outputs.append((tool, [UIMessagePart.Text(
                        text: "{\"ok\":false,\"error\":\"tool_ledger_write_failed\",\"message\":\"could not durably record tool call start\"}",
                        metadata: nil
                    )]))
                    continue
                }
            }

            let executor = executors[tool.toolName]
            let result: IOSAgentToolOutcome
            let cancelledBeforeExecution: Bool
            if Task.isCancelled {
                cancelledBeforeExecution = true
            } else {
                if executor != nil {
                    await onToolExecutionStarted?(tool.toolName)
                }
                cancelledBeforeExecution = Task.isCancelled
            }
            if cancelledBeforeExecution {
                if let ledger, let ledgerRunId {
                    await ledger.recordToolCallFinished(
                        runId: ledgerRunId,
                        toolCallId: tool.toolCallId,
                        outcome: "cancelled_before_execution"
                    )
                }
                return BatchExecutionResult(
                    outputs: outputs,
                    pendingApproval: nil,
                    guardStopped: false,
                    wasCancelled: true
                )
            }
            if let executor {
                result = await executor.execute(
                    name: tool.toolName,
                    arguments: tool.input,
                    isUserInitiated: isUserInitiated
                )
            } else {
                result = .failed("[engine] no executor registered for tool `\(tool.toolName)`")
            }

            if let ledger, let ledgerRunId {
                let outcome: String
                switch result {
                case .filled, .filledParts:
                    outcome = "completed"
                case .needsApproval:
                    outcome = "paused_for_approval"
                case .denied:
                    outcome = "denied"
                case .failed:
                    outcome = "failed"
                }
                await ledger.recordToolCallFinished(
                    runId: ledgerRunId,
                    toolCallId: tool.toolCallId,
                    outcome: outcome
                )
            }

            var resultParts: [UIMessagePart]?
            switch result {
            case .filled(let text):
                resultParts = [UIMessagePart.Text(text: text, metadata: nil)]
            case .filledParts(let parts):
                resultParts = parts
            case .needsApproval(let reason):
                firstApproval = IOSPendingToolApproval(
                    toolCallId: tool.toolCallId,
                    toolName: tool.toolName,
                    arguments: tool.input,
                    reason: reason
                )
            case .denied(let reason):
                resultParts = [UIMessagePart.Text(text: "{\"denied\":\"\(sanitized(reason))\"}", metadata: nil)]
            case .failed(let reason):
                resultParts = [UIMessagePart.Text(text: "{\"error\":\"\(sanitized(reason))\"}", metadata: nil)]
            }
            if var parts = resultParts {
                // I-5 第 2 次相同签名:工具照常执行,把提醒追加为一个额外的
                // Text part(append,不替换),让模型下一轮看到自己在重复。
                if case .proceedAndRemind(let reminder) = loopGuardVerdict {
                    parts.append(UIMessagePart.Text(text: reminder, metadata: nil))
                }
                outputs.append((tool, parts))
            }
        }

        return BatchExecutionResult(
            outputs: outputs,
            pendingApproval: firstApproval,
            guardStopped: guardStopped,
            wasCancelled: false
        )
    }

    /// Rebuilds the message list with tool outputs filled in place. Replaces
    /// ALL matching pending tool parts (by toolCallKey), generalizing
    /// `ChatViewModel.messagesByFinishingToolCall` which only fills the first.
    private func applyToolOutputs(
        _ outputs: [(tool: UIMessagePart.Tool, parts: [UIMessagePart])],
        to messages: [UIMessage]
    ) -> [UIMessage] {
        guard !outputs.isEmpty else { return messages }
        var lookup: [String: [UIMessagePart]] = [:]
        for entry in outputs {
            lookup[Self.toolCallKey(entry.tool)] = entry.parts
        }
        return messages.map { message in
            guard message.role == MessageRole.assistant else { return message }
            var didChange = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard let toolPart = part as? UIMessagePart.Tool,
                      toolPart.output.isEmpty,
                      let newOutput = lookup[Self.toolCallKey(toolPart)] else {
                    return part
                }
                didChange = true
                return UIMessagePart.Tool(
                    toolCallId: toolPart.toolCallId,
                    toolName: toolPart.toolName,
                    input: toolPart.input,
                    output: newOutput,
                    approvalState: toolPart.approvalState,
                    streamIndex: toolPart.streamIndex,
                    metadata: nil
                )
            }
            guard didChange else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt,
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    /// Stable key matching `ChatViewModel.toolCallKey`: toolCallId when present,
    /// else "name:input".
    static func toolCallKey(_ toolCall: UIMessagePart.Tool) -> String {
        let id = toolCall.toolCallId.trimmingCharacters(in: .whitespacesAndNewlines)
        if !id.isEmpty { return id }
        return "\(toolCall.toolName):\(toolCall.input)"
    }

    /// Mirrors `ChatViewModel.nowLocalDateTime()` — the KMP `UIMessage` bridge
    /// does not expose Kotlin default args, so callers must pass an explicit
    /// `LocalDateTime` when constructing a `UIMessage`.
    private static func nowLocalDateTime() -> Kotlinx_datetimeLocalDateTime {
        let now = Date()
        let cal = Calendar.current
        return Kotlinx_datetimeLocalDateTime(
            year: Int32(cal.component(.year, from: now)),
            month: Int32(cal.component(.month, from: now)),
            day: Int32(cal.component(.day, from: now)),
            hour: Int32(cal.component(.hour, from: now)),
            minute: Int32(cal.component(.minute, from: now)),
            second: Int32(cal.component(.second, from: now)),
            nanosecond: Int32(cal.component(.nanosecond, from: now))
        )
    }

    /// Minimal JSON-string sanitization for error/denial payloads so they stay
    /// valid JSON when embedded in tool output.
    private func sanitized(_ raw: String) -> String {
        raw
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
    }

    /// Structured stop notice for a tool call `IOSToolLoopGuard` refused to run
    /// a 3rd time with identical arguments (I-5). Same inline-JSON style as the
    /// other structured errors in this file — the call site does not execute
    /// the tool, so this is the only trace of "why" that ends up in the
    /// transcript.
    private func toolLoopGuardStoppedJSON(toolName: String, reason: String) -> String {
        "{\"ok\":false,\"error\":\"tool_loop_guard_stopped\",\"tool\":\"\(sanitized(toolName))\",\"reason\":\"\(sanitized(reason))\"}"
    }

    private func toolLoopGuardSkippedJSON(toolName: String) -> String {
        "{\"ok\":false,\"error\":\"tool_not_executed\",\"tool\":\"\(sanitized(toolName))\",\"reason\":\"batch stopped by repeated tool-call guard\"}"
    }

    /// Structured error for a tool call whose `input` failed `parseInputStrict()`
    /// (I-2, fail-closed). Same inline-JSON style as the `.failed`/`.denied`
    /// cases above (`{"error": ...}`), extended with the `message` / `raw_prefix`
    /// fields the model needs to self-correct.
    private func toolArgumentsInvalidJSON(message: String, rawPrefix: String) -> String {
        "{\"ok\":false,\"error\":\"tool_arguments_invalid\",\"message\":\"\(sanitized(message))\",\"raw_prefix\":\"\(sanitized(rawPrefix))\"}"
    }
}
