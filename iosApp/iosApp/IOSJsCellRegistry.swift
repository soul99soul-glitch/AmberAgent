import Foundation

/// P3-c: one exec cell's tool-facing status.
///
/// The exec/wait state machine has exactly four tool-visible states
/// (`Running → Completed | Terminated | Failed`). `interrupted` is a FIFTH,
/// sweep-only marker: it is produced exclusively by the cold-start sweep (a
/// Running cell whose process died) and never by exec/wait themselves — the
/// persisted truth must distinguish "script exception" (Failed) from "process
/// died mid-run" (interrupted), never faking a completion.
enum IOSJsCellStatus: String, Codable, Equatable {
    case running
    case completed
    case terminated
    case failed
    case interrupted
}

/// One cell record. Codable because cells are persisted in the session
/// sidecar so a process death can sweep them on the next cold start.
struct IOSJsCellRecord: Codable, Equatable {
    let cellId: String
    var status: IOSJsCellStatus
    /// Epoch milliseconds.
    var createdAtMs: Int64
    /// Completed: the evaluated result payload (same shape exec returns, see
    /// IOSJsSandboxEngine.toolPayload). Nil for every other status.
    var output: String?
    /// Console capture of the evaluation, entries prefixed `[LOG]`/`[INFO]`/...
    var logs: [String]
    /// Failed: the script exception message.
    var error: String?
}

/// Per-session persisted state: cells + the `store`/`load` KV namespace.
/// One sidecar file per conversation (`Documents/js-cells/{conversationId}.json`)
/// holds BOTH — the cell records reference the session store implicitly (same
/// file, same session key), so "store 引用" needs no extra field.
struct IOSJsSessionState: Codable, Equatable {
    var cells: [IOSJsCellRecord] = []
    var store: [String: String] = [:]
}

enum IOSJsStartCellOutcome: Equatable {
    case started
    case limitReached(runningCount: Int)
}

enum IOSJsCellWaitOutcome: Equatable {
    /// The cell does not exist (or was already read once — cells are read-once).
    case notFound
    /// Terminal state (completed/terminated/failed/interrupted). Consumes the cell.
    case terminal(IOSJsCellRecord)
    /// The wait timeout elapsed while the cell was still Running; the cell stays.
    case stillRunning(IOSJsCellRecord)
    /// M6: the waiting run/task was cancelled while blocked; the cell stays
    /// Running (no state change — the caller owns cancellation semantics).
    case cancelled
}

enum IOSJsStoreOutcome: Equatable {
    case stored
    case overLimit(reason: String)
}

