import Foundation
@preconcurrency import Shared

// MARK: - W1 durable tool-execution ledger ("先记账，后动手")
//
// See docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md §W1 / invariant I-1. Writes
// a Started/Finished pair per tool call to the shared `agent_event` Room table
// BEFORE and AFTER the tool's side effect, so a process death mid-execution
// leaves a durable "we called X and don't know if it finished" trace instead
// of amnesia. This file only writes the ledger; W3 (crash-recovery UX) reads
// it back and decides what to tell the user — the pairing/classification rule
// it will need is included here as a pure function so its contract is locked
// in now (`IOSToolCallLedgerClassifier`).

/// How safe a tool call is to blindly retry after its outcome becomes unknown
/// (process died between Started and Finished). Three values only — amber
/// runs one tool at a time per run, so it doesn't need the fuller
/// `resourceKey`/lock model a concurrent executor would.
///
/// - `pure`: no observable side effect (search, fetch, asking the user a
///   question). Always safe to retry.
/// - `idempotent`: has a side effect, but re-running with the same arguments
///   converges to the same state (memory edit/delete by stable id). Safe to retry.
/// - `sideEffect`: re-running could double-apply or double-charge (workspace
///   writes, shell execution, webMount mutation, MCP/council/subagent calls).
///   Never auto-retried; W3 must surface it as "outcome unknown" instead.
public enum IOSToolEffectClass: String, Sendable, Equatable {
    case pure
    case idempotent
    case sideEffect
}

/// Testable surface for the ledger. Production uses `IOSAgentRunLedger`
/// (Room-backed); tests substitute a spy that records calls and can force a
/// failure to exercise the fail-closed path without touching the real DB.
///
/// `public` solely because `IOSAgentToolEngine`'s public initializer takes an
/// optional `IOSAgentRunLedgering?` — Swift requires a public API's parameter
/// types to be at least as visible as the API itself, even though every
/// conformer (`IOSAgentRunLedger`, the test spy) lives in this same module.
public protocol IOSAgentRunLedgering: Sendable {
    @discardableResult
    func recordToolCallStarted(
        runId: String,
        toolCallId: String,
        toolName: String,
        argsDigest: String,
        effectClass: IOSToolEffectClass
    ) async -> Bool

    func recordToolCallFinished(
        runId: String,
        toolCallId: String,
        outcome: String
    ) async
}

