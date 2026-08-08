import Foundation
import Observation
@preconcurrency import Shared

enum IOSMemoryToolWritePolicy: Equatable {
    case allow
    case needsUserAction(String)
    case denied(String)
    case deniedByUser(String)
}

struct IOSMemoryToolApprovalPreview: Equatable {
    let action: String
    let scope: String?
    let kind: String?
    let contentPreview: String?
    let targetId: Int?
    let expectedUpdatedAt: Int64?
}

enum IOSMemoryToolExecutor {
    /// search/query 默认与上限（入参 `limit` 可调，硬顶 20）。
    private static let defaultSearchLimit = 10
    private static let maxSearchLimit = 20
    /// list 数量上限（默认 50，入参 `limit` 可调，硬顶 200）。
    private static let defaultListLimit = 50
    private static let maxListLimit = 200
    /// list 单条 content 截断长度，超出部分以 "..." 结尾并打 `truncated`。
    private static let listContentLimit = 500

    @MainActor
    static func execute(
        input: String,
        runtime: AgentRuntimeSetting,
        writePolicy: IOSMemoryToolWritePolicy,
        expectedUpdatedAt: Int64? = nil
    ) -> String {
        guard let args = jsonObject(input) else {
            return json(["ok": false, "tool": "memory_tool", "error": "memory_tool input must be a JSON object"])
        }

        let action = action(from: args)
        switch action {
        case "list":
            return list(args: args, runtime: runtime)
        case "read":
            return read(args: args, runtime: runtime)
        case "search", "query":
            return search(args: args, runtime: runtime, action: action)
        case "status":
            return status(args: args, runtime: runtime)
        case "create", "add", "write":
            return create(args: args, runtime: runtime, writePolicy: writePolicy)
        case "edit", "update":
            return edit(
                args: args,
                runtime: runtime,
                writePolicy: writePolicy,
                expectedUpdatedAt: expectedUpdatedAt
            )
        case "delete", "remove":
            return delete(args: args, writePolicy: writePolicy, expectedUpdatedAt: expectedUpdatedAt)
        default:
            return json([
                "ok": false,
                "tool": "memory_tool",
                "error": "unknown action: \(action)"
            ])
        }
    }

    static func isEnabled(runtime: AgentRuntimeSetting) -> Bool {
        runtime.enableCoreMemory || runtime.enableShortTermMemory || runtime.enableLongTermMemory
    }

    static func requiresWriteApproval(input: String) -> Bool {
        guard let args = jsonObject(input) else { return false }
        switch action(from: args) {
        case "create", "add", "write", "edit", "update", "delete", "remove":
            return true
        default:
            return false
        }
    }

    static func approvalPreview(input: String) -> IOSMemoryToolApprovalPreview? {
        guard let args = jsonObject(input) else { return nil }
        let action = action(from: args)
        guard ["create", "add", "write", "edit", "update", "delete", "remove"].contains(action) else {
            return nil
        }

        let targetId = int(args["id"])
        let targetRecord = targetId.flatMap { id in
            IosMemoryFactory.shared.getAllRecords().first(where: { Int($0.id) == id })
        }
        let requestedContent = nonEmptyString(args["content"]).map { previewText($0) }
        let storedContent: String? = if ["delete", "remove"].contains(action) {
            targetRecord.map { previewText($0.content) }
        } else {
            nil
        }

        return IOSMemoryToolApprovalPreview(
            action: action,
            scope: nonEmptyString(args["scope"] ?? args["type"]),
            kind: nonEmptyString(args["kind"]),
            contentPreview: requestedContent ?? storedContent,
            targetId: targetId,
            expectedUpdatedAt: targetRecord?.updatedAt
        )
    }

    @MainActor
    private static func list(args: [String: Any], runtime: AgentRuntimeSetting) -> String {
        let requestedScope = (args["scope"] ?? args["type"]) as? String
        let records = IosMemoryFactory.shared.getAllRecords()
            .filter { record in
                guard isScopeEnabled(record.scope, runtime: runtime) else { return false }
                guard let requestedScope, requestedScope != "all" else { return true }
                return record.scope.wireName == requestedScope
            }
            .sorted { lhs, rhs in
                if lhs.pinned != rhs.pinned { return lhs.pinned && !rhs.pinned }
                if lhs.updatedAt != rhs.updatedAt { return lhs.updatedAt > rhs.updatedAt }
                return lhs.id < rhs.id
            }

        let limit = min(max(int(args["limit"]) ?? defaultListLimit, 1), maxListLimit)
        let limited = Array(records.prefix(limit))
        return json([
            "ok": true,
            "tool": "memory_tool",
            "action": "list",
            "count": limited.count,
            "total": records.count,
            "limit": limit,
            "truncated": records.count > limit,
            "memories": limited.map(listPayload)
        ])
    }

