import Foundation
import JavaScriptCore

/// P3-b: one evaluation's nested-tools bridge. `tools.{name}` functions are
/// injected for `availableToolNames` (the whitelist = the current round's
/// visible tool set minus the exec exclusions) — every other name is
/// `undefined` on the JS side, and calling it is a plain JS TypeError.
/// P3-d: the same whitelist + `toolDescriptions` also feed the frozen
/// `ALL_TOOLS` discovery global (`[{name, description}]`), so discovery
/// metadata and callability are one source and can never disagree.
///
/// The bridge is SYNCHRONOUS by JSC's nature: a native block runs inline on
/// the evaluation thread with no event-loop pump, so `tools.x(args)` blocks
/// the JS thread until the host execution finishes (no await/Promise needed;
/// concurrent `Promise.all` across tools is not supported in v1 — sequential
/// calls only). The host execution runs off the JS queue (on the MainActor),
/// so the block must never deadlock the JS thread against a MainActor that
/// is itself waiting on the JS queue — see `runEvaluation`'s sync bridge.
struct IOSJsSandboxTools: Sendable {
    let availableToolNames: [String]

    /// Executes one whitelisted nested tool call on the host. Receives the
    /// arguments as a JSON string; returns the tool output text (usually a
    /// JSON payload), or nil when the host cannot execute the call right now
    /// (e.g. no nested runner in this execution context) — the JS side then
    /// sees `Error("tool not available in exec")`.
    let hostCall: @MainActor (String, String) async -> String?

    /// P3-d: per-tool discovery descriptions for the `ALL_TOOLS` global, keyed
    /// by whitelisted name. Defaults to empty (name-only bridges still get
    /// ALL_TOOLS entries with an empty description). The engine derives
    /// ALL_TOOLS strictly from `availableToolNames` + this dictionary, so the
    /// discovery metadata can never disagree with what is callable.
    let toolDescriptions: [String: String]

    init(
        availableToolNames: [String],
        hostCall: @escaping @MainActor (String, String) async -> String?,
        toolDescriptions: [String: String] = [:]
    ) {
        self.availableToolNames = availableToolNames
        self.hostCall = hostCall
        self.toolDescriptions = toolDescriptions
    }
}

/// P3-c: session-scoped `store`/`load` bridge, installed as JS globals on
/// every cell evaluation. Values cross the boundary as JSON-encoded strings;
/// `store` returns nil on success or an error message (the JS side throws it).
struct IOSJsSandboxStore: Sendable {
    let load: @Sendable (String) async -> String?
    let store: @Sendable (String, String) async -> String?
}

/// P3-c: per-evaluation availability gate for the nested `tools` bridge.
/// The engine closes it the moment the caller abandons the evaluation
/// (timeout yield or run cancellation); every subsequent `tools.*` call in
/// the still-running script then fails with an honest "cell yielded" error
/// instead of dispatching to a host execution context that is no longer
/// valid for this run.
final class IOSJsNestedToolsGate: @unchecked Sendable {
    private let lock = NSLock()
    private var open = true

    var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return open
    }

    func close() {
        lock.lock()
        open = false
        lock.unlock()
    }
}

/// P3-a: result of one `exec` sandbox evaluation.
///
/// - `success`: the last expression's value JSON-ified (`result`) plus the
///   captured console output (`logs`, each entry prefixed `[LOG]`/`[INFO]`/
///   `[WARN]`/`[ERROR]` to mirror Android `eval_javascript`'s `setConsole`).
/// - `failure`: the script threw / the host refused the script (never crashes).
/// - `timedOut`: the caller-side watchdog fired; the runaway script keeps
///   running on its own queue until it naturally finishes (see the engine's
///   abandon semantics below) and its context is never reused.
enum IOSJsSandboxResult: Sendable, Equatable {
    case success(result: String, logs: [String])
    case failure(message: String)
    case timedOut(timeoutMs: Int)
}