/// Room-backed production ledger. An `actor` so同一实例内的 seq 查询→插入
/// 不会交错；跨实例（前台/后台协调器各持一个）的交错由
/// `insertWithFreshSeq` 的现查现写 + 失败重试兜底，见该方法注释。
actor IOSAgentRunLedger: IOSAgentRunLedgering {
    /// Matches the agent_run rows chat writes today (`ChatViewModel.recordRun`,
    /// `IOSChatBackgroundGenerationCoordinator.recordRun`) — same descriptor
    /// space, same table, so a run's `agent_run` row and its `agent_event` rows
    /// agree on who wrote them.
    static let agentDescriptorId = "chat"
    static let agentVersion = "1"

    private let dao: AgentRuntimeDao

    init(dao: AgentRuntimeDao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()) {
        self.dao = dao
    }

    @discardableResult
    func recordToolCallStarted(
        runId: String,
        toolCallId: String,
        toolName: String,
        argsDigest: String,
        effectClass: IOSToolEffectClass
    ) async -> Bool {
        let payload = Self.jsonPayload([
            "toolCallId": toolCallId,
            "toolName": toolName,
            "argsDigest": argsDigest,
            "effectClass": effectClass.rawValue,
        ])
        return await insertWithFreshSeq(runId: runId) { seq in
            AgentEventEntity(
                eventId: UUID().uuidString,
                runId: runId,
                parentRunId: nil,
                seq: seq,
                type: "tool_call_started",
                payloadType: "tool_call_started",
                payload: payload,
                payloadSchemaVersion: 1,
                agentDescriptorId: Self.agentDescriptorId,
                agentVersion: Self.agentVersion,
                isFinal: false,
                ts: Self.nowMillis()
            )
        }
    }

    func recordToolCallFinished(
        runId: String,
        toolCallId: String,
        outcome: String
    ) async {
        let payload = Self.jsonPayload([
            "toolCallId": toolCallId,
            "outcome": outcome,
        ])
        // The attempt either produced an outcome or was definitively stopped
        // before its side effect began. A failed write here can't change that
        // fact, so unlike Started, we only log and move on.
        let ok = await insertWithFreshSeq(runId: runId) { seq in
            AgentEventEntity(
                eventId: UUID().uuidString,
                runId: runId,
                parentRunId: nil,
                seq: seq,
                type: "tool_call_finished",
                payloadType: "tool_call_finished",
                payload: payload,
                payloadSchemaVersion: 1,
                agentDescriptorId: Self.agentDescriptorId,
                agentVersion: Self.agentVersion,
                isFinal: false,
                ts: Self.nowMillis()
            )
        }
        if !ok {
            print("[AmberChat] tool_call_finished ledger write failed run=\(runId) toolCallId=\(toolCallId) outcome=\(outcome)")
        }
    }

    /// seq 分配不做内存缓存：前台协调器与后台续跑协调器各持一个账本实例、写同一
    /// runId（用户来回切 app 会 ping-pong），任何一侧缓存的计数器都会在对侧写入后
    /// 过期，撞上 `(run_id, seq)` 唯一索引——那会让一次无辜的切 app 把 Started 写
    /// 判成失败、进而杀掉整轮。所以每次写都现查 `MAX(seq)+1`（每 run 写入次数个位
    /// 数，带索引的 MAX 查询成本可忽略），插入失败（罕见的两实例交错）就重查重试
    /// 一次，自愈而不是把碰撞升级成轮次失败。
    private func insertWithFreshSeq(
        runId: String,
        build: (Int64) -> AgentEventEntity
    ) async -> Bool {
        for _ in 0..<2 {
            // F13 fix: a genuine query error must not be treated the same as
            // "no rows exist yet for this run" — both used to collapse to
            // `seq = 1` (see the old `?? 0` below), so a real DB error nearly
            // always collided with an existing row's `(run_id, seq)` unique
            // index instead of surfacing as a retryable failure. `nextSeq`
            // now returns `nil` on error; skip straight to the next retry
            // attempt without wasting an insert on a seq we know is wrong.
            guard let seq = await nextSeq(for: runId) else { continue }
            if await insert(build(seq)) { return true }
        }
        return false
    }

    private func nextSeq(for runId: String) async -> Int64? {
        let maxSeq: Int64? = await withCheckedContinuation { continuation in
            dao.maxEventSeq(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: result.int64Value)
            }
        }
        return maxSeq.map { $0 + 1 }
    }

    private func insert(_ event: AgentEventEntity) async -> Bool {
        await withCheckedContinuation { continuation in
            dao.insertEvent(event: event) { error in
                if let error {
                    print("[AmberChat] agent_event insert failed: \(error)")
                    continuation.resume(returning: false)
                } else {
                    continuation.resume(returning: true)
                }
            }
        }
    }

    private static func jsonPayload(_ fields: [String: String]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

/// effectClass lookup tables (§W1 table), split by call site because the two
/// execution paths key tools differently:
///   - chat foreground/approval path knows only `ChatPendingToolKind` (a
///     coarser grouping computed by `ChatToolRuntime.nextPendingToolCall`);
///   - the reusable engine (`IOSAgentToolEngine`) dispatches by raw tool name
///     against an executor map, so it classifies by name directly.
/// Both fail safe: anything not explicitly listed maps to `.sideEffect`, so an
/// unrecognized tool is never assumed retryable after a crash.
enum IOSToolEffectClassMapping {
    static func forChatKind(
        _ kind: ChatPendingToolKind,
        input: String
    ) -> IOSToolEffectClass {
        switch kind {
        case .toolSearch:
            // Local catalog search — no effect in the world.
            .pure
        case .search:
            // search_web / scrape_web — read-only network calls.
            .pure
        case .memory:
            memoryEffectClass(input: input)
        case .askUser:
            // Asking a question has no effect in the world.
            .pure
        case .sessionRead:
            // 跨会话读取（session_search/session_read）——本地只读检索，无副作用。
            .pure
        case .workspace, .ish, .webMount, .image, .advanced:
            // advanced covers mcp_call / subagent_dispatch / model_council_run.
            .sideEffect
        }
    }

    static func forToolName(_ toolName: String, input: String) -> IOSToolEffectClass {
        if toolName == "tool_search" {
            // P0-a: local catalog search — no effect in the world.
            return .pure
        }
        if toolName == "tools_list" {
            // M5: tools_list 与 tool_search 同属本地目录调用（发现引导同一路径），
            // 纯读无副作用——与 ChatToolRuntime 的 kind(.toolSearch) 分类对齐，
            // 也保证生成中在途 tools_list 允许后台交接重放。
            return .pure
        }
        if IOSSearchExecutor.supportedToolNames.contains(toolName) {
            return .pure
        }
        if toolName == "ask_user" {
            return .pure
        }
        // 跨会话读取工具：本地只读检索（搜索/读取其它会话），重放无副作用。
        if toolName == "session_search" || toolName == "session_read" {
            return .pure
        }
        if toolName == "memory_tool" {
            return memoryEffectClass(input: input)
        }
        if IOSWorkspaceToolCatalog.supportedToolNames.contains(toolName) {
            return .sideEffect
        }
        if IOSIshToolCatalog.supportedToolNames.contains(toolName) {
            return .sideEffect
        }
        if IOSEmbeddedIshToolCatalog.supportedToolNames.contains(toolName) {
            return .sideEffect
        }
        if IOSWebMountToolCatalog.supportedToolNames.contains(toolName)
            || IOSWebMountToolCatalog.unsupportedToolNames.contains(toolName) {
            return .sideEffect
        }
        if toolName == "skills_list"
            || toolName == "use_skill"
            || toolName == "skill_validate"
            || toolName == "mcp_list" {
            return .pure
        }
        if toolName == "generate_image"
            || toolName == "subagent_dispatch"
            || toolName == "model_council_run"
            || toolName == "mcp_call"
            || ToolKt.isExpandedMcpToolName(name: toolName) // P0-b: flattened MCP calls
            || toolName == "mcp_test"
            || toolName == "mcp_import_from_skill"
            || toolName == "skill_import"
            || toolName == "skill_enable"
            || toolName == "skill_disable" {
            return .sideEffect
        }
        // P1-c: 编排工具——spawn/interrupt 有真实副作用（建线程/取消 run），
        // list_agents 只读。
        if toolName == "spawn_agent" || toolName == "interrupt_agent" {
            return .sideEffect
        }
        if toolName == "list_agents" {
            return .pure
        }
        // P1-d: send/followup 投递信封（重放会重复投递）→ sideEffect；
        // wait_agent 只等 mailbox 活动（无外部副作用）→ pure。
        if toolName == "send_message" || toolName == "followup_task" {
            return .sideEffect
        }
        if toolName == "wait_agent" {
            return .pure
        }
        // P3-a: exec 运行任意 JS——重放会重复执行，按 shell 执行同类处理。
        if toolName == "exec" {
            return .sideEffect
        }
        // P3-c: wait 推进 cell 状态（terminate=true 真实变更；读取也消耗
        // read-once 终态）——显式钉死为保守 sideEffect（fail-safe 默认同值）：
        // 崩溃后不自动重试 wait，避免重试读到推进后的另一终态。
        if toolName == "wait" {
            return .sideEffect
        }
        // Fail-safe default (I-3): a tool name this map doesn't know about
        // must never be assumed retryable — better to under-retry a pure tool
        // than to auto-replay an unknown side effect.
        return .sideEffect
    }

    /// `memory_tool` multiplexes reads and three materially different write
    /// shapes. Creates allocate a fresh record id and can duplicate data, while
    /// edit/delete address a stable id and converge when repeated.
    private static func memoryEffectClass(input: String) -> IOSToolEffectClass {
        guard let data = input.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawAction = object["action"] as? String else {
            return .sideEffect
        }
        switch rawAction.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "list", "read", "search", "query", "status":
            return .pure
        case "edit", "update", "delete", "remove":
            return .idempotent
        case "create", "add", "write":
            return .sideEffect
        default:
            return .sideEffect
        }
    }
}

