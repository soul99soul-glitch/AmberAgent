import Foundation
import Shared

enum IOSSkillToolCatalog {
    static let toolNames: Set<String> = [
        "skills_list",
        "use_skill",
        "skill_validate",
        "skill_import",
        "skill_enable",
        "skill_disable",
    ]

    static let mutatingToolNames: Set<String> = [
        "skill_import",
        "skill_enable",
        "skill_disable",
    ]
}

enum IOSMcpManagementToolCatalog {
    static let toolNames: Set<String> = [
        "mcp_list",
        "mcp_test",
        "mcp_import_from_skill",
    ]

    static let mutatingToolNames: Set<String> = [
        "mcp_test",
        "mcp_import_from_skill",
    ]
}

/// Android `createSkillTools` + `createMcpManagementTools` parity for iOS chat.
@MainActor
struct IOSSkillMcpToolService {
    let skillStore: IOSSkillFileStore
    let sharedSettings: IOSSharedSettingsStore
    let workspaceStore: IOSWorkspaceStore
    let mcpConfigStore: IOSMcpConfigStore
    let mcpManager: IOSMcpManager

    func execute(toolName: String, arguments: String) async -> String {
        let args = ChatToolCallParsing.jsonObject(arguments) ?? [:]
        do {
            switch toolName {
            case "skills_list":
                return try skillsListJSON()
            case "use_skill":
                return try useSkillText(args)
            case "skill_validate":
                return try skillValidateJSON(args)
            case "skill_import":
                return try skillImportJSON(args)
            case "skill_enable":
                return try skillEnableJSON(args, enable: true)
            case "skill_disable":
                return try skillEnableJSON(args, enable: false)
            case "mcp_list":
                return await mcpListJSON(args)
            case "mcp_test":
                return await mcpTestJSON(args)
            case "mcp_import_from_skill":
                return try mcpImportFromSkillJSON(args)
            default:
                return Self.json(["ok": false, "error": "Unknown tool: \(toolName)"])
            }
        } catch {
            return Self.json([
                "ok": false,
                "error": (error as? LocalizedError)?.errorDescription ?? error.localizedDescription,
            ])
        }
    }

    // MARK: - Skills

    private func skillsListJSON() throws -> String {
        let installedDirs = skillStore.listSkillDirNames()
        let enabled = sharedSettings.currentAssistantEnabledSkillNames
        var available: [[String: Any]] = []
        var disabled: [[String: Any]] = []
        var installedNames = Set<String>()

        for dirName in installedDirs {
            guard let markdown = try? skillStore.readSkillMarkdown(dirName: dirName) else { continue }
            let frontmatter = IOSSkillFileStore.parseFrontmatter(markdown)
            // Enable / use_skill / injection all key off the on-disk directory name.
            installedNames.insert(dirName)
            let entry: [String: Any] = [
                "name": dirName,
                "description": frontmatter["description"] ?? "",
                "allowed_tools": IOSSkillFileStore.allowedToolTokens(
                    from: frontmatter["allowed-tools"] ?? frontmatter["tools"] ?? ""
                ),
                "contains_mcp_config": skillStore.containsMcpConfig(name: dirName),
            ]
            if enabled.contains(dirName) {
                available.append(entry)
            } else {
                disabled.append(entry)
            }
        }

        let missingEnabled = enabled.filter { !installedNames.contains($0) }.sorted()
        return Self.json([
            "installed_count": installedDirs.count,
            "enabled_count": available.count,
            "configured_enabled_count": enabled.count,
            "available_skills": available,
            "disabled_installed_skills": disabled,
            "missing_enabled_skills": missingEnabled,
        ])
    }

