import CryptoKit
import Foundation
@preconcurrency import Shared

// MARK: - IOSDynamicToolRegistry (Phase 1 Wave B1; §9.5 / §13.2 / §13.3 / §16)
//
// Versioned dynamic tool registry that turns the recipe store's ACTIVE set
// into immutable, revisioned catalog snapshots whose declarations are handed
// to the KMP exposure bridge at the round boundary.
//
// Design decisions (contract, §9.5):
// 1. Snapshot-embedded manifests. A `IOSDynamicToolCatalogSnapshot` carries an
//    IMMUTABLE copy of each active recipe's manifest (a recipe package is one
//    small JSON file), so version pinning is structural: an in-flight call
//    that holds a v1 snapshot reference holds the v1 implementation, and a
//    later promotion publishes a new revision without touching the old
//    snapshot (§13.3). No manual lease bookkeeping is required — ARC releases
//    the old revision when the last reference drops.
// 2. `IOSDynamicToolLease` exists as a value type for ledger attribution only
//    (toolId / toolVersion / catalogRevision / executorKey). The next wave's
//    `recipe__*` route resolves a lease from the snapshot the round pinned;
//    this wave only guarantees that declarations and manifests come from the
//    SAME snapshot (§16.1 last bullet — declaration and execution
//    availability can never diverge by revision).
// 3. The registry is an actor; the revision is monotonically increasing within
//    the process. Across restarts the snapshot is rebuilt from the store's
//    CURRENT active set (a fresh launch sequences revision 1 from store state;
//    revisions are not persisted).
// 4. Recipes are default-deferred (§13.2.4): the declarations are part of the
//    bridge's FULL catalog (searchable via tool_search) but never enter the
//    resident/visible set — `recipe__*` is not in `IOS_RESIDENT_TOOL_NAMES`,
//    so in lazy mode (production catalog) they stay hidden until exposed.
// 5. Phase 1 background (§16.2): recipes are NOT declared in the background
//    catalog. The foreground handoff filters `recipe__*` names out of
//    `fullToolNames` (see ChatGenerationCoordinator.refreshBackgroundHandoff),
//    and the background bridge rebuild cannot reconstruct recipe names (they
//    are not static KMP declarations) — so background behavior is unchanged
//    and recipes simply do not exist there until the parity wave.
// 6. Broken recipes fail closed: an active recipe that fails semantic
//    validation (e.g. references a primitive that no longer exists) is not
//    declared and is logged — never half-declared.
// 7. The synchronous warm cache (lock-protected holder) exists so the
//    run-start assembly (`ChatViewModel.makeTextGenerationParams`, a
//    synchronous path) can read the current snapshot without an actor hop;
//    all REVISION sequencing happens inside the actor (`refresh()`).

// MARK: - Snapshot / lease value types (§9.5)

/// One active recipe as the registry pins it. `manifest` is the immutable
/// copy used for in-flight pinning (§13.3); the other fields are the
/// declaration/search inputs derived from that same manifest.
struct IOSDynamicRecipeToolDescriptor: Sendable, Equatable {
    /// The model-facing ToolId: `recipe__<name>`.
    let toolId: String
    let recipeName: String
    /// Manifest `version` string (e.g. "1.0.0").
    let version: String
    /// Store package hash of the exact manifest bytes (invariant 5).
    let manifestHash: String
    /// Human-readable permission summary derived from the envelope effect
    /// class (I-10, §16.3).
    let permissionSummary: String
    /// Envelope `IOSToolEffectClass` rawValue; the KMP declaration factory
    /// maps it to approval flags (a mutation envelope is never advertised as
    /// auto-approvable).
    let effectClassRawValue: String
    /// `{"<input>":"string|number|boolean", ...}` — the declaration's JSON
    /// schema source, generated from `manifest.inputs`.
    let inputsJSON: String
    /// Manifest `description`, verbatim (declaration description).
    let description: String
    /// Immutable manifest copy — the in-flight pinning anchor.
    let manifest: IOSRecipeManifest
    /// `{"version":...,"permission_summary":...,"source":"custom.recipe"}`
    /// merged into `tool_search` results (§16.3: no manifest body is carried).
    let searchInfoJSON: String
}

/// Immutable catalog snapshot for one revision. Value type: callers (rounds,
/// runs) hold the snapshot they saw; a promotion publishes a new revision and
/// never mutates an old snapshot (§13.3).
struct IOSDynamicToolCatalogSnapshot: Sendable, Equatable {
    let revision: Int64
    let recipeTools: [IOSDynamicRecipeToolDescriptor]
    /// Deterministic hash of the active recipe set (sorted
    /// toolId|version|manifestHash lines) — revision bumps exactly when this
    /// changes.
    let contentHash: String

