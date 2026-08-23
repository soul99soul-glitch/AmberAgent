import CryptoKit
import Foundation
@preconcurrency import Shared

enum IOSSoulToolCatalog {
    static let toolName = "soul_import"
    static let workspacePath = "/workspace/SOUL.md"
    static let maximumCharacters = 64_000
}

struct IOSSoulImportPreview: Equatable {
    let baseHash: String
    let candidateHash: String
    let changedLineCount: Int
    let diffPreview: String
    let afterSummary: String

    var approvalSummary: String {
        "更新核心指令 · \(changedLineCount) 行变化 · \(String(baseHash.prefix(8)))→\(String(candidateHash.prefix(8)))"
    }
}

struct IOSPreparedSoulImport: Equatable {
    let preview: IOSSoulImportPreview
}

struct IOSSoulPreviousRecord: Codable, Equatable {
    let previousMarkdown: String
    let promotedHash: String
    let savedAt: Int64
}

enum IOSSoulStoreError: LocalizedError, Equatable {
    case missingWorkspaceFile
    case emptySoul
    case soulTooLarge
    case undecodable
    case staleCandidate
    case staleBase
    case noPrevious
    case currentChangedSincePromotion

    var errorDescription: String? {
        switch self {
        case .missingWorkspaceFile:
            "找不到 \(IOSSoulToolCatalog.workspacePath)。请先把新的核心指令写到该文件。"
        case .emptySoul:
            "SOUL.md 不能为空。"
        case .soulTooLarge:
            "SOUL.md 超过 \(IOSSoulToolCatalog.maximumCharacters) 个字符上限。"
        case .undecodable:
            "SOUL.md 不是有效 UTF-8 文本。"
        case .staleCandidate:
            "Workspace 候选在批准前已变化，请重新预览。"
        case .staleBase:
            "当前核心指令在批准前已变化，请重新预览。"
        case .noPrevious:
            "没有可回退的上一版核心指令。"
        case .currentChangedSincePromotion:
            "当前核心指令已不是上次应用的版本，未覆盖你之后的修改。"
        }
    }
}

@MainActor
struct IOSSoulService {
    let workspaceStore: IOSWorkspaceStore
    let sharedSettings: IOSSharedSettingsStore
    let previousStore: IOSSoulPreviousStore

    func prepareImport() throws -> IOSPreparedSoulImport {
        let candidate = try readWorkspaceSoul()
        let current = Self.normalizedSoul(sharedSettings.agentRuntime.agentSoulMarkdown)
        let preview = IOSSoulImportPreview(
            baseHash: Self.hash(current),
            candidateHash: Self.hash(candidate),
            changedLineCount: Self.changedLineCount(from: current, to: candidate),
            diffPreview: Self.diffPreview(from: current, to: candidate),
            afterSummary: Self.summary(candidate)
        )
        return IOSPreparedSoulImport(preview: preview)
    }

    func applyPreparedImport(_ prepared: IOSPreparedSoulImport) throws -> String {
        let candidate = try readWorkspaceSoul()
        let current = Self.normalizedSoul(sharedSettings.agentRuntime.agentSoulMarkdown)
        let candidateHash = Self.hash(candidate)
        let baseHash = Self.hash(current)
        guard candidateHash == prepared.preview.candidateHash else {
            return Self.errorJSON(code: "stale_candidate", message: IOSSoulStoreError.staleCandidate.errorDescription)
        }
        guard baseHash == prepared.preview.baseHash else {
            return Self.errorJSON(code: "stale_base", message: IOSSoulStoreError.staleBase.errorDescription)
        }
        previousStore.save(
            IOSSoulPreviousRecord(
                previousMarkdown: current,
                promotedHash: candidateHash,
                savedAt: Int64(Date().timeIntervalSince1970 * 1000)
            )
        )
        sharedSettings.setAgentSoulMarkdown(candidate)
        return IOSWorkspaceStore.json([
            "ok": true,
            "applied": true,
            "base_hash": baseHash,
            "candidate_hash": candidateHash,
            "path": IOSSoulToolCatalog.workspacePath,
        ])
    }

