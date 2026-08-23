import Foundation

// MARK: - IOSRecipeRunner
//
// Sequential declarative recipe executor (§10.3). Pure composition of App-
// shipped primitives — no loops, no recursion, no dynamic code, no
// recipe-calling-recipe (the validator rejects all of those).
//
// Execution semantics (§10.3.1-7):
// 1. At call time the runner resolves an IMMUTABLE execution plan from the
//    manifest version, the call's inputs and the current primitive catalog
//    (`resolvePlan`), then validates ToolIds, input types and bindings
//    (§10.3.1-2). The plan pins the manifest version for this call (in-flight
//    pinning, §13.3 — a later promotion cannot retarget this execution).
// 2. Steps run strictly in order. Every step is written to the ledger through
//    the injected `IOSAgentRunLedgering` (real interface): Started before the
//    primitive runs, Finished after — a failed Started write fails the step
//    closed without reaching the primitive (W1 "先记账，后动手").
// 3. Arguments are resolved per step: literal values pass through; bindings
//    resolve against call inputs or the raw JSON output of EARLIER steps
//    (the validator guarantees no self/later references).
// 4. Each step runs under its timeout (default `IOSRecipeLimits
//    .defaultStepTimeoutSeconds`, cap enforced by the validator); the timeout
//    task cancels the primitive task on expiry.
// 5. A step failure stops the run: the outcome reports the failed step, a
//    structured error and the list of completed steps (§10.3.6). There is no
//    automatic compensation of irreversible external side effects.
// 6. The recipe's overall effect class is the conservative upper bound of all
//    steps' effect classes (§10.3.7, invariant 10) — carried on the plan so a
//    mutation step can never be exempted by the recipe layer (invariant 11;
//    real approval wiring belongs to a later wave).
//
// Primitive execution is injected: `(toolId, argsJSON) async throws -> String`.
// Production wiring (through the existing dispatcher) arrives in a later wave.

// MARK: - Plan

/// One immutable, resolved step of an execution plan.
struct IOSRecipePlanStep: Equatable, Sendable {
    let id: String
    let tool: String
    /// Tool version from the catalog entry (informational; reserved for
    /// lease attribution once a versioned tool catalog exists, §9.5).
    let toolVersion: String?
    let arguments: [String: IOSRecipeValue]
    let timeoutSeconds: Int
    let effectClass: IOSToolEffectClass
}

/// Immutable execution plan (§10.3.1): resolved once per call from the
/// manifest version + inputs + current catalog.
struct IOSRecipeExecutionPlan: Equatable, Sendable {
    let recipeName: String
    let recipeVersion: String
    let steps: [IOSRecipePlanStep]
    let outputs: [String: IOSRecipeBinding]
    /// Conservative upper bound of all steps (I-10, §10.3.7).
    let permissionEnvelope: IOSToolEffectClass
}

// MARK: - Errors and outcome

enum IOSRecipeRunError: Error, Equatable, Sendable {
    case planInvalid([IOSRecipeValidationIssue])
    case inputInvalid(message: String)
    case argumentBinding(stepId: String, key: String, reason: String)
    case stepFailed(stepId: String, tool: String, message: String)
    case stepTimeout(stepId: String, tool: String, timeoutSeconds: Int)
    case outputResolution(outputName: String, reason: String)
}

enum IOSRecipeRunOutcome: Equatable {
    /// All steps completed and every output binding resolved.
    case succeeded(outputs: [String: IOSRecipeJSONValue], completedSteps: [String])
    /// `failedStep` is nil only when the run stopped before any step executed
    /// (plan/input validation) or during output resolution after the last
    /// step; `completedSteps` is the list of steps that ran to completion
    /// (the completed side effects, §10.3.6).
    case failed(failedStep: String?, error: IOSRecipeRunError, completedSteps: [String])
}

// MARK: - Runner

struct IOSRecipeRunner: Sendable {
    let manifest: IOSRecipeManifest
    let catalog: IOSRecipeCatalogLookup
    /// `(toolId, argsJSON) async throws -> output JSON text`. Throws = step
    /// failure; a returned string is the step's raw output JSON (which later
    /// steps may bind into). `@MainActor`: production wiring runs the step
    /// through `ChatToolRuntime`'s per-tool dispatch (MainActor); the
    /// isolated closure type lets that wiring capture the runtime directly
    /// (the same pattern the runtime's own executor closures use).
    let executePrimitive: @MainActor @Sendable (String, String) async throws -> String
    /// Real ledger interface (production: `IOSAgentRunLedger`); nil skips
    /// ledger writes (used by the store-level tests only).
    let ledger: (any IOSAgentRunLedgering)?
    /// The agent run this recipe execution belongs to (ledger owner).
    let runId: String

