import CryptoKit
import Foundation
import Observation
@preconcurrency import Shared
#if canImport(EventKit)
@preconcurrency import EventKit
#endif

/// [Board MVP] iOS-local persistence for the generated board signal summary (Markdown).
///
/// Scope (per product decision): ONLY the generated board content is persisted
/// — the model's Markdown output for a given date. NOT persisted: the structured
/// task-flow / BoardItemEntity / opportunity / daily-report / dispatch that
/// Android's BoardRepository + BoardItemDAO handle (those are out of scope for
/// iOS; UI labels should keep that scope explicit).
///
/// Shape: one JSON file per board date under `Documents/boards/<yyyy-MM-dd>.json`,
/// each containing {date, markdown, signalCount, generatedAt}. On app/board-page
/// entry we load the most recent file so the last generated board survives
/// restart. Atomic writes (temp + rename) so a crash can't corrupt it.
@Observable
@MainActor
final class IOSBoardPersistence {

    static let shared = IOSBoardPersistence()

    /// A persisted board entry. Codable so we can JSON-encode directly.
    struct PersistedBoard: Codable, Equatable {
        var boardDate: String          // yyyy-MM-dd (local)
        var markdown: String           // model-generated board content
        var signalCount: Int           // how many signals fed this generation
        var generatedAt: Int64         // epoch ms
        var sourceCounts: [String: Int]? = nil
    }

    private let directory: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let dateFormatter: DateFormatter

    init(baseDirectory: URL? = nil) {
        let root = baseDirectory
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = root.appendingPathComponent("boards", isDirectory: true)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        let f = DateFormatter()
        f.locale = Locale.current
        f.timeZone = TimeZone.current
        f.dateFormat = "yyyy-MM-dd"
        dateFormatter = f
    }

    /// Today's board-date bucket (local yyyy-MM-dd).
    func todayBoardDate() -> String {
        dateFormatter.string(from: Date())
    }

    /// Persist a generated board. Overwrites same-date entry (today's board is
    /// always the freshest generation). Best-effort: logs on failure, doesn't
    /// crash — the in-session display still works.
    func save(board: PersistedBoard) {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(board)
            try data.write(to: fileURL(for: board.boardDate), options: [.atomic])
        } catch {
            print("[IOSBoardPersistence] save failed: \(error.localizedDescription)")
        }
    }

    /// Load a board by date. Returns nil if none exists (honest empty state).
    func load(boardDate: String) -> PersistedBoard? {
        let url = fileURL(for: boardDate)
        guard FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(PersistedBoard.self, from: data)
    }

    /// Load the most recent persisted board (for app/board-page launch display).
    /// Scans the boards dir, decodes each, returns the newest by generatedAt.
    func loadMostRecent() -> PersistedBoard? {
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else {
            return nil
        }
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { try? Data(contentsOf: $0) }
            .compactMap { try? decoder.decode(PersistedBoard.self, from: $0) }
            .max(by: { $0.generatedAt < $1.generatedAt })
    }

    /// Delete a board by date (used by "重新生成" / clear flows).
    func delete(boardDate: String) {
        let url = fileURL(for: boardDate)
        try? FileManager.default.removeItem(at: url)
    }

    private func fileURL(for boardDate: String) -> URL {
        directory.appendingPathComponent("\(boardDate).json", isDirectory: false)
    }
}

// MARK: - Board Signal Model

enum IOSBoardSignalSourceType {
    static let notification = "notification"
    static let calendar = "calendar"
    static let reminder = "reminder"
    static let feishuMessage = "feishu_msg"
    static let feishuDocument = "feishu_doc"
    static let chatHistory = "chat_history"
    static let webmount = "webmount"
    static let time = "time"
    static let hotlist = "hotlist"
}

struct IOSRawBoardSignal: Codable, Equatable, Sendable {
    var sourceType: String
    var sourceRef: String
    var title: String
    var content: String
    var signalTime: Int64
    var metadataJson: String

    init(
        sourceType: String,
        sourceRef: String,
        title: String,
        content: String,
        signalTime: Int64,
        metadataJson: String = "{}"
    ) {
        self.sourceType = sourceType
        self.sourceRef = sourceRef
        self.title = title
        self.content = content
        self.signalTime = signalTime
        self.metadataJson = metadataJson
    }

    init(signal: BoardSignal) {
        self.init(
            sourceType: signal.sourceType,
            sourceRef: signal.sourceRef,
            title: signal.title,
            content: signal.content,
            signalTime: signal.signalTime,
            metadataJson: signal.metadataJson
        )
    }
}

struct IOSBoardSignalRecord: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var sourceType: String
    var sourceRef: String
    var title: String
    var content: String
    var contentHash: String
    var signalTime: Int64
    var metadataJson: String
    var processed: Bool
    var processedAt: Int64?
    var createdAt: Int64

    func asBoardSignal() -> BoardSignal {
        BoardSignal(
            sourceType: sourceType,
            sourceRef: sourceRef,
            title: title,
            content: content,
            signalTime: signalTime,
            metadataJson: metadataJson
        )
    }
}

enum IOSBoardIngestOutcome: Equatable, Sendable {
    case saved(IOSBoardSignalRecord)
    case duplicateSourceRef(IOSBoardSignalRecord)
    case duplicateContentHash(IOSBoardSignalRecord)
}

struct IOSBoardCollectorOutput: Equatable, Sendable {
    var signals: [IOSRawBoardSignal]
    var statusMessage: String?
    var errorMessage: String?

    init(signals: [IOSRawBoardSignal] = [], statusMessage: String? = nil, errorMessage: String? = nil) {
        self.signals = signals
        self.statusMessage = statusMessage
        self.errorMessage = errorMessage
    }
}

struct IOSBoardCollectorStatus: Codable, Equatable, Identifiable, Sendable {
    var id: String { sourceType }

    var sourceType: String
    var collectedCount: Int
    var ingestedCount: Int
    var duplicateCount: Int
    var latestTitle: String?
    var latestSignalTime: Int64?
    var statusMessage: String?
    var errorMessage: String?

    static func skipped(sourceType: String, reason: String) -> IOSBoardCollectorStatus {
        IOSBoardCollectorStatus(
            sourceType: sourceType,
            collectedCount: 0,
            ingestedCount: 0,
            duplicateCount: 0,
            latestTitle: nil,
            latestSignalTime: nil,
            statusMessage: reason,
            errorMessage: nil
        )
    }
}

struct IOSBoardCollectionSnapshot: Equatable, Sendable {
    var statuses: [IOSBoardCollectorStatus]
    var recentSignals: [IOSBoardSignalRecord]
    var pendingCount: Int
    var lastRunAt: Int64?
    var lastRunError: String?

    static let empty = IOSBoardCollectionSnapshot(
        statuses: [],
        recentSignals: [],
        pendingCount: 0,
        lastRunAt: nil,
        lastRunError: nil
    )

    var totalCollected: Int {
        statuses.reduce(0) { $0 + $1.collectedCount }
    }

    var totalIngested: Int {
        statuses.reduce(0) { $0 + $1.ingestedCount }
    }

    var sourceCounts: [String: Int] {
        Dictionary(uniqueKeysWithValues: statuses.map { ($0.sourceType, $0.ingestedCount) })
    }
}

struct IOSBoardSignalBatch: Equatable, Sendable {
    var agentRecords: [IOSBoardSignalRecord]
    var consideredIds: [String]

    static let empty = IOSBoardSignalBatch(agentRecords: [], consideredIds: [])
}

struct IOSBoardRunOnceResult: Equatable, Sendable {
    var snapshot: IOSBoardCollectionSnapshot
    var batch: IOSBoardSignalBatch
    var prunedCount: Int

    var boardSignals: [BoardSignal] {
        batch.agentRecords.map { $0.asBoardSignal() }
    }
}

@MainActor
protocol IOSBoardSignalCollector {
    var sourceType: String { get }
    func collect(limit: Int) async -> IOSBoardCollectorOutput
}

// MARK: - Board Signal Repository

@Observable
@MainActor
final class IOSBoardSignalRepository {
    static let shared = IOSBoardSignalRepository()

    private(set) var records: [IOSBoardSignalRecord]

    private let directory: URL
    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let root = baseDirectory
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = root
            .appendingPathComponent("boards", isDirectory: true)
            .appendingPathComponent("signals", isDirectory: true)
        fileURL = directory.appendingPathComponent("board_signals.json", isDirectory: false)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        records = []
        records = Self.loadRecords(from: fileURL, decoder: decoder)
    }

    @discardableResult
    func ingest(
        _ raw: IOSRawBoardSignal,
        now: Int64 = IOSBoardSignalRepository.currentEpochMs()
    ) -> IOSBoardIngestOutcome {
        let sourceType = raw.sourceType.trimmingCharacters(in: .whitespacesAndNewlines)
        let sourceRef = raw.sourceRef.trimmingCharacters(in: .whitespacesAndNewlines)
        let title = raw.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let content = raw.content.trimmingCharacters(in: .whitespacesAndNewlines)
        let hash = Self.contentHash(sourceType: sourceType, title: title, content: content)

        if let existing = findSignalBySourceRef(sourceType: sourceType, sourceRef: sourceRef) {
            return .duplicateSourceRef(existing)
        }
        let dedupWindowStart = now - Self.dedupWindowMs
        if let existing = findSignalByContentHash(hash, sourceType: sourceType, sinceMs: dedupWindowStart) {
            return .duplicateContentHash(existing)
        }

        let record = IOSBoardSignalRecord(
            id: UUID().uuidString,
            sourceType: sourceType,
            sourceRef: sourceRef,
            title: title.isEmpty ? "未命名信号" : String(title.prefix(160)),
            content: String(content.prefix(4_000)),
            contentHash: hash,
            signalTime: raw.signalTime,
            metadataJson: raw.metadataJson.isEmpty ? "{}" : raw.metadataJson,
            processed: false,
            processedAt: nil,
            createdAt: now
        )
        records.append(record)
        persist()
        return .saved(record)
    }

    func findSignalBySourceRef(sourceType: String, sourceRef: String) -> IOSBoardSignalRecord? {
        records.first { $0.sourceType == sourceType && $0.sourceRef == sourceRef }
    }

    func findSignalByContentHash(
        _ contentHash: String,
        sourceType: String,
        sinceMs: Int64
    ) -> IOSBoardSignalRecord? {
        records.first {
            $0.sourceType == sourceType &&
                $0.contentHash == contentHash &&
                $0.createdAt >= sinceMs
        }
    }

    func getUnprocessedSignals(limit: Int = 200) -> [IOSBoardSignalRecord] {
        records
            .filter { !$0.processed }
            .sorted { $0.signalTime > $1.signalTime }
            .prefix(limit)
            .map { $0 }
    }

    func countUnprocessedSignals() -> Int {
        records.filter { !$0.processed }.count
    }

    func recentSignals(limit: Int = 12) -> [IOSBoardSignalRecord] {
        records
            .sorted { $0.createdAt > $1.createdAt }
            .prefix(limit)
            .map { $0 }
    }

    func markSignalsProcessed(
        ids: [String],
        now: Int64 = IOSBoardSignalRepository.currentEpochMs()
    ) {
        guard !ids.isEmpty else { return }
        let targetIds = Set(ids)
        var changed = false
        records = records.map { record in
            guard targetIds.contains(record.id), !record.processed else { return record }
            var updated = record
            updated.processed = true
            updated.processedAt = now
            changed = true
            return updated
        }
        if changed { persist() }
    }

    @discardableResult
    func pruneProcessedSignals(olderThanMs: Int64) -> Int {
        let before = records.count
        records.removeAll { record in
            guard record.processed, let processedAt = record.processedAt else { return false }
            return processedAt < olderThanMs
        }
        let removed = before - records.count
        if removed > 0 { persist() }
        return removed
    }

    private func persist() {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(records.sorted { $0.createdAt < $1.createdAt })
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            print("[IOSBoardSignalRepository] persist failed: \(error.localizedDescription)")
        }
    }

    private static func loadRecords(from fileURL: URL, decoder: JSONDecoder) -> [IOSBoardSignalRecord] {
        guard FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              let decoded = try? decoder.decode([IOSBoardSignalRecord].self, from: data) else {
            return []
        }
        return decoded
    }

    nonisolated static func currentEpochMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }

    nonisolated static func contentHash(sourceType: String, title: String, content: String) -> String {
        let normalized = "\(sourceType)|\(title.lowercased().trimmingCharacters(in: .whitespacesAndNewlines))|\(content.lowercased().trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))"
        let digest = SHA256.hash(data: Data(normalized.utf8))
        return digest.map { String(format: "%02x", $0) }.joined().prefix(32).description
    }

    private nonisolated static let dedupWindowMs: Int64 = 24 * 60 * 60 * 1_000
}

// MARK: - Aggregator

@MainActor
final class IOSBoardSignalAggregator {
    private let repository: IOSBoardSignalRepository
    private let collectors: [IOSBoardSignalCollector]
    private let nowProvider: () -> Int64

    init(
        repository: IOSBoardSignalRepository,
        collectors: [IOSBoardSignalCollector],
        nowProvider: @escaping () -> Int64 = { IOSBoardSignalRepository.currentEpochMs() }
    ) {
        self.repository = repository
        self.collectors = collectors
        self.nowProvider = nowProvider
    }

    func runOnce(
        limitPerCollector: Int = 50,
        agentLimit: Int = 80,
        enabledSources: Set<String>? = nil
    ) async -> IOSBoardRunOnceResult {
        let now = nowProvider()
        var statuses: [IOSBoardCollectorStatus] = []

        for collector in collectors {
            if let enabledSources, !enabledSources.contains(collector.sourceType) {
                statuses.append(.skipped(sourceType: collector.sourceType, reason: "未启用"))
                continue
            }

            let output = await collector.collect(limit: limitPerCollector)
            var ingested = 0
            var duplicates = 0
            var latestTitle: String?
            var latestSignalTime: Int64?

            for raw in output.signals.prefix(limitPerCollector) {
                switch repository.ingest(raw, now: now) {
                case .saved(let record):
                    ingested += 1
                    if latestSignalTime == nil || record.signalTime > (latestSignalTime ?? 0) {
                        latestTitle = record.title
                        latestSignalTime = record.signalTime
                    }
                case .duplicateSourceRef, .duplicateContentHash:
                    duplicates += 1
                }
            }

            statuses.append(IOSBoardCollectorStatus(
                sourceType: collector.sourceType,
                collectedCount: output.signals.count,
                ingestedCount: ingested,
                duplicateCount: duplicates,
                latestTitle: latestTitle ?? output.signals.max(by: { $0.signalTime < $1.signalTime })?.title,
                latestSignalTime: latestSignalTime ?? output.signals.max(by: { $0.signalTime < $1.signalTime })?.signalTime,
                statusMessage: output.statusMessage,
                errorMessage: output.errorMessage
            ))
        }

        let batch = filteredSignalBatch(limit: agentLimit)
        let pruneCutoff = now - 7 * 24 * 60 * 60 * 1_000
        let pruned = repository.pruneProcessedSignals(olderThanMs: pruneCutoff)
        let snapshot = IOSBoardCollectionSnapshot(
            statuses: statuses,
            recentSignals: repository.recentSignals(limit: 10),
            pendingCount: repository.countUnprocessedSignals(),
            lastRunAt: now,
            lastRunError: statuses.compactMap(\.errorMessage).first
        )
        return IOSBoardRunOnceResult(snapshot: snapshot, batch: batch, prunedCount: pruned)
    }

    func filteredSignalBatch(limit: Int = 80) -> IOSBoardSignalBatch {
        let unprocessed = repository.getUnprocessedSignals(limit: limit)
        guard !unprocessed.isEmpty else { return .empty }

        let scored = unprocessed.map { IOSScoredBoardSignal(record: $0, score: IOSBoardSignalScorer.score($0)) }
        let surfaced = scored
            .filter { IOSBoardSignalScorer.shouldSurface($0) }
            .sorted { $0.score > $1.score }
            .map(\.record)

        let agentRecords: [IOSBoardSignalRecord]
        if surfaced.isEmpty {
            agentRecords = unprocessed.filter { $0.sourceType == IOSBoardSignalSourceType.time }
        } else {
            agentRecords = Array(surfaced.prefix(limit))
        }

        return IOSBoardSignalBatch(
            agentRecords: agentRecords,
            consideredIds: agentRecords.map(\.id)
        )
    }
}

private struct IOSScoredBoardSignal {
    var record: IOSBoardSignalRecord
    var score: Int
}

private enum IOSBoardSignalScorer {
    static func score(_ signal: IOSBoardSignalRecord) -> Int {
        var score = 0
        let now = IOSBoardSignalRepository.currentEpochMs()

        switch signal.sourceType {
        case IOSBoardSignalSourceType.calendar:
            score += 5
            let minutesUntil = (signal.signalTime - now) / 60_000
            switch minutesUntil {
            case -30...30:
                score += 8
            case 31...120:
                score += 4
            case 121...360:
                score += 2
            default:
                break
            }
        case IOSBoardSignalSourceType.reminder:
            score += 5
            if signal.content.localizedCaseInsensitiveContains("逾期") {
                score += 5
            }
        case IOSBoardSignalSourceType.chatHistory:
            score += metadataInt(signal.metadataJson, key: "relevance").map { min(max($0, 0), 10) } ?? 0
        case IOSBoardSignalSourceType.hotlist:
            score += 2
            if let rank = metadataInt(signal.metadataJson, key: "rank"), rank <= 5 {
                score += 2
            }
        case IOSBoardSignalSourceType.feishuMessage:
            score += 4
        case IOSBoardSignalSourceType.feishuDocument:
            score += 3
        case IOSBoardSignalSourceType.time:
            score -= 3
        default:
            break
        }
        return score
    }

    static func shouldSurface(_ scored: IOSScoredBoardSignal) -> Bool {
        guard scored.score > -10 else { return false }
        switch scored.record.sourceType {
        case IOSBoardSignalSourceType.time:
            return false
        case IOSBoardSignalSourceType.chatHistory:
            return scored.score >= 4
        default:
            return true
        }
    }

    private static func metadataInt(_ json: String, key: String) -> Int? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let int = object[key] as? Int { return int }
        if let number = object[key] as? NSNumber { return number.intValue }
        if let string = object[key] as? String { return Int(string) }
        return nil
    }
}

// MARK: - Chat History Collector

struct IOSBoardConversationCandidate: Equatable, Sendable {
    var id: String
    var title: String
    var updateAt: Int64
    var nodeCount: Int
    var tailTexts: [String]
}

@MainActor
protocol IOSBoardConversationSignalSource: AnyObject {
    func boardSignalCandidates(limit: Int) async -> [IOSBoardConversationCandidate]
}

final class IOSChatHistorySignalCollector: IOSBoardSignalCollector {
    let sourceType = IOSBoardSignalSourceType.chatHistory

