import CryptoKit
import Foundation
import Observation
@preconcurrency import Shared
#if canImport(EventKit)
@preconcurrency import EventKit
#endif

/// [Board MVP] iOS-local persistence for the generated "今日看板内容" (Markdown).
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

    func clearAll() {
        records = []
        try? fileManager.removeItem(at: fileURL)
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

// MARK: - Time Collector

final class IOSKMPTimeSignalCollector: IOSBoardSignalCollector {
    let sourceType = IOSBoardSignalSourceType.time

    private let setting: TodayBoardSetting

    init(setting: TodayBoardSetting) {
        self.setting = setting
    }

    func collect(limit: Int) async -> IOSBoardCollectorOutput {
        do {
            let factory = IosBoardFactory.shared
            let context = factory.createTimeCollectContext(
                assistantId: "ios-board-signal-repository",
                anchorTime: 0,
                limit: Int32(limit)
            )
            let collectors = factory.createCollectors(setting: setting)
            var signals: [IOSRawBoardSignal] = []
            for collector in collectors where collector.sourceType == sourceType {
                let collected = try await collectSignals(collector: collector, context: context)
                signals.append(contentsOf: collected.map(IOSRawBoardSignal.init(signal:)))
            }
            return IOSBoardCollectorOutput(signals: signals, statusMessage: "KMP 时间锚点")
        } catch {
            return IOSBoardCollectorOutput(errorMessage: "时间锚点采集失败：\(error.localizedDescription)")
        }
    }

    private func collectSignals(
        collector: BoardSignalCollectorInterface,
        context: BoardCollectContext
    ) async throws -> [BoardSignal] {
        try await withCheckedThrowingContinuation { continuation in
            collector.collect(context: context) { signals, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: signals ?? [])
                }
            }
        }
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

struct IOSHotlistItem: Equatable, Sendable {
    var providerId: String
    var title: String
    var url: String?
    var rank: Int
    var score: Int?
    var fetchedAt: Int64
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

// MARK: - Foreground Refresh

struct IOSBoardForegroundRefreshScheduler: Sendable {
    static func shouldRunForegroundRefresh(lastRunAt: Int64?, now: Int64, gapMs: Int64) -> Bool {
        guard let lastRunAt else { return true }
        return now - lastRunAt >= max(gapMs, 60_000)
    }

    static func nextAnchorDate(triggerHours: [String], now: Date = Date(), calendar: Calendar = .current) -> Date? {
        let slots = triggerHours.compactMap { raw -> Date? in
            let parts = raw.split(separator: ":")
            guard parts.count == 2,
                  let hour = Int(parts[0]),
                  let minute = Int(parts[1]),
                  hour >= 0, hour <= 23, minute >= 0, minute <= 59 else {
                return nil
            }
            var components = calendar.dateComponents([.year, .month, .day], from: now)
            components.hour = hour
            components.minute = minute
            return calendar.date(from: components)
        }.sorted()
        guard let first = slots.first else { return nil }
        if let laterToday = slots.first(where: { $0 > now }) {
            return laterToday
        }
        return calendar.date(byAdding: .day, value: 1, to: first)
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