    init(
        manifest: IOSRecipeManifest,
        catalog: @escaping IOSRecipeCatalogLookup,
        executePrimitive: @escaping @MainActor @Sendable (String, String) async throws -> String,
        ledger: (any IOSAgentRunLedgering)?,
        runId: String
    ) {
        self.manifest = manifest
        self.catalog = catalog
        self.executePrimitive = executePrimitive
        self.ledger = ledger
        self.runId = runId
    }

    // MARK: Plan resolution

    /// Resolves and validates the immutable plan for one call (§10.3.1-2).
    /// Throws `IOSRecipeRunError.planInvalid` for manifest/catalog problems
    /// and `.inputInvalid` when the provided inputs violate the declared
    /// input schema (missing, extra or wrong-typed values).
    func resolvePlan(inputs: [String: IOSRecipeJSONValue]) throws -> IOSRecipeExecutionPlan {
        let validation = IOSRecipeValidator.validate(manifest: manifest, catalog: catalog)
        guard validation.isValid, let envelope = validation.permissionEnvelope else {
            throw IOSRecipeRunError.planInvalid(validation.issues)
        }
        try validateInputs(inputs)

        let steps = manifest.steps.map { step -> IOSRecipePlanStep in
            let tool = step.tool.trimmingCharacters(in: .whitespacesAndNewlines)
            // Validator guarantees the catalog entry exists and the tool has
            // no surrounding whitespace; fail-closed `.sideEffect` default for
            // a (unreachable) missing entry, matching the ledger classifier.
            let entry = catalog(tool)
            return IOSRecipePlanStep(
                id: step.id,
                tool: tool,
                toolVersion: entry?.minVersion,
                arguments: step.arguments,
                timeoutSeconds: step.timeoutSeconds ?? IOSRecipeLimits.defaultStepTimeoutSeconds,
                effectClass: entry?.effectClass ?? .sideEffect
            )
        }
        var outputs: [String: IOSRecipeBinding] = [:]
        for (name, value) in manifest.outputs {
            if case .binding(let binding) = value {
                outputs[name] = binding
            }
        }
        return IOSRecipeExecutionPlan(
            recipeName: manifest.name,
            recipeVersion: manifest.version,
            steps: steps,
            outputs: outputs,
            permissionEnvelope: envelope
        )
    }

    // MARK: Execution

    /// Runs the recipe with the given inputs and returns the outcome. Never
    /// throws: all failure modes are reported in `IOSRecipeRunOutcome.failed`.
    /// This is the linear no-approval case; the production route drives the
    /// SAME per-step machinery (`runStep`) so a mutation step can pause the
    /// whole recipe call for approval without duplicating execution logic.
    func run(inputs: [String: IOSRecipeJSONValue]) async -> IOSRecipeRunOutcome {
        let executionId = "recipe-\(UUID().uuidString)"
        let plan: IOSRecipeExecutionPlan
        do {
            plan = try resolvePlan(inputs: inputs)
        } catch let error as IOSRecipeRunError {
            return .failed(failedStep: nil, error: error, completedSteps: [])
        } catch {
            return .failed(
                failedStep: nil,
                error: .inputInvalid(message: "无法解析执行计划：\(error.localizedDescription)"),
                completedSteps: []
            )
        }

        var stepOutputs: [String: String] = [:]
        var completedSteps: [String] = []

        for step in plan.steps {
            do {
                let output = try await runStep(
                    plan: plan, step: step, executionId: executionId,
                    inputs: inputs, stepOutputs: stepOutputs
                )
                stepOutputs[step.id] = output
                completedSteps.append(step.id)
            } catch let error as IOSRecipeRunError {
                return .failed(failedStep: step.id, error: error, completedSteps: completedSteps)
            } catch {
                let runError = IOSRecipeRunError.stepFailed(
                    stepId: step.id, tool: step.tool, message: error.localizedDescription
                )
                return .failed(failedStep: step.id, error: runError, completedSteps: completedSteps)
            }
        }

        do {
            let outputs = try resolveOutputs(plan: plan, stepOutputs: stepOutputs)
            return .succeeded(outputs: outputs, completedSteps: completedSteps)
        } catch let error as IOSRecipeRunError {
            return .failed(failedStep: nil, error: error, completedSteps: completedSteps)
        } catch {
            let runError = IOSRecipeRunError.outputResolution(
                outputName: "", reason: error.localizedDescription
            )
            return .failed(failedStep: nil, error: runError, completedSteps: completedSteps)
        }
    }