    func rollbackPrevious() throws {
        guard let record = previousStore.load() else {
            throw IOSSoulStoreError.noPrevious
        }
        let currentHash = Self.hash(Self.normalizedSoul(sharedSettings.agentRuntime.agentSoulMarkdown))
        guard currentHash == record.promotedHash else {
            throw IOSSoulStoreError.currentChangedSincePromotion
        }
        sharedSettings.setAgentSoulMarkdown(record.previousMarkdown)
        previousStore.clear()
    }

    var canRollback: Bool {
        guard let record = previousStore.load() else { return false }
        let currentHash = Self.hash(Self.normalizedSoul(sharedSettings.agentRuntime.agentSoulMarkdown))
        return currentHash == record.promotedHash
    }

    private func readWorkspaceSoul() throws -> String {
        guard let record = workspaceStore.fileRecord(idOrPath: IOSSoulToolCatalog.workspacePath)
            ?? workspaceStore.fileRecord(idOrPath: "SOUL.md") else {
            throw IOSSoulStoreError.missingWorkspaceFile
        }
        let url = workspaceStore.fileURL(for: record)
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw IOSSoulStoreError.missingWorkspaceFile
        }
        guard let raw = String(data: data, encoding: .utf8) else {
            throw IOSSoulStoreError.undecodable
        }
        let normalized = Self.normalizedSoul(raw)
        if normalized.isEmpty { throw IOSSoulStoreError.emptySoul }
        if normalized.count > IOSSoulToolCatalog.maximumCharacters {
            throw IOSSoulStoreError.soulTooLarge
        }
        return normalized
    }

    static func normalizedSoul(_ raw: String) -> String {
        raw.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
    }

    static func hash(_ normalized: String) -> String {
        SHA256.hash(data: Data(normalized.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    static func summary(_ text: String) -> String {
        let compact = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard compact.count > 160 else { return compact }
        return String(compact.prefix(160)) + "…"
    }

    static func changedLineCount(from base: String, to candidate: String) -> Int {
        let before = Set(base.split(separator: "\n", omittingEmptySubsequences: false).map(String.init))
        let after = candidate.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        return after.filter { !before.contains($0) }.count
            + before.subtracting(after).count
    }

    static func diffPreview(from base: String, to candidate: String, limit: Int = 24) -> String {
        let oldLines = base.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        let newLines = candidate.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var lines: [String] = []
        let maxCount = max(oldLines.count, newLines.count)
        for index in 0..<maxCount {
            let old = index < oldLines.count ? oldLines[index] : nil
            let new = index < newLines.count ? newLines[index] : nil
            if old == new { continue }
            if let old { lines.append("- \(old)") }
            if let new { lines.append("+ \(new)") }
            if lines.count >= limit { break }
        }
        if lines.isEmpty { return "(无文本差异)" }
        return lines.joined(separator: "\n")
    }

    private static func errorJSON(code: String?, message: String?) -> String {
        IOSWorkspaceStore.json([
            "ok": false,
            "code": code as Any? ?? NSNull(),
            "error": message as Any? ?? "Soul 导入失败",
        ])
    }
}

@MainActor
final class IOSSoulPreviousStore {
    static let shared = IOSSoulPreviousStore()

    private let defaults: UserDefaults
    private let key: String

    init(userDefaults: UserDefaults = .standard, key: String = "app.amber.ios.soul.previous") {
        self.defaults = userDefaults
        self.key = key
    }

    func load() -> IOSSoulPreviousRecord? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(IOSSoulPreviousRecord.self, from: data)
    }

    func save(_ record: IOSSoulPreviousRecord) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        defaults.set(data, forKey: key)
    }

    func clear() {
        defaults.removeObject(forKey: key)
    }
}