    private let source: IOSBoardConversationSignalSource
    private let freshnessWindowMs: Int64
    private let nowProvider: () -> Int64

    init(
        source: IOSBoardConversationSignalSource,
        freshnessWindowMs: Int64 = 36 * 60 * 60 * 1_000,
        nowProvider: @escaping () -> Int64 = { IOSBoardSignalRepository.currentEpochMs() }
    ) {
        self.source = source
        self.freshnessWindowMs = freshnessWindowMs
        self.nowProvider = nowProvider
    }

    func collect(limit: Int) async -> IOSBoardCollectorOutput {
        let now = nowProvider()
        let cutoff = now - freshnessWindowMs
        let candidates = await source.boardSignalCandidates(limit: max(limit * 3, limit))
        let scored = candidates
            .filter { $0.updateAt >= cutoff }
            .compactMap { candidate -> (IOSRawBoardSignal, Int)? in
                guard let score = IOSChatHistorySignalHeuristics.relevanceScore(
                    title: candidate.title,
                    tailTexts: candidate.tailTexts,
                    nodeCount: candidate.nodeCount
                ) else {
                    return nil
                }
                return (Self.signal(from: candidate, relevanceScore: score), score)
            }
            .sorted { $0.1 > $1.1 }
            .prefix(limit)
            .map(\.0)

        return IOSBoardCollectorOutput(
            signals: scored,
            statusMessage: scored.isEmpty ? "近期会话暂无可行动内容" : "读取近期会话"
        )
    }

    private static func signal(from candidate: IOSBoardConversationCandidate, relevanceScore: Int) -> IOSRawBoardSignal {
        let title = candidate.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let content = """
        对话深度：\(candidate.nodeCount)轮
        最近更新：\(IOSBoardDateFormatters.monthDayTime.string(from: Date(timeIntervalSince1970: TimeInterval(candidate.updateAt) / 1_000)))

        --- 最近对话内容 ---
        \(candidate.tailTexts.suffix(8).map { String($0.prefix(300)) }.joined(separator: "\n"))
        """
        return IOSRawBoardSignal(
            sourceType: IOSBoardSignalSourceType.chatHistory,
            sourceRef: candidate.id,
            title: title.isEmpty ? "未命名对话" : String(title.prefix(120)),
            content: String(content.prefix(2_000)),
            signalTime: candidate.updateAt,
            metadataJson: IOSBoardJSON.metadata([
                "conversation_id": candidate.id,
                "node_count": candidate.nodeCount,
                "relevance": relevanceScore
            ])
        )
    }
}

enum IOSChatHistorySignalHeuristics {
    static func relevanceScore(title: String, tailTexts: [String], nodeCount: Int) -> Int? {
        let fullText = "\(title) \(tailTexts.joined(separator: " "))"
        if lowValueTestMarkers.contains(where: { fullText.localizedCaseInsensitiveContains($0) }) {
            return nil
        }

        var score = 0
        for tier in keywordTiers {
            if tier.keywords.contains(where: { contains(fullText, keyword: $0) }) {
                score += tier.weight
            }
        }
        guard score >= 3 else { return nil }

        switch nodeCount {
        case 10...:
            score += 3
        case 5...:
            score += 2
        case 3...:
            score += 1
        default:
            if score < 5 { return nil }
        }
        return score > 0 ? score : nil
    }

    private static func contains(_ text: String, keyword: String) -> Bool {
        if keyword.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") }) {
            let pattern = "(?<![A-Za-z0-9_])\(NSRegularExpression.escapedPattern(for: keyword))(?![A-Za-z0-9_])"
            return text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) != nil
        }
        return text.localizedCaseInsensitiveContains(keyword)
    }

    private static let lowValueTestMarkers = [
        "乱码", "混合语言", "长文生成", "长文对话", "重复 prompt", "重复prompt",
        "测试 prompt", "测试prompt", "随便测试", "流式渲染长文"
    ]

    private static let keywordTiers: [(keywords: [String], weight: Int)] = [
        (
            [
                "TODO", "todo", "待办", "提醒", "deadline", "截止", "到期",
                "紧急", "urgent", "ASAP", "尽快", "follow up", "action item",
                "跟进", "决定", "决策", "bug", "修复", "上线", "发布", "部署"
            ],
            5
        ),
        (["计划", "方案", "会议", "面试", "必须", "接下来", "下一步"], 4),
        (["项目", "需求", "review", "PR", "合并", "反馈", "报告", "文档", "设计"], 2),
        (["预算", "合同", "客户"], 1)
    ]
}

// MARK: - EventKit Collectors

enum IOSEventKitAuthorization: String, Codable, Sendable {
    case notDetermined
    case restricted
    case denied
    case authorized
    case writeOnly
    case unavailable

    var canRead: Bool { self == .authorized }
}

struct IOSEventKitAdapterResult: Equatable, Sendable {
    var signals: [IOSRawBoardSignal]
    var statusMessage: String?
    var errorMessage: String?

    init(signals: [IOSRawBoardSignal] = [], statusMessage: String? = nil, errorMessage: String? = nil) {
        self.signals = signals
        self.statusMessage = statusMessage
        self.errorMessage = errorMessage
    }
}

protocol IOSEventKitSignalAdapter: Sendable {
    func calendarAuthorizationStatus() -> IOSEventKitAuthorization
    func reminderAuthorizationStatus() -> IOSEventKitAuthorization
    func calendarSignals(now: Date, lookBack: TimeInterval, lookAhead: TimeInterval, limit: Int) async -> IOSEventKitAdapterResult
    func reminderSignals(now: Date, lookAhead: TimeInterval, limit: Int) async -> IOSEventKitAdapterResult
}

final class IOSEventKitCalendarSignalCollector: IOSBoardSignalCollector {
    let sourceType = IOSBoardSignalSourceType.calendar

    private let adapter: IOSEventKitSignalAdapter
    private let lookBack: TimeInterval
    private let lookAhead: TimeInterval

    init(
        adapter: IOSEventKitSignalAdapter = IOSRealEventKitSignalAdapter(),
        lookBack: TimeInterval = 60 * 60,
        lookAhead: TimeInterval = 24 * 60 * 60
    ) {
        self.adapter = adapter
        self.lookBack = lookBack
        self.lookAhead = lookAhead
    }

    func collect(limit: Int) async -> IOSBoardCollectorOutput {
        let status = adapter.calendarAuthorizationStatus()
        guard status.canRead else {
            return IOSBoardCollectorOutput(
                statusMessage: "日历权限：\(status.rawValue)",
                errorMessage: status == .notDetermined ? "日历未授权，返回空状态" : "无法读取日历：\(status.rawValue)"
            )
        }
        let result = await adapter.calendarSignals(now: Date(), lookBack: lookBack, lookAhead: lookAhead, limit: limit)
        return IOSBoardCollectorOutput(
            signals: result.signals,
            statusMessage: result.statusMessage ?? "读取 EventKit 日历",
            errorMessage: result.errorMessage
        )
    }
}

final class IOSEventKitReminderSignalCollector: IOSBoardSignalCollector {
    let sourceType = IOSBoardSignalSourceType.reminder

    private let adapter: IOSEventKitSignalAdapter
    private let lookAhead: TimeInterval

    init(
        adapter: IOSEventKitSignalAdapter = IOSRealEventKitSignalAdapter(),
        lookAhead: TimeInterval = 24 * 60 * 60
    ) {
        self.adapter = adapter
        self.lookAhead = lookAhead
    }

    func collect(limit: Int) async -> IOSBoardCollectorOutput {
        let status = adapter.reminderAuthorizationStatus()
        guard status.canRead else {
            return IOSBoardCollectorOutput(
                statusMessage: "提醒事项权限：\(status.rawValue)",
                errorMessage: status == .notDetermined ? "提醒事项未授权，返回空状态" : "无法读取提醒事项：\(status.rawValue)"
            )
        }
        let result = await adapter.reminderSignals(now: Date(), lookAhead: lookAhead, limit: limit)
        return IOSBoardCollectorOutput(
            signals: result.signals,
            statusMessage: result.statusMessage ?? "读取 EventKit 提醒事项",
            errorMessage: result.errorMessage
        )
    }
}

struct IOSRealEventKitSignalAdapter: IOSEventKitSignalAdapter {
    func calendarAuthorizationStatus() -> IOSEventKitAuthorization {
        #if canImport(EventKit)
        Self.map(EKEventStore.authorizationStatus(for: .event))
        #else
        .unavailable
        #endif
    }

    func reminderAuthorizationStatus() -> IOSEventKitAuthorization {
        #if canImport(EventKit)
        Self.map(EKEventStore.authorizationStatus(for: .reminder))
        #else
        .unavailable
        #endif
    }

    func calendarSignals(
        now: Date,
        lookBack: TimeInterval,
        lookAhead: TimeInterval,
        limit: Int
    ) async -> IOSEventKitAdapterResult {
        #if canImport(EventKit)
        let store = EKEventStore()
        let from = now.addingTimeInterval(-lookBack)
        let to = now.addingTimeInterval(lookAhead)
        let predicate = store.predicateForEvents(withStart: from, end: to, calendars: nil)
        let events = store.events(matching: predicate)
            .sorted { $0.startDate < $1.startDate }
            .prefix(limit)

        let signals = events.compactMap { event -> IOSRawBoardSignal? in
            let title = event.title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { return nil }
            let startMs = Int64(event.startDate.timeIntervalSince1970 * 1_000)
            let endMs = Int64(event.endDate.timeIntervalSince1970 * 1_000)
            let id = event.eventIdentifier ?? event.calendarItemIdentifier
            let content = [
                "时间：\(IOSBoardDateFormatters.eventRange(start: event.startDate, end: event.endDate))",
                (event.location ?? "").isEmpty ? nil : "地点：\(event.location ?? "")",
                event.calendar.title.isEmpty ? nil : "日历：\(event.calendar.title)",
                event.notes?.isEmpty == false ? "说明：\(String((event.notes ?? "").prefix(800)))" : nil
            ].compactMap { $0 }.joined(separator: "\n")
            return IOSRawBoardSignal(
                sourceType: IOSBoardSignalSourceType.calendar,
                sourceRef: "\(id)@\(startMs)",
                title: String(title.prefix(120)),
                content: content,
                signalTime: startMs,
                metadataJson: IOSBoardJSON.metadata([
                    "event_id": id,
                    "begin": startMs,
                    "end": endMs
                ])
            )
        }
        return IOSEventKitAdapterResult(
            signals: signals,
            statusMessage: signals.isEmpty ? "日历窗口内暂无事件" : "读取 \(signals.count) 个日历事件"
        )
        #else
        return IOSEventKitAdapterResult(errorMessage: "EventKit 不可用")
        #endif
    }

    func reminderSignals(now: Date, lookAhead: TimeInterval, limit: Int) async -> IOSEventKitAdapterResult {
        #if canImport(EventKit)
        let store = EKEventStore()
        let to = now.addingTimeInterval(lookAhead)
        let predicate = store.predicateForIncompleteReminders(
            withDueDateStarting: now.addingTimeInterval(-60 * 60),
            ending: to,
            calendars: nil
        )
        let signals = await withCheckedContinuation { continuation in
            store.fetchReminders(matching: predicate) { reminders in
                let signals = (reminders ?? [])
                    .sorted {
                        (Self.reminderDate($0) ?? Date.distantFuture) < (Self.reminderDate($1) ?? Date.distantFuture)
                    }
                    .prefix(limit)
                    .compactMap { reminder -> IOSRawBoardSignal? in
                        let title = reminder.title.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !title.isEmpty else { return nil }
                        let dueDate = Self.reminderDate(reminder)
                        let dueMs = dueDate.map { Int64($0.timeIntervalSince1970 * 1_000) } ?? Int64(now.timeIntervalSince1970 * 1_000)
                        let overdue = dueDate.map { $0 < now } ?? false
                        let content = [
                            dueDate.map { "时间：\(IOSBoardDateFormatters.monthDayTime.string(from: $0))" },
                            overdue ? "状态：逾期未完成" : "状态：未完成",
                            reminder.notes?.isEmpty == false ? "说明：\(String((reminder.notes ?? "").prefix(800)))" : nil,
                            reminder.calendar.title.isEmpty ? nil : "列表：\(reminder.calendar.title)"
                        ].compactMap { $0 }.joined(separator: "\n")
                        return IOSRawBoardSignal(
                            sourceType: IOSBoardSignalSourceType.reminder,
                            sourceRef: reminder.calendarItemIdentifier,
                            title: String(title.prefix(120)),
                            content: content,
                            signalTime: dueMs,
                            metadataJson: IOSBoardJSON.metadata([
                                "reminder_id": reminder.calendarItemIdentifier,
                                "due": dueMs,
                                "priority": reminder.priority,
                                "overdue": overdue
                            ])
                        )
                    }
                continuation.resume(returning: signals)
            }
        }
        return IOSEventKitAdapterResult(
            signals: signals,
            statusMessage: signals.isEmpty ? "24 小时内暂无提醒事项" : "读取 \(signals.count) 个提醒事项"
        )
        #else
        return IOSEventKitAdapterResult(errorMessage: "EventKit 不可用")
        #endif
    }

    #if canImport(EventKit)
    private static func map(_ status: EKAuthorizationStatus) -> IOSEventKitAuthorization {
        switch status {
        case .notDetermined:
            return .notDetermined
        case .restricted:
            return .restricted
        case .denied:
            return .denied
        case .authorized, .fullAccess:
            return .authorized
        case .writeOnly:
            return .writeOnly
        @unknown default:
            return .unavailable
        }
    }

    private static func reminderDate(_ reminder: EKReminder) -> Date? {
        guard let components = reminder.dueDateComponents else { return nil }
        return Calendar.current.date(from: components)
    }
    #endif
}

// MARK: - Hotlist Collector

struct IOSHotlistItem: Codable, Equatable, Sendable {
    var providerId: String
    var title: String
    var url: String?
    var rank: Int
    var score: Int?
    var fetchedAt: Int64
    var displayTitle: String? = nil
    var heat: String? = nil
    var category: String? = nil

    var presentationTitle: String {
        (displayTitle ?? "").trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty(title)
    }
}

protocol IOSHotlistProvider: Sendable {
    var providerId: String { get }
    var displayName: String { get }
    func fetch(limit: Int) async throws -> [IOSHotlistItem]
}

final class IOSHotlistSignalCollector: IOSBoardSignalCollector {
    let sourceType = IOSBoardSignalSourceType.hotlist

    private let provider: IOSHotlistProvider

    init(provider: IOSHotlistProvider = IOSHackerNewsHotlistProvider()) {
        self.provider = provider
    }

    func collect(limit: Int) async -> IOSBoardCollectorOutput {
        do {
            let items = try await provider.fetch(limit: min(limit, 10))
            let signals = items.map { item in
                IOSRawBoardSignal(
                    sourceType: IOSBoardSignalSourceType.hotlist,
                    sourceRef: "\(item.providerId):\(item.url ?? item.title)",
                    title: item.title,
                    content: [
                        "来源：\(provider.displayName)",
                        "排名：\(item.rank)",
                        item.score.map { "热度：\($0)" },
                        item.url.map { "链接：\($0)" }
                    ].compactMap { $0 }.joined(separator: "\n"),
                    signalTime: item.fetchedAt,
                    metadataJson: IOSBoardJSON.metadata([
                        "provider": item.providerId,
                        "rank": item.rank,
                        "score": item.score ?? 0,
                        "url": item.url ?? ""
                    ])
                )
            }
            return IOSBoardCollectorOutput(
                signals: signals,
                statusMessage: signals.isEmpty ? "\(provider.displayName) 暂无热榜数据" : "读取 \(provider.displayName)"
            )
        } catch {
            return IOSBoardCollectorOutput(
                statusMessage: "\(provider.displayName) 拉取失败",
                errorMessage: "热榜采集失败：\(error.localizedDescription)"
            )
        }
    }
}

struct IOSHackerNewsHotlistProvider: IOSHotlistProvider {
    let providerId = "hacker_news"
    let displayName = "Hacker News"

    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        let session = URLSession(configuration: {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 8
            configuration.timeoutIntervalForResource = 12
            return configuration
        }())
        let decoder = JSONDecoder()
        let (topData, _) = try await session.data(from: URL(string: "https://hacker-news.firebaseio.com/v0/topstories.json")!)
        let ids = try decoder.decode([Int].self, from: topData)
        let now = IOSBoardSignalRepository.currentEpochMs()
        var items: [IOSHotlistItem] = []

        for (index, id) in ids.prefix(max(limit, 0)).enumerated() {
            let url = URL(string: "https://hacker-news.firebaseio.com/v0/item/\(id).json")!
            let (data, _) = try await session.data(from: url)
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (object["deleted"] as? Bool) != true,
                  (object["dead"] as? Bool) != true,
                  let title = object["title"] as? String,
                  !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                continue
            }
            items.append(IOSHotlistItem(
                providerId: providerId,
                title: String(title.prefix(160)),
                url: object["url"] as? String,
                rank: index + 1,
                score: object["score"] as? Int,
                fetchedAt: now
            ))
        }
        return items
    }
}

// MARK: - Additional hotlist providers (Android BuiltInHotListProviders parity)
//
// Android ships 9 built-in hotlist providers; iOS only had HackerNews. These
// add the providers with stable public endpoints (RSS/JSON/HTML). Providers
// that need login or have aggressive anti-scraping (Weibo, Bilibili) are
// omitted honestly rather than faked.

/// Parses RSS/Atom <item><title>/<link> entries into hotlist items. Shared by
/// the ArxivAI / InfoqAI / 36Kr RSS providers.
struct IOSRSSHotlistProvider: IOSHotlistProvider {
    let providerId: String
    let displayName: String
    let feedURL: String

    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        let session = Self.ephemeralSession
        let (data, _) = try await session.data(from: URL(string: feedURL)!)
        let xml = String(data: data, encoding: .utf8) ?? ""
        let now = IOSBoardSignalRepository.currentEpochMs()
        // Naive RSS <item> extraction (sufficient for feed titles/links).
        let itemPattern = #"<item[^>]*>([\s\S]*?)</item>"#
        guard let regex = try? NSRegularExpression(pattern: itemPattern, options: []) else { return [] }
        let matches = regex.matches(in: xml, range: NSRange(xml.startIndex..., in: xml))
        var items: [IOSHotlistItem] = []
        for (index, match) in matches.prefix(max(limit, 0)).enumerated() {
            guard match.numberOfRanges >= 2,
                  let range = Range(match.range(at: 1), in: xml) else { continue }
            let block = String(xml[range])
            let title = Self.firstTag(in: block, tag: "title").trimmingCharacters(in: .whitespacesAndNewlines)
            let link = Self.firstTag(in: block, tag: "link").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { continue }
            items.append(IOSHotlistItem(
                providerId: providerId,
                title: String(title.prefix(160)),
                url: link.isEmpty ? nil : link,
                rank: index + 1,
                score: nil,
                fetchedAt: now
            ))
        }
        return items
    }

    private static func firstTag(in block: String, tag: String) -> String {
        let pattern = "<\(tag)[^>]*><!\\[CDATA\\[([\\s\\S]*?)\\]\\]></\(tag)>|<\(tag)[^>]*>([\\s\\S]*?)</\(tag)>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else { return "" }
        guard let match = regex.firstMatch(in: block, range: NSRange(block.startIndex..., in: block)),
              match.numberOfRanges >= 4 else { return "" }
        // Group 2 = CDATA content, group 3 = plain content.
        if let r = Range(match.range(at: 2), in: block), !r.isEmpty {
            return String(block[r])
        }
        if let r = Range(match.range(at: 3), in: block) {
            return String(block[r])
        }
        return ""
    }

    static let ephemeralSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 12
        return URLSession(configuration: config)
    }()
}

