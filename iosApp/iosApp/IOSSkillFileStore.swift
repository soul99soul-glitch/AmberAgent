import Foundation

struct IOSSkillFileStore {
    private let baseDirectory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
            ?? (try? fileManager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
    }

    var skillsDirectory: URL {
        baseDirectory.appendingPathComponent("skills", isDirectory: true)
    }

    @discardableResult
    func createSkill(name rawName: String, description: String, allowedTools: [String]) throws -> String {
        let name = Self.normalizedSkillName(rawName)
        let skillDirectory = try resolveSkillDirectory(name: name)
        guard !fileManager.fileExists(atPath: skillDirectory.path) else {
            throw IOSSkillFileStoreError.skillAlreadyExists(name)
        }

        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDescription.isEmpty else {
            throw IOSSkillFileStoreError.emptyDescription
        }

        try fileManager.createDirectory(at: skillDirectory, withIntermediateDirectories: true)
        let markdown = Self.makeSkillMarkdown(
            name: name,
            description: trimmedDescription,
            allowedTools: allowedTools
        )
        try markdown.write(to: skillDirectory.appendingPathComponent("SKILL.md"), atomically: true, encoding: .utf8)
        return name
    }

    func readSkillMarkdown(dirName: String) throws -> String {
        let directory = try resolveSkillDirectory(name: dirName)
        return try String(contentsOf: directory.appendingPathComponent("SKILL.md"), encoding: .utf8)
    }

    func saveSkillMarkdown(dirName: String, expectedName: String, content: String) throws {
        let directory = try resolveSkillDirectory(name: dirName)
        guard fileManager.fileExists(atPath: directory.path) else {
            throw IOSSkillFileStoreError.skillMissing(dirName)
        }
        let parsedName = Self.frontmatterName(in: content)
        guard parsedName == expectedName else {
            throw IOSSkillFileStoreError.skillNameChanged(expected: expectedName)
        }
        try content.write(to: directory.appendingPathComponent("SKILL.md"), atomically: true, encoding: .utf8)
    }

    func deleteSkill(dirName: String) throws {
        let directory = try resolveSkillDirectory(name: dirName)
        guard fileManager.fileExists(atPath: directory.path) else {
            throw IOSSkillFileStoreError.skillMissing(dirName)
        }
        try fileManager.removeItem(at: directory)
    }

    /// Writes a skill package (at least `SKILL.md`) into `skills/<name>/`.
    /// Frontmatter `name` is the authoritative package id. Overwrites an existing package.
    @discardableResult
    func saveSkillFiles(files: [String: String]) throws -> String {
        guard let skillMd = files["SKILL.md"] ?? files["skill.md"] else {
            throw IOSSkillFileStoreError.missingSkillMarkdown
        }
        let frontmatter = Self.parseFrontmatter(skillMd)
        guard let declaredName = frontmatter["name"], !declaredName.isEmpty else {
            throw IOSSkillFileStoreError.missingFrontmatterField("name")
        }
        guard let description = frontmatter["description"], !description.isEmpty else {
            throw IOSSkillFileStoreError.missingFrontmatterField("description")
        }
        let packageName = Self.normalizedSkillName(declaredName)
        let skillDirectory = try resolveSkillDirectory(name: packageName)
        if fileManager.fileExists(atPath: skillDirectory.path) {
            try fileManager.removeItem(at: skillDirectory)
        }
        try fileManager.createDirectory(at: skillDirectory, withIntermediateDirectories: true)
        for (relativePath, content) in files {
            let clean = relativePath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !clean.isEmpty, !clean.contains("..") else { continue }
            let destination: URL
            if clean.lowercased() == "skill.md" || clean.lowercased().hasSuffix("/skill.md") {
                let parent = clean.contains("/")
                    ? skillDirectory.appendingPathComponent((clean as NSString).deletingLastPathComponent)
                    : skillDirectory
                try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
                destination = parent.appendingPathComponent("SKILL.md")
            } else {
                destination = skillDirectory.appendingPathComponent(clean)
                try fileManager.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
            }
            try content.write(to: destination, atomically: true, encoding: .utf8)
        }
        if !fileManager.fileExists(atPath: skillDirectory.appendingPathComponent("SKILL.md").path) {
            try skillMd.write(
                to: skillDirectory.appendingPathComponent("SKILL.md"),
                atomically: true,
                encoding: .utf8
            )
        }
        _ = description
        return packageName
    }

    func skillDirectoryURL(name: String) throws -> URL {
        try resolveSkillDirectory(name: Self.normalizedSkillName(name))
    }

    func containsMcpConfig(name: String) -> Bool {
        guard let directory = try? skillDirectoryURL(name: name) else { return false }
        return fileManager.fileExists(atPath: directory.appendingPathComponent("mcp.json").path)
    }