    /// KMP `Tool` declarations for this snapshot's recipes. Conversion happens
    /// at the bridge seam (MainActor) so the actor never transports KMP
    /// objects; the declarations are a pure function of the descriptors, i.e.
    /// of the same snapshot that carries the manifests (§16.1).
    func recipeDeclarations() -> [Tool] {
        recipeTools.map { descriptor in
            IosToolExposureBridgeKt.createRecipeToolDeclaration(
                recipeName: descriptor.recipeName,
                version: descriptor.version,
                description: descriptor.description,
                inputsJson: descriptor.inputsJSON,
                effectClass: descriptor.effectClassRawValue
            )
        }
    }

    /// toolId → searchInfoJSON, for the bridge's recipe search enrichment.
    var searchInfoByName: [String: String] {
        Dictionary(uniqueKeysWithValues: recipeTools.map { ($0.toolId, $0.searchInfoJSON) })
    }
}

/// Value-type lease used for ledger attribution only (§9.5). Next wave: the
/// recipe route resolves `recipe__*` calls through the pinned snapshot and
/// records a lease per call; ARC (not lease accounting) releases old
/// revisions.
struct IOSDynamicToolLease: Sendable, Equatable {
    let toolId: String
    let toolVersion: String
    let catalogRevision: Int64
    let executorKey: String
}

// MARK: - Lock-protected snapshot cache

/// Synchronous read cache for the run-start assembly path. The actor owns all
/// revision sequencing; the holder only mirrors the LATEST published snapshot
/// for nonisolated readers (ChatViewModel's synchronous catalog assembly).
final class IOSDynamicToolSnapshotHolder: @unchecked Sendable {
    private let lock = NSLock()
    private var boxed: IOSDynamicToolCatalogSnapshot?

    var value: IOSDynamicToolCatalogSnapshot? {
        get { lock.withLock { boxed } }
        set { lock.withLock { boxed = newValue } }
    }
}

// MARK: - Registry

