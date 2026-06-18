import XCTest
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
}
