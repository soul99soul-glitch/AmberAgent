import CryptoKit
import Foundation
import Shared

struct IOSPreparedMcpImport: Equatable {
    let skillName: String
    let digest: String
    let servers: [IOSMcpServerConfig]
    let preview: McpImportPreview
}

enum IOSMcpImportError: LocalizedError, Equatable {
    case invalidJSON
    case emptyServers
    case unsupportedTransport(name: String, type: String)
    case invalidURL(name: String)
    case duplicateCandidate(String)
    case liveNameConflict(String)
    case connectivityFailed(server: String, message: String)

    var errorDescription: String? {
        switch self {
        case .invalidJSON:
            "mcp.json 无法解析。"
        case .emptyServers:
            "mcp.json 不包含可导入的 MCP 服务。"
        case .unsupportedTransport(let name, let type):
            "MCP 服务 \(name) 使用了不支持的 transport（\(type)）。"
        case .invalidURL(let name):
            "MCP 服务 \(name) 缺少有效 URL。"
        case .duplicateCandidate(let name):
            "候选 mcp.json 内存在重复服务名：\(name)。"
        case .liveNameConflict(let name):
            "已存在同名 MCP 服务：\(name)。导入不会部分成功。"
        case .connectivityFailed(let server, let message):
            "\(server) 临时连通测试失败：\(message)"
        }
    }
}

enum IOSSkillFileChangeKind: String, Equatable {
    case added
    case modified
    case removed
}

struct IOSSkillFileChange: Equatable {
    let path: String
    let kind: IOSSkillFileChangeKind
    let beforeText: String?
    let afterText: String?
}

struct IOSSkillImportPreview: Equatable {
    let name: String
    let kind: IOSSkillMutationKind
    let baseHash: String?
    let candidateHash: String
    let changedFiles: [IOSSkillFileChange]
    let beforeSummary: String?
    let afterSummary: String
    let fileCount: Int
    let containsMcpConfig: Bool

    var approvalSummary: String {
        let action = kind == .new ? "新增" : "更新"
        return "\(action) Skill \(name)，\(changedFiles.count) 个文件有变化"
    }
}

/// Small, in-memory approval context. The candidate bytes stay in Workspace and
/// are read again when approval is granted, so stale candidates cannot be applied.
struct IOSPreparedSkillImport: Equatable {
    let workspacePath: String
    let mergeExisting: Bool
    let preview: IOSSkillImportPreview
}

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
        "mcp_describe_tool",
        "mcp_import_from_skill",
    ]

    static let mutatingToolNames: Set<String> = [
        "mcp_test",
        "mcp_import_from_skill",
    ]

    static let highRiskToolNames = mutatingToolNames
}

/// Android `createSkillTools` + `createMcpManagementTools` parity for iOS chat.
@MainActor
struct IOSSkillMcpToolService {
    private static let maximumSkillReadBytes = 256 * 1024
    private static let maximumDiffTextBytes = 16 * 1024
    private static let maximumDiffTextCharacters = 4_000
    private static let maximumPackageSummaryCharacters = 600