/// P3-a: JavaScriptCore sandbox engine for the `exec` tool (pure evaluation,
/// no tools bridge — that is P3-b).
///
/// Safety/threading contract (JSC contexts are NOT thread-safe):
/// - Every evaluation creates a fresh `JSVirtualMachine` + `JSContext` on a
///   dedicated serial `DispatchQueue`, evaluates there, and releases both on
///   that same queue. A context is never shared or reused.
/// - Each evaluation gets its OWN queue, so a runaway script only blocks its
///   own thread; subsequent evaluations keep working (the CPU burn of a
///   runaway continues until the script naturally ends — see timeout below).
/// - Only a minimal `console` is injected. `require`/`process`/`fetch`/fs are
///   never defined, so `typeof` reports `undefined` (verified by contract
///   tests). No network, no DOM, no module imports.
///
/// Timeout = ABANDON semantics. JavaScriptCore has no public terminate
/// API, so a script that runs past `timeout_ms` cannot be force-killed:
/// the caller-side watchdog fires, the caller receives `.timedOut` and the
/// evaluation's (eventual) result is discarded — the context is dropped and
/// never reused. The runaway JS keeps consuming CPU on its own background
/// thread until it finishes by itself. Accepted for v1 (P3-a); device-level
/// verification of JSC termination limits is tracked as a real-device risk
/// (AGENT_ORCHESTRATION_ADOPTION_PLAN P3.5).
final class IOSJsSandboxEngine: @unchecked Sendable {

    static let defaultTimeoutMs = 10000
    static let defaultMaxOutputChars = 10000

    /// timeout_ms clamp (declaration contract: [1000, 30000], default 10000).
    static func clampTimeoutMs(_ value: Int) -> Int {
        min(max(value, 1000), 30000)
    }