/// Arxiv AI/CL/LG/RO RSS feed.
struct IOSArxivAIHotlistProvider: IOSHotlistProvider {
    let providerId = "arxiv_ai"
    let displayName = "Arxiv AI"
    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        // Merge the AI-related arxiv RSS feeds Android uses.
        let urls = ["https://rss.arxiv.org/rss/cs.AI", "https://rss.arxiv.org/rss/cs.CL"]
        var all: [IOSHotlistItem] = []
        for url in urls {
            let provider = IOSRSSHotlistProvider(providerId: providerId, displayName: displayName, feedURL: url)
            all.append(contentsOf: try await provider.fetch(limit: limit))
        }
        return Array(all.prefix(limit))
    }
}

/// InfoqAI RSS feed.
struct IOSInfoqAIHotlistProvider: IOSHotlistProvider {
    let providerId = "infoq_ai"
    let displayName = "InfoQ AI"
    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        try await IOSRSSHotlistProvider(
            providerId: providerId,
            displayName: displayName,
            feedURL: "https://www.infoq.com/artificial_intelligence/rss/"
        ).fetch(limit: limit)
    }
}

/// HuggingFace daily papers (JSON API).
struct IOSHuggingFacePapersHotlistProvider: IOSHotlistProvider {
    let providerId = "huggingface_papers"
    let displayName = "HuggingFace Papers"
    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        let session = IOSRSSHotlistProvider.ephemeralSession
        let (data, _) = try await session.data(from: URL(string: "https://huggingface.co/api/daily_papers")!)
        guard let array = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        let now = IOSBoardSignalRepository.currentEpochMs()
        return array.prefix(max(limit, 0)).enumerated().map { index, entry in
            let paper = entry["paper"] as? [String: Any]
            let title = (paper?["title"] as? String) ?? ""
            let paperId = (paper?["id"] as? String) ?? ""
            return IOSHotlistItem(
                providerId: providerId,
                title: String(title.prefix(160)),
                url: paperId.isEmpty ? nil : "https://huggingface.co/papers/\(paperId)",
                rank: index + 1,
                score: (entry["upvotes"] as? Int),
                fetchedAt: now
            )
        }
    }
}

/// Github trending (HTML scrape — GitHub provides no JSON API for trending).
struct IOSGithubTrendingHotlistProvider: IOSHotlistProvider {
    let providerId = "github_trending_ai"
    let displayName = "GitHub AI"
    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        let session = IOSRSSHotlistProvider.ephemeralSession
        let (data, _) = try await session.data(from: URL(string: "https://github.com/trending")!)
        let html = String(data: data, encoding: .utf8) ?? ""
        let now = IOSBoardSignalRepository.currentEpochMs()
        // Extract repo paths from <h2 class="..."><a href="/owner/repo">.
        let pattern = #"<h2[^>]*>\s*<a[^>]*href="(/[^"]+)"[^>]*>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else { return [] }
        let matches = regex.matches(in: html, range: NSRange(html.startIndex..., in: html))
        var items: [IOSHotlistItem] = []
        for (index, match) in matches.prefix(max(limit, 0)).enumerated() {
            guard let r = Range(match.range(at: 1), in: html) else { continue }
            let path = String(html[r])
            let name = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !name.isEmpty else { continue }
            items.append(IOSHotlistItem(
                providerId: providerId,
                title: String(name.prefix(160)),
                url: "https://github.com\(path)",
                rank: index + 1,
                score: nil,
                fetchedAt: now
            ))
        }
        return items
    }
}

/// NewsNow 聚合器预设源(Android NewsNowPresets 对齐)。NewsNow 代理了一批中文热榜,
/// 标题本身就是中文、且比直连源(如 36kr.com/feed 常被 UA 拦)更稳。按分类组织,便于在
/// 来源设置里"分类添加"。
struct IOSNewsNowPreset: Identifiable, Equatable {
    let newsNowId: String   // e.g. "zhihu"
    let displayName: String // e.g. "知乎热榜"
    let category: String    // e.g. "社交热搜"

    var id: String { providerId }
    var providerId: String { "newsnow:\(newsNowId)" }

    static let all: [IOSNewsNowPreset] = [
        .init(newsNowId: "zhihu", displayName: "知乎热榜", category: "社交热搜"),
        .init(newsNowId: "weibo", displayName: "微博热搜", category: "社交热搜"),
        .init(newsNowId: "douyin", displayName: "抖音热搜", category: "社交热搜"),
        .init(newsNowId: "bilibili-hot-search", displayName: "B 站热搜", category: "科技数码"),
        .init(newsNowId: "ithome", displayName: "IT 之家", category: "科技数码"),
        .init(newsNowId: "sspai", displayName: "少数派", category: "科技数码"),
        .init(newsNowId: "juejin", displayName: "掘金", category: "科技数码"),
        .init(newsNowId: "36kr-quick", displayName: "36 氪快讯", category: "科技数码"),
        .init(newsNowId: "coolapk", displayName: "酷安", category: "科技数码"),
        .init(newsNowId: "v2ex-share", displayName: "V2EX 分享", category: "科技数码"),
        .init(newsNowId: "github-trending-today", displayName: "GitHub 趋势", category: "科技数码"),
        .init(newsNowId: "xueqiu-hotstock", displayName: "雪球热股", category: "财经"),
        .init(newsNowId: "wallstreetcn-hot", displayName: "华尔街见闻", category: "财经"),
        .init(newsNowId: "cls-telegraph", displayName: "财联社电报", category: "财经"),
        .init(newsNowId: "hupu-zhugandaoretie", displayName: "虎扑步行街", category: "体育"),
    ]
}

/// 单个 NewsNow 源 provider。命中 `https://newsnow.busiyi.world/api/s?id=<id>&latest`,
/// 解析 `{items:[{title,url,extra:{info:热度}}]}`(Android FIELD_MAPPING_JSON 对齐)。
struct IOSNewsNowHotlistProvider: IOSHotlistProvider {
    let providerId: String
    let displayName: String
    let newsNowId: String

    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        let session = IOSRSSHotlistProvider.ephemeralSession
        var request = URLRequest(url: URL(string: "https://newsnow.busiyi.world/api/s?id=\(newsNowId)")!)
        request.setValue(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1",
            forHTTPHeaderField: "User-Agent"
        )
        request.setValue("zh-CN,zh;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, _) = try await session.data(for: request)
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = root["items"] as? [[String: Any]] else {
            return []
        }
        let now = IOSBoardSignalRepository.currentEpochMs()
        return items.prefix(max(limit, 0)).enumerated().compactMap { index, item in
            guard let rawTitle = item["title"] as? String else { return nil }
            let title = rawTitle.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { return nil }
            let url = (item["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            let heat = (item["extra"] as? [String: Any])?["info"] as? String
            return IOSHotlistItem(
                providerId: providerId,
                title: String(title.prefix(160)),
                url: (url?.isEmpty == false) ? url : nil,
                rank: index + 1,
                score: nil,
                fetchedAt: now,
                heat: heat
            )
        }
    }
}

/// All built-in iOS hotlist providers (Android BuiltInHotListProviders parity
/// for the providers with stable public endpoints).
enum IOSHotlistProviders {
    struct Descriptor: Identifiable, Codable, Equatable, Sendable {
        var id: String { providerId }
        var providerId: String
        var displayName: String
    }

    // NewsNow 源(中文热榜,标题本身中文)。直连 36kr.com/feed 常被 UA 拦返回空,故用
    // NewsNow 的 36kr-quick 取代。
    static let newsNow: [IOSHotlistProvider] = IOSNewsNowPreset.all.map {
        IOSNewsNowHotlistProvider(providerId: $0.providerId, displayName: $0.displayName, newsNowId: $0.newsNowId)
    }

    static let all: [IOSHotlistProvider] = [
        IOSHackerNewsHotlistProvider(),
        IOSArxivAIHotlistProvider(),
        IOSInfoqAIHotlistProvider(),
        IOSHuggingFacePapersHotlistProvider(),
        IOSGithubTrendingHotlistProvider()
    ] + newsNow

    static var descriptors: [Descriptor] {
        all.map { Descriptor(providerId: $0.providerId, displayName: $0.displayName) }
    }

    /// 分类 → provider id。内置英文源归「AI · 英文源」,NewsNow 源按各自分类。
    static func category(for providerId: String) -> String {
        if let preset = IOSNewsNowPreset.all.first(where: { $0.providerId == providerId }) {
            return preset.category
        }
        return "AI · 英文源"
    }

    static let categoryOrder: [String] = ["AI · 英文源", "社交热搜", "科技数码", "财经", "体育"]

    /// 描述符按分类分组(供来源设置"分类添加"展示),保持 categoryOrder 顺序。
    static func descriptorsByCategory() -> [(category: String, items: [Descriptor])] {
        let grouped = Dictionary(grouping: descriptors) { category(for: $0.providerId) }
        return categoryOrder.compactMap { cat in
            guard let items = grouped[cat], !items.isEmpty else { return nil }
            return (cat, items)
        }
    }

    // 新装/未自定义用户的默认开启集(英文 + 几个常用中文源)。不开全部,避免一次刷新拉一堆源。
    // 注:NewsNow 的 36kr 上游目前常空,故默认用 IT 之家(稳定),36 氪仍保留为可选源。
    static let iOSDefaultProviderIds: Set<String> = [
        "hacker_news",
        "arxiv_ai",
        "infoq_ai",
        "36kr",
        "huggingface_papers",
        "github_trending_ai",
        "newsnow:zhihu",
        "newsnow:weibo",
        "newsnow:ithome",
        "newsnow:bilibili-hot-search",
    ]
    static let androidDefaultProviderIds: Set<String> = ["bilibili", "hacker_news"]
    static let supportedProviderIds: Set<String> = Set(all.map(\.providerId))

    static func provider(id: String) -> IOSHotlistProvider? {
        all.first { $0.providerId == id }
    }

    static func displayName(for providerId: String) -> String {
        provider(id: providerId)?.displayName ?? providerId
    }

    static func effectiveEnabledProviderIds(setting: TodayBoardSetting) -> Set<String> {
        let raw = Set(setting.hotListEnabledSources.map {
            String(describing: $0).trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty })
        if raw.isEmpty {
            return []
        }
        if raw == androidDefaultProviderIds {
            return iOSDefaultProviderIds
        }
        return raw.intersection(supportedProviderIds)
    }
}

struct IOSHotTopicSource: Codable, Equatable, Identifiable, Sendable {
    var id: String { "\(providerId)|\(rank)|\(title)" }
    var providerId: String
    var providerName: String
    var rank: Int
    var title: String
    var displayTitle: String?
    var url: String?
    var heat: String?
    var fetchedAt: Int64

    var presentationTitle: String {
        (displayTitle ?? "").trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty(title)
    }
}

struct IOSHotTopic: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var sources: [IOSHotTopicSource]
    var sourceCount: Int
    var bestRank: Int
    var latestFetchedAt: Int64
}

struct IOSHotListProviderSnapshot: Codable, Equatable, Identifiable, Sendable {
    var id: String { providerId }
    var providerId: String
    var providerName: String
    var items: [IOSHotlistItem]
    var fetchedAt: Int64
    var stale: Bool
    var error: String?

    init(
        providerId: String,
        providerName: String,
        items: [IOSHotlistItem],
        fetchedAt: Int64,
        stale: Bool = false,
        error: String? = nil
    ) {
        self.providerId = providerId
        self.providerName = providerName
        self.items = items
        self.fetchedAt = fetchedAt
        self.stale = stale
        self.error = error
    }
}

struct IOSHotListDashboard: Codable, Equatable, Sendable {
    var topics: [IOSHotTopic]
    var providers: [IOSHotListProviderSnapshot]
    var lastUpdatedAt: Int64
    var enabledSourceCount: Int

    static let empty = IOSHotListDashboard(topics: [], providers: [], lastUpdatedAt: 0, enabledSourceCount: 0)

    var hasContent: Bool {
        !topics.isEmpty || providers.contains { !$0.items.isEmpty }
    }

    var hasEnabledSources: Bool {
        enabledSourceCount > 0
    }

    var hasErrors: Bool {
        providers.contains { ($0.error ?? "").isEmpty == false }
    }
}

enum IOSHotListAggregator {
    static func aggregate(providerSnapshots: [IOSHotListProviderSnapshot], limit: Int = 20) -> [IOSHotTopic] {
        var clusters: [[IOSHotTopicSource]] = []
        for snapshot in providerSnapshots {
            for item in snapshot.items {
                let source = IOSHotTopicSource(
                    providerId: snapshot.providerId,
                    providerName: snapshot.providerName,
                    rank: item.rank,
                    title: item.title,
                    displayTitle: item.displayTitle,
                    url: item.url,
                    heat: item.heat ?? item.score.map(String.init),
                    fetchedAt: item.fetchedAt
                )
                if let index = clusters.firstIndex(where: { cluster in
                    cluster.contains { matches(source, $0) }
                }) {
                    clusters[index].append(source)
                } else {
                    clusters.append([source])
                }
            }
        }

        return clusters
            .map(makeTopic(sources:))
            .sorted {
                if $0.sourceCount != $1.sourceCount { return $0.sourceCount > $1.sourceCount }
                if $0.bestRank != $1.bestRank { return $0.bestRank < $1.bestRank }
                return $0.latestFetchedAt > $1.latestFetchedAt
            }
            .prefix(max(limit, 0))
            .map { $0 }
    }

    static func applyInterestFilter(
        dashboard: IOSHotListDashboard,
        keywords rawKeywords: [String],
        modeWireName: String
    ) -> IOSHotListDashboard {
        let keywords = normalizeKeywords(rawKeywords)
        guard !keywords.isEmpty, modeWireName != "all" else { return dashboard }

        let topicPairs = dashboard.topics.map { topic in (topic, hotTopicMatches(topic, keywords: keywords)) }
        let providerSnapshots = dashboard.providers.map { provider in
            let indexedItems = provider.items.map { item in (item, hotItemMatches(item, keywords: keywords)) }
            let items: [IOSHotlistItem]
            if modeWireName == "focus_only" {
                items = indexedItems.filter(\.1).map(\.0)
            } else {
                items = indexedItems.sorted { left, right in
                    if left.1 != right.1 { return left.1 && !right.1 }
                    return left.0.rank < right.0.rank
                }.map(\.0)
            }
            return IOSHotListProviderSnapshot(
                providerId: provider.providerId,
                providerName: provider.providerName,
                items: items,
                fetchedAt: provider.fetchedAt,
                stale: provider.stale,
                error: modeWireName == "focus_only" && items.isEmpty && (provider.error ?? "").isEmpty ? "没有匹配关注关键词的内容。" : provider.error
            )
        }

        let topics: [IOSHotTopic]
        if modeWireName == "focus_only" {
            topics = topicPairs.filter(\.1).map(\.0)
        } else {
            topics = topicPairs.sorted { left, right in
                if left.1 != right.1 { return left.1 && !right.1 }
                if left.0.sourceCount != right.0.sourceCount { return left.0.sourceCount > right.0.sourceCount }
                if left.0.bestRank != right.0.bestRank { return left.0.bestRank < right.0.bestRank }
                return left.0.latestFetchedAt > right.0.latestFetchedAt
            }.map(\.0)
        }
        return IOSHotListDashboard(
            topics: topics,
            providers: providerSnapshots,
            lastUpdatedAt: dashboard.lastUpdatedAt,
            enabledSourceCount: dashboard.enabledSourceCount
        )
    }

    static func normalizeKeywords(_ raw: [String]) -> [String] {
        var seen = Set<String>()
        return raw
            .flatMap { $0.components(separatedBy: CharacterSet(charactersIn: ",，、;；\n\t")) }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .filter { seen.insert($0.lowercased()).inserted }
            .prefix(80)
            .map { $0 }
    }

    private static func makeTopic(sources: [IOSHotTopicSource]) -> IOSHotTopic {
        let sortedSources = sources.sorted {
            if $0.rank != $1.rank { return $0.rank < $1.rank }
            return $0.fetchedAt > $1.fetchedAt
        }
        let title = sortedSources.first?.presentationTitle ?? "热点"
        let sourceCount = Set(sortedSources.map(\.providerId)).count
        let bestRank = sortedSources.map(\.rank).min() ?? Int.max
        let latest = sortedSources.map(\.fetchedAt).max() ?? 0
        return IOSHotTopic(
            id: topicId(title: title, sources: sortedSources),
            title: title,
            sources: sortedSources,
            sourceCount: sourceCount,
            bestRank: bestRank,
            latestFetchedAt: latest
        )
    }

    private static func matches(_ left: IOSHotTopicSource, _ right: IOSHotTopicSource) -> Bool {
        let leftTitle = normalizedTitle(left.presentationTitle)
        let rightTitle = normalizedTitle(right.presentationTitle)
        guard !leftTitle.isEmpty, !rightTitle.isEmpty else { return false }
        if leftTitle == rightTitle { return true }

        let sharedEntities = extractEntities(leftTitle).intersection(extractEntities(rightTitle))
        if sharedEntities.count >= 2 { return true }
        if sharedEntities.count == 1 && leftTitle == rightTitle { return true }
        guard sameLanguageFamily(leftTitle, rightTitle) else { return false }
        let minLength = min(leftTitle.count, rightTitle.count)
        return minLength >= 6 && bigramJaccard(leftTitle, rightTitle) >= 0.4
    }

    private static func hotTopicMatches(_ topic: IOSHotTopic, keywords: [String]) -> Bool {
        let haystack = ([topic.title] + topic.sources.flatMap { [$0.presentationTitle, $0.providerName, $0.heat ?? ""] })
            .joined(separator: " ")
        return keywords.contains { containsKeyword($0, in: haystack) }
    }

    private static func hotItemMatches(_ item: IOSHotlistItem, keywords: [String]) -> Bool {
        let haystack = [item.presentationTitle, item.category ?? "", item.heat ?? "", item.url ?? ""]
            .joined(separator: " ")
        return keywords.contains { containsKeyword($0, in: haystack) }
    }