    private func useSkillText(_ args: [String: Any]) throws -> String {
        let name = (args["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty else {
            throw IOSSkillToolError.missingArgument("name")
        }
        let dirName = resolveInstalledDirName(name)
        let enabled = sharedSettings.currentAssistantEnabledSkillNames
        guard enabled.contains(dirName) else {
            throw IOSSkillToolError.skillNotEnabled(dirName)
        }
        let path = (args["path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let content: String
        let pathLabel: String
        if let path, !path.isEmpty {
            let url = try skillStore.resolveSkillFile(name: dirName, relativePath: path)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw IOSSkillToolError.skillFileMissing(path)
            }
            content = try String(contentsOf: url, encoding: .utf8)
            pathLabel = path
        } else {
            let markdown = try skillStore.readSkillMarkdown(dirName: dirName)
            content = IOSSkillFileStore.extractBody(from: markdown)
            pathLabel = "SKILL.md"
        }
        return Self.wrapSkillForMobileRuntime(skillName: dirName, pathLabel: pathLabel, body: content)
    }

    private func skillValidateJSON(_ args: [String: Any]) throws -> String {
        let name = (args["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let workspacePath = (args["workspace_path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let files: [String: String]
        if let name, !name.isEmpty {
            files = try collectInstalledSkillFiles(name: name)
        } else if let workspacePath, !workspacePath.isEmpty {
            files = try collectWorkspaceSkillFiles(workspacePath: workspacePath)
        } else {
            throw IOSSkillToolError.missingArgument("name or workspace_path")
        }
        let skillMd = files["SKILL.md"] ?? ""
        let frontmatter = IOSSkillFileStore.parseFrontmatter(skillMd)
        var issues: [String] = []
        if skillMd.isEmpty { issues.append("缺少 SKILL.md") }
        if frontmatter["name"].isNilOrEmpty { issues.append("SKILL.md 缺少 name") }
        if frontmatter["description"].isNilOrEmpty { issues.append("SKILL.md 缺少 description") }
        return Self.json([
            "valid": issues.isEmpty,
            "name": frontmatter["name"] ?? "",
            "description": frontmatter["description"] ?? "",
            "file_count": files.count,
            "contains_mcp_config": files["mcp.json"] != nil,
            "issues": issues,
        ])
    }

    private func skillImportJSON(_ args: [String: Any]) throws -> String {
        let workspacePath = (args["workspace_path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !workspacePath.isEmpty else {
            throw IOSSkillToolError.missingArgument("workspace_path")
        }
        let files = try collectWorkspaceSkillFiles(workspacePath: workspacePath)
        let packageName = try skillStore.saveSkillFiles(files: files)
        sharedSettings.setSkillEnabled(name: packageName, enabled: true)
        return Self.json([
            "success": true,
            "name": packageName,
            "file_count": files.count,
            "enabled": true,
            "contains_mcp_config": files["mcp.json"] != nil,
        ])
    }

    private func skillEnableJSON(_ args: [String: Any], enable: Bool) throws -> String {
        let name = (args["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty else {
            throw IOSSkillToolError.missingArgument("name")
        }
        let dirName = resolveInstalledDirName(name)
        guard skillStore.listSkillDirNames().contains(dirName) else {
            throw IOSSkillFileStoreError.skillMissing(name)
        }
        sharedSettings.setSkillEnabled(name: dirName, enabled: enable)
        return Self.json([
            "success": true,
            "name": dirName,
            "enabled": enable,
        ])
    }

    // MARK: - MCP management

    private func mcpListJSON(_ args: [String: Any]) async -> String {
        let includeTools = (args["include_tools"] as? Bool) ?? true
        mcpManager.refreshServers()
        let servers = mcpManager.servers.isEmpty ? mcpConfigStore.servers : mcpManager.servers
        let payload: [[String: Any]] = servers.map { server in
            var entry: [String: Any] = [
                "id": server.name,
                "name": server.name,
                "enabled": server.enabled,
                "status": statusString(mcpManager.statusByServer[server.name]),
                "tool_count": server.tools.count,
                "enabled_tool_count": server.tools.filter(\.enabled).count,
                "type": server.transportKey,
                "url": server.url,
            ]
            if includeTools {
                // No persisted input schemas on IOSMcpTool; list names/descriptions only.
                entry["tools"] = server.tools.map { tool -> [String: Any] in
                    [
                        "name": tool.name,
                        "description": String((tool.description ?? "").prefix(240)),
                        "enabled": tool.enabled,
                    ]
                }
            }
            return entry
        }
        return Self.json([
            "servers": payload,
            "call_tool": "Use mcp_call with server, tool, and arguments to call one of these tools directly.",
        ])
    }

    private func mcpTestJSON(_ args: [String: Any]) async -> String {
        let serverId = (args["server_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (args["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        mcpManager.refreshServers()
        let servers = mcpManager.servers.isEmpty ? mcpConfigStore.servers : mcpManager.servers
        guard let server = servers.first(where: {
            $0.name == serverId || $0.name == name
        }) else {
            return Self.json(["ok": false, "error": "MCP server not found"])
        }
        await mcpManager.syncAll()
        let status = mcpManager.statusByServer[server.name]
        let toolCount = mcpManager.servers.first(where: { $0.name == server.name })?.tools.count
            ?? server.tools.count
        return Self.json([
            "server": [
                "id": server.name,
                "name": server.name,
                "enabled": server.enabled,
                "status": statusString(status),
                "tool_count": toolCount,
                "type": server.transportKey,
                "url": server.url,
            ],
            "status": statusString(status),
        ])
    }

    private func mcpImportFromSkillJSON(_ args: [String: Any]) throws -> String {
        let skillName = (args["skill_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !skillName.isEmpty else {
            throw IOSSkillToolError.missingArgument("skill_name")
        }
        let dirName = resolveInstalledDirName(skillName)
        let mcpURL = try skillStore.resolveSkillFile(name: dirName, relativePath: "mcp.json")
        guard FileManager.default.fileExists(atPath: mcpURL.path) else {
            throw IOSSkillToolError.skillFileMissing("mcp.json")
        }
        let json = try String(contentsOf: mcpURL, encoding: .utf8)
        let parsed = McpImportParserKt.parseMcpServersFromJson(json: json)
            .compactMap(IOSMcpServerConfig.init)
        guard !parsed.isEmpty else {
            return Self.json(["ok": false, "error": "mcp.json does not contain valid MCP servers"])
        }
        // Match Android: skip servers that already exist; never silently overwrite.
        var existing = Set(mcpConfigStore.servers.map(\.name))
        var importedCount = 0
        var alreadyExistsCount = 0
        for server in parsed {
            if existing.contains(server.name) {
                alreadyExistsCount += 1
                continue
            }
            mcpConfigStore.add(server)
            existing.insert(server.name)
            importedCount += 1
        }
        return Self.json([
            "success": true,
            "skill_name": dirName,
            "imported_count": importedCount,
            "already_exists_count": alreadyExistsCount,
        ])
    }

    // MARK: - Helpers

    private func resolveInstalledDirName(_ name: String) -> String {
        let normalized = IOSSkillFileStore.normalizedSkillName(name)
        let dirs = skillStore.listSkillDirNames()
        if dirs.contains(name) { return name }
        if dirs.contains(normalized) { return normalized }
        for dir in dirs {
            if let markdown = try? skillStore.readSkillMarkdown(dirName: dir) {
                let parsed = IOSSkillFileStore.parseFrontmatter(markdown)["name"] ?? ""
                if parsed == name || IOSSkillFileStore.normalizedSkillName(parsed) == normalized {
                    return dir
                }
            }
        }
        return normalized
    }

    private func collectInstalledSkillFiles(name: String) throws -> [String: String] {
        let dirName = resolveInstalledDirName(name)
        let directory = try skillStore.skillDirectoryURL(name: dirName)
        guard FileManager.default.fileExists(atPath: directory.path) else {
            throw IOSSkillFileStoreError.skillMissing(name)
        }
        return try collectTextFiles(under: directory, root: directory)
    }

    private func collectWorkspaceSkillFiles(workspacePath: String) throws -> [String: String] {
        let normalized = workspacePath
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "^/workspace/", with: "", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !normalized.isEmpty else {
            throw IOSSkillToolError.missingArgument("workspace_path")
        }

        if let record = workspaceStore.fileRecord(idOrPath: workspacePath)
            ?? workspaceStore.fileRecord(idOrPath: "/workspace/\(normalized)")
            ?? workspaceStore.fileRecord(idOrPath: normalized) {
            let url = workspaceStore.fileURL(for: record)
            let content = try String(contentsOf: url, encoding: .utf8)
            if normalized.lowercased().hasSuffix(".zip") {
                throw IOSSkillToolError.unsupportedZip
            }
            let relative = (normalized as NSString).lastPathComponent
            let key = relative.lowercased() == "skill.md" ? "SKILL.md" : relative
            var files = [key: content]
            // Single-file SKILL.md import: also pull sibling mcp.json when present.
            if key == "SKILL.md" {
                let parent = (normalized as NSString).deletingLastPathComponent
                if !parent.isEmpty {
                    let mcpRelative = parent + "/mcp.json"
                    if let mcpRecord = workspaceStore.fileRecord(idOrPath: mcpRelative)
                        ?? workspaceStore.fileRecord(idOrPath: "/workspace/\(mcpRelative)") {
                        files["mcp.json"] = try String(
                            contentsOf: workspaceStore.fileURL(for: mcpRecord),
                            encoding: .utf8
                        )
                    }
                }
            }
            return files
        }

        let prefix = normalized.hasSuffix("/") ? String(normalized.dropLast()) : normalized
        let matching = workspaceStore.files.filter {
            $0.workspacePath == prefix
                || $0.workspacePath.hasPrefix(prefix + "/")
        }
        guard !matching.isEmpty else {
            throw IOSSkillToolError.workspacePathMissing(workspacePath)
        }
        var files: [String: String] = [:]
        for record in matching {
            let relative: String
            if record.workspacePath == prefix {
                relative = record.displayName
            } else if record.workspacePath.hasPrefix(prefix + "/") {
                relative = String(record.workspacePath.dropFirst(prefix.count + 1))
            } else {
                continue
            }
            guard Self.isLikelyTextSkillFile(relative) else { continue }
            let content = try String(contentsOf: workspaceStore.fileURL(for: record), encoding: .utf8)
            let key = relative.lowercased() == "skill.md" ? "SKILL.md" : relative
            files[key] = content
        }
        return files
    }

    private func collectTextFiles(under directory: URL, root: URL) throws -> [String: String] {
        var files: [String: String] = [:]
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return [:]
        }
        for case let fileURL as URL in enumerator {
            var isDir: ObjCBool = false
            guard FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDir),
                  !isDir.boolValue else { continue }
            let relative = fileURL.path
                .replacingOccurrences(of: root.path + "/", with: "")
                .replacingOccurrences(of: "\\", with: "/")
            guard Self.isLikelyTextSkillFile(relative), !relative.contains("..") else { continue }
            let key = relative.lowercased() == "skill.md" ? "SKILL.md" : relative
            files[key] = try String(contentsOf: fileURL, encoding: .utf8)
        }
        return files
    }

    private func statusString(_ status: IOSMcpConnectionStatus?) -> String {
        switch status {
        case .idle, .none: "idle"
        case .connecting: "connecting"
        case .connected: "connected"
        case .reconnecting: "reconnecting"
        case .error(let message): "error:\(message)"
        }
    }

    private static func isLikelyTextSkillFile(_ name: String) -> Bool {
        let lower = name.lowercased()
        return lower == "skill.md"
            || lower == "skill.txt"
            || lower == "mcp.json"
            || lower.hasSuffix(".md")
            || lower.hasSuffix(".json")
            || lower.hasSuffix(".txt")
            || lower.hasSuffix(".yaml")
            || lower.hasSuffix(".yml")
            || lower.hasSuffix(".js")
            || lower.hasSuffix(".ts")
            || lower.hasSuffix(".py")
            || lower.hasSuffix(".sh")
            || lower.hasSuffix(".html")
            || lower.hasSuffix(".css")
    }

    private static func wrapSkillForMobileRuntime(skillName: String, pathLabel: String, body: String) -> String {
        """
        [AmberAgent Mobile Runtime — applies to the skill content below]
        You are running inside AmberAgent on iOS — NOT desktop Claude Code, NOT Codex, NOT a CLI environment.
        These mobile constraints OVERRIDE any conflicting instruction in the skill body:
        - File outputs go to /workspace via workspace_file_write; import a skill with skill_import.
        - MCP servers are configured with mcp_import_from_skill / Settings MCP page, then called with mcp_call.
        - Do NOT recommend npm/pip/curl/python desktop tooling unless an in-app tool explicitly supports it.
        - IMPORTANT about use_skill paths: many skills only ship SKILL.md. Do not chain path retries for missing references/scripts/assets.

        Skill: \(skillName)  (\(pathLabel))
        --- skill content begins ---
        \(body)
        --- skill content ends ---
        """
    }

    private static func json(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return #"{"ok":false,"error":"JSON encoding failed"}"#
        }
        return text
    }
}

private enum IOSSkillToolError: LocalizedError {
    case missingArgument(String)
    case skillNotEnabled(String)
    case skillFileMissing(String)
    case workspacePathMissing(String)
    case unsupportedZip

    var errorDescription: String? {
        switch self {
        case .missingArgument(let name):
            "\(name) is required"
        case .skillNotEnabled(let name):
            "Skill '\(name)' is not enabled. Call skills_list to see installed and enabled skills."
        case .skillFileMissing(let path):
            "File '\(path)' not found in skill package."
        case .workspacePathMissing(let path):
            "Workspace path not found: \(path)"
        case .unsupportedZip:
            "Zip skill import is not supported on iOS yet. Import a SKILL.md or skill folder from Workspace."
        }
    }
}

private extension Optional where Wrapped == String {
    var isNilOrEmpty: Bool {
        switch self {
        case .none: true
        case .some(let value): value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