// MARK: - S3 ground work: pairing/classification (pure, no I/O)

/// Recovery classification for one tool call's ledger trail, keyed off the
/// LAST `tool_call_started` for that `toolCallId` (a call that was Started,
/// paused for approval, Finished, then re-Started and re-Finished after
/// approval has TWO pairs — only the latest matters for "is anything
/// currently unresolved").
enum IOSToolCallRecoveryOutcome: String, Equatable {
    /// No unresolved call: either it never started, or the last Started has a
    /// matching Finished (including the honest "paused_for_approval" outcome,
    /// which the awaiting-permission recovery path already owns).
    case clean
    /// Last Started has no Finished, but the tool is pure/idempotent — safe to
    /// offer an automatic retry.
    case retryable
    /// Last Started has no Finished and the tool is a side effect — must be
    /// surfaced as "did this actually happen?" and never auto-retried.
    case outcomeUnknown
}

/// One ledger row, reduced to what the classifier needs. Callers project
/// `AgentEventEntity` rows (`type`/`seq`) into this before calling — kept
/// separate from the Room entity so the classifier has zero KMP/Room
/// dependency and can be exercised with plain fixtures.
struct IOSToolCallLedgerEvent: Equatable {
    let type: String
    let seq: Int64
}

enum IOSToolCallLedgerClassifier {
    static let startedType = "tool_call_started"
    static let finishedType = "tool_call_finished"