    let skillStore: IOSSkillFileStore
    let sharedSettings: IOSSharedSettingsStore
    let workspaceStore: IOSWorkspaceStore
    let mcpConfigStore: IOSMcpConfigStore
    let mcpManager: IOSMcpManager
    var ephemeralClientFactory: ((IOSMcpServerConfig) -> any IOSMcpClienting)? = nil

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
                return try skillImportPreviewJSON(args)
            case "skill_enable":
                return try skillEnableJSON(args, enable: true)
            case "skill_disable":
                return try skillEnableJSON(args, enable: false)
            case "mcp_list":
                return await mcpListJSON(args)
            case "mcp_test":
                return await mcpTestJSON(args)
            case "mcp_describe_tool":
                return await mcpDescribeToolJSON(args)
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
            guard (path as NSString).lastPathComponent.lowercased() != "mcp.json" else {
                throw IOSSkillToolError.sensitiveSkillFile(path)
            }
            let url = try skillStore.resolveSkillFile(name: dirName, relativePath: path)
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw IOSSkillToolError.skillFileMissing(path)
            }
            content = try readSkillText(at: url, pathLabel: path)
            pathLabel = path
        } else {
            let markdownURL = try skillStore.resolveSkillFile(name: dirName, relativePath: "SKILL.md")
            let markdown = try readSkillText(at: markdownURL, pathLabel: "SKILL.md")
            content = IOSSkillFileStore.extractBody(from: markdown)
            pathLabel = "SKILL.md"
        }
        return Self.wrapSkillForMobileRuntime(skillName: dirName, pathLabel: pathLabel, body: content)
    }

    func prepareSkillImport(arguments: String) throws -> IOSPreparedSkillImport {
        guard let args = ChatToolCallParsing.jsonObject(arguments) else {
            throw IOSSkillToolError.invalidArguments
        }
        return try prepareSkillImport(args).prepared
    }

    func applyPreparedSkillImport(_ prepared: IOSPreparedSkillImport) throws -> String {
        let reread: (
            prepared: IOSPreparedSkillImport,
            package: IOSSkillPackagePreparation
        )
        do {
            reread = try makeSkillImportPreparation(workspacePath: prepared.workspacePath)
        } catch let error as IOSSkillToolError {
            return Self.skillImportErrorJSON(
                code: "stale_candidate",
                message: error.errorDescription
                    ?? "Workspace 候选包已无法读取或验证，请重新生成并预览。"
            )
        } catch let error as IOSSkillFileStoreError {
            return Self.skillImportErrorJSON(
                code: "stale_base",
                message: error.errorDescription
                    ?? "已安装 Skill 已无法读取或验证，请重新预览。"
            )
        } catch {
            return Self.skillImportErrorJSON(
                code: "stale_candidate",
                message: "Workspace 候选包已无法读取，请重新生成并预览。"
            )
        }
        guard reread.prepared.mergeExisting == prepared.mergeExisting,
              reread.prepared.preview.name == prepared.preview.name else {
            return Self.skillImportErrorJSON(
                code: "stale_target",
                message: "Skill 目标已变化，请重新预览后再批准。"
            )
        }
        guard reread.prepared.preview.baseHash == prepared.preview.baseHash else {
            return Self.skillImportErrorJSON(
                code: "stale_base",
                message: "已安装 Skill 在批准前发生变化，请重新预览。"
            )
        }
        guard reread.prepared.preview.candidateHash == prepared.preview.candidateHash else {
            return Self.skillImportErrorJSON(
                code: "stale_candidate",
                message: "Workspace 候选包在批准前发生变化，请重新预览。"
            )
        }

        let name = prepared.preview.name
        let enabledBefore = sharedSettings.isSkillEnabled(name)
        let optionalSeedWasRemoved = IOSBuiltinSkills.isOptionalSeedRemoved(name, store: skillStore)
        let receipt: IOSSkillPackageApplyReceipt
        do {
            receipt = try skillStore.applySkillPackage(
                candidateFiles: reread.package.candidate.files,
                name: name,
                expectedBaseHash: prepared.preview.baseHash,
                expectedCandidateHash: prepared.preview.candidateHash,
                enabledBefore: enabledBefore,
                optionalSeedWasRemoved: optionalSeedWasRemoved
            )
        } catch let error as IOSSkillFileStoreError {
            switch error {
            case .skillPackageBaseChanged:
                return Self.skillImportErrorJSON(
                    code: "stale_base",
                    message: error.errorDescription ?? "已安装 Skill 在批准前发生变化，请重新预览。"
                )
            case .skillPackageCandidateChanged:
                return Self.skillImportErrorJSON(
                    code: "stale_candidate",
                    message: error.errorDescription ?? "Workspace 候选包在批准前发生变化，请重新预览。"
                )
            default:
                throw error
            }
        }

        // A new Skill becomes immediately usable; an update keeps the current
        // Assistant's latest enablement state.
        if prepared.preview.kind == .new {
            sharedSettings.setSkillEnabled(name: name, enabled: true)
        }
        IOSBuiltinSkills.clearOptionalSeedRemoved(name, store: skillStore)
        return Self.json([
            "success": true,
            "status": receipt.outcome == .applied ? "applied" : "unchanged",
            "name": receipt.name,
            "hash": receipt.promotedHash,
            "file_count": reread.package.candidate.files.count,
            "enabled": sharedSettings.isSkillEnabled(name),
            "contains_mcp_config": reread.prepared.preview.containsMcpConfig,
        ])
    }

    private func skillValidateJSON(_ args: [String: Any]) throws -> String {
        let name = (args["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let workspacePath = (args["workspace_path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let files: [String: Data]
        let expectedName: String?
        if let name, !name.isEmpty {
            let dirName = resolveInstalledDirName(name)
            files = try collectInstalledSkillFiles(name: dirName)
            expectedName = dirName
        } else if let workspacePath, !workspacePath.isEmpty {
            files = try collectWorkspaceSkillFiles(workspacePath: workspacePath)
            expectedName = nil
        } else {
            throw IOSSkillToolError.missingArgument("name or workspace_path")
        }
        let validation = validateSkillPackage(files, expectedName: expectedName)
        return Self.json([
            "valid": validation.issues.isEmpty,
            "name": validation.name ?? "",
            "description": validation.description ?? "",
            "file_count": files.count,
            "contains_mcp_config": files["mcp.json"] != nil,
            "issues": validation.issues,
        ])
    }

    private func skillImportPreviewJSON(_ args: [String: Any]) throws -> String {
        let prepared = try prepareSkillImport(args).prepared
        let preview = prepared.preview
        return Self.json([
            "ok": true,
            "status": "preview",
            "requires_approval": true,
            "name": preview.name,
            "kind": preview.kind.rawValue,
            "base_hash": preview.baseHash as Any? ?? NSNull(),
            "candidate_hash": preview.candidateHash,
            "file_count": preview.fileCount,
            "contains_mcp_config": preview.containsMcpConfig,
            "before_summary": preview.beforeSummary as Any? ?? NSNull(),
            "after_summary": preview.afterSummary,
            "changed_files": preview.changedFiles.map(Self.changeJSON),
        ])
    }

    private func prepareSkillImport(_ args: [String: Any]) throws -> (
        prepared: IOSPreparedSkillImport,
        package: IOSSkillPackagePreparation
    ) {
        let workspacePath = (args["workspace_path"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !workspacePath.isEmpty else {
            throw IOSSkillToolError.missingArgument("workspace_path")
        }
        return try makeSkillImportPreparation(workspacePath: workspacePath)
    }

    private func makeSkillImportPreparation(workspacePath: String) throws -> (
        prepared: IOSPreparedSkillImport,
        package: IOSSkillPackagePreparation
    ) {
        let resolvedWorkspacePath: String
        if let record = workspaceStore.fileRecord(idOrPath: workspacePath) {
            resolvedWorkspacePath = "/workspace/\(record.workspacePath)"
        } else {
            resolvedWorkspacePath = workspacePath
        }
        let files = try collectWorkspaceSkillFiles(workspacePath: resolvedWorkspacePath)
        let validation = validateSkillPackage(files, expectedName: nil)
        guard validation.issues.isEmpty else {
            throw IOSSkillToolError.invalidSkillPackage(validation.issues)
        }

        let mergeExisting = Self.isSingleSkillMarkdownImportPath(resolvedWorkspacePath)
            && workspaceStore.fileRecord(idOrPath: resolvedWorkspacePath) != nil
        let package = try skillStore.prepareSkillPackage(
            importedFiles: files,
            mergeExisting: mergeExisting
        )
        let preview = Self.makeImportPreview(package)
        return (
            IOSPreparedSkillImport(
                workspacePath: resolvedWorkspacePath,
                mergeExisting: mergeExisting,
                preview: preview
            ),
            package
        )
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
                "url": IOSWebMountRedactor.redactedURL(server.url) ?? "",
            ]
            if includeTools {
                // Directory entry: names/descriptions only (schema lives in the
                // persisted IOSMcpTool.inputSchema; fetch it with mcp_describe_tool).
                entry["tools"] = server.tools.map { tool -> [String: Any] in
                    [
                        "name": tool.name,
                        "description": String((tool.description ?? "").prefix(240)),
                        "enabled": tool.enabled,
                        "has_input_schema": tool.inputSchema != nil,
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
        guard server.enabled else {
            return Self.json(["ok": false, "error": "MCP server is disabled: \(server.name)"])
        }
        await mcpManager.sync(serverName: server.name)
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
                "url": IOSWebMountRedactor.redactedURL(server.url) ?? "",
            ],
            "status": statusString(status),
        ])
    }

    /// `mcp_describe_tool`: read-only lookup of one discovered tool's full
    /// description + persisted input schema. Never touches the network and
    /// never mutates config, so it shares `mcp_list`'s approval classification.
    private func mcpDescribeToolJSON(_ args: [String: Any]) async -> String {
        let serverName = (args["server"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let toolName = (args["tool"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        mcpManager.refreshServers()
        let servers = mcpManager.servers.isEmpty ? mcpConfigStore.servers : mcpManager.servers
        guard let server = servers.first(where: { $0.name == serverName }) else {
            return Self.json([
                "ok": false,
                "error": "MCP server not found: \(serverName)",
                "valid_servers": servers.map(\.name).sorted(),
            ])
        }
        guard let tool = server.tools.first(where: { $0.name == toolName }) else {
            return Self.json([
                "ok": false,
                "error": "MCP tool not found on server '\(serverName)': \(toolName)",
                "valid_tools": server.tools.map(\.name).sorted(),
            ])
        }
        let inputSchema: Any
        if let schemaText = tool.inputSchema, !schemaText.isEmpty {
            guard let data = schemaText.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) else {
                return Self.json([
                    "ok": false,
                    "code": "invalid_persisted_schema",
                    "error": "Persisted MCP input schema is not complete JSON; refresh this server's tools.",
                    "server": serverName,
                    "tool": toolName,
                ])
            }
            inputSchema = object
        } else {
            inputSchema = NSNull()
        }
        return Self.json([
            "ok": true,
            "server": serverName,
            "tool": toolName,
            "enabled": tool.enabled,
            "description": tool.description ?? "",
            "input_schema": inputSchema,
            "untrusted": true,
        ])
    }

    private func mcpImportFromSkillJSON(_ args: [String: Any]) throws -> String {
        let prepared = try prepareMcpImport(args)
        return Self.json([
            "ok": true,
            "status": "preview",
            "requires_approval": true,
            "skill_name": prepared.skillName,
            "digest": prepared.digest,
            "server_count": prepared.servers.count,
            "servers": prepared.preview.servers.map { server in
                [
                    "name": server.name,
                    "transport": server.transport,
                    "origin": server.origin,
                    "header_names": server.headerNames,
                    "enabled": server.enabled,
                ] as [String: Any]
            },
        ])
    }

    func prepareMcpImport(arguments: String) throws -> IOSPreparedMcpImport {
        guard let args = ChatToolCallParsing.jsonObject(arguments) else {
            throw IOSSkillToolError.invalidArguments
        }
        return try prepareMcpImport(args)
    }

    func prepareMcpImport(_ args: [String: Any]) throws -> IOSPreparedMcpImport {
        let skillName = (args["skill_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !skillName.isEmpty else {
            throw IOSSkillToolError.missingArgument("skill_name")
        }
        let prepared = try makeMcpImportPreparation(skillName: skillName)
        try validateMcpImportConflicts(prepared.servers)
        return prepared
    }

    func applyPreparedMcpImport(
        _ prepared: IOSPreparedMcpImport,
        networkAllowed: () -> Bool = { true }
    ) async -> String {
        let reread: IOSPreparedMcpImport
        do {
            reread = try makeMcpImportPreparation(skillName: prepared.skillName)
        } catch {
            return Self.json([
                "ok": false,
                "code": "stale_candidate",
                "error": (error as? LocalizedError)?.errorDescription ?? "mcp.json 已无法读取，请重新预览。",
            ])
        }
        guard reread.digest == prepared.digest else {
            return Self.json([
                "ok": false,
                "code": "stale_candidate",
                "error": "mcp.json 在批准前已变化，请重新预览。",
            ])
        }
        do {
            try validateMcpImportConflicts(reread.servers)
        } catch {
            return Self.json([
                "ok": false,
                "code": "name_conflict",
                "error": (error as? LocalizedError)?.errorDescription ?? "MCP 名称冲突。",
            ])
        }
        guard networkAllowed() else {
            return Self.json([
                "ok": false,
                "code": "denied",
                "error": "MCP 调用已关闭，未发起网络测试或写入。",
            ])
        }
        do {
            try await testEphemeralServers(reread.servers)
        } catch {
            return Self.json([
                "ok": false,
                "code": "connectivity_failed",
                "error": (error as? LocalizedError)?.errorDescription ?? "临时连通测试失败，未写入任何 MCP 服务。",
            ])
        }
        guard networkAllowed() else {
            return Self.json([
                "ok": false,
                "code": "denied",
                "error": "MCP 调用已关闭，未写入任何 MCP 服务。",
            ])
        }
        do {
            try mcpConfigStore.addBatch(reread.servers)
        } catch {
            return Self.json([
                "ok": false,
                "code": "publish_failed",
                "error": (error as? LocalizedError)?.errorDescription ?? "写入 MCP 配置失败。",
            ])
        }
        mcpManager.refreshFromCurrentSettings()
        return Self.json([
            "ok": true,
            "applied": true,
            "skill_name": reread.skillName,
            "imported_count": reread.servers.count,
            "digest": reread.digest,
        ])
    }

    private func makeMcpImportPreparation(skillName: String) throws -> IOSPreparedMcpImport {
        let dirName = resolveInstalledDirName(skillName)
        let mcpURL = try skillStore.resolveSkillFile(name: dirName, relativePath: "mcp.json")
        guard FileManager.default.fileExists(atPath: mcpURL.path) else {
            throw IOSSkillToolError.skillFileMissing("mcp.json")
        }
        let data = try Data(contentsOf: mcpURL)
        guard let json = String(data: data, encoding: .utf8) else {
            throw IOSSkillToolError.invalidArguments
        }
        let servers = try Self.parseSupportedMcpServers(json: json)
        guard !servers.isEmpty else {
            throw IOSMcpImportError.emptyServers
        }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let preview = McpImportPreview(
            skillName: dirName,
            digest: digest,
            servers: servers.map { server in
                McpImportServerPreview(
                    name: server.name,
                    transport: server.transportTitle,
                    origin: Self.redactedOrigin(server.url),
                    headerNames: server.headers.keys.sorted(),
                    enabled: server.enabled
                )
            }
        )
        return IOSPreparedMcpImport(
            skillName: dirName,
            digest: digest,
            servers: servers,
            preview: preview
        )
    }

    private func validateMcpImportConflicts(_ servers: [IOSMcpServerConfig]) throws {
        var seen = Set<String>()
        for server in servers {
            if !seen.insert(server.name).inserted {
                throw IOSMcpImportError.duplicateCandidate(server.name)
            }
        }
        let live = Set(mcpConfigStore.servers.map(\.name))
            .union(sharedSettings.snapshot.mcpServers.compactMap { IOSMcpServerConfig($0)?.name })
        if let conflict = servers.first(where: { live.contains($0.name) }) {
            throw IOSMcpImportError.liveNameConflict(conflict.name)
        }
    }

    private func testEphemeralServers(_ servers: [IOSMcpServerConfig]) async throws {
        for server in servers {
            let client = ephemeralClientFactory?(server) ?? IOSMcpClient()
            do {
                _ = try await client.connect(config: server)
                _ = try await client.listTools()
                client.disconnect()
            } catch {
                client.disconnect()
                throw IOSMcpImportError.connectivityFailed(server: server.name, message: error.localizedDescription)
            }
        }
    }

    static func parseSupportedMcpServers(json: String) throws -> [IOSMcpServerConfig] {
        guard let data = json.data(using: .utf8),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let mcpServers = root["mcpServers"] as? [String: Any] else {
            throw IOSMcpImportError.invalidJSON
        }
        var parsed: [IOSMcpServerConfig] = []
        for (name, raw) in mcpServers.sorted(by: { $0.key < $1.key }) {
            guard let object = raw as? [String: Any] else {
                throw IOSMcpImportError.invalidJSON
            }
            let type = ((object["type"] as? String) ?? "streamable_http")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .lowercased()
            if type == "stdio" || object["command"] != nil && object["url"] == nil {
                throw IOSMcpImportError.unsupportedTransport(name: name, type: type.isEmpty ? "stdio" : type)
            }
            let normalizedType: String
            switch type {
            case "", "streamable_http", "streamablehttp", "http":
                normalizedType = "streamable_http"
            case "sse":
                normalizedType = "sse"
            default:
                throw IOSMcpImportError.unsupportedTransport(name: name, type: type)
            }
            guard let url = object["url"] as? String, !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw IOSMcpImportError.invalidURL(name: name)
            }
            var headers: [String: String] = [:]
            if let rawHeaders = object["headers"] as? [String: Any] {
                for (key, value) in rawHeaders {
                    headers[key] = String(describing: value)
                }
            }
            if normalizedType == "sse" {
                parsed.append(.sse(name: name, url: url, headers: headers, enabled: true, tools: []))
            } else {
                parsed.append(.streamableHTTP(name: name, url: url, headers: headers, enabled: true, tools: []))
            }
        }
        return parsed
    }

    static func redactedOrigin(_ urlString: String) -> String {
        guard var components = URLComponents(string: urlString) else { return "invalid-url" }
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        if let host = components.host {
            let scheme = components.scheme.map { "\($0)://" } ?? ""
            let port = components.port.map { ":\($0)" } ?? ""
            return "\(scheme)\(host)\(port)"
        }
        return components.string ?? "invalid-url"
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

    private func collectInstalledSkillFiles(name: String) throws -> [String: Data] {
        let dirName = resolveInstalledDirName(name)
        let directory = try skillStore.skillDirectoryURL(name: dirName)
        guard FileManager.default.fileExists(atPath: directory.path) else {
            throw IOSSkillFileStoreError.skillMissing(name)
        }
        return try collectRegularFiles(under: directory, root: directory)
    }

    private func collectWorkspaceSkillFiles(workspacePath: String) throws -> [String: Data] {
        let directRecord = workspaceStore.fileRecord(idOrPath: workspacePath)
        let normalized = try Self.normalizedWorkspaceSkillPath(
            directRecord?.workspacePath ?? workspacePath
        )

        if let record = directRecord
            ?? workspaceStore.fileRecord(idOrPath: workspacePath)
            ?? workspaceStore.fileRecord(idOrPath: "/workspace/\(normalized)")
            ?? workspaceStore.fileRecord(idOrPath: normalized) {
            if normalized.lowercased().hasSuffix(".zip") {
                throw IOSSkillToolError.unsupportedZip
            }
            let relative = (normalized as NSString).lastPathComponent
            var files: [String: Data] = [:]
            try Self.insertSkillFile(
                try readWorkspaceFileData(record),
                relativePath: relative,
                into: &files
            )
            // Single-file SKILL.md import: also pull sibling mcp.json when present.
            if relative.lowercased() == "skill.md" {
                let parent = (normalized as NSString).deletingLastPathComponent
                let mcpRelative = parent.isEmpty ? "mcp.json" : parent + "/mcp.json"
                if let mcpRecord = workspaceStore.fileRecord(idOrPath: mcpRelative)
                    ?? workspaceStore.fileRecord(idOrPath: "/workspace/\(mcpRelative)") {
                    try Self.insertSkillFile(
                        try readWorkspaceFileData(mcpRecord),
                        relativePath: "mcp.json",
                        into: &files
                    )
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
        var files: [String: Data] = [:]
        for record in matching.sorted(by: { $0.workspacePath < $1.workspacePath }) {
            let relative: String
            if record.workspacePath == prefix {
                relative = record.displayName
            } else if record.workspacePath.hasPrefix(prefix + "/") {
                relative = String(record.workspacePath.dropFirst(prefix.count + 1))
            } else {
                continue
            }
            try Self.insertSkillFile(
                try readWorkspaceFileData(record),
                relativePath: relative,
                into: &files
            )
        }
        return files
    }

    private func readWorkspaceFileData(_ record: IOSWorkspaceFileRecord) throws -> Data {
        let url = workspaceStore.fileURL(for: record)
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else {
            throw IOSSkillToolError.invalidSkillPath(record.workspacePath)
        }
        return try Data(contentsOf: url)
    }

    private func collectRegularFiles(under directory: URL, root: URL) throws -> [String: Data] {
        var files: [String: Data] = [:]
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey],
            options: []
        ) else {
            return [:]
        }
        let rootPath = root.standardizedFileURL.path
        while let fileURL = enumerator.nextObject() as? URL {
            let values = try fileURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            if values.isSymbolicLink == true {
                enumerator.skipDescendants()
                throw IOSSkillToolError.invalidSkillPath(fileURL.lastPathComponent)
            }
            guard values.isRegularFile == true else { continue }
            let itemPath = fileURL.standardizedFileURL.path
            guard itemPath.hasPrefix(rootPath + "/") else {
                throw IOSSkillToolError.invalidSkillPath(fileURL.path)
            }
            let relative = String(itemPath.dropFirst(rootPath.count + 1))
            try Self.insertSkillFile(
                Data(contentsOf: fileURL),
                relativePath: relative,
                into: &files
            )
        }
        return files
    }

    private func validateSkillPackage(
        _ files: [String: Data],
        expectedName: String?
    ) -> IOSSkillPackageValidation {
        var issues: [String] = []
        var validatedPaths: [String] = []
        for path in files.keys.sorted() {
            do {
                let canonical = try Self.canonicalSkillRelativePath(path)
                guard Self.collidingSkillPath(in: validatedPaths, candidate: canonical) == nil else {
                    issues.append("Skill 包含大小写冲突或重复路径：\(path)")
                    continue
                }
                validatedPaths.append(canonical)
            } catch {
                issues.append("Skill 包含非法相对路径：\(path)")
            }
        }

        guard let skillData = files["SKILL.md"] else {
            issues.append("缺少 SKILL.md")
            return IOSSkillPackageValidation(name: nil, description: nil, issues: issues)
        }
        guard let skillMarkdown = String(data: skillData, encoding: .utf8) else {
            issues.append("SKILL.md 必须是 UTF-8 文本")
            return IOSSkillPackageValidation(name: nil, description: nil, issues: issues)
        }

        let frontmatter = IOSSkillFileStore.parseFrontmatter(skillMarkdown)
        let declaredName = frontmatter["name"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        let description = frontmatter["description"]?.trimmingCharacters(in: .whitespacesAndNewlines)
        if declaredName.isNilOrEmpty {
            issues.append("SKILL.md 缺少 name")
        }
        if description.isNilOrEmpty {
            issues.append("SKILL.md 缺少 description")
        }

        var normalizedName: String?
        if let declaredName, !declaredName.isEmpty {
            let normalized = IOSSkillFileStore.normalizedSkillName(declaredName)
            if normalized.isEmpty
                || normalized == "."
                || normalized == ".."
                || normalized.hasPrefix(".")
                || normalized.contains("\0")
                || normalized.contains("/")
                || normalized.contains("\\") {
                issues.append("SKILL.md name 不是合法的 Skill 名称")
            } else {
                normalizedName = normalized
                if let expectedName,
                   IOSSkillFileStore.normalizedSkillName(expectedName) != normalized {
                    issues.append("SKILL.md name 与已安装目录名不一致")
                }
            }
        }
        return IOSSkillPackageValidation(
            name: normalizedName,
            description: description,
            issues: issues
        )
    }

    private static func makeImportPreview(_ preparation: IOSSkillPackagePreparation) -> IOSSkillImportPreview {
        let baseFiles = preparation.base?.files ?? [:]
        let candidateFiles = preparation.candidate.files
        let paths = Set(baseFiles.keys).union(candidateFiles.keys).sorted()
        let changedFiles = paths.compactMap { path -> IOSSkillFileChange? in
            let before = baseFiles[path]
            let after = candidateFiles[path]
            let kind: IOSSkillFileChangeKind
            switch (before, after) {
            case (.none, .some):
                kind = .added
            case (.some, .none):
                kind = .removed
            case (.some(let before), .some(let after)) where before != after:
                kind = .modified
            default:
                return nil
            }
            return IOSSkillFileChange(
                path: path,
                kind: kind,
                beforeText: before.flatMap(diffText),
                afterText: after.flatMap(diffText)
            )
        }
        return IOSSkillImportPreview(
            name: preparation.candidate.name,
            kind: preparation.kind,
            baseHash: preparation.base?.hash,
            candidateHash: preparation.candidate.hash,
            changedFiles: changedFiles,
            beforeSummary: preparation.base.map(packageSummary),
            afterSummary: packageSummary(preparation.candidate),
            fileCount: candidateFiles.count,
            containsMcpConfig: candidateFiles["mcp.json"] != nil
        )
    }

    private static func packageSummary(_ package: IOSSkillPackage) -> String {
        guard let markdownData = package.files["SKILL.md"],
              let markdown = String(data: markdownData, encoding: .utf8) else {
            return "\(package.files.count) 个文件"
        }
        let frontmatter = IOSSkillFileStore.parseFrontmatter(markdown)
        let description = frontmatter["description"]?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let body = IOSSkillFileStore.extractBody(from: markdown)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let combined = [description, body]
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        guard !combined.isEmpty else { return "\(package.files.count) 个文件" }
        guard combined.count > maximumPackageSummaryCharacters else { return combined }
        return String(combined.prefix(maximumPackageSummaryCharacters)) + "\n…（摘要已截断）"
    }

    private static func diffText(_ data: Data) -> String? {
        guard data.count <= maximumDiffTextBytes,
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        if text.count <= maximumDiffTextCharacters { return text }
        return String(text.prefix(maximumDiffTextCharacters)) + "\n…（已截断）"
    }

    private static func changeJSON(_ change: IOSSkillFileChange) -> [String: Any] {
        var result: [String: Any] = [
            "path": change.path,
            "kind": change.kind.rawValue,
        ]
        if let beforeText = change.beforeText {
            result["before"] = beforeText
        }
        if let afterText = change.afterText {
            result["after"] = afterText
        }
        return result
    }

    private func statusString(_ status: IOSMcpConnectionStatus?) -> String {
        switch status {
        case .idle, .none: "idle"
        case .connecting: "connecting"
        case .connected: "connected"
        case .reconnecting: "reconnecting"
        case .error(let message): "error:\(IOSWebMountRedactor.redactedText(message))"
        }
    }

    private func readSkillText(at url: URL, pathLabel: String) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: Self.maximumSkillReadBytes + 1) ?? Data()
        guard data.count <= Self.maximumSkillReadBytes else {
            throw IOSSkillToolError.skillFileTooLarge(pathLabel, Self.maximumSkillReadBytes)
        }
        guard let content = String(data: data, encoding: .utf8) else {
            throw IOSSkillToolError.skillFileNotUTF8(pathLabel)
        }
        return content
    }

    private static func normalizedWorkspaceSkillPath(_ raw: String) throws -> String {
        var path = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty, !path.contains("\\") else {
            throw IOSSkillToolError.invalidSkillPath(raw)
        }
        if path == "/workspace" || path == "/workspace/" {
            throw IOSSkillToolError.missingArgument("workspace_path")
        }
        if path.hasPrefix("/workspace/") {
            path.removeFirst("/workspace/".count)
        } else if path.hasPrefix("/") {
            throw IOSSkillToolError.invalidSkillPath(raw)
        }
        while path.hasSuffix("/") { path.removeLast() }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !components.isEmpty,
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else {
            throw IOSSkillToolError.invalidSkillPath(raw)
        }
        return components.map(String.init).joined(separator: "/")
    }

    private static func canonicalSkillRelativePath(_ raw: String) throws -> String {
        let path = raw.precomposedStringWithCanonicalMapping
        guard !path.isEmpty,
              !path.hasPrefix("/"),
              !path.hasSuffix("/"),
              !path.contains("\0"),
              !path.contains("\\") else {
            throw IOSSkillToolError.invalidSkillPath(raw)
        }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !components.isEmpty,
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else {
            throw IOSSkillToolError.invalidSkillPath(raw)
        }
        if components.count == 1, components[0].lowercased() == "skill.md" {
            return "SKILL.md"
        }
        return components.map(String.init).joined(separator: "/")
    }

    private static func collidingSkillPath(
        in existingPaths: some Sequence<String>,
        candidate: String
    ) -> String? {
        let candidateKey = candidate.lowercased()
        return existingPaths.first { existing in
            let existingKey = existing.lowercased()
            return candidateKey == existingKey
                || candidateKey.hasPrefix(existingKey + "/")
                || existingKey.hasPrefix(candidateKey + "/")
        }
    }

    private static func insertSkillFile(
        _ data: Data,
        relativePath: String,
        into files: inout [String: Data]
    ) throws {
        let canonical = try canonicalSkillRelativePath(relativePath)
        guard collidingSkillPath(in: files.keys, candidate: canonical) == nil else {
            throw IOSSkillToolError.invalidSkillPath("重复或大小写冲突：\(relativePath)")
        }
        files[canonical] = data
    }

    private static func isSingleSkillMarkdownImportPath(_ workspacePath: String) -> Bool {
        let normalized = workspacePath
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return (normalized as NSString).lastPathComponent.lowercased() == "skill.md"
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

    private static func skillImportErrorJSON(code: String, message: String) -> String {
        json([
            "ok": false,
            "success": false,
            "status": "stale",
            "code": code,
            "error": message,
        ])
    }
}

private enum IOSSkillToolError: LocalizedError {
    case invalidArguments
    case missingArgument(String)
    case skillNotEnabled(String)
    case skillFileMissing(String)
    case sensitiveSkillFile(String)
    case skillFileTooLarge(String, Int)
    case skillFileNotUTF8(String)
    case workspacePathMissing(String)
    case invalidSkillPath(String)
    case invalidSkillPackage([String])
    case unsupportedZip

    var errorDescription: String? {
        switch self {
        case .invalidArguments:
            "Tool arguments must be a JSON object."
        case .missingArgument(let name):
            "\(name) is required"
        case .skillNotEnabled(let name):
            "Skill '\(name)' is not enabled. Call skills_list to see installed and enabled skills."
        case .skillFileMissing(let path):
            "File '\(path)' not found in skill package."
        case .sensitiveSkillFile(let path):
            "File '\(path)' contains MCP connection configuration and cannot be exposed through use_skill. Use mcp_import_from_skill instead."
        case .skillFileTooLarge(let path, let limit):
            "File '\(path)' exceeds the use_skill limit of \(limit) bytes."
        case .skillFileNotUTF8(let path):
            "File '\(path)' is not UTF-8 text."
        case .workspacePathMissing(let path):
            "Workspace path not found: \(path)"
        case .invalidSkillPath(let path):
            "Skill 包含非法路径：\(path)"
        case .invalidSkillPackage(let issues):
            issues.joined(separator: "；")
        case .unsupportedZip:
            "Zip skill import is not supported on iOS yet. Import a SKILL.md or skill folder from Workspace."
        }
    }
}

private struct IOSSkillPackageValidation {
    let name: String?
    let description: String?
    let issues: [String]
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
