import XCTest
@preconcurrency import Shared
@testable import iosApp

/// [Board MVP] Verifies the 今日看板内容 persistence: save a generated board
/// Markdown by date → load it back (survives a fresh load = restart simulation)
/// → redisplay the most recent. Scope = content only (no task-flow entities).
@MainActor
final class IOSBoardPersistenceTests: XCTestCase {

    private var persistence: IOSBoardPersistence { .shared }

    override func tearDown() async throws {
        // Clean the boards dir so tests don't bleed.
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        let dir = docs?.appendingPathComponent("boards", isDirectory: true)
        if let dir, FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.removeItem(at: dir)
        }
    }

    func testSaveThenLoadRoundTripsBoardContent() {
        let date = "2026-06-18"
        let board = IOSBoardPersistence.PersistedBoard(
            boardDate: date,
            markdown: "# 今日看板\n- 完成报告\n- 开会 14:00",
            signalCount: 7,
            generatedAt: 1_750_000_000_000
        )
        persistence.save(board: board)

        let loaded = persistence.load(boardDate: date)
        XCTAssertEqual(loaded?.boardDate, date)
        XCTAssertEqual(loaded?.markdown, "# 今日看板\n- 完成报告\n- 开会 14:00")
        XCTAssertEqual(loaded?.signalCount, 7)
    }

    func testLoadMissingBoardReturnsNil() {
        XCTAssertNil(persistence.load(boardDate: "1999-01-01"), "missing board must be nil (honest empty)")
    }

    func testSaveOverwritesSameDate() {
        let date = persistence.todayBoardDate()
        persistence.save(board: .init(boardDate: date, markdown: "v1", signalCount: 1, generatedAt: 1))
        persistence.save(board: .init(boardDate: date, markdown: "v2", signalCount: 2, generatedAt: 2))

        let loaded = persistence.load(boardDate: date)
        XCTAssertEqual(loaded?.markdown, "v2", "same-date save must overwrite (today is always freshest)")
        XCTAssertEqual(loaded?.signalCount, 2)
    }

    func testLoadMostRecentPicksNewestAcrossDates() {
        persistence.save(board: .init(boardDate: "2026-06-16", markdown: "older", signalCount: 1, generatedAt: 1_000))
        persistence.save(board: .init(boardDate: "2026-06-18", markdown: "newer", signalCount: 3, generatedAt: 3_000))
        persistence.save(board: .init(boardDate: "2026-06-17", markdown: "middle", signalCount: 2, generatedAt: 2_000))

        let recent = persistence.loadMostRecent()
        XCTAssertEqual(recent?.boardDate, "2026-06-18", "loadMostRecent must pick the newest by generatedAt, not date string")
        XCTAssertEqual(recent?.markdown, "newer")
    }

    func testLoadMostRecentNilWhenEmpty() {
        XCTAssertNil(persistence.loadMostRecent(), "empty boards dir must return nil (honest)")
    }

    func testDeleteRemovesBoard() {
        let date = "2026-06-18"
        persistence.save(board: .init(boardDate: date, markdown: "x", signalCount: 0, generatedAt: 1))
        XCTAssertNotNil(persistence.load(boardDate: date))
        persistence.delete(boardDate: date)
        XCTAssertNil(persistence.load(boardDate: date), "delete must remove the board file")
    }

    func testTodayBoardDateIsYYYYMMdd() {
        let d = persistence.todayBoardDate()
        XCTAssertEqual(d.count, 10, "board date must be yyyy-MM-dd (10 chars)")
        XCTAssertEqual(d.filter { $0 == "-" }.count, 2)
    }

    func testSignalStorePersistsAndRestoresRecords() {
        let base = makeTempBase()
        defer { try? FileManager.default.removeItem(at: base) }
        let repo = IOSBoardSignalRepository(baseDirectory: base)

        let raw = rawSignal(sourceType: IOSBoardSignalSourceType.calendar, sourceRef: "event-1")
        guard case .saved(let saved) = repo.ingest(raw, now: 10_000) else {
            return XCTFail("first ingest should save")
        }

        let restarted = IOSBoardSignalRepository(baseDirectory: base)
        XCTAssertEqual(restarted.records.count, 1)
        XCTAssertEqual(restarted.records.first?.id, saved.id)
        XCTAssertEqual(restarted.records.first?.sourceRef, "event-1")
    }

    func testSignalStoreDedupsBySourceRef() {
        let repo = IOSBoardSignalRepository(baseDirectory: makeTempBase())
        let first = rawSignal(sourceType: IOSBoardSignalSourceType.calendar, sourceRef: "event-1", title: "Design review")
        let second = rawSignal(sourceType: IOSBoardSignalSourceType.calendar, sourceRef: "event-1", title: "Changed title")

        guard case .saved = repo.ingest(first, now: 10_000) else {
            return XCTFail("first ingest should save")
        }
        guard case .duplicateSourceRef = repo.ingest(second, now: 11_000) else {
            return XCTFail("second ingest with same sourceRef should dedup")
        }
        XCTAssertEqual(repo.records.count, 1)
    }

    func testSignalStoreDedupsByContentHashWithinWindow() {
        let repo = IOSBoardSignalRepository(baseDirectory: makeTempBase())
        let first = rawSignal(sourceType: IOSBoardSignalSourceType.chatHistory, sourceRef: "chat-1", title: "TODO follow up")
        let second = rawSignal(sourceType: IOSBoardSignalSourceType.chatHistory, sourceRef: "chat-2", title: "TODO follow up")

        guard case .saved = repo.ingest(first, now: 10_000) else {
            return XCTFail("first ingest should save")
        }
        guard case .duplicateContentHash = repo.ingest(second, now: 11_000) else {
            return XCTFail("same source/content hash should dedup")
        }
        XCTAssertEqual(repo.records.count, 1)
    }

    func testProcessedMarkAndPrune() {
        let repo = IOSBoardSignalRepository(baseDirectory: makeTempBase())
        guard case .saved(let first) = repo.ingest(rawSignal(sourceRef: "a"), now: 1_000) else {
            return XCTFail("first save")
        }
        guard case .saved = repo.ingest(
            rawSignal(sourceRef: "b", title: "TODO follow up B", content: "Need to follow up a different project decision."),
            now: 2_000
        ) else {
            return XCTFail("second save")
        }

        repo.markSignalsProcessed(ids: [first.id], now: 3_000)
        XCTAssertEqual(repo.countUnprocessedSignals(), 1)
        XCTAssertEqual(repo.records.first(where: { $0.id == first.id })?.processedAt, 3_000)

        let pruned = repo.pruneProcessedSignals(olderThanMs: 4_000)
        XCTAssertEqual(pruned, 1)
        XCTAssertEqual(repo.records.map(\.sourceRef), ["b"])
    }

    func testChatHistoryCollectorFiltersActionableRecentConversations() async {
        let now: Int64 = 1_800_000_000_000
        let source = FakeConversationSignalSource(candidates: [
            .init(
                id: "chat-action",
                title: "项目上线 TODO",
                updateAt: now,
                nodeCount: 5,
                tailTexts: ["user: 明天会议前需要跟进 bug 修复", "assistant: 已列出 action item"]
            ),
            .init(
                id: "chat-test",
                title: "测试 prompt",
                updateAt: now,
                nodeCount: 12,
                tailTexts: ["user: 流式渲染长文测试 prompt"]
            ),
            .init(
                id: "chat-old",
                title: "客户合同跟进",
                updateAt: now - 72 * 60 * 60 * 1_000,
                nodeCount: 6,
                tailTexts: ["user: 需要跟进合同"]
            )
        ])
        let collector = IOSChatHistorySignalCollector(source: source, nowProvider: { now })

        let output = await collector.collect(limit: 10)
        XCTAssertEqual(output.signals.count, 1)
        XCTAssertEqual(output.signals.first?.sourceRef, "chat-action")
        XCTAssertEqual(output.signals.first?.sourceType, IOSBoardSignalSourceType.chatHistory)
        XCTAssertTrue(output.signals.first?.metadataJson.contains("\"relevance\"") == true)
    }

    func testEventKitCollectorsUseMockAdapterAndReportPermissionEmptyState() async {
        let allowedAdapter = MockEventKitAdapter(
            calendarStatus: .authorized,
            reminderStatus: .authorized,
            calendarResult: .init(signals: [
                rawSignal(sourceType: IOSBoardSignalSourceType.calendar, sourceRef: "event-1", title: "Team meeting")
            ]),
            reminderResult: .init(signals: [
                rawSignal(sourceType: IOSBoardSignalSourceType.reminder, sourceRef: "reminder-1", title: "Submit report")
            ])
        )

        let calendarOutput = await IOSEventKitCalendarSignalCollector(adapter: allowedAdapter).collect(limit: 10)
        XCTAssertEqual(calendarOutput.signals.map(\.sourceRef), ["event-1"])

        let reminderOutput = await IOSEventKitReminderSignalCollector(adapter: allowedAdapter).collect(limit: 10)
        XCTAssertEqual(reminderOutput.signals.map(\.sourceRef), ["reminder-1"])

        let deniedAdapter = MockEventKitAdapter(calendarStatus: .notDetermined, reminderStatus: .denied)
        let deniedOutput = await IOSEventKitCalendarSignalCollector(adapter: deniedAdapter).collect(limit: 10)
        XCTAssertTrue(deniedOutput.signals.isEmpty)
        XCTAssertNotNil(deniedOutput.errorMessage)
    }

    func testHotlistCollectorUsesMockProviderWithoutFabricatingData() async {
        let now: Int64 = 1_800_000_000_000
        let collector = IOSHotlistSignalCollector(provider: MockHotlistProvider(items: [
            IOSHotlistItem(
                providerId: "mock_hot",
                title: "Swift concurrency release notes",
                url: "https://example.com/swift",
                rank: 1,
                score: 99,
                fetchedAt: now
            )
        ]))

        let output = await collector.collect(limit: 5)
        XCTAssertEqual(output.signals.count, 1)
        XCTAssertEqual(output.signals.first?.sourceType, IOSBoardSignalSourceType.hotlist)
        XCTAssertTrue(output.signals.first?.content.contains("https://example.com/swift") == true)
    }

    func testRunOnceAggregatesDedupsAndPrefersRealSignalsOverTime() async {
        let base = makeTempBase()
        defer { try? FileManager.default.removeItem(at: base) }
        let repo = IOSBoardSignalRepository(baseDirectory: base)
        let real = rawSignal(sourceType: IOSBoardSignalSourceType.calendar, sourceRef: "event-1", title: "Board review")
        let duplicate = rawSignal(sourceType: IOSBoardSignalSourceType.calendar, sourceRef: "event-1", title: "Board review duplicate")
        let time = rawSignal(sourceType: IOSBoardSignalSourceType.time, sourceRef: "anchor:today", title: "上午看板节点")
        let aggregator = IOSBoardSignalAggregator(
            repository: repo,
            collectors: [
                FakeBoardCollector(sourceType: IOSBoardSignalSourceType.calendar, output: .init(signals: [real, duplicate])),
                FakeBoardCollector(sourceType: IOSBoardSignalSourceType.time, output: .init(signals: [time]))
            ],
            nowProvider: { 1_800_000_000_000 }
        )

        let result = await aggregator.runOnce()
        XCTAssertEqual(result.snapshot.totalCollected, 3)
        XCTAssertEqual(result.snapshot.totalIngested, 2)
        XCTAssertEqual(result.snapshot.statuses.first(where: { $0.sourceType == IOSBoardSignalSourceType.calendar })?.duplicateCount, 1)
        XCTAssertEqual(result.boardSignals.map(\.sourceType), [IOSBoardSignalSourceType.calendar])
    }

    func testFilteredBatchOnlyConsidersSignalsSentToAgent() {
        let base = makeTempBase()
        defer { try? FileManager.default.removeItem(at: base) }
        let repo = IOSBoardSignalRepository(baseDirectory: base)
        guard case .saved(let lowScore) = repo.ingest(
            rawSignal(
                sourceRef: "chat-low",
                title: "FYI",
                content: "Casual note, no action needed.",
                metadataJson: #"{"relevance":1}"#
            ),
            now: 1_000
        ) else {
            return XCTFail("low-score save")
        }
        guard case .saved(let actionable) = repo.ingest(
            rawSignal(
                sourceRef: "chat-high",
                title: "TODO 跟进合同",
                content: "需要在明天前跟进合同状态。",
                metadataJson: #"{"relevance":8}"#
            ),
            now: 2_000
        ) else {
            return XCTFail("actionable save")
        }
        let aggregator = IOSBoardSignalAggregator(repository: repo, collectors: [])

        let batch = aggregator.filteredSignalBatch()

        XCTAssertEqual(batch.agentRecords.map(\.id), [actionable.id])
        XCTAssertEqual(batch.consideredIds, [actionable.id])
        XCTAssertFalse(batch.consideredIds.contains(lowScore.id))
    }

    func testBoardPersistenceStoresSourceCountsFromRunOnce() async {
        let base = makeTempBase()
        defer { try? FileManager.default.removeItem(at: base) }
        let repo = IOSBoardSignalRepository(baseDirectory: base)
        let persistence = IOSBoardPersistence(baseDirectory: base)
        let aggregator = IOSBoardSignalAggregator(
            repository: repo,
            collectors: [
                FakeBoardCollector(
                    sourceType: IOSBoardSignalSourceType.chatHistory,
                    output: .init(signals: [
                        rawSignal(
                            sourceType: IOSBoardSignalSourceType.chatHistory,
                            sourceRef: "chat-1",
                            title: "TODO 跟进 PR",
                            content: "需要 review PR 并决定是否合并。",
                            metadataJson: #"{"relevance":8}"#
                        )
                    ])
                )
            ],
            nowProvider: { 1_800_000_000_000 }
        )
        let result = await aggregator.runOnce()

        persistence.save(board: .init(
            boardDate: "2026-06-18",
            markdown: "# 今日看板\n- 跟进 PR",
            signalCount: result.boardSignals.count,
            generatedAt: 1_800_000_000_001,
            sourceCounts: result.snapshot.sourceCounts
        ))

        let loaded = persistence.load(boardDate: "2026-06-18")
        XCTAssertEqual(loaded?.signalCount, 1)
        XCTAssertEqual(loaded?.sourceCounts?[IOSBoardSignalSourceType.chatHistory], 1)
    }

    private func makeTempBase() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSBoardPersistenceTests-")
            .appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func rawSignal(
        sourceType: String = IOSBoardSignalSourceType.chatHistory,
        sourceRef: String,
        title: String = "TODO follow up",
        content: String = "Need to follow up the project decision before deadline.",
        signalTime: Int64 = 1_800_000_000_000,
        metadataJson: String = "{}"
    ) -> IOSRawBoardSignal {
        IOSRawBoardSignal(
            sourceType: sourceType,
            sourceRef: sourceRef,
            title: title,
            content: content,
            signalTime: signalTime,
            metadataJson: metadataJson
        )
    }
}

