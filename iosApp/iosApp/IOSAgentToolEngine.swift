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
/// live HTTP provider. `OpenAIKmpProviderAdapter` wraps the real KMP provider;
/// tests inject a scripted provider.
public protocol IOSAgentTextProvider: Sendable {
    /// Non-streaming generation. Returns the full message chunk (which may
    /// contain assistant text and/or tool calls in `choices[].message.parts`).
    func generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk
}

/// Wraps the real KMP `OpenAIKmpProvider.generateText` (the same call the
/// SubAgent/Council iOS factories already make) behind `IOSAgentTextProvider`.
public struct OpenAIKmpProviderAdapter: IOSAgentTextProvider {
    private let provider: OpenAIKmpProvider

    public init(provider: OpenAIKmpProvider = OpenAIKmpProvider()) {
        self.provider = provider
    }

    public func generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        try await provider.generateText(providerSetting: providerSetting, messages: messages, params: params)
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

    /// Runs the loop to completion (or until approval is required / the step
    /// limit is hit). Pure function over `messages`: returns the new list,
    /// never mutates the caller's array.
    public func run(
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async -> IOSAgentToolEngineResult {
        var working = messages
        var steps = 0

        while steps < configuration.maxSteps {
            let chunk: MessageChunk
            do {
                chunk = try await provider.generateText(
                    providerSetting: providerSetting,
                    messages: working,
                    params: params
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
            let pendingTools = pendingToolCalls(in: assistantMessage)
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
            if let approval = firstApproval {
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
                    metadata: toolPart.metadata
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
