import CryptoKit
import Foundation

// MARK: - Phase 3 Wave 1: Experience/Playbook core model (§11.3 / §15 Phase 3 /
// §18.3 / §20)
//
// One curated, durable "experience" entry — the smallest unit of the
// Experience/Playbook layer. The plan requires (§11.3): 稳定 ID, 适用条件与
// 反例 (applicability + counterexamples), 来源 evidence refs, helpful/harmful
// 计数, `active / superseded / rejected` 状态, 与其它规则的冲突关系; the rule
// body text and creation/update timestamps are the remaining fields.
//
// Deliberately NO embeddings / vector index: dedupe, conflict detection and
// retrieval scoring are deterministic token-overlap heuristics (v1), per §15
// Phase 3. The pool must stay bounded (§20 经验池无限膨胀风险行; §18.3 预算) —
// that is enforced by the store's hard caps, not by this model.

// MARK: - Status

/// `active` entries are retrievable and injectable; `superseded` entries are
/// retired in favor of a newer entry (see `supersededByExperienceId`);
/// `rejected` entries are kept for audit but never injected (§11.3).
enum IOSExperienceStatus: String, Codable, Equatable, Sendable {
    case active
    case superseded
    case rejected
}

// MARK: - Conflict edge

/// One directed conflict edge on an experience: this entry conflicts with
/// `otherExperienceId`. Edges are recorded symmetrically (both sides carry the
/// edge). Retrieval never injects both sides of an active conflict without a
/// marker (§15 Phase 3 acceptance 2).
struct IOSExperienceConflict: Codable, Equatable, Sendable {
    /// The OTHER experience id this entry conflicts with.
    let otherExperienceId: String
    /// Deterministic human-readable reason (applicability overlap + keyword
    /// opposition), for display in approval/conflict surfaces.
    let reason: String
    /// The opposing keyword pair that fired the heuristic, e.g. "总是↔从不".
    let detectedKeywordPair: String
}

// MARK: - Experience

/// §11.3 durable experience entry.
struct IOSEvolutionExperience: Codable, Equatable, Sendable {
    /// Stable, host-assigned id (`exp-<uuid>`); never re-derived from content,
    /// so a later edit of the rule text keeps the same identity.
    let id: String
    /// 适用条件 — when this rule applies.
    var applicability: String
    /// 反例 — conditions under which the rule does NOT apply.
    var counterexamples: [String]
    /// 来源 evidence refs (§11.3) — provenance, never copied content (I-15).
    var evidenceRefs: [IOSEvidenceRef]
    /// Optional for legacy/manual entries. Recipe feedback always supplies
    /// both fields so counters never drift across artifact versions.
    var sourceArtifactId: String? = nil
    var sourceArtifactVersion: String? = nil
    var helpfulCount: Int
    var harmfulCount: Int
    var status: IOSExperienceStatus
    /// Set only when `status == .superseded`: the id of the newer entry this
    /// one was superseded by (§11.3 / §15 Phase 3 supersede).
    var supersededByExperienceId: String?
    /// Conflict edges with OTHER experiences (§11.3). Kept sorted by
    /// `otherExperienceId` for deterministic persistence.
    var conflicts: [IOSExperienceConflict]
    /// 正文规则文本 — the rule body.
    var ruleText: String
    var createdAtEpochMs: Int64
    var updatedAtEpochMs: Int64
}

// MARK: - Tombstone

/// Summary kept after a physical delete (§15 Phase 3 delete: 物理移除并留
/// tombstone 摘要，防复读). `contentFingerprint` is the deterministic
/// token-set fingerprint of the deleted content, so a later add of the SAME
/// experience is detected and surfaced instead of being re-created blindly.
struct IOSExperienceTombstone: Codable, Equatable, Sendable {
    let id: String
    let contentFingerprint: String
    let reason: String?
    let deletedAtEpochMs: Int64
}

// MARK: - Suggestions (approval-gated)

/// What the curator suggests when an entry's harmful count reaches a
/// threshold (§15 Phase 3 acceptance 3). This wave only PRODUCES the
/// suggestion object; applying it (supersede/delete) requires explicit
/// approval in a later wave — the entry stays `active` until then.
enum IOSExperienceActionSuggestionKind: String, Codable, Equatable, Sendable {
    case supersede
    case delete
}

struct IOSExperienceActionSuggestion: Codable, Equatable, Sendable {
    let id: String
    let experienceId: String
    let kind: IOSExperienceActionSuggestionKind
    let helpfulCount: Int
    let harmfulCount: Int
    let reason: String
    let createdAtEpochMs: Int64
}

// MARK: - Retrieval item

