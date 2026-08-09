import XCTest
@preconcurrency import Shared
@testable import iosApp

/// I-4（`docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md` §W4,不变量 I-4）：
/// - `IOSCodexResolveCoordinator` 的 single-flight 合并语义（裂缝①）。
/// - `ChatGenerationCoordinator.settingsSnapshot(forRun:)` 的冻结 / 新 turn 边界
///   语义（裂缝②③,`prepareAndStartStreaming` 的两处压缩 live 读）。
final class IOSRunSnapshotTests: XCTestCase {

    // MARK: - Single-flight：并发调用合并为一次底层解析，结果共享

    func testResolveCoordinatorMergesConcurrentCallsForSameKeyIntoOneUnderlyingResolve() async {
        let coordinator = IOSCodexResolveCoordinator()
        let counter = ResolveCallCounter()
        let token = "shared-token"

        // 三个“并发”调用者（模拟前台流 + 后台交接同时解析同一个 provider），
        // 每个调用里的闭包本应各自跑一次网络刷新——single-flight 要把它们合并成一次。
        let tasks = (0..<3).map { _ in
            Task {
                try await coordinator.resolve(key: "provider-a") {
                    await counter.increment()
                    // 故意留一点时间窗口，让另外两个调用者有机会在第一个完成前
                    // 就撞上同一个 in-flight task。
                    try await Task.sleep(nanoseconds: 30_000_000)
                    return token
                }
            }
        }

        var results: [String] = []
        for task in tasks {
            switch await task.result {
            case .success(let resolved):
                results.append(resolved)
            case .failure(let error):
                XCTFail("unexpected failure: \(error)")
            }
        }

        XCTAssertEqual(results.count, 3)
        XCTAssertEqual(results, Array(repeating: token, count: 3))
        let invocationCount = await counter.count
        XCTAssertEqual(invocationCount, 1, "N 个并发调用应只触发一次底层解析闭包")
    }

    func testResolveCoordinatorSharesFailureAcrossConcurrentCallersWithoutAmplifyingIt() async {
        let coordinator = IOSCodexResolveCoordinator()
        let counter = ResolveCallCounter()
        struct ProbeError: Error, Equatable, Hashable { let token: Int }

        let tasks = (0..<3).map { _ in
            Task {
                try await coordinator.resolve(key: "provider-b") {
                    await counter.increment()
                    try await Task.sleep(nanoseconds: 30_000_000)
                    throw ProbeError(token: 7)
                }
            }
        }

        var observedErrors: [ProbeError] = []
        for task in tasks {
            switch await task.result {
            case .success:
                XCTFail("expected all callers to observe the shared failure")
            case .failure(let error):
                guard let probe = error as? ProbeError else {
                    return XCTFail("unexpected error type: \(error)")
                }
                observedErrors.append(probe)
            }
        }

        XCTAssertEqual(observedErrors.count, 3)
        XCTAssertEqual(Set(observedErrors), [ProbeError(token: 7)], "失败也应共享同一个结果，不放大成三次不同的错误")
        let invocationCount = await counter.count
        XCTAssertEqual(invocationCount, 1, "失败路径同样只应触发一次底层解析闭包")
    }

    func testResolveCoordinatorDoesNotMergeCallsForDifferentKeys() async {
        let coordinator = IOSCodexResolveCoordinator()
        let counter = ResolveCallCounter()
        async let resolvedA = coordinator.resolve(key: "provider-a") {
            await counter.increment()
            return "token-a"
        }
        async let resolvedB = coordinator.resolve(key: "provider-b") {
            await counter.increment()
            return "token-b"
        }

        let (a, b) = try! await (resolvedA, resolvedB)
        XCTAssertEqual(a, "token-a")
        XCTAssertEqual(b, "token-b")
        let invocationCount = await counter.count
        XCTAssertEqual(invocationCount, 2, "不同 key 之间不应互相合并")
    }

    // MARK: - I-4 冻结语义：`settingsSnapshot(forRun:)` 是压缩 prepare/finalize 两处的注入缝