    /// list 专用 payload：单条 content 超过 listContentLimit 时截断并打
    /// `truncated` 标记，避免全量列表把长记忆整段倾倒给模型。create/edit
    /// 的写回响应仍用完整 recordPayload。
    private static func listPayload(_ record: MemoryRecord) -> [String: Any] {
        var payload = recordPayload(record)
        if record.content.count > listContentLimit {
            payload["content"] = String(record.content.prefix(listContentLimit)) + "..."
            payload["truncated"] = true
        }
        return payload
    }

    /// `read`：按入参 id 精确返回单条记录；不存在时返回结构化错误并提示用
    /// list 查看可用记忆，不再走全量列表。
    @MainActor
    private static func read(args: [String: Any], runtime: AgentRuntimeSetting) -> String {
        guard let id = int(args["id"]) else {
            return json([
                "ok": false,
                "tool": "memory_tool",
                "action": "read",
                "error": "read requires an id"
            ])
        }
        guard let record = IosMemoryFactory.shared.getAllRecords().first(where: { Int($0.id) == id }) else {
            return json([
                "ok": false,
                "tool": "memory_tool",
                "action": "read",
                "error": "memory not found",
                "id": id,
                "hint": "use action \"list\" to see available memories"
            ])
        }
        guard isScopeEnabled(record.scope, runtime: runtime) else {
            return disabledScopeResult(record.scope, action: "read")
        }
        return json([
            "ok": true,
            "tool": "memory_tool",
            "action": "read",
            "memory": recordPayload(record)
        ])
    }

    /// `search`/`query`：复用 ChatMemoryContextBuilder 的召回打分（pinned/
    /// 词元重叠/时间衰减/confidence），按分排序后返回 top N（默认 10，
    /// 上限 20）。过滤契约与上下文召回对齐：无词元重叠时仅 pinned / core /
    /// feedback / 高置信 long_term user 记录参与。
    @MainActor
    private static func search(args: [String: Any], runtime: AgentRuntimeSetting, action: String) -> String {
        let query = nonEmptyString(args["query"] ?? args["q"] ?? args["text"])
        let limit = min(max(int(args["limit"]) ?? defaultSearchLimit, 1), maxSearchLimit)
        let requestedScope = (args["scope"] ?? args["type"]) as? String
        let records = IosMemoryFactory.shared.getAllRecords()
            .filter { !$0.archived }
            .filter { record in
                guard isScopeEnabled(record.scope, runtime: runtime) else { return false }
                guard let requestedScope, requestedScope != "all" else { return true }
                return record.scope.wireName == requestedScope
            }
        let queryTokens = query.map { Set(ChatMemoryContextBuilder.recallTokens(from: $0)) } ?? []
        let scored = ChatMemoryContextBuilder.scoredByRelevance(records, queryText: query ?? "", now: nowMillis())
        let ranked = scored
            .filter { record, _ in
                queryTokens.isEmpty
                    || !Set(ChatMemoryContextBuilder.recallTokens(from: record.content)).isDisjoint(with: queryTokens)
                    || isAlwaysEligibleForSearch(record)
            }
            .sorted { lhs, rhs in
                if lhs.record.pinned != rhs.record.pinned { return lhs.record.pinned && !rhs.record.pinned }
                if lhs.score != rhs.score { return lhs.score > rhs.score }
                if lhs.record.updatedAt != rhs.record.updatedAt { return lhs.record.updatedAt > rhs.record.updatedAt }
                return lhs.record.id < rhs.record.id
            }
            .prefix(limit)
            .map(\.record)

        var payload: [String: Any] = [
            "ok": true,
            "tool": "memory_tool",
            "action": action,
            "count": ranked.count,
            "memories": ranked.map(recordPayload)
        ]
        if let query { payload["query"] = query }
        return json(payload)
    }

    /// 与 ChatMemoryContextBuilder.isAlwaysEligible 等价的最小实现。
    private static func isAlwaysEligibleForSearch(_ record: MemoryRecord) -> Bool {
        record.pinned || record.scope == .core || record.kind == .feedback ||
            (record.scope == .longTerm && record.kind == .user && record.confidence >= 0.70)
    }

