import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P3-a: iOS JavaScriptCore 沙箱引擎（IOSJsSandboxEngine）与 `exec` 工具接线
/// 契约。覆盖：求值/JSON 结果/console 捕获/异常收口、无宿主见对象、
/// 超时 abandon 语义（后续求值仍工作）、输出截断、设置开关零痕迹与
/// deferred 池发现、前台/后台完整 tool runtime 分发。
@MainActor
final class IOSJsSandboxEngineTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "IOSJsSandboxEngineTests-\(UUID().uuidString)")!
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "js-sandbox-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeParams(tools: [Tool] = []) -> TextGenerationParams {
        let model = Model(
            modelId: "js-sandbox-test-model",
            displayName: "js-sandbox-test-model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        return TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: tools,
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeMessage(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 19, hour: 0, minute: 0, second: 0, nanosecond: 0),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func execToolCall(id: String = "tc-exec", input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: id,
            toolName: "exec",
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func runtime(execEnabled: Bool) -> ChatToolRuntime {
        let defaults = isolatedDefaults()
        let settingsStore = SettingsStore(userDefaults: defaults)
        settingsStore.execJavaScriptEnabled = execEnabled
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        return ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
        )
    }

    private func toolOutputText(_ messages: [UIMessage]) -> String {
        messages.flatMap { $0.parts.compactMap { ($0 as? UIMessagePart.Tool)?.output.compactMap { ($0 as? UIMessagePart.Text)?.text } } }
            .flatMap { $0 }
            .joined()
    }

    // MARK: - Evaluation

    func testArithmeticReturnsLastExpressionValue() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(code: "1 + 2", timeoutMs: 5000, maxOutputChars: 10000)
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, "3")
        XCTAssertTrue(output.logs.isEmpty)
    }

    func testJSONObjectResultIsSerialized() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(code: #"const x = {a: 1, b: [2, 3]}; x"#, timeoutMs: 5000, maxOutputChars: 10000)
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        // JSON.stringify ordering is insertion order — the object literal keeps a then b.
        XCTAssertEqual(output.result, #"{"a":1,"b":[2,3]}"#)
    }

    func testOverriddenJsonStringifyDoesNotTamperWithResultSerialization() async {
        // 复核修复：用户脚本覆盖全局 JSON.stringify 后，结果序列化必须用求值前
        // 捕获的原始引用，不能被篡改。
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"JSON.stringify = () => "PWNED"; ({a: 1})"#,
            timeoutMs: 5000, maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #"{"a":1}"#)
    }

    func testConsoleObjectFormattingUsesPristineStringify() async {
        // 同上：console shim 的对象格式化也须在安装时捕获原始 stringify。
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"JSON.stringify = () => "PWNED"; console.log({a: 2}); null"#,
            timeoutMs: 5000, maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.logs, [#"[LOG] {"a":2}"#])
    }

    func testMultiLineScriptLastExpressionWins() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: "const a = 5;\nconst b = 7;\na * b",
            timeoutMs: 5000,
            maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, "35")
    }

    func testUndefinedCompletionValueMapsToNull() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(code: "const x = 1", timeoutMs: 5000, maxOutputChars: 10000)
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, "null")
    }

    // MARK: - Console capture (Android setConsole parity)

    func testConsoleCaptureIncludesLevelPrefixedEntries() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"console.log("plain", 42); console.info("note"); console.warn("careful"); console.error("boom")"#,
            timeoutMs: 5000,
            maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.logs, [
            "[LOG] plain 42",
            "[INFO] note",
            "[WARN] careful",
            "[ERROR] boom",
        ])
    }

    // MARK: - Exception handling (never crashes)

    func testScriptExceptionReturnsError() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(code: #"throw new Error("boom")"#, timeoutMs: 5000, maxOutputChars: 10000)
        guard case .failure(let message) = result else {
            return XCTFail("expected failure, got \(result)")
        }
        XCTAssertTrue(message.contains("boom"), "error must carry the thrown message: \(message)")
    }

    func testSyntaxErrorReturnsError() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(code: "1 +", timeoutMs: 5000, maxOutputChars: 10000)
        guard case .failure = result else {
            return XCTFail("expected failure, got \(result)")
        }
    }

    // MARK: - Host invisibility (typeof contract)

    func testHostGlobalsRequireProcessFetchAreUndefined() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"typeof require + '|' + typeof process + '|' + typeof fetch"#,
            timeoutMs: 5000,
            maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #""undefined|undefined|undefined""#)
    }

    func testConsoleIsTheOnlyInjectedHostSurface() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"typeof console + '|' + typeof setTimeout + '|' + typeof globalThis.fs"#,
            timeoutMs: 5000,
            maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #""object|undefined|undefined""#)
    }

    // MARK: - Timeout = abandon semantics

    func testInfiniteLoopTimesOutAndSubsequentEvaluationsStillWork() async {
        let engine = IOSJsSandboxEngine()
        let start = Date()
        let result = await engine.evaluate(code: "while (true) {}", timeoutMs: 300, maxOutputChars: 10000)
        let elapsed = Date().timeIntervalSince(start)
        guard case .timedOut(let ms) = result else {
            return XCTFail("expected timedOut, got \(result)")
        }
        XCTAssertEqual(ms, 300)
        XCTAssertGreaterThanOrEqual(elapsed, 0.2, "watchdog must not fire before the timeout window")
        XCTAssertLessThan(elapsed, 10, "caller must receive the timeout error promptly (no hang)")

        // The abandoned context is never reused; a fresh evaluation still works.
        let next = await engine.evaluate(code: "2 + 2", timeoutMs: 5000, maxOutputChars: 10000)
        guard case .success(let output) = next else {
            return XCTFail("subsequent evaluation must work after abandon: \(next)")
        }
        XCTAssertEqual(output.result, "4")
    }

    func testTimeoutMsClamp() {
        XCTAssertEqual(IOSJsSandboxEngine.clampTimeoutMs(500), 1000)
        XCTAssertEqual(IOSJsSandboxEngine.clampTimeoutMs(60000), 30000)
        XCTAssertEqual(IOSJsSandboxEngine.clampTimeoutMs(10000), 10000)
    }

    // MARK: - Output truncation

    func testMaxOutputCharsTruncatesPayload() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(code: #""x".repeat(200)"#, timeoutMs: 5000, maxOutputChars: 10000)
        let full = IOSJsSandboxEngine.toolPayload(result, maxOutputChars: 10000)
        XCTAssertTrue(full.count > 200, "full payload must exceed the small truncation limit")

        let truncated = IOSJsSandboxEngine.toolPayload(result, maxOutputChars: 40)
        XCTAssertLessThanOrEqual(truncated.count, 40)
        XCTAssertEqual(truncated, String(full.prefix(40)), "truncation must be a plain prefix cut")
    }

    func testToolPayloadShapes() async {
        let success = IOSJsSandboxEngine.toolPayload(.success(result: "3", logs: ["[LOG] hi"]), maxOutputChars: 10000)
        XCTAssertTrue(success.contains("\"result\":\"3\""))
        XCTAssertTrue(success.contains("\"[LOG] hi\""))
        XCTAssertTrue(success.contains("\"logs\""))

        let failure = IOSJsSandboxEngine.toolPayload(.failure(message: "boom"), maxOutputChars: 10000)
        XCTAssertTrue(failure.contains("\"error\":\"boom\""))

        let timeout = IOSJsSandboxEngine.toolPayload(.timedOut(timeoutMs: 250), maxOutputChars: 10000)
        XCTAssertTrue(timeout.contains("timed out after 250 ms"))
    }

    // MARK: - Runtime gating (settings switch, zero-trace when off)

    func testExecCallNotRecognizedWhenSwitchOff() {
        let runtime = runtime(execEnabled: false)
        let messages = [makeMessage(role: .assistant, parts: [execToolCall(input: #"{"code":"1"}"#)])]
        XCTAssertNil(runtime.nextPendingToolCall(in: messages, availableToolNames: ["exec"]))
    }

    func testExecCallPickedUpAsAdvancedWhenSwitchOn() {
        let runtime = runtime(execEnabled: true)
        let messages = [makeMessage(role: .assistant, parts: [execToolCall(input: #"{"code":"1"}"#)])]
        let pending = runtime.nextPendingToolCall(in: messages, availableToolNames: ["exec"])
        XCTAssertEqual(pending?.kind, .advanced)
        XCTAssertEqual(pending?.toolCall.toolName, "exec")
    }

    // MARK: - Full foreground runtime dispatch

    func testExecToolCallDispatchesThroughForegroundRuntime() async {
        let runtime = runtime(execEnabled: true)
        let toolCall = execToolCall(input: #"{"code":"console.log('hi'); 6 * 7"}"#)
        let assistant = makeMessage(role: .assistant, parts: [toolCall])
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-exec-fg",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )
        guard case .completed(let messages) = result else {
            return XCTFail("exec must complete through the foreground runtime, got \(result)")
        }
        let outputText = toolOutputText(messages)
        XCTAssertTrue(outputText.contains("\"result\":\"42\""), "output must carry the evaluated result: \(outputText)")
        XCTAssertTrue(outputText.contains("[LOG] hi"), "output must carry console capture: \(outputText)")
    }

    func testExecInvalidArgumentsProduceStructuredFailure() async {
        let runtime = runtime(execEnabled: true)
        let toolCall = execToolCall(input: #"{"timeout_ms": 500}"#) // missing required code
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-exec-fg",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeMessage(role: .assistant, parts: [toolCall])]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )
        guard case .completed(let messages) = result else {
            return XCTFail("expected completed, got \(result)")
        }
        let outputText = toolOutputText(messages)
        XCTAssertTrue(outputText.contains("exec 参数无效"), "missing code must be rejected: \(outputText)")
    }

    // MARK: - Background executor registration

    func testExecRegisteredInBackgroundExecutorsAndExecutes() async throws {
        let runtime = runtime(execEnabled: true)
        let execDeclaration = try XCTUnwrap(ToolKt.iosToolDeclarations(names: ["exec"]).first)
        let params = makeParams(tools: [execDeclaration])
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "run-exec-bg"
        )
        let executor = try XCTUnwrap(executors["exec"], "exec must be registered when visible in params")
        let outcome = await UncheckedToolExecutorBox(executor).execute(
            name: "exec",
            arguments: #"{"code":"40 + 2"}"#,
            isUserInitiated: false
        )
        guard case .filled(let text) = outcome else {
            return XCTFail("background exec executor must fill, got \(outcome)")
        }
        XCTAssertTrue(text.contains("\"result\":\"42\""), "background output must carry the result: \(text)")
    }

    func testExecNotRegisteredInBackgroundExecutorsWhenParamsLackIt() async {
        let runtime = runtime(execEnabled: true)
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: makeParams(tools: []),
            runId: "run-exec-bg"
        )
        XCTAssertNil(executors["exec"], "exec must only register when the current round declares it")
    }

    // MARK: - P3-b: nested tools bridge (synchronous host calls)

    func testNestedSearchWebToolInvokesHostSynchronouslyAndReturnsObject() async {
        let engine = IOSJsSandboxEngine()
        let recorder = IOSJsSandboxToolCallRecorder()
        let result = await engine.evaluate(
            code: #"const r = tools.search_web({query: 'amber', limit: 3}); ({type: typeof r, title: r.title, count: r.count})"#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["search_web"],
                hostCall: { name, arguments in
                    recorder.record(name: name, arguments: arguments)
                    return #"{"title":"amber results","count":3}"#
                }
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        // The host was invoked exactly once, synchronously, with the JS object
        // serialized by the pristine JSON.stringify (insertion order).
        XCTAssertEqual(recorder.calls.count, 1)
        XCTAssertEqual(recorder.calls.first?.name, "search_web")
        XCTAssertEqual(recorder.calls.first?.arguments, #"{"query":"amber","limit":3}"#)
        // The host's JSON payload comes back as a JS object with readable fields.
        XCTAssertEqual(output.result, #"{"type":"object","title":"amber results","count":3}"#)
    }

    func testNestedToolHostPlainTextResultIsReturnedAsString() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"const r = tools.echo({text: 'hi'}); typeof r + '|' + r"#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["echo"],
                hostCall: { _, _ in "hello plain" }
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #""string|hello plain""#)
    }

    func testNestedToolsOutsideWhitelistAreUndefinedAndCallingFailsClearly() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"""
            const spawn = typeof tools.spawn_agent;
            const exec = typeof tools.exec;
            const search = typeof tools.search_web;
            let error = 'no-error';
            try { tools.spawn_agent({task_name: 'x'}) } catch (e) { error = String(e); }
            spawn + '|' + exec + '|' + search + '|' + error
            """#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["search_web"],
                hostCall: { _, _ in "{}" }
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        // Orchestration tools and exec itself are never injected (undefined),
        // while whitelisted names are functions.
        XCTAssertTrue(output.result.hasPrefix(#""undefined|undefined|function|"#),
                      "whitelist exposure contract broken: \(output.result)")
        // Calling a non-whitelisted name fails with a clear JS error that names it.
        XCTAssertTrue(output.result.contains("spawn_agent"),
                      "the error must name the offending tool: \(output.result)")
    }

    func testNestedWhitelistedToolWithoutHostRunnerThrowsUnavailable() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"try { tools.search_web({query: 'x'}) } catch (e) { 'caught:' + e.message }"#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["search_web"],
                hostCall: { _, _ in nil }
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #""caught:tool not available in exec: search_web""#)
    }

    func testNestedToolTimeCountsTowardCellTimeout() async {
        // P3-b v1 semantics: nested tool time is part of the cell's total
        // timeout (the watchdog covers the whole evaluation). The caller gets
        // .timedOut promptly; the slow host call keeps running to completion
        // on its own (abandon semantics, same as P3-a).
        let engine = IOSJsSandboxEngine()
        let start = Date()
        let result = await engine.evaluate(
            code: #"tools.slow_tool({wait: 1})"#,
            timeoutMs: 300,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["slow_tool"],
                hostCall: { _, _ in
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    return "{}"
                }
            )
        )
        let elapsed = Date().timeIntervalSince(start)
        guard case .timedOut(let ms) = result else {
            return XCTFail("expected timedOut, got \(result)")
        }
        XCTAssertEqual(ms, 300)
        XCTAssertLessThan(elapsed, 2.0, "caller must not wait for the slow nested host call")
    }

    func testMultipleSequentialNestedCallsDoNotDeadlock() async {
        let engine = IOSJsSandboxEngine()
        let recorder = IOSJsSandboxToolCallRecorder()
        let result = await engine.evaluate(
            code: #"tools.a({v: 1}).v + tools.b({v: 2}).v"#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["a", "b"],
                hostCall: { name, arguments in
                    recorder.record(name: name, arguments: arguments)
                    return "{\"v\": \(name == "a" ? 1 : 2)}"
                }
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(recorder.calls.map(\.name), ["a", "b"])
        XCTAssertEqual(output.result, "3")
    }

    // MARK: - P3-d: ALL_TOOLS discovery metadata

    func testAllToolsListsWhitelistEntriesWithDescriptions() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"""
            const names = ALL_TOOLS.map(t => t.name).join(',');
            const search = ALL_TOOLS.filter(t => t.name === 'search_web')[0];
            const memory = ALL_TOOLS.filter(t => t.name === 'memory_tool')[0];
            names + '|' + search.description + '|' + memory.description
            """#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["search_web", "memory_tool"],
                hostCall: { _, _ in "{}" },
                toolDescriptions: [
                    "search_web": "Search the web through AmberAgent search execution.",
                    "memory_tool": "Read and update AmberAgent memories.",
                ]
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        // ALL_TOOLS 与白名单同源：恰好覆盖可调用工具，每个条目携带 name+description。
        XCTAssertEqual(
            output.result,
            #""search_web,memory_tool|Search the web through AmberAgent search execution.|Read and update AmberAgent memories.""#,
            "ALL_TOOLS must carry exactly the whitelist with per-tool descriptions: \(output.result)"
        )
    }

    func testAllToolsIsFrozenAndCannotBeTamperedWith() async {
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"""
            const before = ALL_TOOLS.length;
            let pushError = 'no-error';
            try { ALL_TOOLS.push({name: 'evil'}); } catch (e) { pushError = 'thrown'; }
            ALL_TOOLS[0].name = 'evil';
            ALL_TOOLS = [{name: 'evil'}];
            (pushError === 'thrown' && before === ALL_TOOLS.length &&
             ALL_TOOLS[0].name === 'search_web' &&
             Object.isFrozen(ALL_TOOLS) && Object.isFrozen(ALL_TOOLS[0]) &&
             typeof ALL_TOOLS.map === 'function')
            """#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["search_web"],
                hostCall: { _, _ in "{}" },
                toolDescriptions: ["search_web": "Search the web."]
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        // 数组冻结（push 抛错）+ 条目冻结（name 不可改）+ 全局只读（重绑不生效）。
        XCTAssertEqual(output.result, "true", "ALL_TOOLS must be frozen end to end: \(output.result)")
    }

    func testFilterAllToolsByDescriptionAndCallFoundTool() async {
        let engine = IOSJsSandboxEngine()
        let recorder = IOSJsSandboxToolCallRecorder()
        let result = await engine.evaluate(
            code: #"""
            const found = ALL_TOOLS.filter(t => t.description.indexOf('the web through') !== -1)[0];
            const r = tools[found.name]({query: 'amber', limit: 3});
            ({name: found.name, title: r.title, count: r.count})
            """#,
            timeoutMs: 5000,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["search_web", "memory_tool"],
                hostCall: { name, arguments in
                    recorder.record(name: name, arguments: arguments)
                    return #"{"title":"amber results","count":3}"#
                },
                toolDescriptions: [
                    "search_web": "Search the web through AmberAgent search execution.",
                    "memory_tool": "Read and update AmberAgent memories.",
                ]
            )
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        // 按 description 过滤发现工具名 → 成功调用（发现元数据与可调用面一致）。
        XCTAssertEqual(recorder.calls.first?.name, "search_web")
        XCTAssertEqual(output.result, #"{"name":"search_web","title":"amber results","count":3}"#)
    }

    func testAllToolsNotInstalledWithoutToolsBridge() async {
        // P3-a 行为保留：没有 tools 桥时 ALL_TOOLS 也不注入（白名单缺失即无可发现面）。
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"typeof tools + '|' + typeof ALL_TOOLS"#,
            timeoutMs: 5000,
            maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #""undefined|undefined""#)
    }

    // MARK: - P3-d 安全审查：yield 后嵌套 gate 的诚实错误

    func testNestedCallsAfterTimeoutYieldFailHonestlyWithClosedGate() async {
        // P3-c abandon 语义：超时后调用方收到 .timedOut，脚本仍在自己的队列跑；
        // 第一个嵌套调用阻塞在宿主执行上，宿主返回后脚本继续——此时 gate 已关，
        // 后续嵌套调用必须诚实报错（不得派发到已失效的宿主上下文）。
        let engine = IOSJsSandboxEngine()
        let finalResult = IOSJsSandboxResultBox()
        let result = await engine.evaluate(
            code: #"tools.slow({wait: 1}); tools.again({v: 1})"#,
            timeoutMs: 300,
            maxOutputChars: 10000,
            tools: IOSJsSandboxTools(
                availableToolNames: ["slow", "again"],
                hostCall: { name, _ in
                    if name == "slow" {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                    }
                    return "{}"
                }
            ),
            completion: { final in
                finalResult.store(final)
            }
        )
        guard case .timedOut = result else {
            return XCTFail("expected timedOut first, got \(result)")
        }
        // 求值最终（宿主调用完成后）以失败收口，错误必须点名 gate 关闭原因——
        // 证明脚本没有悬挂在无限期阻塞里（其队列线程正常结束）。
        let final = await finalResult.waitForValue()
        guard case .failure(let message) = final else {
            return XCTFail("expected the abandoned evaluation to fail finally, got \(final)")
        }
        XCTAssertTrue(
            message.contains("nested tools unavailable after the exec call yielded or was abandoned"),
            "post-yield nested calls must fail with the honest gate-closed error: \(message)"
        )
    }

    // MARK: - P3-d 安全审查：切换开关后既有 run 的执行边界

    func testExecSwitchToggledOffBetweenClassificationAndExecutionIsDenied() async {
        // 声明与执行双重 gate：pendingAdvancedToolCall 分类时开关开 → 被收为
        // advanced；执行时开关已被用户关掉 → dispatchAdvancedToolCall 的执行级
        // gate 必须诚实拒绝（结构化 denied），不得静默执行陈旧轮次的调用。
        let defaults = isolatedDefaults()
        let settingsStore = SettingsStore(userDefaults: defaults)
        settingsStore.execJavaScriptEnabled = true
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let runtime = ChatToolRuntime(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
        )
        let toolCall = execToolCall(input: #"{"code":"1 + 1"}"#)
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-exec-toggle",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeMessage(role: .assistant, parts: [toolCall])]
        )
        let pending = runtime.nextPendingToolCall(in: context.baseMessages, availableToolNames: ["exec"])
        XCTAssertEqual(pending?.kind, .advanced, "classification gate is open while the switch is on")
        settingsStore.execJavaScriptEnabled = false
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )
        guard case .completed(let messages) = result else {
            return XCTFail("denial must complete in place, got \(result)")
        }
        let outputText = toolOutputText(messages)
        XCTAssertTrue(outputText.contains("\"denied\":true"), "execution must be denied, not executed: \(outputText)")
        XCTAssertTrue(outputText.contains("未开启"), "denial must say the capability is off: \(outputText)")
        XCTAssertFalse(outputText.contains("\"result\""), "a denied call must never evaluate JS: \(outputText)")
    }

    // MARK: - P3-d 安全审查：max_output_chars 硬上限

    func testMaxOutputCharsClamp() {
        XCTAssertEqual(ChatToolRuntime.clampMaxOutputChars(0), 1)
        XCTAssertEqual(ChatToolRuntime.clampMaxOutputChars(-5), 1)
        XCTAssertEqual(ChatToolRuntime.clampMaxOutputChars(100), 100)
        XCTAssertEqual(ChatToolRuntime.clampMaxOutputChars(10000), 10000)
        XCTAssertEqual(ChatToolRuntime.clampMaxOutputChars(1_000_000_000), 100000)
    }

    func testExecOutputPayloadIsClampedToHardCap() async {
        // 模型传超大 max_output_chars 不得绕过输出封顶：dispatch 边界 clamp 后
        // 截断生效（payload 进工具输出/账本前必须封顶）。
        let runtime = runtime(execEnabled: true)
        let toolCall = execToolCall(input: #"{"code":"'x'.repeat(200000)","max_output_chars":1000000000}"#)
        let context = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-exec-cap",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeMessage(role: .assistant, parts: [toolCall])]
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: context
        )
        guard case .completed(let messages) = result else {
            return XCTFail("expected completed, got \(result)")
        }
        let outputText = toolOutputText(messages)
        XCTAssertLessThanOrEqual(
            outputText.count, 100_000,
            "the output payload must be capped even when the model asks for an unbounded limit"
        )
    }

    func testNoToolsBridgeLeavesToolsUndefined() async {
        // P3-a behavior preserved: without a bridge, `tools` is not injected
        // at all (typeof reports undefined, calling it is a ReferenceError).
        let engine = IOSJsSandboxEngine()
        let result = await engine.evaluate(
            code: #"typeof tools"#,
            timeoutMs: 5000,
            maxOutputChars: 10000
        )
        guard case .success(let output) = result else {
            return XCTFail("expected success, got \(result)")
        }
        XCTAssertEqual(output.result, #""undefined""#)
    }
}

/// IOSToolExecutor is not Sendable; box it for the async actor boundary
/// (same pattern as IOSMcpExpandedToolTests).
private final class UncheckedToolExecutorBox: @unchecked Sendable {
    private let base: any IOSToolExecutor

    init(_ base: any IOSToolExecutor) {
        self.base = base
    }

    func execute(
        name: String,
        arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        await base.execute(
            name: name,
            arguments: arguments,
            isUserInitiated: isUserInitiated
        )
    }
}

/// P3-b: records nested host calls made from the sandbox's MainActor bridge so
/// the test can assert name/arguments ordering across synchronous calls.
private final class IOSJsSandboxToolCallRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var entries: [(name: String, arguments: String)] = []

    func record(name: String, arguments: String) {
        lock.lock()
        entries.append((name, arguments))
        lock.unlock()
    }

    var calls: [(name: String, arguments: String)] {
        lock.lock()
        defer { lock.unlock() }
        return entries
    }
}

/// P3-d: captures one evaluation's FINAL result delivered through the
/// completion listener (fires exactly once, including after a timeout/cancel
/// abandon). Safe against both orderings: store-before-wait and wait-before-store.
private final class IOSJsSandboxResultBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: IOSJsSandboxResult?
    private var continuation: CheckedContinuation<IOSJsSandboxResult, Never>?

    func store(_ result: IOSJsSandboxResult) {
        lock.lock()
        if let continuation {
            lock.unlock()
            continuation.resume(returning: result)
        } else {
            value = result
            lock.unlock()
        }
    }

    func waitForValue() async -> IOSJsSandboxResult {
        await withCheckedContinuation { continuation in
            lock.lock()
            if let value {
                lock.unlock()
                continuation.resume(returning: value)
            } else {
                self.continuation = continuation
                lock.unlock()
            }
        }
    }
}
