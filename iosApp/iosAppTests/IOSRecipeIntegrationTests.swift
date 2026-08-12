import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 1 Wave B2 integration tests (§15 Phase 1 acceptance 2-6):
/// the production `recipe__*` route, mutation-step approval pause/resume with
/// a durable checkpoint, `recipe_import` promotion, lease pinning and the
/// round-by-round hot-reload canary.
///
/// Assembly level (reported trade-off): the foreground Chat loop
/// (`ChatGenerationCoordinator`) is not provider-injectable (it owns
/// `OpenAIKmpProvider`), so a coordinator-level scripted-provider harness does
/// not exist. The canary therefore drives the REAL round loop
/// (`IOSAgentToolEngine`, the same loop the background path uses) one round at
/// a time with a scripted provider, performing the promotion + registry
/// refresh + bridge rebuild between rounds — exactly the coordinator's
/// `continueAfterToolResult` / `refreshDynamicCatalogAtRoundBoundary` seam —
/// with the REAL bridge, REAL registry, REAL recipe store, REAL ledger and the
/// REAL `ChatToolRuntime` recipe route. Everything except the provider script
/// is production code.
@MainActor
final class IOSRecipeIntegrationTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - Acceptance 2: hot-reload e2e canary (search → promote → call → final)

    func testHotReloadCanarySearchPromoteCallResultRoundByRound() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let runId = "canary-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)
        let runtime = makeRuntime(root: root, ledger: ledger)

        // Round 1: the recipe is NOT published. The run bridge covers only the
        // static catalog; the scripted model searches for it and misses.
        let staticDeclarations = fullIosDeclarations()
        var bridge = IosToolExposureBridge(tools: staticDeclarations)
        let provider1 = ParamsRecordingProvider([
            toolCallMessage(toolCallId: "tc-search-1", toolName: "tool_search",
                            input: #"{"query":"catalog_probe","limit":5}"#),
        ])
        let engine1 = IOSAgentToolEngine(
            provider: provider1,
            executors: ["tool_search": BridgeToolSearchExecutor(bridge: bridge)],
            configuration: .init(maxSteps: 1)
        )
        let r1 = await engine1.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("先找一下 catalog_probe 工具")],
            params: makeParams(tools: staticDeclarations),
            toolExposureBridge: bridge
        )
        XCTAssertFalse(
            Set(bridge.fullToolDeclarations().map(\.name)).contains("recipe__catalog_probe"),
            "round 1 must start without the recipe"
        )
        XCTAssertFalse(
            Set(provider1.recordedParams[0].tools.map(\.name)).contains("recipe__catalog_probe")
        )
        let search1 = try XCTUnwrap(toolOutputText(in: r1.messages, toolCallId: "tc-search-1"))
        XCTAssertTrue(toolSearchHitNames(search1).isEmpty, "round 1 search must miss: \(search1)")

        // Between rounds: promote + registry.refresh() → new revision; rebuild
        // the run bridge over the new snapshot (the coordinator seam does this
        // at the round boundary).
        try apply(store: store, json: try listingRecipeJSON(version: "1.0.0"))
        let promotedSnapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(promotedSnapshot.revision, 1)
        bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(from: bridge, snapshot: promotedSnapshot)
        XCTAssertTrue(
            Set(bridge.fullToolDeclarations().map(\.name)).contains("recipe__catalog_probe"),
            "after promotion the full catalog must contain the recipe"
        )
        XCTAssertFalse(
            Set(bridge.visibleTools().map(\.name)).contains("recipe__catalog_probe"),
            "the recipe stays default-deferred until tool_search exposes it"
        )

        // Round 2: the scripted model searches again → hit with descriptor +
        // version + permission summary + source=custom.recipe (no manifest
        // body); the search itself exposes the recipe for the NEXT round.
        let provider2 = ParamsRecordingProvider([
            toolCallMessage(toolCallId: "tc-search-2", toolName: "tool_search",
                            input: #"{"query":"catalog_probe","limit":5}"#),
        ])
        let engine2 = IOSAgentToolEngine(
            provider: provider2,
            executors: ["tool_search": BridgeToolSearchExecutor(bridge: bridge)],
            configuration: .init(maxSteps: 1)
        )
        let r2 = await engine2.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("再找一次")],
            params: makeParams(tools: bridge.visibleTools()),
            toolExposureBridge: bridge
        )
        let search2 = try XCTUnwrap(toolOutputText(in: r2.messages, toolCallId: "tc-search-2"))
        let hit = try XCTUnwrap(searchHit(search2, name: "recipe__catalog_probe"))
        XCTAssertEqual(hit["version"] as? String, "1.0.0")
        XCTAssertEqual(hit["source"] as? String, "custom.recipe")
        XCTAssertEqual(hit["permission_summary"] as? String,
                       IOSDynamicToolRegistry.permissionSummary(for: .pure))
        XCTAssertNil(hit["manifest"], "search results must not carry the manifest body")
        XCTAssertTrue(
            Set(bridge.visibleTools().map(\.name)).contains("recipe__catalog_probe"),
            "the tool_search hit must expose the recipe for the next round"
        )

        // Round 3: the scripted model CALLS recipe__catalog_probe. The
        // executor routes through the REAL ChatToolRuntime recipe route with
        // the round's pinned snapshot; the step runs the REAL tools_list
        // primitive through the REAL bridge.
        let recipeExecutors: [String: any IOSToolExecutor] = [
            "tool_search": BridgeToolSearchExecutor(bridge: bridge),
            "recipe__catalog_probe": RecipeRouteExecutor(
                runtime: runtime,
                snapshot: promotedSnapshot,
                bridge: bridge,
                runId: runId
            ),
        ]
        let provider3 = ParamsRecordingProvider([
            toolCallMessage(toolCallId: "tc-recipe-1", toolName: "recipe__catalog_probe", input: "{}"),
        ])
        let engine3 = IOSAgentToolEngine(
            provider: provider3,
            executors: recipeExecutors,
            configuration: .init(maxSteps: 1)
        )
        let r3 = await engine3.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("调用 catalog_probe")],
            params: makeParams(tools: bridge.visibleTools()),
            toolExposureBridge: bridge
        )
        let recipeOutput = try XCTUnwrap(toolOutputText(in: r3.messages, toolCallId: "tc-recipe-1"))
        let recipeResult = try XCTUnwrap(parse(recipeOutput))
        XCTAssertEqual(recipeResult["ok"] as? Bool, true, recipeOutput)
        XCTAssertEqual(recipeResult["status"] as? String, "completed")
        XCTAssertEqual(recipeResult["steps"] as? [String], ["list"])
        let outputs = try XCTUnwrap(recipeResult["outputs"] as? [String: Any])
        let toolCount = try XCTUnwrap(outputs["tool_count"] as? Int)
        XCTAssertGreaterThan(toolCount, 0, "the real tools_list total must flow into the recipe output")

        // Ledger: step-level Started/Finished with artifact attribution AND a
        // recipe-level Finished row with artifactId/artifactVersion.
        let rows = await ledgerRows(runId: runId, dao: dao)
        let stepStarted = rows.filter { $0.type == IOSToolCallLedgerClassifier.startedType }
        let stepFinished = rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
        XCTAssertEqual(stepStarted.count, 1, "one step Started")
        XCTAssertEqual(stepFinished.count, 2, "one step Finished + one recipe-level Finished")
        let finishedPayloads = rows
            .filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
            .compactMap { parsedPayload($0.payload) }
        let stepFinishedPayload = try XCTUnwrap(
            finishedPayloads.first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-recipe-") == true }
        )
        XCTAssertEqual(stepFinishedPayload["artifactId"] as? String, "recipe__catalog_probe")
        XCTAssertEqual(stepFinishedPayload["artifactVersion"] as? String, "1.0.0")
        XCTAssertEqual(stepFinishedPayload["outcomeKind"] as? String, "success")
        let recipeLevelPayload = try XCTUnwrap(
            finishedPayloads.first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-level-") == true }
        )
        XCTAssertEqual(recipeLevelPayload["artifactId"] as? String, "recipe__catalog_probe")
        XCTAssertEqual(recipeLevelPayload["artifactVersion"] as? String, "1.0.0")
        XCTAssertEqual(recipeLevelPayload["outcomeKind"] as? String, "success")

        // Round 4: final text to a durable terminal; the recipe stays visible.
        let provider4 = ParamsRecordingProvider([assistantText("done")])
        let engine4 = IOSAgentToolEngine(
            provider: provider4,
            executors: recipeExecutors,
            configuration: .init(maxSteps: 1)
        )
        let r4 = await engine4.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("收尾")],
            params: makeParams(tools: bridge.visibleTools()),
            toolExposureBridge: bridge
        )
        XCTAssertEqual(r4.messages.last?.role, MessageRole.assistant)
        XCTAssertEqual(r4.messages.last?.toText().trimmingCharacters(in: .whitespacesAndNewlines), "done")
        XCTAssertNil(r4.pendingApproval)
        XCTAssertFalse(r4.hitStepLimit)
        XCTAssertTrue(
            Set(provider4.recordedParams[0].tools.map(\.name)).contains("recipe__catalog_probe"),
            "the already-exposed recipe stays visible on later rounds"
        )
    }

    // MARK: - Acceptance 3: lease pinning (v1 call keeps v1 despite promotion)

    func testLeasePinningV1CallCompletesWithPinnedManifestDespiteV2Promotion() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let runtime = makeRuntime(root: root, ledger: ledger)
        let runId = "lease-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try apply(store: store, json: try listingRecipeJSON(version: "1.0.0"))
        let v1Snapshot = try unwrapSnapshot(await registry.refresh())
        let v1Bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: v1Snapshot
        )

        // Promote v2 while the v1 snapshot is held.
        try apply(store: store, json: try listingRecipeV2JSON(version: "2.0.0"))
        let v2Snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(v2Snapshot.recipeTools.first?.version, "2.0.0")

        // The pinned v1 call runs AFTER the promotion and still uses the v1
        // manifest: one step, not v2's two steps.
        let call = makeRecipeToolCall(name: "recipe__catalog_probe", input: "{}")
        let result = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: v1Snapshot, bridge: v1Bridge,
            runId: runId
        )
        guard case .completed(let messages) = result else {
            return XCTFail("expected completion, got \(result)")
        }
        let output = try XCTUnwrap(toolOutputText(in: messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["steps"] as? [String], ["list"],
                       "the v1 call must use the v1 (one-step) manifest, not v2")
        XCTAssertEqual(parsed["status"] as? String, "completed")

        let rows = await ledgerRows(runId: runId, dao: dao)
        let recipeLevel = try XCTUnwrap(
            rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
                .compactMap { parsedPayload($0.payload) }
                .first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-level-") == true }
        )
        XCTAssertEqual(recipeLevel["artifactVersion"] as? String, "1.0.0",
                       "the recipe-level record attributes the run to the pinned v1")

        // The NEXT round acquires v2 (declaration reflects the promotion).
        XCTAssertEqual(v2Snapshot.recipeTools.first?.toolId, "recipe__catalog_probe")
    }

    // MARK: - Acceptance 4: stale base/candidate fail closed with zero writes

    func testStaleCandidateFailsClosedZeroWriteOnRecipeImportApproval() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let workspace = makeWorkspaceStore(root: root)
        let runtime = makeRuntime(root: root, ledger: ledger, workspaceStore: workspace)
        let runId = "stale-candidate-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try await seedWorkspaceRecipe(workspace: workspace, json: try listingRecipeJSON(version: "1.0.0"))
        let previewCall = makeRecipeToolCall(name: "recipe_import",
                                             input: #"{"workspace_path":"/workspace/recipes/catalog_probe/recipe.json"}"#)
        let previewResult = await executeRecipeCall(
            runtime: runtime, toolCall: previewCall, snapshot: nil, bridge: nil,
            runId: runId
        )
        guard case .waitingForApproval(.recipe(let request)) = previewResult,
              case .recipeImport(let payload) = request.payload else {
            return XCTFail("expected recipe import approval, got \(previewResult)")
        }
        let candidateHashAtPreview = payload.candidateHash

        // The candidate changes while the card is open.
        try await seedWorkspaceRecipe(workspace: workspace, json: try listingRecipeJSON(version: "1.0.1"))

        let coordinator = makeCoordinator(root: root, ledger: ledger, runtimeOverride: runtime)
        await coordinator.installPendingRecipeToolApprovalForTesting(pending: pendingContext(for: previewCall, runId: runId), request: request)
        await coordinator.approvePendingRecipeTool(requestId: request.id)

        let output = try XCTUnwrap(toolOutputText(in: coordinatorState.messages, toolCallId: previewCall.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["success"] as? Bool, false, output)
        XCTAssertEqual(parsed["code"] as? String, "stale_candidate")
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: store.recipesDirectory.path),
            "a stale-candidate approval must write nothing"
        )
        XCTAssertNotEqual(candidateHashAtPreview, (try? store.prepareRecipe(recipeJSON: try listingRecipeJSON(version: "1.0.1")))?.candidate.hash)
    }

    func testStaleBaseFailsClosedOnRecipeImportApproval() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let workspace = makeWorkspaceStore(root: root)

        // v1 installed through the store (live base), then a v2 candidate in
        // Workspace whose preview pins base = v1.
        try apply(store: store, json: try listingRecipeJSON(version: "1.0.0", name: "base_stale"))
        let v1Hash = try store.readLiveRecipe(name: "base_stale").hash
        try await seedWorkspaceRecipe(
            workspace: workspace,
            json: try listingRecipeJSON(version: "2.0.0", name: "base_stale"),
            workspacePath: "/workspace/recipes/base_stale/recipe.json"
        )
        let service = IOSRecipeToolService(
            workspaceStore: workspace,
            recipeStore: store,
            refreshRegistry: { nil }
        )
        let prepared = try service.prepareRecipeImport(
            arguments: #"{"workspace_path":"/workspace/recipes/base_stale/recipe.json"}"#
        )
        XCTAssertEqual(prepared.preview.baseHash, v1Hash)

        // Live base changes after the preview.
        try apply(store: store, json: try listingRecipeJSON(version: "3.0.0", name: "base_stale"))
        let afterV3 = directorySnapshot(store.recipesDirectory)

        let result = try await service.applyPreparedRecipeImport(prepared)
        let parsed = try XCTUnwrap(parse(result))
        XCTAssertEqual(parsed["code"] as? String, "stale_base", result)
        XCTAssertEqual(
            directorySnapshot(store.recipesDirectory), afterV3,
            "a stale-base apply must not change the live package"
        )
        XCTAssertEqual(try store.readLiveRecipe(name: "base_stale").version, "3.0.0")
    }

    // MARK: - Acceptance 5: rollback → next round sees previous; in-flight not replaced

    func testRollbackNextRoundSeesPreviousAndInFlightCallNotReplaced() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let runtime = makeRuntime(root: root, ledger: ledger)
        let runId = "rollback-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try apply(store: store, json: try listingRecipeJSON(version: "1.0.0"))
        _ = try unwrapSnapshot(await registry.refresh())
        try apply(store: store, json: try listingRecipeV2JSON(version: "2.0.0"))
        let v2Snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(v2Snapshot.recipeTools.first?.version, "2.0.0")
        let v2Bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: v2Snapshot
        )

        // The in-flight v2 call completes BEFORE the rollback takes effect.
        let call = makeRecipeToolCall(name: "recipe__catalog_probe", input: "{}")
        let result = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: v2Snapshot, bridge: v2Bridge,
            runId: runId
        )
        guard case .completed(let messages) = result else {
            return XCTFail("expected completion, got \(result)")
        }
        let output = try XCTUnwrap(toolOutputText(in: messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["steps"] as? [String], ["list", "list_again"],
                       "the started v2 call must use the v2 manifest")
        let rows = await ledgerRows(runId: runId, dao: dao)
        let recipeLevel = try XCTUnwrap(
            rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
                .compactMap { parsedPayload($0.payload) }
                .first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-level-") == true }
        )
        XCTAssertEqual(recipeLevel["artifactVersion"] as? String, "2.0.0",
                       "the in-flight v2 call is attributed to v2, never replaced")

        // Rollback publishes a NEW revision restoring v1; the next round sees
        // the previous version.
        let availability = try store.rollbackAvailability(name: "catalog_probe")
        guard case .available(let expectedManifest) = availability else {
            return XCTFail("expected rollback availability, got \(availability)")
        }
        _ = try store.rollbackRecipe(name: "catalog_probe", expectedManifest: expectedManifest)
        let rolledBack = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(rolledBack.revision, v2Snapshot.revision)
        XCTAssertEqual(rolledBack.recipeTools.first?.version, "1.0.0",
                       "the next round sees the previous version")
    }

    // MARK: - Acceptance 6: mutation step uses the existing approval machinery

    func testMutationStepDeniedStopsRecipeWithStructuredErrorAndLedgerDenial() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let (executor, workspace, _) = makeWorkspaceExecutor(root: root)
        let runtime = makeRuntime(root: root, ledger: ledger, localToolExecutor: executor, workspaceStore: workspace)
        let coordinator = makeCoordinator(root: root, ledger: ledger, executor: executor, runtimeOverride: runtime)
        let runId = "mutation-deny-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try apply(store: store, json: try mutatingRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        let bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: snapshot
        )

        let call = makeRecipeToolCall(
            name: "recipe__digest_save",
            input: #"{"output_path":"/workspace/notes/out.md"}"#
        )
        let result = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: snapshot, bridge: bridge,
            runId: runId
        )
        guard case .waitingForApproval(.recipe(let request)) = result else {
            return XCTFail("expected step approval, got \(result)")
        }
        await coordinator.installPendingRecipeToolApprovalForTesting(
            pending: pendingContext(for: call, runId: runId),
            request: request,
            toolExposureBridge: bridge
        )
        await coordinator.denyPendingRecipeTool(requestId: request.id)

        let output = try XCTUnwrap(toolOutputText(in: coordinatorState.messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["ok"] as? Bool, false, output)
        XCTAssertEqual(parsed["step"] as? String, "save")
        XCTAssertNil(workspace.fileRecord(idOrPath: "/workspace/notes/out.md"),
                     "the denied step must never execute")
        let checkpointDir = root.appendingPathComponent("recipes/.checkpoints", isDirectory: true)
        let remaining = (try? FileManager.default.contentsOfDirectory(atPath: checkpointDir.path)) ?? []
        XCTAssertTrue(remaining.isEmpty, "checkpoint must be removed on denial")

        // Phase 0 evidence: the denial must write a REAL approval_denied row
        // and the recipe-level Finished must carry the denied outcome. The
        // denial event is fire-and-forget in production (same tier as
        // Finished), so poll briefly for it.
        let rows = await waitForApprovalDenied(runId: runId, dao: dao)
        let denials = rows.filter { $0.type == IOSAgentRunLedger.approvalDeniedEventType }
        XCTAssertEqual(denials.count, 1, "approval_denied must be a real ledger event")
        let recipeLevel = try XCTUnwrap(
            rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
                .compactMap { parsedPayload($0.payload) }
                .first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-level-") == true }
        )
        XCTAssertEqual(recipeLevel["outcomeKind"] as? String, "denied")
        XCTAssertEqual(recipeLevel["errorCode"] as? String, "step_denied")
        XCTAssertEqual(recipeLevel["artifactId"] as? String, "recipe__digest_save")
    }

    /// Slice B 红测试 2：每个 mutation step 的审批 request id 必须唯一到
    /// step（外层 toolCallId + executionId + stepId）。step A 被消费后暂停在
    /// step B 时，来自旧 A 卡的 approve id 不得批准 B（零执行、零清槽、UI
    /// 保持 pending）；随后用当前 B 的 id approve 仍能正常完成整个 recipe
    /// （两文件落盘、终态 completed）。红点：当前 A.id == B.id（都是外层
    /// toolCallId），旧 id 会消费 B。
    func testStaleStepApprovalIdCannotConsumeNextStepPause() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let (executor, workspace, _) = makeWorkspaceExecutor(root: root)
        let runtime = makeRuntime(root: root, ledger: ledger, localToolExecutor: executor, workspaceStore: workspace)
        let coordinator = makeCoordinator(root: root, ledger: ledger, executor: executor, runtimeOverride: runtime)
        let runId = "stale-step-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try apply(store: store, json: try twoMutationRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        let bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: snapshot
        )

        let call = makeRecipeToolCall(
            name: "recipe__double_save",
            input: #"{"path_a":"/workspace/notes/a.md","path_b":"/workspace/notes/b.md"}"#
        )
        let firstResult = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: snapshot, bridge: bridge,
            runId: runId
        )
        guard case .waitingForApproval(.recipe(let firstRequest)) = firstResult,
              case .step(let firstPayload) = firstRequest.payload else {
            return XCTFail("expected first step approval, got \(firstResult)")
        }
        XCTAssertEqual(firstPayload.stepId, "save_a")

        // 批准 save_a → recipe 继续并再次暂停在 save_b。
        await coordinator.installPendingRecipeToolApprovalForTesting(
            pending: pendingContext(for: call, runId: runId),
            request: firstRequest,
            toolExposureBridge: bridge
        )
        await coordinator.approvePendingRecipeTool(requestId: firstRequest.id)

        let secondRequest = try XCTUnwrap(coordinatorState.pendingRecipeApproval,
                                          "approving the first mutation step must re-pause at the second")
        guard case .step(let secondPayload) = secondRequest.payload else {
            return XCTFail("expected a second step approval, got \(secondRequest.payload)")
        }
        XCTAssertEqual(secondPayload.stepId, "save_b")
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: workspace.fileURL(for: try XCTUnwrap(
                    workspace.fileRecord(idOrPath: "/workspace/notes/a.md")
                )).path
            ),
            "the first approved step must have executed before the second pause"
        )

        // 旧 A 卡 id 再 approve：零执行、零清槽（红点：当前 A.id == B.id，
        // 旧 id 会消费 B、执行 save_b 并完成 recipe）。
        await coordinator.approvePendingRecipeTool(requestId: firstRequest.id)
        XCTAssertNil(
            workspace.fileRecord(idOrPath: "/workspace/notes/b.md"),
            "旧 step 卡的 approve id 不得执行 save_b（B2）"
        )
        XCTAssertEqual(
            coordinatorState.pendingRecipeApproval?.id, secondRequest.id,
            "旧 step 卡的 approve id 不得消费当前 pending（零清槽，UI 保持）"
        )
        XCTAssertNil(
            toolOutputText(in: coordinatorState.messages, toolCallId: call.toolCallId),
            "旧 step 卡的 approve id 不得产生终态输出"
        )

        // 用当前 B 卡 id approve → 正常完成整个 recipe。
        await coordinator.approvePendingRecipeTool(requestId: secondRequest.id)
        let output = try XCTUnwrap(toolOutputText(in: coordinatorState.messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["ok"] as? Bool, true, output)
        XCTAssertEqual(parsed["status"] as? String, "completed")
        XCTAssertEqual(parsed["steps"] as? [String], ["list", "save_a", "save_b"])
        for path in ["/workspace/notes/a.md", "/workspace/notes/b.md"] {
            let record = try XCTUnwrap(workspace.fileRecord(idOrPath: path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: workspace.fileURL(for: record).path),
                          "the approved step must have written \(path)")
        }
        XCTAssertNil(coordinatorState.pendingRecipeApproval, "recipe 完成后 pending 清槽")
    }

    /// Checker-requested coverage (§10.3.5): a recipe with TWO mutation steps
    /// must pause once per mutation step — approving the first CONTINUES the
    /// recipe, which pauses again at the second (the coordinator's
    /// `pausedForNextStep` branch + the runtime's resumable loop), then
    /// completes on the second approval. Checkpoint is persisted at each
    /// pause and cleaned at the terminal.
    func testTwoMutationStepsPauseTwiceThenCompleteWithCleanCheckpointAndAttribution() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let (executor, workspace, permissionStore) = makeWorkspaceExecutor(root: root)
        let runtime = makeRuntime(root: root, ledger: ledger, localToolExecutor: executor, workspaceStore: workspace)
        let coordinator = makeCoordinator(root: root, ledger: ledger, executor: executor, runtimeOverride: runtime)
        let runId = "two-mutation-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try apply(store: store, json: try twoMutationRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        let bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: snapshot
        )

        let call = makeRecipeToolCall(
            name: "recipe__double_save",
            input: #"{"path_a":"/workspace/notes/a.md","path_b":"/workspace/notes/b.md"}"#
        )
        let firstResult = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: snapshot, bridge: bridge,
            runId: runId
        )
        // First pause: step save_a (the pure list step ran without a card).
        guard case .waitingForApproval(.recipe(let firstRequest)) = firstResult,
              case .step(let firstPayload) = firstRequest.payload else {
            return XCTFail("expected first step approval, got \(firstResult)")
        }
        XCTAssertEqual(firstPayload.stepId, "save_a")
        XCTAssertEqual(firstPayload.tool, "workspace_file_write")
        let checkpointDir = root.appendingPathComponent("recipes/.checkpoints", isDirectory: true)
        XCTAssertEqual(checkpointFileCount(checkpointDir), 1,
                       "first pause must persist its checkpoint")

        // Approve save_a → the recipe must CONTINUE and pause AGAIN at save_b
        // (coordinator `pausedForNextStep` re-enters the durable pause).
        await coordinator.installPendingRecipeToolApprovalForTesting(
            pending: pendingContext(for: call, runId: runId),
            request: firstRequest,
            toolExposureBridge: bridge
        )
        await coordinator.approvePendingRecipeTool(requestId: firstRequest.id)

        let secondRequest = try XCTUnwrap(coordinatorState.pendingRecipeApproval,
                                          "approving the first mutation step must re-pause at the second")
        guard case .step(let secondPayload) = secondRequest.payload else {
            return XCTFail("expected a second step approval, got \(secondRequest.payload)")
        }
        XCTAssertEqual(secondPayload.stepId, "save_b")
        XCTAssertEqual(secondPayload.tool, "workspace_file_write")
        XCTAssertEqual(checkpointFileCount(checkpointDir), 1,
                       "the second pause must refresh (not duplicate) the checkpoint")
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: workspace.fileURL(for: try XCTUnwrap(
                    workspace.fileRecord(idOrPath: "/workspace/notes/a.md")
                )).path
            ),
            "the first approved step must have executed before the second pause"
        )

        // The finisher's `pausedForNextStep` branch already re-entered the
        // durable pause (card + prepared execution installed), so approve the
        // second card directly — no second install.
        await coordinator.approvePendingRecipeTool(requestId: secondRequest.id)

        let output = try XCTUnwrap(toolOutputText(in: coordinatorState.messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["ok"] as? Bool, true, output)
        XCTAssertEqual(parsed["status"] as? String, "completed")
        XCTAssertEqual(parsed["steps"] as? [String], ["list", "save_a", "save_b"])
        let outputs = try XCTUnwrap(parsed["outputs"] as? [String: Any])
        XCTAssertEqual(outputs["path_a_out"] as? String, "/workspace/notes/a.md")
        XCTAssertEqual(outputs["path_b_out"] as? String, "/workspace/notes/b.md")
        for path in ["/workspace/notes/a.md", "/workspace/notes/b.md"] {
            let record = try XCTUnwrap(workspace.fileRecord(idOrPath: path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: workspace.fileURL(for: record).path),
                          "the approved step must have written \(path)")
        }

        // Terminal: the checkpoint is cleaned.
        XCTAssertEqual(checkpointFileCount(checkpointDir), 0,
                       "the checkpoint must be removed when the recipe completes")

        // Ledger attribution: per-step Started/Finished pairs + the recipe-level
        // Finished(completed) + the two finisher attempts (Started →
        // paused_for_approval for cycle 1, Started → completed for cycle 2).
        let rows = await ledgerRows(runId: runId, dao: dao)
        let started = rows.filter { $0.type == IOSToolCallLedgerClassifier.startedType }
        let finished = rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
        XCTAssertEqual(started.count, 5, "3 steps + 2 finisher Started")
        XCTAssertEqual(finished.count, 6, "3 step Finished + recipe-level Finished + 2 finisher Finished")
        let finishedPayloads = rows
            .filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
            .compactMap { parsedPayload($0.payload) }
        let stepAttributions = finishedPayloads.filter {
            ($0["toolCallId"] as? String)?.hasPrefix("recipe-recipe-") == true
        }
        XCTAssertEqual(stepAttributions.count, 3)
        for payload in stepAttributions {
            XCTAssertEqual(payload["artifactId"] as? String, "recipe__double_save")
            XCTAssertEqual(payload["artifactVersion"] as? String, "1.0.0")
            XCTAssertEqual(payload["outcomeKind"] as? String, "success")
        }
        let recipeLevel = try XCTUnwrap(
            finishedPayloads.first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-level-") == true }
        )
        XCTAssertEqual(recipeLevel["artifactId"] as? String, "recipe__double_save")
        XCTAssertEqual(recipeLevel["artifactVersion"] as? String, "1.0.0")
        XCTAssertEqual(recipeLevel["outcomeKind"] as? String, "success")

        // Both approvals went through the recordToolApproval funnel: two
        // .allowed records for the recipe execution capability.
        let approvals = permissionStore.approvalRecords.filter {
            $0.capabilityId == "ios.agent.recipe_execution"
        }
        XCTAssertEqual(approvals.count, 2, "one allowed record per approved mutation step")
        XCTAssertTrue(approvals.allSatisfy { $0.action == .allowed })
    }

    // MARK: - recipe_import promotion through the approval card

    func testRecipeImportApprovalAppliesAndPublishesToRegistry() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let workspace = makeWorkspaceStore(root: root)
        let runtime = makeRuntime(root: root, ledger: ledger, workspaceStore: workspace)
        let coordinator = makeCoordinator(root: root, ledger: ledger, runtimeOverride: runtime)
        let runId = "import-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try await seedWorkspaceRecipe(workspace: workspace, json: try listingRecipeJSON(version: "1.0.0"))

        let highRiskKey = "app.amber.ios.highRiskAutoApprove"
        let previousHighRisk = UserDefaults.standard.object(forKey: highRiskKey)
        UserDefaults.standard.set(false, forKey: highRiskKey)
        defer {
            if let previousHighRisk {
                UserDefaults.standard.set(previousHighRisk, forKey: highRiskKey)
            } else {
                UserDefaults.standard.removeObject(forKey: highRiskKey)
            }
        }

        let call = makeRecipeToolCall(
            name: "recipe_import",
            input: #"{"workspace_path":"/workspace/recipes/catalog_probe/recipe.json"}"#
        )
        let result = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: nil, bridge: nil,
            runId: runId
        )
        guard case .waitingForApproval(.recipe(let request)) = result,
              case .recipeImport(let payload) = request.payload else {
            return XCTFail("expected recipe import approval, got \(result)")
        }
        XCTAssertEqual(payload.mutationKind, .new)
        XCTAssertNil(payload.baseHash)
        XCTAssertEqual(payload.stepsSummary, ["list → tools_list"])
        XCTAssertEqual(payload.effectClassRawValue, IOSToolEffectClass.pure.rawValue)

        await coordinator.installPendingRecipeToolApprovalForTesting(
            pending: pendingContext(for: call, runId: runId),
            request: request
        )
        await coordinator.approvePendingRecipeTool(requestId: request.id)

        let output = try XCTUnwrap(toolOutputText(in: coordinatorState.messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["success"] as? Bool, true, output)
        XCTAssertEqual(parsed["name"] as? String, "catalog_probe")
        XCTAssertEqual(parsed["version"] as? String, "1.0.0")

        // The store published the package and the registry serves it.
        let live = try store.readLiveRecipe(name: "catalog_probe")
        XCTAssertEqual(live.version, "1.0.0")
        let published = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(published.recipeTools.first?.toolId, "recipe__catalog_probe")
    }

    // MARK: - Stale snapshot call + handoff fail-closed

    func testStaleSnapshotCallFailsClosedWithStructuredError() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let runtime = makeRuntime(root: root, ledger: ledger)
        let runId = "stale-call-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        // A snapshot that does not declare the recipe (rolled back / stale).
        try apply(store: store, json: try listingRecipeJSON(version: "1.0.0"))
        let emptySnapshot = IOSDynamicToolCatalogSnapshot(
            revision: 99,
            recipeTools: [],
            contentHash: IOSDynamicToolRegistry.emptyContentHash
        )
        let bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: emptySnapshot
        )
        let call = makeRecipeToolCall(name: "recipe__catalog_probe", input: "{}")
        let result = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: emptySnapshot, bridge: bridge,
            runId: runId
        )
        guard case .completed(let messages) = result else {
            return XCTFail("stale calls must fail closed, not crash, got \(result)")
        }
        let output = try XCTUnwrap(toolOutputText(in: messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["ok"] as? Bool, false, output)
        XCTAssertTrue((parsed["reason"] as? String)?.contains("不在当前工具目录中") == true, output)

        // Nothing was executed and no recipe-level record was written.
        let rows = await ledgerRows(runId: runId, dao: dao)
        XCTAssertTrue(
            rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
                .compactMap { parsedPayload($0.payload) }
                .first { ($0["toolCallId"] as? String)?.hasPrefix("recipe-level-") == true } == nil,
            "a stale-snapshot call must not record a recipe run"
        )
    }

    func testInFlightRecipeCallClassifiesSideEffectAndBackgroundHasNoRecipeExecutor() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let runtime = makeRuntime(root: root, ledger: ledger)

        // Fail-closed handoff classification: an in-flight recipe__* call is
        // sideEffect (never auto-handed-off/replayed, §16.2).
        XCTAssertEqual(
            IOSToolEffectClassMapping.forToolName("recipe__catalog_probe", input: "{}"),
            .sideEffect
        )
        XCTAssertEqual(
            IOSToolEffectClassMapping.forToolName("recipe_import", input: "{}"),
            .sideEffect
        )

        // The background bridge never declares recipes (B1 handoff filter), so
        // the background executor table must not register recipe names either;
        // recipe_import is denied in the background.
        try apply(store: store, json: try listingRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        var declarations = fullIosDeclarations()
        declarations.append(contentsOf: snapshot.recipeDeclarations())
        if let importDeclaration = ToolKt.iosToolDeclaration(name: "recipe_import") {
            declarations.append(importDeclaration)
        }
        let params = makeParams(tools: declarations)
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "bg-run-\(UUID().uuidString)"
        )
        XCTAssertNil(executors["recipe__catalog_probe"],
                     "background must not register recipe executors (B1 filter)")
        XCTAssertNotNil(executors["recipe_import"],
                        "recipe_import is declared-but-denied in the background")

        // Drive the denial through the REAL background path (the engine is
        // nonisolated, exactly how production invokes background executors).
        let engine = IOSAgentToolEngine(
            provider: ParamsRecordingProvider([
                toolCallMessage(toolCallId: "tc-bg-import", toolName: "recipe_import", input: "{}"),
            ]),
            executors: executors,
            configuration: .init(maxSteps: 1)
        )
        let r = await engine.run(
            providerSetting: makeProviderSetting(),
            messages: [userMessage("后台导入")],
            params: params,
            toolExposureBridge: nil
        )
        let output = try XCTUnwrap(toolOutputText(in: r.messages, toolCallId: "tc-bg-import"))
        XCTAssertTrue(
            output.contains("需要回到 App 确认"),
            "recipe_import must be denied in the background without high-risk auto-approve: \(output)"
        )
    }

    /// 收口 Slice A 红测试：Workspace primitive 的 ok:false 输出在 recipe step
    /// 语义里必须是诚实的 step 失败（§10.3.6 stop-on-failure），而不是「步骤
    /// 成功、输出恰好是失败 JSON」的 false-green——否则真实路径错误永远不产生
    /// typed evidence，差分评测无从谈起（plan §2.2）。
    func testWorkspaceStepFailureInsideRecipeIsHonestStepFailureNotFalseGreen() async throws {
        let root = tempRoot()
        let store = makeStore(root: root)
        let registry = makeRegistry(store: store)
        let (_, dao) = makeDatabase(root: root)
        let ledger = IOSAgentRunLedger(dao: dao)
        let (executor, workspace, _) = makeWorkspaceExecutor(root: root)
        let runtime = makeRuntime(root: root, ledger: ledger, localToolExecutor: executor, workspaceStore: workspace)
        let coordinator = makeCoordinator(root: root, ledger: ledger, executor: executor, runtimeOverride: runtime)
        let runId = "ws-honest-run-\(UUID().uuidString)"
        try await seedDurableRun(runId, dao: dao)

        try apply(store: store, json: try readMissingRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        let bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: snapshot
        )

        let call = makeRecipeToolCall(
            name: "recipe__missing_reader",
            input: #"{"path":"missing/nope.txt"}"#
        )
        let result = await executeRecipeCall(
            runtime: runtime, toolCall: call, snapshot: snapshot, bridge: bridge,
            runId: runId
        )
        // 现行 catalog 把 workspace_file_read 归 sideEffect——先过审批卡，
        // 批准后读取必然缺失的文件。
        guard case .waitingForApproval(.recipe(let request)) = result else {
            return XCTFail("expected step approval, got \(result)")
        }
        await coordinator.installPendingRecipeToolApprovalForTesting(
            pending: pendingContext(for: call, runId: runId),
            request: request,
            toolExposureBridge: bridge
        )
        await coordinator.approvePendingRecipeTool(requestId: request.id)

        let output = try XCTUnwrap(toolOutputText(in: coordinatorState.messages, toolCallId: call.toolCallId))
        let parsed = try XCTUnwrap(parse(output))
        XCTAssertEqual(parsed["ok"] as? Bool, false,
                       "workspace ok:false 必须冒泡为 recipe 级失败：\(output)")
        XCTAssertEqual(parsed["step"] as? String, "read")

        // typed evidence：step Finished(error) + recipe-level Finished(failed)。
        let rows = await ledgerRows(runId: runId, dao: dao)
        let stepFinish = rows
            .filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
            .compactMap { parsedPayload($0.payload) }
            .first { ($0["toolCallId"] as? String)?.hasSuffix("-read") == true }
        XCTAssertEqual(stepFinish?["outcome"] as? String, "failed")
        XCTAssertEqual(stepFinish?["outcomeKind"] as? String, "error")
        XCTAssertEqual(stepFinish?["artifactId"] as? String, "recipe__missing_reader")
        XCTAssertEqual(stepFinish?["artifactVersion"] as? String, "1.0.0")
    }

    /// 单 step pure 读 recipe：读取必然缺失的文件（隔离 temp workspace 为空）。
    private func readMissingRecipeJSON(version: String) throws -> Data {
        let dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": "missing_reader",
            "version": version,
            "description": "读取一个必然不存在的文件。",
            "inputs": ["path": "string"],
            "steps": [
                ["id": "read", "tool": "workspace_file_read",
                 "arguments": ["path": "${input.path}"]],
            ],
            "outputs": ["text": "${step.read.output.text}"],
        ]
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    // MARK: - Fixtures

    /// XCTUnwrap cannot take `await` in its autoclosure; this wrapper keeps
    /// `unwrapSnapshot(await registry.refresh())` readable at call sites.
    private func unwrapSnapshot(
        _ value: IOSDynamicToolCatalogSnapshot?,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> IOSDynamicToolCatalogSnapshot {
        try XCTUnwrap(value, file: file, line: line)
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-recipe-integration-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }

    private func makeStore(root: URL) -> IOSRecipeFileStore {
        IOSRecipeFileStore(baseDirectory: root)
    }

    private func makeRegistry(store: IOSRecipeFileStore) -> IOSDynamicToolRegistry {
        IOSDynamicToolRegistry(baseDirectory: store.recipesDirectory.deletingLastPathComponent())
    }

    private func makeDatabase(root: URL) -> (db: AgentRuntimeDatabase, dao: AgentRuntimeDao) {
        // The temp directory MUST exist before Room creates the DB file.
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        return (db, db.agentRuntimeDao())
    }

    private func seedDurableRun(_ runId: String, dao: AgentRuntimeDao) async throws {
        let started = try await IOSDurableRunStore(dao: dao).startChatRun(
            runId: runId,
            startedAt: 1,
            inputDigest: "recipe-integration-test",
            conversationId: "recipe-integration-test"
        )
        XCTAssertTrue(started)
    }

    private func makeRuntime(
        root: URL,
        ledger: IOSAgentRunLedgering,
        localToolExecutor: IOSLocalToolExecutor? = nil,
        workspaceStore: IOSWorkspaceStore? = nil
    ) -> ChatToolRuntime {
        let defaults = UserDefaults(suiteName: "recipe-runtime-\(UUID().uuidString)")!
        return ChatToolRuntime(
            settingsStore: SettingsStore(userDefaults: defaults),
            sharedSettings: IOSSharedSettingsStore(userDefaults: defaults),
            localToolExecutor: localToolExecutor,
            searchTransport: RecipeNoopSearchTransport(),
            mcpManager: IOSMcpManager(
                sharedSettings: IOSSharedSettingsStore(userDefaults: defaults),
                configStore: .shared
            ),
            workspaceStore: workspaceStore ?? .shared,
            ledger: ledger,
            recipeStoreBaseDirectory: root
        )
    }

    private func makeWorkspaceStore(root: URL) -> IOSWorkspaceStore {
        IOSWorkspaceStore(baseDirectory: root.appendingPathComponent("ws", isDirectory: true))
    }

    private func makeWorkspaceExecutor(
        root: URL
    ) -> (executor: IOSLocalToolExecutor, workspace: IOSWorkspaceStore, permissionStore: IOSPermissionStore) {
        let workspace = makeWorkspaceStore(root: root)
        let permissionStore = IOSPermissionStore(
            userDefaults: UserDefaults(suiteName: "recipe-perm-\(UUID().uuidString)")!
        )
        let executor = IOSLocalToolExecutor(
            permissionStore: permissionStore,
            documentStore: DocumentAccessStore(),
            workspaceStore: workspace
        )
        return (executor, workspace, permissionStore)
    }

    private var coordinatorState = RecipeBindingState()

    private func makeCoordinator(
        root: URL,
        ledger: IOSAgentRunLedgering,
        executor: IOSLocalToolExecutor? = nil,
        runtimeOverride: ChatToolRuntime? = nil
    ) -> ChatGenerationCoordinator {
        coordinatorState = RecipeBindingState()
        let defaults = UserDefaults(suiteName: "recipe-coord-\(UUID().uuidString)")!
        let settingsStore = SettingsStore(userDefaults: defaults)
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        let coordinator = ChatGenerationCoordinator(
            dependencies: ChatGenerationDependencies(
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                localToolExecutor: executor,
                searchTransport: RecipeNoopSearchTransport(),
                liveActivityController: .shared,
                autoGenerateResponses: false,
                mcpManager: IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared),
                orchestrationToolService: nil,
                memoryPollutionMarker: nil
            ),
            bindings: coordinatorState.bindings(),
            toolLedger: ledger
        )
        // The finisher must run through the SAME runtime that executed the
        // recipe call (its stashed execution state / temp workspace / recipe
        // store); set the override before the lazy runtime is first touched.
        coordinator.toolRuntimeOverrideForTesting = runtimeOverride
        return coordinator
    }

    private func makeRecipeToolCall(name: String, input: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: "tc-\(name)-\(UUID().uuidString)",
            toolName: name,
            input: input,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
    }

    private func pendingContext(for toolCall: UIMessagePart.Tool, runId: String) -> ChatPendingToolApproval {
        ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: makeProviderSetting(),
            params: makeParams(tools: []),
            runId: runId,
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [makeAssistantMessage(parts: [toolCall])]
        )
    }

    private func executeRecipeCall(
        runtime: ChatToolRuntime,
        toolCall: UIMessagePart.Tool,
        snapshot: IOSDynamicToolCatalogSnapshot?,
        bridge: IosToolExposureBridge?,
        runId: String
    ) async -> ChatToolRuntimeResult {
        let pending = pendingContext(for: toolCall, runId: runId)
        return await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending,
            toolExposureBridge: bridge,
            recipeCatalogSnapshot: snapshot
        )
    }

    @discardableResult
    private func apply(store: IOSRecipeFileStore, json: Data) throws -> String {
        let prep = try store.prepareRecipe(recipeJSON: json)
        let receipt = try store.applyRecipe(
            name: prep.candidate.name,
            recipeJSON: json,
            expectedBaseHash: prep.base?.hash,
            expectedCandidateHash: prep.candidate.hash
        )
        return receipt.promotedHash
    }

    private func seedWorkspaceRecipe(
        workspace: IOSWorkspaceStore,
        json: Data,
        workspacePath: String = "/workspace/recipes/catalog_probe/recipe.json"
    ) async throws {
        let content = String(data: json, encoding: .utf8) ?? "{}"
        let input = String(data: try JSONSerialization.data(
            withJSONObject: [
                "path": workspacePath,
                "content": content,
                "overwrite": true,
            ] as [String: Any]
        ), encoding: .utf8) ?? "{}"
        let result = await workspace.executeTool(toolName: "workspace_file_write", input: input)
        XCTAssertTrue(result.contains(#""ok":true"#), result)
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "recipe-test",
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

    private func makeParams(tools: [Tool]) -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
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
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: tools,
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeMessage(role: MessageRole, parts: [UIMessagePart]) -> UIMessage {
        let now = Kotlinx_datetimeLocalDateTime(
            year: 2026, month: 8, day: 12, hour: 0, minute: 0, second: 0, nanosecond: 0
        )
        return UIMessage(
            id: KotlinUuid.companion.random(),
            role: role,
            parts: parts,
            annotations: [],
            createdAt: now,
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func userMessage(_ text: String) -> UIMessage {
        makeMessage(role: MessageRole.user, parts: [UIMessagePart.Text(text: text, metadata: nil)])
    }

    private func assistantText(_ text: String) -> UIMessage {
        makeMessage(role: MessageRole.assistant, parts: [UIMessagePart.Text(text: text, metadata: nil)])
    }

    private func makeAssistantMessage(parts: [UIMessagePart]) -> UIMessage {
        makeMessage(role: MessageRole.assistant, parts: parts)
    }

    private func toolCallMessage(toolCallId: String, toolName: String, input: String) -> UIMessage {
        makeMessage(
            role: MessageRole.assistant,
            parts: [UIMessagePart.Tool(
                toolCallId: toolCallId,
                toolName: toolName,
                input: input,
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )]
        )
    }

    private func fullIosDeclarations() -> [Tool] {
        let names =
            IOSWorkspaceToolCatalog.supportedToolNames
            .union(IOSIshToolCatalog.supportedToolNames)
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
            .union(IOSWebMountToolCatalog.supportedToolNames)
            .union(IOSSkillToolCatalog.toolNames)
            .union(IOSMcpManagementToolCatalog.toolNames)
            .union([
                "search_web", "scrape_web", "memory_tool", "generate_image",
                "mcp_call", "subagent_dispatch", "model_council_run", "ask_user",
                "spawn_agent", "list_agents", "interrupt_agent", "send_message",
                "followup_task", "wait_agent", "session_search", "session_read",
                "exec", "wait", "tools_list", "subagent_report",
                "permissions_status", "file_read_selected",
            ])
        return ToolKt.iosToolDeclarations(names: Array(names).sorted())
    }

    // MARK: Manifest builders (test data, not assertions)

    private func listingRecipeJSON(version: String, name: String = "catalog_probe") throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": name,
            "version": version,
            "description": "列出当前工具目录并返回总数。",
            "inputs": [:],
            "steps": [
                ["id": "list", "tool": "tools_list", "arguments": [:]],
            ],
            "outputs": ["tool_count": "${step.list.output.total}"],
        ])
    }

    private func listingRecipeV2JSON(version: String) throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": "catalog_probe",
            "version": version,
            "description": "列出工具目录两次。",
            "inputs": [:],
            "steps": [
                ["id": "list", "tool": "tools_list", "arguments": [:]],
                ["id": "list_again", "tool": "tools_list", "arguments": [:]],
            ],
            "outputs": ["tool_count": "${step.list_again.output.total}"],
        ])
    }

    private func twoMutationRecipeJSON(version: String) throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": "double_save",
            "version": version,
            "description": "列出工具目录并写入两个 Workspace 文件。",
            "inputs": ["path_a": "string", "path_b": "string"],
            "steps": [
                ["id": "list", "tool": "tools_list", "arguments": [:]],
                ["id": "save_a", "tool": "workspace_file_write",
                 "arguments": ["path": "${input.path_a}", "content": "${step.list.output.status}"]],
                ["id": "save_b", "tool": "workspace_file_write",
                 "arguments": ["path": "${input.path_b}", "content": "${step.save_a.output.id}"]],
            ],
            "outputs": [
                "path_a_out": "${step.save_a.output.path}",
                "path_b_out": "${step.save_b.output.path}",
            ],
        ])
    }

    private func mutatingRecipeJSON(version: String) throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": "digest_save",
            "version": version,
            "description": "列出工具目录并把结果写入 Workspace。",
            "inputs": ["output_path": "string"],
            "steps": [
                ["id": "list", "tool": "tools_list", "arguments": [:]],
                ["id": "save", "tool": "workspace_file_write",
                 "arguments": ["path": "${input.output_path}", "content": "${step.list.output.status}"]],
            ],
            "outputs": ["file_path": "${step.save.output.path}"],
        ])
    }

    private func jsonData(_ dict: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    private func parse(_ text: String) throws -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func toolOutputText(in messages: [UIMessage], toolCallId: String) -> String? {
        for message in messages {
            for part in message.parts {
                guard let tool = part as? UIMessagePart.Tool, tool.toolCallId == toolCallId else { continue }
                let text = tool.output.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
                return text.isEmpty ? nil : text
            }
        }
        return nil
    }

    private func toolSearchHitNames(_ output: String) -> [String] {
        guard let object = try? parse(output),
              let tools = object["tools"] as? [[String: Any]] else { return [] }
        return tools.compactMap { $0["name"] as? String }
    }

    private func searchHit(_ output: String, name: String) -> [String: Any]? {
        guard let object = try? parse(output),
              let tools = object["tools"] as? [[String: Any]] else { return nil }
        return tools.first { ($0["name"] as? String) == name }
    }

    private func parsedPayload(_ payload: String) -> [String: Any]? {
        guard let data = payload.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func checkpointFileCount(_ directory: URL) -> Int {
        (try? FileManager.default.contentsOfDirectory(atPath: directory.path))?.count ?? 0
    }

    /// The `approval_denied` event is written fire-and-forget by the runtime
    /// (same tier as Finished); poll briefly until it lands.
    private func waitForApprovalDenied(runId: String, dao: AgentRuntimeDao) async -> [LedgerRowSnapshot] {
        for _ in 0..<40 {
            let rows = await ledgerRows(runId: runId, dao: dao)
            if rows.contains(where: { $0.type == IOSAgentRunLedger.approvalDeniedEventType }) {
                return rows
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return await ledgerRows(runId: runId, dao: dao)
    }

    private func ledgerRows(runId: String, dao: AgentRuntimeDao) async -> [LedgerRowSnapshot] {
        await withCheckedContinuation { continuation in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result.map {
                    LedgerRowSnapshot(type: $0.type, seq: $0.seq, payload: $0.payload)
                })
            }
        }
    }

    private func directorySnapshot(_ root: URL) -> [String] {
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey],
            options: []
        ) else {
            return []
        }
        var entries: [String] = []
        while let item = enumerator.nextObject() as? URL {
            var isDirectory: ObjCBool = false
            FileManager.default.fileExists(atPath: item.path, isDirectory: &isDirectory)
            let kind = isDirectory.boolValue ? "dir" : "file"
            entries.append("\(kind):\(item.lastPathComponent)")
        }
        return entries.sorted()
    }
}