    /// `status`：返回可用性摘要（各 scope 是否启用、可见/archived 记录数、
    /// 召回预算默认值），不倾倒全量 content。
    @MainActor
    private static func status(args: [String: Any], runtime: AgentRuntimeSetting) -> String {
        let records = IosMemoryFactory.shared.getAllRecords()
        let recall = runtime.memoryRecall
        let scopes = [MemoryScope.core, MemoryScope.shortTerm, MemoryScope.longTerm].map { scope in
            let scopeRecords = records.filter { $0.scope == scope }
            return [
                "scope": scope.wireName,
                "enabled": isScopeEnabled(scope, runtime: runtime),
                "visible": scopeRecords.filter { !$0.archived }.count,
                "archived": scopeRecords.filter { $0.archived }.count,
            ]
        }
        let visibleRecords = records.filter { isScopeEnabled($0.scope, runtime: runtime) && !$0.archived }
        let archivedRecords = records.filter { isScopeEnabled($0.scope, runtime: runtime) && $0.archived }
        return json([
            "ok": true,
            "tool": "memory_tool",
            "action": "status",
            "available": isEnabled(runtime: runtime),
            "scopes": scopes,
            "visibleCount": visibleRecords.count,
            "archivedCount": archivedRecords.count,
            "recallDefaults": [
                "maxItems": Int(recall.maxItems),
                "maxPromptChars": Int(recall.maxPromptChars),
            ],
        ])
    }

    @MainActor
    private static func create(
        args: [String: Any],
        runtime: AgentRuntimeSetting,
        writePolicy: IOSMemoryToolWritePolicy
    ) -> String {
        guard case .allow = writePolicy else {
            let result = writeBlockedResult(action: "create", writePolicy: writePolicy)
            auditWrite(action: "create", args: args, writePolicy: writePolicy)
            return result
        }
        guard let content = nonEmptyString(args["content"]) else {
            IOSMemoryWriteAuditStore.shared.record(
                action: "create",
                status: "failed",
                reason: "content is required"
            )
            return json(["ok": false, "tool": "memory_tool", "action": "create", "error": "content is required"])
        }
        guard let scope = memoryScope(from: (args["scope"] ?? args["type"]) as? String) else {
            IOSMemoryWriteAuditStore.shared.record(action: "create", status: "failed", reason: "invalid memory scope")
            return json(["ok": false, "tool": "memory_tool", "action": "create", "error": "invalid memory scope"])
        }
        guard isScopeEnabled(scope, runtime: runtime) else {
            IOSMemoryWriteAuditStore.shared.record(
                action: "create",
                status: "failed",
                reason: "memory scope is disabled",
                scope: scope.wireName
            )
            return disabledScopeResult(scope, action: "create")
        }

        guard let kind = memoryKind(from: args["kind"] as? String) else {
            IOSMemoryWriteAuditStore.shared.record(action: "create", status: "failed", reason: "invalid memory kind")
            return json(["ok": false, "tool": "memory_tool", "action": "create", "error": "invalid memory kind"])
        }
        guard let supersedesIds = kotlinIntArray(args["supersedesIds"]) else {
            IOSMemoryWriteAuditStore.shared.record(action: "create", status: "failed", reason: "supersedesIds contains an out-of-range integer")
            return integerOutOfRangeResult(action: "create", field: "supersedesIds")
        }
        let previousRecords = IosMemoryFactory.shared.snapshotRecords()
        let record = IosMemoryFactory.shared.addDetailedMemory(
            scope: scope,
            kind: kind,
            content: content,
            assistantId: bucket(for: scope),
            sourceConversationId: nonEmptyString(args["sourceConversationId"]),
            sourceMessageIds: stringArray(args["sourceMessageIds"]),
            supersedesIds: supersedesIds,
            expiresAt: int64(args["expiresAt"]).map { KotlinLong(value: $0) },
            confidence: float(args["confidence"]) ?? 1,
            pinned: bool(args["pinned"]) ?? false,
            archived: false
        )
        guard IOSMemoryPersistence.shared.persist(previousRecords: previousRecords) else {
            IOSMemoryWriteAuditStore.shared.record(action: "create", status: "failed", reason: "persistence failed")
            return json(["ok": false, "tool": "memory_tool", "action": "create", "error": "persistence failed"])
        }
        IOSMemoryWriteAuditStore.shared.record(
            action: "create",
            status: "approved",
            memoryId: Int(record.id),
            scope: record.scope.wireName,
            kind: record.kind.wireName,
            contentPreview: previewText(record.content)
        )

        return json([
            "ok": true,
            "tool": "memory_tool",
            "action": "create",
            "memory": recordPayload(record)
        ])
    }

