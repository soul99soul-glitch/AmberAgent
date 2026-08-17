import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct NovelWorkspaceFolderDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.folder] }
    static var writableContentTypes: [UTType] { [.folder] }

    let files: [NovelWorkspaceBackup.File]

    init(files: [NovelWorkspaceBackup.File]) {
        self.files = files
    }

    init(configuration: ReadConfiguration) throws {
        files = try Self.flatten(configuration.file, prefix: "")
        guard files.contains(where: { $0.path == "manifest.yaml" }) else {
            throw NovelError.invalidPackage("The selected folder is not a novel workspace.")
        }
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let root = FileWrapper(directoryWithFileWrappers: [:])
        for file in files {
            try Self.insert(file, into: root)
        }
        return root
    }

    static func files(fromDirectory root: URL, fileManager: FileManager = .default) throws -> [NovelWorkspaceBackup.File] {
        var accessed = false
        if root.startAccessingSecurityScopedResource() {
            accessed = true
        }
        defer {
            if accessed {
                root.stopAccessingSecurityScopedResource()
            }
        }
        let resolved = try resolveWorkspaceRoot(root, fileManager: fileManager)
        return try collectFiles(at: resolved, relative: "", fileManager: fileManager)
    }

    private static func resolveWorkspaceRoot(
        _ root: URL,
        fileManager: FileManager
    ) throws -> URL {
        let manifest = root.appendingPathComponent("manifest.yaml")
        if fileManager.fileExists(atPath: manifest.path) {
            return root
        }
        let children = try fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        let nested = children.filter { url in
            fileManager.fileExists(atPath: url.appendingPathComponent("manifest.yaml").path)
        }
        guard nested.count == 1, let only = nested.first else {
            throw NovelError.invalidPackage("The selected folder is not a novel workspace.")
        }
        return only
    }

    private static func collectFiles(
        at directory: URL,
        relative: String,
        fileManager: FileManager
    ) throws -> [NovelWorkspaceBackup.File] {
        let items = try fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        var files: [NovelWorkspaceBackup.File] = []
        for item in items.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            let childRelative = relative.isEmpty
                ? item.lastPathComponent
                : "\(relative)/\(item.lastPathComponent)"
            var isDirectory: ObjCBool = false
            guard fileManager.fileExists(atPath: item.path, isDirectory: &isDirectory) else {
                continue
            }
            if isDirectory.boolValue {
                files.append(contentsOf: try collectFiles(
                    at: item,
                    relative: childRelative,
                    fileManager: fileManager
                ))
            } else if item.pathExtension == "md" || item.pathExtension == "yaml" {
                let text = try String(contentsOf: item, encoding: .utf8)
                files.append(NovelWorkspaceBackup.File(path: childRelative, contents: text))
            }
        }
        return files
    }

    private static func flatten(_ wrapper: FileWrapper, prefix: String) throws -> [NovelWorkspaceBackup.File] {
        if wrapper.isRegularFile {
            guard let data = wrapper.regularFileContents,
                  let text = String(data: data, encoding: .utf8) else {
                throw NovelError.invalidPackage("A workspace file is not valid UTF-8.")
            }
            return [NovelWorkspaceBackup.File(path: prefix, contents: text)]
        }
        guard wrapper.isDirectory, let children = wrapper.fileWrappers else {
            return []
        }
        var files: [NovelWorkspaceBackup.File] = []
        for (name, child) in children.sorted(by: { $0.key < $1.key }) {
            let next = prefix.isEmpty ? name : "\(prefix)/\(name)"
            files.append(contentsOf: try flatten(child, prefix: next))
        }
        return files
    }

    private static func insert(
        _ file: NovelWorkspaceBackup.File,
        into root: FileWrapper
    ) throws {
        let parts = file.path.split(separator: "/").map(String.init)
        guard let leaf = parts.last else { return }
        var current = root
        for folder in parts.dropLast() {
            if let existing = current.fileWrappers?[folder], existing.isDirectory {
                current = existing
                continue
            }
            let created = FileWrapper(directoryWithFileWrappers: [:])
            created.preferredFilename = folder
            current.addFileWrapper(created)
            current = created
        }
        let child = FileWrapper(regularFileWithContents: Data(file.contents.utf8))
        child.preferredFilename = leaf
        current.addFileWrapper(child)
    }
}