/// Sendable reduction of one `agent_event` row.
private struct LedgerRowSnapshot: Sendable {
    let type: String
    let seq: Int64
    let payload: String
}

/// Scripted provider that records every `params` it was called with, so tests
/// can assert what the round declared (P0-a Fix C / Wave B2 canary).
private final class ParamsRecordingProvider: IOSAgentTextProvider, @unchecked Sendable {
    private var script: [UIMessage]
    private(set) var recordedParams: [TextGenerationParams] = []
    init(_ script: [UIMessage]) { self.script = script }

    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        recordedParams.append(params)
        if !script.isEmpty {
            return chunk(with: script.removeFirst())
        }
        return chunk(with: UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: "stop", metadata: nil)],
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026, month: 8, day: 12, hour: 0, minute: 0, second: 0, nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        ))
    }

    private func chunk(with message: UIMessage?) -> MessageChunk {
        MessageChunk(
            id: "chunk-\(UUID().uuidString)",
            model: "test-model",
            choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: "stop")],
            usage: nil
        )
    }
}

/// Executes tool_search through a real KMP exposure bridge (local, no
/// network) so the hit becomes visible inside the bridge.
private final class BridgeToolSearchExecutor: IOSToolExecutor {
    private let bridge: IosToolExposureBridge
    init(bridge: IosToolExposureBridge) { self.bridge = bridge }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        .filled(bridge.executeToolSearch(argumentsJson: arguments))
    }
}