    private static func containsKeyword(_ keyword: String, in text: String) -> Bool {
        let key = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return false }
        let lowerText = text.lowercased()
        let lowerKey = key.lowercased()
        if lowerKey.count <= 3,
           lowerKey.range(of: #"^[a-z0-9+\-]+$"#, options: .regularExpression) != nil {
            let pattern = #"(?<![a-z0-9+\-])\#(NSRegularExpression.escapedPattern(for: lowerKey))(?![a-z0-9+\-])"#
            return lowerText.range(of: pattern, options: .regularExpression) != nil
        }
        return lowerText.contains(lowerKey)
    }

    private static func normalizedTitle(_ value: String) -> String {
        value
            .lowercased()
            .replacingOccurrences(of: #"https?://\S+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"[^\p{L}\p{N}\+]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func extractEntities(_ normalized: String) -> Set<String> {
        let aliases: [String: [String]] = [
            "openai": ["openai", "chatgpt", "gpt"],
            "anthropic": ["anthropic", "claude"],
            "deepseek": ["deepseek", "深度求索"],
            "gemini": ["gemini", "google ai"],
            "ai": ["ai", "人工智能", "大模型", "llm", "agent"],
            "robotics": ["机器人", "具身智能", "robot"],
            "chip": ["芯片", "半导体", "gpu", "nvidia"],
            "apple": ["apple", "苹果"],
            "microsoft": ["microsoft", "微软"],
            "google": ["google", "谷歌"],
            "tesla": ["tesla", "特斯拉"],
            "xiaomi": ["xiaomi", "小米"],
            "huawei": ["huawei", "华为"],
            "github": ["github", "git hub"]
        ]
        var found = Set<String>()
        for (key, values) in aliases where values.contains(where: { normalized.contains($0) }) {
            found.insert(key)
        }
        let words = normalized.split(separator: " ").map(String.init)
        for word in words where word.count >= 4 && word.range(of: #"^[a-z][a-z0-9+\-]+$"#, options: .regularExpression) != nil {
            found.insert(word)
        }
        return found
    }

    private static func sameLanguageFamily(_ left: String, _ right: String) -> Bool {
        containsCJK(left) == containsCJK(right)
    }

    private static func containsCJK(_ value: String) -> Bool {
        value.unicodeScalars.contains { scalar in
            (0x4E00...0x9FFF).contains(Int(scalar.value))
        }
    }

    private static func bigramJaccard(_ left: String, _ right: String) -> Double {
        let leftSet = bigrams(left)
        let rightSet = bigrams(right)
        guard !leftSet.isEmpty, !rightSet.isEmpty else { return 0 }
        let intersection = leftSet.intersection(rightSet).count
        let union = leftSet.union(rightSet).count
        return union == 0 ? 0 : Double(intersection) / Double(union)
    }

    private static func bigrams(_ value: String) -> Set<String> {
        let chars = Array(value)
        guard chars.count >= 2 else { return [] }
        return Set((0..<(chars.count - 1)).map { String(chars[$0]) + String(chars[$0 + 1]) })
    }

    private static func topicId(title: String, sources: [IOSHotTopicSource]) -> String {
        let material = ([normalizedTitle(title)] + sources.map { "\($0.providerId):\($0.rank):\(normalizedTitle($0.presentationTitle))" })
            .joined(separator: "|")
        let digest = SHA256.hash(data: Data(material.utf8))
        return digest.compactMap { String(format: "%02x", $0) }.joined().prefixString(24)
    }
}

/// A batched title-translation step the dashboard store can run on fetch. Takes
/// the raw titles, returns a `[originalTitle: chineseTitle]` map for those it
/// translated (others absent → caller keeps the original). Injected by the view
/// layer so the persistence store stays LLM-free and unit-testable.
typealias IOSHotListTitleTranslate = (_ titles: [String]) async -> [String: String]

/// Translates non-Chinese hot-list / ranking titles into fluent Simplified
/// Chinese on fetch (technical terms preserved), gated by the board's
/// `hotListTranslateToChinese` toggle. One batched LLM call per refresh keeps
/// cost bounded; already-Chinese titles are skipped, and any failure degrades
/// silently to the raw title (no fabricated translation).
enum IOSHotListTitleTranslator {
    /// A title needs translation only when it contains NO CJK ideograph. Mixed
    /// titles like "OpenAI 发布 GPT-5" already read in Chinese and are left as-is;
    /// only zero-ideograph titles ("Apple unveils new chip") are translated.
    static func needsTranslation(_ title: String) -> Bool {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else { return false }
        for scalar in trimmed.unicodeScalars {
            if (0x4E00...0x9FFF).contains(scalar.value)      // CJK Unified Ideographs
                || (0x3400...0x4DBF).contains(scalar.value)  // CJK Ext-A
                || (0xF900...0xFAFF).contains(scalar.value) { // CJK Compatibility
                return false
            }
        }
        return true
    }

    /// Returns a `[originalTitle: chineseTitle]` map for the titles that were
    /// translated. Already-Chinese titles and any the model omits are absent.
    /// Never throws — an LLM failure yields an empty map (honest degradation).
    static func translate(
        titles: [String],
        providerSetting: ProviderSetting,
        modelId: String,
        provider: IOSAgentTextProvider = OpenAIKmpProviderAdapter()
    ) async -> [String: String] {
        let pending = Array(Set(titles.filter(needsTranslation))).prefix(60).map { $0 }
#if DEBUG
        NSLog("[AmberTranslate] translate() in=\(titles.count) pending=\(pending.count) model=\(modelId)")
#endif
        guard !pending.isEmpty else { return [:] }
        let numbered = pending.enumerated()
            .map { "\($0.offset + 1). \($0.element)" }
            .joined(separator: "\n")
        let system = "你是专业的中文科技编辑。把给定的非中文榜单标题翻译成通顺、简洁的简体中文标题；专有名词、产品名、公司名、技术术语（如 OpenAI、GPT-5、Kubernetes）保留原文，不要音译。只输出 JSON。"
        let userPrompt = """
        把下面每条标题翻译成简体中文。保持序号一一对应，不要增删条目。
        输出严格 JSON 对象：{"items":[{"i":序号,"zh":"中文标题"}]}，不要代码围栏、不要解释。

        \(numbered)
        """
        let messages = [
            UIMessage.companion.system(prompt: system),
            UIMessage.companion.user(prompt: userPrompt)
        ]
        let params = TextGenerationParams(
            model: Model(modelId: modelId, displayName: modelId, id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.2),
            topP: nil,
            maxTokens: KotlinInt(value: 4_000),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let text: String
        do {
            let chunk = try await provider.generateText(providerSetting: providerSetting, messages: messages, params: params)
            text = (chunk.choices.first?.message?.parts ?? [])
                .compactMap { $0 as? UIMessagePart.Text }
                .map { $0.text }
                .joined(separator: "")
        } catch {
#if DEBUG
            NSLog("[AmberTranslate] generateText threw: \(error)")
#endif
            return [:]
        }
        let result = parse(text, pending: pending)
#if DEBUG
        NSLog("[AmberTranslate] llm chars=\(text.count) parsed=\(result.count) head=\(text.prefix(120))")
#endif
        return result
    }

    /// Parses `{"items":[{"i":N,"zh":"..."}]}` into `[original: translation]`,
    /// mapping the 1-based index back to `pending`. Tolerates ```json fences and
    /// surrounding prose (reuses the deep-read JSON-object extractor).
    static func parse(_ text: String, pending: [String]) -> [String: String] {
        guard let json = IOSDeepReadDraftGenerator.extractJSONObject(text),
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = obj["items"] as? [[String: Any]] else {
            return [:]
        }
        var result: [String: String] = [:]
        for entry in items {
            guard let zhRaw = entry["zh"] as? String else { continue }
            let zh = zhRaw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !zh.isEmpty else { continue }
            let idx: Int?
            if let i = entry["i"] as? Int { idx = i }
            else if let s = entry["i"] as? String { idx = Int(s) }
            else { idx = nil }
            guard let i = idx, i >= 1, i <= pending.count else { continue }
            result[pending[i - 1]] = zh
        }
        return result
    }
}

@MainActor
@Observable
final class IOSHotListDashboardStore {
    static let shared = IOSHotListDashboardStore()

    private(set) var dashboard: IOSHotListDashboard
    private(set) var isRefreshing = false
    private(set) var lastError: String?

    private let directory: URL
    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let root = baseDirectory
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = root.appendingPathComponent("deep_read", isDirectory: true)
        fileURL = directory.appendingPathComponent("hotlist_dashboard.json", isDirectory: false)
        dashboard = Self.load(from: fileURL, decoder: decoder, fileManager: fileManager) ?? .empty
    }

    func refresh(
        setting: TodayBoardSetting,
        force: Bool = true,
        limit: Int = 20,
        translate: IOSHotListTitleTranslate? = nil
    ) async {
        guard !isRefreshing else { return }
        let enabledIds = IOSHotlistProviders.effectiveEnabledProviderIds(setting: setting)
        guard !enabledIds.isEmpty else {
            dashboard = IOSHotListDashboard(topics: [], providers: [], lastUpdatedAt: 0, enabledSourceCount: 0)
            lastError = nil
            persist()
            return
        }
        if !force, !shouldRefresh(setting: setting) {
            // Fresh enough to skip a re-fetch, but still fill in any untranslated titles
            // (the model may have become available after the last fetch, or a prior
            // translation was skipped — opening the page should translate, not only a pull).
            // applyTitleTranslations no-ops when nothing pends, so this spends an LLM call
            // only the first time new non-Chinese titles appear.
            let cached = dashboard.providers
            let prev = Dictionary(uniqueKeysWithValues: cached.map { ($0.providerId, $0) })
            let translated = await Self.applyTitleTranslations(to: cached, previous: prev, translate: translate)
            let topics = IOSHotListAggregator.aggregate(providerSnapshots: translated, limit: limit)
            dashboard = filteredDashboard(
                IOSHotListDashboard(
                    topics: topics, providers: translated,
                    lastUpdatedAt: dashboard.lastUpdatedAt,
                    enabledSourceCount: dashboard.enabledSourceCount
                ),
                setting: setting
            )
            persist()
            return
        }

        isRefreshing = true
        lastError = nil
        defer { isRefreshing = false }

        let previous = Dictionary(uniqueKeysWithValues: dashboard.providers.map { ($0.providerId, $0) })
        let now = IOSBoardSignalRepository.currentEpochMs()
        let enabledProviders = IOSHotlistProviders.all.filter { enabledIds.contains($0.providerId) }

        // 并发抓取所有启用的来源。此前是顺序抓取,且某个源被取消/超时就 `return` 整批中断,
        // 导致后面的源(尤其新加的 NewsNow)永远抓不到、首页不显示。改成 TaskGroup 后单个源
        // 失败/为空只影响自己,互不拖累。
        var snapshots = await withTaskGroup(of: IOSHotListProviderSnapshot.self) { group in
            for provider in enabledProviders {
                group.addTask {
                    do {
                        let items = try await provider.fetch(limit: limit)
                        return IOSHotListProviderSnapshot(
                            providerId: provider.providerId,
                            providerName: provider.displayName,
                            items: items,
                            fetchedAt: now
                        )
                    } catch {
                        let cached = previous[provider.providerId]?.items ?? []
                        return IOSHotListProviderSnapshot(
                            providerId: provider.providerId,
                            providerName: provider.displayName,
                            items: cached,
                            fetchedAt: previous[provider.providerId]?.fetchedAt ?? now,
                            stale: !cached.isEmpty,
                            // 有缓存就不显示为错误(标 stale 即可);彻底拿不到才报错。
                            error: cached.isEmpty ? error.localizedDescription : nil
                        )
                    }
                }
            }
            var collected: [IOSHotListProviderSnapshot] = []
            for await snapshot in group { collected.append(snapshot) }
            return collected
        }
        // TaskGroup 完成顺序不定,还原成启用列表的稳定顺序。
        let orderIndex = Dictionary(uniqueKeysWithValues: enabledProviders.enumerated().map { ($1.providerId, $0) })
        snapshots.sort { (orderIndex[$0.providerId] ?? 0) < (orderIndex[$1.providerId] ?? 0) }

        // 整批已被取消(用户离开页面)→ 不用部分数据覆盖既有 dashboard。
        if Task.isCancelled { return }

        // Always re-check for untranslated non-Chinese titles on every refresh and
        // fill them in. Cached translations from the prior dashboard are reused so a
        // refresh only spends an LLM call on genuinely new titles. This no longer
        // reads the (snapshot-stale-prone) toggle: applyTitleTranslations no-ops when
        // the translator is nil (no model) or nothing is pending, so it's safe always.
        snapshots = await Self.applyTitleTranslations(to: snapshots, previous: previous, translate: translate)

        let topics = IOSHotListAggregator.aggregate(providerSnapshots: snapshots, limit: limit)
        let rawDashboard = IOSHotListDashboard(
            topics: topics,
            providers: snapshots,
            lastUpdatedAt: snapshots.map(\.fetchedAt).max() ?? now,
            enabledSourceCount: enabledIds.count
        )
        dashboard = filteredDashboard(rawDashboard, setting: setting)
        if dashboard.hasErrors {
            lastError = dashboard.providers.compactMap(\.error).first
        }
        persist()
    }

    /// Fills `displayTitle` with a Chinese translation for non-Chinese titles.
    /// First reuses any cached translation from the previous dashboard (keyed by
    /// raw title), then issues one batched LLM call for the remaining untranslated
    /// titles. No translator or no pending titles → snapshots returned unchanged.
    private static func applyTitleTranslations(
        to snapshots: [IOSHotListProviderSnapshot],
        previous: [String: IOSHotListProviderSnapshot],
        translate: IOSHotListTitleTranslate?
    ) async -> [IOSHotListProviderSnapshot] {
        // 1. Cache prior translations (raw title -> Chinese displayTitle).
        var cache: [String: String] = [:]
        for snap in previous.values {
            for item in snap.items {
                let dt = (item.displayTitle ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if !dt.isEmpty, dt != item.title { cache[item.title] = dt }
            }
        }
        func applyCache(_ snaps: [IOSHotListProviderSnapshot]) -> [IOSHotListProviderSnapshot] {
            snaps.map { snap in
                var s = snap
                s.items = s.items.map { item in
                    var it = item
                    if (it.displayTitle ?? "").isEmpty, let cached = cache[it.title] { it.displayTitle = cached }
                    return it
                }
                return s
            }
        }
        var working = applyCache(snapshots)

        // 2. Translate the titles that are still untranslated and non-Chinese.
        let pending = working.flatMap(\.items)
            .filter { ($0.displayTitle ?? "").isEmpty && IOSHotListTitleTranslator.needsTranslation($0.title) }
            .map(\.title)
#if DEBUG
        NSLog("[AmberTranslate] apply pending=\(pending.count) translatorNil=\(translate == nil) cached=\(cache.count)")
#endif
        guard !pending.isEmpty, let translate else { return working }
        let translations = await translate(Array(Set(pending)))
        guard !translations.isEmpty else { return working }

        working = working.map { snap in
            var s = snap
            s.items = s.items.map { item in
                var it = item
                if (it.displayTitle ?? "").isEmpty, let zh = translations[it.title] { it.displayTitle = zh }
                return it
            }
            return s
        }
        return working
    }

    static func topic(from provider: IOSHotListProviderSnapshot, item: IOSHotlistItem) -> IOSHotTopic {
        let source = IOSHotTopicSource(
            providerId: provider.providerId,
            providerName: provider.providerName,
            rank: item.rank,
            title: item.title,
            displayTitle: item.displayTitle,
            url: item.url,
            heat: item.heat ?? item.score.map(String.init),
            fetchedAt: item.fetchedAt
        )
        return IOSHotListAggregator.aggregate(
            providerSnapshots: [
                IOSHotListProviderSnapshot(
                    providerId: provider.providerId,
                    providerName: provider.providerName,
                    items: [item],
                    fetchedAt: provider.fetchedAt,
                    stale: provider.stale,
                    error: provider.error
                )
            ],
            limit: 1
        ).first ?? IOSHotTopic(
            id: UUID().uuidString,
            title: source.presentationTitle,
            sources: [source],
            sourceCount: 1,
            bestRank: source.rank,
            latestFetchedAt: source.fetchedAt
        )
    }

    private func shouldRefresh(setting: TodayBoardSetting) -> Bool {
        guard dashboard.hasContent, dashboard.lastUpdatedAt > 0 else { return true }
        // 启用来源集合变了(新开/关了源,比如刚加的 NewsNow)→ 立刻刷新,否则缓存里没有
        // 这些源,首页就一直不显示它们,直到刷新间隔过去。
        let enabledIds = IOSHotlistProviders.effectiveEnabledProviderIds(setting: setting)
        let fetchedIds = Set(dashboard.providers.map(\.providerId))
        if enabledIds != fetchedIds { return true }
        let minutes = max(Int(setting.hotListRefreshIntervalMinutes), 30)
        let gapMs = Int64(minutes) * 60_000
        return IOSBoardSignalRepository.currentEpochMs() - dashboard.lastUpdatedAt >= gapMs
    }

    private func filteredDashboard(_ rawDashboard: IOSHotListDashboard, setting: TodayBoardSetting) -> IOSHotListDashboard {
        IOSHotListAggregator.applyInterestFilter(
            dashboard: rawDashboard,
            keywords: setting.hotListFocusKeywords,
            modeWireName: setting.hotListFilterMode.wireName
        )
    }

    private func persist() {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(dashboard)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            print("[IOSHotListDashboardStore] persist failed: \(error.localizedDescription)")
        }
    }

    private static func load(from url: URL, decoder: JSONDecoder, fileManager: FileManager) -> IOSHotListDashboard? {
        guard fileManager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(IOSHotListDashboard.self, from: data)
    }
}

// MARK: - Helpers

enum IOSBoardJSON {
    static func metadata(_ values: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(values),
              let data = try? JSONSerialization.data(withJSONObject: values, options: [.sortedKeys]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }
}

enum IOSBoardDateFormatters {
    static let monthDayTime: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter
    }()

    static func eventRange(start: Date, end: Date) -> String {
        let day = monthDayTime.string(from: start)
        let time = DateFormatter()
        time.locale = Locale.current
        time.timeZone = TimeZone.current
        time.dateFormat = "HH:mm"
        return "\(day) - \(time.string(from: end))"
    }
}

// MARK: - iOS Deep Read

enum IOSDeepReadTaskStatus: String, Codable, CaseIterable, Sendable {
    case queued
    case running
    case succeeded
    case failed
    case unsupported

    var isTerminal: Bool {
        self == .succeeded || self == .failed || self == .unsupported
    }

