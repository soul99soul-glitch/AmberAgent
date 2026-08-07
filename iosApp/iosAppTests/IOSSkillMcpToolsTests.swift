import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSSkillMcpToolsTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    func testInstallBuiltinSkillsSeedsSkillCreatorAndEnablesIt() throws {
        let root = tempRoot()
        let store = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "builtin-skills-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)

        let installed = IOSBuiltinSkills.installIfMissing(into: store, enableWith: settings)

        XCTAssertTrue(installed.contains("skill-creator"))
        XCTAssertTrue(store.listSkillDirNames().contains("skill-creator"))
        XCTAssertTrue(settings.isSkillEnabled("skill-creator"))
        XCTAssertTrue(settings.isSkillEnabled("会议准备"))
        XCTAssertTrue(settings.isSkillEnabled("监控文档"))

        let second = IOSBuiltinSkills.installIfMissing(into: store, enableWith: settings)
        XCTAssertTrue(second.isEmpty, "second install must not overwrite existing builtins")
    }

    func testSkillImportFromWorkspaceAndUseSkill() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-import-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let workspace = IOSWorkspaceStore(
            baseDirectory: tempRoot().appendingPathComponent("workspace", isDirectory: true)
        )
        let mcpDefaults = UserDefaults(suiteName: "mcp-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)
        let mcpManager = IOSMcpManager(sharedSettings: settings, configStore: mcpStore)

        let markdown = """
        ---
        name: "demo-skill"
        description: "Use when testing skill import."
        ---

        # Demo
        Do the demo thing.
        """
        let writeInput = try JSONSerialization.data(
            withJSONObject: [
                "path": "/workspace/skills/demo-skill/SKILL.md",
                "content": markdown,
                "overwrite": true,
            ] as [String: Any],
            options: []
        )
        let writeResult = await workspace.executeTool(
            toolName: "workspace_file_write",
            input: String(data: writeInput, encoding: .utf8) ?? "{}"
        )
        XCTAssertTrue(writeResult.contains(#""ok":true"#), writeResult)

        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore,
            mcpManager: mcpManager
        )

        let imported = await service.execute(
            toolName: "skill_import",
            arguments: #"{"workspace_path":"/workspace/skills/demo-skill/SKILL.md"}"#
        )
        XCTAssertTrue(imported.contains(#""success":true"#), imported)
        XCTAssertTrue(imported.contains("demo-skill"), imported)
        XCTAssertTrue(settings.isSkillEnabled("demo-skill"))

        let listed = await service.execute(toolName: "skills_list", arguments: "{}")
        XCTAssertTrue(listed.contains("demo-skill"), listed)

        let used = await service.execute(
            toolName: "use_skill",
            arguments: #"{"name":"demo-skill"}"#
        )
        XCTAssertTrue(used.contains("AmberAgent Mobile Runtime"), used)
        XCTAssertTrue(used.contains("Do the demo thing."), used)
    }

    func testMcpImportFromSkillPersistsServer() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "mcp-import-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)

        let skillMd = """
        ---
        name: "mcp-pack"
        description: "Use when testing mcp import."
        ---

        # MCP Pack
        """
        let mcpJSON = """
        {
          "mcpServers": {
            "docs": {
              "type": "streamable_http",
              "url": "https://example.com/mcp"
            }
          }
        }
        """
        _ = try skillStore.saveSkillFiles(files: [
            "SKILL.md": skillMd,
            "mcp.json": mcpJSON,
        ])
        settings.setSkillEnabled(name: "mcp-pack", enabled: true)

        let mcpDefaults = UserDefaults(suiteName: "mcp-store-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)
        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)
            ),
            mcpConfigStore: mcpStore,
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        )

        let result = await service.execute(
            toolName: "mcp_import_from_skill",
            arguments: #"{"skill_name":"mcp-pack"}"#
        )
        XCTAssertTrue(result.contains(#""success":true"#), result)
        XCTAssertTrue(mcpStore.servers.contains(where: { $0.name == "docs" }))
    }

    func testChatDeclaresSkillAndMcpManagementTools() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
                documentStore: DocumentAccessStore()
            ),
            autoGenerateResponses: false
        )
        let names = Set(viewModel.currentToolDeclarationNames())
        for tool in [
            "skills_list", "use_skill", "skill_validate", "skill_import", "skill_enable", "skill_disable",
            "mcp_list", "mcp_test", "mcp_import_from_skill", "mcp_call",
        ] {
            XCTAssertTrue(names.contains(tool), "\(tool) should be declared")
        }

        viewModel.inputText = "帮我创建一个本地 skill，并连接 MCP 服务器"
        viewModel.sendMessage()
        let writeNames = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(writeNames.contains("workspace_file_write"))
    }

    func testSkillEnableRejectsMissingSkill() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-enable-missing-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)
            ),
            mcpConfigStore: IOSMcpConfigStore(userDefaults: UserDefaults(suiteName: "mcp-enable-\(UUID().uuidString)")!),
            mcpManager: IOSMcpManager(
                sharedSettings: settings,
                configStore: IOSMcpConfigStore(userDefaults: UserDefaults(suiteName: "mcp-enable-mgr-\(UUID().uuidString)")!)
            )
        )
        let result = await service.execute(
            toolName: "skill_enable",
            arguments: #"{"name":"does-not-exist"}"#
        )
        XCTAssertTrue(result.contains(#""ok":false"#), result)
        XCTAssertFalse(settings.isSkillEnabled("does-not-exist"))
    }

    func testUseSkillWorksWhenFrontmatterNameDiffersFromDirName() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-name-key-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let mcpDefaults = UserDefaults(suiteName: "mcp-name-key-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)

        let markdown = """
        ---
        name: "Demo Skill"
        description: "Use when testing name-key parity."
        ---

        # Demo Skill
        Body for Demo Skill.
        """
        let packageName = try skillStore.saveSkillFiles(files: ["SKILL.md": markdown])
        XCTAssertEqual(packageName, "demo-skill")
        settings.setSkillEnabled(name: packageName, enabled: true)

        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)
            ),
            mcpConfigStore: mcpStore,
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        )

        let listed = await service.execute(toolName: "skills_list", arguments: "{}")
        XCTAssertTrue(listed.contains(#""name":"demo-skill"#), listed)
        XCTAssertFalse(listed.contains(#""name":"Demo Skill"#), listed)

        let usedByDisplay = await service.execute(
            toolName: "use_skill",
            arguments: #"{"name":"Demo Skill"}"#
        )
        XCTAssertTrue(usedByDisplay.contains("Body for Demo Skill."), usedByDisplay)

        let usedByDir = await service.execute(
            toolName: "use_skill",
            arguments: #"{"name":"demo-skill"}"#
        )
        XCTAssertTrue(usedByDir.contains("Body for Demo Skill."), usedByDir)
    }

    func testSkillImportSingleFileAlsoCollectsSiblingMcpJSON() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-sibling-mcp-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let workspace = IOSWorkspaceStore(
            baseDirectory: tempRoot().appendingPathComponent("workspace", isDirectory: true)
        )
        let mcpDefaults = UserDefaults(suiteName: "mcp-sibling-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)

        let markdown = """
        ---
        name: "sibling-pack"
        description: "Use when testing sibling mcp.json import."
        ---

        # Sibling
        """
        let mcpJSON = """
        {
          "mcpServers": {
            "sibling-docs": {
              "type": "streamable_http",
              "url": "https://example.com/sibling"
            }
          }
        }
        """
        for (path, content) in [
            "/workspace/skills/sibling-pack/SKILL.md": markdown,
            "/workspace/skills/sibling-pack/mcp.json": mcpJSON,
        ] {
            let input = try JSONSerialization.data(
                withJSONObject: ["path": path, "content": content, "overwrite": true] as [String: Any],
                options: []
            )
            let writeResult = await workspace.executeTool(
                toolName: "workspace_file_write",
                input: String(data: input, encoding: .utf8) ?? "{}"
            )
            XCTAssertTrue(writeResult.contains(#""ok":true"#), writeResult)
        }

        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore,
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        )
        let imported = await service.execute(
            toolName: "skill_import",
            arguments: #"{"workspace_path":"/workspace/skills/sibling-pack/SKILL.md"}"#
        )
        XCTAssertTrue(imported.contains(#""success":true"#), imported)
        XCTAssertTrue(imported.contains(#""contains_mcp_config":true"#), imported)
        XCTAssertTrue(skillStore.containsMcpConfig(name: "sibling-pack"))
    }

    func testMcpImportFromSkillSkipsExistingServer() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "mcp-skip-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)

        let skillMd = """
        ---
        name: "mcp-skip"
        description: "Use when testing skip-existing mcp import."
        ---

        # MCP Skip
        """
        let mcpJSON = """
        {
          "mcpServers": {
            "docs": {
              "type": "streamable_http",
              "url": "https://example.com/new"
            }
          }
        }
        """
        _ = try skillStore.saveSkillFiles(files: [
            "SKILL.md": skillMd,
            "mcp.json": mcpJSON,
        ])
        settings.setSkillEnabled(name: "mcp-skip", enabled: true)

        let mcpDefaults = UserDefaults(suiteName: "mcp-skip-store-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)
        mcpStore.add(.streamableHTTP(
            name: "docs",
            url: "https://example.com/old",
            headers: [:],
            enabled: true,
            tools: []
        ))

        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)
            ),
            mcpConfigStore: mcpStore,
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        )

        let result = await service.execute(
            toolName: "mcp_import_from_skill",
            arguments: #"{"skill_name":"mcp-skip"}"#
        )
        XCTAssertTrue(result.contains(#""imported_count":0"#), result)
        XCTAssertTrue(result.contains(#""already_exists_count":1"#), result)
        XCTAssertEqual(
            mcpStore.servers.first(where: { $0.name == "docs" })?.url,
            "https://example.com/old"
        )
    }

    func testOralSkillCreatePhraseUnlocksWorkspaceWrite() {
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            localToolExecutor: IOSLocalToolExecutor(
                permissionStore: IOSPermissionStore(userDefaults: isolatedDefaults()),
                documentStore: DocumentAccessStore()
            ),
            autoGenerateResponses: false
        )
        viewModel.inputText = "帮我做一个 skill"
        viewModel.sendMessage()
        let names = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(names.contains("workspace_file_write"))
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-skill-mcp-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        tempDirs.append(url)
        return url
    }

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "IOSSkillMcpToolsTests-\(UUID().uuidString)")!
    }
}
