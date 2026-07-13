import Foundation
import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let amberNovelProject = UTType(
        exportedAs: "app.amber.ios.novel-project",
        conformingTo: .data
    )

    static var amberMarkdown: UTType {
        UTType(filenameExtension: "md") ?? .plainText
    }
}

struct NovelProjectFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.amberNovelProject] }
    static var writableContentTypes: [UTType] { [.amberNovelProject] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard configuration.file.isRegularFile,
              let data = configuration.file.regularFileContents else {
            throw NovelError.invalidPackage("The selected novel project is not a regular file.")
        }
        guard data.count <= NovelProjectPackageLimits.standard.maximumEnvelopeBytes else {
            throw NovelError.packageTooLarge(
                maximumBytes: NovelProjectPackageLimits.standard.maximumEnvelopeBytes
            )
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        guard data.count <= NovelProjectPackageLimits.standard.maximumEnvelopeBytes else {
            throw NovelError.packageTooLarge(
                maximumBytes: NovelProjectPackageLimits.standard.maximumEnvelopeBytes
            )
        }
        return FileWrapper(regularFileWithContents: data)
    }
}

struct NovelMarkdownFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.amberMarkdown, .plainText] }
    static var writableContentTypes: [UTType] { [.amberMarkdown] }

    let markdown: String

    init(markdown: String) {
        self.markdown = markdown
    }

    init(configuration: ReadConfiguration) throws {
        guard configuration.file.isRegularFile,
              let data = configuration.file.regularFileContents,
              let markdown = String(data: data, encoding: .utf8) else {
            throw NovelError.invalidInput("The selected Markdown file is not valid UTF-8.")
        }
        self.markdown = markdown
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(markdown.utf8))
    }
}

enum NovelProjectFileReader {
    static func readPackage(
        from url: URL,
        limits: NovelProjectPackageLimits = .standard,
        fileManager: FileManager = .default
    ) throws -> Data {
        guard limits.maximumProjectBytes > 0,
              limits.maximumEnvelopeBytes >= limits.maximumProjectBytes else {
            throw NovelError.invalidInput("Novel project package limits are invalid.")
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                url.stopAccessingSecurityScopedResource()
            }
        }

        guard fileManager.fileExists(atPath: url.path) else {
            throw NovelError.invalidPackage("The selected novel project file no longer exists.")
        }
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else {
            throw NovelError.invalidPackage("The selected novel project is not a regular file.")
        }
        guard let fileSize = values.fileSize else {
            throw NovelError.invalidPackage("The novel project file size is unavailable.")
        }
        guard fileSize <= limits.maximumEnvelopeBytes else {
            throw NovelError.packageTooLarge(maximumBytes: limits.maximumEnvelopeBytes)
        }

        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        guard data.count <= limits.maximumEnvelopeBytes else {
            throw NovelError.packageTooLarge(maximumBytes: limits.maximumEnvelopeBytes)
        }
        return data
    }
}