    /// `events` should already be filtered to one `toolCallId`'s rows; order
    /// does not matter, this sorts by `seq` itself.
    static func classify(
        events: [IOSToolCallLedgerEvent],
        effectClass: IOSToolEffectClass
    ) -> IOSToolCallRecoveryOutcome {
        let sorted = events.sorted { $0.seq < $1.seq }
        guard let lastStarted = sorted.last(where: { $0.type == startedType }) else {
            // Never started: nothing to reconcile.
            return .clean
        }
        let hasFinishedAfter = sorted.contains { $0.type == finishedType && $0.seq > lastStarted.seq }
        if hasFinishedAfter {
            return .clean
        }
        switch effectClass {
        case .sideEffect:
            return .outcomeUnknown
        case .pure, .idempotent:
            return .retryable
        }
    }
}

// MARK: - W3: crash-recovery UX (§W3, invariant I-3)
//
// The classifier above answers "is this toolCallId resolved" per call; W3's
// recovery sweep needs one more layer on top of that to reach all three
// documented states, including the third one (§W3 step 3 / task point 3):
// a clean Started→Finished(completed) pairing whose result never reached the
// persisted conversation (died between Finished and the turn-end message
// save). That disambiguation needs the *outcome* string a Finished event
// carries and whether the tool part's output is still empty — neither of
// which `IOSToolCallLedgerClassifier.classify` looks at, by design (S2 kept
// it minimal). `IOSToolCallRecoveryPlanner` below is additive, not a
// replacement for it.

/// What a single toolCallId's ledger trail resolves to for the recovery UX.
/// I-3: three durable outcomes, none of which is "silently rerun" — every
/// case below writes a structured record into the tool part's `output`, so
/// `groupPartsByToolBoundary` (`ai-core/.../ProviderMessageUtils.kt:46`)
/// treats the call as resolved (`isExecuted`) rather than silently dropping
/// it from the next provider request. An earlier revision of this type had a
/// fourth "leave output empty" case for pure/idempotent tools, reasoning
/// that `nextPendingToolCall` would pick it back up on the next turn — that
/// was wrong: an empty-output Tool part falls into `groupPartsByToolBoundary`'s
/// Content group and is silently dropped when building the provider request,
/// so the call would simply vanish from the model's view, not "retry
/// automatically". Fixed per the same W2 fail-closed philosophy: hand the
/// state back to the model as text and let the model decide to re-call the
/// tool — a real, model-mediated retry instead of an app-level replay.
enum IOSToolCallRecoveryAction: Equatable {
    /// sideEffect class, unresolved Started (no Finished after it): the tool
    /// may or may not actually have run — never auto-rerun it. Written output
    /// makes that structural: once `output` is non-empty no mechanism in the
    /// app can ever re-dispatch this toolCallId.
    case markUnknown
    /// pure/idempotent class, unresolved Started: the tool provably did NOT
    /// finish (no side effect risk either way), so it is safe to hand back to
    /// the model as "not executed, safe to call again" — the model reads this
    /// like any other tool result and re-issues the call itself if the task
    /// still needs it.
    case markRetryable
    /// Clean Started/Finished(completed) pairing, but the conversation's
    /// persisted tool part still has an empty output: the side effect ran to
    /// completion, only the write of its result never made it to disk.
    case markResultLost