@MainActor
private final class FakeBoardCollector: IOSBoardSignalCollector {
    let sourceType: String
    private let output: IOSBoardCollectorOutput

    init(sourceType: String, output: IOSBoardCollectorOutput) {
        self.sourceType = sourceType
        self.output = output
    }

    func collect(limit: Int) async -> IOSBoardCollectorOutput {
        output
    }
}

@MainActor
private final class FakeConversationSignalSource: IOSBoardConversationSignalSource {
    private let candidates: [IOSBoardConversationCandidate]

    init(candidates: [IOSBoardConversationCandidate]) {
        self.candidates = candidates
    }

    func boardSignalCandidates(limit: Int) async -> [IOSBoardConversationCandidate] {
        Array(candidates.prefix(limit))
    }
}

private struct MockEventKitAdapter: IOSEventKitSignalAdapter {
    var calendarStatus: IOSEventKitAuthorization = .authorized
    var reminderStatus: IOSEventKitAuthorization = .authorized
    var calendarResult: IOSEventKitAdapterResult = .init()
    var reminderResult: IOSEventKitAdapterResult = .init()

    func calendarAuthorizationStatus() -> IOSEventKitAuthorization {
        calendarStatus
    }

    func reminderAuthorizationStatus() -> IOSEventKitAuthorization {
        reminderStatus
    }

    func calendarSignals(
        now: Date,
        lookBack: TimeInterval,
        lookAhead: TimeInterval,
        limit: Int
    ) async -> IOSEventKitAdapterResult {
        calendarResult
    }

    func reminderSignals(now: Date, lookAhead: TimeInterval, limit: Int) async -> IOSEventKitAdapterResult {
        reminderResult
    }
}

private struct MockHotlistProvider: IOSHotlistProvider {
    let providerId = "mock_hot"
    let displayName = "Mock Hotlist"
    var items: [IOSHotlistItem]

    func fetch(limit: Int) async throws -> [IOSHotlistItem] {
        Array(items.prefix(limit))
    }
}