    /// P3-c: evaluates `code`. The returned value is the FIRST outcome the
    /// caller sees (success / failure / timedOut / cancelled). `completion`,
    /// when provided, is invoked EXACTLY ONCE with the evaluation's FINAL
    /// result — including after a timeout yield or a mid-run cancellation,
    /// where the caller already got `.timedOut`/failure while the script kept
    /// running on its own queue (abandon semantics). `completion` never fires
    /// while the script is still running, so a cell can capture the eventual
    /// output even though the caller has moved on.
    ///
    /// `store`, when provided, installs the session-scoped `store`/`load` JS
    /// globals. Both are handed to the cell registry on every exec call (P3-c).
    func evaluate(
        code: String,
        timeoutMs: Int = IOSJsSandboxEngine.defaultTimeoutMs,
        maxOutputChars: Int = IOSJsSandboxEngine.defaultMaxOutputChars,
        tools: IOSJsSandboxTools? = nil,
        store: IOSJsSandboxStore? = nil,
        completion: (@Sendable (IOSJsSandboxResult) -> Void)? = nil
    ) async -> IOSJsSandboxResult {
        let safeTimeoutMs = max(timeoutMs, 1)
        let box = IOSJsEvalBox(completion: completion)
        // One gate per evaluation: the nested-tools bridge stays open while
        // the script runs in its first (non-abandoned) window, and closes on
        // the timeout/cancel abandon so post-yield `tools.*` calls fail
        // honestly instead of reaching a stale run's host execution.
        let gate = IOSJsNestedToolsGate()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                box.register(continuation)
                box.startWatchdog(timeoutMs: safeTimeoutMs) {
                    gate.close()
                }
                // Dedicated serial queue per evaluation: a runaway script only
                // poisons its own queue/thread; the next evaluation starts a
                // fresh queue (and fresh VM/context) and keeps working.
                let queue = DispatchQueue(
                    label: "app.amber.ios.jssandbox.\(UUID().uuidString)",
                    qos: .userInitiated
                )
                // A cancel that landed before the queue starts must not run the
                // script at all (the box dedups with the onCancel handler).
                if Task.isCancelled {
                    gate.close()
                    box.finish(.failure(message: "exec evaluation cancelled"), evaluationFinished: true)
                } else {
                    queue.async {
                        let result = Self.runEvaluation(code: code, tools: tools, store: store, gate: gate)
                        box.finish(result, evaluationFinished: true)
                    }
                }
            }
        } onCancel: {
            // The calling run was cancelled (e.g. the user stopped generation):
            // abandon the evaluation the same way as a timeout — the caller gets
            // a prompt failure, the JS keeps running on its own queue, and the
            // nested bridge closes so its post-cancel `tools.*` calls fail
            // honestly. The completion listener still receives the final result.
            gate.close()
            box.finish(.failure(message: "exec evaluation cancelled"), evaluationFinished: false)
        }
    }

    /// Serializes a sandbox result into the tool-output payload JSON and
    /// applies `max_output_chars` truncation (plain prefix cut; default
    /// 10000). Never throws.
    static func toolPayload(_ result: IOSJsSandboxResult, maxOutputChars: Int) -> String {
        let object: [String: Any]
        switch result {
        case .success(let value, let logs):
            object = ["result": value, "logs": logs]
        case .failure(let message):
            object = ["error": message]
        case .timedOut(let ms):
            object = ["error": "exec timed out after \(ms) ms"]
        }
        let payload = (try? JSONSerialization.data(withJSONObject: object))
            .map { String(data: $0, encoding: .utf8) ?? "" }
            ?? #"{"error":"exec payload serialization failed"}"#
        let limit = max(maxOutputChars, 1)
        guard payload.count > limit else { return payload }
        return String(payload.prefix(limit))
    }

    // MARK: - Evaluation (runs on the evaluation's own serial queue)

    private static func runEvaluation(
        code: String,
        tools: IOSJsSandboxTools? = nil,
        store: IOSJsSandboxStore? = nil,
        gate: IOSJsNestedToolsGate? = nil
    ) -> IOSJsSandboxResult {
        let virtualMachine = JSVirtualMachine()
        let context = JSContext(virtualMachine: virtualMachine)
        guard let context else {
            return .failure(message: "could not create JavaScriptCore context")
        }

        let logs = IOSJsLogCollector()

        // Native sink for the JS console shim: `__amberConsoleSink(level, text)`.
        // Runs synchronously on this queue while evaluateScript executes.
        let sink: @convention(block) (JSValue, JSValue) -> Void = { levelValue, textValue in
            let level = levelValue.toString() ?? "LOG"
            let text = textValue.toString() ?? ""
            logs.append("[\(level)] \(text)")
        }
        context.setObject(sink, forKeyedSubscript: "__amberConsoleSink" as NSString)

        // Install a minimal console (log/info/warn/error) as a JS shim so
        // multi-argument calls join like Android's QuickJS console. The sink is
        // captured into the shim's closure before the global is removed. The
        // pristine JSON.stringify is captured too — user code that overwrites
        // the global later must not tamper with console formatting or the
        // result serialization below.
        context.evaluateScript("""
        (function () {
          const sink = globalThis.__amberConsoleSink;
          const stringify = JSON.stringify;
          const join = (args) => Array.from(args)
            .map((a) => (a !== null && typeof a === 'object') ? stringify(a) : String(a))
            .join(' ');
          const make = (level) => function () { sink(level, join(arguments)); };
          globalThis.console = { log: make('LOG'), info: make('INFO'), warn: make('WARN'), error: make('ERROR') };
        })();
        delete globalThis.__amberConsoleSink;
        """)

        // P3-b: nested tools bridge. Injected after the console shim (both use
        // the same pristine-stringify discipline) and before the user script.
        // The whitelist is a plain JS array; only whitelisted names get a
        // function on `tools`. Each function serializes its argument with the
        // pristine stringify, calls the native sync bridge, and hands the host
        // output text back to JS as a parsed object when it is valid JSON and
        // as a plain string otherwise. The per-evaluation gate closes on
        // abandon (P3-c): post-yield nested calls fail honestly.
        if let tools {
            installNestedTools(tools, gate: gate, into: context)
        }

        // P3-c: session-scoped store/load globals (JSON-serializable values,
        // shared by all cells of the conversation, persisted by the registry).
        if let store {
            installStoreGlobals(store, into: context)
        }

        // A shim failure must not be mistaken for the user script's exception.
        context.exception = nil

        let pristineStringify = context.globalObject
            .objectForKeyedSubscript("JSON")?
            .objectForKeyedSubscript("stringify")
        let value = context.evaluateScript(code)
        if let exception = context.exception {
            return .failure(message: exception.toString() ?? "unknown script error")
        }
        return .success(result: Self.jsonify(value, stringify: pristineStringify), logs: logs.snapshot())
    }

    /// P3-b: installs the `tools` global for one evaluation.
    ///
    /// Deadlock argument for the synchronous bridge (each queue is a thread):
    /// - The native block `__amberNestedToolCall` runs inline on THIS
    ///   evaluation's serial queue while evaluateScript executes.
    /// - The block hands the host execution to the MainActor
    ///   (`Task { @MainActor in ... }`) and blocks this JS thread on a
    ///   semaphore until the host finishes.
    /// - The MainActor never blocks on the JS queue: the caller of
    ///   `evaluate` is suspended at an await while the evaluation runs, and
    ///   the evaluation's own continuation is resumed from this queue AFTER
    ///   the script (and every nested call) finished. So the host call always
    ///   gets to run, and the semaphore always gets signaled.
    /// - Timeout/cancel keep the P3-a abandon semantics: the caller receives
    ///   `.timedOut`/failure while the JS thread stays blocked in the nested
    ///   call until the host call completes (nested tool time counts into the
    ///   cell's total timeout, since the watchdog covers the whole
    ///   evaluation). The context is dropped and never reused.
    private static func installNestedTools(
        _ tools: IOSJsSandboxTools,
        gate: IOSJsNestedToolsGate?,
        into context: JSContext
    ) {
        context.setObject(
            tools.availableToolNames as NSArray,
            forKeyedSubscript: "__amberToolsWhitelist" as NSString
        )
        context.setObject(
            tools.toolDescriptions as NSDictionary,
            forKeyedSubscript: "__amberToolDescriptions" as NSString
        )
        let nestedCall: @convention(block) (JSValue, JSValue) -> JSValue = { nameValue, argsJSONValue in
            let name = nameValue.toString() ?? ""
            let argumentsJSON = argsJSONValue.toString() ?? "{}"
            // P3-c: the caller abandoned this evaluation (timeout yield or
            // cancel) — the script keeps running but its host execution
            // context is no longer valid, so nested calls fail honestly.
            guard gate?.isOpen ?? true else {
                context.exception = context.objectForKeyedSubscript("Error")
                    .call(withArguments: ["tool not available in exec: nested tools unavailable after the exec call yielded or was abandoned"])
                return JSValue(undefinedIn: context)
            }
            guard tools.availableToolNames.contains(name) else {
                // Defense in depth: functions are only installed for whitelisted
                // names, so this should be unreachable from JS.
                context.exception = context.objectForKeyedSubscript("Error")
                    .call(withArguments: ["tool not available in exec: \(name)"])
                return JSValue(undefinedIn: context)
            }
            // Synchronous bridge: run the host execution on the MainActor and
            // block THIS JS thread until it completes (see the deadlock
            // argument above). The JS thread is never the MainActor and the
            // MainActor never waits on it.
            let semaphore = DispatchSemaphore(value: 0)
            let resultBox = IOSJsNestedToolResultBox()
            Task { @MainActor in
                let output = await tools.hostCall(name, argumentsJSON)
                resultBox.store(output)
                semaphore.signal()
            }
            let output = resultBox.waitForValue(semaphore: semaphore)
            if let output {
                return JSValue(object: output, in: context)
            }
            context.exception = context.objectForKeyedSubscript("Error")
                .call(withArguments: ["tool not available in exec: \(name)"])
            return JSValue(undefinedIn: context)
        }
        context.setObject(nestedCall, forKeyedSubscript: "__amberNestedToolCall" as NSString)

        context.evaluateScript("""
        (function () {
          const whitelist = globalThis.__amberToolsWhitelist;
          const stringify = JSON.stringify;
          const callNative = globalThis.__amberNestedToolCall;
          const toolsObj = {};
          for (let i = 0; i < whitelist.length; i++) {
            const name = whitelist[i];
            toolsObj[name] = function (args) {
              const argJSON = stringify(args === undefined ? null : args);
              const raw = callNative(name, argJSON);
              try { return JSON.parse(raw); } catch (e) { return raw; }
            };
          }
          globalThis.tools = toolsObj;
          // P3-d: ALL_TOOLS discovery metadata, derived from the SAME whitelist
          // array as `tools` (callability and discoverability can never
          // disagree), with each tool's round declaration description. Frozen
          // (array + entries) and made non-writable/non-configurable so a
          // script cannot tamper with its own view and mislead itself or a
          // later cell about what is callable.
          const descriptions = globalThis.__amberToolDescriptions || {};
          const entries = [];
          for (let i = 0; i < whitelist.length; i++) {
            entries.push(Object.freeze({
              name: whitelist[i],
              description: descriptions[whitelist[i]] || ''
            }));
          }
          Object.defineProperty(globalThis, 'ALL_TOOLS', {
            value: Object.freeze(entries),
            writable: false,
            configurable: false,
            enumerable: true
          });
        })();
        delete globalThis.__amberNestedToolCall;
        delete globalThis.__amberToolsWhitelist;
        delete globalThis.__amberToolDescriptions;
        """)
    }

    /// P3-c: installs the session-scoped `store(key, value)` / `load(key)`
    /// globals. Values are JSON-serializable; the shim serializes with the
    /// pristine JSON.stringify and hands the JSON text to the native sync
    /// bridge (same semaphore pattern as the nested-tools bridge; the registry
    /// actor never blocks on the JS queue, so there is no deadlock). `load` of
    /// a missing key returns `undefined`; a non-JSON-serializable value or a
    /// capacity overflow surfaces as a thrown JS Error.
    private static func installStoreGlobals(
        _ store: IOSJsSandboxStore,
        into context: JSContext
    ) {
        let storeCall: @convention(block) (JSValue, JSValue) -> JSValue = { keyValue, jsonValue in
            let key = keyValue.toString() ?? ""
            let json = jsonValue.toString() ?? ""
            let semaphore = DispatchSemaphore(value: 0)
            let resultBox = IOSJsNestedToolResultBox()
            Task { [store] in
                let error = await store.store(key, json)
                resultBox.store(error)
                semaphore.signal()
            }
            let error = resultBox.waitForValue(semaphore: semaphore)
            if let error {
                return JSValue(object: error, in: context)
            }
            return JSValue(nullIn: context)
        }
        let loadCall: @convention(block) (JSValue) -> JSValue = { keyValue in
            let key = keyValue.toString() ?? ""
            let semaphore = DispatchSemaphore(value: 0)
            let resultBox = IOSJsNestedToolResultBox()
            Task { [store] in
                let value = await store.load(key)
                resultBox.store(value)
                semaphore.signal()
            }
            let value = resultBox.waitForValue(semaphore: semaphore)
            if let value {
                return JSValue(object: value, in: context)
            }
            return JSValue(undefinedIn: context)
        }
        context.setObject(storeCall, forKeyedSubscript: "__amberStoreCall" as NSString)
        context.setObject(loadCall, forKeyedSubscript: "__amberLoadCall" as NSString)

        context.evaluateScript("""
        (function () {
          const stringify = JSON.stringify;
          const callStore = globalThis.__amberStoreCall;
          const callLoad = globalThis.__amberLoadCall;
          globalThis.store = function (key, value) {
            if (typeof key !== 'string') { throw new Error('store: key must be a string'); }
            const json = stringify(value);
            if (typeof json !== 'string') { throw new Error('store: value must be JSON-serializable'); }
            const error = callStore(key, json);
            if (error) { throw new Error('store: ' + error); }
          };
          globalThis.load = function (key) {
            const raw = callLoad(key);
            if (raw === undefined || raw === null) { return undefined; }
            try { return JSON.parse(raw); } catch (e) { return undefined; }
          };
        })();
        delete globalThis.__amberStoreCall;
        delete globalThis.__amberLoadCall;
        """)
    }

    private static func jsonify(_ value: JSValue?, stringify: JSValue?) -> String {
        // Mirror Android's null mapping: undefined/null become JSON `null`.
        guard let value else { return "null" }
        if value.isUndefined || value.isNull { return "null" }
        guard let stringify, !stringify.isUndefined, !stringify.isNull,
              let stringified = stringify.call(withArguments: [value]) else {
            return "null"
        }
        if stringified.isUndefined || stringified.isNull { return "null" }
        return stringified.toString() ?? "null"
    }
}