actor IOSDynamicToolRegistry {
    /// App-scoped registry over the recipe store in the documents directory
    /// (same base-directory convention as the skill store). Warm cache is
    /// seeded synchronously so the first run-start assembly sees the current
    /// active set without an actor hop.
    static let shared: IOSDynamicToolRegistry = {
        let baseDirectory = (try? FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? FileManager.default.temporaryDirectory
        let registry = IOSDynamicToolRegistry(baseDirectory: baseDirectory)
        // Seed the synchronous cache with the store's current state at
        // revision 1 (deterministic; no-op when the store is unreadable).
        if let content = IOSDynamicToolRegistry.readCatalogContent(
            store: IOSRecipeFileStore(baseDirectory: baseDirectory)
        ) {
            registry.holder.value = IOSDynamicToolCatalogSnapshot(
                revision: 1,
                recipeTools: content.recipeTools,
                contentHash: content.contentHash
            )
        }
        return registry
    }()

    /// Base directory of the recipe store this registry mirrors. The store is
    /// a non-Sendable value type (holds FileManager), so it is constructed
    /// inside the actor at refresh time instead of being sent across the
    /// boundary; reads are stateless, so a fresh instance per refresh is
    /// equivalent to holding one for the actor's lifetime.
    private let baseDirectory: URL
    /// Lock-protected mirror of the latest published snapshot; accessed from
    /// both actor-isolated (`refresh`) and nonisolated (`currentSnapshot`)
    /// contexts. `nonisolated(unsafe)` is justified by the holder's own lock
    /// and `@unchecked Sendable` conformance — the snapshot value itself is a
    /// Sendable immutable value type.
    nonisolated(unsafe) private let holder = IOSDynamicToolSnapshotHolder()
    /// Next revision to issue for genuinely new content. Starts at 2 so the
    /// app-scoped `shared` instance never reissues the warm-seeded revision 1
    /// (see `shared`'s initializer). Non-shared instances (tests) never seed
    /// revision 1 and simply start their sequence at 2 — only monotonicity is
    /// load-bearing, not the absolute value.
    private var nextRevision: Int64 = 2
    /// Content hash the actor last sequenced (authoritative for bumping).
    private var sequencedContentHash: String?
    /// §19 观测 store：revision ↔ lease 不一致哨兵（Phase 4 Wave 1）。
    private let metrics: IOSEvolutionMetrics

    init(baseDirectory: URL, metrics: IOSEvolutionMetrics = .shared) {
        self.baseDirectory = baseDirectory
        self.metrics = metrics
    }

    private var store: IOSRecipeFileStore {
        IOSRecipeFileStore(baseDirectory: baseDirectory)
    }

    /// Latest published snapshot (nil only when the store was unreadable and
    /// no snapshot could ever be built). Nonisolated: synchronous readers
    /// (run-start assembly) use this; the actor's `refresh()` stays the only
    /// revision sequencer.
    nonisolated var currentSnapshot: IOSDynamicToolCatalogSnapshot? {
        holder.value
    }

    /// Round-boundary refresh (§13.2 seam, §16.1): re-reads the store's
    /// active set and publishes a NEW revision only when the content hash
    /// changed (recipe promotion/rollback since the last check). Callers
    /// compare `revision` with the previous round's value to decide whether
    /// to rebuild their exposure bridge.
    func refresh() async -> IOSDynamicToolCatalogSnapshot? {
        guard let content = Self.readCatalogContent(store: store) else {
            // Store unreadable: keep serving the last known snapshot rather
            // than dropping the catalog mid-run.
            return holder.value
        }
        if content.contentHash != sequencedContentHash {
            if sequencedContentHash == nil,
               let cached = holder.value, cached.contentHash == content.contentHash {
                // First sequencing after the warm: identical content keeps the
                // warm revision (no spurious bump on the first round).
                sequencedContentHash = content.contentHash
                nextRevision = cached.revision + 1
            } else {
                let revision = nextRevision
                nextRevision = revision + 1
                sequencedContentHash = content.contentHash
                holder.value = IOSDynamicToolCatalogSnapshot(
                    revision: revision,
                    recipeTools: content.recipeTools,
                    contentHash: content.contentHash
                )
            }
        }
        // §19 哨兵：catalog revision ↔ 执行 lease 不一致应恒为 0。
        // 当前架构下 holder 只有本 actor 写入（revision 单调、随 contentHash
        // bump），in-flight 调用经 ARC 钉住 snapshot 副本，不存在可观察的
        // 不一致路径；这是最便宜的可观测断言点——未来若出现第二个 holder
        // 写入方或 revision 错位（如跨进程共享），此哨兵立即报警。
        if let published = holder.value, published.revision != nextRevision - 1 {
            metrics.record(.catalogLeaseInconsistency)
        }
        return holder.value
    }

    // MARK: Catalog content reading (deterministic, synchronous)

    struct CatalogContent: Sendable {
        let recipeTools: [IOSDynamicRecipeToolDescriptor]
        let contentHash: String

        static let empty = CatalogContent(recipeTools: [], contentHash: IOSDynamicToolRegistry.emptyContentHash)
    }

    static let emptyContentHash = {
        var hasher = SHA256()
        hasher.update(data: Data("".utf8))
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }()

    /// Reads the store's active recipe set and produces validated descriptors.
    /// Returns nil only when the store is unreadable (fail-safe: keep the last
    /// known snapshot). A missing recipes directory is a valid EMPTY catalog.
    static func readCatalogContent(store: IOSRecipeFileStore) -> CatalogContent? {
        let fileManager = FileManager.default
        let recipesDirectory = store.recipesDirectory
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: recipesDirectory.path, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            return .empty
        }
        let names: [String]
        do {
            names = try fileManager.contentsOfDirectory(atPath: recipesDirectory.path)
                .filter { !$0.hasPrefix(".") }
                .sorted()
        } catch {
            return nil
        }

        var descriptors: [IOSDynamicRecipeToolDescriptor] = []
        for name in names {
            guard IOSRecipeNames.isValidRecipeName(name) else { continue }
            guard let package = try? store.readLiveRecipe(name: name),
                  let manifest = try? IOSRecipeManifest.decode(package.canonicalJSON) else {
                NSLog("[IOSDynamicToolRegistry] active recipe \(name) unreadable; not declared.")
                continue
            }
            let validation = IOSRecipeValidator.validate(manifest: manifest, catalog: Self.primitiveCatalogEntry)
            guard validation.isValid, let envelope = validation.permissionEnvelope else {
                NSLog("[IOSDynamicToolRegistry] active recipe \(name) fails semantic validation; not declared: \(validation.issues.map { $0.code.rawValue })")
                continue
            }
            let toolId = "recipe__\(manifest.name)"
            let permissionSummary = Self.permissionSummary(for: envelope)
            guard let inputsJSON = Self.inputsJSON(from: manifest.inputs),
                  let searchInfoJSON = Self.recipeSearchInfoJSON(
                      version: manifest.version,
                      permissionSummary: permissionSummary
                  ) else {
                NSLog("[IOSDynamicToolRegistry] active recipe \(name) skipped (search/schema payload not encodable).")
                continue
            }
            descriptors.append(IOSDynamicRecipeToolDescriptor(
                toolId: toolId,
                recipeName: manifest.name,
                version: manifest.version,
                manifestHash: package.hash,
                permissionSummary: permissionSummary,
                effectClassRawValue: envelope.rawValue,
                inputsJSON: inputsJSON,
                description: manifest.description,
                manifest: manifest,
                searchInfoJSON: searchInfoJSON
            ))
        }
        return CatalogContent(recipeTools: descriptors, contentHash: Self.contentHash(of: descriptors))
    }

    /// Existence + effect-class oracle for recipe validation. A tool "exists"
    /// iff the iOS run can actually declare it (KMP static catalog) or it is
    /// a dynamic `mcp__*` name; the effect class comes from the existing
    /// name-based mapping (fail-safe default sideEffect).
    static func primitiveCatalogEntry(for toolName: String) -> IOSRecipeCatalogEntry? {
        if toolName.hasPrefix("recipe__") { return nil }
        let known = ToolKt.iosToolDeclaration(name: toolName) != nil
            || ToolKt.isExpandedMcpToolName(name: toolName)
        guard known else { return nil }
        return IOSRecipeCatalogEntry(
            exists: true,
            minVersion: "1.0.0",
            effectClass: IOSToolEffectClassMapping.forToolName(toolName, input: "{}")
        )
    }

    /// `recipe__<name>` — the model-facing prefix (validator already rejects
    /// recipes referencing recipes; this marks the whole class).
    static func isRecipeToolName(_ toolName: String) -> Bool {
        toolName.hasPrefix("recipe__")
    }

    static func permissionSummary(for effectClass: IOSToolEffectClass) -> String {
        switch effectClass {
        case .pure:
            "read-only; no side effects (pure)"
        case .networkRead:
            "read-only network access (networkRead)"
        case .idempotent:
            "local idempotent changes; replay-safe (idempotent)"
        case .sideEffect:
            "may change state; existing approval policy applies (sideEffect)"
        }
    }

    // MARK: Private helpers

    private static func inputsJSON(from inputs: [String: IOSRecipeInputType]) -> String? {
        let object = Dictionary(uniqueKeysWithValues: inputs.map { ($0.key, $0.value.rawValue) })
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private static func recipeSearchInfoJSON(version: String, permissionSummary: String) -> String? {
        let object: [String: Any] = [
            "version": version,
            "permission_summary": permissionSummary,
            "source": "custom.recipe",
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private static func contentHash(of descriptors: [IOSDynamicRecipeToolDescriptor]) -> String {
        var hasher = SHA256()
        let lines = descriptors.map { "\($0.toolId)|\($0.version)|\($0.manifestHash)" }
            .joined(separator: "\n")
        hasher.update(data: Data(lines.utf8))
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Round-boundary bridge rebuild (§13.2.3)

/// Rebuilds the run's exposure bridge over a new catalog snapshot while
/// carrying the already-exposed tool-name set forward ("rebuild bridge and
/// take the exposed set along" — the KMP bridge's `exposeToolNames` is the
/// seed mechanism; names that no longer exist in the new catalog are dropped
/// by the bridge itself, so a rolled-back recipe loses visibility and every
/// surviving tool keeps it).
@MainActor
enum IOSDynamicToolBridgeRebuilder {
    static func rebuiltBridge(
        from oldBridge: IosToolExposureBridge?,
        snapshot: IOSDynamicToolCatalogSnapshot
    ) -> IosToolExposureBridge {
        // Static declarations are preserved from the previous bridge (they do
        // not change mid-run); only the recipe slice is replaced by the new
        // snapshot's — declaration and execution availability stay aligned
        // with the SAME snapshot revision (§16.1).
        let oldStatic = (oldBridge?.fullToolDeclarations() ?? [])
            .filter { !IOSDynamicToolRegistry.isRecipeToolName($0.name) }
        let bridge = IosToolExposureBridge(
            tools: oldStatic + snapshot.recipeDeclarations(),
            recipeSearchInfo: snapshot.searchInfoByName
        )
        bridge.exposeToolNames(names: oldBridge?.visibleTools().map(\.name) ?? [])
        return bridge
    }
}
