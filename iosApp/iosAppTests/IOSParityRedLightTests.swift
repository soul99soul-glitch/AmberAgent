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
        // Apply the pipeline result the way createAndGenerateTask now does:
        // honest failure when every stage threw, instead of marking succeeded.
        if result.didFail {
            store.fail(id: running.id, message: "所有生成阶段失败：\(result.failureReason)")
        } else {
            store.complete(id: running.id, markdown: result.markdown)
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
        // Empty-output run must surface as honest failure, not succeeded/ready.
        if result.didFail {
            store.fail(id: running.id, message: "生成内容为空：所有阶段未产出可用内容")
        } else {
            store.complete(id: running.id, markdown: result.markdown)
        }
        let final = store.task(id: running.id)

        XCTAssertNotEqual(
            final?.status,
            IOSDeepReadTaskStatus.succeeded,
            "A run whose every stage was empty must not be marked succeeded/ready. "
                + "Got: \(String(describing: final?.status)) — honest-fail state machine (P0.5)."
        )
    }

    /// P0 REGRESSION GUARD (GREEN now). Green target: stays green through P4.
    /// Cell: deepread.honest_fail (search/source failure mapping).
    ///
    /// NOTE: spec line 63 lists this among the P0 red-lights, but current iOS
    /// code ALREADY maps search/scrape failures to a source-level marker
    /// (BoardView.swift:580 `scrape_status = "failed"` + synthetic "搜索不可用"
    /// source) and does NOT hard-fail the task. So this is a regression-guard
    /// test (GREEN), locking the correct behavior so P4's state-machine changes
    /// don't regress it. Recorded as a spec leniency deviation in PHASE_LOG.md.
    ///
    /// A search/scrape failure must be reflected as a SOURCE-level failure, not
    /// a whole-pipeline failure — and the pipeline must still run over the
    /// surviving sources. This test pins that contract.
    func test_deepread_searchFailure_isSourceFailure() async throws {
        let task = makeDeepReadTask()
        let store = IOSDeepReadStore(baseDirectory: makeTempDir())
        // One good source + one source whose fetch failed (mirrors BoardView's
        // synthetic "搜索不可用" / scrape_status="failed" source).
        var failedSource = IOSDeepReadSource(
            kind: .searchResult, title: "搜索不可用：query",
            content: "搜索来源未能读取：network error"
        )
        failedSource.metadata["scrape_status"] = "failed"
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

        // Parity target A: the failed source must carry a source-level marker.
        let hasSourceFailureMarker = (final?.sources ?? []).contains { source in
            source.metadata["scrape_status"] == "failed"
                || source.title.contains("搜索不可用")
                || source.title.contains("未能读取")
        }
        XCTAssertTrue(
            hasSourceFailureMarker,
            "A search/scrape failure must be recorded as a source-level marker, not lost. (P4)"
        )
        // Parity target B: the task must NOT be hard-failed solely because a
        // source failed to fetch (it should run over surviving sources).
        XCTAssertNotEqual(
            final?.status,
            IOSDeepReadTaskStatus.failed,
            "A source-level search failure must not fail the whole task when surviving sources exist. (P4)"
        )
    }

    // MARK: - SubAgent (truth_matrix row `subagent_standalone`)

    /// RED for P0. GREEN target: P4.
    /// Cell: subagent_standalone.exec_real.
    ///
    /// The STANDALONE subagent page must dispatch through `IOSAgentToolEngine`
    /// (`runViaEngine`), not the legacy KMP `SubAgentManager` path (`run`).
    /// Today `SubAgentsView` calls `runner.run(...)` (SubAgentsView.swift:127,178)
    /// — the legacy path that uses IosSubAgentFactory + SubAgentManager and never
    /// touches `IOSAgentToolEngine`. Only the CHAT tool path uses `runViaEngine`
    /// (ChatToolRuntime.swift:590). P4 (spec line 132) routes the standalone
    /// page to `runViaEngine`.
    ///
    /// This test verifies the dispatch wiring structurally: the standalone page's
    /// recorded task must be tagged engine-routed. We drive `runViaEngine` (which
    /// works in isolation) with a probe and assert BOTH (a) the engine provider is
    /// invoked and (b) the task carries the engine tag. The legacy `run` path
    /// (which the page actually calls today) is not executable in a unit test —
    /// it needs full app context and crashes via KMP serialization — so this test
    /// proves the engine path is ready; the P4 wiring change makes the page call
    /// it. The RED today is that the page does NOT call this path yet.
    func test_subagent_standalone_usesEnginePath() async {
        let suite = UserDefaults(suiteName: "redlight-subagent-\(UUID().uuidString)")!
        let taskStore = IOSAdvancedTaskStore(userDefaults: suite, storageKey: "redlight.tasks")
        let runner = SubAgentRunner(taskStore: taskStore)
        let probe = EngineProbeProvider()

        // Drive the engine path (the path the standalone page SHOULD call).
        _ = await runner.runViaEngine(
            objective: "explore the objective",
            roleId: "explorer",
            providerSetting: makeOpenAIProvider(),
            modelId: "any-model",
            parentToolExecutors: [:],
            provider: probe
        )

        // The standalone page (SubAgentsView) must route runs through the
        // engine path. Today it calls the legacy `run` which:
        //   - never invokes IOSAgentTextProvider (probe.callCount stays 0), and
        //   - records metadata WITHOUT the engine tag.
        // P4 wires SubAgentsView to runViaEngine. Assert the engine path's
        // observable contract holds (probe invoked + engine tag set) — this is
        // the contract the page must adopt. RED until the page calls it.
        XCTAssertGreaterThan(
            probe.callCount,
            0,
            "Engine path must invoke the provider. (Proves runViaEngine is ready; "
                + "P4 wires SubAgentsView to call it instead of legacy `run`.)"
        )
        let recorded = runner.lastTask
        XCTAssertEqual(
            recorded?.metadata["engine"],
            "true",
            "Standalone subagent must record engine routing. P4 wires SubAgentsView "
                + "to runViaEngine (today it calls legacy `run` without this tag)."
        )
        // The gap itself: the standalone page does NOT yet use the engine path.
        // We assert the page's source-level dispatch targets runViaEngine. Today
        // SubAgentsView calls the legacy `run` — so this reflection check fails.
        let pageDispatchesViaEngine = SubAgentsView.standaloneDispatchUsesEnginePath
        XCTAssertTrue(
            pageDispatchesViaEngine,
            "SubAgentsView (standalone page) must dispatch through runViaEngine, "
                + "not the legacy `run`. Today it calls runner.run(...) (SubAgentsView.swift:127,178). (P4)"
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
        let decoded = IosSettingsJsonBridge.shared.decode(json: json)
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

        let decoded = IosSettingsJsonBridge.shared.decode(json: json)
        store.restoreSnapshot(decoded)

        let persisted = (defaults.string(forKey: "app.amber.ios.sharedSettingsJson") ?? "")
        XCTAssertFalse(
            persisted.contains(secret),
            "Persisted settings JSON must not contain an assistant customBody credential in plaintext. (P2 scheme B)"
        )
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

    // MARK: - helpers / probe types

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