    @MainActor
    private static func edit(
        args: [String: Any],
        runtime: AgentRuntimeSetting,
        writePolicy: IOSMemoryToolWritePolicy,
        expectedUpdatedAt: Int64?
    ) -> String {
        guard case .allow = writePolicy else {
            let result = writeBlockedResult(action: "edit", writePolicy: writePolicy)
            auditWrite(action: "edit", args: args, writePolicy: writePolicy)
            return result
        }
        guard let id = int(args["id"]) else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "id is required")
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "id is required"])
        }
        let previousRecords = IosMemoryFactory.shared.snapshotRecords()
        guard let existing = previousRecords.first(where: { Int($0.id) == id }) else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "memory not found", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "memory not found", "id": id])
        }
        guard expectedUpdatedAt == nil || existing.updatedAt == expectedUpdatedAt else {
            IOSMemoryWriteAuditStore.shared.record(
                action: "edit",
                status: "failed",
                reason: "memory changed after approval",
                memoryId: id
            )
            return staleApprovalResult(action: "edit", id: id)
        }
        guard let content = nonEmptyString(args["content"]) else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "content is required", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "content is required"])
        }

        let requestedScope = (args["scope"] ?? args["type"]) as? String
        guard requestedScope == nil || memoryScope(from: requestedScope) != nil else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "invalid memory scope", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "invalid memory scope", "id": id])
        }
        let scope = requestedScope.flatMap(memoryScope) ?? existing.scope
        guard isScopeEnabled(scope, runtime: runtime) else {
            IOSMemoryWriteAuditStore.shared.record(
                action: "edit",
                status: "failed",
                reason: "memory scope is disabled",
                memoryId: id,
                scope: scope.wireName
            )
            return disabledScopeResult(scope, action: "edit")
        }

        let requestedKind = args["kind"] as? String
        guard requestedKind == nil || memoryKind(from: requestedKind) != nil else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "invalid memory kind", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "invalid memory kind", "id": id])
        }
        let kind = requestedKind.flatMap(memoryKind) ?? existing.kind
        let sourceMessageIds = args.keys.contains("sourceMessageIds")
            ? stringArray(args["sourceMessageIds"])
            : existing.sourceMessageIds
        let supersedesIds: [KotlinInt]
        if args.keys.contains("supersedesIds") {
            guard let parsedSupersedesIds = kotlinIntArray(args["supersedesIds"]) else {
                IOSMemoryWriteAuditStore.shared.record(
                    action: "edit",
                    status: "failed",
                    reason: "supersedesIds contains an out-of-range integer",
                    memoryId: id
                )
                return integerOutOfRangeResult(action: "edit", field: "supersedesIds")
            }
            supersedesIds = parsedSupersedesIds
        } else {
            supersedesIds = existing.supersedesIds
        }
        let updatedAt = nowMillis()
        let updated = MemoryRecord(
            id: existing.id,
            content: content,
            scope: scope,
            kind: kind,
            assistantId: bucket(for: scope),
            sourceConversationId: args.keys.contains("sourceConversationId")
                ? nonEmptyString(args["sourceConversationId"])
                : existing.sourceConversationId,
            sourceMessageIds: sourceMessageIds,
            supersedesIds: supersedesIds,
            expiresAt: args.keys.contains("expiresAt")
                ? int64(args["expiresAt"]).map { KotlinLong(value: $0) }
                : existing.expiresAt,
            confidence: float(args["confidence"]) ?? existing.confidence,
            pinned: bool(args["pinned"]) ?? existing.pinned,
            archived: existing.archived,
            createdAt: existing.createdAt,
            updatedAt: updatedAt,
            lastUsedAt: existing.lastUsedAt
        )

        guard let saved = IosMemoryFactory.shared.updateRecord(record: updated) else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "memory not found", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "memory not found", "id": id])
        }
        guard IOSMemoryPersistence.shared.persist(previousRecords: previousRecords) else {
            IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "persistence failed", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "edit", "error": "persistence failed", "id": id])
        }
        IOSMemoryWriteAuditStore.shared.record(
            action: "edit",
            status: "approved",
            memoryId: id,
            scope: saved.scope.wireName,
            kind: saved.kind.wireName,
            contentPreview: previewText(saved.content)
        )

        return json([
            "ok": true,
            "tool": "memory_tool",
            "action": "edit",
            "memory": recordPayload(saved)
        ])
    }

    @MainActor
    private static func delete(
        args: [String: Any],
        writePolicy: IOSMemoryToolWritePolicy,
        expectedUpdatedAt: Int64?
    ) -> String {
        guard case .allow = writePolicy else {
            let result = writeBlockedResult(action: "delete", writePolicy: writePolicy)
            auditWrite(action: "delete", args: args, writePolicy: writePolicy)
            return result
        }
        guard let id = int(args["id"]) else {
            IOSMemoryWriteAuditStore.shared.record(action: "delete", status: "failed", reason: "id is required")
            return json(["ok": false, "tool": "memory_tool", "action": "delete", "error": "id is required"])
        }
        let previousRecords = IosMemoryFactory.shared.snapshotRecords()
        let existing = previousRecords.first { Int($0.id) == id }
        let existed = existing != nil
        guard existed else {
            IOSMemoryWriteAuditStore.shared.record(action: "delete", status: "failed", reason: "memory not found", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "delete", "error": "memory not found", "id": id])
        }
        guard expectedUpdatedAt == nil || existing?.updatedAt == expectedUpdatedAt else {
            IOSMemoryWriteAuditStore.shared.record(
                action: "delete",
                status: "failed",
                reason: "memory changed after approval",
                memoryId: id
            )
            return staleApprovalResult(action: "delete", id: id)
        }
        IosMemoryFactory.shared.deleteMemory(id: Int32(id))
        guard IOSMemoryPersistence.shared.persist(previousRecords: previousRecords) else {
            IOSMemoryWriteAuditStore.shared.record(action: "delete", status: "failed", reason: "persistence failed", memoryId: id)
            return json(["ok": false, "tool": "memory_tool", "action": "delete", "error": "persistence failed", "id": id])
        }
        IOSMemoryWriteAuditStore.shared.record(action: "delete", status: "approved", memoryId: id)
        return json([
            "ok": true,
            "tool": "memory_tool",
            "action": "delete",
            "id": id
        ])
    }

    private static func writeBlockedResult(action: String, writePolicy: IOSMemoryToolWritePolicy) -> String {
        switch writePolicy {
        case .allow:
            json(["ok": false, "tool": "memory_tool", "action": action, "error": "unexpected write policy"])
        case .needsUserAction(let reason):
            json([
                "ok": false,
                "tool": "memory_tool",
                "action": action,
                "needs_user_action": true,
                "policy": "foreground_required",
                "reason": reason
            ])
        case .denied(let reason):
            json([
                "ok": false,
                "tool": "memory_tool",
                "action": action,
                "denied": true,
                "policy": "disabled",
                "reason": reason
            ])
        case .deniedByUser(let reason):
            json([
                "ok": false,
                "tool": "memory_tool",
                "action": action,
                "denied": true,
                "policy": "user_denied",
                "reason": reason
            ])
        }
    }

    private static func staleApprovalResult(action: String, id: Int) -> String {
        json([
            "ok": false,
            "tool": "memory_tool",
            "action": action,
            "error": "memory changed after approval",
            "code": "stale_memory",
            "id": id,
        ])
    }

    @MainActor
    private static func auditWrite(
        action: String,
        args: [String: Any],
        writePolicy: IOSMemoryToolWritePolicy
    ) {
        let status: String
        let reason: String
        switch writePolicy {
        case .allow:
            status = "unexpected"
            reason = ""
        case .needsUserAction(let message):
            status = "needs_user_action"
            reason = message
        case .denied(let message):
            status = "denied"
            reason = message
        case .deniedByUser(let message):
            status = "denied_by_user"
            reason = message
        }
        IOSMemoryWriteAuditStore.shared.record(
            action: action,
            status: status,
            reason: reason,
            memoryId: int(args["id"]),
            scope: nonEmptyString(args["scope"] ?? args["type"]),
            kind: nonEmptyString(args["kind"])
        )
    }

    private static func disabledScopeResult(_ scope: MemoryScope, action: String) -> String {
        json([
            "ok": false,
            "tool": "memory_tool",
            "action": action,
            "scope": scope.wireName,
            "error": "memory scope is disabled"
        ])
    }

    private static func isScopeEnabled(_ scope: MemoryScope, runtime: AgentRuntimeSetting) -> Bool {
        if scope == MemoryScope.core { return runtime.enableCoreMemory }
        if scope == MemoryScope.shortTerm { return runtime.enableShortTermMemory }
        if scope == MemoryScope.longTerm { return runtime.enableLongTermMemory }
        return false
    }

    private static func memoryScope(from wireName: String?) -> MemoryScope? {
        switch wireName {
        case "core":
            MemoryScope.core
        case "short_term":
            MemoryScope.shortTerm
        case "long_term", nil:
            MemoryScope.longTerm
        default:
            nil
        }
    }

    private static func memoryKind(from wireName: String?) -> MemoryKind? {
        switch wireName {
        case "user":
            MemoryKind.user
        case "feedback":
            MemoryKind.feedback
        case "project":
            MemoryKind.project
        case "reference":
            MemoryKind.reference
        case "routine":
            MemoryKind.routine
        case "note", nil:
            MemoryKind.note
        default:
            nil
        }
    }

    private static func bucket(for scope: MemoryScope) -> String {
        if scope == MemoryScope.core { return IosMemoryFactory.shared.GLOBAL_MEMORY_ID }
        if scope == MemoryScope.shortTerm { return IosMemoryFactory.shared.SHORT_TERM_MEMORY_ID }
        return IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
    }

    private static func recordPayload(_ record: MemoryRecord) -> [String: Any] {
        var payload: [String: Any] = [
            "id": Int(record.id),
            "type": record.scope.wireName,
            "scope": record.scope.wireName,
            "kind": record.kind.wireName,
            "content": record.content,
            "assistantId": record.assistantId,
            "confidence": Double(record.confidence),
            "pinned": record.pinned,
            "archived": record.archived,
            "createdAt": record.createdAt,
            "updatedAt": record.updatedAt
        ]
        if let sourceConversationId = record.sourceConversationId {
            payload["sourceConversationId"] = sourceConversationId
        }
        let sourceMessageIds = record.sourceMessageIds
        if !sourceMessageIds.isEmpty {
            payload["sourceMessageIds"] = sourceMessageIds
        }
        let supersedesIds = record.supersedesIds.map { Int(truncating: $0) }
        if !supersedesIds.isEmpty {
            payload["supersedesIds"] = supersedesIds
        }
        if let expiresAt = record.expiresAt?.int64Value {
            payload["expiresAt"] = expiresAt
        }
        if let lastUsedAt = record.lastUsedAt?.int64Value {
            payload["lastUsedAt"] = lastUsedAt
        }
        return payload
    }

    private static func jsonObject(_ string: String) -> [String: Any]? {
        guard let data = string.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func action(from args: [String: Any]) -> String {
        ((args["action"] ?? args["operation"] ?? args["op"]) as? String ?? "list").lowercased()
    }

    private static func json(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return #"{"ok":false,"tool":"memory_tool","error":"failed to encode result"}"#
        }
        return text
    }

    private static func nonEmptyString(_ value: Any?) -> String? {
        guard let text = value as? String else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private static func previewText(_ text: String, maxLength: Int = 180) -> String {
        let normalized = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: " ")
        guard normalized.count > maxLength else { return normalized }
        return String(normalized.prefix(maxLength)) + "..."
    }

    private static func stringArray(_ value: Any?) -> [String] {
        (value as? [Any])?.compactMap { $0 as? String } ?? []
    }

    private static func kotlinIntArray(_ value: Any?) -> [KotlinInt]? {
        guard let value else { return [] }
        guard let items = value as? [Any] else { return nil }
        var result: [KotlinInt] = []
        for item in items {
            guard let value = int(item) else { return nil }
            guard let exactValue = Int32(exactly: value) else { return nil }
            result.append(KotlinInt(value: exactValue))
        }
        return result
    }

    private static func integerOutOfRangeResult(action: String, field: String) -> String {
        json([
            "ok": false,
            "tool": "memory_tool",
            "action": action,
            "code": "integer_out_of_range",
            "field": field,
            "error": "\(field) must contain only 32-bit signed integers",
        ])
    }

    private static func int(_ value: Any?) -> Int? {
        if let number = value as? NSNumber {
            guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
            return Int(number.stringValue)
        }
        if let int = value as? Int { return int }
        if let string = value as? String { return Int(string) }
        return nil
    }

    private static func int64(_ value: Any?) -> Int64? {
        if let int64 = value as? Int64 { return int64 }
        if let number = value as? NSNumber { return number.int64Value }
        if let string = value as? String { return Int64(string) }
        return nil
    }

    private static func float(_ value: Any?) -> Float? {
        if let float = value as? Float { return float }
        if let number = value as? NSNumber { return number.floatValue }
        if let string = value as? String { return Float(string) }
        return nil
    }

    private static func bool(_ value: Any?) -> Bool? {
        if let bool = value as? Bool { return bool }
        if let number = value as? NSNumber { return number.boolValue }
        if let string = value as? String {
            switch string.lowercased() {
            case "true", "1", "yes":
                return true
            case "false", "0", "no":
                return false
            default:
                return nil
            }
        }
        return nil
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

/// [Slice 6] iOS-local persistence for memories.
///
/// The KMP `IosMemoryFactory` is a pure in-memory StateFlow (no file IO). This
/// Swift wrapper adds real persistence to `Documents/memories/memories.json`:
///   - `load()` at app startup reads the file and calls
///     `IosMemoryFactory.shared.replaceAll(records:)` to seed the store.
///   - `persist()` is called after each mutation (add/update/delete) to write
///     `IosMemoryFactory.shared.snapshotRecords()` to disk (atomic write).
///
/// Because `MemoryRecord` is a KMP class (not Swift Codable), we round-trip
/// through a Codable Swift mirror (`PersistedMemoryRecord`) that maps the same
/// fields and the `MemoryScope`/`MemoryKind` enums (serialized by their
/// serialName to stay compatible with Android/KMP's @Serializable form).
///
/// A write failure rolls the KMP store back to the caller-provided snapshot so
/// memory state never claims a mutation that was not durably written.
@Observable
@MainActor
final class IOSMemoryPersistence {

    enum LoadState: Equatable {
        case notLoaded
        case loaded
        case missing
        case unreadable
    }

    static let shared = IOSMemoryPersistence()

    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private(set) var records: [MemoryRecord] = []
    private(set) var revision: Int = 0
    private(set) var loadState: LoadState = .notLoaded
    private(set) var lastErrorMessage: String?

    init(fileURL: URL, encoder: JSONEncoder = JSONEncoder(), decoder: JSONDecoder = JSONDecoder()) {
        self.fileURL = fileURL
        self.encoder = encoder
        self.decoder = decoder
        records = IosMemoryFactory.shared.getAllRecords()
    }

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        fileURL = docs.appendingPathComponent("memories", isDirectory: true)
            .appendingPathComponent("memories.json", isDirectory: false)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        records = IosMemoryFactory.shared.getAllRecords()
    }

    /// Load persisted records into the KMP store. Call once at app startup.
    /// A missing file starts an empty library. An unreadable file is preserved
    /// and blocks later writes until a subsequent load succeeds.
    func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            IosMemoryFactory.shared.replaceAll(records: [])
            loadState = .missing
            lastErrorMessage = nil
            refresh()
            return
        }
        do {
            let data = try Data(contentsOf: fileURL)
            let persisted = try decoder.decode([PersistedMemoryRecord].self, from: data)
            let kmpRecords = try persisted.map { try $0.toKmp() }
            IosMemoryFactory.shared.replaceAll(records: kmpRecords)
            records = kmpRecords
            revision += 1
            loadState = .loaded
            lastErrorMessage = nil
        } catch {
            loadState = .unreadable
            lastErrorMessage = "无法读取现有记忆，已停止写入以保护原文件。"
        }
    }

    func refresh() {
        records = IosMemoryFactory.shared.getAllRecords()
        revision += 1
    }

    /// Write the current KMP records to disk. Call after each mutation.
    @discardableResult
    func persist(previousRecords: [MemoryRecord]) -> Bool {
        guard loadState != .notLoaded else {
            lastErrorMessage = "记忆尚未加载，无法安全写入。"
            rollback(to: previousRecords)
            return false
        }
        guard loadState != .unreadable else {
            rollback(to: previousRecords)
            return false
        }
        let snapshot = IosMemoryFactory.shared.snapshotRecords()
        let persisted = snapshot.map { PersistedMemoryRecord.from($0) }
        do {
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let data = try encoder.encode(persisted)
            // .atomic writes via a temp file + rename, so a crash mid-write
            // can't corrupt the existing store.
            try data.write(to: fileURL, options: [.atomic])
            records = snapshot
            revision += 1
            loadState = .loaded
            lastErrorMessage = nil
            return true
        } catch {
            print("[IOSMemoryPersistence] persist failed: \(error.localizedDescription)")
            lastErrorMessage = "无法写入记忆，请检查设备存储空间后重试。"
            rollback(to: previousRecords)
            return false
        }
    }

    private func rollback(to previousRecords: [MemoryRecord]) {
        IosMemoryFactory.shared.replaceAll(records: previousRecords)
        records = previousRecords
        revision += 1
    }
}