/// Console capture buffer for one evaluation. The JS sink block may only be
/// invoked on the evaluation's own queue, but strict concurrency requires the
/// captured value to be Sendable — the lock makes it safe regardless.
private final class IOSJsLogCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var entries: [String] = []

    func append(_ entry: String) {
        lock.lock()
        entries.append(entry)
        lock.unlock()
    }

    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }
}

/// P3-b: thread-safe result box for one synchronous nested-tool call. The JS
/// thread waits on the semaphore while the host executes on the MainActor;
/// `store` happens-before `signal`, and `waitForValue` reads under the same
/// lock, so the value is always observed fully written.
private final class IOSJsNestedToolResultBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?

    func store(_ value: String?) {
        lock.lock()
        self.value = value
        lock.unlock()
    }

    /// Blocks the calling (JS) thread until `store` has been called.
    func waitForValue(semaphore: DispatchSemaphore) -> String? {
        semaphore.wait()
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

/// Thread-safe box that mediates between the evaluation completion, the
/// caller-side watchdog and task cancellation — exactly one outcome is ever
/// delivered to the caller, and (P3-c) exactly one final-result delivery goes
/// to the completion listener.
private final class IOSJsEvalBox: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<IOSJsSandboxResult, Never>?
    private var delivered: IOSJsSandboxResult?
    private var completed = false
    private var completion: (@Sendable (IOSJsSandboxResult) -> Void)?
    private var completionDelivered = false
    private var watchdogTask: Task<Void, Never>?

    init(completion: (@Sendable (IOSJsSandboxResult) -> Void)?) {
        self.completion = completion
    }

    func register(_ continuation: CheckedContinuation<IOSJsSandboxResult, Never>) {
        lock.lock()
        if let delivered {
            lock.unlock()
            continuation.resume(returning: delivered)
            return
        }
        self.continuation = continuation
        lock.unlock()
    }

    func startWatchdog(timeoutMs: Int, onAbandon: @escaping @Sendable () -> Void) {
        let task = Task.detached { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
            onAbandon()
            self?.finish(.timedOut(timeoutMs: timeoutMs), evaluationFinished: false)
        }
        lock.lock()
        if completed {
            lock.unlock()
            task.cancel()
        } else {
            watchdogTask = task
            lock.unlock()
        }
    }

    /// Delivers `result` to the caller (first call wins; later calls are
    /// deduplicated — the timeout/cancel paths race with the natural end).
    /// `evaluationFinished: true` additionally fires the completion listener
    /// exactly once, even when the caller already received an early
    /// `.timedOut`/cancel outcome (the script kept running on its own queue).
    func finish(_ result: IOSJsSandboxResult, evaluationFinished: Bool) {
        var resume: CheckedContinuation<IOSJsSandboxResult, Never>?
        var cancelWatchdog: Task<Void, Never>?
        var storeDelivered: IOSJsSandboxResult?
        var completionResume: (@Sendable (IOSJsSandboxResult) -> Void)?
        var completionResult: IOSJsSandboxResult?
        lock.lock()
        if !completed {
            completed = true
            resume = continuation
            continuation = nil
            cancelWatchdog = watchdogTask
            watchdogTask = nil
            if resume == nil {
                // Cancellation raced ahead of registration: deliver on register().
                storeDelivered = result
            }
        }
        if evaluationFinished && !completionDelivered {
            completionDelivered = true
            completionResume = completion
            completion = nil
            completionResult = result
        }
        lock.unlock()
        cancelWatchdog?.cancel()
        if let completionResume, let completionResult {
            completionResume(completionResult)
        }
        if let resume {
            resume.resume(returning: result)
        } else if let storeDelivered {
            lock.lock()
            delivered = result
            lock.unlock()
        }
    }
}
