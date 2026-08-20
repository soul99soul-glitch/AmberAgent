import Foundation

/// Runtime book authority is the markdown worktree (`checkout/`).
/// The sharded JSON package remains the ledger: sessions, checkpoints,
/// version history, runs. Working chapter bodies on disk are what
/// discussion reads and what a collect/edit must publish.
enum NovelWorkspaceAuthority {
    static let markerFileName = "authority.json"
    static let bookWorktree = "worktree"
    static let ledgerShardedJSON = "sharded-json"
    static let markerFormat = "amber.novel.worktree"

    struct Marker: Codable, Equatable, Sendable {
        var format: String
        var version: Int
        var book: String
        var ledger: String

        static let worktree = Marker(
            format: markerFormat,
            version: 1,
            book: bookWorktree,
            ledger: ledgerShardedJSON
        )
    }

    static func checkoutDirectory(in packageDirectory: URL) -> URL {
        packageDirectory.appendingPathComponent("checkout", isDirectory: true)
    }

    static func markerURL(in checkoutDirectory: URL) -> URL {
        checkoutDirectory
            .appendingPathComponent(NovelWorkspaceLedger.directoryName, isDirectory: true)
            .appendingPathComponent(markerFileName)
    }

    static func isWorktreeBook(at checkoutDirectory: URL, fileManager: FileManager = .default) -> Bool {
        guard let data = try? Data(contentsOf: markerURL(in: checkoutDirectory)),
              let marker = try? JSONDecoder().decode(Marker.self, from: data) else {
            return false
        }
        return marker.book == bookWorktree
    }

    static func writeMarker(
        to checkoutDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        let url = markerURL(in: checkoutDirectory)
        try fileManager.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let data = try JSONEncoder().encode(Marker.worktree)
        try data.write(to: url, options: .atomic)
    }

    /// True when every live working chapter exists on disk with the same
    /// id / title / body. Missing files or drifted bodies need a heal
    /// from the ledger before the worktree can be the book.
    static func worktreeCoversWorkingManuscript(
        _ document: NovelProjectDocumentV1,
        checkoutDirectory: URL,
        fileManager: FileManager = .default
    ) -> Bool {
        guard fileManager.fileExists(
            atPath: checkoutDirectory.appendingPathComponent("manifest.yaml").path
        ) else {
            return false
        }
        guard let files = try? NovelWorkspaceFolderDocument.files(
            fromDirectory: checkoutDirectory,
            fileManager: fileManager
        ) else {
            return false
        }
        return worktreeCoversWorkingManuscript(document, files: files)
    }

    static func worktreeCoversWorkingManuscript(
        _ document: NovelProjectDocumentV1,
        files: [NovelWorkspaceBackup.File]
    ) -> Bool {
        let expected = workingChapterBodies(in: document)
        guard !expected.isEmpty else { return true }
        let onDisk = diskChapterBodies(in: files)
        for (id, body) in expected {
            guard let disk = onDisk[id],
                  disk.title == body.title,
                  disk.content == body.content else {
                return false
            }
        }
        return true
    }

    /// Publish the ledger's working manuscript onto disk. Call after load
    /// when the photocopy is behind, and after every book-affecting persist.
    static func publish(
        _ document: NovelProjectDocumentV1,
        to checkoutDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        try NovelWorkspaceBackup.write(document, to: checkoutDirectory, fileManager: fileManager)
        try writeMarker(to: checkoutDirectory, fileManager: fileManager)
    }

    /// Replace `checkout/drafts/` with the current available candidates.
    /// Does not reprint chapters, settings, or plot files.
    static func publishDrafts(
        _ document: NovelProjectDocumentV1,
        to checkoutDirectory: URL,
        fileManager: FileManager = .default
    ) throws {
        guard fileManager.fileExists(
            atPath: checkoutDirectory.appendingPathComponent("manifest.yaml").path
        ) else {
            return
        }
        let drafts = checkoutDirectory.appendingPathComponent("drafts", isDirectory: true)
        let staging = checkoutDirectory.appendingPathComponent("drafts.next", isDirectory: true)
        if fileManager.fileExists(atPath: staging.path) {
            try fileManager.removeItem(at: staging)
        }
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        do {
            for file in NovelWorkspaceBackup.draftFiles(from: document) {
                let name = URL(fileURLWithPath: file.path).lastPathComponent
                let url = staging.appendingPathComponent(name)
                try Data(file.contents.utf8).write(to: url, options: .atomic)
            }
            if fileManager.fileExists(atPath: drafts.path) {
                _ = try fileManager.replaceItemAt(
                    drafts,
                    withItemAt: staging,
                    backupItemName: nil,
                    options: []
                )
            } else {
                try fileManager.moveItem(at: staging, to: drafts)
            }
        } catch {
            try? fileManager.removeItem(at: staging)
            throw error
        }
    }

    static func draftBody(
        candidateID: NovelCandidateID,
        in checkoutDirectory: URL,
        fileManager: FileManager = .default
    ) -> String? {
        let drafts = checkoutDirectory.appendingPathComponent("drafts", isDirectory: true)
        guard let urls = try? fileManager.contentsOfDirectory(
            at: drafts,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }
        let needle = candidateID.description.lowercased()
        for url in urls where url.pathExtension == "md" {
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            let parsed = NovelWorkspaceMarkdown.parseFile(text)
            if parsed.fields["id"]?.lowercased() == needle {
                return parsed.body
            }
        }
        return nil
    }

    static func filesOnDisk(
        at checkoutDirectory: URL,
        fileManager: FileManager = .default
    ) -> [NovelWorkspaceBackup.File]? {
        guard fileManager.fileExists(
            atPath: checkoutDirectory.appendingPathComponent("manifest.yaml").path
        ) else {
            return nil
        }
        return try? NovelWorkspaceFolderDocument.files(
            fromDirectory: checkoutDirectory,
            fileManager: fileManager
        )
    }

    private struct ChapterBody: Equatable {
        var title: String
        var content: String
    }

    private static func workingChapterBodies(
        in document: NovelProjectDocumentV1
    ) -> [String: ChapterBody] {
        var result: [String: ChapterBody] = [:]
        for branch in document.branches where branch.lifecycle == .active {
            for selection in branch.workingChapterSelections {
                guard document.chapters.first(where: { $0.id == selection.chapterID })?.discardedAt == nil,
                      let version = document.chapterVersions.first(where: {
                          $0.id == selection.versionID && $0.chapterID == selection.chapterID
                      }) else {
                    continue
                }
                result[selection.chapterID.description.lowercased()] = ChapterBody(
                    title: version.title,
                    content: version.content
                )
            }
        }
        return result
    }

    private static func diskChapterBodies(
        in files: [NovelWorkspaceBackup.File]
    ) -> [String: ChapterBody] {
        var result: [String: ChapterBody] = [:]
        for file in files where file.path.contains("/chapters/") && file.path.hasSuffix(".md") {
            let parsed = NovelWorkspaceMarkdown.parseFile(file.contents)
            guard parsed.fields["kind"] == "chapter",
                  let id = parsed.fields["id"]?.lowercased(),
                  let title = parsed.fields["title"] else {
                continue
            }
            result[id] = ChapterBody(title: title, content: parsed.body)
        }
        return result
    }
}
