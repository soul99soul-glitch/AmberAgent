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
    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping @Sendable (MessageChunk) -> Void,
        onComplete: @escaping @Sendable () -> Void,
        onError: @escaping @Sendable (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob?
}

/// Wraps the real KMP providers behind `IOSAgentTextProvider`. Routes to
/// `OpenAIKmpProvider` or `ClaudeKmpProvider` based on the provider's sealed
/// type, so sub-agents/councils can run on either protocol.
public struct OpenAIKmpProviderAdapter: IOSAgentTextProvider, IOSAgentStreamingProvider {
    private let openAIProvider: OpenAIKmpProvider
    private let claudeProvider: ClaudeKmpProvider

    public init(
        openAIProvider: OpenAIKmpProvider = OpenAIKmpProvider(),
        claudeProvider: ClaudeKmpProvider = ClaudeKmpProvider()
    ) {
        self.openAIProvider = openAIProvider
        self.claudeProvider = claudeProvider
    }

    public func generateText(
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
            domain: "AmberAgent.AgentToolEngine",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: "当前服务商类型暂不支持子代理"]
        )
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
        onError(KotlinThrowable(message: "当前服务商类型暂不支持子代理"))
        return nil
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
}

/// A tool call that needs foreground approval before the engine can continue.
public struct IOSPendingToolApproval: Sendable {
    public let toolCallId: String
    public let toolName: String
    public let arguments: String
    public let reason: String
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
    let accumulator: MessageStreamAccumulator
    private let lock = NSLock()
    private var assistantText = ""
    private var continuation: CheckedContinuation<MessageChunk, Error>?
    private var job: Kotlinx_coroutines_coreJob?
    private var cancellationRequested = false
    private var resumed = false

    init(accumulator: MessageStreamAccumulator) { self.accumulator = accumulator }