    /// User-visible text (I-3), rendered via `ChatToolOutputFormatter.failureReason(from:)`
    /// as the tool timeline's failed-step detail — the same JSON shape for
    /// all three states means the UI needs zero new rendering code, and the
    /// model reads the exact same text (it's what `output` actually is).
    var toolPartMessage: String {
        switch self {
        case .markUnknown:
            return "应用中断，此操作是否已生效未知。为避免重复执行的风险，已停止自动重试。"
        case .markRetryable:
            return "应用中断，该操作未执行（该工具无副作用，可安全重试）。如任务仍需要，请重新调用此工具。"
        case .markResultLost:
            return "工具已执行完成，但结果在应用中断中丢失（不会重复执行）。"
        }
    }
}

/// One decoded `agent_event` row, carrying what W3's grouping needs beyond
/// `IOSToolCallLedgerEvent`'s bare type/seq. Built by best-effort parsing of
/// `AgentEventEntity.payload` (a flat JSON string — see `IOSAgentRunLedger`'s
/// `jsonPayload`).
struct IOSToolCallLedgerRow: Equatable {
    let toolCallId: String
    let type: String
    let seq: Int64
    /// Present (non-nil) only on Started rows.
    let effectClass: IOSToolEffectClass?
    /// Present (non-nil) only on Finished rows.
    let outcome: String?

    /// Decodes one ledger row's payload. Returns `nil` only when `toolCallId`
    /// itself can't be recovered — without it the row can't be grouped with
    /// its siblings, so it is dropped rather than guessed at (there is
    /// nothing conservative to fall back to for "which call is this").
    ///
    /// A Started row whose `effectClass` field is missing or an unrecognized
    /// string still keeps its `toolCallId` and is attributed `.sideEffect` —
    /// the same fail-closed default `IOSToolEffectClassMapping` uses for an
    /// unrecognized tool name. Corrupt metadata must never be read as "safe
    /// to retry"; the worst case of defaulting to `.sideEffect` is an
    /// unnecessary "outcome unknown" notice, not a silent re-execution.
    static func decode(type: String, seq: Int64, payload: String) -> IOSToolCallLedgerRow? {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let toolCallId = (object["toolCallId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !toolCallId.isEmpty else {
            return nil
        }
        var effectClass: IOSToolEffectClass?
        if type == IOSToolCallLedgerClassifier.startedType {
            if let raw = object["effectClass"] as? String, let parsed = IOSToolEffectClass(rawValue: raw) {
                effectClass = parsed
            } else {
                effectClass = .sideEffect
            }
        }
        let outcome = object["outcome"] as? String
        return IOSToolCallLedgerRow(toolCallId: toolCallId, type: type, seq: seq, effectClass: effectClass, outcome: outcome)
    }
}

/// Pure grouping + decision function: many `IOSToolCallLedgerRow`s (one
/// run's whole `agent_event` history, potentially several toolCallIds across
/// several tool-resume turns) → at most one `IOSToolCallRecoveryAction` per
/// toolCallId that actually needs one. No I/O — callers own reading the
/// ledger and reading whether a tool part's persisted output is empty.
enum IOSToolCallRecoveryPlanner {
    static func plan(
        rows: [IOSToolCallLedgerRow],
        isOutputEmpty: (String) -> Bool
    ) -> [String: IOSToolCallRecoveryAction] {
        var byToolCallId: [String: [IOSToolCallLedgerRow]] = [:]
        for row in rows {
            byToolCallId[row.toolCallId, default: []].append(row)
        }
        var actions: [String: IOSToolCallRecoveryAction] = [:]
        for (toolCallId, toolRows) in byToolCallId {
            let sorted = toolRows.sorted { $0.seq < $1.seq }
            guard let lastStarted = sorted.last(where: { $0.type == IOSToolCallLedgerClassifier.startedType }) else {
                continue // Never started: nothing to reconcile.
            }
            let effectClass = lastStarted.effectClass ?? .sideEffect
            let finishedAfter = sorted.last(where: {
                $0.type == IOSToolCallLedgerClassifier.finishedType && $0.seq > lastStarted.seq
            })
            guard let finishedAfter else {
                // Unresolved (same condition `classify` calls outcomeUnknown/retryable).
                actions[toolCallId] = (effectClass == .sideEffect) ? .markUnknown : .markRetryable
                continue
            }
            // Clean pairing. "paused_for_approval" is the awaiting-approval
            // hand-off, already owned by `IOSRunRecovery`'s pending-approval
            // path — only a completed call whose result never reached disk
            // (§W3 third state) is actionable here.
            if finishedAfter.outcome == "completed", isOutputEmpty(toolCallId) {
                actions[toolCallId] = .markResultLost
            }
        }
        return actions
    }
}

/// Applies a `IOSToolCallRecoveryPlanner.plan` result to one conversation's
/// message list. Pure (no I/O) and deliberately independent of
/// `ChatToolRuntime` — replacing one tool part's `output` needs none of its
/// settings/executor plumbing, and keeping this free-standing lets both the
/// pending-approval and plain-interrupted recovery paths share it, and lets
/// tests exercise it without constructing a full runtime.
///
/// One code path for all three states: every `IOSToolCallRecoveryAction`
/// writes its `toolPartMessage` into the matching tool part's `output` via
/// the same `failureJSON` shape. This is deliberate, not incidental — a Tool
/// part with empty `output` is dropped from the next provider request by
/// `groupPartsByToolBoundary` (`ai-core/.../ProviderMessageUtils.kt:46`,
/// which only treats `isExecuted` parts as boundaries), so leaving `output`
/// empty for any of these states would make the call vanish from the
/// model's view instead of "staying resumable".
enum IOSToolCallRecoveryApplier {
    static func apply(
        _ actions: [String: IOSToolCallRecoveryAction],
        to messages: [UIMessage]
    ) -> [UIMessage] {
        guard !actions.isEmpty else { return messages }
        var result = messages
        // Deterministic order so tests (and any future logging) aren't at the
        // mercy of Dictionary's iteration order.
        for toolCallId in actions.keys.sorted() {
            guard let action = actions[toolCallId] else { continue }
            guard let toolPart = result
                .flatMap(\.parts)
                .compactMap({ $0 as? UIMessagePart.Tool })
                .first(where: { $0.toolCallId == toolCallId && $0.output.isEmpty }) else { continue }
            let failureText = Self.failureJSON(toolName: toolPart.toolName, reason: action.toolPartMessage)
            result = replacingToolOutput(toolCallId: toolCallId, outputText: failureText, in: result)
        }
        return result
    }