/// P3-c: per-session cell registry — the single owner of all cell and store
/// state.
///
/// Owner rationale (multi-writer defense): the registry is an `actor`, so
/// EVERY mutation — exec cell start, evaluation completion, wait
/// read/terminate, store/load, cold-start sweep — is serialized through one
/// exclusive owner. No other object can write cell state: the sandbox engine
/// only REPORTS evaluation results through the completion closure, and the
/// tool runtime only calls registry methods. This is the same
/// single-owner-via-actor discipline as `IOSMailboxActivityCenter`, and it
/// makes the "session-scoped, shared across runs" contract hold by
/// construction: cells and the store KV are keyed by conversationId and
/// outlive any single run (a run ending never kills its cells).
///
/// Persistence: one sidecar per session key in the injected directory,
/// written atomically on every mutation; an empty session leaves no file
/// (zero trace). Cold start = a fresh registry instance reading the same
/// sidecar: Running cells are swept to `interrupted` and the swept state is
/// persisted before any cell is served.
actor IOSJsCellRegistry {

    /// Production instance (`Documents/js-cells`). Tests inject fresh
    /// instances with a temp directory so suites stay hermetic.
    static let shared = IOSJsCellRegistry(directory: IOSJsCellRegistry.defaultDirectory())

    /// Concurrency hard cap per session: the 5th concurrent Running cell is
    /// rejected with a structured error before any evaluation starts.
    static let maxRunningCellsPerSession = 4

    /// Bounded retention for terminal cells the model never waited on: a
    /// session keeps at most this many, evicting oldest first (cells are
    /// normally read-once and short-lived; this bound is only a safety net).
    static let maxRetainedTerminalCellsPerSession = 16

    /// store/load capacity limits (UTF-8 bytes of the JSON-encoded value).
    static let maxStoreValueBytes = 64 * 1024
    static let maxStoreTotalBytes = 1024 * 1024

    private let directory: URL
    private var sessions: [String: IOSJsSessionState] = [:]
    /// Waiters blocked on a Running cell, keyed by cellId. Resumed exactly
    /// once either by the cell's terminal transition or by their own timeout.
    private var waitersByCell: [String: [(UUID, CheckedContinuation<IOSJsCellWaitOutcome, Never>)]] = [:]

    init(directory: URL) {
        self.directory = directory
    }

    static func defaultDirectory() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return documents.appendingPathComponent("js-cells", isDirectory: true)
    }

    // MARK: - Cells

    /// Registers a new Running cell and persists it immediately, so a process
    /// death can sweep it. Rejects when the session already has
    /// `maxRunningCellsPerSession` Running cells.
    func startCell(sessionKey: String, cellId: String) -> IOSJsStartCellOutcome {
        var state = sessionState(sessionKey)
        let runningCount = state.cells.filter { $0.status == .running }.count
        guard runningCount < Self.maxRunningCellsPerSession else {
            return .limitReached(runningCount: runningCount)
        }
        // Bound the retention of terminal cells the model never waited on:
        // cells are normally read-once, but an abandoned yield would otherwise
        // accumulate forever. Cells are append-ordered by creation, so the
        // first terminal cells are the oldest — evict them first.
        let terminalOverflow = state.cells.filter { $0.status != .running }.count
            - Self.maxRetainedTerminalCellsPerSession
        if terminalOverflow > 0 {
            var kept: [IOSJsCellRecord] = []
            var dropped = 0
            for cell in state.cells {
                if cell.status == .running || dropped >= terminalOverflow {
                    kept.append(cell)
                } else {
                    dropped += 1
                }
            }
            state.cells = kept
        }
        state.cells.append(IOSJsCellRecord(
            cellId: cellId,
            status: .running,
            createdAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            output: nil,
            logs: [],
            error: nil
        ))
        sessions[sessionKey] = state
        persist(state, sessionKey: sessionKey)
        return .started
    }

    /// Terminal transition driven by the engine's completion listener (fires
    /// exactly once per evaluation, never on timeout-abandon). Resumes any
    /// registered waiters with the terminal record and consumes the cell;
    /// without waiters the cell stays terminal until a `wait` reads it
    /// (read-once). Inline-terminal cells are removed by the exec dispatch
    /// itself (`removeCell`) because the model never saw their cell_id.
    func finishCell(sessionKey: String, cellId: String, result: IOSJsSandboxResult, maxOutputChars: Int) {
        guard var state = sessions[sessionKey],
              let index = state.cells.firstIndex(where: { $0.cellId == cellId }),
              state.cells[index].status == .running else { return }
        let base = state.cells[index]
        let record: IOSJsCellRecord
        switch result {
        case .success(let value, let logs):
            record = IOSJsCellRecord(
                cellId: cellId,
                status: .completed,
                createdAtMs: base.createdAtMs,
                output: IOSJsSandboxEngine.toolPayload(result, maxOutputChars: maxOutputChars),
                logs: logs,
                error: nil
            )
        case .failure(let message):
            record = IOSJsCellRecord(
                cellId: cellId,
                status: .failed,
                createdAtMs: base.createdAtMs,
                output: nil,
                logs: [],
                error: message
            )
        case .timedOut:
            // The completion listener never fires on timeout (the evaluation
            // is still running); this is only a defensive no-op.
            return
        }
        state.cells[index] = record
        if let waiters = waitersByCell[cellId], !waiters.isEmpty {
            waitersByCell[cellId] = nil
            state.cells.remove(at: index)
            sessions[sessionKey] = state
            persist(state, sessionKey: sessionKey)
            for waiter in waiters {
                waiter.1.resume(returning: .terminal(record))
            }
        } else {
            sessions[sessionKey] = state
            persist(state, sessionKey: sessionKey)
        }
    }

    /// Removes a cell (read-once consumption or inline-terminal cleanup).
    func removeCell(sessionKey: String, cellId: String) {
        guard var state = sessions[sessionKey],
              let index = state.cells.firstIndex(where: { $0.cellId == cellId }) else { return }
        state.cells.remove(at: index)
        sessions[sessionKey] = state
        persist(state, sessionKey: sessionKey)
    }

    /// wait's three paths:
    /// - cell missing → `.notFound` (structured error, never silent).
    /// - `terminate: true` on a Running cell → mark Terminated (abandon:
    ///   the runaway script cannot be force-killed by JavaScriptCore, it
    ///   keeps running until it ends by itself; its result is discarded).
    ///   M6: every OTHER waiter on the same cell is woken with the terminal
    ///   record too (they must not block until their own timeout).
    /// - Running + wait → block until the cell reaches a terminal state,
    ///   `timeoutMs` elapses (`.stillRunning` keeps the cell), or the
    ///   calling task is cancelled (`.cancelled`, M6 — release promptly
    ///   instead of holding until timeout, same intent as wait_agent's
    ///   50 ms cancellation observation).
    /// A terminal cell is consumed by this call (read-once).
    func wait(cellId: String, sessionKey: String, timeoutMs: Int, terminate: Bool) async -> IOSJsCellWaitOutcome {
        let state = sessionState(sessionKey)
        guard let index = state.cells.firstIndex(where: { $0.cellId == cellId }) else {
            return .notFound
        }
        var record = state.cells[index]
        if record.status == .running, terminate {
            record.status = .terminated
            var updated = state
            updated.cells[index] = record
            sessions[sessionKey] = updated
            persist(updated, sessionKey: sessionKey)
            // M6: 同 cell 的其他 waiter 一起收口为 terminated 终态（不再阻塞到
            // 各自超时）；终止调用者自身读一次后清除 cell。
            if let waiters = waitersByCell[cellId], !waiters.isEmpty {
                waitersByCell[cellId] = nil
                for waiter in waiters {
                    waiter.1.resume(returning: .terminal(record))
                }
            }
            removeCell(sessionKey: sessionKey, cellId: cellId)
            return .terminal(record)
        }
        guard record.status == .running else {
            removeCell(sessionKey: sessionKey, cellId: cellId)
            return .terminal(record)
        }
        // M6: withTaskCancellationHandler 释放 waiter——取消立即收口，不等
        // 超时（wait_agent 先例的同一意图；比 50ms 轮询更及时且无轮询开销）。
        let waiterID = UUID()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<IOSJsCellWaitOutcome, Never>) in
                // 注册前已取消（onCancel 可能已先行触发）：立即收口，防悬挂。
                if Task.isCancelled {
                    continuation.resume(returning: .cancelled)
                    return
                }
                waitersByCell[cellId, default: []].append((waiterID, continuation))
                let sleepNs = UInt64(max(timeoutMs, 1)) * 1_000_000
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: sleepNs)
                    await self?.resolveWaitTimeout(cellId: cellId, sessionKey: sessionKey, waiterID: waiterID)
                }
            }
        } onCancel: {
            Task { [weak self] in
                await self?.resolveWaitCancelled(cellId: cellId, sessionKey: sessionKey, waiterID: waiterID)
            }
        }
    }

    /// M6: run 取消 → waiter 尽快收口为 `.cancelled`（幂等：waiter 已不在表
    /// 时 no-op——超时或终态路径先到则取消观察不生效）。
    private func resolveWaitCancelled(cellId: String, sessionKey: String, waiterID: UUID) {
        guard var list = waitersByCell[cellId],
              let index = list.firstIndex(where: { $0.0 == waiterID }) else { return }
        let continuation = list[index].1
        list.remove(at: index)
        waitersByCell[cellId] = list.isEmpty ? nil : list
        continuation.resume(returning: .cancelled)
    }

    private func resolveWaitTimeout(cellId: String, sessionKey: String, waiterID: UUID) {
        guard var list = waitersByCell[cellId],
              let index = list.firstIndex(where: { $0.0 == waiterID }) else { return }
        let continuation = list[index].1
        list.remove(at: index)
        waitersByCell[cellId] = list.isEmpty ? nil : list
        // The cell is still Running here (a terminal transition would have
        // resolved this waiter already — the actor serializes both paths).
        let record = sessions[sessionKey]?.cells.first(where: { $0.cellId == cellId })
            ?? IOSJsCellRecord(cellId: cellId, status: .running, createdAtMs: 0, output: nil, logs: [], error: nil)
        continuation.resume(returning: .stillRunning(record))
    }

    /// Read-only diagnostic snapshot (also the tests' contract surface).
    func cells(sessionKey: String) -> [IOSJsCellRecord] {
        sessionState(sessionKey).cells
    }

    // MARK: - store/load KV

    /// Stores a JSON-encoded value, enforcing the per-key 64 KB and per-session
    /// 1 MB limits (UTF-8 bytes of the JSON text). Persists atomically.
    func storeValue(sessionKey: String, key: String, valueJSON: String) -> IOSJsStoreOutcome {
        guard !key.isEmpty else {
            return .overLimit(reason: "store key must not be empty")
        }
        let valueBytes = valueJSON.utf8.count
        guard valueBytes <= Self.maxStoreValueBytes else {
            return .overLimit(reason: "store value exceeds the 64 KB per-key limit (\(valueBytes) bytes)")
        }
        var state = sessionState(sessionKey)
        let otherBytes = state.store.reduce(0) { total, entry in
            total + (entry.key == key ? 0 : entry.value.utf8.count)
        }
        guard otherBytes + valueBytes <= Self.maxStoreTotalBytes else {
            return .overLimit(reason: "session store exceeds the 1 MB total limit")
        }
        state.store[key] = valueJSON
        sessions[sessionKey] = state
        persist(state, sessionKey: sessionKey)
        return .stored
    }

    /// Loads a stored JSON-encoded value; nil when absent (JS sees undefined).
    func loadValue(sessionKey: String, key: String) -> String? {
        sessionState(sessionKey).store[key]
    }

    // MARK: - Persistence

    private func sessionFileURL(_ sessionKey: String) -> URL {
        directory.appendingPathComponent(
            IOSChatBackgroundJobFileNaming.sanitized(sessionKey) + ".json"
        )
    }

    /// First touch of a session loads the sidecar and sweeps it (Running →
    /// interrupted). In-memory state is authoritative afterwards.
    private func sessionState(_ sessionKey: String) -> IOSJsSessionState {
        if let state = sessions[sessionKey] { return state }
        let loaded = loadFromDisk(sessionKey)
        sessions[sessionKey] = loaded
        return loaded
    }

    private func loadFromDisk(_ sessionKey: String) -> IOSJsSessionState {
        let url = sessionFileURL(sessionKey)
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(IOSJsSessionState.self, from: data) else {
            return IOSJsSessionState()
        }
        // Cold-start sweep: a Running cell belongs to a process that died.
        // Mark it interrupted (never fake completion) and persist the swept
        // state so the marker is durable.
        var swept = decoded
        var changed = false
        for index in swept.cells.indices where swept.cells[index].status == .running {
            swept.cells[index].status = .interrupted
            changed = true
        }
        if changed {
            persist(swept, sessionKey: sessionKey)
        }
        return swept
    }

    /// Atomic write of the whole session sidecar; an empty session removes
    /// the file (zero trace, mirroring the steer-queue sidecar precedent).
    private func persist(_ state: IOSJsSessionState, sessionKey: String) {
        let url = sessionFileURL(sessionKey)
        if state.cells.isEmpty && state.store.isEmpty {
            try? FileManager.default.removeItem(at: url)
            return
        }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(state)
            try data.write(to: url, options: .atomic)
        } catch {
            // Persistence failure must not corrupt the in-memory truth; the
            // next mutation retries. A lost sidecar write only degrades
            // cold-start fidelity (cells sweep as interrupted at worst).
        }
    }
}
