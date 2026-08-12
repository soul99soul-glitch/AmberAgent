import Foundation

// MARK: - IOSEvolutionExperienceStore (Phase 3 Wave 1; §11.3 / §15 Phase 3 /
// §18.3 / §20)
//
// Small JSON store for the experience pool. One document at
// `evolution/experiences.json` under the injected base directory (production
// will pass an Application Support-derived directory; the next wave wires
// that). Tests inject a temp base directory.
//
// §20 risk line (经验池无限膨胀): the pool must never grow without bound, so
// the store enforces HARD caps — a total entry count and a total document
// byte cap (§18.3). An add/update beyond a cap is a TYPED rejection, never a
// silent eviction or a silent truncation; the curator (policy layer) decides
// merge/supersede on rejection. Deletions leave a bounded tombstone summary
// (防复读), capped with FIFO eviction so they cannot grow the document
// without bound either.
//
// Each mutation rewrites the single document atomically. Writes are
// serialized with a process-wide static lock (same pattern as
// IOSRecipeFileStore), so separate value-type store instances over the same
// directory cannot interleave.

// MARK: - Limits

struct IOSExperienceStoreLimits: Equatable, Sendable {
    /// Hard cap on the total number of persisted experiences (all statuses).
    /// Chosen so the pool stays small enough for the per-round retrieval
    /// budget to keep the prompt bounded (§15 Phase 3 acceptance 4).
    var maxExperienceCount: Int = 200
    /// Hard cap on the whole document's encoded bytes (experiences +
    /// tombstones). 256 KiB keeps the pool far below per-round injection
    /// budgets even if every entry were retrieved at once — which the
    /// topK/byteBudget of `retrieve` never allows.
    var maxTotalBytes: Int = 256 * 1024
    /// Tombstones are bounded separately (FIFO: the oldest is dropped beyond
    /// this cap) so deletions cannot grow the document without bound either.
    var maxTombstoneCount: Int = 32

    static let `default` = IOSExperienceStoreLimits()
}

// MARK: - Typed errors

/// Typed failures shared by the store and the curator (typed results, never
/// silent downgrades — matches the diagnoser's fail-closed style).
enum IOSExperienceError: Error, Equatable, Sendable {
    // Store hard caps (§18.3) — the pool must never grow without bound.
    case overEntryLimit(current: Int, limit: Int)
    case overByteLimit(currentBytes: Int, limit: Int)
    // Identity / data integrity.
    case idCollision(String)
    case experienceNotFound(String)
    case corruptDocument(String)
    case ioFailure(String)
    // Curator policy.
    case emptyApplicability
    case emptyRuleText
    case feedbackOnInactiveExperience(String)
    case supersedeTargetNotActive(String)
    case cannotSupersedeSelf
    case mergeTargetNotActive(String)
    case mergeSourceNotActive(String)
    case cannotMergeIntoSelf
    case invalidStatusTransition(String)
}

// MARK: - Typed results

enum IOSExperienceStoreAddResult: Equatable, Sendable {
    /// Stored as-is; `matchedTombstone` is set when the exact content
    /// (token fingerprint) was deleted before (防复读 — the caller can show
    /// that this experience was previously removed).
    case added(IOSEvolutionExperience, matchedTombstone: IOSExperienceTombstone?)
    case rejected(IOSExperienceError)
}

enum IOSExperienceStoreUpdateResult: Equatable, Sendable {
    case updated(IOSEvolutionExperience)
    case rejected(IOSExperienceError)
}

enum IOSExperienceStoreDeleteResult: Equatable, Sendable {
    case deleted(IOSExperienceTombstone)
    case rejected(IOSExperienceError)
}

// MARK: - Document

/// Single persisted document (one file, one atomic write).
struct IOSExperienceStoreDocument: Codable, Equatable {
    var schemaVersion: Int
    var experiences: [IOSEvolutionExperience]
    var tombstones: [IOSExperienceTombstone]
}

// MARK: - Store

struct IOSEvolutionExperienceStore {
    private static let mutationLock = NSLock()
    private static let documentSchemaVersion = 1
    private static let documentFileName = "experiences.json"

    private let baseDirectory: URL
    private let fileManager: FileManager
    private let limits: IOSExperienceStoreLimits

    /// `nil` base directory resolves to the app's Documents directory (same
    /// production default as `IOSSkillFileStore` / `IOSRecipeFileStore`), so
    /// Phase 3 Wave 2 production wiring is `IOSEvolutionExperienceStore()`
    /// without a dedicated factory.
    init(
        baseDirectory: URL? = nil,
        fileManager: FileManager = .default,
        limits: IOSExperienceStoreLimits = .default
    ) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
            ?? (try? fileManager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.limits = limits
    }

    private var documentURL: URL {
        baseDirectory.appendingPathComponent("evolution", isDirectory: true)
            .appendingPathComponent(Self.documentFileName)
    }

    // MARK: Reads

    func allExperiences() throws -> [IOSEvolutionExperience] {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        return try loadDocument().experiences
    }

    func activeExperiences() throws -> [IOSEvolutionExperience] {
        try allExperiences().filter { $0.status == .active }
    }

    func experience(id: String) throws -> IOSEvolutionExperience? {
        try allExperiences().first { $0.id == id }
    }

    func tombstones() throws -> [IOSExperienceTombstone] {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        return try loadDocument().tombstones
    }

    // MARK: Mutations (typed results — never silent drops)