    /// Resolves one step's arguments into canonical JSON WITHOUT executing —
    /// the production route uses it before the approval gate so the approval
    /// card can show exactly what the step will call with; `runStep` calls
    /// the same resolution again (deterministic, so no divergence).
    func resolveArguments(
        step: IOSRecipePlanStep,
        inputs: [String: IOSRecipeJSONValue],
        stepOutputs: [String: String]
    ) throws -> String {
        var resolvedArguments: [String: IOSRecipeJSONValue] = [:]
        for (key, value) in step.arguments {
            switch value {
            case .literal(let literal):
                resolvedArguments[key] = literal
            case .binding(let binding):
                resolvedArguments[key] = try resolve(
                    binding, stepId: step.id, key: key, inputs: inputs, stepOutputs: stepOutputs
                )
            }
        }
        return try Self.canonicalArgumentsJSON(resolvedArguments)
    }

    /// Resolves + executes ONE step of a resolved plan. Shared by `run()` and
    /// the resumable production loop (ChatToolRuntime's `recipe__*` route): a
    /// mutation step can be paused for approval between `runStep` calls, and
    /// each `runStep` invocation is an independent, W1-paired ledger
    /// Started/Finished unit under `recipe-<executionId>-<step.id>`.
    ///
    /// Throws `IOSRecipeRunError` on ANY failure; the Finished(failed) ledger
    /// record is written here (same codes as the pre-refactor `run()`), so a
    /// caller translating the throw into an outcome must NOT write the ledger
    /// again. A successful return is the step's raw output JSON, which later
    /// steps may bind into.
    func runStep(
        plan: IOSRecipeExecutionPlan,
        step: IOSRecipePlanStep,
        executionId: String,
        inputs: [String: IOSRecipeJSONValue],
        stepOutputs: [String: String]
    ) async throws -> String {
        let toolCallId = "recipe-\(executionId)-\(step.id)"

        // Resolve this step's arguments strictly: literals pass through,
        // bindings resolve against call inputs / earlier step outputs.
        let argsJSON: String
        do {
            argsJSON = try resolveArguments(step: step, inputs: inputs, stepOutputs: stepOutputs)
        } catch let error as IOSRecipeRunError {
            await recordFinished(
                plan: plan, executionId: executionId, toolCallId: toolCallId, step: step,
                outcome: "failed", outcomeKind: "error", errorCode: "argument_binding",
            )
            throw error
        } catch {
            let runError = IOSRecipeRunError.argumentBinding(
                stepId: step.id, key: "", reason: error.localizedDescription
            )
            await recordFinished(
                plan: plan, executionId: executionId, toolCallId: toolCallId, step: step,
                outcome: "failed", outcomeKind: "error", errorCode: "argument_binding",
            )
            throw runError
        }

        // W1 durable boundary: record Started before the primitive runs.
        // A failed Started write fails the step closed (no side effect
        // without a durable trace), mirroring IOSAgentToolEngine.
        if let ledger {
            let didStart = await ledger.recordToolCallStarted(
                runId: runId,
                toolCallId: toolCallId,
                toolName: step.tool,
                argsDigest: chatInputDigest(for: argsJSON),
                effectClass: step.effectClass
            )
            guard didStart else {
                let runError = IOSRecipeRunError.stepFailed(
                    stepId: step.id, tool: step.tool, message: "ledger started write failed"
                )
                await recordFinished(
                    plan: plan, executionId: executionId, toolCallId: toolCallId, step: step,
                    outcome: "failed", outcomeKind: "error", errorCode: "ledger_write_failed",
                )
                throw runError
            }
        }

        do {
            let output = try await executeStepWithTimeout(step, argsJSON: argsJSON)
            if let ledger {
                await ledger.recordToolCallFinished(
                    runId: runId,
                    toolCallId: toolCallId,
                    outcome: "completed",
                    artifactId: "recipe__\(plan.recipeName)",
                    artifactVersion: plan.recipeVersion,
                    outcomeKind: "success",
                    errorCode: nil,
                    sourceRef: executionId
                )
            }
            return output
        } catch let error as IOSRecipeRunError {
            await recordFinished(
                plan: plan, executionId: executionId, toolCallId: toolCallId, step: step,
                outcome: "failed", outcomeKind: "error",
                errorCode: Self.errorCode(for: error),
            )
            throw error
        } catch {
            let runError = IOSRecipeRunError.stepFailed(
                stepId: step.id, tool: step.tool, message: error.localizedDescription
            )
            await recordFinished(
                plan: plan, executionId: executionId, toolCallId: toolCallId, step: step,
                outcome: "failed", outcomeKind: "error", errorCode: "step_failed",
            )
            throw runError
        }
    }

