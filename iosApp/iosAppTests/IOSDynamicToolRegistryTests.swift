import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Phase 1 Wave B1 contract tests for the versioned dynamic tool registry:
/// snapshot revisioning + content hash + embedded-manifest in-flight pinning,
/// and the round-boundary bridge-rebuild seam (recipe declared deferred →
/// tool_search exposes → exposure carried across rebuilds; rollback removes).
///
/// Uses REAL components: the real `IOSRecipeFileStore` in temp directories
/// (created before first use), the real registry (actor over that store), the
/// real KMP `IosToolExposureBridge` and the real static iOS declarations — no
/// source-string anchors, every assertion decodes or re-reads actual data.
@MainActor
final class IOSDynamicToolRegistryTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    // MARK: - Registry: snapshots, revisioning, content hash, pinning

    func testEmptyStorePublishesEmptySnapshotWithStableRevisionAndHash() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        let first = try unwrapSnapshot(await registry.refresh())
        XCTAssertTrue(first.recipeTools.isEmpty)
        XCTAssertEqual(first.contentHash, IOSDynamicToolRegistry.emptyContentHash)

        // Unchanged store → same revision, same hash (no spurious bump).
        let second = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(second.revision, first.revision)
        XCTAssertEqual(second.contentHash, first.contentHash)
    }

    func testPromotionPublishesSnapshotWithDiskConsistentEmbeddedManifest() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)
        let empty = try unwrapSnapshot(await registry.refresh())
        XCTAssertTrue(empty.recipeTools.isEmpty)

        let v1 = try digestRecipeJSON(version: "1.0.0")
        try apply(store: store, json: v1)

        let snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(snapshot.revision, empty.revision, "promotion must bump the revision")
        XCTAssertNotEqual(snapshot.contentHash, empty.contentHash)

        let descriptor = try XCTUnwrap(snapshot.recipeTools.first)
        XCTAssertEqual(descriptor.toolId, "recipe__rss_digest")
        XCTAssertEqual(descriptor.recipeName, "rss_digest")
        XCTAssertEqual(descriptor.version, "1.0.0")
        XCTAssertEqual(descriptor.manifest.name, "rss_digest")
        XCTAssertEqual(descriptor.manifest.version, "1.0.0")

        // Embedded manifest is byte-identical to what the store published
        // (executed = stored = hashed, invariant 5).
        let live = try store.readLiveRecipe(name: "rss_digest")
        XCTAssertEqual(descriptor.manifestHash, live.hash)
        XCTAssertEqual(try descriptor.manifest.canonicalJSONData(), live.canonicalJSON)

        // Stable across refreshes with no store change.
        let again = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(again.revision, snapshot.revision)
        XCTAssertEqual(again.contentHash, snapshot.contentHash)
    }

    func testPromotionToV2BumpsRevisionAndPinsOldSnapshotManifest() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        try apply(store: store, json: try digestRecipeJSON(version: "1.0.0"))
        let v1Snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(v1Snapshot.recipeTools.first?.manifest.version, "1.0.0")

        // Promote v2 (update kind: base hash is the v1 package hash).
        let v2 = try digestRecipeJSON(version: "2.0.0")
        let prep = try store.prepareRecipe(recipeJSON: v2)
        XCTAssertEqual(prep.kind, .update)
        _ = try store.applyRecipe(
            name: "rss_digest",
            recipeJSON: v2,
            expectedBaseHash: prep.base?.hash,
            expectedCandidateHash: prep.candidate.hash
        )

        let v2Snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(v2Snapshot.revision, v1Snapshot.revision)
        XCTAssertNotEqual(v2Snapshot.contentHash, v1Snapshot.contentHash)
        XCTAssertEqual(v2Snapshot.recipeTools.first?.manifest.version, "2.0.0")
        XCTAssertEqual(v2Snapshot.recipeTools.first?.version, "2.0.0")

        // In-flight pinning (§13.3): the v1 snapshot REFERENCE still carries
        // the v1 manifest even though the registry moved to v2.
        XCTAssertEqual(v1Snapshot.recipeTools.first?.manifest.version, "1.0.0")
        XCTAssertEqual(v1Snapshot.recipeTools.first?.manifestHash, prep.base?.hash,
                       "the pinned snapshot must keep the exact v1 package hash")
    }

    func testRollbackPublishesNewRevisionAndRemovesRecipeFromSnapshot() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        try apply(store: store, json: try digestRecipeJSON(version: "1.0.0"))
        let promoted = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(promoted.recipeTools.count, 1)

        // Rollback of a kind=new recipe removes the live package.
        let availability = try store.rollbackAvailability(name: "rss_digest")
        guard case .available(let manifest) = availability else {
            return XCTFail("expected rollback availability, got \(availability)")
        }
        _ = try store.rollbackRecipe(name: "rss_digest", expectedManifest: manifest)

        let rolledBack = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(rolledBack.revision, promoted.revision)
        XCTAssertTrue(rolledBack.recipeTools.isEmpty)
        XCTAssertEqual(rolledBack.contentHash, IOSDynamicToolRegistry.emptyContentHash)
    }

    func testRollbackOfUpdateRestoresPreviousManifestInSnapshot() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        try apply(store: store, json: try digestRecipeJSON(version: "1.0.0"))
        let v2 = try digestRecipeJSON(version: "2.0.0")
        let prep = try store.prepareRecipe(recipeJSON: v2)
        _ = try store.applyRecipe(
            name: "rss_digest",
            recipeJSON: v2,
            expectedBaseHash: prep.base?.hash,
            expectedCandidateHash: prep.candidate.hash
        )
        let v2Snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertEqual(v2Snapshot.recipeTools.first?.version, "2.0.0")

        let availability = try store.rollbackAvailability(name: "rss_digest")
        guard case .available(let manifest) = availability else {
            return XCTFail("expected rollback availability, got \(availability)")
        }
        _ = try store.rollbackRecipe(name: "rss_digest", expectedManifest: manifest)

        let restored = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(restored.revision, v2Snapshot.revision)
        XCTAssertEqual(restored.recipeTools.first?.version, "1.0.0")
    }

    func testInvalidActiveRecipeFailsClosedWithoutDeclaration() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        // The store accepts the JSON (name/shape valid), but the recipe
        // references a primitive that does not exist in the iOS catalog — the
        // registry must NOT declare it (fail closed, §16.1 alignment).
        try apply(store: store, json: try invalidToolRecipeJSON())

        let snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertTrue(snapshot.recipeTools.isEmpty, "invalid recipe must not be declared")
        XCTAssertEqual(snapshot.contentHash, IOSDynamicToolRegistry.emptyContentHash)
    }

    // MARK: - Round-boundary seam: real bridge + registry

    func testRoundBoundaryRefreshAddsDeferredRecipeAndPreservesExposure() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        // Run-start bridge over the static catalog (heavy → lazy mode).
        let staticDeclarations = fullIosDeclarations()
        XCTAssertGreaterThan(staticDeclarations.count, 40, "test needs lazy mode like production")
        let initialBridge = IosToolExposureBridge(tools: staticDeclarations)
        XCTAssertEqual(initialBridge.lazyModeEnabled(), true)
        let initialCatalog = Set(initialBridge.fullToolDeclarations().map(\.name))
        XCTAssertFalse(initialCatalog.contains("recipe__rss_digest"))

        // The model already exposed two deferred tools before the promotion.
        initialBridge.exposeToolNames(names: ["wm_stations", "spawn_agent"])
        XCTAssertTrue(initialBridge.visibleTools().map(\.name).contains("wm_stations"))

        // Promotion happens between rounds → refresh publishes a new revision.
        let initialSnapshot = try unwrapSnapshot(await registry.refresh())
        try apply(store: store, json: try digestRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(snapshot.revision, initialSnapshot.revision)

        // Round boundary: rebuild the bridge over the new snapshot, carrying
        // the exposed set (this is exactly what the coordinator seam does).
        let rebuilt = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: initialBridge,
            snapshot: snapshot
        )

        // The recipe is in the FULL catalog (searchable) but deferred: not
        // visible until tool_search exposes it (§13.2.4).
        let rebuiltCatalog = Set(rebuilt.fullToolDeclarations().map(\.name))
        XCTAssertTrue(rebuiltCatalog.contains("recipe__rss_digest"))
        XCTAssertFalse(rebuilt.visibleTools().map(\.name).contains("recipe__rss_digest"))

        // Previously exposed names survived the rebuild.
        let rebuiltVisible = Set(rebuilt.visibleTools().map(\.name))
        XCTAssertTrue(rebuiltVisible.contains("wm_stations"))
        XCTAssertTrue(rebuiltVisible.contains("spawn_agent"))

        // tool_search finds the recipe with descriptor + version + permission
        // summary + source=custom.recipe (no manifest body, §16.3).
        let payload = rebuilt.executeToolSearch(argumentsJson: #"{"query":"rss_digest","limit":5}"#)
        let json = try XCTUnwrap(parse(payload))
        let hit = try XCTUnwrap((json["tools"] as? [[String: Any]])?.first {
            ($0["name"] as? String) == "recipe__rss_digest"
        })
        XCTAssertEqual(hit["version"] as? String, "1.0.0")
        XCTAssertEqual(hit["source"] as? String, "custom.recipe")
        XCTAssertEqual(hit["permission_summary"] as? String,
                       IOSDynamicToolRegistry.permissionSummary(for: .networkRead))
        XCTAssertNil(hit["manifest"], "search results must not carry the manifest body")
        let expanded = json["expanded_tools"] as? [String]
        XCTAssertTrue(expanded?.contains("recipe__rss_digest") == true)

        // The hit is exposed by the search itself → callable on the NEXT
        // round (the model's already-tuned tools stay put).
        XCTAssertTrue(rebuilt.visibleTools().map(\.name).contains("recipe__rss_digest"))
    }

    func testRoundBoundaryRebuildDropsRolledBackRecipeFromCatalogAndExposure() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)

        try apply(store: store, json: try digestRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        let bridge = IOSDynamicToolBridgeRebuilder.rebuiltBridge(
            from: IosToolExposureBridge(tools: fullIosDeclarations()),
            snapshot: snapshot
        )
        bridge.exposeToolNames(names: ["recipe__rss_digest"])
        XCTAssertTrue(bridge.visibleTools().map(\.name).contains("recipe__rss_digest"))

        // Rollback removes the recipe; the next round's rebuild must drop it
        // from both the full catalog and the exposed set.
        let availability = try store.rollbackAvailability(name: "rss_digest")
        guard case .available(let manifest) = availability else {
            return XCTFail("expected rollback availability, got \(availability)")
        }
        _ = try store.rollbackRecipe(name: "rss_digest", expectedManifest: manifest)

        let rolledBack = try unwrapSnapshot(await registry.refresh())
        XCTAssertGreaterThan(rolledBack.revision, snapshot.revision)
        let rebuilt = IOSDynamicToolBridgeRebuilder.rebuiltBridge(from: bridge, snapshot: rolledBack)
        XCTAssertFalse(rebuilt.fullToolDeclarations().map(\.name).contains("recipe__rss_digest"))
        XCTAssertFalse(rebuilt.visibleTools().map(\.name).contains("recipe__rss_digest"))
        XCTAssertTrue(rebuilt.visibleTools().map(\.name).contains("tool_search"),
                      "static catalog must be untouched by the rebuild")
    }

    func testRunStartAssemblyIncludesRecipeDeclarationsAndSearchInfo() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)
        try apply(store: store, json: try digestRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())

        // Mirrors the ChatViewModel.makeTextGenerationParams seam: one
        // snapshot supplies declarations + search info together (§16.1).
        var declarations = fullIosDeclarations()
        declarations.append(contentsOf: snapshot.recipeDeclarations())
        let bridge = IosToolExposureBridge(tools: declarations, recipeSearchInfo: snapshot.searchInfoByName)

        XCTAssertTrue(bridge.fullToolDeclarations().map(\.name).contains("recipe__rss_digest"))
        XCTAssertEqual(bridge.lazyModeEnabled(), true)
        XCTAssertFalse(bridge.visibleTools().map(\.name).contains("recipe__rss_digest"),
                       "recipe must be default-deferred at run start")

        let searchInfo = try XCTUnwrap(snapshot.searchInfoByName["recipe__rss_digest"])
        let info = try XCTUnwrap(parse(searchInfo))
        XCTAssertEqual(info["version"] as? String, "1.0.0")
        XCTAssertEqual(info["source"] as? String, "custom.recipe")
        XCTAssertNotNil(info["permission_summary"])
    }

    func testDeclarationApprovalFlagsFollowEnvelope() async throws {
        let store = try makeStore()
        let registry = makeRegistry(store)
        // Mutation envelope (sideEffect) → declaration is never auto-approvable.
        try apply(store: store, json: try mutatingRecipeJSON(version: "1.0.0"))
        let snapshot = try unwrapSnapshot(await registry.refresh())
        let descriptor = try XCTUnwrap(snapshot.recipeTools.first)
        XCTAssertEqual(descriptor.effectClassRawValue, IOSToolEffectClass.sideEffect.rawValue)

        let bridge = IosToolExposureBridge(
            tools: fullIosDeclarations() + snapshot.recipeDeclarations(),
            recipeSearchInfo: snapshot.searchInfoByName
        )
        let payload = bridge.executeToolSearch(argumentsJson: #"{"query":"recipe__digest_save","limit":5}"#)
        let json = try XCTUnwrap(parse(payload))
        let hit = try XCTUnwrap((json["tools"] as? [[String: Any]])?.first {
            ($0["name"] as? String) == "recipe__digest_save"
        })
        XCTAssertEqual(hit["needs_approval"] as? Bool, true)
        XCTAssertEqual(hit["allows_auto_approval"] as? Bool, false)
        XCTAssertEqual(hit["permission_summary"] as? String,
                       IOSDynamicToolRegistry.permissionSummary(for: .sideEffect))
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

    private func makeStore() throws -> IOSRecipeFileStore {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-dynamic-registry-\(UUID().uuidString)", isDirectory: true)
        // The temp directory MUST exist before the store stages packages.
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        tempDirs.append(root)
        return IOSRecipeFileStore(baseDirectory: root)
    }

    private func makeRegistry(_ store: IOSRecipeFileStore) -> IOSDynamicToolRegistry {
        // recipesDirectory == baseDirectory/recipes, so the registry's base
        // directory is the parent of the store's recipes directory.
        IOSDynamicToolRegistry(baseDirectory: store.recipesDirectory.deletingLastPathComponent())
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

    /// Real static iOS declarations — the same assembly the run uses. Heavy
    /// (>> 40 non-discovery tools) so the bridge enters lazy mode like
    /// production, where the deferred-recipe contract actually applies.
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

    /// Real primitive ids only (the registry's catalog lookup is the REAL iOS
    /// one — `summarize_text` etc. do not exist and would fail validation).
    private func digestRecipeJSON(version: String) throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": "rss_digest",
            "version": version,
            "description": "抓取一个 RSS 源并整理成摘要。",
            "inputs": ["feed_url": "string", "limit": "number"],
            "steps": [
                ["id": "fetch", "tool": "scrape_web",
                 "arguments": ["url": "${input.feed_url}"]],
            ],
            "outputs": ["text": "${step.fetch.output.text}"],
        ])
    }

    /// Envelope = sideEffect (workspace_file_write step raises the union).
    private func mutatingRecipeJSON(version: String) throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": "digest_save",
            "version": version,
            "description": "抓取 RSS 源并把摘要写入 Workspace。",
            "inputs": ["feed_url": "string", "output_path": "string"],
            "steps": [
                ["id": "fetch", "tool": "scrape_web",
                 "arguments": ["url": "${input.feed_url}"]],
                ["id": "save", "tool": "workspace_file_write",
                 "arguments": ["path": "${input.output_path}", "content": "${step.fetch.output.text}"]],
            ],
            "outputs": ["file_path": "${step.save.output.path}"],
        ])
    }

    /// References a primitive that does not exist in the iOS catalog.
    private func invalidToolRecipeJSON() throws -> Data {
        try jsonData([
            "schema": "amber.recipe.v1",
            "name": "ghost_recipe",
            "version": "1.0.0",
            "description": "引用了不存在的工具。",
            "inputs": ["feed_url": "string"],
            "steps": [
                ["id": "fetch", "tool": "no_such_primitive",
                 "arguments": ["url": "${input.feed_url}"]],
            ],
            "outputs": ["text": "${step.fetch.output.text}"],
        ])
    }

    private func jsonData(_ dict: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    private func parse(_ text: String) throws -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}