    var title: String {
        switch self {
        case .queued: "待生成"
        case .running: "生成中"
        case .succeeded: "已完成"
        case .failed: "失败"
        case .unsupported: "不可用"
        }
    }
}

enum IOSDeepReadSourceKind: String, Codable, CaseIterable, Identifiable, Sendable {
    case manualText = "manual_text"
    case searchResult = "search_result"
    case conversation
    case file
    case webMount = "web_mount"
    case hotTopic = "hot_topic"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .manualText: "手动文本"
        case .searchResult: "搜索结果"
        case .conversation: "会话内容"
        case .file: "文件"
        case .webMount: "WebMount"
        case .hotTopic: "热榜主题"
        }
    }
}

struct IOSDeepReadSource: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var kind: IOSDeepReadSourceKind
    var title: String
    var content: String
    var url: String?
    var metadata: [String: String]
    var createdAt: Int64

    init(
        id: String = UUID().uuidString,
        kind: IOSDeepReadSourceKind,
        title: String,
        content: String,
        url: String? = nil,
        metadata: [String: String] = [:],
        createdAt: Int64 = IOSBoardSignalRepository.currentEpochMs()
    ) {
        self.id = id
        self.kind = kind
        self.title = IOSDeepReadSourceNormalizer.clean(title).prefixString(160).ifEmpty(kind.title)
        self.content = IOSDeepReadSourceNormalizer.cleanMultiline(content).prefixString(40_000)
        self.url = IOSDeepReadSourceNormalizer.clean(url ?? "").ifBlankNil
        self.metadata = metadata
        self.createdAt = createdAt
    }
}

struct IOSDeepReadTemplate: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var name: String
    var description: String

    static let customPrefix = "custom:"
    static let magazine = IOSDeepReadTemplate(
        id: "compose_magazine",
        name: "默认杂志",
        description: "摘要、关键点、脉络和延伸阅读。"
    )
    static let editorial = IOSDeepReadTemplate(
        id: "editorial_slant",
        name: "斜切图文",
        description: "更有编辑判断的图文版式。"
    )
    static let analysis = IOSDeepReadTemplate(
        id: "ios_analysis",
        name: "分析",
        description: "旧 iOS 历史版式：突出判断、风险和下一步。"
    )
    static let reading = editorial
    static let builtIns = [magazine, editorial]
    static let defaultId = magazine.id

    static func template(id: String) -> IOSDeepReadTemplate {
        let normalized = normalizedTemplateId(id)
        if normalized.hasPrefix(customPrefix) {
            return IOSDeepReadTemplate(id: normalized, name: "自定义模板", description: "本机保存的 HTML 模板。")
        }
        return builtIns.first { $0.id == normalized } ?? {
            if id == "ios_analysis" { return analysis }
            return magazine
        }()
    }

    static func normalizedTemplateId(_ id: String) -> String {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        switch trimmed {
        case "", "ios_magazine":
            return magazine.id
        case "ios_reading":
            return editorial.id
        case "ios_analysis":
            return analysis.id
        default:
            if trimmed.hasPrefix(customPrefix) { return trimmed }
            if builtIns.contains(where: { $0.id == trimmed }) { return trimmed }
            return magazine.id
        }
    }
}

struct IOSDeepReadTemplateValidationResult: Equatable, Sendable {
    var ok: Bool
    var error: String?

    static let valid = IOSDeepReadTemplateValidationResult(ok: true, error: nil)
}

enum IOSDeepReadHTMLSecurity {
    static let contentSecurityPolicy = "default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data: amberfont:; connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

    static func hardenedDocument(_ html: String) -> String {
        let escapedPolicy = contentSecurityPolicy.replacingOccurrences(of: "\"", with: "&quot;")
        let meta = #"<meta http-equiv="Content-Security-Policy" content="\#(escapedPolicy)">"#
        if let head = tagRanges(named: "head", in: html).first(where: {
            !isClosingTag(String(html[$0]))
        }) {
            var hardened = html
            hardened.insert(contentsOf: meta, at: head.upperBound)
            return hardened
        }
        if let htmlTag = tagRanges(named: "html", in: html).first(where: {
            !isClosingTag(String(html[$0]))
        }) {
            var hardened = html
            hardened.insert(contentsOf: "<head>\(meta)</head>", at: htmlTag.upperBound)
            return hardened
        }
        return "<head>\(meta)</head>\(html)"
    }

    static func containsMetaRefresh(in html: String) -> Bool {
        tags(named: "meta", in: normalizedForInspection(html)).contains { tag in
            attributeValues(named: "http-equiv", in: tag).contains { value in
                value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "refresh"
            }
        }
    }

    static func containsExternalURLAttribute(in html: String) -> Bool {
        let normalized = normalizedForInspection(html)
        return tags(named: nil, in: normalized).contains { tag in
            ["href", "src"].contains { name in
                attributeValues(named: name, in: tag).contains(where: isExternalURLValue)
            }
        }
    }

    private static func normalizedForInspection(_ html: String) -> String {
        let numericUnescaped = decodeNumericCharacterReferences(in: html)
        return [
            "&colon;": ":",
            "&sol;": "/",
            "&Tab;": "\t",
            "&NewLine;": "\n",
        ].reduce(numericUnescaped) { partial, replacement in
            partial.replacingOccurrences(of: replacement.key, with: replacement.value)
        }
    }

    private static func decodeNumericCharacterReferences(in html: String) -> String {
        let pattern = #"&#(?:(?:x|X)([0-9a-fA-F]+)|([0-9]+));?"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return html }
        let mutable = NSMutableString(string: html)
        let matches = regex.matches(
            in: html,
            range: NSRange(location: 0, length: (html as NSString).length)
        )
        for match in matches.reversed() {
            let hexRange = match.range(at: 1)
            let decimalRange = match.range(at: 2)
            let digits: String
            let radix: Int
            if hexRange.location != NSNotFound {
                digits = (html as NSString).substring(with: hexRange)
                radix = 16
            } else if decimalRange.location != NSNotFound {
                digits = (html as NSString).substring(with: decimalRange)
                radix = 10
            } else {
                continue
            }
            guard let value = UInt32(digits, radix: radix),
                  let scalar = UnicodeScalar(value) else {
                continue
            }
            mutable.replaceCharacters(in: match.range, with: String(Character(scalar)))
        }
        return mutable as String
    }

    private static func isExternalURLValue(_ rawValue: String) -> Bool {
        let canonical = rawValue
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\t", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .lowercased()
        if canonical.hasPrefix("//") { return true }
        return ["http:", "https:", "file:", "content:"].contains { canonical.hasPrefix($0) }
    }

    private static func tags(named name: String?, in html: String) -> [String] {
        tagRanges(named: name, in: html).map { String(html[$0]) }
    }

    private static func tagRanges(named name: String?, in html: String) -> [Range<String.Index>] {
        var result: [Range<String.Index>] = []
        var searchStart = html.startIndex
        while let opening = html[searchStart...].firstIndex(of: "<") {
            var cursor = html.index(after: opening)
            var quote: Character?
            var closing: String.Index?
            while cursor < html.endIndex {
                let character = html[cursor]
                if let activeQuote = quote {
                    if character == activeQuote { quote = nil }
                } else if character == "\"" || character == "'" {
                    quote = character
                } else if character == ">" {
                    closing = cursor
                    break
                }
                cursor = html.index(after: cursor)
            }
            guard let closing else { break }
            let upperBound = html.index(after: closing)
            let range = opening..<upperBound
            let tag = String(html[range])
            if let parsedName = parsedTagName(tag), name == nil || parsedName == name?.lowercased() {
                result.append(range)
            }
            searchStart = upperBound
        }
        return result
    }

    private static func parsedTagName(_ tag: String) -> String? {
        var body = tag.dropFirst().dropLast()
            .drop(while: { $0.isWhitespace })
        if body.first == "/" {
            body = body.dropFirst().drop(while: { $0.isWhitespace })
        }
        guard let first = body.first, first.isLetter else { return nil }
        return String(body.prefix(while: { character in
            character.isLetter || character.isNumber || character == ":" || character == "-"
        })).lowercased()
    }

    private static func isClosingTag(_ tag: String) -> Bool {
        tag.dropFirst().drop(while: { $0.isWhitespace }).first == "/"
    }

    private static func attributeValues(named name: String, in tag: String) -> [String] {
        let escapedName = NSRegularExpression.escapedPattern(for: name)
        let pattern = #"(?is)\b\#(escapedName)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let source = tag as NSString
        return regex.matches(in: tag, range: NSRange(location: 0, length: source.length)).compactMap { match in
            for index in 1..<match.numberOfRanges where match.range(at: index).location != NSNotFound {
                return source.substring(with: match.range(at: index))
            }
            return nil
        }
    }

}

enum IOSDeepReadTemplateValidator {
    static let maxHTMLBytes = 96 * 1024