// MARK: - Codable mirror of KMP MemoryRecord

/// Swift Codable mirror of KMP `MemoryRecord`. Field names match the KMP
/// @Serializable defaults (camelCase) so the file is readable and round-trips
/// through kotlinx.serialization if ever consumed by KMP.
private struct PersistedMemoryRecord: Codable {
    var id: Int
    var content: String
    var scope: String
    var kind: String
    var assistantId: String
    var sourceConversationId: String?
    var sourceMessageIds: [String]
    var supersedesIds: [Int]
    var expiresAt: Int64?
    var confidence: Double
    var pinned: Bool
    var archived: Bool
    var createdAt: Int64
    var updatedAt: Int64
    var lastUsedAt: Int64?

    init(
        id: Int,
        content: String,
        scope: String,
        kind: String,
        assistantId: String,
        sourceConversationId: String?,
        sourceMessageIds: [String],
        supersedesIds: [Int],
        expiresAt: Int64?,
        confidence: Double,
        pinned: Bool,
        archived: Bool,
        createdAt: Int64,
        updatedAt: Int64,
        lastUsedAt: Int64?
    ) {
        self.id = id
        self.content = content
        self.scope = scope
        self.kind = kind
        self.assistantId = assistantId
        self.sourceConversationId = sourceConversationId
        self.sourceMessageIds = sourceMessageIds
        self.supersedesIds = supersedesIds
        self.expiresAt = expiresAt
        self.confidence = confidence
        self.pinned = pinned
        self.archived = archived
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastUsedAt = lastUsedAt
    }

