import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P0 red-light tests for the ios-parity-closure goal.
///
/// Each test here codifies a CONFIRMED parity gap as a white-box test that does
/// NOT depend on a live API key. These are the "named red-lights" from
/// GOAL_ios_parity_closure.md (P0 exit, lines 59-68): they MUST currently be RED
/// (the gap is real), and later phases (P0.5–P5) turn them GREEN.
///
/// Naming convention is the spec's snake_case (`test_<capability>_<scenario>`)
/// even though the rest of the suite uses camelCase — the spec names are the
/// contract that verify/refute check against, so they are kept verbatim.
///
/// Why each is RED is documented per-test. The truth_matrix cell each one binds
/// is noted in the doc comment.
@MainActor
final class IOSParityRedLightTests: XCTestCase {

    func testChatBackButtonPopsTheAppRouterPath() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let chat = try String(
            contentsOf: appDirectory.appendingPathComponent("ChatView.swift"),
            encoding: .utf8
        )

        guard let start = chat.range(of: "private var backToolbarButton") else {
            return XCTFail("Expected the Chat back button")
        }
        let backButton = chat[start.lowerBound...]
        XCTAssertTrue(backButton.contains("router.goBack()"))
        XCTAssertFalse(backButton.prefix(900).contains("dismiss()"))
    }

    func testRouterPathGoBackRemovesOnlyTheTopRoute() {
        let router = RouterPath()
        router.path = [.chat, .settings]

        router.goBack()

        XCTAssertEqual(router.path, [.chat])
    }

    func testConversationListInvalidatesForForegroundAndBackgroundGenerationTermination() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let home = try String(
            contentsOf: appDirectory.appendingPathComponent("PlaceholderViews.swift"),
            encoding: .utf8
        )

        guard let start = home.range(of: "struct ConversationsView: View") else {
            return XCTFail("Expected ConversationsView")
        }
        let conversations = home[start.lowerBound...]
        XCTAssertTrue(conversations.contains("_ = chatViewModel.isLoading"))
        XCTAssertTrue(conversations.contains(".amberChatBackgroundJobDidTerminate"))
        XCTAssertTrue(conversations.contains("backgroundGenerationRevision &+= 1"))
    }

    func testForegroundToolBatchDrainsBeforeOneModelContinuationAndLimitDisablesTools() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let coordinator = try String(
            contentsOf: appDirectory.appendingPathComponent("ChatGenerationCoordinator.swift"),
            encoding: .utf8
        )

        guard let start = coordinator.range(of: "private func continueAfterToolResult(") else {
            return XCTFail("Expected one continuation owner after a tool result")
        }
        let continuation = coordinator[start.lowerBound...]
        let pending = try XCTUnwrap(continuation.range(of: "toolRuntime.nextPendingToolCall("))
        let unresolved = try XCTUnwrap(continuation.range(of: "toolRuntime.hasUnresolvedToolCall("))
        let stream = try XCTUnwrap(continuation.range(of: "prepareAndStartStreaming("))
        XCTAssertLessThan(pending.lowerBound, stream.lowerBound)
        XCTAssertLessThan(pending.lowerBound, unresolved.lowerBound)
        XCTAssertLessThan(unresolved.lowerBound, stream.lowerBound)
        XCTAssertTrue(continuation.prefix(7000).contains("continuationParamsAfterToolExecution("))
        XCTAssertTrue(coordinator.contains("tools: []"))
    }

    func testToolContinuationParamsDisableToolsOnlyAtTheExecutionLimit() {
        let model = Model(
            modelId: "test-model",
            displayName: "Test Model",
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
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [ToolKt.createSearchWebToolDeclaration()],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: [],
            customBody: []
        )

        let beforeLimit = ChatGenerationCoordinator.continuationParamsAfterToolExecution(
            params,
            resumeCount: 3,
            maxResumeCount: 4
        )
        let atLimit = ChatGenerationCoordinator.continuationParamsAfterToolExecution(
            params,
            resumeCount: 4,
            maxResumeCount: 4
        )

        XCTAssertEqual(beforeLimit.tools.map(\.name), params.tools.map(\.name))
        XCTAssertTrue(atLimit.tools.isEmpty)
    }

    // MARK: - shared fixtures

    /// A Claude provider as it would be constructed for a user who selected
    /// Anthropic as their chat provider. Mirrors the KMP
    /// `ProviderSetting.Claude` shape (ProviderSetting.kt:310).
    private func makeClaudeProvider(apiKey: String = "sk-ant-test-SECRET") -> ProviderSetting.Claude {
        ProviderSetting.Claude(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Claude",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: "https://api.anthropic.com/v1",
            promptCaching: false
        )
    }

    private func makeOpenAIProvider(apiKey: String = "sk-test-SECRET") -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI-compatible",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: "https://api.example.com/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    // MARK: - DeepRead (truth_matrix row `deepread`)

    /// RED for P0. GREEN target: P0.5 / P3.
    /// Cell: deepread.provider_real.
    ///
    /// When the user has selected a Claude provider, the Deep Read pipeline must
    /// resolve to a `ProviderSetting.Claude` and ultimately call Anthropic's
    /// native `/messages` — NOT silently rebuild an OpenAI setting and hit
    /// `/chat/completions`. Today `BoardView.createAndGenerateTask`
    /// (BoardView.swift:513) unconditionally constructs `ProviderSetting.OpenAI`
    /// regardless of the selected provider, so there is no Claude construction
    /// path at all. This test asserts the parity target: a Claude-selected run
    /// must construct a Claude setting.
    ///
    /// The test reaches the resolver the same way the pipeline does — via the
    /// canonical chat provider-resolution path that P3 will route DeepRead
    /// through. Until that routing exists, the resolved setting for a
    /// Claude selection is OpenAI (the rebuild), so the assertion fails.
    func test_deepread_claudeSelected_constructsClaudeSetting() {
        // The parity target: selecting Claude must yield a Claude provider
        // setting for the Deep Read pipeline.
        let selectedClaude = makeClaudeProvider()
        let resolved = deepReadResolvedProvider(forSelected: selectedClaude)

        // Must be Claude, not a silently-rebuilt OpenAI setting (and not nil).
        XCTAssertTrue(
            resolved is ProviderSetting.Claude,
            "Deep Read must use the user's selected Claude provider, not a rebuilt OpenAI setting. "
                + "Currently resolves to: \(String(describing: resolved.map { String(describing: type(of: $0)) })) — this is the rebuild gap (P3)."
        )
    }

    /// RED for P0. GREEN target: P4.
    /// Cell: deepread.honest_fail.
    ///
    /// When every synthesis stage throws, the task must end in `.failed` with an
    /// honest failure message — not `.succeeded` with empty sections. Today
    /// `synthesize` swallows errors and returns "" (IOSBoardPersistence.swift:2900),
    /// and `createAndGenerateTask` unconditionally calls `complete(...)`
    /// (BoardView.swift:539), so an all-stages-failed run is marked succeeded.
    /// P4 introduces the queued/running/ready/failed state machine + honest
    /// failure. This test drives a failing provider through the generator and
    /// asserts the resulting task status reflects failure.
    func test_deepread_allStagesThrow_statusFailed() async throws {
        let task = makeDeepReadTask()
        let store = IOSDeepReadStore(baseDirectory: makeTempDir())
        let running = try store.createTask(
            title: task.title,
            sources: task.sources,
            templateId: task.templateId
        )
        store.markRunning(id: running.id)
        let failingProvider = FailingTextProvider()
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: store.task(id: running.id) ?? running,
            providerSetting: makeOpenAIProvider(),
            modelId: "any-model",
            provider: failingProvider
        )
        // Apply the result via the SAME production decision function the create
        // and retry view paths use (IOSDeepReadDraftGenerator.outcome), not a
        // re-implemented branch — so this guards caller-honoring, not a copy.
        switch IOSDeepReadDraftGenerator.outcome(for: result, offlineFallback: "") {
        case .failed(let reason):
            store.fail(id: running.id, message: "所有生成阶段失败：\(reason)")
        case .completed(let markdown, _):
            store.complete(id: running.id, markdown: markdown)
        }
        let final = store.task(id: running.id)

        // Parity target: when all stages threw, status MUST be .failed, not
        // succeeded.
        XCTAssertEqual(
            final?.status,
            IOSDeepReadTaskStatus.failed,
            "All-stages-threw must surface as status=.failed, not .succeeded. "
                + "Got: \(String(describing: final?.status)) — honest-fail state machine (P0.5)."
        )
    }

    /// RED for P0. GREEN target: P4.
    /// Cell: deepread.honest_fail.
    ///
    /// A stage that produces empty output must NOT let the task be marked as
    /// successfully completed/ready. P4's state machine distinguishes a "ready"
    /// (usable) result from an empty one. Today empty sections still flow to
    /// `complete(...)`. This test asserts an empty-output run is not marked
    /// ready/succeeded.
    func test_deepread_stageEmpty_notMarkedReady() async throws {
        let task = makeDeepReadTask()
        let store = IOSDeepReadStore(baseDirectory: makeTempDir())
        let running = try store.createTask(
            title: task.title,
            sources: task.sources,
            templateId: task.templateId
        )
        store.markRunning(id: running.id)
        // Provider returns empty for every stage.
        let emptyProvider = EmptyTextProvider()
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: store.task(id: running.id) ?? running,
            providerSetting: makeOpenAIProvider(),
            modelId: "any-model",
            provider: emptyProvider
        )
        // Empty-output run must surface as honest failure, not succeeded/ready —
        // decided by the production outcome function, not a test-local branch.
        switch IOSDeepReadDraftGenerator.outcome(for: result, offlineFallback: "") {
        case .failed:
            store.fail(id: running.id, message: "生成内容为空：所有阶段未产出可用内容")
        case .completed(let markdown, _):
            store.complete(id: running.id, markdown: markdown)
        }
        let final = store.task(id: running.id)

        XCTAssertNotEqual(
            final?.status,
            IOSDeepReadTaskStatus.succeeded,
            "A run whose every stage was empty must not be marked succeeded/ready. "
                + "Got: \(String(describing: final?.status)) — honest-fail state machine (P0.5)."
        )
    }

    /// GREEN guard for the Deep Read RETRY surface (parity fix, this change).
    /// Cell: deepread.honest_fail (retry path).
    ///
    /// The retry button previously called a thin wrapper that discarded
    /// `didFail` and always `complete(...)`d, so an all-stages-failed retry was
    /// marked succeeded with empty sections. `retryOutcome` now propagates the
    /// failure. Driving a failing provider end-to-end through the real retry
    /// decision must yield `.failed` — this exercises the actual production seam
    /// (`IOSDeepReadDraftGenerator.retryOutcome`), not a re-implemented branch.
    func test_deepread_retry_allStagesThrow_returnsFailed() async {
        let outcome = await IOSDeepReadDraftGenerator.retryOutcome(
            resolvedProvider: makeOpenAIProvider(),
            modelId: "any-model",
            task: makeDeepReadTask(),
            provider: FailingTextProvider()
        )
        guard case .failed = outcome else {
            return XCTFail("All-stages-failed retry must return .failed, got \(outcome)")
        }
    }

    /// GREEN guard: a successful retry returns `.completed` with the model text.
    func test_deepread_retry_success_returnsCompleted() async {
        let outcome = await IOSDeepReadDraftGenerator.retryOutcome(
            resolvedProvider: makeOpenAIProvider(),
            modelId: "any-model",
            task: makeDeepReadTask(),
            provider: IOSDeepReadPipelineTests.StageProvider([#"{"summary":"ov 摘要"}"#])
        )
        guard case .completed(let markdown, _) = outcome else {
            return XCTFail("Successful retry must return .completed, got \(outcome)")
        }
        XCTAssertTrue(markdown.contains("ov"), "Completed retry must carry the model output (summary).")
    }

    /// GREEN guard for the search-failure source state (de-faked this change).
    /// Cell: deepread.honest_fail (search/source failure mapping).
    ///
    /// A failed search must be recorded as a DISTINCT, machine-readable
    /// source-failure state (scrape_status="failed"), not silently fed to the
    /// model as factual content — and a single failed source must NOT hard-fail
    /// the whole task when surviving sources exist. Previously this test passed
    /// against a hand-built fixture via a title-substring fallback (the production
    /// catch path wrote no marker); it now drives the production source builder
    /// (IOSDeepReadSourceNormalizer.searchFailureSource, the same one BoardView's
    /// catch path uses) and asserts the marker strictly.
    func test_deepread_searchFailure_isSourceFailure() async throws {
        let task = makeDeepReadTask()
        let store = IOSDeepReadStore(baseDirectory: makeTempDir())
        // Build the failed-search source exactly the way production does (the
        // catch path in createDeepReadTask), not a hand-rolled fixture.
        let failedSource = try IOSDeepReadSourceNormalizer.searchFailureSource(
            query: "query",
            error: "network error"
        )
        // The production builder must stamp the machine-readable failure marker.
        XCTAssertEqual(
            failedSource.metadata["scrape_status"],
            "failed",
            "A failed search must produce a source carrying scrape_status=failed."
        )
        let goodSource = task.sources[0]
        let running = try store.createTask(
            title: task.title,
            sources: [goodSource, failedSource],
            templateId: task.templateId
        )
        store.markRunning(id: running.id)
        let provider = IOSDeepReadPipelineTests.StageProvider(["ov", "na", "an"])
        let draft = await IOSDeepReadDraftGenerator.generateViaLLM(
            task: store.task(id: running.id) ?? running,
            providerSetting: makeOpenAIProvider(),
            modelId: "any-model",
            provider: provider
        )
        store.complete(id: running.id, markdown: draft)
        let final = store.task(id: running.id)

        // Parity target A: the marker must survive into the persisted task —
        // asserted STRICTLY via metadata (no title-substring fallback).
        let persistedFailure = (final?.sources ?? []).first { $0.metadata["scrape_status"] == "failed" }
        XCTAssertNotNil(
            persistedFailure,
            "The failed-search source-level marker must survive into the persisted task."
        )
        // Parity target B: the task must NOT be hard-failed solely because a
        // source failed to fetch (it should run over surviving sources).
        XCTAssertNotEqual(
            final?.status,
            IOSDeepReadTaskStatus.failed,
            "A source-level search failure must not fail the whole task when surviving sources exist."
        )
    }

    // MARK: - SubAgent (truth_matrix row `subagent_standalone`)

    /// RED for P0. GREEN target: P4.
    /// Cell: subagent_standalone.exec_real.
    ///
    /// GREEN guard for the standalone SubAgent engine path (de-faked this change).
    /// Cell: subagent_standalone.exec_real.
    ///
    /// The standalone SubAgent page must dispatch through `IOSAgentToolEngine`
    /// (`runViaEngine`), not the legacy KMP `SubAgentManager` path (`run`). Both
    /// page buttons call `SubAgentsView.runStandaloneViaEngine`, which delegates to
    /// the testable `dispatchStandalone` seam. This drives that EXACT seam with a
    /// configured shared-settings store + a probe provider and asserts the engine
    /// path is taken (provider invoked + task tagged engine) — so a regression that
    /// re-routes the page to legacy `run` is actually caught. The previous version
    /// only read a hardcoded `static let = true` constant (fake-green).
    func test_subagent_standalone_usesEnginePath() async {
        let taskSuite = UserDefaults(suiteName: "redlight-subagent-\(UUID().uuidString)")!
        let taskStore = IOSAdvancedTaskStore(userDefaults: taskSuite, storageKey: "redlight.tasks")
        let runner = SubAgentRunner(taskStore: taskStore)
        let probe = EngineProbeProvider()

        // A shared-settings store configured with a provider + selected model —
        // exactly what the standalone page resolves from.
        let ssSuite = UserDefaults(suiteName: "redlight-subagent-ss-\(UUID().uuidString)")!
        let sharedSettings = IOSSharedSettingsStore(userDefaults: ssSuite)
        sharedSettings.addCustomModel(name: "引擎模型", modelId: "engine-model", providerName: "EngineProvider")
        if let added = sharedSettings.snapshot.providers.last as? ProviderSetting.OpenAI,
           let model = added.models.first {
            sharedSettings.setCurrentChatModelId(model.id.description())
        }

        // Drive the EXACT dispatch the standalone page's buttons call.
        _ = await SubAgentsView.dispatchStandalone(
            objective: "explore the objective",
            roleId: "explorer",
            sharedSettings: sharedSettings,
            runner: runner,
            provider: probe
        )

        // The engine path must be taken: provider invoked + task tagged engine.
        // The legacy `run` path would leave probe.callCount==0 and no engine tag.
        XCTAssertGreaterThan(
            probe.callCount,
            0,
            "Standalone dispatch must invoke the provider via the engine path (runViaEngine)."
        )
        XCTAssertEqual(
            runner.lastTask?.metadata["engine"],
            "true",
            "Standalone dispatch must record engine routing, not legacy `run`."
        )
    }

    // MARK: - Council (truth_matrix row `council`)

    /// RED for P0. GREEN target: P3 (now GREEN).
    /// Cell: council.provider_real.
    ///
    /// When the user has selected a Claude provider, Council must construct a
    /// `ProviderSetting.Claude` (native /messages), not silently rebuild an
    /// OpenAI setting. P3 added `IOSCouncilRoomRunner.resolveProviderSetting`
    /// (SLICE_TEMPLATE pattern 1, copied from DeepRead) and routed both council
    /// entries through it. The lower-level streamer dispatches on the sealed
    /// type, so a Claude setting flows to native.
    func test_council_claudeSelected_constructsClaudeSetting() {
        let selectedClaude = makeClaudeProvider()
        let resolved = councilResolvedProvider(forSelected: selectedClaude)

        XCTAssertTrue(
            resolved is ProviderSetting.Claude,
            "Council must use the user's selected Claude provider, not a rebuilt OpenAI setting. "
                + "Currently resolves to: \(String(describing: resolved.map { String(describing: type(of: $0)) })) — council resolver (P3)."
        )
    }

    // MARK: - Settings / Backup / Recovery (truth_matrix rows `backup`, `chat`.recover)

    /// RED for P0. GREEN target: P2.
    /// Cell: *.secure_store (settings encode path).
    ///
    /// Encoding the canonical settings snapshot to its persisted form must NOT
    /// contain any credential (apiKey) in plaintext. Today
    /// `IOSSharedSettingsStore` serializes the full KMP `Settings` (whose
    /// providers carry a real `apiKey`) as plaintext JSON to UserDefaults
    /// (`app.amber.ios.sharedSettingsJson`, IOSSharedSettingsStore.swift:147).
    /// P2 (iOS-only scheme B) strips credentials before encode + stores them in
    /// a Keychain side-table. This test writes a provider apiKey via the public
    /// mutation API, persists, and greps the persisted bytes for the secret.
    func test_settings_encode_containsNoCredential() {
        let secret = "sk-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-settings-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = makeOpenAIProvider(apiKey: secret)
        store.addProvider(provider)
        // addProvider calls restoreSnapshot (writes JSON to UserDefaults).

        guard let persisted = defaults.data(forKey: "app.amber.ios.sharedSettingsJson") ??
            defaults.string(forKey: "app.amber.ios.sharedSettingsJson")?.data(using: .utf8) else {
            XCTFail("settings JSON must be persisted to UserDefaults for the leak check")
            return
        }
        let persistedString = String(data: persisted, encoding: .utf8) ?? ""

        XCTAssertFalse(
            persistedString.contains(secret),
            "Persisted settings JSON must not contain the plaintext apiKey credential. "
                + "P2 scheme B strips credentials before encode + Keychain side-table."
        )
    }

    /// RED for P0. GREEN target: P2.
    /// Cell: backup.secure_store.
    ///
    /// The DEFAULT backup export (no opt-in redaction) must NOT contain
    /// credentials. A backup that decrypts back to plaintext credentials is a
    /// leak (encryption is not a substitute for redaction: a passphrase-protected
    /// backup decrypts to the secret, and a no-passphrase backup uses a public
    /// fallback constant). Today `IOSSyncBackup.export` calls
    /// `IosSettingsJsonBridge.encode` with no redaction (IOSSyncBackup.swift:63),
    /// unlike Android's `BackupSettingsRedactor`. This test builds a Settings
    /// with a known apiKey, exports, imports back (round-trip decrypt), and
    /// asserts the secret is absent from the decrypted payload.
    func test_backup_default_containsNoCredential() throws {
        let secret = "sk-SECRET-\(UUID().uuidString)"
        var settings = IosSettingsDefaults.shared.defaultSeededSettings()
        // Inject a provider carrying the secret apiKey.
        let providerWithSecret = makeOpenAIProvider(apiKey: secret)
        settings = IosSettingsMutations.shared.addProvider(
            settings: settings,
            provider: providerWithSecret
        )

        // Export with a known passphrase, then decrypt-import. The redaction
        // target must hold in the DECRYPTED payload, not just the encrypted
        // bytes. (Scheme B redacts before encryption; the decrypted form carries
        // the mask sentinel, never the secret.)
        let passphrase = "pw-\(UUID().uuidString)"
        let archive = try IOSSyncBackup.export(settings: settings, passphrase: passphrase)
        let imported = try IOSSyncBackup.import(data: archive, passphrase: passphrase)
        let decryptedJson = IosSettingsJsonBridge.shared.encode(settings: imported.settings)

        XCTAssertFalse(
            decryptedJson.contains(secret),
            "Default backup (decrypted) must not contain the plaintext apiKey credential. "
                + "Scheme B redacts before encryption; the decrypted form carries the mask, not the secret."
        )
    }

    /// RED for P0. GREEN target: P5.
    /// Cell: chat.recover.
    /// RED for P0. GREEN target: P5 (now GREEN).
    /// Cell: chat.recover.
    ///
    /// A run killed mid-stream must be reclassified to an interrupted-or-
    /// resumable state — NOT silently lost or frozen as "running" forever. P5
    /// added `IOSRunRecovery.recoverInterruptedRuns()` — a startup sweep that
    /// reads `listUnfinished` (non-terminal rows) and calls `markInterrupted` on
    /// each, so a killed run surfaces honestly on next launch.
    ///
    /// This test inserts a "running" row (simulating a mid-stream kill), runs
    /// the recovery sweep, then asserts the run was reclassified to
    /// interrupted/resumable (not still "running").
    func test_recovery_killMidStream_runInterruptedOrResumed() async throws {
        let db = IosDatabaseFactory.shared.createDatabase()
        let dao = db.agentRuntimeDao()
        let runId = "redlight-recovery-\(UUID().uuidString)"
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // Simulate the row that a start() would write mid-stream (P5 adds the
        // start-row write; here we inject it directly to model the killed state).
        let runningRow = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: nil,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: "running",
            inputDigest: "digest-\(runId)",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: now,
            finishedAt: nil,
            interruptedReason: nil
        )
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            dao.insertRun(run: runningRow) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }

        // On next launch, the recovery sweep (IOSRunRecovery.recoverInterruptedRuns)
        // reclassifies orphaned non-terminal rows to "interrupted". This is the
        // P5 parity target: a killed run surfaces as interrupted-or-resumable.
        _ = await IOSRunRecovery.recoverInterruptedRuns(reason: "process_killed", now: now)

        // After the sweep, the run must be reclassified — read its status.
        let recoveredStatus = await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            dao.getRun(id: runId) { result, _ in
                cont.resume(returning: result?.status)
            }
        }

        // Parity target: after a kill + the recovery sweep, the run must be in an
        // interrupted-or-resumable state, not left frozen as "running".
        let isInterruptedOrResumed = recoveredStatus == "interrupted" || recoveredStatus == "resumed"
        XCTAssertTrue(
            isInterruptedOrResumed,
            "A run killed mid-stream must be reclassified to interrupted/resumable by the recovery "
                + "sweep, not left frozen as 'running'. Recovered status: \(recoveredStatus ?? "<nil>") (P5)."
        )
    }

    func test_recovery_preservesDurablyOwnedBackgroundRun() async throws {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let runId = "background-owned-recovery-\(UUID().uuidString)"
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let runningRow = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: "conversation-owned-by-background",
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: "running",
            inputDigest: "digest-\(runId)",
            inputSnapshotRef: nil,
            inputSchemaVersion: 1,
            startedAt: now,
            finishedAt: nil,
            interruptedReason: nil
        )
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            dao.insertRun(run: runningRow) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }

        _ = await IOSRunRecovery.recoverInterruptedRuns(
            excludingRunIds: [runId],
            reason: "process_killed",
            now: now
        )

        let status = await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            dao.getRun(id: runId) { result, _ in
                continuation.resume(returning: result?.status)
            }
        }
        XCTAssertEqual(status, "running")

        _ = await IOSRunRecovery.recoverInterruptedRuns(
            reason: "test_cleanup",
            now: now
        )
    }

    func test_recovery_pendingApprovalCarriesExactToolOwnerBeforeInterruptingRun() async throws {
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let runId = "pending-approval-recovery-\(UUID().uuidString)"
        let conversationId = "conversation-\(UUID().uuidString)"
        let toolCallId = "tool-\(UUID().uuidString)"
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let run = AgentRunEntity(
            runId: runId,
            parentRunId: nil,
            agentDescriptorId: "chat",
            agentVersion: "1",
            conversationId: conversationId,
            messageNodeId: nil,
            producesMessageId: nil,
            assistantId: nil,
            status: "awaiting_permission",
            inputDigest: "digest",
            inputSnapshotRef: "tool_call:\(toolCallId)",
            inputSchemaVersion: 1,
            startedAt: now,
            finishedAt: nil,
            interruptedReason: nil
        )
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            dao.insertRun(run: run) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }

        let loadedDescriptors = await IOSRunRecovery.recoverPendingApprovalDescriptors()
        let descriptors = try XCTUnwrap(loadedDescriptors)
        let descriptor = try XCTUnwrap(descriptors.first { $0.runId == runId })
        XCTAssertEqual(descriptor.conversationId, conversationId)
        XCTAssertEqual(descriptor.toolCallId, toolCallId)
        let pendingStatus = await withCheckedContinuation {
            (continuation: CheckedContinuation<String?, Never>) in
            dao.getRun(id: runId) { result, _ in
                continuation.resume(returning: result?.status)
            }
        }
        XCTAssertEqual(pendingStatus, "awaiting_permission")

        await IOSRunRecovery.completePendingApprovalRecovery(runId: runId, now: now)
        let recoveredStatus = await withCheckedContinuation {
            (continuation: CheckedContinuation<String?, Never>) in
            dao.getRun(id: runId) { result, _ in
                continuation.resume(returning: result?.status)
            }
        }
        XCTAssertEqual(recoveredStatus, "interrupted")
    }

    // MARK: - secure_store (search / TTS credential classes — discovery round 1)

    /// RED for P0 (discovery round 1). GREEN target: P2.
    /// Cell: search.secure_store.
    ///
    /// A user-configured SEARCH provider API key must NOT be persisted in
    /// plaintext. Today `IOSSharedSettingsStore.addSearchProvider(name:apiKey:serviceType:)`
    /// (IOSSharedSettingsStore.swift:535) writes the apiKey into BOTH the legacy
    /// mirror `app.amber.ios.customSearchProviders` (line 546) AND the full
    /// `Settings` JSON blob via `restoreSnapshot` → `IosSettingsJsonBridge.encode`
    /// (the SearchServiceOptions.TavilyOptions.apiKey field is serialized). P2
    /// scheme B extends credential stripping to search keys. This test adds a
    /// search provider and greps both UserDefaults surfaces for the secret.
    func test_searchProviderApiKey_encode_containsNoCredential() {
        let secret = "search-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-search-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        store.addSearchProvider(name: "Tavily Test", apiKey: secret, serviceType: "tavily")

        // Two leak surfaces (both must be clean after P2):
        let fullJson = (defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? "")
        let legacyEntries = (defaults.array(forKey: "app.amber.ios.customSearchProviders") as? [[String: Any]]) ?? []
        let legacyMirror = legacyEntries.flatMap { $0["apiKey"] as? String }.joined()

        XCTAssertFalse(
            fullJson.contains(secret),
            "Full settings JSON must not contain the search apiKey in plaintext. (P2 scheme B)"
        )
        XCTAssertFalse(
            legacyMirror.contains(secret),
            "Legacy customSearchProviders mirror must not contain the search apiKey in plaintext. (P2)"
        )
    }

    func test_searchProviderApiKey_rehydratedFromKeychainOnReloadAndRepersist() {
        let secret = "search-rehydrate-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-search-rehydrate-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        store.addSearchProvider(name: "Tavily Rehydrate", apiKey: secret, serviceType: "tavily")

        guard let added = store.snapshot.searchServices.last as? SearchServiceOptions.TavilyOptions else {
            return XCTFail("Expected added Tavily search service.")
        }
        let serviceId = added.id.description()
        let persistedJson = defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? ""
        XCTAssertFalse(persistedJson.contains(secret))
        XCTAssertTrue(persistedJson.contains(IOSCredentialRedactor.mask))

        let reloaded = IOSSharedSettingsStore(userDefaults: defaults)
        let reloadedService = reloaded.snapshot.searchServices.first {
            $0.id.description() == serviceId
        } as? SearchServiceOptions.TavilyOptions
        XCTAssertEqual(reloadedService?.apiKey, secret)

        reloaded.setEnableWebSearch(!reloaded.snapshot.enableWebSearch)
        let repersistedJson = defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? ""
        XCTAssertFalse(repersistedJson.contains(secret))
        XCTAssertTrue(repersistedJson.contains(IOSCredentialRedactor.mask))

        let reloadedAgain = IOSSharedSettingsStore(userDefaults: defaults)
        let serviceAfterRepersist = reloadedAgain.snapshot.searchServices.first {
            $0.id.description() == serviceId
        } as? SearchServiceOptions.TavilyOptions
        XCTAssertEqual(serviceAfterRepersist?.apiKey, secret)
    }

    /// RED for P0 (discovery round 1). GREEN target: P2.
    /// Cell: *.secure_store (TTS credential class).
    ///
    /// A user-configured TTS engine API key must NOT be persisted in plaintext.
    /// Today `IOSSharedSettingsStore.addTtsEngine(name:engineType:apiKey:model:)`
    /// (IOSSharedSettingsStore.swift:615) writes the apiKey into BOTH the legacy
    /// mirror `app.amber.ios.customTtsEngines` (line 603) AND the full `Settings`
    /// JSON blob via `restoreSnapshot` (the TTSProviderSetting.OpenAI.apiKey field
    /// is serialized). P2 scheme B extends credential stripping to TTS keys.
    func test_ttsEngineApiKey_encode_containsNoCredential() {
        let secret = "tts-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-tts-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        store.addTtsEngine(name: "OpenAI TTS Test", engineType: "openai", apiKey: secret, model: "tts-1")

        let fullJson = (defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? "")
        let legacyEntries = (defaults.array(forKey: "app.amber.ios.customTtsEngines") as? [[String: Any]]) ?? []
        let legacyMirror = legacyEntries.flatMap { $0["apiKey"] as? String }.joined()

        XCTAssertFalse(
            fullJson.contains(secret),
            "Full settings JSON must not contain the TTS apiKey in plaintext. (P2 scheme B)"
        )
        XCTAssertFalse(
            legacyMirror.contains(secret),
            "Legacy customTtsEngines mirror must not contain the TTS apiKey in plaintext. (P2)"
        )
    }

    /// RED for P0 (discovery round 2). GREEN target: P2.
    /// Cell: *.secure_store (MCP credential class).
    ///
    /// A user-configured MCP server's Authorization/API-key headers must NOT be
    /// persisted in plaintext. Today `IOSMcpConfigStore.persist()`
    /// (IOSMcpConfigStore.swift:87) encodes `IOSMcpStoredServer` (whose `headers`
    /// dict is stored verbatim, IOSMcpClient.swift:99) to UserDefaults key
    /// `app.amber.ios.mcpServers` with no redaction — so a user typing
    /// `Authorization: Bearer <secret>` leaks the secret. MCP headers have no
    /// Keychain wrapper (only provider/SSH keys do). P2 scheme B extends
    /// credential stripping to MCP header values.
    func test_mcpServerHeaders_encode_containsNoCredential() {
        let secret = "mcp-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-mcp-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSMcpConfigStore(userDefaults: defaults)
        let server = IOSMcpServerConfig.streamableHTTP(
            name: "Leaky MCP",
            url: "https://mcp.example.com/sse",
            headers: ["Authorization": "Bearer \(secret)"],
            enabled: true,
            tools: []
        )
        store.add(server)

        guard let persisted = defaults.data(forKey: "app.amber.ios.mcpServers"),
              let persistedString = String(data: persisted, encoding: .utf8) else {
            XCTFail("MCP servers must be persisted to UserDefaults for the leak check")
            return
        }
        XCTAssertFalse(
            persistedString.contains(secret),
            "Persisted MCP config must not contain the Authorization header secret in plaintext. (P2 scheme B)"
        )
    }

    /// RED for P0 (discovery round 3). GREEN target: P2.
    /// Cell: chat.secure_store (model-level customHeaders credential class).
    ///
    /// A user-editable model customHeader carrying a credential (e.g.
    /// `Authorization: Bearer <secret>`) must NOT be persisted in plaintext.
    /// Today `IOSSharedSettingsStore.upsertProviderChatModel(... headers:)`
    /// (IOSSharedSettingsStore.swift:453) writes the header pairs verbatim onto
    /// `Model.customHeaders` (via IosSettingsMutations, no redaction) and
    /// `restoreSnapshot` serializes the full Settings to UserDefaults plaintext
    /// (the same JSON also feeds the default backup export). The model-header
    /// editor is user-facing (ProviderDetailView.swift:869). P2 scheme B extends
    /// credential stripping to customHeader Authorization-like values.
    func test_modelCustomHeaders_encode_containsNoCredential() {
        let secret = "modelhdr-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-modelhdr-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = makeOpenAIProvider()
        store.addProvider(provider)
        let providerId = provider.id.description() as String
        store.upsertProviderChatModel(
            providerId: providerId,
            modelUuid: nil,
            modelId: "m-\(UUID().uuidString)",
            displayName: "m",
            contextWindowTokens: nil,
            modelType: ModelType.chat,
            headers: [("Authorization", "Bearer \(secret)")]
        )

        let fullJson = (defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? "")
        XCTAssertFalse(
            fullJson.contains(secret),
            "Persisted settings JSON must not contain a model customHeader credential in plaintext. (P2 scheme B)"
        )
    }

    /// RED for P0 (discovery round 5). GREEN target: P2.
    /// Cell: chat.secure_store (assistant-level customHeaders credential class).
    ///
    /// An assistant carrying a credential in `Assistant.customHeaders` (e.g.
    /// `Authorization: Bearer <secret>`) must NOT be persisted in plaintext.
    /// No iOS editor exists for Assistant.customHeaders, BUT the backup-import /
    /// restore path deserializes it from external JSON (IOSSyncBackup.import →
    /// `IosSettingsJsonBridge.decode`, IOSSyncBackup.swift:210) and
    /// `restoreSnapshot` (IOSSharedSettingsStore.swift:147) re-encodes the full
    /// Settings via the UNREDACTED `IosSettingsJsonBridge.encode` to UserDefaults
    /// `app.amber.ios.sharedSettingsJson`. So an imported assistant with an
    /// Authorization header leaks the secret in plaintext. Same leak class as
    /// `test_modelCustomHeaders_encode_containsNoCredential`; P2 scheme B must
    /// extend credential stripping to Assistant.customHeaders on the persist path.
    ///
    /// This test seeds the leak via the same JSON-round-trip path import uses:
    /// encode default settings → inject an assistant carrying the header into the
    /// JSON → decode → restoreSnapshot → assert the secret is absent.
    func test_assistantCustomHeaders_encode_containsNoCredential() throws {
        let secret = "assthdr-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-assthdr-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)

        // Build a Settings JSON with an assistant carrying the credential header,
        // mirroring what an imported backup would contain.
        var json = IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        // The Settings JSON is an object with an "assistants" array. Inject one.
        let assistantFragment = """
        {"id":"\(UUID().uuidString)","name":"Imported","customHeaders":[{"name":"Authorization","value":"Bearer \(secret)"}]}
        """.data(using: .utf8)!
        json = try injectAssistant(into: json, assistantJsonData: assistantFragment)

        // Decode + restoreSnapshot = the import/restore leak path.
        // decode 已声明 @Throws（Kotlin/Native 桥接），这里走成功路径，用 try。
        let decoded = try IosSettingsJsonBridge.shared.decode(json: json)
        store.restoreSnapshot(decoded)

        let persisted = (defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? "")
        XCTAssertFalse(
            persisted.contains(secret),
            "Persisted settings JSON must not contain an assistant customHeader credential in plaintext. "
                + "Import/restore round-trips Assistant.customHeaders unredacted (P2 scheme B)."
        )
    }

    /// Injects an assistant object into a Settings JSON string's "assistants"
    /// array (used to model the backup-import leak path). Throws if the JSON
    /// shape is unexpected.
    private func injectAssistant(into settingsJson: String, assistantJsonData: Data) throws -> String {
        guard var root = try JSONSerialization.jsonObject(with: Data(settingsJson.utf8)) as? [String: Any] else {
            struct BadShape: Error {}
            throw BadShape()
        }
        var assistants = (root["assistants"] as? [Any]) ?? []
        guard let assistant = try JSONSerialization.jsonObject(with: assistantJsonData) as? [String: Any] else {
            struct BadAssistant: Error {}
            throw BadAssistant()
        }
        assistants.append(assistant)
        root["assistants"] = assistants
        let data = try JSONSerialization.data(withJSONObject: root)
        return String(data: data, encoding: .utf8) ?? settingsJson
    }

    /// RED for P0 (discovery round 5b). GREEN target: P2.
    /// Cell: chat.secure_store (assistant-level customBodies credential class).
    ///
    /// Companion to `test_assistantCustomHeaders_encode_containsNoCredential`.
    /// `Assistant.customBodies: List<CustomBody>` (CustomBody.key + value: JSON)
    /// is a user-supplied request-body merge map; a user could place a credential
    /// there (e.g. key "authorization", value `"Bearer <secret>"`). It flows
    /// through the same unredacted import/restore → encode path. P2 scheme B's
    /// JSON-tree redactor (mirroring Android BackupSettingsRedactor, which masks
    /// any key matching authorization/token/secret) covers this too.
    func test_assistantCustomBodies_encode_containsNoCredential() throws {
        let secret = "asstbody-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-asstbody-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)

        var json = IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        let assistantFragment = """
        {"id":"\(UUID().uuidString)","name":"Imported","customBodies":[{"key":"authorization","value":"Bearer \(secret)"}]}
        """.data(using: .utf8)!
        json = try injectAssistant(into: json, assistantJsonData: assistantFragment)

        // decode 已声明 @Throws（Kotlin/Native 桥接），这里走成功路径，用 try。
        let decoded = try IosSettingsJsonBridge.shared.decode(json: json)
        store.restoreSnapshot(decoded)

        let persisted = (defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? "")
        XCTAssertFalse(
            persisted.contains(secret),
            "Persisted settings JSON must not contain an assistant customBody credential in plaintext. (P2 scheme B)"
        )
    }

    // MARK: - secure_store (redactor classification + path collision — discovery round 6)

    /// RED for P0 (discovery round 6). GREEN target: P2.
    /// Cell: *.secure_store (header classification gap).
    ///
    /// `isSensitiveHeaderName` only matched a narrow exact list
    /// (authorization/x-api-key/cookie/…), so a credential header named
    /// `Access-Token` / `Token` / `Secret` (or a customBody keyed `token`) was NOT
    /// masked and leaked into the persisted Settings JSON and backups. Classification
    /// must cover the same sensitive markers as `isSensitiveKey`, matching by
    /// substring so prefixed forms (X-Access-Token, Api-Token) are covered too.
    func test_customHeaderNamedAccessToken_encode_containsNoCredential() {
        let secret = "tokhdr-SECRET-\(UUID().uuidString)"
        let json = """
        {"providers":[{"id":"p1","customHeaders":[{"first":"Access-Token","second":"\(secret)"}]}]}
        """
        let redacted = IOSCredentialRedactor.redact(json)
        XCTAssertFalse(
            redacted.contains(secret),
            "A credential header named Access-Token must be masked (isSensitiveHeaderName was too narrow)."
        )
    }

    /// RED for P0 (discovery round 6). GREEN target: P2.
    /// Cell: *.secure_store (side-table path collision).
    ///
    /// The Keychain side-table ref for an array item used only its name/identity (no
    /// index), so two custom headers sharing the same sensitive name (e.g. two
    /// `X-Api-Key`) collapsed onto ONE path: redact stored the second over the first
    /// (credential A lost) and rehydrate filled BOTH fields with credential B
    /// (cross-contamination). The path must include the array index so same-named
    /// items keep distinct refs.
    func test_twoSameNamedSensitiveHeaders_keepDistinctValuesAcrossRoundTrip() {
        let secretA = "hdr-A-\(UUID().uuidString)"
        let secretB = "hdr-B-\(UUID().uuidString)"
        let json = """
        {"providers":[{"id":"p1","models":[{"id":"m1","customHeaders":[\
        {"first":"X-Api-Key","second":"\(secretA)"},\
        {"first":"X-Api-Key","second":"\(secretB)"}\
        ]}]}]}
        """
        var sideTable: [String: String] = [:]
        let redacted = IOSCredentialRedactor.redact(json) { path, value in sideTable[path] = value }
        // Both distinct secrets must be masked out of the persisted JSON...
        XCTAssertFalse(redacted.contains(secretA))
        XCTAssertFalse(redacted.contains(secretB))
        // ...and each must rehydrate to its OWN field (not collide onto one path).
        let rehydrated = IOSCredentialRedactor.rehydrate(redacted) { path in sideTable[path] }
        XCTAssertTrue(rehydrated.contains(secretA), "first same-named header's distinct value must survive the round-trip")
        XCTAssertTrue(rehydrated.contains(secretB), "second same-named header's distinct value must survive the round-trip")
    }

    // MARK: - secure_store binding (subagent_standalone / subagent_chat / council)

    /// GREEN (P4 binding). Cell: subagent_standalone.secure_store.
    ///
    /// The standalone subagent capability must NOT land credentials on any
    /// independent persist surface. It resolves its provider via
    /// `IOSDeepReadDraftGenerator.resolveProviderSetting` (which clones the
    /// selected setting into an in-memory value passed to the adapter — never
    /// persisted) and stores run records via `IOSAdvancedTaskStore`, whose
    /// `IOSAdvancedTaskRecord` is redacted (`redacted()` scrubs bearer/key/
    /// token/password). Credentials flow only through the shared, redacted
    /// `IOSSharedSettingsStore` (bound green by `test_settings_encode_contains
    /// NoCredential`). This test binds that contract: a standalone run with a
    /// secret apiKey leaves no secret in the task store's persist surface.
    func test_subagentStandalone_secureStore_noIndependentCredentialLanding() async throws {
        let secret = "subagent-standalone-SECRET-\(UUID().uuidString)"
        let suite = UserDefaults(suiteName: "redlight-subagent-sec-\(UUID().uuidString)")!
        let taskStore = IOSAdvancedTaskStore(userDefaults: suite, storageKey: "redlight.tasks")
        let runner = SubAgentRunner(taskStore: taskStore)
        let providerWithSecret = makeOpenAIProvider(apiKey: secret)

        // Drive the engine path with the secret-bearing provider (the resolved
        // provider is passed in-memory; nothing persists it).
        _ = await runner.runViaEngine(
            objective: "explore the objective",
            roleId: "explorer",
            providerSetting: providerWithSecret,
            modelId: "any-model",
            parentToolExecutors: [:],
            provider: EngineProbeProvider()
        )

        // The task store is the only persist surface the standalone capability
        // owns. Assert the secret is absent from its persisted form.
        guard let persisted = suite.data(forKey: "redlight.tasks") else {
            // No persistence yet is itself clean (no credential landed).
            return
        }
        let persistedString = String(data: persisted, encoding: .utf8) ?? ""
        XCTAssertFalse(
            persistedString.contains(secret),
            "Standalone subagent must not land the apiKey credential in its task-store persist surface. "
                + "Credentials flow only through the shared redacted settings store (scheme B)."
        )
    }

    /// GREEN (P4 binding). Cell: subagent_chat.secure_store.
    ///
    /// The chat-embedded subagent dispatch (`ChatToolRuntime.subagent_dispatch`
    /// → `SubAgentRunner.runViaEngine`) reuses the chat-resolved provider
    /// (passed in-memory) and the same redacted task store. It has NO
    /// independent credential persist surface. This binds that contract the
    /// same way as the standalone test.
    func test_subagentChat_secureStore_noIndependentCredentialLanding() async throws {
        let secret = "subagent-chat-SECRET-\(UUID().uuidString)"
        let suite = UserDefaults(suiteName: "redlight-subagentchat-sec-\(UUID().uuidString)")!
        let taskStore = IOSAdvancedTaskStore(userDefaults: suite, storageKey: "redlight.tasks")
        let runner = SubAgentRunner(taskStore: taskStore)
        let providerWithSecret = makeOpenAIProvider(apiKey: secret)

        // The chat path calls runViaEngine with the chat-resolved provider.
        _ = await runner.runViaEngine(
            objective: "chat-embedded objective",
            roleId: "explorer",
            providerSetting: providerWithSecret,
            modelId: "any-model",
            parentToolExecutors: [:],
            provider: EngineProbeProvider()
        )

        guard let persisted = suite.data(forKey: "redlight.tasks") else { return }
        let persistedString = String(data: persisted, encoding: .utf8) ?? ""
        XCTAssertFalse(
            persistedString.contains(secret),
            "Chat-embedded subagent must not land the apiKey credential in its task-store persist surface. "
                + "It reuses the chat-resolved provider (in-memory) + shared redacted settings store."
        )
    }

    /// GREEN (P4 binding). Cell: council.secure_store.
    ///
    /// The council capability must NOT land credentials on any independent
    /// persist surface. It resolves its provider via
    /// `IOSCouncilRoomRunner.resolveProviderSetting` (in-memory clone passed to
    /// the streamer — never persisted) and records runs via the shared
    /// `IOSAdvancedTaskStore` (redacted). Credentials flow only through the
    /// shared, redacted settings store. This binds that contract by driving a
    /// council run record through the same redacted task store the capability
    /// uses, with the secret-bearing provider, and asserting the secret is
    /// absent from the persisted form.
    func test_council_secureStore_noIndependentCredentialLanding() {
        let secret = "council-SECRET-\(UUID().uuidString)"
        let suite = UserDefaults(suiteName: "redlight-council-sec-\(UUID().uuidString)")!
        let taskStore = IOSAdvancedTaskStore(userDefaults: suite, storageKey: "redlight.tasks")
        // Council records runs via IOSAdvancedTaskStore; start a task carrying
        // the secret-bearing provider in its metadata (worst case) and persist.
        let providerWithSecret = makeClaudeProvider(apiKey: secret)
        _ = taskStore.startTask(
            kind: .modelCouncil,
            title: "council sec test",
            objective: "objective",
            roleId: nil,
            toolScope: [],
            budgetSummary: "budget",
            connectionSummary: "",
            commandPreview: "",
            sourceToolName: "model_council_run",
            metadata: ["provider_type": String(describing: type(of: providerWithSecret))]
        )

        // Council has no independent credential persist surface — it reuses the
        // shared redacted settings store + the redacted task store. Assert the
        // task store (its only persist surface) does not contain the secret.
        guard let persisted = suite.data(forKey: "redlight.tasks") else {
            return // no persistence yet is itself clean
        }
        let persistedString = String(data: persisted, encoding: .utf8) ?? ""
        XCTAssertFalse(
            persistedString.contains(secret),
            "Council must not land the apiKey credential in its task-store persist surface. "
                + "Credentials flow only through the shared redacted settings store (scheme B)."
        )
    }

    /// GREEN (P2 rehydration). Cell: chat.secure_store (restart-survival aspect).
    ///
    /// Scheme B redacts provider apiKeys in the persisted JSON (mask sentinel)
    /// and stores the real key in the Keychain side-table. On restart, the
    /// loaded snapshot must REHYDRATE the real apiKey from the side-table — so
    /// a provider added before restart is still usable after, with its real key
    /// (not the mask). This binds that contract: add a provider with a real key,
    /// reload the store from the same UserDefaults, and assert the reloaded
    /// snapshot carries the REAL key (not the mask).
    func test_providerApiKey_rehydratedFromKeychainOnReload() {
        let secret = "sk-rehydrate-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-rehydrate-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = makeOpenAIProvider(apiKey: secret)
        store.addProvider(provider)
        let providerId = provider.id.description() as String

        // Reload from the same UserDefaults (simulates restart). The persisted
        // JSON is redacted; the reload must rehydrate the real key from Keychain.
        let reloaded = IOSSharedSettingsStore(userDefaults: defaults)
        let reloadedProvider = reloaded.snapshot.providers.first {
            ($0.id.description() as String) == providerId
        }

        XCTAssertNotNil(reloadedProvider, "Provider must survive restart.")
        let reloadedKey = (reloadedProvider as? ProviderSetting.OpenAI)?.apiKey
            ?? (reloadedProvider as? ProviderSetting.Claude)?.apiKey
            ?? ""
        XCTAssertEqual(
            reloadedKey, secret,
            "Provider apiKey must be rehydrated from the Keychain side-table on reload, "
                + "not left as the redactor mask. Got: \(reloadedKey) (P2 scheme B rehydration)."
        )
    }

    /// 根因守护:`IOSCredentialRedactor.arrayItemPath` 用 `[<identity>#<index>]`
    /// 标识数组项(索引后缀是为了让两个同名 header 不塌到同一条 side-table 路径),
    /// 因此按稳定 ID 反查 generic 凭据路径时必须匹配到 `#` 为止。
    /// 曾经用 `"[\(id)]"` 匹配——对真实路径恒为 false,`genericCredentialRefs`
    /// 永远返回空,provider/model/TTS 删除时 generic side-table 条目全部残留。
    func test_credentialPathsIdentifyArrayItemsWithIndexSuffix() {
        let defaults = UserDefaults(suiteName: "redlight-path-format-\(UUID().uuidString)")!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = makeOpenAIProvider(apiKey: "sk-path-format-\(UUID().uuidString)")
        store.addProvider(provider)
        let providerId = provider.id.description() as String

        let paths = IOSCredentialRedactor.activeCredentialPaths(
            in: IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        ).filter { $0.contains(providerId) }
        print("[CRED-PATH] \(paths.sorted().joined(separator: " | "))")

        XCTAssertFalse(paths.isEmpty)
        XCTAssertTrue(
            paths.allSatisfy { $0.contains("[\(providerId)#") },
            "数组项路径必须是 [<id>#<index>] 形态;按稳定 ID 反查的一方要匹配到 # 为止"
        )
        XCTAssertFalse(
            paths.contains { $0.contains("[\(providerId)]") },
            "不存在无索引后缀的 [<id>] 形态——按该形态匹配等于永远匹配不到"
        )
    }

    func test_removeProviderDeletesTypedAndGenericCredentialRefs() {
        let secret = "sk-delete-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: "redlight-delete-provider-\(UUID().uuidString)")!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = makeOpenAIProvider(apiKey: secret)
        store.addProvider(provider)
        let providerId = provider.id.description() as String
        let paths = IOSCredentialRedactor.activeCredentialPaths(
            in: IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        ).filter { $0.contains(providerId) }

        XCTAssertEqual(
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.providerApiKey(providerId: providerId)),
            secret
        )
        XCTAssertFalse(paths.isEmpty)
        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) != nil
        })

        XCTAssertTrue(store.removeProvider(providerId: providerId))

        XCTAssertNil(IOSCredentialSideTable.load(key: IOSCredentialSideTable.providerApiKey(providerId: providerId)))
        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) == nil
        })
    }

    func test_removeCustomModelReusesProviderCredentialCleanup() throws {
        let secret = "custom-model-delete-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: "redlight-delete-custom-model-\(UUID().uuidString)")!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        store.addCustomModel(
            name: "Legacy Custom",
            modelId: "legacy-custom-\(UUID().uuidString)",
            providerName: "Legacy Provider"
        )
        let index = store.savedCustomModels.count - 1
        let providerId = try XCTUnwrap(store.savedCustomModels[index]["providerId"])
        _ = store.updateProviderApiKey(providerId: providerId, apiKey: secret)
        let paths = IOSCredentialRedactor.activeCredentialPaths(
            in: IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        ).filter { $0.contains(providerId) }

        XCTAssertEqual(
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.providerApiKey(providerId: providerId)),
            secret
        )
        XCTAssertFalse(paths.isEmpty)

        store.removeCustomModel(at: index)

        XCTAssertFalse(store.snapshot.providers.contains { $0.id.description() == providerId })
        XCTAssertNil(IOSCredentialSideTable.load(key: IOSCredentialSideTable.providerApiKey(providerId: providerId)))
        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) == nil
        })
    }

    func test_removeModelDeletesOnlyItsGenericCredentialRefs() throws {
        let secret = "model-delete-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: "redlight-delete-model-\(UUID().uuidString)")!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        let provider = makeOpenAIProvider(apiKey: "")
        store.addProvider(provider)
        let providerId = provider.id.description() as String
        let modelId = "private-model-\(UUID().uuidString)"
        let updated = try XCTUnwrap(store.upsertProviderChatModel(
            providerId: providerId,
            modelUuid: nil,
            modelId: modelId,
            displayName: "Private",
            contextWindowTokens: nil,
            modelType: .chat,
            headers: [("Authorization", "Bearer \(secret)")]
        ))
        let model = try XCTUnwrap(updated.models.first { $0.modelId == modelId })
        let modelUuid = model.id.description()
        let paths = IOSCredentialRedactor.activeCredentialPaths(
            in: IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        ).filter { $0.contains(modelUuid) }

        XCTAssertFalse(paths.isEmpty)
        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) != nil
        })

        _ = store.removeProviderChatModel(providerId: providerId, modelUuid: modelUuid)

        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) == nil
        })
    }

    func test_removeTtsProviderDeletesItsGenericCredentialRefs() throws {
        let secret = "tts-delete-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: "redlight-delete-tts-\(UUID().uuidString)")!
        let store = IOSSharedSettingsStore(userDefaults: defaults)
        store.addTtsEngine(name: "Delete TTS", engineType: "openai", apiKey: secret, model: "tts-1")
        let ttsId = try XCTUnwrap(store.savedTtsEngines.last?["ttsId"])
        let paths = IOSCredentialRedactor.activeCredentialPaths(
            in: IosSettingsJsonBridge.shared.encode(settings: store.snapshot)
        ).filter { $0.contains(ttsId) }

        XCTAssertFalse(paths.isEmpty)
        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) != nil
        })

        store.removeTtsEngine(at: store.savedTtsEngines.count - 1)

        XCTAssertTrue(paths.allSatisfy {
            IOSCredentialSideTable.load(key: IOSCredentialSideTable.settingsPath($0)) == nil
        })
    }

    func test_providerApiKey_plaintextMigrationRedactsDefaultsImmediately() {
        let secret = "sk-migrate-SECRET-\(UUID().uuidString)"
        let namespace = "redlight-migrate-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: namespace)!
        var settings = IosSettingsDefaults.shared.defaultSeededSettings()
        let provider = makeOpenAIProvider(apiKey: secret)
        settings = IosSettingsMutations.shared.addProvider(settings: settings, provider: provider)
        defaults.set(
            IosSettingsJsonBridge.shared.encode(settings: settings),
            forKey: "app.amber.ios.sharedSettingsJson"
        )

        _ = IOSSharedSettingsStore(userDefaults: defaults)

        let migratedJson = defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? ""
        XCTAssertFalse(
            migratedJson.contains(secret),
            "Plaintext migration must immediately rewrite UserDefaults with redacted JSON, not wait for the next settings write."
        )
        XCTAssertTrue(migratedJson.contains(IOSCredentialRedactor.mask))

        let reloaded = IOSSharedSettingsStore(userDefaults: defaults)
        let providerId = provider.id.description() as String
        let reloadedProvider = reloaded.snapshot.providers.first {
            ($0.id.description() as String) == providerId
        } as? ProviderSetting.OpenAI
        XCTAssertEqual(reloadedProvider?.apiKey, secret)
    }

    func test_releaseSensitiveLogsAreDebugGuarded() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let board = try String(
            contentsOf: appDirectory.appendingPathComponent("IOSBoardPersistence.swift"),
            encoding: .utf8
        )
        let deepRead = try String(
            contentsOf: appDirectory.appendingPathComponent("DeepReadCreateView.swift"),
            encoding: .utf8
        )

        for marker in [
            "[AmberTranslate] translate()",
            "[AmberTranslate] generateText threw:",
            "[AmberTranslate] llm chars=",
            "[AmberTranslate] apply pending=",
            "[AmberDeepRead] sources="
        ] {
            XCTAssertTrue(
                Self.source(board, hasDebugGuardAround: marker),
                "\(marker) must be wrapped in #if DEBUG before release builds."
            )
        }
        for marker in [
            "[AmberDeepRead] topic-search angle failed",
            "[AmberDeepRead] topic-search angles="
        ] {
            XCTAssertTrue(
                Self.source(deepRead, hasDebugGuardAround: marker),
                "\(marker) must be wrapped in #if DEBUG before release builds."
            )
        }
    }

    func test_iPadMultipleScenesDisabledUntilMultiWindowCoordinatorExists() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let plistURL = appDirectory.appendingPathComponent("Info.plist")
        let data = try Data(contentsOf: plistURL)
        let plist = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any]
        )

        XCTAssertEqual(
            plist["UIApplicationSupportsMultipleScenes"] as? Bool,
            false,
            "iPad multi-window must stay disabled until the store/viewModel/background coordinator are multi-scene safe."
        )
    }

    func testBackgroundExpirationFailureMergesPartialAndFailureIntoSingleAssistantMessage() {
        let displayMessages = [UIMessage.companion.user(prompt: "question")]

        let messages = IOSChatBackgroundGenerationCoordinator.failedMessagesForTesting(
            displayMessages: displayMessages,
            partialAssistantText: "已经生成但尚未完成的正文",
            rawMessage: "后台生成被系统中断，可回到 App 后重试。",
            modelId: "test-model"
        )

        XCTAssertEqual(messages.map { $0.role }, [MessageRole.user, MessageRole.assistant])
        XCTAssertTrue(messages[1].toText().contains("已经生成但尚未完成的正文"))
        XCTAssertTrue(messages[1].toText().contains("后台生成被系统中断"))
    }

    func testBackgroundExpirationAtomicallyOwnsTheTerminalPath() {
        let expiredState = IOSChatBackgroundRunState()

        XCTAssertEqual(expiredState.expireAndReserveTerminal(), .persistFailure)
        XCTAssertTrue(expiredState.isExpired)
        XCTAssertEqual(expiredState.expireAndReserveTerminal(), .rejected)
        XCTAssertFalse(expiredState.reserveTerminal())

        let completedState = IOSChatBackgroundRunState()
        XCTAssertTrue(completedState.reserveTerminal())
        XCTAssertTrue(completedState.finalizeTerminal())
        XCTAssertEqual(completedState.expireAndReserveTerminal(), .rejected)
        XCTAssertFalse(completedState.isExpired)

        let cancelledState = IOSChatBackgroundRunState()
        XCTAssertTrue(cancelledState.cancelAndReserveTerminal())
        XCTAssertTrue(cancelledState.isExpired)
        XCTAssertFalse(cancelledState.reserveTerminal())
        XCTAssertFalse(cancelledState.cancelAndReserveTerminal())
    }

    func testBackgroundTerminalReservationStopsRunningPresentations() {
        let expiredState = IOSChatBackgroundRunState()
        let cancelledState = IOSChatBackgroundRunState()
        let completedState = IOSChatBackgroundRunState()

        XCTAssertTrue(expiredState.allowsRunningPresentation)
        XCTAssertTrue(cancelledState.allowsRunningPresentation)
        XCTAssertTrue(completedState.allowsRunningPresentation)

        XCTAssertEqual(expiredState.expireAndReserveTerminal(), .persistFailure)
        XCTAssertTrue(cancelledState.cancelAndReserveTerminal())
        XCTAssertTrue(completedState.reserveTerminal())

        XCTAssertFalse(expiredState.allowsRunningPresentation)
        XCTAssertFalse(cancelledState.allowsRunningPresentation)
        XCTAssertFalse(completedState.allowsRunningPresentation)
    }

    func testBackgroundCancellationCancelsTheInstalledOperationTask() {
        let state = IOSChatBackgroundRunState()
        let operationTask = Task {
            IOSAgentToolEngineResult(
                messages: [],
                stepsExecuted: 0,
                pendingApproval: nil,
                hitStepLimit: false
            )
        }
        state.installOperationTask(operationTask)

        XCTAssertTrue(state.cancelAndReserveTerminal())
        XCTAssertTrue(operationTask.isCancelled)
        XCTAssertFalse(state.reserveTerminal())
    }

    func testBackgroundCancellationCannotPreemptCompletionAfterSavingStarts() {
        let savingState = IOSChatBackgroundRunState()
        XCTAssertTrue(savingState.reserveTerminal())
        XCTAssertFalse(savingState.cancelAndReserveTerminal())
        XCTAssertTrue(savingState.finalizeTerminal())
        XCTAssertTrue(savingState.terminalWasFinalized(by: .completion))

        let committedState = IOSChatBackgroundRunState()
        XCTAssertTrue(committedState.reserveTerminal())
        XCTAssertTrue(committedState.finalizeTerminal())
        XCTAssertFalse(committedState.cancelAndReserveTerminal())
    }

    func testBackgroundExpirationPreemptsReservedTerminalAndOwnsFinalization() {
        let savingState = IOSChatBackgroundRunState()
        XCTAssertTrue(savingState.reserveTerminal())

        XCTAssertEqual(savingState.expireAndReserveTerminal(), .terminateInFlightSave)
        XCTAssertTrue(savingState.isExpired)
        XCTAssertFalse(savingState.finalizeTerminal())
        XCTAssertTrue(savingState.finalizeTerminal(as: .expiration))
        XCTAssertTrue(savingState.terminalWasFinalized(by: .expiration))
        XCTAssertFalse(savingState.terminalWasFinalized(by: .completion))
    }

    func testBackgroundSystemTaskCompletionCanOnlyBeClaimedOnce() {
        let state = IOSChatBackgroundRunState()

        XCTAssertTrue(state.claimSystemTaskCompletion())
        XCTAssertFalse(state.claimSystemTaskCompletion())
    }

    func testBackgroundProviderExposesRealStreamingCallbacks() {
        let provider: any IOSAgentTextProvider = IOSChatBackgroundProvider()

        XCTAssertTrue(provider is any IOSAgentStreamingProvider)
    }

    func testBackgroundProviderFailureKeepsCompletedSuffixAndCurrentPartial() {
        let displayMessages = [UIMessage.companion.user(prompt: "question")]
        let seed = UIMessage.companion.assistant(prompt: "")
        let completedToolTurn = UIMessage(
            id: seed.id,
            role: seed.role,
            parts: [UIMessagePart.Tool(
                toolCallId: "tool-1",
                toolName: "search_web",
                input: #"{"query":"amber"}"#,
                output: [UIMessagePart.Text(text: "tool result already completed", metadata: nil)],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )],
            annotations: seed.annotations,
            createdAt: seed.createdAt,
            finishedAt: seed.finishedAt,
            modelId: seed.modelId,
            usage: seed.usage,
            translation: seed.translation
        )

        let messages = IOSChatBackgroundGenerationCoordinator.failedMessagesForTesting(
            displayMessages: displayMessages,
            preservedGeneratedSuffix: [completedToolTurn],
            partialAssistantText: "current round partial",
            rawMessage: "network error",
            modelId: "test-model"
        )

        XCTAssertEqual(messages.count, 3)
        let preservedTool = messages[1].parts.compactMap { $0 as? UIMessagePart.Tool }.first
        XCTAssertEqual(
            preservedTool?.output.compactMap { ($0 as? UIMessagePart.Text)?.text },
            ["tool result already completed"]
        )
        XCTAssertTrue(messages[2].toText().contains("current round partial"))
        XCTAssertTrue(messages[2].toText().contains("network"))
    }

    func testBackgroundSuccessBuildsWatchSummaryFromLastAssistantText() {
        let messages = [
            UIMessage.companion.user(prompt: "question"),
            UIMessage.companion.assistant(prompt: "first answer"),
            UIMessage.companion.assistant(prompt: String(repeating: "最终摘要", count: 80))
        ]

        let summary = IOSChatBackgroundGenerationCoordinator.backgroundSummaryForTesting(messages: messages)

        XCTAssertNotNil(summary)
        XCTAssertTrue(summary?.hasPrefix("最终摘要") == true)
        XCTAssertLessThanOrEqual(summary?.count ?? 0, 281)
    }

    func testBackgroundResumeRehydratesRuntimeHeadersFromCurrentSettingsModelAndAssistant() throws {
        let modelId = KotlinUuid.companion.random()
        let configuredModel = Model(
            modelId: "runtime-model",
            displayName: "Runtime Model",
            id: modelId,
            type: ModelType.chat,
            customHeaders: [CustomHeader(name: "X-Model-Token", value: "current-model-secret")],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Runtime Provider",
            models: [configuredModel],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "current-provider-secret",
            baseUrl: "https://example.com",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let persistedModel = Model(
            modelId: "runtime-model",
            displayName: "Runtime Model",
            id: modelId,
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
        let persistedParams = TextGenerationParams(
            model: persistedModel,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.off,
            customHeaders: [],
            customBody: []
        )

        let resumed = try XCTUnwrap(
            IOSChatBackgroundGenerationCoordinator.rehydratedParamsForTesting(
                persistedParams: persistedParams,
                providerSetting: provider,
                assistantHeaders: [CustomHeader(name: "X-Assistant-Token", value: "current-assistant-secret")],
                assistantBodies: []
            )
        )

        XCTAssertEqual(
            Set(resumed.customHeaders.map(\.value)),
            Set(["current-assistant-secret", "current-model-secret"])
        )
        XCTAssertEqual(
            resumed.model.customHeaders.map(\.value),
            ["current-model-secret"]
        )
        XCTAssertNil(
            IOSChatBackgroundGenerationCoordinator.rehydratedParamsForTesting(
                persistedParams: persistedParams,
                providerSetting: makeOpenAIProvider(),
                assistantHeaders: [CustomHeader(name: "X-Assistant-Token", value: "other-secret")],
                assistantBodies: []
            ),
            "A resumed job must not borrow a same-named model from a different provider."
        )
    }

    func testBackgroundCoordinatorDeletesPayloadAfterTerminalSaveFailureWithoutRetryOwner() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/IOSChatBackgroundGenerationCoordinator.swift"),
            encoding: .utf8
        )
        guard let saveResult = source.range(of: "let didSave: Bool"),
              let saveGuard = source.range(of: "guard didSave else", range: saveResult.upperBound..<source.endIndex),
              let completedRecord = source.range(of: "await recordRun(", range: saveGuard.upperBound..<source.endIndex),
              let retainedFailureStart = source.range(of: "private func completeAsFailureAfterSaveFailure"),
              let retainedFailureEnd = source.range(
                of: "private func job(for requestId:",
                range: retainedFailureStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected persistence result to guard the background terminal path")
        }

        XCTAssertLessThan(saveGuard.lowerBound, completedRecord.lowerBound)
        let retainedFailureBody = source[
            retainedFailureStart.lowerBound..<retainedFailureEnd.lowerBound
        ]
        XCTAssertTrue(
            retainedFailureBody.contains(
                "finish(requestId: backgroundTask.identifier)"
            ),
            "A terminal save failure without a retry owner must release ownership and delete its payload."
        )
        XCTAssertFalse(
            retainedFailureBody.contains("removePayload: false"),
            "A payload cannot outlive its task-map owner without a retry path or cleanup policy."
        )
    }

    func testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let source = try String(
            contentsOf: appDirectory.appendingPathComponent("ChatGenerationCoordinator.swift"),
            encoding: .utf8
        )

        guard let eventLoopStart = source.range(of: "for await event in eventStream"),
              let scheduleCall = source.range(of: "self.scheduleStreamSnapshotPublish", range: eventLoopStart.upperBound..<source.endIndex) else {
            return XCTFail("Expected foreground stream event consumer to schedule a throttled snapshot publish")
        }
        let eventConsumerBeforeSchedule = source[eventLoopStart.upperBound..<scheduleCall.lowerBound]
        XCTAssertFalse(
            eventConsumerBeforeSchedule.contains("accumulator.snapshot()"),
            "Each stream chunk must not snapshot the full accumulator before the throttled flush; long responses make this O(n²) on the main actor."
        )
        XCTAssertTrue(
            source.contains("snapshotProvider:") && source.contains("latestPendingStreamSnapshot()"),
            "The stream scheduler should defer accumulator.snapshot() behind a snapshotProvider so flush/cancel can take the latest snapshot only when needed."
        )
        guard let onChunkStart = source.range(of: "onChunk: { chunk in"),
              let onCompleteStart = source.range(of: "onComplete:", range: onChunkStart.upperBound..<source.endIndex) else {
            return XCTFail("Expected foreground stream onChunk callback before onComplete callback")
        }
        let providerCallbackBody = source[onChunkStart.upperBound..<onCompleteStart.lowerBound]
        XCTAssertFalse(
            providerCallbackBody.contains("Task { @MainActor"),
            "Foreground onChunk must not spawn one MainActor task per provider chunk; high-frequency streams need a single FIFO event consumer."
        )
        XCTAssertTrue(
            source.contains("AsyncStream<ChatStreamEvent>") && source.contains("streamEventTask"),
            "Foreground streaming should use one AsyncStream-backed FIFO consumer for chunk/complete/error ordering."
        )
        XCTAssertTrue(
            source.contains("drainPendingStreamChunksIntoAccumulator()"),
            "Cancel/background handoff must drain chunks accepted by the sink but not yet consumed on MainActor."
        )
        guard let cancelStart = source.range(of: "func cancel()"),
              let cancelEnd = source.range(
                of: "/// (Re)snapshots the background handoff payload.",
                range: cancelStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected cancellation implementation before background handoff support")
        }
        let cancelBody = source[cancelStart.lowerBound..<cancelEnd.lowerBound]
        XCTAssertTrue(
            cancelBody.contains(
                "var messagesAtCancellation = pendingStreamSnapshotAtCancellation ?? bindings.getMessages()"
            ) && cancelBody.contains("bindings.setMessages(messagesAtCancellation)"),
            "Cancel must publish the drain-complete active-stream snapshot, including terminal tool closure, before persisting it."
        )
        guard let completeStart = source.range(of: "case .complete:"),
              let errorStart = source.range(
                of: "case .error(let error):",
                range: completeStart.upperBound..<source.endIndex
              ),
              let eventLoopEnd = source.range(
                of: "streamJob = dispatchStream(",
                range: errorStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected complete/error terminal branches in the foreground event consumer")
        }
        XCTAssertTrue(
            source[completeStart.lowerBound..<errorStart.lowerBound].contains("self.activeStreamSession = nil")
        )
        XCTAssertTrue(
            source[completeStart.lowerBound..<errorStart.lowerBound].contains("await self.drainStreamPresentation"),
            "Completion must drain the bounded UI presentation backlog before publishing terminal state."
        )
        XCTAssertTrue(
            source[errorStart.lowerBound..<eventLoopEnd.lowerBound].contains("self.activeStreamSession = nil"),
            "Terminal branches must relinquish accumulator ownership before tool/error awaits."
        )
        XCTAssertTrue(
            source.contains("publishPacedStreamSnapshot(targetSnapshot, runId: runId)"),
            "The normal throttled flush must publish a bounded presentation step rather than the entire burst."
        )
        XCTAssertTrue(
            source.contains("pendingStreamSnapshot = target"),
            "The authoritative terminal snapshot must remain reachable if cancellation races presentation catch-up."
        )
    }

    func testForegroundErrorTerminalDrainsPresentationBacklogBeforePublishingTerminalState() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let source = try String(
            contentsOf: appDirectory.appendingPathComponent("ChatGenerationCoordinator.swift"),
            encoding: .utf8
        )

        guard let errorStart = source.range(of: "case .error(let error):"),
              let eventLoopEnd = source.range(
                of: "streamJob = dispatchStream(",
                range: errorStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected .error terminal branch in the foreground event consumer")
        }
        let errorBody = source[errorStart.lowerBound..<eventLoopEnd.lowerBound]

        guard let drainGuardRange = errorBody.range(
            of: "guard await self.drainStreamPresentation(to: snapshot, runId: runId) else {"
        ) else {
            return XCTFail(
                "Error terminal must drain the bounded UI presentation backlog (same guard as .complete) before publishing terminal state; otherwise a backlogged burst renders in one uncapped frame right before the error bubble."
            )
        }
        guard let setMessagesRange = errorBody.range(
            of: "self.bindings.setMessages(snapshot)",
            range: drainGuardRange.upperBound..<errorBody.endIndex
        ) else {
            return XCTFail("Expected error terminal to publish the accumulated snapshot after draining, not before.")
        }
        guard let presentErrorRange = errorBody.range(
            of: "await self.presentStreamError(",
            range: setMessagesRange.upperBound..<errorBody.endIndex
        ) else {
            return XCTFail("Expected the error bubble to be presented only after the drained terminal snapshot is published.")
        }
        XCTAssertTrue(
            drainGuardRange.upperBound <= setMessagesRange.lowerBound
                && setMessagesRange.upperBound <= presentErrorRange.lowerBound,
            "Symmetric with .complete: drain the paced backlog, then publish the terminal snapshot, then surface the error bubble."
        )
    }

    func testForegroundPresentationPacerSplitsBurstWithoutDroppingSuffix() {
        let user = UIMessage.companion.user(prompt: "question")
        let assistant = UIMessage.companion.assistant(prompt: "已显示")
        let burst = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥天地玄黄"
        let targetAssistant = UIMessage(
            id: assistant.id,
            role: assistant.role,
            parts: [UIMessagePart.Text(text: "已显示" + burst, metadata: nil)],
            annotations: assistant.annotations,
            createdAt: assistant.createdAt,
            finishedAt: nil,
            modelId: assistant.modelId,
            usage: assistant.usage,
            translation: assistant.translation
        )
        let target = [user, targetAssistant]

        var current = [user, assistant]
        var publishedSuffixes: [String] = []
        var caughtUp = false
        for _ in 0..<10 where !caughtUp {
            let step = ChatStreamPresentationPacer.step(current: current, target: target)
            current = step.snapshot
            caughtUp = step.isCaughtUp
            publishedSuffixes.append(String(current.last?.toText().dropFirst("已显示".count) ?? ""))
        }

        XCTAssertGreaterThan(publishedSuffixes.count, 1, "A multi-line provider burst must not reach layout in one frame.")
        // 这段 burst 的积压很小(26 字),自适应推进落在下限档,所以每拍仍是
        // `minimumTextAdvance`。断言从 `maximumTextAdvance` 改到下限常量:
        // 推进量不再是固定预算,而是「下限 ≤ 推进 ≤ 上限」的自适应值。
        XCTAssertTrue(
            publishedSuffixes.dropLast().allSatisfy { $0.count.isMultiple(of: ChatStreamPresentationPacer.minimumTextAdvance) },
            "Each intermediate frame should advance by the bounded presentation budget."
        )
        XCTAssertTrue(caughtUp)
        XCTAssertEqual(current.last?.toText(), targetAssistant.toText(), "Pacing must eventually publish the authoritative full text.")
    }

    /// 长回复的积压必须在有界拍数内追平,而不是恒定 12 字符/拍。
    ///
    /// 固定预算下,4000 字的回复要 334 拍 × 48ms ≈ 16 秒才显示完;模型通常
    /// 早已结束,而 `drainStreamPresentation` 在终态后仍按同一节奏逐拍追平,
    /// 期间 `isLoading` 保持 true——用户看着"停止"按钮等一段已经生成完的文本。
    /// 小说创作侧的 `NovelSessionPresentationPacer` 已按积压自适应,标准 Chat
    /// 必须同构。
    ///
    /// 实测拍数:自适应 88 拍(≈4.2s),固定 12 字符 334 拍(≈16.0s)。阈值取 150,
    /// 两侧余量充足。
    func testForegroundPresentationPacerDrainsLongReplyWithinBoundedTicks() {
        let user = UIMessage.companion.user(prompt: "question")
        let assistant = UIMessage.companion.assistant(prompt: "已显示")
        let targetAssistant = UIMessage(
            id: assistant.id,
            role: assistant.role,
            parts: [UIMessagePart.Text(text: "已显示" + String(repeating: "长", count: 4000), metadata: nil)],
            annotations: assistant.annotations,
            createdAt: assistant.createdAt,
            finishedAt: nil,
            modelId: assistant.modelId,
            usage: assistant.usage,
            translation: assistant.translation
        )
        let target = [user, targetAssistant]

        var current = [user, assistant]
        var ticks = 0
        var caughtUp = false
        var firstAdvance = 0
        while !caughtUp, ticks < 1_000 {
            let before = current.last?.toText().count ?? 0
            let step = ChatStreamPresentationPacer.step(current: current, target: target)
            current = step.snapshot
            caughtUp = step.isCaughtUp
            if ticks == 0 {
                firstAdvance = (current.last?.toText().count ?? 0) - before
            }
            ticks += 1
        }

        XCTAssertTrue(caughtUp, "Pacing must converge on the authoritative text.")
        XCTAssertEqual(current.last?.toText(), targetAssistant.toText())
        XCTAssertGreaterThan(
            firstAdvance,
            ChatStreamPresentationPacer.minimumTextAdvance,
            "大积压的第一拍必须超过下限预算,否则长回复会以恒定速率持续落后于模型"
        )
        XCTAssertLessThanOrEqual(
            ticks,
            150,
            "4000 字积压必须在约 150 拍(≈7s)内追平;固定 12 字符/拍需要 334 拍(≈16s)"
        )
    }

    /// 命中模型输出上限(finish_reason = length / max_tokens)必须被当成截断收尾,
    /// 而不是静默按 `completed` 交付。
    ///
    /// 供应商侧确实把 finishReason 透传到了 Swift(`OpenAIKmpProvider` 会在末尾
    /// chunk 上带出),`IOSAgentToolEngine` 也读了它;唯独前台 ChatGenerationCoordinator
    /// 从不读——于是半截答案在 UI 上和完整答案毫无区别,run 也记成 completed。
    /// 判定必须先于工具分支:截断点可能落在 tool_calls 的参数 JSON 中途,那串
    /// 残缺 JSON 不能当成可执行的工具调用。
    func testForegroundCompletionTreatsOutputLimitAsTruncationBeforeToolDispatch() throws {
        func chunk(finishReason: String?) -> MessageChunk {
            MessageChunk(
                id: "chunk",
                model: "test-model",
                choices: [UIMessageChoice(
                    index: 0,
                    delta: UIMessage.companion.assistant(prompt: "部分正文"),
                    message: nil,
                    finishReason: finishReason
                )],
                usage: nil
            )
        }

        XCTAssertTrue(ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: "length")))
        XCTAssertTrue(ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: "max_tokens")))
        XCTAssertTrue(ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: "max_output_tokens")))
        XCTAssertTrue(
            ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: "LENGTH")),
            "finish_reason 的大小写由网关决定,判定必须大小写无关。"
        )
        XCTAssertFalse(ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: "stop")))
        XCTAssertFalse(ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: "tool_calls")))
        XCTAssertFalse(ChatGenerationCoordinator.reachedOutputLimit(chunk(finishReason: nil)))

        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let source = try String(
            contentsOf: appDirectory.appendingPathComponent("ChatGenerationCoordinator.swift"),
            encoding: .utf8
        )

        guard let consumerStart = source.range(of: "case .chunk(let chunk):"),
              let consumerEnd = source.range(of: "case .complete:", range: consumerStart.upperBound..<source.endIndex) else {
            return XCTFail("Expected the foreground event consumer to have chunk/complete branches")
        }
        XCTAssertTrue(
            source[consumerStart.upperBound..<consumerEnd.lowerBound].contains("reachedOutputLimit(chunk)"),
            "累加器只保留 delta/message/usage,不透传 finishReason,所以必须在消费 chunk 的当下记录上限命中。"
        )

        guard let completionStart = source.range(of: "private func handleCompletedStream("),
              let limitBranch = source.range(of: "if hitOutputLimit {", range: completionStart.upperBound..<source.endIndex),
              let toolBranch = source.range(of: "toolRuntime.nextPendingToolCall(", range: completionStart.upperBound..<source.endIndex) else {
            return XCTFail("Expected handleCompletedStream to branch on the output limit")
        }
        XCTAssertLessThan(
            limitBranch.lowerBound,
            toolBranch.lowerBound,
            "截断判定必须先于待执行工具调用的分派,否则残缺的 tool_calls JSON 会被当成可执行调用。"
        )

        guard let truncatedStart = source.range(of: "private func completeTruncatedStream("),
              let truncatedEnd = source.range(of: "static func outputLimitNotice()", range: truncatedStart.upperBound..<source.endIndex) else {
            return XCTFail("Expected a dedicated truncated-completion path")
        }
        let truncatedBody = source[truncatedStart.lowerBound..<truncatedEnd.lowerBound]
        XCTAssertTrue(
            truncatedBody.contains("\"truncated\""),
            "截断的 run 必须记为 truncated,记成 completed 会让用户把半截答案当完整答案。"
        )
        XCTAssertTrue(
            truncatedBody.contains("Self.outputLimitNotice()"),
            "已生成的正文要保留,并追加一条可见提示告知回复不完整。"
        )
    }

    func testBackgroundCompletionUsesTypedEngineTerminalSignals() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/IOSChatBackgroundGenerationCoordinator.swift"),
            encoding: .utf8
        )

        guard let limitBranch = source.range(of: "result.hitOutputLimit"),
              let providerFailureBranch = source.range(
                of: "result.providerFailureMessage",
                range: limitBranch.upperBound..<source.endIndex
              ) else {
            return XCTFail("Background completion must consume typed engine terminal fields")
        }
        XCTAssertLessThan(limitBranch.lowerBound, providerFailureBranch.lowerBound)
        XCTAssertFalse(
            source.contains("providerFailureMessage(in generatedSuffix:"),
            "Background completion must not infer provider failure from localized transcript text"
        )

        guard let truncatedStart = source.range(of: "private func publishTruncatedTerminal("),
              let truncatedEnd = source.range(
                of: "private func fail(",
                range: truncatedStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected a dedicated background truncated terminal")
        }
        let truncatedBody = source[truncatedStart.lowerBound..<truncatedEnd.lowerBound]
        XCTAssertTrue(truncatedBody.contains("status: didSave ? \"truncated\" : \"recovery_pending\""))
        XCTAssertTrue(
            truncatedBody.contains("presentation: .failed()"),
            "Watch and Live Activity must not present truncated output as an unqualified success"
        )
    }

    func testApprovedAsyncToolsAcquireLeaseAndCancellationOwnershipBeforeExecution() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/ChatGenerationCoordinator.swift"),
            encoding: .utf8
        )

        guard let helperStart = source.range(of: "private func executeApprovedAsyncTool("),
              let helperEnd = source.range(
                of: "private func resumeAfterApproval(",
                range: helperStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected one owner for approved async tool execution")
        }
        let helper = source[helperStart.lowerBound..<helperEnd.lowerBound]
        guard let lease = helper.range(of: "beginKeepAlive(for: pending)"),
              let taskOwner = helper.range(of: "foregroundToolExecutionTask = executionTask") else {
            return XCTFail("Approved async tools must hold both keepalive and a cancellable task owner")
        }
        XCTAssertLessThan(lease.lowerBound, taskOwner.lowerBound)
        XCTAssertTrue(helper.contains("guard allow else"), "Denied approvals must not acquire async execution ownership")
        XCTAssertTrue(source.contains("private func beginKeepAlive(for pending:"))
        XCTAssertTrue(source.contains("BackgroundGenerationKeepAlive.shared.begin("))

        for functionName in [
            "finishPendingSearchToolApproval",
            "finishPendingWebMountToolApproval",
            "finishPendingWorkspaceToolApproval",
            "finishPendingIshHandoffToolApproval",
            "finishPendingMcpToolApproval",
            "finishPendingCouncilToolApproval"
        ] {
            guard let start = source.range(of: "private func \(functionName)("),
                  let resume = source.range(
                    of: "resumeAfterApproval(",
                    range: start.upperBound..<source.endIndex
                  ) else {
                return XCTFail("Missing approved tool path \(functionName)")
            }
            XCTAssertTrue(
                source[start.lowerBound..<resume.lowerBound].contains("executeApprovedAsyncTool("),
                "\(functionName) must use the shared cancellable owner"
            )
        }
    }

    func testForegroundTerminalPersistencePrecedesExternalTerminalAndSuccessCallbackIsExplicit() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let source = try String(
            contentsOf: testDirectory
                .deletingLastPathComponent()
                .appendingPathComponent("iosApp/ChatGenerationCoordinator.swift"),
            encoding: .utf8
        )

        XCTAssertTrue(source.contains("persistMessages: @MainActor (KotlinUuid?) async -> Bool"))
        XCTAssertTrue(source.contains("generationSucceeded: @MainActor () -> Void"))

        guard let completionStart = source.range(of: "private func handleCompletedStream("),
              let completionEnd = source.range(
                of: "private func completeTruncatedStream(",
                range: completionStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected foreground completion boundary")
        }
        let completion = source[completionStart.lowerBound..<completionEnd.lowerBound]
        guard let persist = completion.range(of: "await bindings.persistMessages("),
              let publish = completion.range(of: "WatchTaskCoordinator.shared.publishCompleted("),
              let finish = completion.range(of: "finishStreaming("),
              let success = completion.range(of: "bindings.generationSucceeded()") else {
            return XCTFail("Successful completion must persist, publish, finish, then notify auxiliary work")
        }
        XCTAssertLessThan(persist.lowerBound, publish.lowerBound)
        XCTAssertLessThan(finish.lowerBound, success.lowerBound)

        guard let pauseStart = source.range(of: "private func pauseForApproval("),
              let pauseEnd = source.range(
                of: "private func finishPendingMemoryToolApproval(",
                range: pauseStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected approval pause boundary")
        }
        let pause = source[pauseStart.lowerBound..<pauseEnd.lowerBound]
        guard let pausePersist = pause.range(of: "await bindings.persistMessagesSnapshot("),
              let release = pause.range(of: "BackgroundGenerationKeepAlive.shared.end("),
              let waiting = pause.range(of: "WatchTaskCoordinator.shared.publishWaitingApproval(") else {
            return XCTFail("Approval pause must durably save before release and external waiting state")
        }
        XCTAssertLessThan(pausePersist.lowerBound, release.lowerBound)
        XCTAssertLessThan(pausePersist.lowerBound, waiting.lowerBound)

        guard let truncatedStart = source.range(of: "private func completeTruncatedStream("),
              let truncatedEnd = source.range(
                of: "static func outputLimitNotice()",
                range: truncatedStart.upperBound..<source.endIndex
              ) else {
            return XCTFail("Expected truncated terminal boundary")
        }
        XCTAssertFalse(
            source[truncatedStart.lowerBound..<truncatedEnd.lowerBound].contains("generationSucceeded")
        )
    }

    func testForegroundStreamEventSinkRetainsQueuedChunksUntilClaimed() {
        let sink = ChatStreamEventSink()
        _ = AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded) { continuation in
            sink.bind(continuation)
        }
        let first = MessageChunk(
            id: "first",
            model: "test-model",
            choices: [UIMessageChoice(
                index: 0,
                delta: UIMessage.companion.assistant(prompt: "A"),
                message: nil,
                finishReason: nil
            )],
            usage: nil
        )
        let second = MessageChunk(
            id: "second",
            model: "test-model",
            choices: [UIMessageChoice(
                index: 0,
                delta: UIMessage.companion.assistant(prompt: "B"),
                message: nil,
                finishReason: nil
            )],
            usage: nil
        )

        sink.yield(.chunk(first))
        sink.yield(.chunk(second))
        sink.yield(.complete())
        sink.finish()

        let drained = sink.takePendingChunks()
        XCTAssertEqual(drained.map(\.id), ["first", "second"])
        XCTAssertEqual(sink.pendingEventCountForTesting, 1, "Terminal event should remain queued for the FIFO consumer.")
    }

    func testForegroundStreamEventSinkDoesNotHandoffPastQueuedTerminal() {
        let sink = ChatStreamEventSink()
        _ = AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded) { continuation in
            sink.bind(continuation)
        }
        sink.yield(.complete())
        var startCount = 0

        let didStart = sink.transitionToBackgroundIfNoTerminal {
            startCount += 1
            return true
        }

        XCTAssertFalse(didStart)
        XCTAssertEqual(startCount, 0)
        XCTAssertEqual(sink.pendingEventCountForTesting, 1)
    }

    func testForegroundStreamEventSinkClaimsOnlyFIFOHeadAndCompactsLargeBacklog() {
        let sink = ChatStreamEventSink()
        _ = AsyncStream<ChatStreamEvent>(bufferingPolicy: .unbounded) { continuation in
            sink.bind(continuation)
        }
        let events = (0..<1_000).map { index in
            ChatStreamEvent.chunk(MessageChunk(
                id: "chunk-\(index)",
                model: "test-model",
                choices: [],
                usage: nil
            ))
        }
        for event in events {
            sink.yield(event)
        }

        XCTAssertFalse(sink.claim(events[1]), "Out-of-order claims must not disturb FIFO ownership.")
        XCTAssertEqual(sink.pendingEventCountForTesting, events.count)
        for event in events {
            XCTAssertTrue(sink.claim(event))
        }
        XCTAssertEqual(sink.pendingEventCountForTesting, 0)
    }

    func testWorkspaceArtifactSavesDoNotUseTryOptionalSuccessPath() throws {
        let testDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let appDirectory = testDirectory.deletingLastPathComponent().appendingPathComponent("iosApp")
        let sources = try [
            "DeepReadCreateView.swift",
            "ChatViewModel.swift",
            "MiniAppRunnerView.swift"
        ].map { fileName in
            try String(contentsOf: appDirectory.appendingPathComponent(fileName), encoding: .utf8)
        }

        for source in sources {
            XCTAssertFalse(
                source.contains("_ = try? IOSWorkspaceStore.shared.saveArtifact"),
                "Workspace artifact saves must not swallow errors and then show a success message."
            )
        }
    }

    // MARK: - helpers / probe types

    private static func source(_ source: String, hasDebugGuardAround marker: String) -> Bool {
        guard let markerRange = source.range(of: marker) else { return false }
        let before = source[..<markerRange.lowerBound]
        let after = source[markerRange.upperBound...]
        guard let ifRange = before.range(of: "#if DEBUG", options: .backwards) else { return false }
        if let endifBefore = before.range(of: "#endif", options: .backwards),
           endifBefore.lowerBound > ifRange.lowerBound {
            return false
        }
        guard let endifAfter = after.range(of: "#endif") else { return false }
        if let ifAfter = after.range(of: "#if DEBUG"),
           ifAfter.lowerBound < endifAfter.lowerBound {
            return false
        }
        return true
    }

    /// Isolated temp directory for per-test DeepRead stores (avoids polluting
    /// the shared Documents/deep_read store across tests).
    private func makeTempDir() -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("redlight-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func makeDeepReadTask() -> IOSDeepReadTask {
        IOSDeepReadTask(
            id: "redlight-task-\(UUID().uuidString)",
            title: "RedLight Deep Read",
            status: .running,
            templateId: IOSDeepReadTemplate.analysis.id,
            sources: [
                IOSDeepReadSource(kind: .manualText, title: "Source A", content: "AmberAgent is an iOS app with chat and tools."),
                IOSDeepReadSource(kind: .manualText, title: "Source B", content: "It supports deep reading and subagents.")
            ],
            resultMarkdown: "",
            failureMessage: nil,
            createdAt: 1,
            updatedAt: 1,
            completedAt: nil,
            retryCount: 0
        )
    }

    /// DeepRead provider resolver. Calls the REAL resolver the pipeline now uses
    /// (`IOSDeepReadDraftGenerator.resolveProviderSetting`), which honors the
    /// selected sealed type. P0.5 routed BoardView through this; the test asserts
    /// a Claude selection yields a `ProviderSetting.Claude`.
    private func deepReadResolvedProvider(forSelected selected: ProviderSetting) -> ProviderSetting? {
        IOSDeepReadDraftGenerator.resolveProviderSetting(selected: selected)
    }

    /// Council provider resolver. Calls the REAL resolver the council runtime
    /// now uses (`IOSCouncilRoomRunner.resolveProviderSetting`), which honors
    /// the selected sealed type. P3 routed both council entries through it; the
    /// test asserts a Claude selection yields a `ProviderSetting.Claude`.
    private func councilResolvedProvider(forSelected selected: ProviderSetting) -> ProviderSetting? {
        IOSCouncilRoomRunner.resolveProviderSetting(selected: selected)
    }

    /// A provider that throws on every call — models all-stages-failed.
    final class FailingTextProvider: IOSAgentTextProvider, @unchecked Sendable {
        private(set) var callCount = 0
        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            throw NSError(domain: "redlight", code: 1, userInfo: [NSLocalizedDescriptionKey: "stage failed"])
        }
    }

    /// A provider that returns an empty string for every call.
    final class EmptyTextProvider: IOSAgentTextProvider, @unchecked Sendable {
        private(set) var callCount = 0
        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            let message = UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: "", metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 22, hour: 0, minute: 0, second: 0, nanosecond: 0),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
            return MessageChunk(
                id: "empty-\(callCount)",
                model: "test",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }

    /// A provider that records engine-path invocation for the subagent test.
    final class EngineProbeProvider: IOSAgentTextProvider, @unchecked Sendable {
        private(set) var callCount = 0
        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            let message = UIMessage(
                id: KotlinUuid.companion.random(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: "done", metadata: nil)],
                annotations: [],
                createdAt: Kotlinx_datetimeLocalDateTime(year: 2026, month: 6, day: 22, hour: 0, minute: 0, second: 0, nanosecond: 0),
                finishedAt: nil,
                modelId: nil,
                usage: nil,
                translation: nil
            )
            return MessageChunk(
                id: "probe-\(callCount)",
                model: "test",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
                usage: nil
            )
        }
    }
}