/// Executes one `recipe__*` call through the REAL `ChatToolRuntime` recipe
/// route with the round's pinned snapshot (the production path; only the
/// provider is scripted).
@MainActor
private final class RecipeRouteExecutor: IOSToolExecutor {
    private let runtime: ChatToolRuntime
    private let snapshot: IOSDynamicToolCatalogSnapshot
    private let bridge: IosToolExposureBridge
    private let runId: String
    private let providerSetting: ProviderSetting.OpenAI
    private let params: TextGenerationParams

    init(
        runtime: ChatToolRuntime,
        snapshot: IOSDynamicToolCatalogSnapshot,
        bridge: IosToolExposureBridge,
        runId: String
    ) {
        self.runtime = runtime
        self.snapshot = snapshot
        self.bridge = bridge
        self.runId = runId
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
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
        self.providerSetting = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "recipe-test",
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
        self.params = TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    func execute(name: String, arguments: String, isUserInitiated: Bool) async -> IOSAgentToolOutcome {
        let toolCall = UIMessagePart.Tool(
            toolCallId: "tc-\(name)-\(UUID().uuidString)",
            toolName: name,
            input: arguments,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let now = Kotlinx_datetimeLocalDateTime(
            year: 2026, month: 8, day: 12, hour: 0, minute: 0, second: 0, nanosecond: 0
        )
        let baseMessages = [UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [toolCall],
            annotations: [],
            createdAt: now,
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )]
        let pending = ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: providerSetting,
            params: params,
            runId: runId,
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: baseMessages
        )
        let result = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending,
            toolExposureBridge: bridge,
            recipeCatalogSnapshot: snapshot
        )
        switch result {
        case .completed(let messages):
            let output = messages.flatMap(\.parts)
                .compactMap { $0 as? UIMessagePart.Tool }
                .first { $0.toolCallId == toolCall.toolCallId }?
                .output.compactMap { ($0 as? UIMessagePart.Text)?.text }
                .joined() ?? ""
            return .filled(output)
        case .waitingForApproval:
            return .needsApproval("Recipe step requires approval.")
        }
    }
}

