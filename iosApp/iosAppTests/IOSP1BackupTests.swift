import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P1-8 backup conversations tests. Verifies the conversations bundle is
/// included in the export payload and can be round-tripped on restore.
@MainActor
final class IOSP1BackupTests: XCTestCase {

    private func makeTempDir(_ label: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(label)-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func testConversationsZipReturnsNilForEmptyDirectory() throws {
        let dir = try makeTempDir("EmptyConv")
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertNil(try IOSSyncBackup.conversationsZip(fromDirectory: dir))
    }

    func testConversationsZipBundlesJsonFiles() throws {
        let dir = try makeTempDir("Conv")
        defer { try? FileManager.default.removeItem(at: dir) }
        try "{\"id\":\"a\"}".data(using: .utf8)!.write(to: dir.appendingPathComponent("a.json"))
        try "{\"id\":\"b\"}".data(using: .utf8)!.write(to: dir.appendingPathComponent("b.json"))
        try "[]".data(using: .utf8)!.write(to: dir.appendingPathComponent("index.json"))
        // A non-json file must be excluded.
        try "ignore".data(using: .utf8)!.write(to: dir.appendingPathComponent("notes.txt"))

        let zip = try XCTUnwrap(IOSSyncBackup.conversationsZip(fromDirectory: dir))
        // Round-trip into a fresh dir via the public restore helper and assert
        // both json files reappear (and notes.txt does not).
        let dest = try makeTempDir("ConvDest")
        defer { try? FileManager.default.removeItem(at: dest) }
        let written = try IOSSyncBackup.restoreConversations(zipData: zip, intoDirectory: dest)
        XCTAssertEqual(written, 2)
        XCTAssertTrue(FileManager.default.fileExists(atPath: dest.appendingPathComponent("a.json").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: dest.appendingPathComponent("b.json").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: dest.appendingPathComponent("index.json").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: dest.appendingPathComponent("notes.txt").path))

        let documents = try IOSSyncBackup.conversationDocuments(zipData: zip)
        XCTAssertEqual(Set(documents), ["{\"id\":\"a\"}", "{\"id\":\"b\"}"])
    }

    func testConversationBackupExcludesListMetadataSidecars() throws {
        let dir = try makeTempDir("ConvSidecars")
        defer { try? FileManager.default.removeItem(at: dir) }
        try #"{"id":"conversation-1"}"#.write(
            to: dir.appendingPathComponent("conversation-1.json"),
            atomically: true,
            encoding: .utf8
        )
        try #"{"conversation-1":"preview"}"#.write(
            to: dir.appendingPathComponent("list-previews.json"),
            atomically: true,
            encoding: .utf8
        )
        try #"{"conversation-1":"sparkles"}"#.write(
            to: dir.appendingPathComponent("list-icons.json"),
            atomically: true,
            encoding: .utf8
        )

        let zip = try XCTUnwrap(IOSSyncBackup.conversationsZip(fromDirectory: dir))

        XCTAssertEqual(try IOSSyncBackup.conversationDocuments(zipData: zip), [#"{"id":"conversation-1"}"#])
    }

    func testRestoreConversationsRoundTripsIntoDirectory() throws {
        let sourceDir = try makeTempDir("ConvSrc")
        let destDir = try makeTempDir("ConvDest")
        defer {
            try? FileManager.default.removeItem(at: sourceDir)
            try? FileManager.default.removeItem(at: destDir)
        }
        try "{\"title\":\"hello\"}".data(using: .utf8)!.write(to: sourceDir.appendingPathComponent("conv1.json"))
        let zip = try XCTUnwrap(IOSSyncBackup.conversationsZip(fromDirectory: sourceDir))

        let written = try IOSSyncBackup.restoreConversations(zipData: zip, intoDirectory: destDir)
        XCTAssertEqual(written, 1)
        let restored = try String(contentsOf: destDir.appendingPathComponent("conv1.json"), encoding: .utf8)
        XCTAssertTrue(restored.contains("hello"))
    }

    func testExportWithConversationsIncludesConversationsDataset() throws {
        let dir = try makeTempDir("ConvExport")
        defer { try? FileManager.default.removeItem(at: dir) }
        try "{\"id\":\"x\"}".data(using: .utf8)!.write(to: dir.appendingPathComponent("x.json"))
        let convZip = try XCTUnwrap(IOSSyncBackup.conversationsZip(fromDirectory: dir))

        let settings = IosSettingsDefaults.shared.defaultSeededSettings()
        let data = try IOSSyncBackup.export(settings: settings, passphrase: "pw", conversationsZip: convZip)
        XCTAssertGreaterThan(data.count, 0)
        // The full import path decrypts and reads the payload manifest, which
        // must list a conversations dataset. (restorePreview can't see into the
        // encrypted payload, so use `import` to verify the dataset is present.)
        let restored = try IOSSyncBackup.import(data: data, passphrase: "pw")
        XCTAssertTrue(restored.preview.datasets.contains { $0.id == "conversations" })
        XCTAssertTrue(restored.preview.datasets.contains { $0.id == "settings" })
    }
}