    @MainActor
    func testSettingsSnapshotStaysFrozenAcrossBothCompactionReadsEvenAfterMidRunSettingsChange() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        let coordinator = makeCoordinator(sharedSettings: sharedSettings)

        let runId = "run-frozen"
        coordinator.installRunSnapshotForTesting(
            runId: runId,
            snapshot: ChatRunSnapshot(runId: runId, settings: sharedSettings.snapshot)
        )

        // run 中途改设置——`prepareAndStartStreaming` 压缩 prepare 步的 live 读裂缝
        // 曾经会让这次修改在同一个 run 里立刻生效。
        sharedSettings.setEnableWebSearch(false)

        // 模拟同一轮内的两次读取点（:1058 压缩 prepare、:1096 压缩 finalize）。
        let firstRead = coordinator.settingsSnapshotForTesting(runId: runId)
        let secondRead = coordinator.settingsSnapshotForTesting(runId: runId)

        XCTAssertTrue(firstRead.enableWebSearch, "prepare 步应读到 run 开始时冻结的值，而不是中途改动后的值")
        XCTAssertTrue(secondRead.enableWebSearch, "finalize 步应与 prepare 步读到同一份冻结值，不允许轮内自不一致")
        // sharedSettings 本身确实已经变了——证明"冻结"不是碰巧没变，而是快照生效。
        XCTAssertFalse(sharedSettings.snapshot.enableWebSearch)
    }

    @MainActor
    func testSettingsSnapshotCapturesInPlaceAndStoresWhenRunIdMatchesButSnapshotMissing() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        let coordinator = makeCoordinator(sharedSettings: sharedSettings)

        // 不经过 start()/runImageTool() 正常入口，直接把 currentRunId 设成一个新
        // runId、不带快照——这不是兜底容错，而是"新 turn 边界"的合法语义（例如未来
        // 接入的、跨进程重启的审批续流：那本来就该按新一轮的世界观重新定格配置）。
        // runId 与 currentRunId 一致是这个场景的关键前提。
        coordinator.installRunSnapshotForTesting(runId: "run-new-turn", snapshot: nil)
        XCTAssertNil(coordinator.currentRunSnapshotForTesting())

        let captured = coordinator.settingsSnapshotForTesting(runId: "run-new-turn")
        XCTAssertTrue(captured.enableWebSearch, "缺失快照时应就地捕获当前的真实设置")

        // 就地捕获的快照写回 currentRunSnapshot，本 run 剩余调用读到同一份新快照。
        XCTAssertEqual(coordinator.currentRunSnapshotForTesting()?.runId, "run-new-turn")

        // 捕获之后设置再变，不应影响已经定格的这份新快照（同一条冻结纪律，只是
        // 起点是"就地捕获"而不是"start() 时"）。
        sharedSettings.setEnableWebSearch(false)
        let secondRead = coordinator.settingsSnapshotForTesting(runId: "run-new-turn")
        XCTAssertTrue(secondRead.enableWebSearch, "就地捕获后的快照同样应该冻结，不再跟随后续变化")
    }

    // F4 fix: a stale/superseded runId must never overwrite the ACTIVE run's
    // frozen snapshot. Before the fix, `settingsSnapshot(forRun:)` wrote back
    // to `currentRunSnapshot` unconditionally whenever no snapshot existed for
    // the given `runId` — including when `runId` no longer matched
    // `currentRunId` (an async caller resuming after the run it belonged to
    // was replaced). That reverse-polluted the real active run's I-4 freeze
    // with a live-read settings snapshot captured on behalf of a dead run.
    @MainActor
    func testSettingsSnapshotReturnsFreshButDoesNotOverwriteActiveSnapshotWhenRunIdIsStale() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        let coordinator = makeCoordinator(sharedSettings: sharedSettings)

        let activeRunId = "run-active"
        let activeSnapshot = ChatRunSnapshot(runId: activeRunId, settings: sharedSettings.snapshot)
        coordinator.installRunSnapshotForTesting(runId: activeRunId, snapshot: activeSnapshot)

        // 活跃 run 的设置之后又变了一次，进一步证明"冻结"不是巧合，而是快照生效。
        sharedSettings.setEnableWebSearch(false)

        // 一个陈旧/已被替换的 runId（不再是 currentRunId）调用它——模拟一次异步
        // 调用在它所属的 run 被 cancel()/新 start() 替换之后才恢复执行。
        let staleRunId = "run-stale-expired"
        let staleRead = coordinator.settingsSnapshotForTesting(runId: staleRunId)
        XCTAssertFalse(staleRead.enableWebSearch, "过期 runId 应拿到就地读出的 fresh 设置，而不是活跃快照的冻结值")

        // 关键断言：currentRunSnapshot 必须仍然是活跃 run 那份，没有被过期 runId
        // 的 fresh 快照覆盖。
        XCTAssertEqual(coordinator.currentRunSnapshotForTesting()?.runId, activeRunId)
        XCTAssertTrue(
            coordinator.currentRunSnapshotForTesting()?.settings.enableWebSearch ?? false,
            "活跃 run 的冻结快照必须保持 run 开始时的值，不能被过期 runId 的写回污染"
        )
    }

    @MainActor
    func testPendingToolSelectionUsesFrozenToolNamesAfterLiveSettingChanges() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setEnableWebSearch(true)
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: nil,
            searchTransport: NoopSearchTransport(),
            mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
        )
        let tool = UIMessagePart.Tool(
            toolCallId: "search-1",
            toolName: "search_web",
            input: #"{"query":"amber"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [tool],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )

        // Simulate changing the global switch after this run's params.tools was frozen.
        sharedSettings.setEnableWebSearch(false)

        XCTAssertNotNil(runtime.nextPendingToolCall(
            in: [message],
            availableToolNames: ["search_web"]
        ))
        XCTAssertNil(runtime.nextPendingToolCall(
            in: [message],
            availableToolNames: []
        ))
    }

    // MARK: - Harness

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.tests.runsnapshot.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }

    @MainActor
    private func makeCoordinator(sharedSettings: IOSSharedSettingsStore) -> ChatGenerationCoordinator {
        ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: SettingsStore(),
                sharedSettings: sharedSettings,
                localToolExecutor: nil,
                searchTransport: NoopSearchTransport(),
                liveActivityController: .shared,
                autoGenerateResponses: false,
                mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared),
                orchestrationToolService: nil,
                memoryPollutionMarker: nil
            ),
            bindings: ChatGenerationBindings(
                getMessages: { [] },
                setMessages: { _ in },
                bumpMessageRevision: { _ in },
                shouldPaceStreamPresentation: { true },
                setIsLoading: { _ in },
                setPendingMemoryApproval: { _ in },
                setPendingSearchApproval: { _ in },
                setPendingWebMountApproval: { _ in },
                setPendingWorkspaceApproval: { _ in },
                setPendingIshHandoffApproval: { _ in },
                setPendingMcpApproval: { _ in },
                setPendingCouncilApproval: { _ in },
                setPendingAskUser: { _ in },
                setContextCompactState: { _ in },
                persistMessages: { _ in true },
                capturePersistMessagesBaseline: { _ in nil },
                persistMessagesSnapshot: { _, _, _ in true },
                recordRun: { _, _, _, _, _ in },
                startLiveActivity: { _, _, _ in },
                saveMiniAppIfPresent: { _, _ in nil },
                messagesByInjectingRuntimeContext: { $0 },
                userFacingGenerationError: { rawMessage, _ in rawMessage }
            )
        )
    }
}

/// 计数专用 actor：只用来验证 single-flight 合并语义，不涉及真实网络/Keychain。
private actor ResolveCallCounter {
    private(set) var count = 0
    func increment() { count += 1 }
}

@MainActor
private final class NoopSearchTransport: IOSSearchHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.com")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: [:]
        )!
        return (response, Data())
    }
}
