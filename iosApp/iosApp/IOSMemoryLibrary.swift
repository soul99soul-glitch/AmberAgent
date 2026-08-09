import Foundation
import Observation
@preconcurrency import Shared

enum IOSMemoryScopeFilter: String, CaseIterable, Identifiable {
    case all
    case core
    case shortTerm
    case longTerm

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "全部"
        case .core: "核心"
        case .shortTerm: "短期"
        case .longTerm: "长期"
        }
    }

    func includes(_ scope: MemoryScope) -> Bool {
        switch self {
        case .all:
            true
        case .core:
            scope == MemoryScope.core
        case .shortTerm:
            scope == MemoryScope.shortTerm
        case .longTerm:
            scope == MemoryScope.longTerm
        }
    }
}

enum IOSMemoryLibrary {
    static func filteredRecords(
        records: [MemoryRecord],
        query: String,
        scopeFilter: IOSMemoryScopeFilter
    ) -> [MemoryRecord] {
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return records
            .filter { scopeFilter.includes($0.scope) }
            .filter { record in
                guard !trimmedQuery.isEmpty else { return true }
                if record.content.localizedCaseInsensitiveContains(trimmedQuery) { return true }
                if record.scope.wireName.localizedCaseInsensitiveContains(trimmedQuery) { return true }
                if record.kind.wireName.localizedCaseInsensitiveContains(trimmedQuery) { return true }
                if record.sourceConversationId?.localizedCaseInsensitiveContains(trimmedQuery) == true { return true }
                return record.sourceMessageIds.contains {
                    $0.localizedCaseInsensitiveContains(trimmedQuery)
                }
            }
            .sorted(by: memorySort)
    }

    static func recallCandidates(
        records: [MemoryRecord],
        runtime: AgentRuntimeSetting,
        nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) -> [MemoryRecord] {
        ChatMemoryContextBuilder.contextPromptResult(
            records: ChatMemoryContextBuilder.recordsForPrompt(records: records, runtime: runtime),
            runtime: runtime,
            queryText: "",
            now: nowMillis
        ).records
    }

    static func recallExplanation(
        records: [MemoryRecord],
        runtime: AgentRuntimeSetting
    ) -> String {
        let candidates = recallCandidates(records: records, runtime: runtime)
        let enabledScopes = [
            runtime.enableCoreMemory ? "核心" : nil,
            runtime.enableShortTermMemory ? "短期" : nil,
            runtime.enableLongTermMemory ? "长期" : nil,
        ].compactMap { $0 }
        guard !candidates.isEmpty else {
            if enabledScopes.isEmpty {
                return "当前没有启用的记忆范围，聊天不会注入记忆。"
            }
            return "已启用 \(enabledScopes.joined(separator: "、"))，但没有可召回的未归档、未过期记忆。"
        }
        let maxItems = min(max(Int(runtime.memoryRecall.maxItems), 1), 40)
        let maxChars = min(max(Int(runtime.memoryRecall.maxPromptChars), 256), 12_000)
        return "聊天会从 \(enabledScopes.joined(separator: "、")) 中按当前问题相关性、置顶和记忆类型选择，单轮最多 \(maxItems) 条、\(maxChars) 字符。"
    }

    static func scopeTitle(_ scope: MemoryScope) -> String {
        if scope == MemoryScope.core { return "核心" }
        if scope == MemoryScope.shortTerm { return "短期" }
        if scope == MemoryScope.longTerm { return "长期" }
        return scope.wireName
    }

    static func kindTitle(_ kind: MemoryKind) -> String {
        switch kind.wireName {
        case "user": "用户"
        case "feedback": "反馈"
        case "project": "项目"
        case "reference": "资料"
        case "routine": "习惯"
        default: "笔记"
        }
    }

    static func scopeDisplay(_ raw: String) -> String {
        switch raw {
        case "core": "核心"
        case "short_term": "短期"
        case "long_term": "长期"
        default: raw
        }
    }

    static func kindDisplay(_ raw: String) -> String {
        switch raw {
        case "user": "用户"
        case "feedback": "反馈"
        case "project": "项目"
        case "reference": "资料"
        case "routine": "习惯"
        case "note": "笔记"
        default: raw
        }
    }

    static func actionDisplay(_ raw: String) -> String {
        switch raw {
        case "create", "add", "write": "新增"
        case "edit", "update": "修改"
        case "delete", "remove": "删除"
        default: raw
        }
    }

    static func sourceSummary(_ record: MemoryRecord) -> String {
        // 用户面不暴露 conversation UUID / 内部 id；只保留可读来源语义。
        var parts: [String] = []
        if let sourceConversationId = record.sourceConversationId, !sourceConversationId.isEmpty {
            parts.append("来自聊天")
        }
        if !record.sourceMessageIds.isEmpty {
            parts.append("关联 \(record.sourceMessageIds.count) 条消息")
        }
        if !record.supersedesIds.isEmpty {
            parts.append("替代 \(record.supersedesIds.count) 条旧记忆")
        }
        return parts.isEmpty ? "手动或工具写入，未记录来源" : parts.joined(separator: " · ")
    }

    static func preview(_ text: String, limit: Int = 180) -> String {
        let trimmed = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: " ")
        guard trimmed.count > limit else { return trimmed }
        return String(trimmed.prefix(limit)) + "..."
    }

    private static func memorySort(lhs: MemoryRecord, rhs: MemoryRecord) -> Bool {
        if lhs.pinned != rhs.pinned { return lhs.pinned && !rhs.pinned }
        if lhs.updatedAt != rhs.updatedAt { return lhs.updatedAt > rhs.updatedAt }
        return lhs.id < rhs.id
    }
}

struct IOSMemoryWriteAuditRecord: Codable, Identifiable, Equatable {
    var id: String
    var action: String
    var status: String
    var reason: String
    var memoryId: Int?
    var scope: String?
    var kind: String?
    var contentPreview: String?
    var createdAt: Int64
}

@MainActor
@Observable
final class IOSMemoryWriteAuditStore {
    static let shared = IOSMemoryWriteAuditStore()

    private let defaults: UserDefaults
    private let key: String
    private let limit = 80

    private(set) var records: [IOSMemoryWriteAuditRecord]

    init(
        userDefaults: UserDefaults = .standard,
        key: String = "app.amber.ios.memoryWriteAudit.v1"
    ) {
        self.defaults = userDefaults
        self.key = key
        if let data = userDefaults.data(forKey: key),
           let decoded = try? JSONDecoder().decode([IOSMemoryWriteAuditRecord].self, from: data) {
            self.records = decoded
        } else {
            self.records = []
        }
    }

    func record(
        action: String,
        status: String,
        reason: String = "",
        memoryId: Int? = nil,
        scope: String? = nil,
        kind: String? = nil,
        contentPreview: String? = nil
    ) {
        records.insert(
            IOSMemoryWriteAuditRecord(
                id: UUID().uuidString,
                action: action,
                status: status,
                reason: reason,
                memoryId: memoryId,
                scope: scope,
                kind: kind,
                contentPreview: contentPreview,
                createdAt: Int64(Date().timeIntervalSince1970 * 1_000)
            ),
            at: 0
        )
        if records.count > limit {
            records = Array(records.prefix(limit))
        }
        persist()
    }

    func clear() {
        records = []
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: key)
        }
    }
}