    /// Appends a NEW experience. The curator decides dedupe/merge BEFORE
    /// calling this; the store is the persistence + limit layer. Typed
    /// rejection when the pool is over a hard cap (§18.3): the caller decides
    /// merge/supersede, the store never evicts silently.
    func add(_ experience: IOSEvolutionExperience) -> IOSExperienceStoreAddResult {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        do {
            var document = try loadDocument()
            guard !document.experiences.contains(where: { $0.id == experience.id }) else {
                return .rejected(.idCollision(experience.id))
            }
            guard document.experiences.count < limits.maxExperienceCount else {
                return .rejected(.overEntryLimit(
                    current: document.experiences.count,
                    limit: limits.maxExperienceCount
                ))
            }
            let fingerprint = IOSEvolutionTokenization.contentFingerprint(
                applicability: experience.applicability,
                ruleText: experience.ruleText
            )
            let matchedTombstone = document.tombstones.first { $0.contentFingerprint == fingerprint }
            document.experiences.append(experience)
            let encoded = try encode(document)
            guard encoded.count <= limits.maxTotalBytes else {
                return .rejected(.overByteLimit(currentBytes: encoded.count, limit: limits.maxTotalBytes))
            }
            try write(document, encoded: encoded)
            return .added(experience, matchedTombstone: matchedTombstone)
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    /// Replaces the entry with the same id (content, status, counters, edges
    /// — the curator is the policy layer deciding WHAT the new state is).
    /// Enforces the byte cap; unknown ids are typed rejections.
    func update(_ experience: IOSEvolutionExperience) -> IOSExperienceStoreUpdateResult {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        do {
            var document = try loadDocument()
            guard let index = document.experiences.firstIndex(where: { $0.id == experience.id }) else {
                return .rejected(.experienceNotFound(experience.id))
            }
            document.experiences[index] = experience
            let encoded = try encode(document)
            guard encoded.count <= limits.maxTotalBytes else {
                return .rejected(.overByteLimit(currentBytes: encoded.count, limit: limits.maxTotalBytes))
            }
            try write(document, encoded: encoded)
            return .updated(experience)
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    /// Physically removes the entry and leaves a bounded tombstone summary
    /// (§15 Phase 3 delete: 物理移除并留 tombstone 摘要，防复读). Conflict
    /// edges referencing the removed id are stripped from the remaining
    /// entries inside the same atomic rewrite (data integrity).
    func delete(id: String, reason: String?) -> IOSExperienceStoreDeleteResult {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        do {
            var document = try loadDocument()
            guard let index = document.experiences.firstIndex(where: { $0.id == id }) else {
                return .rejected(.experienceNotFound(id))
            }
            let removed = document.experiences.remove(at: index)
            let tombstone = IOSExperienceTombstone(
                id: id,
                contentFingerprint: IOSEvolutionTokenization.contentFingerprint(
                    applicability: removed.applicability,
                    ruleText: removed.ruleText
                ),
                reason: reason,
                deletedAtEpochMs: Self.nowMillis()
            )
            document.tombstones.append(tombstone)
            if document.tombstones.count > limits.maxTombstoneCount {
                let overflow = document.tombstones.count - limits.maxTombstoneCount
                let oldestFirst = document.tombstones.sorted {
                    $0.deletedAtEpochMs != $1.deletedAtEpochMs
                        ? $0.deletedAtEpochMs < $1.deletedAtEpochMs
                        : $0.id < $1.id
                }
                let dropIds = Set(oldestFirst.prefix(overflow).map(\.id))
                document.tombstones.removeAll { dropIds.contains($0.id) }
            }
            for entryIndex in document.experiences.indices
            where document.experiences[entryIndex].conflicts.contains(where: { $0.otherExperienceId == id }) {
                document.experiences[entryIndex].conflicts.removeAll { $0.otherExperienceId == id }
            }
            let encoded = try encode(document)
            guard encoded.count <= limits.maxTotalBytes else {
                // Deleting cannot grow the document past a cap the pool
                // already respected — unless the tombstone push crossed it;
                // fail closed with a typed error rather than drop data.
                return .rejected(.overByteLimit(currentBytes: encoded.count, limit: limits.maxTotalBytes))
            }
            try write(document, encoded: encoded)
            return .deleted(tombstone)
        } catch {
            return .rejected(Self.foldedError(error))
        }
    }

    // MARK: Private

    private func loadDocument() throws -> IOSExperienceStoreDocument {
        guard fileManager.fileExists(atPath: documentURL.path) else {
            return IOSExperienceStoreDocument(
                schemaVersion: Self.documentSchemaVersion,
                experiences: [],
                tombstones: []
            )
        }
        guard let data = try? Data(contentsOf: documentURL) else {
            throw IOSExperienceError.ioFailure("无法读取 \(Self.documentFileName)")
        }
        guard let document = try? JSONDecoder().decode(IOSExperienceStoreDocument.self, from: data) else {
            throw IOSExperienceError.corruptDocument("\(Self.documentFileName) 无法解码，拒绝覆盖")
        }
        guard document.schemaVersion == Self.documentSchemaVersion else {
            throw IOSExperienceError.corruptDocument(
                "\(Self.documentFileName) schemaVersion=\(document.schemaVersion)，预期 \(Self.documentSchemaVersion)"
            )
        }
        return document
    }

    private func encode(_ document: IOSExperienceStoreDocument) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(document)
    }

    private func write(_ document: IOSExperienceStoreDocument, encoded: Data) throws {
        try fileManager.createDirectory(
            at: baseDirectory.appendingPathComponent("evolution", isDirectory: true),
            withIntermediateDirectories: true
        )
        try encoded.write(to: documentURL, options: .atomic)
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static func foldedError(_ error: Error) -> IOSExperienceError {
        if let experienceError = error as? IOSExperienceError { return experienceError }
        return .ioFailure(String(describing: error))
    }
}