    func appendAssistantTextDelta(from chunk: MessageChunk) -> String? {
        lock.lock()
        defer { lock.unlock() }
        var changed = false
        for choice in chunk.choices {
            if let delta = choice.delta, delta.role == MessageRole.assistant {
                let deltaText = Self.text(in: delta)
                if !deltaText.isEmpty {
                    assistantText += deltaText
                    changed = true
                }
            } else if let message = choice.message, message.role == MessageRole.assistant {
                let fullText = Self.text(in: message)
                if !fullText.isEmpty {
                    assistantText = fullText
                    changed = true
                }
            }
        }
        return changed && !assistantText.isEmpty ? assistantText : nil
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
    private let executors: [String: any IOSToolExecutor]
    private let configuration: Configuration

    public init(
        provider: any IOSAgentTextProvider,
        executors: [String: any IOSToolExecutor],
        configuration: Configuration = .init()
    ) {
        self.provider = provider
        self.executors = executors
        self.configuration = configuration
    }

    /// Streams one model turn, accumulating chunks via the shared (500-fixed)
    /// MessageStreamAccumulator and surfacing the accumulating assistant text to
    /// [onAssistantText] token-by-token. Returns a MessageChunk wrapping the final
    /// assistant message so the rest of the loop stays unchanged.
    private func streamStep(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onAssistantText: (@Sendable (String) -> Void)?
    ) async throws -> MessageChunk {
        // Non-streaming providers (e.g. test doubles) do a single blocking
        // generate — no live tokens, identical loop behavior.
        guard let streaming = provider as? IOSAgentStreamingProvider else {
            return try await provider.generateText(providerSetting: providerSetting, messages: messages, params: params)
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
                    providerSetting: providerSetting,
                    messages: messages,
                    params: params,
                    onChunk: { chunk in
                        state.accumulator.append(chunk: chunk)
                        if let text = state.appendAssistantTextDelta(from: chunk) {
                            onAssistantText?(text)
                        }
                    },
                    onComplete: {
                        let last = state.accumulator.snapshot().last
                        let finalMessage: UIMessage = (last?.role == MessageRole.assistant) ? last! : Self.emptyAssistant()
                        state.resume(returning: MessageChunk(
                            id: "",
                            model: "",
                            choices: [UIMessageChoice(index: 0, delta: nil, message: finalMessage, finishReason: "stop")],
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
    public func run(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onAssistantTurnStarted: (@Sendable () -> Void)? = nil,
        onAssistantText: (@Sendable (String) -> Void)? = nil
    ) async -> IOSAgentToolEngineResult {
        var working = messages
        var steps = 0

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
        working = await executePreExistingPendingTools(in: working)

        while steps < configuration.maxSteps {
            let chunk: MessageChunk
            onAssistantTurnStarted?()
            do {
                chunk = try await streamStep(
                    providerSetting: providerSetting,
                    messages: working,
                    params: params,
                    onAssistantText: onAssistantText
                )
            } catch is CancellationError {
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps,
                    pendingApproval: nil,
                    hitStepLimit: false
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
                return IOSAgentToolEngineResult(
                    messages: working + [failureMessage],
                    stepsExecuted: steps,
                    pendingApproval: nil,
                    hitStepLimit: false
                )
            }

            // Append the assistant turn produced by this step.
            let assistantMessage = assistantMessage(from: chunk)
            if let assistantMessage {
                working.append(assistantMessage)
            }

            // Gather all pending tool calls in the just-produced turn.
            var pendingTools = pendingToolCalls(in: assistantMessage)
            // Drop any call whose result is ALREADY filled earlier in the
            // transcript (same toolCallKey). A background pre-execution (see
            // `executePreExistingPendingTools`) or a provider that echoes a
            // completed call id would otherwise re-run the executor, double-
            // charging side effects like image generation. The empty-output
            // echo is collapsed into the already-completed one instead.
            if !pendingTools.isEmpty {
                let alreadyCompleted = completedToolKeys(in: working)
                if !alreadyCompleted.isEmpty {
                    pendingTools = pendingTools.filter { !alreadyCompleted.contains(Self.toolCallKey($0)) }
                }
            }
            if pendingTools.isEmpty {
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
            let batchResult = await executeBatch(pendingTools, isUserInitiated: false)
            if let approval = batchResult.pendingApproval, configuration.honorApprovalPause {
                return IOSAgentToolEngineResult(
                    messages: working,
                    stepsExecuted: steps + 1,
                    pendingApproval: approval,
                    hitStepLimit: false
                )
            }
            working = applyToolOutputs(batchResult.outputs, to: working)

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

    /// Executes only the pending tool calls already present in `messages`.
    /// Used by direct image-edit background handoff where no follow-up model
    /// turn should be requested after the tool result is filled.
    public func executePreExistingToolsOnly(messages: [UIMessage]) async -> [UIMessage] {
        await executePreExistingPendingTools(in: messages)
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
    private func executePreExistingPendingTools(in messages: [UIMessage]) async -> [UIMessage] {
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
        guard !executable.isEmpty else { return messages }
        let batchResult = await executeBatch(executable, isUserInitiated: false)
        // honorApprovalPause is irrelevant here: pre-existing tools handed off
        // from the foreground are not user-initiated prompts, and a background
        // run cannot surface an approval card. A .needsApproval outcome is
        // simply left unfilled (the model will re-issue it after streaming,
        // where the normal loop honors approvalPause per configuration).
        return applyToolOutputs(batchResult.outputs, to: messages)
    }

    private struct BatchExecutionResult {
        /// One output per pending tool, keyed by toolCallKey (same definition
        /// as ChatViewModel.toolCallKey) so they can be applied in place.
        let outputs: [(tool: UIMessagePart.Tool, parts: [UIMessagePart])]
        let pendingApproval: IOSPendingToolApproval?
    }

    private func executeBatch(
        _ tools: [UIMessagePart.Tool],
        isUserInitiated: Bool
    ) async -> BatchExecutionResult {
        var outputs: [(UIMessagePart.Tool, [UIMessagePart])] = []
        var firstApproval: IOSPendingToolApproval?

        for tool in tools {
            if firstApproval != nil {
                // Once an approval is pending, leave subsequent tools unfilled
                // (they will be re-executed when the caller resumes the loop).
                continue
            }
            let executor = executors[tool.toolName]
            let result: IOSAgentToolOutcome
            if let executor {
                result = await executor.execute(
                    name: tool.toolName,
                    arguments: tool.input,
                    isUserInitiated: isUserInitiated
                )
            } else {
                result = .failed("[engine] no executor registered for tool `\(tool.toolName)`")
            }

            switch result {
            case .filled(let text):
                outputs.append((tool, [UIMessagePart.Text(text: text, metadata: nil)]))
            case .filledParts(let parts):
                outputs.append((tool, parts))
            case .needsApproval(let reason):
                firstApproval = IOSPendingToolApproval(
                    toolCallId: tool.toolCallId,
                    toolName: tool.toolName,
                    arguments: tool.input,
                    reason: reason
                )
            case .denied(let reason):
                outputs.append((tool, [UIMessagePart.Text(text: "{\"denied\":\"\(sanitized(reason))\"}", metadata: nil)]))
            case .failed(let reason):
                outputs.append((tool, [UIMessagePart.Text(text: "{\"error\":\"\(sanitized(reason))\"}", metadata: nil)]))
            }
        }

        return BatchExecutionResult(outputs: outputs, pendingApproval: firstApproval)
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
}