    func resolveSkillFile(name: String, relativePath: String) throws -> URL {
        let directory = try skillDirectoryURL(name: name)
        let clean = relativePath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !clean.isEmpty, !clean.contains("..") else {
            throw IOSSkillFileStoreError.invalidSkillName
        }
        let target = directory.appendingPathComponent(clean).standardizedFileURL
        guard target.path.hasPrefix(directory.standardizedFileURL.path + "/")
            || target.path == directory.standardizedFileURL.path else {
            throw IOSSkillFileStoreError.invalidSkillName
        }
        return target
    }

    static func parseFrontmatter(_ content: String) -> [String: String] {
        guard content.hasPrefix("---"),
              let endRange = content.range(
                of: "\n---",
                range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex
              ) else {
            return [:]
        }
        let yaml = String(content[content.index(content.startIndex, offsetBy: 3)..<endRange.lowerBound])
        var frontmatter: [String: String] = [:]
        for line in yaml.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard let separator = trimmed.firstIndex(of: ":") else { continue }
            let key = String(trimmed[..<separator]).trimmingCharacters(in: .whitespaces)
            let value = String(trimmed[trimmed.index(after: separator)...])
                .trimmingCharacters(in: .whitespaces)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            if !key.isEmpty {
                frontmatter[key] = value
            }
        }
        return frontmatter
    }

    static func extractBody(from content: String) -> String {
        guard content.hasPrefix("---"),
              let endRange = content.range(
                of: "\n---",
                range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex
              ) else {
            return content
        }
        let bodyStart = endRange.upperBound
        return String(content[bodyStart...]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func normalizedSkillName(_ raw: String) -> String {
        raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: " ", with: "-")
    }

    static func allowedToolTokens(from raw: String) -> [String] {
        raw
            .components(separatedBy: CharacterSet(charactersIn: ", \n\t"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    static func makeSkillMarkdown(name: String, description: String, allowedTools: [String]) -> String {
        let toolsLine = allowedTools.isEmpty ? "" : "\nallowed-tools: \(allowedTools.joined(separator: " "))"
        return """
        ---
        name: "\(escapeYaml(name))"
        description: "\(escapeYaml(description))"\(toolsLine)
        ---

        # \(name)

        \(description)
        """
    }

    private func resolveSkillDirectory(name: String) throws -> URL {
        guard !name.isEmpty,
              name != ".",
              name != "..",
              !name.contains("/"),
              !name.contains("\\") else {
            throw IOSSkillFileStoreError.invalidSkillName
        }

        let root = skillsDirectory.standardizedFileURL
        let directory = root.appendingPathComponent(name, isDirectory: true).standardizedFileURL
        guard directory.deletingLastPathComponent().path == root.path else {
            throw IOSSkillFileStoreError.invalidSkillName
        }
        return directory
    }

    /// Lists the directory names of every skill that has a SKILL.md on disk.
    /// Used by chat skill-context injection to map enabled skill names → their
    /// markdown bodies. Best-effort: skips unreadable / malformed entries.
    func listSkillDirNames() -> [String] {
        guard let entries = try? fileManager.contentsOfDirectory(at: skillsDirectory, includingPropertiesForKeys: nil) else {
            return []
        }
        var result: [String] = []
        for entry in entries {
            var isDir: ObjCBool = false
            guard fileManager.fileExists(atPath: entry.path, isDirectory: &isDir), isDir.boolValue else { continue }
            let skillMd = entry.appendingPathComponent("SKILL.md")
            if fileManager.fileExists(atPath: skillMd.path) {
                result.append(entry.lastPathComponent)
            }
        }
        return result
    }

    private static func frontmatterName(in content: String) -> String? {
        guard content.hasPrefix("---"),
              let endRange = content.range(of: "\n---", range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex) else {
            return nil
        }
        let yaml = String(content[content.index(content.startIndex, offsetBy: 3)..<endRange.lowerBound])
        for line in yaml.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("name:") else { continue }
            return trimmed
                .dropFirst("name:".count)
                .trimmingCharacters(in: .whitespaces)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        }
        return nil
    }

    private static func escapeYaml(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: " ")
    }
}

enum IOSSkillFileStoreError: LocalizedError, Equatable {
    case invalidSkillName
    case emptyDescription
    case skillAlreadyExists(String)
    case skillMissing(String)
    case skillNameChanged(expected: String)
    case missingSkillMarkdown
    case missingFrontmatterField(String)

    var errorDescription: String? {
        switch self {
        case .invalidSkillName:
            "技能名称不能为空，且不能包含路径分隔符。"
        case .emptyDescription:
            "触发说明不能为空。"
        case .skillAlreadyExists(let name):
            "技能 \(name) 已存在。"
        case .skillMissing(let name):
            "技能 \(name) 不存在。"
        case .skillNameChanged(let expected):
            "不允许修改技能名称（name 字段必须为 \(expected)）。"
        case .missingSkillMarkdown:
            "Skill 包缺少 SKILL.md。"
        case .missingFrontmatterField(let field):
            "SKILL.md 缺少 \(field)。"
        }
    }
}
