import Foundation
import XCTest
@testable import iosApp

/// Copied into a temporary iOS checkout by run_novel_workspace_exchange.sh.
/// The product checkout stays read-only; these tests call the current iOS
/// exporter/importer rather than recreating its fixture in another language.
final class W15CrossPlatformRunnerTests: XCTestCase {
    private let fixedDate = Date(timeIntervalSince1970: 1_787_011_200)
    private let root = URL(fileURLWithPath: "/tmp/amber-w15-cross-platform", isDirectory: true)

    func testRealIOSExport() throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let document = try makeNovelWorkspaceBackupFixture()
        let files = try NovelWorkspaceBackup.export(document, exportedAt: fixedDate)
        try write(files, to: root.appendingPathComponent("ios-export", isDirectory: true))
        try writeText(
            "sourceProjectID: \(document.project.id.description)\nsourceRevision: \(document.project.revision)\nsourceSchema: \(document.schemaVersion)\nfileCount: \(files.count)\n",
            to: root.appendingPathComponent("ios-export-metadata.txt")
        )
    }

    func testRealIOSImport() throws {
        let files = try read(from: root.appendingPathComponent("android-export", isDirectory: true))
        let document = try NovelWorkspaceImporter.makeDocument(from: files, now: fixedDate)
        try NovelDocumentValidator.validate(document)
        XCTAssertEqual(document.project.name, "Test Novel (Android)")
        // The importer appends discarded chapters through the same reducer
        // path before marking them discarded; the selection history therefore
        // has two entries while only one chapter remains live.
        XCTAssertEqual(document.branches.first?.workingChapterSelections.count, 2)
        XCTAssertEqual(document.chapters.filter { $0.discardedAt == nil }.count, 1)
        XCTAssertEqual(document.chapters.filter { $0.discardedAt != nil }.count, 1)
        let importedBranch = document.branches.first
        let importedSnapshot = importedBranch.flatMap { branch in
            document.stateSnapshots.first { $0.id == branch.currentStateSnapshotID }
        }
        XCTAssertEqual(importedSnapshot?.summary, "赵大已在陈桥。")
        XCTAssertEqual(importedSnapshot?.branchOutline, "从陈桥往汴京")
        XCTAssertEqual(document.upcomingArcs.first?.beats, ["入汴"])
        XCTAssertEqual(document.chapterPlans.first?.status.rawValue, "confirmed")
        let hasZhao = document.materials.contains { material in
            guard !material.isDeleted,
                  let revision = document.materialRevisions.first(where: { $0.id == material.currentRevisionID })
            else { return false }
            return revision.title == "赵大"
        }
        XCTAssertTrue(hasZhao)
        let reexport = try NovelWorkspaceBackup.export(document, exportedAt: fixedDate)
        try write(reexport, to: root.appendingPathComponent("ios-reimport", isDirectory: true))
        try writeText(
            "importedProjectID: \(document.project.id.description)\nimportedRevision: \(document.project.revision)\nfileCount: \(reexport.count)\n",
            to: root.appendingPathComponent("ios-reimport-metadata.txt")
        )
    }

    private func write(_ files: [NovelWorkspaceBackup.File], to directory: URL) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: directory.path) { try fm.removeItem(at: directory) }
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        for file in files {
            let url = directory.appendingPathComponent(file.path)
            try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(file.contents.utf8).write(to: url)
        }
    }

    private func read(from directory: URL) throws -> [NovelWorkspaceBackup.File] {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(at: directory, includingPropertiesForKeys: [.isRegularFileKey]) else {
            throw NSError(domain: "W15Runner", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing Android export"])
        }
        // `/tmp` is a symlink on macOS. Resolve both sides before deriving the
        // tree-relative path, otherwise the manifest key can become an
        // absolute `/private/tmp/.../manifest.yaml` path in the simulator.
        let resolvedDirectory = directory.resolvingSymlinksInPath()
        var files: [NovelWorkspaceBackup.File] = []
        for case let url as URL in enumerator {
            guard url.pathExtension == "md" || url.pathExtension == "yaml" else { continue }
            let resolvedURL = url.resolvingSymlinksInPath()
            let prefix = resolvedDirectory.path + "/"
            guard resolvedURL.path.hasPrefix(prefix) else { continue }
            let relative = String(resolvedURL.path.dropFirst(prefix.count))
            files.append(.init(path: relative, contents: try String(contentsOf: url, encoding: .utf8)))
        }
        return files.sorted { $0.path < $1.path }
    }

    private func writeText(_ text: String, to url: URL) throws {
        try Data(text.utf8).write(to: url)
    }
}