/// One experience returned by `IOSEvolutionExperienceCurator.retrieve`.
struct IOSExperienceRetrievalItem: Equatable, Sendable {
    let experience: IOSEvolutionExperience
    /// Ids of ACTIVE conflicting experiences that were suppressed for this
    /// query, because the two sides of an active conflict may never be
    /// injected silently (§15 Phase 3 acceptance 2). The caller must surface
    /// this marker.
    let suppressedConflictingExperienceIds: [String]
}

// MARK: - Deterministic tokenization (no embeddings)

/// Deterministic mixed CJK/ASCII tokenizer used by dedupe, conflict detection
/// and retrieval scoring. No embeddings, no external dependencies (§15 Phase
/// 3): ASCII words are lowercased and kept whole; CJK runs are emitted as
/// character bigrams (a single char stays a uni-gram) so short conditions
/// still produce distinguishable tokens. CJK punctuation is excluded on
/// purpose, so "总是先搜索，再写文件" and "总是先搜索再写文件" normalize to the
/// same set except for the comma-split bigrams.
enum IOSEvolutionTokenization {
    private static func isAsciiWordCharacter(_ scalar: Unicode.Scalar) -> Bool {
        (0x30...0x39).contains(scalar.value) || // 0-9
        (0x41...0x5A).contains(scalar.value) || // A-Z
        (0x61...0x7A).contains(scalar.value) || // a-z
        scalar == "'"                            // keeps "don't" as one token
    }

    /// CJK unified ideographs + extension A + compatibility ideographs.
    /// CJK punctuation (、。，…) is NOT included.
    private static func isCJKIdeograph(_ scalar: Unicode.Scalar) -> Bool {
        (0x3400...0x4DBF).contains(scalar.value) ||
        (0x4E00...0x9FFF).contains(scalar.value) ||
        (0xF900...0xFAFF).contains(scalar.value)
    }

    /// Deterministic token set of `text`. Empty text → empty set.
    static func tokenSet(_ text: String) -> Set<String> {
        var tokens = Set<String>()
        var asciiRun = [Character]()
        var cjkRun = [Character]()
        func flushASCII() {
            guard !asciiRun.isEmpty else { return }
            tokens.insert(String(asciiRun).lowercased())
            asciiRun.removeAll(keepingCapacity: true)
        }
        func flushCJK() {
            guard !cjkRun.isEmpty else { return }
            if cjkRun.count == 1 {
                tokens.insert(String(cjkRun))
            } else {
                for index in 0..<(cjkRun.count - 1) {
                    tokens.insert(String([cjkRun[index], cjkRun[index + 1]]))
                }
            }
            cjkRun.removeAll(keepingCapacity: true)
        }
        for scalar in text.unicodeScalars {
            if isAsciiWordCharacter(scalar) {
                flushCJK()
                asciiRun.append(Character(scalar))
            } else if isCJKIdeograph(scalar) {
                flushASCII()
                cjkRun.append(Character(scalar))
            } else {
                flushASCII()
                flushCJK()
            }
        }
        flushASCII()
        flushCJK()
        return tokens
    }

    /// Jaccard overlap of two token sets; 0 when either side is empty.
    static func jaccard(_ a: Set<String>, _ b: Set<String>) -> Double {
        guard !a.isEmpty, !b.isEmpty else { return 0 }
        let unionCount = a.union(b).count
        guard unionCount > 0 else { return 0 }
        return Double(a.intersection(b).count) / Double(unionCount)
    }

    /// Deterministic content fingerprint (domain-separated SHA-256 over the
    /// sorted token set) — used by tombstones to detect a re-add of the same
    /// experience (防复读).
    static func contentFingerprint(applicability: String, ruleText: String) -> String {
        var hasher = SHA256()
        hasher.update(data: Data("amber.experience.content.v1\0".utf8))
        for token in tokenSet(applicability + "\n" + ruleText).sorted() {
            hasher.update(data: Data(token.utf8))
            hasher.update(data: Data([0x00]))
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Byte accounting

/// Deterministic byte accounting for the retrieval budget (§18.3 / §15 Phase
/// 3 acceptance 4). The budget counts the UTF-8 bytes of each returned item
/// as the caller would render it: the experience JSON plus its conflict
/// markers.
enum IOSExperienceByteAccounting {
    private struct RetrievalItemPayload: Codable {
        let experience: IOSEvolutionExperience
        let suppressedConflictingExperienceIds: [String]
    }

    static func encodedByteCount(
        experience: IOSEvolutionExperience,
        suppressedConflictingExperienceIds: [String] = []
    ) -> Int {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let payload = RetrievalItemPayload(
            experience: experience,
            suppressedConflictingExperienceIds: suppressedConflictingExperienceIds
        )
        // Codable encoding of these value types cannot fail in practice; a
        // defensive fallback of 0 only affects accounting, never correctness
        // of the returned data.
        return (try? encoder.encode(payload))?.count ?? 0
    }
}