    private enum CodingKeys: String, CodingKey {
        case id, content, scope, kind, assistantId, sourceConversationId, sourceMessageIds
        case supersedesIds, expiresAt, confidence, pinned, archived, createdAt, updatedAt, lastUsedAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        content = try c.decode(String.self, forKey: .content)
        scope = try c.decode(String.self, forKey: .scope)
        kind = try c.decode(String.self, forKey: .kind)
        assistantId = try c.decode(String.self, forKey: .assistantId)
        guard memoryScopeByName(scope) != nil else {
            throw DecodingError.dataCorruptedError(forKey: .scope, in: c, debugDescription: "Unknown memory scope")
        }
        guard memoryKindByName(kind) != nil else {
            throw DecodingError.dataCorruptedError(forKey: .kind, in: c, debugDescription: "Unknown memory kind")
        }
        sourceConversationId = try c.decodeIfPresent(String.self, forKey: .sourceConversationId)
        sourceMessageIds = try c.decodeIfPresent([String].self, forKey: .sourceMessageIds) ?? []
        supersedesIds = try c.decodeIfPresent([Int].self, forKey: .supersedesIds) ?? []
        expiresAt = try c.decodeIfPresent(Int64.self, forKey: .expiresAt)
        confidence = try c.decodeIfPresent(Double.self, forKey: .confidence) ?? 1
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        archived = try c.decodeIfPresent(Bool.self, forKey: .archived) ?? false
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? createdAt
        lastUsedAt = try c.decodeIfPresent(Int64.self, forKey: .lastUsedAt)
    }