/// 无网络搜索传输（同 IOSExecNestedNoopSearchTransport 模式）。
private struct RecipeNoopSearchTransport: IOSSearchHTTPTransport {
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

/// Coordinator bindings state（照 IOSExecNestedBindingState 模式）。
private final class RecipeBindingState {
    var messages: [UIMessage] = []
    var pendingRecipeApproval: RecipeToolApprovalRequest?
    var revisions: [ChatMessageUpdateReason] = []
    var isLoading = false

    func bindings() -> ChatGenerationBindings {
        ChatGenerationBindings(
            getMessages: { self.messages },
            setMessages: { self.messages = $0 },
            bumpMessageRevision: { self.revisions.append($0) },
            shouldPaceStreamPresentation: { true },
            setIsLoading: { self.isLoading = $0 },
            setPendingMemoryApproval: { _ in },
            setPendingSearchApproval: { _ in },
            setPendingWebMountApproval: { _ in },
            setPendingWorkspaceApproval: { _ in },
            setPendingIshHandoffApproval: { _ in },
            setPendingMcpApproval: { _ in },
            setPendingCouncilApproval: { _ in },
            setPendingAskUser: { _ in },
            setPendingRecipeApproval: { self.pendingRecipeApproval = $0 },
            setContextCompactState: { _ in },
            persistMessages: { _ in true },
            capturePersistMessagesBaseline: { _ in nil },
            persistMessagesSnapshot: { _, _, _ in true },
            recordRun: { _, _, _, _, _ in true },
            startLiveActivity: { _, _, _ in },
            saveMiniAppIfPresent: { _, _ in nil },
            messagesByInjectingRuntimeContext: { $0 },
            userFacingGenerationError: { rawMessage, _ in rawMessage }
        )
    }
}