    /// Same `{"ok":false,"tool":...,"reason":...}` shape as
    /// `ChatToolOutputFormatter.toolFailureJSON` (so `failureReason(from:)`
    /// recognizes it and the tool timeline UI renders it as a failed step
    /// with `reason` as the detail) — reimplemented locally rather than
    /// calling that formatter directly because `ChatToolOutputFormatter` is
    /// `@MainActor`-isolated and this applier must stay a plain, testable,
    /// non-isolated pure function.
    private static func failureJSON(toolName: String, reason: String) -> String {
        let payload: [String: Any] = ["ok": false, "tool": toolName, "reason": reason]
        guard let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "\(toolName) failed: \(reason)"
        }
        return text
    }

    private static func replacingToolOutput(
        toolCallId: String,
        outputText: String,
        in messages: [UIMessage]
    ) -> [UIMessage] {
        var didReplace = false
        return messages.map { message in
            guard message.role == MessageRole.assistant, !didReplace else { return message }
            var didChangeMessage = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard !didReplace,
                      let toolPart = part as? UIMessagePart.Tool,
                      toolPart.toolCallId == toolCallId else { return part }
                didReplace = true
                didChangeMessage = true
                return UIMessagePart.Tool(
                    toolCallId: toolPart.toolCallId,
                    toolName: toolPart.toolName,
                    input: toolPart.input,
                    output: [UIMessagePart.Text(text: outputText, metadata: nil)],
                    approvalState: toolPart.approvalState,
                    streamIndex: toolPart.streamIndex,
                    metadata: nil
                )
            }
            guard didChangeMessage else { return message }
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
}