    static func from(_ record: MemoryRecord) -> PersistedMemoryRecord {
        PersistedMemoryRecord(
            id: Int(record.id),
            content: record.content,
            // [Slice 6 review P1] Use wireName (the @SerialName form, e.g.
            // "core"/"short_term"/"user") so the JSON file is interchangeable
            // with Android/KMP's kotlinx.serialization — not the Kotlin
            // constant identifier ("CORE"/"USER").
            scope: record.scope.wireName,
            kind: record.kind.wireName,
            assistantId: record.assistantId,
            sourceConversationId: record.sourceConversationId,
            sourceMessageIds: record.sourceMessageIds,
            supersedesIds: record.supersedesIds.map { Int(truncating: $0) },
            expiresAt: record.expiresAt?.int64Value,
            confidence: Double(record.confidence),
            pinned: record.pinned,
            archived: record.archived,
            createdAt: record.createdAt,
            updatedAt: record.updatedAt,
            lastUsedAt: record.lastUsedAt?.int64Value
        )
    }

    func toKmp() throws -> MemoryRecord {
        guard let exactId = Int32(exactly: id) else {
            throw DecodingError.dataCorrupted(.init(
                codingPath: [],
                debugDescription: "Memory id is outside the 32-bit signed integer range"
            ))
        }
        let exactSupersedesIds = try supersedesIds.map { value -> KotlinInt in
            guard let exactValue = Int32(exactly: value) else {
                throw DecodingError.dataCorrupted(.init(
                    codingPath: [],
                    debugDescription: "Memory supersedesIds contains a value outside the 32-bit signed integer range"
                ))
            }
            return KotlinInt(value: exactValue)
        }
        let scopeValue = memoryScopeByName(scope) ?? MemoryScope.longTerm
        let kindValue = memoryKindByName(kind) ?? MemoryKind.note
        return MemoryRecord(
            id: exactId,
            content: content,
            scope: scopeValue,
            kind: kindValue,
            assistantId: assistantId,
            sourceConversationId: sourceConversationId,
            sourceMessageIds: sourceMessageIds,
            supersedesIds: exactSupersedesIds,
            expiresAt: expiresAt.map { KotlinLong(value: $0) },
            confidence: Float(confidence),
            pinned: pinned,
            archived: archived,
            createdAt: createdAt,
            updatedAt: updatedAt,
            lastUsedAt: lastUsedAt.map { KotlinLong(value: $0) }
        )
    }
}

/// Iterate a KotlinArray<MemoryScope> by index (no Collection conformance in
/// the bridge) and match by `wireName` (the @SerialName form).
private func memoryScopeByName(_ wireName: String) -> MemoryScope? {
    let array = MemoryScope.values()
    let count = Int(array.size)
    for index in 0..<count {
        guard let value = array.get(index: Int32(index)) else { continue }
        if value.wireName == wireName { return value }
    }
    return nil
}

private func memoryKindByName(_ wireName: String) -> MemoryKind? {
    let array = MemoryKind.values()
    let count = Int(array.size)
    for index in 0..<count {
        guard let value = array.get(index: Int32(index)) else { continue }
        if value.wireName == wireName { return value }
    }
    return nil
}
