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
            return IOSBoardCollectorOutput(signals: signals, statusMessage: "时间锚点")
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

/// 36Kr RSS feed.
struct IOSKr36HotlistProvider: IOSHotlistProvider {
    let providerId = "36kr"
    let displayName = "36 氪"
    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        try await IOSRSSHotlistProvider(
            providerId: providerId,
            displayName: displayName,
            feedURL: "https://36kr.com/feed"
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

/// All built-in iOS hotlist providers (Android BuiltInHotListProviders parity
/// for the providers with stable public endpoints).
enum IOSHotlistProviders {
    struct Descriptor: Identifiable, Codable, Equatable, Sendable {
        var id: String { providerId }
        var providerId: String
        var displayName: String
    }

    static let all: [IOSHotlistProvider] = [
        IOSHackerNewsHotlistProvider(),
        IOSArxivAIHotlistProvider(),
        IOSInfoqAIHotlistProvider(),
        IOSKr36HotlistProvider(),
        IOSHuggingFacePapersHotlistProvider(),
        IOSGithubTrendingHotlistProvider()
    ]

    static var descriptors: [Descriptor] {
        all.map { Descriptor(providerId: $0.providerId, displayName: $0.displayName) }
    }

    static let iOSDefaultProviderIds: Set<String> = Set(all.map(\.providerId))
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

    func refresh(setting: TodayBoardSetting, force: Bool = true, limit: Int = 20) async {
        guard !isRefreshing else { return }
        let enabledIds = IOSHotlistProviders.effectiveEnabledProviderIds(setting: setting)
        guard !enabledIds.isEmpty else {
            dashboard = IOSHotListDashboard(topics: [], providers: [], lastUpdatedAt: 0, enabledSourceCount: 0)
            lastError = nil
            persist()
            return
        }
        if !force, !shouldRefresh(setting: setting) {
            dashboard = filteredDashboard(dashboard, setting: setting)
            return
        }

        isRefreshing = true
        lastError = nil
        defer { isRefreshing = false }

        let previous = Dictionary(uniqueKeysWithValues: dashboard.providers.map { ($0.providerId, $0) })
        let now = IOSBoardSignalRepository.currentEpochMs()
        var snapshots: [IOSHotListProviderSnapshot] = []
        for provider in IOSHotlistProviders.all where enabledIds.contains(provider.providerId) {
            do {
                let items = try await provider.fetch(limit: limit)
                snapshots.append(IOSHotListProviderSnapshot(
                    providerId: provider.providerId,
                    providerName: provider.displayName,
                    items: items,
                    fetchedAt: now
                ))
            } catch {
                // Refresh interrupted (the view's `.task` is cancelled on
                // navigation, and sources are fetched sequentially) — abandon
                // without overwriting the dashboard, so the not-yet-fetched
                // sources don't all show a spurious "cancelled" failure.
                if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                    return
                }
                let cached = previous[provider.providerId]?.items ?? []
                snapshots.append(IOSHotListProviderSnapshot(
                    providerId: provider.providerId,
                    providerName: provider.displayName,
                    items: cached,
                    fetchedAt: previous[provider.providerId]?.fetchedAt ?? now,
                    stale: !cached.isEmpty,
                    error: error.localizedDescription
                ))
            }
        }

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

    static func isValidTemplateId(_ id: String) -> Bool {
        let normalized = normalizedTemplateId(id)
        return normalized.hasPrefix(customPrefix) || builtIns.contains(where: { $0.id == normalized }) || normalized == analysis.id
    }
}

struct IOSDeepReadTemplateValidationResult: Equatable, Sendable {
    var ok: Bool
    var error: String?

    static let valid = IOSDeepReadTemplateValidationResult(ok: true, error: nil)
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
            (#"(?is)<\s*link\b[^>]*\bhref\s*=\s*['"]?\s*(https?:|//|file:|content:)"#, "模板不允许外部样式表。"),
            (#"(?is)\bhref\s*=\s*(?:['"]\s*)?(https?:|//|file:|content:)"#, "模板不允许硬编码外部链接。"),
            (#"(?is)\bsrc\s*=\s*(?:['"]\s*)?(https?:|//|file:|content:)"#, "模板不允许硬编码外部资源。"),
            (#"(?is)@import\b"#, "模板不允许 CSS import。"),
            (#"(?is)url\s*\("#, "模板不允许 CSS URL。"),
            (#"(?is)\b(fetch|XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB|eval)\b"#, "模板不允许浏览器 API。")
        ]
        for (pattern, message) in blocked where html.range(of: pattern, options: .regularExpression) != nil {
            return .init(ok: false, error: message)
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
            return reason
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
            return IOSDeepReadSource(
                kind: .searchResult,
                title: result.title.ifEmpty("搜索结果 \(index + 1)"),
                content: content,
                url: result.url,
                metadata: [
                    "query": cleanQuery,
                    "rank": "\(index + 1)"
                ],
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

    func complete(id: String, markdown: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.status = .succeeded
            task.resultMarkdown = markdown
            task.failureMessage = nil
            task.updatedAt = now
            task.completedAt = now
        }
    }

    func fail(id: String, message: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.status = .failed
            task.failureMessage = message.prefixString(500)
            task.updatedAt = now
        }
    }

    func markUnsupported(id: String, message: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.status = .unsupported
            task.failureMessage = message.prefixString(500)
            task.updatedAt = now
        }
    }

    func prepareRetry(id: String, now: Int64 = IOSBoardSignalRepository.currentEpochMs()) {
        update(id: id) { task in
            task.status = .queued
            task.failureMessage = nil
            task.retryCount += 1
            task.updatedAt = now
        }
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
        let template = task.template
        let sources = task.sources
        let totalCharacters = sources.reduce(0) { $0 + $1.content.count }
        let date = IOSDeepReadDateFormatters.detail.string(from: now)
        let grouped = Dictionary(grouping: sources, by: \.kind)

        var lines: [String] = []
        lines.append("# \(task.title)")
        lines.append("")
        lines.append("版式：\(template.name) · 来源：\(sources.count) 个 · 字符：\(totalCharacters) · \(date)")
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
    }

    /// Terminal outcome of a deep-read run, decoupled from the caller's
    /// side-effects (navigation/banner). Both the create and retry view paths
    /// route their `GenerationResult` through `outcome(for:offlineFallback:)`,
    /// so the "didFail → .failed, else → completed draft" decision is a single
    /// unit-tested source of truth — a caller can no longer silently drop
    /// `didFail` and mark an all-failed run succeeded (closes the
    /// deepread.honest_fail caller-honoring gap on BOTH surfaces).
    enum DeepReadOutcome: Equatable {
        case completed(markdown: String)
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
        return .completed(markdown: result.markdown.isEmpty ? offlineFallback : result.markdown)
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
        now: Date = Date()
    ) async -> GenerationResult {
        // Exclude failed-search sources (scrape_status=failed) from the factual
        // block: their "content" is just an error message, so feeding it to the
        // model as a source would be dishonest. The marker makes the failure a
        // real source-failure state, not silent noise.
        let sourcesBlock = task.sources
            .filter { $0.metadata["scrape_status"] != "failed" }
            .enumerated().map { index, source in
                "[\(index + 1)] \(source.kind.title)｜\(source.title)\n\(IOSDeepReadSourceNormalizer.cleanMultiline(source.content).prefixString(1200))"
            }.joined(separator: "\n\n")

        var sections: [String] = []
        var threwCount = 0
        var emptyCount = 0

        // Stage 1: overview.
        let overview = await synthesize(
            stage: "overview",
            instruction: "基于以下来源写一段简洁的总体摘要（3-5 句），点明主题、关键事实和结论方向。不要编造来源中没有的信息。",
            sourcesBlock: sourcesBlock,
            priorDraft: "",
            providerSetting: providerSetting,
            modelId: modelId,
            provider: provider
        )
        if overview.threw { threwCount += 1 } else if overview.text.isEmpty { emptyCount += 1 }
        sections.append("## 摘要\n\(overview.text)")

        // Stage 2: narrative (seeded with the overview).
        let narrative = await synthesize(
            stage: "narrative",
            instruction: "基于摘要和来源，写出脉络叙述（按时间或逻辑组织，引用来源编号 [n]）。保留事实边界，不要编造。",
            sourcesBlock: sourcesBlock,
            priorDraft: overview.text,
            providerSetting: providerSetting,
            modelId: modelId,
            provider: provider
        )
        if narrative.threw { threwCount += 1 } else if narrative.text.isEmpty { emptyCount += 1 }
        sections.append("## 脉络\n\(narrative.text)")

        // Stage 3: analysis (seeded with overview + narrative).
        let analysis = await synthesize(
            stage: "analysis",
            instruction: "对前面的摘要与脉络做分析：矛盾点、风险、缺失的来源、以及下一步建议。分条列出。",
            sourcesBlock: sourcesBlock,
            priorDraft: "\(overview.text)\n\n\(narrative.text)",
            providerSetting: providerSetting,
            modelId: modelId,
            provider: provider
        )
        if analysis.threw { threwCount += 1 } else if analysis.text.isEmpty { emptyCount += 1 }
        sections.append("## 分析\n\(analysis.text)")

        // Stage 4: extended reading (seeded with overview + narrative + analysis).
        // Parity with Android's EXTENDED_READING stage — a real 4th model call,
        // not a static placeholder.
        let extended = await synthesize(
            stage: "extended_reading",
            instruction: "基于前面的摘要、脉络与分析，整理「扩展阅读」：列出最值得进一步追踪的角度或线索，并对照来源说明可继续了解的方向（引用来源编号 [n]）。只用来源覆盖到的信息，不要编造链接或事实。",
            sourcesBlock: sourcesBlock,
            priorDraft: "\(overview.text)\n\n\(narrative.text)\n\n\(analysis.text)",
            providerSetting: providerSetting,
            modelId: modelId,
            provider: provider
        )
        if extended.threw { threwCount += 1 } else if extended.text.isEmpty { emptyCount += 1 }
        sections.append("## 扩展阅读\n\(extended.text)")

        let date = IOSDeepReadDateFormatters.detail.string(from: now)
        let header = "# \(task.title)\n\n版式：\(task.template.name) · 来源：\(task.sources.count) 个 · \(date)\n（由模型分阶段生成）\n"
        let body = header + sections.joined(separator: "\n\n")

        // Honest failure: ALL 4 stages either threw or were empty. A single
        // usable stage is enough to surface a (partial) completed draft.
        let allStagesUnusable = (threwCount + emptyCount) == 4
        let didFail = allStagesUnusable
        let reason: String
        if threwCount == 4 {
            reason = "所有阶段调用失败（provider 抛错）"
        } else if emptyCount == 4 {
            reason = "所有阶段未产出可用内容（输出为空）"
        } else if allStagesUnusable {
            reason = "所有阶段失败或为空（threw=\(threwCount), empty=\(emptyCount)）"
        } else {
            reason = ""
        }
        return GenerationResult(markdown: body, didFail: didFail, failureReason: reason)
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

    /// One LLM synthesis call for a single stage. Returns the model text, or an
    /// One LLM synthesis call for a single stage. Returns the model text plus
    /// whether the call threw (`threw`). On throw, text is "" — the caller's
    /// honest-fail accounting treats threw+empty as an unusable stage.
    private static func synthesize(
        stage: String,
        instruction: String,
        sourcesBlock: String,
        priorDraft: String,
        providerSetting: ProviderSetting,
        modelId: String,
        provider: IOSAgentTextProvider
    ) async -> (text: String, threw: Bool) {
        let system = "你是 AmberAgent 的深度阅读写作助手。只基于提供的来源写作，不编造。"
        var user = "\(instruction)\n\n来源：\n\(sourcesBlock)"
        if !priorDraft.isEmpty {
            user += "\n\n上一阶段产出：\n\(priorDraft)"
        }
        let messages = [
            UIMessage.companion.system(prompt: system),
            UIMessage.companion.user(prompt: user)
        ]
        let params = TextGenerationParams(
            model: Model(modelId: modelId, displayName: modelId, id: KotlinUuid.companion.random(), type: ModelType.chat, customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [], abilities: [], tools: Set<BuiltInTools>(), contextWindowTokens: nil, providerOverwrite: nil),
            temperature: KotlinFloat(value: 0.4),
            topP: nil,
            maxTokens: KotlinInt(value: 1_500),
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        do {
            let chunk = try await provider.generateText(providerSetting: providerSetting, messages: messages, params: params)
            let text = (chunk.choices.first?.message?.parts ?? [])
                .compactMap { $0 as? UIMessagePart.Text }
                .map { $0.text }
                .joined(separator: "")
            return (text.trimmingCharacters(in: .whitespacesAndNewlines), false)
        } catch {
            return ("", true)
        }
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
