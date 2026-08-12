import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 1 Wave A contract tests for the declarative Recipe foundation:
/// manifest validator, file store (preview/apply CAS/rollback + receipts) and
/// sequential runner. Uses REAL components — the Room-backed
/// `IOSAgentRunLedger` with an isolated DB, the real `IOSRecipeFileStore` /
/// `IOSPromotionReceiptStore` in temp directories, and the real validator /
/// runner with a fake primitive executor. No source-string anchors: every
/// assertion decodes or re-reads actual data.
@MainActor
final class IOSRecipeFoundationTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - Validator

    func testValidatorAcceptsLegalManifestAndComputesEnvelope() throws {
        let manifest = try IOSRecipeManifest.decode(
            data(manifestWithInputs: ["feed_url": "string", "count": "number"])
        )
        let result = IOSRecipeValidator.validate(manifest: manifest, catalog: defaultCatalog)
        XCTAssertTrue(result.isValid, "\(result.issues)")
        XCTAssertNil(result.issues.first { $0.code == .schemaMismatch })
        XCTAssertEqual(result.permissionEnvelope, .sideEffect)
    }

    func testValidatorEnvelopeIsConservativeUnion() throws {
        // All-pure recipe → pure envelope.
        let pureJSON = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
            ["id": "b", "tool": "search_web", "arguments": ["q": "${step.a.output.text}"]],
        ])
        let pureResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(pureJSON), catalog: defaultCatalog
        )
        XCTAssertTrue(pureResult.isValid)
        XCTAssertEqual(pureResult.permissionEnvelope, .pure)

        // One mutation step must raise the whole envelope (I-10, §10.3.7):
        // a mutation step is never exempted by the recipe layer.
        let mixedJSON = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
            ["id": "b", "tool": "workspace_file_write",
             "arguments": ["path": "/workspace/x.md", "content": "${step.a.output.text}"]],
        ])
        let mixedResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(mixedJSON), catalog: defaultCatalog
        )
        XCTAssertTrue(mixedResult.isValid)
        XCTAssertEqual(mixedResult.permissionEnvelope, .sideEffect)
    }

    func testValidatorRejectsUnknownToolId() throws {
        let json = try data(manifestWithSteps: [
            ["id": "a", "tool": "no_such_primitive", "arguments": ["x": 1]],
        ])
        let result = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(json), catalog: defaultCatalog
        )
        XCTAssertFalse(result.isValid)
        XCTAssertEqual(result.issues.first?.code, .unknownTool)
        XCTAssertEqual(result.issues.first?.path, "steps[0].tool")
        XCTAssertNil(result.permissionEnvelope)
    }

    func testValidatorRejectsForwardAndSelfStepBindings() throws {
        // Forward reference: step b binds step c's output (c runs after b).
        let forwardJSON = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
            ["id": "b", "tool": "search_web", "arguments": ["q": "${step.c.output.text}"]],
            ["id": "c", "tool": "summarize_text", "arguments": ["text": "${step.a.output.text}"]],
        ])
        let forwardResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(forwardJSON), catalog: defaultCatalog
        )
        XCTAssertEqual(forwardResult.issues.first?.code, .invalidStepReference)
        XCTAssertEqual(forwardResult.issues.first?.path, "steps[1].arguments.q")

        // Self reference: step a binds its own output.
        let selfJSON = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web",
             "arguments": ["url": "${step.a.output.text}"]],
        ])
        let selfResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(selfJSON), catalog: defaultCatalog
        )
        XCTAssertEqual(selfResult.issues.first?.code, .invalidStepReference)

        // Reference to a step that does not exist at all.
        let missingJSON = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${step.ghost.output.text}"]],
        ])
        let missingResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(missingJSON), catalog: defaultCatalog
        )
        XCTAssertEqual(missingResult.issues.first?.code, .unresolvedStepBinding)
    }

    func testValidatorRejectsUndeclaredInputBinding() throws {
        let json = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.not_declared}"]],
        ])
        let result = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(json), catalog: defaultCatalog
        )
        XCTAssertEqual(result.issues.first?.code, .unresolvedInputBinding)
        XCTAssertEqual(result.issues.first?.path, "steps[0].arguments.url")
    }

    func testValidatorRejectsStepLimitExceeded() throws {
        let steps = (0...8).map { index in
            ["id": "s\(index)", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]]
        }
        let json = try data(manifestWithSteps: steps)
        let result = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(json), catalog: defaultCatalog
        )
        XCTAssertEqual(result.issues.first?.code, .stepLimitExceeded)
        XCTAssertTrue(result.issues.first?.message.contains("8") == true)

        // Exactly the cap passes.
        let okSteps = (0..<8).map { index in
            ["id": "s\(index)", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]]
        }
        let okResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(data(manifestWithSteps: okSteps)),
            catalog: defaultCatalog
        )
        XCTAssertTrue(okResult.isValid)
    }

    func testValidatorRejectsInvalidNameAndSchemaAndTimeout() throws {
        var json = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
        ])
        // Bad name (uppercase, too short).
        var dict = try JSONSerialization.jsonObject(with: json) as! [String: Any]
        dict["name"] = "RSSDigest"
        let badName = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(jsonData(dict)), catalog: defaultCatalog
        )
        XCTAssertEqual(badName.issues.first?.code, .invalidName)

        // Wrong schema.
        dict["name"] = "rss_digest"
        dict["schema"] = "amber.recipe.v2"
        let badSchema = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(jsonData(dict)), catalog: defaultCatalog
        )
        XCTAssertEqual(badSchema.issues.first?.code, .schemaMismatch)

        // Timeout above the cap.
        dict["schema"] = "amber.recipe.v1"
        var steps = dict["steps"] as! [[String: Any]]
        steps[0]["timeoutSeconds"] = 9999
        dict["steps"] = steps
        let badTimeout = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(jsonData(dict)), catalog: defaultCatalog
        )
        XCTAssertEqual(badTimeout.issues.first?.code, .invalidTimeout)
    }

    func testValidatorRejectsMalformedBindingSyntax() throws {
        // Unclosed binding in an argument — must be flagged, not treated as
        // a literal.
        let json = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url"]],
        ])
        let result = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(json), catalog: defaultCatalog
        )
        XCTAssertEqual(result.issues.first?.code, .invalidBindingSyntax)
        XCTAssertEqual(result.issues.first?.path, "steps[0].arguments.url")

        // Malformed binding nested inside a literal object.
        let nestedJSON = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web",
             "arguments": ["opts": ["headers": ["X": "${step.b.output.x"]]]],
        ])
        let nestedResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(nestedJSON), catalog: defaultCatalog
        )
        XCTAssertEqual(nestedResult.issues.first?.code, .invalidBindingSyntax)
    }

    func testValidatorRejectsRecipeSelfReferenceAndBadOutputs() throws {
        // A step cannot call another recipe (§10.1) — defense in depth.
        let selfRecipe = try data(manifestWithSteps: [
            ["id": "a", "tool": "recipe__rss_digest", "arguments": ["url": "${input.feed_url}"]],
        ])
        let recipeRefResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(selfRecipe), catalog: defaultCatalog
        )
        XCTAssertEqual(recipeRefResult.issues.first?.code, .recipeToolReference)

        // Outputs must be bindings to existing steps.
        let literalOutput = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
        ], outputs: ["summary": "not a binding"])
        let literalResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(literalOutput), catalog: defaultCatalog
        )
        XCTAssertEqual(literalResult.issues.first?.code, .outputMustBeBinding)

        let missingStepOutput = try data(manifestWithSteps: [
            ["id": "a", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
        ], outputs: ["summary": "${step.ghost.output.text}"])
        let missingResult = IOSRecipeValidator.validate(
            manifest: try IOSRecipeManifest.decode(missingStepOutput), catalog: defaultCatalog
        )
        XCTAssertEqual(missingResult.issues.first?.code, .unresolvedOutputStep)
    }

    func testCanonicalEncodingIsStableFixedPoint() throws {
        let json = try data(manifestWithInputs: ["feed_url": "string"])
        let manifest = try IOSRecipeManifest.decode(json)
        let canonical = try manifest.canonicalJSONData()
        // Decode → re-encode must be a fixed point, so the store hash is
        // stable regardless of how the author formatted the JSON.
        let reparsed = try IOSRecipeManifest.decode(canonical)
        XCTAssertEqual(try reparsed.canonicalJSONData(), canonical)
    }

    // MARK: - Store

    func testPreviewIsZeroWrite() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: store.recipesDirectory.path),
            "fresh store must have no recipes directory"
        )

        let preparation = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: "preview_zero"))
        XCTAssertEqual(preparation.kind, .new)
        XCTAssertNil(preparation.base)
        XCTAssertEqual(preparation.candidate.name, "preview_zero")

        XCTAssertFalse(
            FileManager.default.fileExists(atPath: store.recipesDirectory.path),
            "preview must not create any directory or file (zero-write)"
        )
    }

    func testApplyPublishesCanonicalBytesAndHashMatchesDisk() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let name = "store_apply"

        let preparation = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: name))
        let receipt = try store.applyRecipe(
            name: name,
            recipeJSON: preparation.candidate.canonicalJSON,
            expectedBaseHash: nil,
            expectedCandidateHash: preparation.candidate.hash
        )
        XCTAssertEqual(receipt.outcome, .applied)

        let live = try store.readLiveRecipe(name: name)
        XCTAssertEqual(live.hash, preparation.candidate.hash, "live hash must equal the published candidate hash")
        XCTAssertEqual(live.canonicalJSON, preparation.candidate.canonicalJSON)
        XCTAssertEqual(live.version, "1.0.0")
    }

    func testStaleBaseRejectedWithZeroWrites() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let name = "stale_base"

        let v1 = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v1.candidate.canonicalJSON,
            expectedBaseHash: nil, expectedCandidateHash: v1.candidate.hash
        )
        let snapshotAfterV1 = directorySnapshot(store.recipesDirectory)

        let v2 = try store.prepareRecipe(recipeJSON: recipeV2JSON(name: name))
        XCTAssertThrowsError(
            try store.applyRecipe(
                name: name, recipeJSON: v2.candidate.canonicalJSON,
                expectedBaseHash: "stale-base-hash", expectedCandidateHash: v2.candidate.hash
            )
        ) { error in
            XCTAssertEqual(
                error as? IOSRecipeFileStoreError,
                .recipePackageBaseChanged(expected: "stale-base-hash", actual: v1.candidate.hash)
            )
        }

        XCTAssertEqual(
            directorySnapshot(store.recipesDirectory), snapshotAfterV1,
            "a stale-base apply must not write anything"
        )
        XCTAssertEqual(try store.readLiveRecipe(name: name).hash, v1.candidate.hash)
    }

    func testStaleCandidateRejectedWithZeroWrites() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let name = "stale_candidate"

        let v1 = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v1.candidate.canonicalJSON,
            expectedBaseHash: nil, expectedCandidateHash: v1.candidate.hash
        )
        let snapshotAfterV1 = directorySnapshot(store.recipesDirectory)

        let v2 = try store.prepareRecipe(recipeJSON: recipeV2JSON(name: name))
        XCTAssertThrowsError(
            try store.applyRecipe(
                name: name, recipeJSON: v2.candidate.canonicalJSON,
                expectedBaseHash: v1.candidate.hash, expectedCandidateHash: "stale-candidate-hash"
            )
        ) { error in
            XCTAssertEqual(
                error as? IOSRecipeFileStoreError,
                .recipePackageCandidateChanged(expected: "stale-candidate-hash", actual: v2.candidate.hash)
            )
        }

        XCTAssertEqual(
            directorySnapshot(store.recipesDirectory), snapshotAfterV1,
            "a stale-candidate apply must not write anything"
        )
        XCTAssertEqual(try store.readLiveRecipe(name: name).hash, v1.candidate.hash)
    }

    func testRollbackRestoresPreviousAndRejectsManifestMismatch() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let name = "rollback_recipe"

        let v1 = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v1.candidate.canonicalJSON,
            expectedBaseHash: nil, expectedCandidateHash: v1.candidate.hash
        )
        let v2 = try store.prepareRecipe(recipeJSON: recipeV2JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v2.candidate.canonicalJSON,
            expectedBaseHash: v1.candidate.hash, expectedCandidateHash: v2.candidate.hash
        )
        guard case .available(let manifestAfterV2) = try store.rollbackAvailability(name: name) else {
            return XCTFail("update import must publish a rollback manifest")
        }
        XCTAssertEqual(manifestAfterV2.kind, .update)
        XCTAssertEqual(manifestAfterV2.promotedHash, v2.candidate.hash)
        XCTAssertEqual(manifestAfterV2.baseHash, v1.candidate.hash)

        // A newer import replaces the slot; the seen manifest is then stale.
        let v3 = try store.prepareRecipe(recipeJSON: recipeV3JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v3.candidate.canonicalJSON,
            expectedBaseHash: v2.candidate.hash, expectedCandidateHash: v3.candidate.hash
        )
        XCTAssertThrowsError(
            try store.rollbackRecipe(name: name, expectedManifest: manifestAfterV2)
        ) { error in
            XCTAssertEqual(
                error as? IOSRecipeFileStoreError,
                .recipeRollbackUnavailable("可回退版本已变化，请刷新后重试。")
            )
        }
        XCTAssertEqual(
            try store.readLiveRecipe(name: name).hash, v3.candidate.hash,
            "a rejected rollback must not change the live package"
        )

        // Rollback with the current manifest restores v2 (v3's base — the
        // single previous slot holds exactly one generation, §18.1).
        guard case .available(let currentManifest) = try store.rollbackAvailability(name: name) else {
            return XCTFail("current slot must be available")
        }
        XCTAssertEqual(currentManifest.promotedHash, v3.candidate.hash)
        _ = try store.rollbackRecipe(name: name, expectedManifest: currentManifest)
        XCTAssertEqual(try store.readLiveRecipe(name: name).hash, v2.candidate.hash)
        XCTAssertEqual(try store.rollbackAvailability(name: name), .unavailable("没有可回退的上一次导入。"))
    }

    func testNewRecipeRollbackRemovesPackageAndClearsReceipts() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let name = "new_rollback"

        let v1 = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v1.candidate.canonicalJSON,
            expectedBaseHash: nil, expectedCandidateHash: v1.candidate.hash
        )
        guard case .available(let manifest) = try store.rollbackAvailability(name: name) else {
            return XCTFail("new import must publish a rollback manifest")
        }
        XCTAssertEqual(manifest.kind, .new)
        XCTAssertNil(manifest.baseHash)

        _ = try store.rollbackRecipe(name: name, expectedManifest: manifest)
        XCTAssertThrowsError(try store.readLiveRecipe(name: name)) { error in
            XCTAssertEqual(error as? IOSRecipeFileStoreError, .recipeMissing(name))
        }
        XCTAssertNil(receiptStore.snapshot(artifactId: name), "removed artifact must not keep receipts")
    }

    func testApplyAndRollbackReceiptsMatchDiskLiveHash() throws {
        let root = tempRoot()
        let store = IOSRecipeFileStore(baseDirectory: root)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: root)
        let name = "receipt_hash"

        let v1 = try store.prepareRecipe(recipeJSON: recipeV1JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v1.candidate.canonicalJSON,
            expectedBaseHash: nil, expectedCandidateHash: v1.candidate.hash
        )
        let afterV1 = try XCTUnwrap(receiptStore.snapshot(artifactId: name))
        XCTAssertNil(afterV1.active?.fromHash, "new import has no previous live hash")
        XCTAssertEqual(afterV1.active?.toHash, v1.candidate.hash)
        XCTAssertEqual(
            afterV1.active?.toHash, try store.readLiveRecipe(name: name).hash,
            "receipt toHash must equal the live package hash on disk"
        )

        let v2 = try store.prepareRecipe(recipeJSON: recipeV2JSON(name: name))
        _ = try store.applyRecipe(
            name: name, recipeJSON: v2.candidate.canonicalJSON,
            expectedBaseHash: v1.candidate.hash, expectedCandidateHash: v2.candidate.hash
        )
        let afterV2 = try XCTUnwrap(receiptStore.snapshot(artifactId: name))
        XCTAssertEqual(afterV2.active?.toHash, v2.candidate.hash)
        XCTAssertEqual(afterV2.previous?.toHash, v1.candidate.hash, "old active stays as previous")
        XCTAssertEqual(afterV2.active?.toHash, try store.readLiveRecipe(name: name).hash)

        guard case .available(let manifest) = try store.rollbackAvailability(name: name) else {
            return XCTFail("update import must publish a rollback manifest")
        }
        _ = try store.rollbackRecipe(name: name, expectedManifest: manifest)
        let afterRollback = try XCTUnwrap(receiptStore.snapshot(artifactId: name))
        XCTAssertEqual(afterRollback.active?.toHash, v1.candidate.hash, "rollback toHash must be the restored live hash")
        XCTAssertEqual(afterRollback.active?.toHash, try store.readLiveRecipe(name: name).hash)
    }

    // MARK: - Runner

    func testRunnerResolvesBindingsInOrderAndPassesCanonicalArguments() async throws {
        let executor = FakePrimitiveExecutor()
        let runner = try makeRunner(
            manifestJSON: recipeV1JSON(name: "runner_bindings"),
            executor: executor,
            ledger: nil
        )
        await executor.set(output: #"{"text":"fetched body","title":"T"}"#, for: "scrape_web")
        await executor.set(output: #"{"text":"摘要内容"}"#, for: "summarize_text")
        await executor.set(output: #"{"path":"/workspace/out.md"}"#, for: "workspace_file_write")

        let outcome = await runner.run(inputs: [
            "feed_url": .string("https://example.com/rss.xml"),
            "output_path": .string("/workspace/out.md"),
        ])
        guard case .succeeded(let outputs, let completedSteps) = outcome else {
            return XCTFail("expected success, got \(outcome)")
        }
        XCTAssertEqual(completedSteps, ["fetch", "summarize", "save"])
        XCTAssertEqual(outputs["file_path"], .string("/workspace/out.md"))

        let calls = await executor.calls
        XCTAssertEqual(calls.count, 3)
        // Step 1: input binding + literal int/bool — canonical sorted keys.
        XCTAssertEqual(calls[0].tool, "scrape_web")
        let firstArgs = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(calls[0].argsJSON.utf8)) as? [String: Any])
        XCTAssertEqual(firstArgs["url"] as? String, "https://example.com/rss.xml")
        XCTAssertEqual(firstArgs["max"] as? Int, 5)
        XCTAssertEqual(firstArgs["overwrite"] as? Bool, true)
        // Step 2: prior step output field binding.
        let secondArgs = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(calls[1].argsJSON.utf8)) as? [String: Any])
        XCTAssertEqual(secondArgs["text"] as? String, "fetched body")
        XCTAssertEqual(secondArgs["lang"] as? String, "zh-CN")
        // Step 3: input + two-step chain.
        let thirdArgs = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(calls[2].argsJSON.utf8)) as? [String: Any])
        XCTAssertEqual(thirdArgs["path"] as? String, "/workspace/out.md")
        XCTAssertEqual(thirdArgs["content"] as? String, "摘要内容")
    }

    func testRunnerStopsOnStepFailureAndReportsCompletedSteps() async throws {
        let executor = FakePrimitiveExecutor()
        let runner = try makeRunner(
            manifestJSON: recipeV1JSON(name: "runner_failure"),
            executor: executor,
            ledger: nil
        )
        await executor.set(output: #"{"text":"fetched body"}"#, for: "scrape_web")
        await executor.set(failure: "summary engine down", for: "summarize_text")

        let outcome = await runner.run(inputs: [
            "feed_url": .string("https://example.com/rss.xml"),
            "output_path": .string("/workspace/out.md"),
        ])
        guard case .failed(let failedStep, let error, let completedSteps) = outcome else {
            return XCTFail("expected failure, got \(outcome)")
        }
        XCTAssertEqual(failedStep, "summarize")
        XCTAssertEqual(completedSteps, ["fetch"], "steps after the failure must not run")
        guard case .stepFailed(let stepId, let tool, let message) = error else {
            return XCTFail("expected stepFailed, got \(error)")
        }
        XCTAssertEqual(stepId, "summarize")
        XCTAssertEqual(tool, "summarize_text")
        XCTAssertTrue(message.contains("summary engine down"))
        let callCount = await executor.calls.count
        XCTAssertEqual(callCount, 2, "no steps may run after the failure")

        // Structured error JSON has the tool envelope shape (§10.3.6).
        let structured = try XCTUnwrap(runner.structuredErrorJSON(for: outcome))
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(structured.utf8)) as? [String: Any]
        )
        XCTAssertEqual(object["ok"] as? Bool, false)
        XCTAssertEqual(object["tool"] as? String, "recipe__runner_failure")
        XCTAssertEqual(object["step"] as? String, "summarize")
        XCTAssertNotNil(object["reason"])
    }

    func testRunnerRecordsPerStepLedgerEventsThroughRealLedger() async throws {
        let (_, dao) = makeDatabase()
        let ledger = IOSAgentRunLedger(dao: dao)
        let executor = FakePrimitiveExecutor()
        let runId = "recipe-run-\(UUID().uuidString)"
        let runner = try makeRunner(
            manifestJSON: recipeV1JSON(name: "ledger_recipe"),
            executor: executor,
            ledger: ledger,
            runId: runId
        )
        await executor.set(output: #"{"text":"fetched body"}"#, for: "scrape_web")
        await executor.set(output: #"{"text":"摘要"}"#, for: "summarize_text")
        await executor.set(output: #"{"path":"/workspace/out.md"}"#, for: "workspace_file_write")

        let outcome = await runner.run(inputs: [
            "feed_url": .string("https://example.com/rss.xml"),
            "output_path": .string("/workspace/out.md"),
        ])
        guard case .succeeded = outcome else { return XCTFail("expected success, got \(outcome)") }

        let events: [LedgerEventSnapshot]? = await withCheckedContinuation { continuation in
            dao.listEventsForRun(id: runId) { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: nil)
                    return
                }
                // AgentEventEntity is a non-Sendable KMP object; reduce to a
                // Sendable snapshot inside the callback (strict concurrency).
                continuation.resume(returning: result.map {
                    LedgerEventSnapshot(type: $0.type, seq: $0.seq, payload: $0.payload)
                })
            }
        }
        let rows = try XCTUnwrap(events).compactMap {
            IOSToolCallLedgerRow.decode(type: $0.type, seq: $0.seq, payload: $0.payload)
        }
        let started = rows.filter { $0.type == IOSToolCallLedgerClassifier.startedType }
        let finished = rows.filter { $0.type == IOSToolCallLedgerClassifier.finishedType }
        XCTAssertEqual(started.count, 3, "every step must write a Started event")
        XCTAssertEqual(finished.count, 3, "every step must write a Finished event")
        XCTAssertEqual(
            Set(started.compactMap(\.effectClass)),
            [.pure, .idempotent, .sideEffect],
            "each step must be recorded with its own effect class"
        )

        // Each Started must precede its own Finished (W1 pairing).
        let ordered = rows.sorted { $0.seq < $1.seq }
        for toolCallId in Set(ordered.compactMap(\.toolCallId)) {
            let pair = ordered.filter { $0.toolCallId == toolCallId }
            XCTAssertEqual(pair.count, 2)
            XCTAssertEqual(pair.first?.type, IOSToolCallLedgerClassifier.startedType)
            XCTAssertEqual(pair.last?.type, IOSToolCallLedgerClassifier.finishedType)
        }

        // Finished payloads carry the evolution-contract attribution keys.
        let finishedPayloads = try XCTUnwrap(events).filter {
            $0.type == IOSToolCallLedgerClassifier.finishedType
        }.compactMap { event -> [String: Any]? in
            try? JSONSerialization.jsonObject(with: Data(event.payload.utf8)) as? [String: Any]
        }
        for payload in finishedPayloads {
            XCTAssertEqual(payload["artifactId"] as? String, "recipe__ledger_recipe")
            XCTAssertEqual(payload["artifactVersion"] as? String, "1.0.0")
            XCTAssertEqual(payload["outcomeKind"] as? String, "success")
            XCTAssertNil(payload["errorCode"])
            XCTAssertNotNil(payload["sourceRef"], "the recipe execution id must be recorded")
        }
    }

    func testRunnerEnvelopeUpperBoundAndInputValidation() async throws {
        let executor = FakePrimitiveExecutor()
        let runner = try makeRunner(
            manifestJSON: recipeV1JSON(name: "runner_envelope"),
            executor: executor,
            ledger: nil
        )
        let plan = try runner.resolvePlan(inputs: [
            "feed_url": .string("https://example.com/rss.xml"),
            "output_path": .string("/workspace/out.md"),
        ])
        XCTAssertEqual(
            plan.permissionEnvelope, .sideEffect,
            "a recipe containing a mutation step must carry the conservative upper bound (I-10)"
        )
        XCTAssertEqual(plan.recipeVersion, "1.0.0")
        XCTAssertEqual(plan.steps.map(\.id), ["fetch", "summarize", "save"])
        XCTAssertEqual(plan.steps.first?.toolVersion, "1.0.0", "catalog minVersion flows into the plan")

        // Wrong input type → fails before any step runs.
        let typeOutcome = await runner.run(inputs: [
            "feed_url": .number(42),
            "output_path": .string("/workspace/out.md"),
        ])
        guard case .failed(let failedStep, let error, let completedSteps) = typeOutcome else {
            return XCTFail("expected input validation failure")
        }
        XCTAssertNil(failedStep)
        XCTAssertEqual(completedSteps, [])
        guard case .inputInvalid = error else { return XCTFail("expected inputInvalid, got \(error)") }
        let callCount = await executor.calls.count
        XCTAssertEqual(callCount, 0)

        // Missing declared input → fails.
        let missingOutcome = await runner.run(inputs: ["feed_url": .string("x")])
        guard case .failed(_, let missingError, _) = missingOutcome,
              case .inputInvalid = missingError else {
            return XCTFail("expected inputInvalid for missing input, got \(missingOutcome)")
        }

        // Undeclared input → fails.
        let extraOutcome = await runner.run(inputs: [
            "feed_url": .string("x"), "output_path": .string("/o"), "sneaky": .bool(true),
        ])
        guard case .failed(_, let extraError, _) = extraOutcome,
              case .inputInvalid = extraError else {
            return XCTFail("expected inputInvalid for undeclared input, got \(extraOutcome)")
        }
    }

    func testRunnerRejectsInvalidPlanBeforeExecution() async throws {
        let executor = FakePrimitiveExecutor()
        let json = try data(manifestWithSteps: [
            ["id": "a", "tool": "ghost_tool", "arguments": ["url": "${input.feed_url}"]],
        ])
        let runner = try makeRunner(manifestJSON: json, executor: executor, ledger: nil)

        XCTAssertThrowsError(try runner.resolvePlan(inputs: ["feed_url": .string("x")])) { error in
            guard case IOSRecipeRunError.planInvalid(let issues) = error else {
                return XCTFail("expected planInvalid, got \(error)")
            }
            XCTAssertEqual(issues.first?.code, .unknownTool)
        }

        let outcome = await runner.run(inputs: ["feed_url": .string("x")])
        guard case .failed(let failedStep, let error, let completedSteps) = outcome else {
            return XCTFail("expected failure")
        }
        XCTAssertNil(failedStep, "plan failure happens before any step")
        XCTAssertEqual(completedSteps, [])
        guard case .planInvalid = error else { return XCTFail("expected planInvalid, got \(error)") }
        let callCount = await executor.calls.count
        XCTAssertEqual(callCount, 0)
    }

    func testRunnerEnforcesStepTimeout() async throws {
        let executor = FakePrimitiveExecutor()
        let name = "runner_timeout"
        var json = try JSONSerialization.jsonObject(with: recipeV1JSON(name: name)) as! [String: Any]
        var steps = json["steps"] as! [[String: Any]]
        steps[0]["timeoutSeconds"] = 1
        steps.removeLast(2) // keep a single slow step
        json["steps"] = steps
        json["outputs"] = ["text": "${step.fetch.output.text}"]
        let runner = try makeRunner(
            manifestJSON: jsonData(json), executor: executor, ledger: nil
        )
        await executor.set(delay: 30, for: "scrape_web")

        let start = Date()
        let outcome = await runner.run(inputs: [
            "feed_url": .string("https://example.com/rss.xml"),
            "output_path": .string("/workspace/out.md"),
        ])
        let elapsed = Date().timeIntervalSince(start)
        XCTAssertLessThan(elapsed, 10, "timeout must abort the slow step, not wait for it")

        guard case .failed(let failedStep, let error, let completedSteps) = outcome else {
            return XCTFail("expected timeout failure")
        }
        XCTAssertEqual(failedStep, "fetch")
        XCTAssertEqual(completedSteps, [])
        guard case .stepTimeout(let stepId, _, let seconds) = error else {
            return XCTFail("expected stepTimeout, got \(error)")
        }
        XCTAssertEqual(stepId, "fetch")
        XCTAssertEqual(seconds, 1)
    }

    func testRunnerOutputResolutionFailureAfterAllSteps() async throws {
        let executor = FakePrimitiveExecutor()
        let runner = try makeRunner(
            manifestJSON: recipeV1JSON(name: "runner_output"),
            executor: executor,
            ledger: nil
        )
        // The step's output is not a JSON object with the bound field — but
        // only the LAST step's output is broken, so the failure must surface
        // at output resolution (after all steps completed), not earlier.
        await executor.set(output: #"{"text":"fetched body","title":"T"}"#, for: "scrape_web")
        await executor.set(output: #"{"text":"摘要"}"#, for: "summarize_text")
        await executor.set(output: "not json at all", for: "workspace_file_write")

        let outcome = await runner.run(inputs: [
            "feed_url": .string("https://example.com/rss.xml"),
            "output_path": .string("/workspace/out.md"),
        ])
        guard case .failed(let failedStep, let error, let completedSteps) = outcome else {
            return XCTFail("expected output resolution failure")
        }
        XCTAssertNil(failedStep, "output resolution happens after the last step")
        XCTAssertEqual(completedSteps, ["fetch", "summarize", "save"])
        guard case .outputResolution(let outputName, _) = error else {
            return XCTFail("expected outputResolution, got \(error)")
        }
        XCTAssertEqual(outputName, "file_path")
    }

    // MARK: - Fixtures

    /// Real ledger + isolated Room DB. The temp directory MUST exist before
    /// `createDatabase(atFilePath:)` — Room does not create parent directories.
    private func makeDatabase() -> (db: AgentRuntimeDatabase, dao: AgentRuntimeDao) {
        let root = tempRoot()
        let path = root.appendingPathComponent("agent_runtime.db").path
        let db = IosDatabaseFactory.shared.createDatabase(atFilePath: path)
        return (db, db.agentRuntimeDao())
    }

    private func makeRunner(
        manifestJSON: Data,
        executor: FakePrimitiveExecutor,
        ledger: (any IOSAgentRunLedgering)?,
        runId: String = "recipe-test-run"
    ) throws -> IOSRecipeRunner {
        let manifest = try IOSRecipeManifest.decode(manifestJSON)
        return IOSRecipeRunner(
            manifest: manifest,
            catalog: defaultCatalog,
            executePrimitive: { tool, args in
                try await executor.execute(tool: tool, argsJSON: args)
            },
            ledger: ledger,
            runId: runId
        )
    }

    private var defaultCatalog: IOSRecipeCatalogLookup {
        { tool in
            switch tool {
            case "scrape_web", "search_web":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .pure)
            case "summarize_text":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "2.1.0", effectClass: .idempotent)
            case "workspace_file_write":
                return IOSRecipeCatalogEntry(exists: true, minVersion: "1.0.0", effectClass: .sideEffect)
            default:
                return nil
            }
        }
    }

    // MARK: Manifest builders (test data, not assertions)

    private func recipeV1JSON(name: String) throws -> Data {
        try data(manifestWithSteps: [
            ["id": "fetch", "tool": "scrape_web",
             "arguments": ["url": "${input.feed_url}", "max": 5, "overwrite": true]],
            ["id": "summarize", "tool": "summarize_text",
             "arguments": ["text": "${step.fetch.output.text}", "lang": "zh-CN"]],
            ["id": "save", "tool": "workspace_file_write",
             "arguments": ["path": "${input.output_path}", "content": "${step.summarize.output.text}"]],
        ], outputs: ["file_path": "${step.save.output.path}"], name: name)
    }

    private func recipeV2JSON(name: String) throws -> Data {
        var dict = try JSONSerialization.jsonObject(with: recipeV1JSON(name: name)) as! [String: Any]
        dict["version"] = "2.0.0"
        return try jsonData(dict)
    }

    private func recipeV3JSON(name: String) throws -> Data {
        var dict = try JSONSerialization.jsonObject(with: recipeV1JSON(name: name)) as! [String: Any]
        dict["version"] = "3.0.0"
        return try jsonData(dict)
    }

    private func data(manifestWithInputs inputs: [String: String]) throws -> Data {
        try data(manifestWithSteps: [
            ["id": "fetch", "tool": "scrape_web", "arguments": ["url": "${input.feed_url}"]],
            ["id": "summarize", "tool": "summarize_text",
             "arguments": ["text": "${step.fetch.output.text}"]],
            ["id": "save", "tool": "workspace_file_write",
             "arguments": ["path": "/workspace/x.md", "content": "${step.summarize.output.text}"]],
        ], inputs: inputs)
    }

    private func data(
        manifestWithSteps steps: [[String: Any]],
        outputs: [String: String]? = nil,
        inputs: [String: String]? = nil,
        name: String = "digest_recipe"
    ) throws -> Data {
        // Default outputs reference the LAST step so smaller fixtures stay
        // valid (the validator requires output bindings to resolve).
        let lastStepId = (steps.last?["id"] as? String) ?? "save"
        var dict: [String: Any] = [
            "schema": "amber.recipe.v1",
            "name": name,
            "version": "1.0.0",
            "description": "测试用 Recipe。",
            "inputs": inputs ?? ["feed_url": "string", "output_path": "string"],
            "steps": steps,
            "outputs": outputs ?? ["file_path": "${step.\(lastStepId).output.path}"],
        ]
        return try jsonData(dict)
    }

    private func jsonData(_ dict: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-recipe-foundation-\(UUID().uuidString)", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }

    /// Deterministic recursive listing of a directory tree (used to prove
    /// zero-write on rejected operations).
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

/// Sendable reduction of an `AgentEventEntity` ledger row (the KMP entity is
/// not Sendable; this is built inside the DAO callback).
private struct LedgerEventSnapshot: Sendable {
    let type: String
    let seq: Int64
    let payload: String
}

/// Fake primitive executor: an actor so the injected `@Sendable` closure can
/// capture it safely under Swift 6 strict concurrency.
actor FakePrimitiveExecutor {
    struct Call: Equatable {
        let tool: String
        let argsJSON: String
    }

    private(set) var calls: [Call] = []
    private var outputs: [String: String] = [:]
    private var failures: [String: String] = [:]
    private var delays: [String: TimeInterval] = [:]

    func set(output: String, for tool: String) {
        outputs[tool] = output
    }

    func set(failure message: String, for tool: String) {
        failures[tool] = message
    }

    func set(delay: TimeInterval, for tool: String) {
        delays[tool] = delay
    }

    func execute(tool: String, argsJSON: String) async throws -> String {
        calls.append(Call(tool: tool, argsJSON: argsJSON))
        if let message = failures[tool] {
            throw NSError(domain: "FakePrimitiveExecutor", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
        if let delay = delays[tool] {
            try await Task.sleep(for: .seconds(delay))
        }
        return outputs[tool] ?? "{}"
    }
}