    static func validateHTML(_ html: String, requirePlaceholders: Bool = true) -> IOSDeepReadTemplateValidationResult {
        let byteCount = html.data(using: .utf8)?.count ?? 0
        if byteCount > maxHTMLBytes {
            return .init(ok: false, error: "模板过大：\(byteCount) bytes。")
        }
        if html.range(of: #"(?is)<\s*(html\b|!doctype\s+html)"#, options: .regularExpression) == nil {
            return .init(ok: false, error: "模板必须包含 <html> 或 <!DOCTYPE html>。")
        }
        let blocked: [(String, String)] = [
            (#"(?is)<\s*script\b"#, "模板不允许 JavaScript。"),
            (#"(?is)\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)"#, "模板不允许事件处理器。"),
            (#"(?is)<\s*(iframe|object|embed|form|input|button|textarea|select)\b"#, "模板不允许交互或嵌入元素。"),
            (#"(?is)<\s*(svg|canvas|math|audio|video|source|picture|track)\b"#, "模板不允许媒体、Canvas 或 SVG。"),
            (#"(?is)\b(srcset|poster)\s*="#, "模板不允许响应式或媒体资源属性。"),
            (#"(?is)@import\b"#, "模板不允许 CSS import。"),
            (#"(?is)url\s*\("#, "模板不允许 CSS URL。"),
            (#"(?is)\b(fetch|XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB|eval)\b"#, "模板不允许浏览器 API。")
        ]
        for (pattern, message) in blocked where html.range(of: pattern, options: .regularExpression) != nil {
            return .init(ok: false, error: message)
        }
        if IOSDeepReadHTMLSecurity.containsMetaRefresh(in: html) {
            return .init(ok: false, error: "模板不允许 meta refresh 导航。")
        }
        if IOSDeepReadHTMLSecurity.containsExternalURLAttribute(in: html) {
            return .init(ok: false, error: "模板不允许硬编码外部链接或资源。")
        }
        if requirePlaceholders {
            let requiredPlaceholders = [
                "{{title}}",
                "{{summary}}",
                "{{analysis_html}}",
                "{{extended_reading_html}}",
                "{{font_css}}"
            ]
            if let missing = requiredPlaceholders.first(where: { !html.contains($0) }) {
                return .init(ok: false, error: "模板缺少必要占位符：\(missing)。")
            }
        }
        return .valid
    }
}

enum IOSDeepReadSourceNormalizationError: LocalizedError, Equatable {
    case emptySource(IOSDeepReadSourceKind)
    case unsupported(String)

    var errorDescription: String? {
        switch self {
        case .emptySource(let kind):
            return "\(kind.title)没有可读取内容。"
        case .unsupported(let reason):
            return IOSDeepReadUserFacingText.sanitize(reason)
        }
    }
}

/// 深度阅读用户可见文案：尽量中文，避免把系统/SDK 英文错误直接抛到界面。
enum IOSDeepReadUserFacingText {
    static func fromError(_ error: Error) -> String {
        if let access = error as? DocumentAccessError {
            return access.userMessageForDeepRead
        }
        if let localized = error as? LocalizedError,
           let description = localized.errorDescription?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !description.isEmpty {
            return sanitize(description)
        }
        return sanitize(error.localizedDescription)
    }

    /// 清洗任意原始错误串；已是中文则保留，常见英文映射为中文，否则给通用句。
    static func sanitize(_ raw: String) -> String {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return "操作失败，请稍后重试。" }
        if containsCJK(text) {
            // 去掉夹杂的 debug 英文尾巴（如 threw=2, unusable=1）
            return text
                .replacingOccurrences(
                    of: #"\s*\(threw=\d+,\s*unusable=\d+\)"#,
                    with: "",
                    options: .regularExpression
                )
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }

        let lower = text.lowercased()
        if lower.contains("network") || lower.contains("offline") || lower.contains("internet")
            || lower.contains("not connected") || lower.contains("connection") {
            return "网络不可用，请检查连接后重试。"
        }
        if lower.contains("timeout") || lower.contains("timed out") || lower.contains("time out") {
            return "请求超时，请稍后重试。"
        }
        if lower.contains("cancel") {
            return "操作已取消。"
        }
        if lower.contains("unauthorized") || lower.contains("api key") || lower.contains("401")
            || lower.contains("invalid api") || lower.contains("authentication") {
            return "鉴权失败，请检查 API Key 或登录状态。"
        }
        if lower.contains("forbidden") || lower.contains("403") || lower.contains("permission") {
            return "没有权限执行此操作。"
        }
        if lower.contains("not found") || lower.contains("404") {
            return "未找到相关资源。"
        }
        if lower.contains("429") || lower.contains("rate limit") || lower.contains("too many") {
            return "请求过于频繁，请稍后重试。"
        }
        if lower.contains("500") || lower.contains("502") || lower.contains("503")
            || lower.contains("server error") || lower.contains("internal error") {
            return "服务暂时不可用，请稍后重试。"
        }
        if lower.contains("ssl") || lower.contains("certificate") || lower.contains("secure connection") {
            return "安全连接失败，请稍后重试。"
        }
        if lower.contains("json") || lower.contains("decode") || lower.contains("parse") {
            return "返回内容无法解析。"
        }
        if lower.contains("no such file") || lower.contains("file") && lower.contains("exist") {
            return "文件不存在或无法读取。"
        }
        if lower.contains("workspace") {
            return "Workspace 保存失败，请稍后重试。"
        }
        return "操作失败，请稍后重试。"
    }

    private static func containsCJK(_ text: String) -> Bool {
        text.unicodeScalars.contains {
            let v = $0.value
            return (0x4E00...0x9FFF).contains(v) || (0x3400...0x4DBF).contains(v)
        }
    }
}

enum IOSDeepReadSourceNormalizer {
    static func manualText(title: String, text: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> IOSDeepReadSource {
        let content = cleanMultiline(text)
        guard !content.isEmpty else { throw IOSDeepReadSourceNormalizationError.emptySource(.manualText) }
        return IOSDeepReadSource(
            kind: .manualText,
            title: clean(title).ifEmpty(firstLineTitle(content, fallback: "手动深度阅读")),
            content: content,
            createdAt: now
        )
    }

    /// A synthetic source representing a FAILED search — a distinct,
    /// machine-readable source-failure state (`scrape_status="failed"`, matching
    /// the scrape-enrichment convention) so a failed search is not silently
    /// indistinguishable from real manual content. The Deep Read source-collection
    /// catch path and its test both build the failed source via this, so the
    /// marker is exercised end-to-end (closes the deepread search-failure fake-green:
    /// the generator excludes scrape_status=failed sources from the factual block).
    static func searchFailureSource(query: String, error: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> IOSDeepReadSource {
        var source = try manualText(
            title: "搜索不可用：\(query)",
            text: "搜索来源未能读取：\(error)",
            now: now
        )
        source.metadata["scrape_status"] = "failed"
        return source
    }

    static func searchSources(query: String, results: [IOSSearchResult], now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> [IOSDeepReadSource] {
        let cleanQuery = clean(query)
        let sources = results.enumerated().compactMap { index, result -> IOSDeepReadSource? in
            let content = cleanMultiline([
                result.snippet,
                result.url
            ].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "\n"))
            guard !content.isEmpty else { return nil }
            var metadata = [
                "query": cleanQuery,
                "rank": "\(index + 1)"
            ]
            // Carry the first usable provider image (e.g. a Brave thumbnail) so the
            // editorial reader can source a hero. Stored in metadata to avoid a
            // schema change to the persisted IOSDeepReadSource.
            if let image = result.images.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
                metadata["hero_image_url"] = image
            }
            return IOSDeepReadSource(
                kind: .searchResult,
                title: result.title.ifEmpty("搜索结果 \(index + 1)"),
                content: content,
                url: result.url,
                metadata: metadata,
                createdAt: now
            )
        }
        guard !sources.isEmpty else { throw IOSDeepReadSourceNormalizationError.emptySource(.searchResult) }
        return sources
    }

    static func conversationSource(title: String, messages: [String], now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> IOSDeepReadSource {
        let content = cleanMultiline(messages.joined(separator: "\n\n"))
        guard !content.isEmpty else { throw IOSDeepReadSourceNormalizationError.emptySource(.conversation) }
        return IOSDeepReadSource(
            kind: .conversation,
            title: clean(title).ifEmpty("当前会话"),
            content: content,
            metadata: ["message_count": "\(messages.count)"],
            createdAt: now
        )
    }

    static func fileSource(_ read: SelectedDocumentReadResult, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> IOSDeepReadSource {
        let content = cleanMultiline(read.preview)
        guard !content.isEmpty else {
            throw IOSDeepReadSourceNormalizationError.unsupported(read.note ?? "文件中没有可读取文本。")
        }
        return IOSDeepReadSource(
            kind: .file,
            title: read.fileName,
            content: content,
            metadata: [
                "file_type": read.fileType,
                "bytes": "\(read.bytesRead)",
                "truncated": "\(read.isTruncated)"
            ],
            createdAt: now
        )
    }

    static func webMountSource(title: String, url: String?, text: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> IOSDeepReadSource {
        let content = cleanMultiline(text)
        guard !content.isEmpty else {
            throw IOSDeepReadSourceNormalizationError.unsupported("当前 WebMount 页面没有可读取正文；请先打开站点并确认页面已加载。")
        }
        return IOSDeepReadSource(
            kind: .webMount,
            title: clean(title).ifEmpty("WebMount 页面"),
            content: content,
            url: url,
            createdAt: now
        )
    }

    static func hotTopicSources(topic: IOSHotTopic, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) throws -> [IOSDeepReadSource] {
        let sources = topic.sources.compactMap { source -> IOSDeepReadSource? in
            let content = cleanMultiline([
                "综合主题：\(topic.title)",
                "来源：\(source.providerName)",
                "榜单标题：\(source.presentationTitle)",
                "排名：\(source.rank)",
                source.heat.map { "热度：\($0)" },
                source.url.map { "链接：\($0)" }
            ].compactMap { $0 }.joined(separator: "\n"))
            guard !content.isEmpty else { return nil }
            return IOSDeepReadSource(
                kind: .hotTopic,
                title: source.presentationTitle,
                content: content,
                url: source.url,
                metadata: [
                    "topic_id": topic.id,
                    "topic_title": topic.title,
                    "provider_id": source.providerId,
                    "provider_name": source.providerName,
                    "rank": "\(source.rank)",
                    "heat": source.heat ?? ""
                ],
                createdAt: now
            )
        }
        guard !sources.isEmpty else { throw IOSDeepReadSourceNormalizationError.emptySource(.hotTopic) }
        return sources
    }

    static func clean(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\u{0}", with: "")
            .replacingOccurrences(of: #"[ \t\r\f\v]+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func cleanMultiline(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\u{0}", with: "")
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
            .map { clean($0) }
            .joined(separator: "\n")
            .replacingOccurrences(of: #"\n[ \t]*\n(?:[ \t]*\n)+"#, with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func firstLineTitle(_ text: String, fallback: String) -> String {
        text
            .split(whereSeparator: \.isNewline)
            .first
            .map { String($0).prefixString(60) }?
            .ifEmpty(fallback) ?? fallback
    }
}

struct IOSDeepReadTask: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var title: String
    var status: IOSDeepReadTaskStatus
    var templateId: String
    var sources: [IOSDeepReadSource]
    var resultMarkdown: String
    var failureMessage: String?
    var createdAt: Int64
    var updatedAt: Int64
    var completedAt: Int64?
    var retryCount: Int
    /// Serialized `IOSDeepReadOutput` JSON when the LLM produced structured output;
    /// the reader renders the rich editorial cards from it. nil → flat-markdown reader.
    /// Optional so old persisted tasks decode unchanged.
    var structuredJSON: String? = nil
    /// Non-nil when the reading itself succeeded but the best-effort Workspace
    /// artifact sync failed. Persisted because true background execution has no
    /// live status closure to surface this problem.
    var workspaceSyncFailed: String? = nil
    /// Stage labels that produced no usable content when the run completed
    /// (partial completion — Android's per-section FAILED analogue). Optional so
    /// old persisted tasks decode unchanged; nil/empty = every stage contributed.
    var missingSections: [String]? = nil

    var sourceSummary: String {
        let counts = Dictionary(grouping: sources, by: \.kind)
            .mapValues(\.count)
            .sorted { $0.key.rawValue < $1.key.rawValue }
            .map { "\($0.key.title) \($0.value)" }
        return counts.joined(separator: " · ")
    }

    var template: IOSDeepReadTemplate {
        IOSDeepReadTemplate.template(id: templateId)
    }
}

@MainActor
@Observable
final class IOSDeepReadStore {
    static let shared = IOSDeepReadStore()

    private(set) var tasks: [IOSDeepReadTask]
    /// Ephemeral in-memory stage labels for the detail skeleton (not persisted).
    private(set) var progressLabelsByTaskId: [String: String] = [:]

    private let directory: URL
    private let fileURL: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let root = baseDirectory
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = root.appendingPathComponent("deep_read", isDirectory: true)
        fileURL = directory.appendingPathComponent("tasks.json", isDirectory: false)
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        tasks = Self.loadTasks(from: fileURL, decoder: decoder)
    }

    func progressLabel(for id: String) -> String? {
        progressLabelsByTaskId[id]
    }

    func setProgressLabel(id: String, _ label: String?) {
        let cleaned = label?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if cleaned.isEmpty {
            progressLabelsByTaskId.removeValue(forKey: id)
        } else {
            progressLabelsByTaskId[id] = cleaned
        }
    }

    func clearProgressLabel(id: String) {
        progressLabelsByTaskId.removeValue(forKey: id)
    }

    var history: [IOSDeepReadTask] {
        tasks.sorted {
            if $0.updatedAt == $1.updatedAt { return $0.createdAt > $1.createdAt }
            return $0.updatedAt > $1.updatedAt
        }
    }

    func task(id: String) -> IOSDeepReadTask? {
        tasks.first { $0.id == id }
    }

    @discardableResult
    func createTask(
        title rawTitle: String,
        sources: [IOSDeepReadSource],
        templateId: String = IOSDeepReadTemplate.defaultId,
        now: Int64 = IOSBoardSignalRepository.currentEpochMs()
    ) throws -> IOSDeepReadTask {
        let validSources = sources.filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard !validSources.isEmpty else {
            throw IOSDeepReadSourceNormalizationError.emptySource(.manualText)
        }
        let title = IOSDeepReadSourceNormalizer.clean(rawTitle)
            .ifEmpty(validSources.first?.title ?? "深度阅读")
        let task = IOSDeepReadTask(
            id: UUID().uuidString,
            title: title.prefixString(160),
            status: .queued,
            templateId: IOSDeepReadTemplate.normalizedTemplateId(templateId),
            sources: validSources,
            resultMarkdown: "",
            failureMessage: nil,
            createdAt: now,
            updatedAt: now,
            completedAt: nil,
            retryCount: 0
        )
        upsert(task)
        return task
    }

    func markRunning(id: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.status = .running
            task.failureMessage = nil
            task.updatedAt = now
        }
    }

    func replaceSources(id: String, sources: [IOSDeepReadSource], now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.sources = sources
            task.updatedAt = now
        }
    }

    func complete(id: String, markdown: String, structuredJSON: String? = nil, missingSections: [String]? = nil, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        clearProgressLabel(id: id)
        update(id: id) { task in
            task.status = .succeeded
            task.resultMarkdown = markdown
            task.structuredJSON = structuredJSON
            task.missingSections = (missingSections?.isEmpty == false) ? missingSections : nil
            task.failureMessage = nil
            task.workspaceSyncFailed = nil
            task.updatedAt = now
            task.completedAt = now
        }
    }

    func markWorkspaceSyncFailed(id: String, message: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.workspaceSyncFailed = message.prefixString(500)
            task.updatedAt = now
        }
    }

    func clearWorkspaceSyncFailure(id: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.workspaceSyncFailed = nil
            task.updatedAt = now
        }
    }

    func fail(id: String, message: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        clearProgressLabel(id: id)
        update(id: id) { task in
            task.status = .failed
            task.failureMessage = message.prefixString(500)
            task.updatedAt = now
        }
    }

    func prepareRetry(id: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        clearProgressLabel(id: id)
        update(id: id) { task in
            task.status = .queued
            task.resultMarkdown = ""
            task.structuredJSON = nil
            task.missingSections = nil
            task.failureMessage = nil
            task.workspaceSyncFailed = nil
            task.completedAt = nil
            task.retryCount += 1
            task.updatedAt = now
        }
    }

    func recoverInterruptedRuns(
        excluding activeTaskIds: Set<String> = [],
        staleAfterMs: Int64 = 30 * 60 * 1000,
        now: Int64 = IOSBoardSignalRepository.currentEpochMs()
    ) {
        var changed = false
        for index in tasks.indices {
            guard tasks[index].status == .running || tasks[index].status == .queued else { continue }
            if activeTaskIds.contains(tasks[index].id),
               now - tasks[index].updatedAt < staleAfterMs {
                continue
            }
            tasks[index].status = .failed
            tasks[index].failureMessage = "上次深度阅读生成被中断，可重试。"
            tasks[index].updatedAt = now
            changed = true
        }
        if changed { persist() }
    }

    private func update(id: String, mutate: (inout IOSDeepReadTask) -> Void) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        mutate(&tasks[index])
        persist()
    }

    private func upsert(_ task: IOSDeepReadTask) {
        if let index = tasks.firstIndex(where: { $0.id == task.id }) {
            tasks[index] = task
        } else {
            tasks.append(task)
        }
        persist()
    }

    private func persist() {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(tasks.sorted { $0.createdAt < $1.createdAt })
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            print("[IOSDeepReadStore] persist failed: \(error.localizedDescription)")
        }
    }

    private static func loadTasks(from fileURL: URL, decoder: JSONDecoder) -> [IOSDeepReadTask] {
        guard FileManager.default.fileExists(atPath: fileURL.path),
              let data = try? Data(contentsOf: fileURL),
              let decoded = try? decoder.decode([IOSDeepReadTask].self, from: data) else {
            return []
        }
        return decoded
    }
}

struct IOSDeepReadCustomTemplate: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var name: String
    var description: String
    var html: String
    var createdByAI: Bool
    var createdAt: Int64
    var updatedAt: Int64

    init(
        id: String = IOSDeepReadTemplate.customPrefix + UUID().uuidString.lowercased(),
        name: String,
        description: String,
        html: String,
        createdByAI: Bool,
        createdAt: Int64 = IOSBoardSignalRepository.currentEpochMs(),
        updatedAt: Int64 = IOSBoardSignalRepository.currentEpochMs()
    ) {
        self.id = id.hasPrefix(IOSDeepReadTemplate.customPrefix) ? id : IOSDeepReadTemplate.customPrefix + id
        self.name = IOSDeepReadSourceNormalizer.clean(name).ifEmpty("自定义模板")
        self.description = IOSDeepReadSourceNormalizer.clean(description).prefixString(240)
        self.html = html
        self.createdByAI = createdByAI
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

enum IOSDeepReadTemplateStoreError: LocalizedError, Equatable {
    case invalidTemplate(String)
    case notFound

    var errorDescription: String? {
        switch self {
        case .invalidTemplate(let message): message
        case .notFound: "模板不存在。"
        }
    }
}

@MainActor
@Observable
final class IOSDeepReadTemplateStore {
    static let shared = IOSDeepReadTemplateStore()

    private(set) var templates: [IOSDeepReadCustomTemplate]

    private let directory: URL
    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let root = baseDirectory
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = root.appendingPathComponent("deep_read", isDirectory: true)
        fileURL = directory.appendingPathComponent("templates.json", isDirectory: false)
        templates = Self.load(from: fileURL, decoder: decoder, fileManager: fileManager)
    }

    func template(id: String) -> IOSDeepReadCustomTemplate? {
        templates.first { $0.id == id }
    }

    @discardableResult
    func save(_ template: IOSDeepReadCustomTemplate) throws -> IOSDeepReadCustomTemplate {
        let validation = IOSDeepReadTemplateValidator.validateHTML(template.html)
        guard validation.ok else {
            throw IOSDeepReadTemplateStoreError.invalidTemplate(validation.error ?? "模板校验失败。")
        }
        var next = template
        next.id = IOSDeepReadTemplate.normalizedTemplateId(next.id)
        if !next.id.hasPrefix(IOSDeepReadTemplate.customPrefix) {
            next.id = IOSDeepReadTemplate.customPrefix + UUID().uuidString.lowercased()
        }
        next.updatedAt = IOSBoardSignalRepository.currentEpochMs()
        if let index = templates.firstIndex(where: { $0.id == next.id }) {
            templates[index] = next
        } else {
            templates.append(next)
        }
        templates.sort { $0.updatedAt > $1.updatedAt }
        persist()
        return next
    }

    func delete(id: String) {
        templates.removeAll { $0.id == id }
        persist()
    }

    private func persist() {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(templates)
            try data.write(to: fileURL, options: [.atomic])
        } catch {
            print("[IOSDeepReadTemplateStore] persist failed: \(error.localizedDescription)")
        }
    }

    private static func load(from url: URL, decoder: JSONDecoder, fileManager: FileManager) -> [IOSDeepReadCustomTemplate] {
        guard fileManager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let decoded = try? decoder.decode([IOSDeepReadCustomTemplate].self, from: data) else {
            return []
        }
        return decoded.filter { IOSDeepReadTemplateValidator.validateHTML($0.html).ok }
            .sorted { $0.updatedAt > $1.updatedAt }
    }
}

enum IOSDeepReadHTMLTemplateRenderer {
    static func render(task: IOSDeepReadTask, template: IOSDeepReadCustomTemplate, fontScale: Float, fontModeWireName: String) throws -> String {
        let validation = IOSDeepReadTemplateValidator.validateHTML(template.html)
        guard validation.ok else {
            throw IOSDeepReadTemplateStoreError.invalidTemplate(validation.error ?? "模板校验失败。")
        }
        let contentHTML = markdownToHTML(task.resultMarkdown.isEmpty ? IOSDeepReadDraftGenerator.generate(task: task) : task.resultMarkdown)
        let sourcesHTML = task.sources.map { source in
            let url = source.url.map { "<div class=\"source-url\">\(escapeHTML($0))</div>" } ?? ""
            return "<li><strong>\(escapeHTML(source.kind.title))｜\(escapeHTML(source.title))</strong><p>\(escapeHTML(source.content.prefixString(420)))</p>\(url)</li>"
        }.joined(separator: "\n")
        let replacements: [String: String] = [
            "{{title}}": escapeHTML(task.title),
            "{{summary}}": escapeHTML(summary(from: task.resultMarkdown)),
            "{{content_html}}": contentHTML,
            "{{analysis_html}}": contentHTML,
            "{{narrative_html}}": contentHTML,
            "{{extended_reading_html}}": "<ul class=\"sources\">\(sourcesHTML)</ul>",
            "{{sources_html}}": "<ul class=\"sources\">\(sourcesHTML)</ul>",
            "{{font_css}}": fontCSS(scale: fontScale, modeWireName: fontModeWireName)
        ]
        var html = template.html
        for (key, value) in replacements {
            html = html.replacingOccurrences(of: key, with: value)
        }
        return html
    }

    static func starterHTML(name: String = "自定义模板") -> String {
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <style>
        {{font_css}}
        body { margin: 0; padding: 24px; background: #f8fafc; color: #111827; }
        article { max-width: 760px; margin: 0 auto; }
        h1 { font-size: 30px; line-height: 1.18; margin: 0 0 14px; }
        .summary { color: #475569; margin-bottom: 20px; }
        section { background: #ffffff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 18px; margin: 14px 0; }
        .sources { padding-left: 18px; }
        .source-url { color: #2563eb; word-break: break-all; font-size: 12px; }
        </style>
        </head>
        <body>
        <article>
        <h1>{{title}}</h1>
        <p class="summary">{{summary}}</p>
        <section>{{analysis_html}}</section>
        <section>{{extended_reading_html}}</section>
        </article>
        </body>
        </html>
        """
    }

    private static func fontCSS(scale: Float, modeWireName: String) -> String {
        let safeScale = max(0.85, min(1.25, Double(scale)))
        let family = modeWireName == "system"
            ? "-apple-system, BlinkMacSystemFont, 'SF Pro Text', sans-serif"
            : "Georgia, 'Times New Roman', 'Songti SC', serif"
        return """
        :root { font-size: \(String(format: "%.2f", safeScale * 16))px; }
        body { font-family: \(family); line-height: 1.72; }
        """
    }

    private static func summary(from markdown: String) -> String {
        let clean = IOSDeepReadSourceNormalizer.cleanMultiline(markdown)
        guard !clean.isEmpty else { return "暂无摘要。" }
        return clean
            .split(whereSeparator: \.isNewline)
            .first { line in
                !line.hasPrefix("#") && !String(line).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            .map { String($0).prefixString(180) } ?? clean.prefixString(180)
    }

    private static func markdownToHTML(_ markdown: String) -> String {
        var html: [String] = []
        var inList = false
        for rawLine in markdown.components(separatedBy: "\n") {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty {
                if inList {
                    html.append("</ul>")
                    inList = false
                }
                continue
            }
            if line.hasPrefix("### ") {
                if inList { html.append("</ul>"); inList = false }
                html.append("<h3>\(escapeHTML(String(line.dropFirst(4))))</h3>")
            } else if line.hasPrefix("## ") {
                if inList { html.append("</ul>"); inList = false }
                html.append("<h2>\(escapeHTML(String(line.dropFirst(3))))</h2>")
            } else if line.hasPrefix("# ") {
                if inList { html.append("</ul>"); inList = false }
                html.append("<h1>\(escapeHTML(String(line.dropFirst(2))))</h1>")
            } else if line.hasPrefix("- ") {
                if !inList {
                    html.append("<ul>")
                    inList = true
                }
                html.append("<li>\(escapeHTML(String(line.dropFirst(2))))</li>")
            } else {
                if inList { html.append("</ul>"); inList = false }
                html.append("<p>\(escapeHTML(line))</p>")
            }
        }
        if inList { html.append("</ul>") }
        return html.joined(separator: "\n")
    }

    private static func escapeHTML(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")
    }
}

enum IOSDeepReadTemplateDraftGenerator {
    enum DraftError: LocalizedError, Equatable {
        case missingModel
        case emptyResponse
        case invalidJSON
        case invalidTemplate(String)

        var errorDescription: String? {
            switch self {
            case .missingModel: "没有可用模型，无法生成模板草稿。"
            case .emptyResponse: "模型没有返回模板草稿。"
            case .invalidJSON: "模型返回的模板草稿不是可解析 JSON。"
            case .invalidTemplate(let reason): "模板草稿未通过校验：\(reason)"
            }
        }
    }

    @MainActor
    static func generateDraft(
        name: String,
        brief: String,
        providerSetting: ProviderSetting,
        modelId: String,
        provider: IOSAgentTextProvider = OpenAIKmpProviderAdapter()
    ) async throws -> IOSDeepReadCustomTemplate {
        let safeModel = modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !safeModel.isEmpty else { throw DraftError.missingModel }
        let prompt = """
        为 AmberAgent 深度阅读生成一个受限 HTML 模板，返回 JSON：{"name":"","description":"","html":""}。
        模板必须包含 <!DOCTYPE html> 或 <html>，不能包含 JavaScript、iframe、form、外部链接/外部资源、CSS url/import。
        必须至少使用这些占位符：{{title}}、{{summary}}、{{analysis_html}}、{{extended_reading_html}}、{{font_css}}。
        模板名称：\(name)
        用户要求：\(brief)
        """
        let messages = [
            UIMessage.companion.system(prompt: "你只输出 JSON，不输出 Markdown 代码围栏。"),
            UIMessage.companion.user(prompt: prompt)
        ]
        let params = TextGenerationParams(
            model: Model(modelId: safeModel, displayName: safeModel, id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.35),
            topP: nil,
            maxTokens: KotlinInt(value: 2_800),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let chunk = try await provider.generateText(providerSetting: providerSetting, messages: messages, params: params)
        let text = (chunk.choices.first?.message?.parts ?? [])
            .compactMap { $0 as? UIMessagePart.Text }
            .map { $0.text }
            .joined(separator: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw DraftError.emptyResponse }
        guard let data = extractJSONObject(from: text).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DraftError.invalidJSON
        }
        let html = object["html"] as? String ?? ""
        let validation = IOSDeepReadTemplateValidator.validateHTML(html)
        guard validation.ok else { throw DraftError.invalidTemplate(validation.error ?? "未知错误") }
        return IOSDeepReadCustomTemplate(
            name: (object["name"] as? String)?.ifEmpty(name) ?? name,
            description: (object["description"] as? String) ?? brief,
            html: html,
            createdByAI: true
        )
    }

    private static func extractJSONObject(from text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("{"), trimmed.hasSuffix("}") { return trimmed }
        guard let start = trimmed.firstIndex(of: "{"),
              let end = trimmed.lastIndex(of: "}") else {
            return trimmed
        }
        return String(trimmed[start...end])
    }
}

enum IOSDeepReadDraftGenerator {
    /// Resolves the provider setting the Deep Read pipeline should use, by
    /// honoring the user's actually-selected provider — NOT rebuilding an
    /// OpenAI-shaped setting from scratch. This is the parity fix for
    /// `deepread.provider_real`: when Claude is selected, the pipeline must
    /// dispatch through a `ProviderSetting.Claude` (native `/messages`), and
    /// `OpenAIKmpProviderAdapter.generateText` already downcasts + dispatches on
    /// the sealed type. Reusing the selected setting (mirroring how chat resolves
    /// via `ChatProviderConfiguration.provider(for:providers:)`) avoids the
    /// silent `/chat/completions`-on-Claude gap. (locked_decision: no
    /// ProviderExecutionContext; only the (provider, model, params) tuple.)
    ///
    /// The caller passes the selected provider verbatim (chat already resolved
    /// it); this returns it unchanged so the sealed type flows to the adapter.
    /// A nil return signals "no usable provider" → the caller falls back to the
    /// deterministic offline draft (honest degradation).
    static func resolveProviderSetting(selected: ProviderSetting?) -> ProviderSetting? {
        guard let selected else { return nil }
        // Pass the selected setting through by sealed type so the adapter
        // dispatches to the right native endpoint. We clone into a clean id to
        // avoid mutating the shared registry object, but preserve the type +
        // credentials + endpoint. descriptionText/shortDescriptionText are
        // abstract on the base ProviderSetting, read once here.
        let descriptionText = selected.descriptionText
        let shortDescriptionText = selected.shortDescriptionText
        switch selected {
        case let openAI as ProviderSetting.OpenAI:
            return ProviderSetting.OpenAI(
                id: KotlinUuid.companion.random(),
                enabled: openAI.enabled,
                name: openAI.name,
                models: openAI.models,
                balanceOption: openAI.balanceOption,
                builtIn: openAI.builtIn,
                descriptionText: descriptionText,
                shortDescriptionText: shortDescriptionText,
                apiKey: openAI.apiKey,
                baseUrl: openAI.baseUrl,
                chatCompletionsPath: openAI.chatCompletionsPath,
                useResponseApi: openAI.useResponseApi,
                authMode: openAI.authMode,
                brand: openAI.brand
            )
        case let claude as ProviderSetting.Claude:
            return ProviderSetting.Claude(
                id: KotlinUuid.companion.random(),
                enabled: claude.enabled,
                name: claude.name,
                models: claude.models,
                balanceOption: claude.balanceOption,
                builtIn: claude.builtIn,
                descriptionText: descriptionText,
                shortDescriptionText: shortDescriptionText,
                apiKey: claude.apiKey,
                baseUrl: claude.baseUrl,
                promptCaching: claude.promptCaching
            )
        default:
            // Unknown/non-OpenAI-compatible sealed type → none usable. The caller
            // degrades to the deterministic offline draft (interim safeguard).
            return nil
        }
    }

    static func generate(task: IOSDeepReadTask, now: Date = Date()) -> String {
        let sources = task.sources.filter { $0.metadata["scrape_status"] != "failed" }
        let date = IOSDeepReadDateFormatters.detail.string(from: now)
        let grouped = Dictionary(grouping: sources, by: \.kind)

        var lines: [String] = []
        lines.append("# \(task.title)")
        lines.append("")
        lines.append(date)
        lines.append("")
        lines.append("## 摘要")
        lines.append(summary(from: sources))
        lines.append("")
        lines.append("## 关键来源")
        for source in sources.prefix(8) {
            lines.append("- **\(source.kind.title)｜\(source.title)**：\(excerpt(source.content, limit: 220))")
            if let url = source.url, !url.isEmpty {
                lines.append("  \(url)")
            }
        }
        lines.append("")
        lines.append("## 脉络")
        lines.append(narrative(from: sources))
        lines.append("")
        lines.append("## 分析")
        lines.append(analysis(from: sources, templateId: task.templateId))
        lines.append("")
        lines.append("## 后续阅读")
        let links = sources.compactMap { source -> String? in
            guard let url = source.url, !url.isEmpty else { return nil }
            return "- [\(source.title)](\(url))"
        }
        lines.append(contentsOf: links.isEmpty ? ["- 当前来源没有可打开链接。"] : links)
        if grouped[.file]?.contains(where: { $0.metadata["truncated"] == "true" }) == true {
            lines.append("")
            lines.append("> 文件内容已截断；如需更完整分析，请缩小文件或分段导入。")
        }
        return lines.joined(separator: "\n")
    }

    private static func summary(from sources: [IOSDeepReadSource]) -> String {
        let head = sources
            .prefix(3)
            .map { excerpt($0.content, limit: 160) }
            .filter { !$0.isEmpty }
        guard !head.isEmpty else { return "当前没有足够文本形成摘要。" }
        return "这次深度阅读基于\(sources.count)个真实来源整理：\(head.joined(separator: " "))"
    }

    private static func narrative(from sources: [IOSDeepReadSource]) -> String {
        sources
            .prefix(5)
            .enumerated()
            .map { index, source in
                "\(index + 1). \(source.title)：\(excerpt(source.content, limit: 180))"
            }
            .joined(separator: "\n")
    }

    private static func analysis(from sources: [IOSDeepReadSource], templateId: String) -> String {
        let sourceKinds = Set(sources.map(\.kind))
        var points: [String] = []
        if sourceKinds.contains(.searchResult) {
            points.append("- 搜索来源适合交叉核对，但摘要可能受搜索页片段限制。")
        }
        if sourceKinds.contains(.conversation) {
            points.append("- 会话来源能保留上下文意图，适合提炼待办、决策和未解决问题。")
        }
        if sourceKinds.contains(.file) {
            points.append("- 文件来源来自本机显式选择；不可读或扫描图片不会被假装 OCR。")
        }
        if sourceKinds.contains(.webMount) {
            points.append("- WebMount 来源只读取当前前台页面正文，不自动登录或跨站抓取。")
        }
        if sourceKinds.contains(.hotTopic) {
            points.append("- 热榜来源来自公开榜单；网页正文抓取失败时只保留榜单标题、排名、热度和链接，不补写未读取内容。")
        }
        if points.isEmpty {
            points.append("- 当前结论主要来自用户提供文本；建议补充搜索或文件来源做交叉验证。")
        }
        if templateId == IOSDeepReadTemplate.analysis.id {
            points.append("- 下一步：标记需要验证的事实、补齐缺失来源，再决定是否把结果发回聊天继续推演。")
        }
        return points.joined(separator: "\n")
    }

    private static func excerpt(_ text: String, limit: Int) -> String {
        IOSDeepReadSourceNormalizer.cleanMultiline(text).prefixString(limit)
    }

    // MARK: - LLM-driven generation (Android DeepReadAgentRunManager parity)
    //
    // The deterministic `generate(task:)` above is an offline fallback. This
    // async variant runs real LLM synthesis per section (overview → narrative →
    // analysis → extended reading), mirroring Android's DeepReadAgentRunManager
    // stage pipeline. When the provider/key is unavailable it falls back to the
    // deterministic draft so the feature degrades honestly instead of failing.
    // Real generation quality is validated via manual smoke; the stage loop +
    // fallback are unit-tested with a scripted provider.

    /// Generates a deep-read draft via real LLM synthesis. Each section is one
    /// model call seeded with the sources + prior-section output (sequential,
    /// like Android). Returns the deterministic fallback when generation fails.
    static func generateViaLLM(
        task: IOSDeepReadTask,
        providerSetting: ProviderSetting,
        modelId: String,
        provider: IOSAgentTextProvider = OpenAIKmpProviderAdapter(),
        now: Date = Date()
    ) async -> String {
        await generateViaLLMResult(
            task: task,
            providerSetting: providerSetting,
            modelId: modelId,
            provider: provider,
            now: now
        ).markdown
    }

    /// Structured result of an LLM-driven generation run, exposing whether the
    /// run honestly failed (every stage threw OR produced empty/whitespace-only
    /// output). The honest-fail state machine (P0.5 `deepread.honest_fail`)
    /// requires the caller to mark the task `.failed` when `didFail` is true,
    /// instead of marking it `.succeeded` with empty sections.
    struct GenerationResult {
        let markdown: String
        let didFail: Bool
        let failureReason: String
        var structuredJSON: String? = nil
        /// Stage labels (e.g. "深度分析") that produced no usable content after
        /// the in-stage retry. Empty = every stage contributed. Non-empty runs are
        /// still completed honestly (Android's per-section FAILED analogue) but
        /// the caller MUST surface this instead of silently thinning the article.
        var missingSections: [String] = []
    }

    /// Terminal outcome of a deep-read run, decoupled from the caller's
    /// side-effects (navigation/banner). Both the create and retry view paths
    /// route their `GenerationResult` through `outcome(for:offlineFallback:)`,
    /// so the "didFail → .failed, else → completed draft" decision is a single
    /// unit-tested source of truth — a caller can no longer silently drop
    /// `didFail` and mark an all-failed run succeeded (closes the
    /// deepread.honest_fail caller-honoring gap on BOTH surfaces).
    enum DeepReadOutcome: Equatable {
        case completed(markdown: String, structuredJSON: String? = nil)
        case failed(reason: String)
    }

    /// Maps a `GenerationResult` to a terminal `DeepReadOutcome`. An honest
    /// failure (every stage threw/empty) becomes `.failed`; otherwise the run
    /// completes, substituting the deterministic offline draft only when the
    /// model produced empty markdown (never fabricating success).
    static func outcome(for result: GenerationResult, offlineFallback: String) -> DeepReadOutcome {
        if result.didFail {
            return .failed(reason: result.failureReason)
        }
        return .completed(
            markdown: result.markdown.isEmpty ? offlineFallback : result.markdown,
            structuredJSON: result.structuredJSON
        )
    }

    /// Same stage pipeline as `generateViaLLM`, but reports honest failure:
    /// `didFail` is true when every stage either threw or produced empty output.
    /// A run where at least one stage produced usable text is NOT a failure
    /// (partial output is still surfaced honestly as a completed draft).
    static func generateViaLLMResult(
        task: IOSDeepReadTask,
        providerSetting: ProviderSetting,
        modelId: String,
        provider: IOSAgentTextProvider = OpenAIKmpProviderAdapter(),
        now: Date = Date(),
        onStageProgress: (@MainActor (_ label: String, _ index: Int, _ total: Int) -> Void)? = nil,
        initialOutput: IOSDeepReadOutput? = nil,
        targetStages: Set<String>? = nil,
        stageTimeouts: [String: Double]? = nil
    ) async -> GenerationResult {
        // Build a source block incl. any captured image URLs (so the model can obey
        // the "images only from sources" rule). Exclude failed-search sources.
        let usableSources = task.sources.filter {
            $0.metadata["scrape_status"] != "failed"
                && !IOSDeepReadSourceNormalizer.cleanMultiline($0.content).isEmpty
        }
        guard !usableSources.isEmpty else {
            return GenerationResult(
                markdown: "",
                didFail: true,
                failureReason: "没有可用来源"
            )
        }
        let usable = Array(usableSources.prefix(10))
#if DEBUG
        NSLog("[AmberDeepRead] sources=\(task.sources.count) usable=\(usable.count)")
#endif

        // Article plan first (Android generateArticlePlan parity): one call decides
        // the angle / narrative slots / analysis questions / stakeholders; any
        // failure falls back to the deterministic local plan. The plan drives the
        // per-stage source bucketing and the prompt injections.
        let plan = await synthesizePlan(
            topicTitle: task.title,
            usableSources: usable,
            providerSetting: providerSetting,
            modelId: modelId,
            provider: provider,
            timeoutSeconds: stageTimeouts?["结构规划"] ?? planTimeoutSeconds
        )
        await onStageProgress?("结构规划", 0, 4)
#if DEBUG
        NSLog("[AmberDeepRead] plan angle=\(plan.overviewAngle.prefix(60)) stakeholders=\(plan.stakeholders.count) requiredIds=\(plan.requiredSourceIds)")
#endif

        // 4 JSON stages merged into one IOSDeepReadOutput (Android DeepReadAgentRunManager parity):
        // overview -> narrative -> analysis -> extended-reading. Each stage outputs ONLY its
        // new fields (the accumulator merges prior stages) — re-emitting the whole merged
        // JSON is exactly what blows past maxTokens mid-stage and yields truncated,
        // unparseable output, which is the "only the overview survived" failure mode.
        // A stage that throws / returns unparseable JSON / omits its own fields gets ONE
        // retry with a corrective note before being dropped; dropped stages are reported
        // in `missingSections` instead of silently thinning the article.
        // sourceLimit/excerptLimit/timeout mirror Android (6/1000/90s, 9/1400/110s,
        // 8/1400/150s, 12/700/90s).
        let stages: [(label: String, instruction: String, schema: String, retryNote: String, fieldsPresent: (IOSDeepReadOutput) -> Bool, sourceLimit: Int, excerptLimit: Int, timeoutSeconds: Double)] = [
            ("概览",
             "只完成 topic_type、summary、key_entities。summary 像杂志导语，约 120-250 字、完整句子优先、说明为什么值得读，按 Article Plan 的 angle 组织。本阶段不要输出 timeline / core_points / analysis / extended_reading。不要编造来源之外的事实。",
             #"{"topic_type":"event|opinion|product|person","summary":"约120-250字中文杂志导语","key_entities":["关键实体"]}"#,
             "上一次输出没有包含 summary 字段或太短。请直接输出包含 summary（约120-250字中文导语）的 JSON 对象。",
             { $0.summary.trimmingCharacters(in: .whitespacesAndNewlines).count >= Self.overviewSummaryMinChars },
             6, 1_000, 90),
            ("时间轴叙事",
             "在已有概览基础上补齐 timeline 和 core_points。timeline 讲清「早期背景 → 直接导火索 → 当前事件 → 后续影响」，并覆盖 Article Plan 的 narrative_slots。core_points 是你消化来源后的中文关键脉络（不是来源清单），每条解释为什么重要。",
             #"{"timeline":[{"date":"日期或时间","event":"连贯叙事事件","is_highlight":true}],"core_points":[{"point":"关键脉络","supporting":"为什么重要"}]}"#,
             "上一次输出没有包含 timeline 或 core_points 字段。请基于来源给出至少一条 timeline 事件或一个 core_point 的 JSON 对象。",
             { !$0.timeline.isEmpty || !$0.corePoints.isEmpty },
             9, 1_400, 110),
            ("深度分析",
             "在已有概览和叙事基础上补齐 analysis。core_dispute 用 1-2 句回答各方到底在争什么。perspectives 按 Article Plan 的 stakeholders 与 analysis_questions 展开，至少给出 3-5 个不同当事方/利益方的立场（如监管/政府、涉事企业、消费者/用户、竞争对手、专家/媒体），每条用 viewpoint+holder 表达，避免只有两个立场。quotes 放来源里可核查的原话或关键表态（text+attribution），没有可靠原话就留空数组。implications 写对行业/公众/政策的短期和长期影响。",
             #"{"analysis":{"core_dispute":"核心分歧，可为空","perspectives":[{"viewpoint":"观点","holder":"持有方"}],"implications":"影响分析，可为空","quotes":[{"text":"原话或关键表态","attribution":"出处"}]}}"#,
             "上一次输出没有包含 analysis 字段。请输出包含 core_dispute、perspectives（至少 3 个立场）和 implications 的 analysis JSON 对象。",
             { $0.analysis.hasContent },
             8, 1_400, 150),
            ("扩展阅读",
             "做最后整理：补齐 extended_reading、references 与 hero_image_url。extended_reading 与 references 使用来源里的 title/url/source（优先 Article Plan 的 required_source_ids，各挑 4-8 条真实链接）。hero_image_url 只能从来源 images 列表中选择，没有可靠图片时留空字符串。可选：如果因果链/流程图能帮助理解，补充 diagram（3-6 个节点，type 取 causal_chain|process_flow|stakeholder_map|system_structure|comparison_matrix，节点 label 约 30 字内，edges 只保留关键关系），不需要就省略整个 diagram 字段。",
             #"{"extended_reading":[{"title":"中文标题","url":"URL","source":"来源"}],"references":[{"title":"中文标题","url":"URL","source":"来源"}],"hero_image_url":"只能用来源 images 中的 URL，可为空","hero_caption":"图片说明，可为空"}"#,
             "上一次输出没有包含 extended_reading 字段。请从来源中挑选 4-8 条真实 title/url 链接并输出 extended_reading JSON 对象。",
             { !$0.extendedReading.isEmpty || ($0.heroImageUrl?.isEmpty == false) || ($0.diagram?.nodes.count ?? 0) >= 2 },
             12, 700, 90),
        ]

        var merged = initialOutput ?? IOSDeepReadOutput()
        var threwCount = 0
        var missingSections: [String] = []
        let stagesToRun = stages.filter { targetStages?.contains($0.label) ?? true }
        for (stageIndex, stage) in stagesToRun.enumerated() {
            let priorJSON = merged.hasStructuredBody ? (encodeStructured(merged) ?? "") : ""
            let stageSources = stageSourcesBlock(
                for: usable, stageLimit: stage.sourceLimit, excerptLimit: stage.excerptLimit, plan: plan
            )
            var stageError: String? = nil
            var parseFailed = false
            var stageSucceeded = false
            for attempt in 1...2 {
                var retryNote: String? = nil
                if attempt == 2 {
                    retryNote = stageError.map {
                        "上一次调用失败（\(String($0.prefix(160)))），请重新输出本阶段 JSON。"
                    } ?? (parseFailed
                        ? "上一次输出无法解析为合法 JSON。请直接输出本阶段字段的 JSON 对象，不要任何解释或 Markdown。"
                        : stage.retryNote)
                }
                let prompt = buildStagePrompt(
                    topicTitle: task.title, stageLabel: stage.label, instruction: stage.instruction,
                    schema: stage.schema, priorJSON: priorJSON, sourcesBlock: stageSources,
                    plan: plan, retryNote: retryNote
                )
                let (text, error) = await synthesizeJSON(
                    prompt: prompt, providerSetting: providerSetting, modelId: modelId, provider: provider,
                    timeoutSeconds: stageTimeouts?[stage.label] ?? stage.timeoutSeconds
                )
                stageError = error
                parseFailed = false
                if let error {
                    threwCount += 1
#if DEBUG
                    NSLog("[AmberDeepRead] stage=\(stage.label) attempt=\(attempt) threw: \(error.prefix(300))")
#endif
                    continue
                }
                var parsedOutput: IOSDeepReadOutput? = nil
                var repairedTruncation = false
                if let parsed = parseStageJSON(text), parsed.hasStructuredBody {
                    parsedOutput = parsed
                } else if let repaired = repairTruncatedJSON(text),
                          let parsed = parseStageJSON(repaired),
                          parsed.hasStructuredBody {
                    // The repaired JSON parsed: a later failure is a missing-fields
                    // problem, not a parse problem.
                    parsedOutput = parsed
                    repairedTruncation = true
                }
                if let parsed = parsedOutput {
                    // Gate before merge (Android writer-tool semantics): content that
                    // does not meet the stage minimums is not folded into the article.
                    if stage.fieldsPresent(parsed) {
                        merged = merged.merged(with: parsed)
                        stageSucceeded = true
#if DEBUG
                        if repairedTruncation {
                            NSLog("[AmberDeepRead] stage=\(stage.label) attempt=\(attempt) repaired truncated JSON")
                        }
#endif
                        break
                    }
                    parseFailed = false
#if DEBUG
                    NSLog("[AmberDeepRead] stage=\(stage.label) attempt=\(attempt) missing stage fields; chars=\(text.count) head=\(text.prefix(120))")
#endif
                } else {
                    parseFailed = true
#if DEBUG
                    NSLog("[AmberDeepRead] stage=\(stage.label) attempt=\(attempt) unparseable; chars=\(text.count) head=\(text.prefix(120))")
#endif
                }
            }
            if !stageSucceeded {
                missingSections.append(stage.label)
            }
            await onStageProgress?(stage.label, stageIndex + 1, stagesToRun.count)
        }
#if DEBUG
        if !missingSections.isEmpty {
            NSLog("[AmberDeepRead] missing sections after retries: \(missingSections.joined(separator: "、"))")
        }
#endif

        let date = IOSDeepReadDateFormatters.detail.string(from: now)
        // Honest failure only when nothing usable came back at all.
        let didFail = !merged.hasStructuredBody
        let reason: String
        if didFail && threwCount == stagesToRun.count * 2 {
            reason = "模型调用全部失败，请检查网络、API Key 或模型配置后重试。"
        } else if didFail {
            reason = "未能生成可用的深度阅读内容，请换个来源或模型后重试。"
        } else {
            reason = ""
        }

        let structuredJSON = merged.hasStructuredBody ? encodeStructured(merged) : nil
        let body = merged.hasStructuredBody
            ? markdownFromStructured(merged, title: task.title, date: date)
            : "# \(task.title)\n\n\(date)\n"
        return GenerationResult(
            markdown: body,
            didFail: didFail,
            failureReason: reason,
            structuredJSON: structuredJSON,
            missingSections: missingSections
        )
    }

    // MARK: - Stage JSON helpers

    private static func buildStagePrompt(topicTitle: String, stageLabel: String, instruction: String, schema: String, priorJSON: String, sourcesBlock: String, plan: IOSDeepReadArticlePlan? = nil, retryNote: String? = nil) -> String {
        var b = "你是 AmberAgent 的深度阅读编辑，分阶段生成高端 News 杂志风格的结构化深读稿。\n"
        b += "话题：\(topicTitle)\n当前阶段：\(stageLabel)\n\n"
        b += "## 阶段要求\n- \(instruction)\n"
        b += "- 输出合法 JSON 对象，不要代码围栏、不要前后解释。\n"
        b += "- 只输出本阶段新增字段；上一阶段字段不要重复输出，系统会自动合并。\n"
        b += "- 用户可见文本必须是简体中文；url 原样保留。\n"
        b += "- 不要输出 null；没有内容时用空字符串或空数组。\n\n"
        if let plan {
            b += "## Article Plan（本地规划，只读）\n"
            if !plan.overviewAngle.isEmpty { b += "- angle: \(plan.overviewAngle)\n" }
            if !plan.narrativeSlots.isEmpty { b += "- narrative_slots: \(plan.narrativeSlots.joined(separator: " / "))\n" }
            if !plan.analysisQuestions.isEmpty {
                b += "- analysis_questions:\n"
                plan.analysisQuestions.forEach { question in b += "  - \(question)\n" }
            }
            if !plan.stakeholders.isEmpty { b += "- stakeholders: \(plan.stakeholders.joined(separator: " / "))\n" }
            if !plan.riskOrUncertainty.isEmpty {
                b += "- risk_or_uncertainty:\n"
                plan.riskOrUncertainty.forEach { risk in b += "  - \(risk)\n" }
            }
            if !plan.requiredSourceIds.isEmpty { b += "- required_source_ids: \(plan.requiredSourceIds.map(String.init).joined(separator: ", "))\n" }
            b += "\n"
        }
        if !priorJSON.isEmpty {
            b += "## 上一阶段 JSON（已生成内容，仅供参考，不要重复输出）\n\(priorJSON.prefixString(4000))\n\n"
        }
        b += "## 本阶段 JSON 字段\n\(schema)\n\n"
        if let retryNote {
            b += "## 重试要求（上一次未通过）\n- \(retryNote)\n\n"
        }
        b += "## 来源\n\(sourcesBlock)\n"
        return b
    }

    private static func synthesizeJSON(prompt: String, providerSetting: ProviderSetting, modelId: String, provider: IOSAgentTextProvider, timeoutSeconds: Double = 150) async -> (text: String, error: String?) {
        let system = "你是 AmberAgent 的深度阅读结构化写作助手。只基于提供的来源写作，不编造，只输出合法 JSON 对象。"
        let messages = [
            UIMessage.companion.system(prompt: system),
            UIMessage.companion.user(prompt: prompt)
        ]
        let params = TextGenerationParams(
            model: Model(modelId: modelId, displayName: modelId, id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.3),
            topP: nil,
            maxTokens: KotlinInt(value: 3_500),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let request = DeepReadSynthesisRequest(
            providerSetting: providerSetting,
            messages: messages,
            params: params,
            provider: provider
        )
        do {
            let boxed = try await withTimeout(seconds: timeoutSeconds) {
                try await DeepReadChunkBox(chunk: request.provider.generateText(
                    providerSetting: request.providerSetting,
                    messages: request.messages,
                    params: request.params
                ))
            }
            let chunk = boxed.chunk
            let text = (chunk.choices.first?.message?.parts ?? [])
                .compactMap { $0 as? UIMessagePart.Text }
                .map { $0.text }
                .joined(separator: "")
            return (text.trimmingCharacters(in: .whitespacesAndNewlines), nil)
        } catch {
            // Swift-native LocalizedError structs do not surface errorDescription
            // through the NSError-bridged `localizedDescription`; read it first so
            // the stage retry note can name the real reason (e.g. 超时).
            let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return ("", message)
        }
    }

    // MARK: - Planning (Android generateArticlePlan parity)

    private static let overviewSummaryMinChars = 24
    private static let planTimeoutSeconds: Double = 120

    private static func synthesizePlan(
        topicTitle: String,
        usableSources: [IOSDeepReadSource],
        providerSetting: ProviderSetting,
        modelId: String,
        provider: IOSAgentTextProvider,
        timeoutSeconds: Double
    ) async -> IOSDeepReadArticlePlan {
        let fallback = fallbackPlan(topicTitle: topicTitle, usableSources: usableSources)
        let prompt = buildPlanningPrompt(topicTitle: topicTitle, usableSources: usableSources)
        let (text, error) = await synthesizeJSON(
            prompt: prompt, providerSetting: providerSetting, modelId: modelId,
            provider: provider, timeoutSeconds: timeoutSeconds
        )
        if error != nil {
#if DEBUG
            NSLog("[AmberDeepRead] plan fell back to local plan: \(error?.prefix(200) ?? "")")
#endif
            return fallback
        }
        guard let raw = extractJSONObject(text),
              let parsed = try? JSONDecoder().decode(IOSDeepReadArticlePlan.self, from: Data(raw.utf8)) else {
#if DEBUG
            NSLog("[AmberDeepRead] plan unparseable, fell back to local plan; chars=\(text.count) head=\(text.prefix(120))")
#endif
            return fallback
        }
        return parsed.normalized(with: fallback, sourceCount: usableSources.count)
    }

    /// Deterministic local plan used when the planning call fails — mirrors
    /// Android's `DeepReadResearchHarness.fallbackPlan` wording.
    private static func fallbackPlan(topicTitle: String, usableSources: [IOSDeepReadSource]) -> IOSDeepReadArticlePlan {
        var plan = IOSDeepReadArticlePlan()
        plan.overviewAngle = "从已核查来源解释「\(topicTitle)」发生了什么、为什么值得读，以及哪些结论仍需保守表达。"
        plan.narrativeSlots = [
            "背景和直接触发因素",
            "关键进展或时间线",
            "当前状态与后续观察点",
        ]
        plan.analysisQuestions = [
            "核心矛盾是什么，各方到底在争什么？",
            "这件事会影响哪些用户、公司、行业或公共议题？",
            "有哪些反方证据、不确定点或互相矛盾的说法需要降格表达？",
        ]
        plan.riskOrUncertainty = [
            "来源之间未互相印证的事实不得写成定论。",
            "没有来源支撑的价格、时间、人物表态、因果关系需要跳过或标注为不确定。",
        ]
        plan.requiredSourceIds = Array(1...usableSources.count)
        return plan
    }

    private static func buildPlanningPrompt(topicTitle: String, usableSources: [IOSDeepReadSource]) -> String {
        var b = "你是 AmberAgent 深度阅读的结构规划器。\n"
        b += "只输出合法 JSON，不要 Markdown、不要代码围栏、不要解释。\n"
        b += "话题：\(topicTitle)\n\n"
        b += "## 可用来源（编号是 required_source_ids 的取值）\n"
        for (index, source) in usableSources.enumerated() {
            let excerpt = IOSDeepReadSourceNormalizer.cleanMultiline(source.content).prefixString(300)
            b += "- [\(index + 1)] \(source.kind.title)｜\(source.title)\n"
            if let url = source.url, !url.isEmpty { b += "  url: \(url)\n" }
            b += "  excerpt: \(excerpt)\n"
        }
        b += "\n## 输出 JSON Schema\n"
        b += #"{"overview_angle":"一两句话说明文章角度","narrative_slots":["必须覆盖的叙事槽位"],"analysis_questions":["必须回答的分析问题"],"stakeholders":["相关方"],"risk_or_uncertainty":["风险、不确定点或反方证据"],"required_source_ids":[1,2]}"#
        b += "\n\n要求：\n"
        b += "- required_source_ids 用上面的编号，尽量覆盖所有可用来源。\n"
        b += "- analysis_questions 必须覆盖核心矛盾、影响链条、反方证据或不确定点。\n"
        b += "- 不要创造不存在的来源编号。\n"
        return b
    }

    /// Per-stage source bucketing (Android `cardsFor(stage:)` parity): the plan's
    /// required ids come first, then the remaining sources in original order,
    /// capped at the stage's source count and per-source excerpt limit.
    private static func stageSourcesBlock(
        for sources: [IOSDeepReadSource],
        stageLimit: Int,
        excerptLimit: Int,
        plan: IOSDeepReadArticlePlan
    ) -> String {
        let required = Set(plan.requiredSourceIds)
        let ordered = sources.enumerated()
            .map { (index: $0.offset + 1, source: $0.element) }
            .sorted { a, b in
                let aRequired = required.contains(a.index)
                let bRequired = required.contains(b.index)
                if aRequired != bRequired { return aRequired && !bRequired }
                return a.index < b.index
            }
        return ordered.prefix(stageLimit).map { entry in
            var lines = "[\(entry.index)] \(entry.source.kind.title)｜\(entry.source.title)"
            if let url = entry.source.url, !url.isEmpty { lines += "\n- url: \(url)" }
            if let image = entry.source.metadata["hero_image_url"], !image.isEmpty {
                lines += "\n- images: \(image)"
            }
            lines += "\n- excerpt: \(IOSDeepReadSourceNormalizer.cleanMultiline(entry.source.content).prefixString(excerptLimit))"
            return lines
        }.joined(separator: "\n\n")
        .prefixString(9_000)
    }

    // MARK: - Timeout (Android withTimeout parity)

    private struct DeepReadSynthesisRequest: @unchecked Sendable {
        let providerSetting: ProviderSetting
        let messages: [UIMessage]
        let params: TextGenerationParams
        let provider: IOSAgentTextProvider
    }

    private struct DeepReadChunkBox: @unchecked Sendable {
        let chunk: MessageChunk
    }

    private struct IOSDeepReadStageTimeoutError: Error, LocalizedError {
        let seconds: Int
        var errorDescription: String? { "阶段超时（\(seconds) 秒预算用尽）" }
    }

    private static func withTimeout<T: Sendable>(seconds: Double, _ body: @escaping @Sendable () async throws -> T) async throws -> T {
        // Result-based group so cancelled children can never throw at scope exit
        // and replace the winner's value.
        let outcome: Result<T, Error> = await withTaskGroup(of: Result<T, Error>.self) { group in
            group.addTask {
                do { return .success(try await body()) } catch { return .failure(error) }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(0.05, seconds) * 1_000_000_000))
                if Task.isCancelled { return .failure(CancellationError()) }
                return .failure(IOSDeepReadStageTimeoutError(seconds: Int(seconds.rounded())))
            }
            guard let first = await group.next() else {
                return .failure(IOSDeepReadStageTimeoutError(seconds: Int(seconds.rounded())))
            }
            group.cancelAll()
            return first
        }
        return try outcome.get()
    }

    static func parseStageJSON(_ text: String) -> IOSDeepReadOutput? {
        guard let json = extractJSONObject(text) else { return nil }
        return try? JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
    }

    /// Best-effort repair for JSON cut off by max-token truncation: balances
    /// unclosed braces/brackets, closes an unterminated string and strips a
    /// dangling comma before each closer it appends. Returns nil when the text
    /// is already balanced (nothing to repair) or contains no object at all.
    /// Repaired output can still fail to decode (e.g. a truncated key without a
    /// value) — the caller then falls back to the stage retry.
    static func repairTruncatedJSON(_ text: String) -> String? {
        let chars = Array(text)
        guard let start = chars.firstIndex(of: "{") else { return nil }
        var stack: [Character] = []
        var inString = false
        var escaped = false
        var index = start
        while index < chars.count {
            let c = chars[index]
            if inString {
                if escaped { escaped = false }
                else if c == "\\" { escaped = true }
                else if c == "\"" { inString = false }
            } else if c == "\"" {
                inString = true
            } else if c == "{" {
                stack.append("}")
            } else if c == "}" {
                if stack.last == "}" { stack.removeLast() }
            } else if c == "[" {
                stack.append("]")
            } else if c == "]" {
                if stack.last == "]" { stack.removeLast() }
            }
            index += 1
        }
        guard inString || !stack.isEmpty else { return nil }
        var body = String(chars[start...])
        var suffix = ""
        if inString { suffix += "\"" }
        for closer in stack.reversed() {
            if let last = body.last, last == "," { body.removeLast() }
            suffix.append(closer)
        }
        return body + suffix
    }

    /// First balanced top-level {...} object (ignores braces inside strings), so a
    /// response wrapped in ```json fences or surrounding prose still parses.
    static func extractJSONObject(_ text: String) -> String? {
        let chars = Array(text)
        guard let start = chars.firstIndex(of: "{") else { return nil }
        var depth = 0, inString = false, escaped = false
        var i = start
        while i < chars.count {
            let c = chars[i]
            if inString {
                if escaped { escaped = false }
                else if c == "\\" { escaped = true }
                else if c == "\"" { inString = false }
            } else if c == "\"" {
                inString = true
            } else if c == "{" {
                depth += 1
            } else if c == "}" {
                depth -= 1
                if depth == 0 { return String(chars[start...i]) }
            }
            i += 1
        }
        return nil
    }

    static func encodeStructured(_ output: IOSDeepReadOutput) -> String? {
        guard let data = try? JSONEncoder().encode(output) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func markdownFromStructured(_ o: IOSDeepReadOutput, title: String, date: String) -> String {
        var b = "# \(title)\n\n\(date)\n"
        if !o.summary.isEmpty { b += "\n## 摘要\n\(o.summary)\n" }
        if !o.timeline.isEmpty {
            b += "\n## 时间轴\n"
            for e in o.timeline { b += "- **\(e.date)** \(e.event)\n" }
        }
        if !o.corePoints.isEmpty {
            b += "\n## 关键脉络\n"
            for p in o.corePoints { b += "- **\(p.point)**" + (p.supporting.map { "：\($0)" } ?? "") + "\n" }
        }
        if o.analysis.hasContent {
            b += "\n## 深度分析\n"
            if let d = o.analysis.coreDispute, !d.isEmpty { b += "> \(d)\n\n" }
            for p in o.analysis.perspectives where !p.viewpoint.isEmpty {
                b += "- **\(p.holder ?? "")**：\(p.viewpoint)\n"
            }
            for q in o.analysis.quotes where !q.text.isEmpty {
                b += "> “\(q.text)”"
                if let attribution = q.attribution, !attribution.isEmpty { b += " —— \(attribution)" }
                b += "\n\n"
            }
            if let imp = o.analysis.implications, !imp.isEmpty { b += "\n\(imp)\n" }
        }
        if !o.extendedReading.isEmpty {
            b += "\n## 扩展阅读\n"
            for l in o.extendedReading { b += "- [\(l.title)](\(l.url))\n" }
        }
        if !o.references.isEmpty {
            b += "\n## 参考来源\n"
            for l in o.references { b += "- [\(l.title)](\(l.url))\n" }
        }
        return b
    }

    /// Retry-generation decision as a testable static seam: given the already-
    /// resolved (non-optional) provider — the caller resolves it on the MainActor
    /// via `resolveProviderSetting` and passes the unwrapped fresh value in,
    /// mirroring the create path's concurrency shape so it can cross the async
    /// boundary without a data race — runs the staged pipeline and maps the
    /// result to a terminal `DeepReadOutcome` (didFail → `.failed`, else a
    /// completed draft). The caller handles the no-provider/no-key offline
    /// fallback. The decision lives here so it is unit-tested end-to-end (closes
    /// the deepread retry-path dual leak: provider_real + honest_fail).
    static func retryOutcome(
        resolvedProvider: ProviderSetting,
        modelId: String,
        task: IOSDeepReadTask,
        provider: IOSAgentTextProvider = OpenAIKmpProviderAdapter(),
        now: Date = Date()
    ) async -> DeepReadOutcome {
        let result = await generateViaLLMResult(
            task: task,
            providerSetting: resolvedProvider,
            modelId: modelId,
            provider: provider,
            now: now
        )
        return outcome(for: result, offlineFallback: generate(task: task, now: now))
    }

}

enum IOSDeepReadDateFormatters {
    static let detail: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter
    }()
}

private extension String {
    var ifBlankNil: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func ifEmpty(_ fallback: String) -> String {
        isEmpty ? fallback : self
    }

    func prefixString(_ limit: Int) -> String {
        guard count > limit else { return self }
        return String(prefix(limit))
    }
}
