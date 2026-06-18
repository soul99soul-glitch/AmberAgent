import Foundation
import Observation

/// [Board MVP] iOS-local persistence for the generated "今日看板内容" (Markdown).
///
/// Scope (per product decision): ONLY the generated board content is persisted
/// — the model's Markdown output for a given date. NOT persisted: the structured
/// task-flow / BoardItemEntity / opportunity / daily-report / dispatch that
/// Android's BoardRepository + BoardItemDAO handle (those are out of scope for
/// iOS; the markers stay "不做/待接").
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
    }

    private let directory: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private let dateFormatter: DateFormatter

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = docs.appendingPathComponent("boards", isDirectory: true)
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