    /// Resolves the plan's outputs after every step completed: each output is
    /// a binding into a step's raw output JSON (top-level field only). Throws
    /// `IOSRecipeRunError.outputResolution` — shared by `run()` and the
    /// resumable production loop.
    func resolveOutputs(
        plan: IOSRecipeExecutionPlan,
        stepOutputs: [String: String]
    ) throws -> [String: IOSRecipeJSONValue] {
        var outputs: [String: IOSRecipeJSONValue] = [:]
        for (name, binding) in plan.outputs.sorted(by: { $0.key < $1.key }) {
            guard case .stepOutput(let stepId, let field) = binding.source else { continue }
            guard let raw = stepOutputs[stepId] else {
                throw IOSRecipeRunError.outputResolution(
                    outputName: name, reason: "step「\(stepId)」没有输出"
                )
            }
            guard let value = Self.extractField(from: raw, field: field) else {
                throw IOSRecipeRunError.outputResolution(
                    outputName: name,
                    reason: "step「\(stepId)」的输出不是 JSON 对象或缺少顶层字段「\(field)」"
                )
            }
            outputs[name] = value
        }
        return outputs
    }

    /// Deterministic JSON rendering of a failure (§10.3.6), shaped like the
    /// existing tool failure envelope (`{"ok":false,"tool":...,"reason":...}`)
    /// so the tool timeline UI renders it without new code. Nil when the
    /// outcome is a success.
    func structuredErrorJSON(for outcome: IOSRecipeRunOutcome) -> String? {
        guard case .failed(let failedStep, let error, _) = outcome else { return nil }
        let reason: String
        switch error {
        case .planInvalid(let issues):
            reason = "Recipe 校验失败（\(issues.count) 个问题）。"
        case .inputInvalid(let message):
            reason = message
        case .argumentBinding(let stepId, let key, let bindingReason):
            let keyText = key.isEmpty ? "" : "「\(key)」"
            reason = "step「\(stepId)」参数\(keyText)绑定失败：\(bindingReason)"
        case .stepFailed(_, _, let message):
            reason = message
        case .stepTimeout(let stepId, _, let seconds):
            reason = "step「\(stepId)」超过 \(seconds) 秒超时。"
        case .outputResolution(let outputName, let outputReason):
            reason = "输出「\(outputName)」解析失败：\(outputReason)"
        }
        let payload: [String: Any] = [
            "ok": false,
            "tool": "recipe__\(manifest.name)",
            "step": failedStep ?? NSNull(),
            "reason": reason,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        return text
    }

    // MARK: Private

    private func validateInputs(_ inputs: [String: IOSRecipeJSONValue]) throws {
        for (name, type) in manifest.inputs.sorted(by: { $0.key < $1.key }) {
            guard let value = inputs[name] else {
                throw IOSRecipeRunError.inputInvalid(message: "缺少声明输入「\(name)」。")
            }
            let matches: Bool
            switch type {
            case .string: matches = if case .string = value { true } else { false }
            case .number: matches = if case .number = value { true } else { false }
            case .boolean: matches = if case .bool = value { true } else { false }
            }
            guard matches else {
                throw IOSRecipeRunError.inputInvalid(message: "输入「\(name)」类型必须是 \(type.rawValue)。")
            }
        }
        let undeclared = Set(inputs.keys).subtracting(manifest.inputs.keys).sorted()
        if !undeclared.isEmpty {
            throw IOSRecipeRunError.inputInvalid(message: "未声明的输入：\(undeclared.joined(separator: ", "))。")
        }
    }

    private func resolve(
        _ binding: IOSRecipeBinding,
        stepId: String,
        key: String,
        inputs: [String: IOSRecipeJSONValue],
        stepOutputs: [String: String]
    ) throws -> IOSRecipeJSONValue {
        switch binding.source {
        case .input(let name):
            guard let value = inputs[name] else {
                throw IOSRecipeRunError.argumentBinding(
                    stepId: stepId, key: key, reason: "输入「\(name)」在本次调用中缺失"
                )
            }
            return value
        case .stepOutput(let referencedStepId, let field):
            guard let raw = stepOutputs[referencedStepId] else {
                throw IOSRecipeRunError.argumentBinding(
                    stepId: stepId, key: key, reason: "前序 step「\(referencedStepId)」尚未产生输出"
                )
            }
            guard let value = Self.extractField(from: raw, field: field) else {
                throw IOSRecipeRunError.argumentBinding(
                    stepId: stepId, key: key,
                    reason: "step「\(referencedStepId)」的输出不是 JSON 对象或缺少顶层字段「\(field)」"
                )
            }
            return value
        }
    }

    private func executeStepWithTimeout(_ step: IOSRecipePlanStep, argsJSON: String) async throws -> String {
        do {
            return try await withThrowingTaskGroup(of: String.self) { group in
                group.addTask { [executePrimitive] in
                    // The primitive closure is MainActor-isolated (production
                    // wiring dispatches through ChatToolRuntime); calling it
                    // from this nonisolated child task hops onto the MainActor
                    // (the same automatic hop as any isolated async call).
                    try await executePrimitive(step.tool, argsJSON)
                }
                group.addTask {
                    try await Task.sleep(for: .seconds(Double(step.timeoutSeconds)))
                    throw IOSRecipeRunError.stepTimeout(
                        stepId: step.id, tool: step.tool, timeoutSeconds: step.timeoutSeconds
                    )
                }
                guard let first = try await group.next() else {
                    // Unreachable: the timeout task always yields a result.
                    throw CancellationError()
                }
                group.cancelAll()
                return first
            }
        } catch let error as IOSRecipeRunError {
            throw error
        } catch {
            // The primitive failed (or the enclosing task was cancelled).
            if Task.isCancelled {
                throw IOSRecipeRunError.stepFailed(
                    stepId: step.id, tool: step.tool, message: "执行被取消"
                )
            }
            throw IOSRecipeRunError.stepFailed(
                stepId: step.id, tool: step.tool, message: error.localizedDescription
            )
        }
    }

    private func recordFinished(
        plan: IOSRecipeExecutionPlan,
        executionId: String,
        toolCallId: String,
        step: IOSRecipePlanStep,
        outcome: String,
        outcomeKind: String,
        errorCode: String?
    ) async {
        guard let ledger else { return }
        await ledger.recordToolCallFinished(
            runId: runId,
            toolCallId: toolCallId,
            outcome: outcome,
            artifactId: "recipe__\(plan.recipeName)",
            artifactVersion: plan.recipeVersion,
            outcomeKind: outcomeKind,
            errorCode: errorCode,
            sourceRef: executionId
        )
    }

    private static func errorCode(for error: IOSRecipeRunError) -> String {
        switch error {
        case .planInvalid: return "plan_invalid"
        case .inputInvalid: return "input_invalid"
        case .argumentBinding: return "argument_binding"
        case .stepFailed: return "step_failed"
        case .stepTimeout: return "step_timeout"
        case .outputResolution: return "output_resolution"
        }
    }

    private static func extractField(from rawJSON: String, field: String) -> IOSRecipeJSONValue? {
        guard let data = rawJSON.data(using: .utf8),
              let value = try? JSONDecoder().decode(IOSRecipeJSONValue.self, from: data),
              case .object(let dict) = value,
              let fieldValue = dict[field] else {
            return nil
        }
        return fieldValue
    }

    private static func canonicalArgumentsJSON(_ args: [String: IOSRecipeJSONValue]) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(args)
        guard let text = String(data: data, encoding: .utf8) else {
            throw IOSRecipeRunError.argumentBinding(
                stepId: "", key: "", reason: "参数 JSON 不是 UTF-8"
            )
        }
        return text
    }
}
