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

    func testDeepReadTaskPersistenceHistoryAndCompletion() throws {
        let base = makeTempBase()
        defer { try? FileManager.default.removeItem(at: base) }
        let store = IOSDeepReadStore(baseDirectory: base)
        let source = try IOSDeepReadSourceNormalizer.manualText(
            title: "Swift Concurrency",
            text: "Actors isolate mutable state and structured concurrency keeps task lifetimes visible.",
            now: 100
        )

        let task = try store.createTask(
            title: "Concurrency 阅读",
            sources: [source],
            templateId: IOSDeepReadTemplate.analysis.id,
            now: 1_000
        )
        store.markRunning(id: task.id, now: 2_000)
        store.complete(id: task.id, markdown: "# Concurrency\n\nResult", now: 3_000)

        let restarted = IOSDeepReadStore(baseDirectory: base)
        let loaded = try XCTUnwrap(restarted.task(id: task.id))
        XCTAssertEqual(loaded.status, .succeeded)
        XCTAssertEqual(loaded.templateId, IOSDeepReadTemplate.analysis.id)
        XCTAssertEqual(loaded.resultMarkdown, "# Concurrency\n\nResult")
        XCTAssertEqual(restarted.history.first?.id, task.id)
    }

    func testDeepReadFailureAndRetryState() throws {
        let store = IOSDeepReadStore(baseDirectory: makeTempBase())
        let source = try IOSDeepReadSourceNormalizer.manualText(title: "", text: "A source with enough text.", now: 100)
        let task = try store.createTask(title: "", sources: [source], now: 1_000)

        store.fail(id: task.id, message: "network unavailable", now: 2_000)
        XCTAssertEqual(store.task(id: task.id)?.status, .failed)
        XCTAssertEqual(store.task(id: task.id)?.failureMessage, "network unavailable")

        store.prepareRetry(id: task.id, now: 3_000)
        XCTAssertEqual(store.task(id: task.id)?.status, .queued)
        XCTAssertEqual(store.task(id: task.id)?.retryCount, 1)
        XCTAssertNil(store.task(id: task.id)?.failureMessage)

        store.markRunning(id: task.id, now: 4_000)
        let output = IOSDeepReadDraftGenerator.generate(task: try XCTUnwrap(store.task(id: task.id)))
        store.complete(id: task.id, markdown: output, now: 5_000)
        XCTAssertEqual(store.task(id: task.id)?.status, .succeeded)
        XCTAssertTrue(store.task(id: task.id)?.resultMarkdown.contains("## 摘要") == true)
    }

    func testDeepReadSourceNormalizationCoversManualFileConversationAndWeb() throws {
        let manual = try IOSDeepReadSourceNormalizer.manualText(
            title: "  ",
            text: "  First line\n\n\nSecond line  ",
            now: 100
        )
        XCTAssertEqual(manual.title, "First line")
        XCTAssertEqual(manual.content, "First line\n\nSecond line")

        let conversation = try IOSDeepReadSourceNormalizer.conversationSource(
            title: "Current chat",
            messages: ["user: 深读这个问题", "assistant: 可以，从来源开始。"],
            now: 101
        )
        XCTAssertEqual(conversation.kind, .conversation)
        XCTAssertEqual(conversation.metadata["message_count"], "2")

        let file = try IOSDeepReadSourceNormalizer.fileSource(
            SelectedDocumentReadResult(
                fileName: "report.pdf",
                fileType: "application/pdf",
                totalBytes: 2_048,
                bytesRead: 2_048,
                characterCount: 24,
                preview: "PDF readable text",
                isTruncated: false,
                note: nil
            ),
            now: 102
        )
        XCTAssertEqual(file.kind, .file)
        XCTAssertEqual(file.metadata["file_type"], "application/pdf")

        let web = try IOSDeepReadSourceNormalizer.webMountSource(
            title: "Loaded page",
            url: "https://example.com/article?secret=redacted",
            text: "Readable page text",
            now: 103
        )
        XCTAssertEqual(web.kind, .webMount)
        XCTAssertEqual(web.url, "https://example.com/article?secret=redacted")
    }

    func testDeepReadSourceNormalizationRejectsUnreadableFileAndEmptyWebMount() {
        let unreadable = SelectedDocumentReadResult(
            fileName: "scan.pdf",
            fileType: "application/pdf",
            totalBytes: 1_024,
            bytesRead: 1_024,
            characterCount: 0,
            preview: "",
            isTruncated: false,
            note: "PDF 中没有可提取文本；扫描版 PDF 需要 OCR。"
        )
        XCTAssertThrowsError(try IOSDeepReadSourceNormalizer.fileSource(unreadable)) { error in
            XCTAssertTrue(error.localizedDescription.contains("PDF") || error.localizedDescription.contains("OCR"))
        }
        XCTAssertThrowsError(try IOSDeepReadSourceNormalizer.webMountSource(title: "", url: nil, text: "   ")) { error in
            XCTAssertTrue(error.localizedDescription.contains("WebMount"))
        }
    }

    func testDeepReadTemplateValidatorAcceptsSafeHTMLAndRejectsUnsafeHTML() {
        let valid = IOSDeepReadTemplateValidator.validateHTML("""
        <!DOCTYPE html><html><head><style>{{font_css}}</style></head><body>{{narrative_html}}</body></html>
        """)
        XCTAssertTrue(valid.ok)

        let script = IOSDeepReadTemplateValidator.validateHTML("<html><body><script>alert(1)</script></body></html>")
        XCTAssertFalse(script.ok)
        XCTAssertTrue(script.error?.contains("JavaScript") == true)

        let external = IOSDeepReadTemplateValidator.validateHTML("<html><body><img src=\"https://example.com/a.png\"></body></html>")
        XCTAssertFalse(external.ok)
        XCTAssertTrue(external.error?.contains("外部") == true || external.error?.contains("资源") == true)
    }

    func testDeepReadDraftGeneratorIncludesSourceBoundaries() throws {
        let sources = [
            try IOSDeepReadSourceNormalizer.manualText(title: "Manual", text: "Manual source explains the topic."),
            try IOSDeepReadSourceNormalizer.conversationSource(title: "Chat", messages: ["user: 需要跟进这个决策"]),
            try IOSDeepReadSourceNormalizer.webMountSource(title: "Page", url: "https://example.com/page", text: "Current page body")
        ]
        let store = IOSDeepReadStore(baseDirectory: makeTempBase())
        let task = try store.createTask(title: "Boundary test", sources: sources, templateId: IOSDeepReadTemplate.analysis.id)

        let markdown = IOSDeepReadDraftGenerator.generate(task: task)

        XCTAssertTrue(markdown.contains("## 摘要"))
        XCTAssertTrue(markdown.contains("WebMount 来源只读取当前前台页面正文"))
        XCTAssertTrue(markdown.contains("会话来源能保留上下文意图"))
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
