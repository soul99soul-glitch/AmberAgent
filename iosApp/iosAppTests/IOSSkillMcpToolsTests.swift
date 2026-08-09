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

        XCTAssertEqual(Set(installed), Set(["skill-creator", "visual-svg"]))
        XCTAssertEqual(Set(store.listSkillDirNames()), Set(["skill-creator", "visual-svg"]))
        XCTAssertTrue(settings.isSkillEnabled("skill-creator"))
        XCTAssertFalse(settings.isSkillEnabled("visual-svg"), "optional seed must not auto-enable")
        XCTAssertFalse(settings.isSkillEnabled("会议准备"))
        XCTAssertFalse(settings.isSkillEnabled("监控文档"))

        let second = IOSBuiltinSkills.installIfMissing(into: store, enableWith: settings)
        XCTAssertTrue(second.isEmpty, "second install must not overwrite existing builtins")
    }

    func testOptionalVisualSvgSeedIsDeletableAndRestorable() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        _ = IOSBuiltinSkills.installIfMissing(into: store)

        let factory = try XCTUnwrap(IOSBuiltinSkills.markdown(for: "visual-svg"))
        XCTAssertTrue(factory.contains("show-widget"))
        XCTAssertTrue(factory.contains("diagram"))
        XCTAssertTrue(factory.contains("illustration"))
        XCTAssertFalse(factory.contains("先 use_skill"))
        XCTAssertEqual(try store.readSkillMarkdown(dirName: "visual-svg"), factory)

        let edited = """
        ---
        name: visual-svg
        description: agent 改过的 visual-svg。
        ---

        # 自定义
        """
        _ = try store.saveSkillFiles(files: ["SKILL.md": edited])
        XCTAssertEqual(try store.readSkillMarkdown(dirName: "visual-svg"), edited)

        try IOSBuiltinSkills.restoreFactoryContent(name: "visual-svg", into: store)
        XCTAssertEqual(try store.readSkillMarkdown(dirName: "visual-svg"), factory)

        try store.deleteSkill(dirName: "visual-svg")
        IOSBuiltinSkills.markOptionalSeedRemoved("visual-svg", store: store)
        XCTAssertFalse(store.listSkillDirNames().contains("visual-svg"))

        let reinstall = IOSBuiltinSkills.installIfMissing(into: store)
        XCTAssertFalse(reinstall.contains("visual-svg"))
        XCTAssertFalse(store.listSkillDirNames().contains("visual-svg"))

        try IOSBuiltinSkills.restoreFactoryContent(name: "visual-svg", into: store)
        XCTAssertTrue(store.listSkillDirNames().contains("visual-svg"))
        XCTAssertFalse(IOSBuiltinSkills.isOptionalSeedRemoved("visual-svg", store: store))
    }

    func testSkillImportPreservesDisabledOptionalSeedEnableState() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-import-optional-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let workspace = IOSWorkspaceStore(
            baseDirectory: tempRoot().appendingPathComponent("workspace", isDirectory: true)
        )
        _ = IOSBuiltinSkills.installIfMissing(into: skillStore, enableWith: settings)
        XCTAssertFalse(settings.isSkillEnabled("visual-svg"))

        let factory = try XCTUnwrap(IOSBuiltinSkills.markdown(for: "visual-svg"))
        let writeInput = try JSONSerialization.data(
            withJSONObject: [
                "path": "/workspace/skills/visual-svg/SKILL.md",
                "content": factory.replacingOccurrences(
                    of: "version: 1.0.1",
                    with: "version: 1.0.1-edited"
                ),
                "overwrite": true,
            ] as [String: Any],
            options: []
        )
        let writeResult = await workspace.executeTool(
            toolName: "workspace_file_write",
            input: String(data: writeInput, encoding: .utf8) ?? "{}"
        )
        XCTAssertTrue(writeResult.contains(#""ok":true"#), writeResult)

        let mcpDefaults = UserDefaults(suiteName: "mcp-optional-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)
        let mcpManager = IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore,
            mcpManager: mcpManager
        )
        let prepared = try service.prepareSkillImport(
            arguments: #"{"workspace_path":"/workspace/skills/visual-svg/SKILL.md"}"#
        )
        let imported = try service.applyPreparedSkillImport(prepared)
        XCTAssertTrue(imported.contains(#""success":true"#), imported)
        XCTAssertTrue(imported.contains(#""enabled":false"#), imported)
        XCTAssertFalse(settings.isSkillEnabled("visual-svg"))
    }

    func testInstallBuiltinSkillsRemovesDeprecatedMeetingAndDocumentSeeds() throws {
        let root = tempRoot()
        let store = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "builtin-skills-cleanup-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)

        for name in ["会议准备", "监控文档"] {
            _ = try store.saveSkillFiles(
                files: [
                    "SKILL.md": """
                    ---
                    name: \(name)
                    description: deprecated seed
                    ---

                    # \(name)
                    """
                ],
                allowBuiltinSkill: true
            )
            settings.setSkillEnabled(name: name, enabled: true)
        }

        _ = IOSBuiltinSkills.installIfMissing(into: store, enableWith: settings)

        XCTAssertFalse(store.listSkillDirNames().contains("会议准备"))
        XCTAssertFalse(store.listSkillDirNames().contains("监控文档"))
        XCTAssertTrue(store.listSkillDirNames().contains("skill-creator"))
        XCTAssertTrue(store.listSkillDirNames().contains("visual-svg"))
        XCTAssertFalse(settings.isSkillEnabled("会议准备"))
        XCTAssertFalse(settings.isSkillEnabled("监控文档"))
        XCTAssertFalse(settings.isSkillEnabled("visual-svg"))
    }

    func testSkillPackageImportCanIterateSkillCreatorAndRestoreFactoryBackup() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        _ = IOSBuiltinSkills.installIfMissing(into: store)
        let factory = try store.readSkillMarkdown(dirName: "skill-creator")
        XCTAssertTrue(factory.contains("当用户要创建、更新或迭代"))
        let replacement = """
        ---
        name: skill-creator
        description: 迭代后的 skill-creator 描述。
        ---

        # 迭代版
        Agent 可以覆盖本机 skill-creator。
        """

        _ = try store.saveSkillFiles(files: ["SKILL.md": replacement])
        XCTAssertEqual(try store.readSkillMarkdown(dirName: "skill-creator"), replacement)

        try IOSBuiltinSkills.restoreFactoryContent(name: "skill-creator", into: store)
        let restored = try store.readSkillMarkdown(dirName: "skill-creator")
        XCTAssertTrue(IOSBuiltinSkills.isFactorySnapshot(restored))
        XCTAssertTrue(restored.contains("当用户要创建、更新或迭代"))
        XCTAssertTrue(restored.contains("version: 2.2.0"))
        XCTAssertTrue(restored.contains("allowed-tools: workspace_file_write"))
        XCTAssertTrue(restored.contains("渐进披露"))
        XCTAssertTrue(restored.contains("轻量验收"))
        XCTAssertThrowsError(try store.deleteSkill(dirName: "skill-creator")) { error in
            XCTAssertEqual(error as? IOSSkillFileStoreError, .builtinSkillProtected("skill-creator"))
        }
    }

    func testInstallRefreshesUnmodifiedLegacyEnglishSkillCreator() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        let legacy = IOSBuiltinSkills.legacyEnglishSkillCreatorMarkdown
        _ = try store.saveSkillFiles(files: ["SKILL.md": legacy], allowBuiltinSkill: true)
        XCTAssertTrue(IOSBuiltinSkills.isFactorySnapshot(legacy))

        _ = IOSBuiltinSkills.installIfMissing(into: store)
        let refreshed = try store.readSkillMarkdown(dirName: "skill-creator")
        XCTAssertTrue(refreshed.contains("version: 2.2.0"))
        XCTAssertTrue(refreshed.contains("当用户要创建、更新或迭代"))
        XCTAssertFalse(refreshed.contains("Use when the user wants to create a new AmberAgent skill"))
    }

    func testInstallRefreshesUnmodifiedLegacyChineseSkillCreatorV21() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        let legacy = IOSBuiltinSkills.legacyChineseSkillCreatorMarkdownV21
        _ = try store.saveSkillFiles(
            files: [
                "SKILL.md": legacy,
                "references/kept.md": "keep across factory refresh\n",
            ],
            allowBuiltinSkill: true
        )
        XCTAssertTrue(IOSBuiltinSkills.isFactorySnapshot(legacy))

        _ = IOSBuiltinSkills.installIfMissing(into: store)
        let refreshed = try store.readSkillMarkdown(dirName: "skill-creator")
        XCTAssertTrue(refreshed.contains("version: 2.2.0"))
        XCTAssertTrue(refreshed.contains("渐进披露"))
        XCTAssertFalse(refreshed.contains("version: 2.1.0"))
        let kept = try store.resolveSkillFile(name: "skill-creator", relativePath: "references/kept.md")
        XCTAssertEqual(try String(contentsOf: kept, encoding: .utf8), "keep across factory refresh\n")
    }

    func testSkillImportSingleSkillMarkdownMergesExistingSiblings() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-import-merge-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let workspace = IOSWorkspaceStore(
            baseDirectory: tempRoot().appendingPathComponent("workspace", isDirectory: true)
        )
        let mcpDefaults = UserDefaults(suiteName: "mcp-import-merge-\(UUID().uuidString)")!
        let mcpStore = IOSMcpConfigStore(userDefaults: mcpDefaults)
        let mcpManager = IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        _ = try skillStore.saveSkillFiles(files: [
            "SKILL.md": """
            ---
            name: merge-pack
            description: installed
            ---

            # installed
            """,
            "assets/logo.txt": "logo\n",
        ])
        settings.setSkillEnabled(name: "merge-pack", enabled: true)

        let updated = """
        ---
        name: merge-pack
        description: imported update
        ---

        # imported
        """
        let writeInput = try JSONSerialization.data(
            withJSONObject: [
                "path": "/workspace/skills/merge-pack/SKILL.md",
                "content": updated,
            ]
        )
        _ = await workspace.executeTool(
            toolName: "workspace_file_write",
            input: String(data: writeInput, encoding: .utf8) ?? "{}"
        )

        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore,
            mcpManager: mcpManager
        )
        let prepared = try service.prepareSkillImport(
            arguments: #"{"workspace_path":"/workspace/skills/merge-pack/SKILL.md"}"#
        )
        let imported = try service.applyPreparedSkillImport(prepared)
        XCTAssertTrue(imported.contains(#""success":true"#), imported)
        XCTAssertTrue(try skillStore.readSkillMarkdown(dirName: "merge-pack").contains("imported update"))
        let logo = try skillStore.resolveSkillFile(name: "merge-pack", relativePath: "assets/logo.txt")
        XCTAssertEqual(try String(contentsOf: logo, encoding: .utf8), "logo\n")
    }

    func testInstallPreservesAgentIteratedSkillCreator() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        _ = IOSBuiltinSkills.installIfMissing(into: store)
        let iterated = """
        ---
        name: skill-creator
        description: agent 自己改过的描述。
        ---

        # 自定义迭代
        不要被启动 seed 覆盖。
        """
        _ = try store.saveSkillFiles(files: ["SKILL.md": iterated])

        _ = IOSBuiltinSkills.installIfMissing(into: store)
        XCTAssertEqual(try store.readSkillMarkdown(dirName: "skill-creator"), iterated)
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

        let prepared = try service.prepareSkillImport(
            arguments: #"{"workspace_path":"/workspace/skills/demo-skill/SKILL.md"}"#
        )
        let imported = try service.applyPreparedSkillImport(prepared)
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

    func testSkillImportPreviewApproveApplyRollbackRestoresCompletePackageAndEnableState() async throws {
        let skillStore = IOSSkillFileStore(baseDirectory: tempRoot())
        let defaults = isolatedDefaults()
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let workspace = IOSWorkspaceStore(
            baseDirectory: tempRoot().appendingPathComponent("workspace", isDirectory: true)
        )
        let skillName = "evolving-skill"
        let baseMarkdown = """
        ---
        name: evolving-skill
        description: Base package before self evolution.
        ---

        # Base
        """
        let candidateMarkdown = """
        ---
        name: evolving-skill
        description: Candidate package after self evolution.
        ---

        # Candidate
        """
        _ = try skillStore.saveSkillFiles(files: [
            "SKILL.md": baseMarkdown,
            "references/base.txt": "base sibling\n",
        ])
        settings.setSkillEnabled(name: skillName, enabled: false)
        try await writeWorkspaceText(
            candidateMarkdown,
            path: "/workspace/skills/evolving-skill/SKILL.md",
            workspace: workspace
        )
        try await writeWorkspaceText(
            "candidate sibling\n",
            path: "/workspace/skills/evolving-skill/references/candidate.txt",
            workspace: workspace
        )

        let mcpStore = IOSMcpConfigStore(userDefaults: isolatedDefaults())
        let runtime = ChatToolRuntime(
            settingsStore: SettingsStore(userDefaults: defaults),
            sharedSettings: settings,
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore),
            skillFileStore: skillStore,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore
        )
        let arguments = #"{"workspace_path":"/workspace/skills/evolving-skill"}"#
        let toolCall = UIMessagePart.Tool(
            toolCallId: "skill-import-contract-\(UUID().uuidString)",
            toolName: "skill_import",
            input: arguments,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let pending = makeSkillImportPending(toolCall: toolCall)

        let globalKey = "app.amber.ios.globalAutoApprove"
        let highRiskKey = "app.amber.ios.highRiskAutoApprove"
        let standardDefaults = UserDefaults.standard
        let previousGlobal = standardDefaults.object(forKey: globalKey)
        let previousHighRisk = standardDefaults.object(forKey: highRiskKey)
        defer {
            restore(previousGlobal, forKey: globalKey, in: standardDefaults)
            restore(previousHighRisk, forKey: highRiskKey, in: standardDefaults)
        }
        standardDefaults.set(true, forKey: globalKey)
        standardDefaults.set(true, forKey: highRiskKey)

        let previewResult = await runtime.execute(
            ChatPendingToolCall(kind: .advanced, toolCall: toolCall),
            context: pending
        )
        guard case .waitingForApproval(.mcp(let request)) = previewResult,
              let preview = request.skillImportPreview else {
            return XCTFail("skill_import must pause on its explicit approval preview")
        }
        XCTAssertEqual(preview.skillName, skillName)
        XCTAssertEqual(preview.mutationKind, .update)
        XCTAssertEqual(
            Set(preview.changedFiles.map(\.path)),
            Set(["SKILL.md", "references/base.txt", "references/candidate.txt"])
        )
        XCTAssertEqual(try skillStore.readSkillMarkdown(dirName: skillName), baseMarkdown)
        XCTAssertEqual(
            try String(
                contentsOf: skillStore.resolveSkillFile(
                    name: skillName,
                    relativePath: "references/base.txt"
                ),
                encoding: .utf8
            ),
            "base sibling\n"
        )
        XCTAssertFalse(settings.isSkillEnabled(skillName))
        XCTAssertFalse(try skillStore.rollbackAvailability(name: skillName).canRollback)

        let prepared = try XCTUnwrap(
            runtime.takePreparedSkillImportForApproval(toolCallId: toolCall.toolCallId)
        )
        XCTAssertEqual(prepared.preview.candidateHash, preview.candidateHash)
        _ = await runtime.finishMcpApproval(
            pending: pending,
            allow: true,
            preparedSkillImport: prepared
        )

        XCTAssertEqual(try skillStore.readSkillMarkdown(dirName: skillName), candidateMarkdown)
        XCTAssertFalse(FileManager.default.fileExists(atPath: try skillStore.resolveSkillFile(
            name: skillName,
            relativePath: "references/base.txt"
        ).path))
        XCTAssertEqual(
            try String(
                contentsOf: skillStore.resolveSkillFile(
                    name: skillName,
                    relativePath: "references/candidate.txt"
                ),
                encoding: .utf8
            ),
            "candidate sibling\n"
        )
        XCTAssertFalse(settings.isSkillEnabled(skillName), "an update keeps the prior enable state")
        guard case .available(let manifest) = try skillStore.rollbackAvailability(name: skillName) else {
            return XCTFail("approved import must publish one rollback slot")
        }
        XCTAssertEqual(manifest.kind, .update)
        XCTAssertFalse(manifest.enabledBefore)

        settings.setSkillEnabled(name: skillName, enabled: true)
        let rollback = try skillStore.rollbackSkillPackage(
            name: skillName,
            expectedManifest: manifest
        )
        settings.setSkillEnabled(
            name: rollback.manifest.name,
            enabled: rollback.manifest.enabledBefore
        )

        XCTAssertEqual(try skillStore.readSkillMarkdown(dirName: skillName), baseMarkdown)
        XCTAssertEqual(
            try String(
                contentsOf: skillStore.resolveSkillFile(
                    name: skillName,
                    relativePath: "references/base.txt"
                ),
                encoding: .utf8
            ),
            "base sibling\n"
        )
        XCTAssertFalse(FileManager.default.fileExists(atPath: try skillStore.resolveSkillFile(
            name: skillName,
            relativePath: "references/candidate.txt"
        ).path))
        XCTAssertFalse(settings.isSkillEnabled(skillName))
        XCTAssertFalse(try skillStore.rollbackAvailability(name: skillName).canRollback)
    }

    func testSkillImportRejectsStaleBaseOrCandidateWithoutMutation() async throws {
        do {
            let skillStore = IOSSkillFileStore(baseDirectory: tempRoot())
            let settings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
            let workspace = IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("stale-base-workspace", isDirectory: true)
            )
            _ = try skillStore.saveSkillFiles(files: [
                "SKILL.md": skillMarkdown(name: "stale-base", version: "base"),
                "references/state.txt": "base\n",
            ])
            settings.setSkillEnabled(name: "stale-base", enabled: false)
            try await writeWorkspaceText(
                skillMarkdown(name: "stale-base", version: "candidate"),
                path: "/workspace/skills/stale-base/SKILL.md",
                workspace: workspace
            )
            let service = makeSkillService(
                skillStore: skillStore,
                settings: settings,
                workspace: workspace
            )
            let prepared = try service.prepareSkillImport(
                arguments: #"{"workspace_path":"/workspace/skills/stale-base"}"#
            )

            let manualMarkdown = skillMarkdown(name: "stale-base", version: "manual-live-change")
            _ = try skillStore.saveSkillFiles(files: [
                "SKILL.md": manualMarkdown,
                "references/state.txt": "manual\n",
            ])
            let rollbackBeforeApply = try skillStore.rollbackAvailability(name: "stale-base")
            let result = try service.applyPreparedSkillImport(prepared)
            XCTAssertEqual(try jsonObject(result)["code"] as? String, "stale_base")
            XCTAssertEqual(try skillStore.readSkillMarkdown(dirName: "stale-base"), manualMarkdown)
            XCTAssertEqual(
                try String(
                    contentsOf: skillStore.resolveSkillFile(
                        name: "stale-base",
                        relativePath: "references/state.txt"
                    ),
                    encoding: .utf8
                ),
                "manual\n"
            )
            XCTAssertEqual(
                try skillStore.rollbackAvailability(name: "stale-base"),
                rollbackBeforeApply
            )
            XCTAssertFalse(settings.isSkillEnabled("stale-base"))
        }

        do {
            let skillStore = IOSSkillFileStore(baseDirectory: tempRoot())
            let settings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
            let workspace = IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("stale-candidate-workspace", isDirectory: true)
            )
            let baseMarkdown = skillMarkdown(name: "stale-candidate", version: "base")
            _ = try skillStore.saveSkillFiles(files: [
                "SKILL.md": baseMarkdown,
                "references/state.txt": "base\n",
            ])
            settings.setSkillEnabled(name: "stale-candidate", enabled: true)
            try await writeWorkspaceText(
                skillMarkdown(name: "stale-candidate", version: "candidate-one"),
                path: "/workspace/skills/stale-candidate/SKILL.md",
                workspace: workspace
            )
            let service = makeSkillService(
                skillStore: skillStore,
                settings: settings,
                workspace: workspace
            )
            let prepared = try service.prepareSkillImport(
                arguments: #"{"workspace_path":"/workspace/skills/stale-candidate"}"#
            )
            let rollbackBeforeApply = try skillStore.rollbackAvailability(name: "stale-candidate")

            try await writeWorkspaceText(
                skillMarkdown(name: "stale-candidate", version: "candidate-two"),
                path: "/workspace/skills/stale-candidate/SKILL.md",
                workspace: workspace
            )
            let result = try service.applyPreparedSkillImport(prepared)
            XCTAssertEqual(try jsonObject(result)["code"] as? String, "stale_candidate")
            XCTAssertEqual(try skillStore.readSkillMarkdown(dirName: "stale-candidate"), baseMarkdown)
            XCTAssertEqual(
                try String(
                    contentsOf: skillStore.resolveSkillFile(
                        name: "stale-candidate",
                        relativePath: "references/state.txt"
                    ),
                    encoding: .utf8
                ),
                "base\n"
            )
            XCTAssertEqual(
                try skillStore.rollbackAvailability(name: "stale-candidate"),
                rollbackBeforeApply
            )
            XCTAssertTrue(settings.isSkillEnabled("stale-candidate"))
        }
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

    /// P0-a: the chat declares the resident skill/MCP surface up front and
    /// defers the mutating/management tools (skill_validate/import/enable/
    /// disable, mcp_test, mcp_import_from_skill) behind tool_search in the
    /// default (>40 tools) config.
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
            "skills_list", "use_skill",
            "mcp_list", "mcp_describe_tool", "mcp_call",
        ] {
            XCTAssertTrue(names.contains(tool), "\(tool) should be declared")
        }
        for tool in [
            "skill_validate", "skill_import", "skill_enable", "skill_disable",
            "mcp_test", "mcp_import_from_skill",
        ] {
            XCTAssertFalse(names.contains(tool), "\(tool) should be deferred behind tool_search")
        }
        XCTAssertTrue(names.contains("tool_search"))

        // The deferred set is reachable through tool_search (next-step exposure).
        let bridge = viewModel.toolExposureBridgeForTesting()
        let payload = bridge?.executeToolSearch(argumentsJson: #"{"query":"skill_import","limit":1}"#) ?? ""
        XCTAssertTrue(payload.contains("skill_import"))

        viewModel.inputText = "帮我创建一个本地 skill，并连接 MCP 服务器"
        viewModel.sendMessage()
        let writeNames = Set(viewModel.currentToolDeclarationNames())
        XCTAssertTrue(writeNames.contains("workspace_file_write"))
    }

    // MARK: - G2: schema persistence + mcp_describe_tool

    func testMcpToolSchemaIsPersistedAndReloaded() throws {
        let defaults = UserDefaults(suiteName: "mcp-schema-\(UUID().uuidString)")!
        let store = IOSMcpConfigStore(userDefaults: defaults)
        store.add(.streamableHTTP(name: "docs", url: "https://example.com/mcp", tools: []))
        let longDescription = String(repeating: "schema-detail-", count: 300)
        let schemaData = try JSONSerialization.data(withJSONObject: [
            "type": "object",
            "properties": ["q": ["type": "string", "description": longDescription]],
        ], options: [.sortedKeys])
        let schema = try XCTUnwrap(String(data: schemaData, encoding: .utf8))
        store.mergeDiscoveredTools(named: "docs", tools: [
            IOSMcpTool(name: "search", description: "Search docs", inputSchema: schema),
        ])

        // A fresh store over the same defaults must see the persisted schema.
        let reloaded = IOSMcpConfigStore(userDefaults: defaults)
        let tool = try XCTUnwrap(reloaded.servers.first(where: { $0.name == "docs" })?.tools.first)
        XCTAssertEqual(tool.name, "search")
        XCTAssertEqual(tool.inputSchema, schema)
    }

    func testMcpToolLegacyPersistedDataWithoutSchemaStillLoads() throws {
        let defaults = UserDefaults(suiteName: "mcp-legacy-\(UUID().uuidString)")!
        let legacyJSON = """
        [{"name":"docs","url":"https://example.com/mcp","transport":"streamable_http","headers":{},"enabled":true,"tools":[{"name":"search","description":"Search docs","enabled":true}]}]
        """
        defaults.set(Data(legacyJSON.utf8), forKey: "app.amber.ios.mcpServers")

        let store = IOSMcpConfigStore(userDefaults: defaults)
        let tool = try XCTUnwrap(store.servers.first?.tools.first)
        XCTAssertEqual(tool.name, "search")
        XCTAssertEqual(tool.description, "Search docs")
        XCTAssertNil(tool.inputSchema, "legacy rows decode with a nil schema")
    }

    func testMcpDescribeToolReturnsDescriptionAndSchema() async throws {
        let longDescription = String(repeating: "schema-detail-", count: 300)
        let schemaData = try JSONSerialization.data(withJSONObject: [
            "type": "object",
            "properties": ["q": ["type": "string", "description": longDescription]],
        ], options: [.sortedKeys])
        let schema = try XCTUnwrap(String(data: schemaData, encoding: .utf8))
        let manager = IOSMcpManager(serverProvider: {
            [
                .streamableHTTP(
                    name: "docs",
                    url: "https://example.com/mcp",
                    tools: [
                        IOSMcpTool(name: "search", description: "Search docs", inputSchema: schema),
                        IOSMcpTool(name: "write_note", description: "Write a note"),
                    ]
                ),
            ]
        })
        let service = IOSSkillMcpToolService(
            skillStore: IOSSkillFileStore(baseDirectory: tempRoot()),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            workspaceStore: IOSWorkspaceStore(baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)),
            mcpConfigStore: IOSMcpConfigStore(userDefaults: UserDefaults(suiteName: "mcp-describe-store-\(UUID().uuidString)")!),
            mcpManager: manager
        )

        let result = await service.execute(
            toolName: "mcp_describe_tool",
            arguments: #"{"server":"docs","tool":"search"}"#
        )

        XCTAssertTrue(result.contains(#""ok":true"#), result)
        XCTAssertTrue(result.contains("Search docs"), result)
        XCTAssertTrue(result.contains(#""q""#), result)
        XCTAssertTrue(result.contains(#""input_schema""#), result)
        XCTAssertTrue(result.contains(#""untrusted":true"#), result)
        let resultData = try XCTUnwrap(result.data(using: .utf8))
        let resultObject = try XCTUnwrap(JSONSerialization.jsonObject(with: resultData) as? [String: Any])
        let inputSchema = try XCTUnwrap(resultObject["input_schema"] as? [String: Any])
        let properties = try XCTUnwrap(inputSchema["properties"] as? [String: Any])
        let query = try XCTUnwrap(properties["q"] as? [String: Any])
        XCTAssertEqual(query["description"] as? String, longDescription)
    }

    func testMcpDescribeToolReturnsStructuredErrorForIncompletePersistedSchema() async {
        let manager = IOSMcpManager(serverProvider: {
            [.streamableHTTP(
                name: "docs",
                url: "https://example.com/mcp",
                tools: [IOSMcpTool(name: "broken", description: nil, inputSchema: #"{"type":"object""#)]
            )]
        })
        let service = IOSSkillMcpToolService(
            skillStore: IOSSkillFileStore(baseDirectory: tempRoot()),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            workspaceStore: IOSWorkspaceStore(baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)),
            mcpConfigStore: IOSMcpConfigStore(userDefaults: UserDefaults(suiteName: "mcp-describe-invalid-\(UUID().uuidString)")!),
            mcpManager: manager
        )

        let result = await service.execute(
            toolName: "mcp_describe_tool",
            arguments: #"{"server":"docs","tool":"broken"}"#
        )

        XCTAssertTrue(result.contains(#""ok":false"#), result)
        XCTAssertTrue(result.contains(#""code":"invalid_persisted_schema""#), result)
    }

    func testMcpDescribeToolErrorsListValidValues() async throws {
        let manager = IOSMcpManager(serverProvider: {
            [
                .streamableHTTP(
                    name: "docs",
                    url: "https://example.com/mcp",
                    tools: [
                        IOSMcpTool(name: "search", description: "Search docs"),
                        IOSMcpTool(name: "write_note", description: "Write a note"),
                    ]
                ),
            ]
        })
        let service = IOSSkillMcpToolService(
            skillStore: IOSSkillFileStore(baseDirectory: tempRoot()),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            workspaceStore: IOSWorkspaceStore(baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)),
            mcpConfigStore: IOSMcpConfigStore(userDefaults: UserDefaults(suiteName: "mcp-describe-err-\(UUID().uuidString)")!),
            mcpManager: manager
        )

        let unknownServer = await service.execute(
            toolName: "mcp_describe_tool",
            arguments: #"{"server":"nope","tool":"search"}"#
        )
        XCTAssertTrue(unknownServer.contains(#""ok":false"#), unknownServer)
        XCTAssertTrue(unknownServer.contains("MCP server not found"), unknownServer)
        XCTAssertTrue(unknownServer.contains(#""valid_servers":["docs"]"#), unknownServer)

        let unknownTool = await service.execute(
            toolName: "mcp_describe_tool",
            arguments: #"{"server":"docs","tool":"nope"}"#
        )
        XCTAssertTrue(unknownTool.contains(#""ok":false"#), unknownTool)
        XCTAssertTrue(unknownTool.contains("MCP tool not found"), unknownTool)
        XCTAssertTrue(unknownTool.contains(#""valid_tools":["search","write_note"]"#), unknownTool)
    }

    // MARK: - G2: MCP catalog injection contract

    private func makeContextBuilder(mcpTools: [IOSMcpDiscoveredTool], mcpGateEnabled: Bool = true) -> ChatRuntimeContextBuilder {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        sharedSettings.setCapabilityGate(.mcp, enabled: mcpGateEnabled)
        var builder = ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: mcpTools,
            miniAppRepository: IOSMiniAppRepository(baseDirectory: tempRoot()),
            miniAppRuntimeEnabled: false
        )
        builder.skillFileStore = IOSSkillFileStore(baseDirectory: tempRoot())
        return builder
    }

    private func injectedSystemText(_ builder: ChatRuntimeContextBuilder) -> String {
        let prepared = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "hello")],
            coalesceSystemMessages: true
        )
        return prepared
            .filter { $0.role == MessageRole.system }
            .map { $0.toText() }
            .joined(separator: "\n")
    }

    func testMcpCatalogInjectionHasNoCountCapAndInlinesSmallServerSchemas() {
        var tools: [IOSMcpDiscoveredTool] = []
        for index in 0..<3 {
            tools.append(IOSMcpDiscoveredTool(
                serverName: "small",
                tool: IOSMcpTool(name: "t\(index)", description: "Small tool \(index)", inputSchema: #"{"type":"object"}"#)
            ))
        }
        for index in 0..<60 {
            tools.append(IOSMcpDiscoveredTool(
                serverName: "big",
                tool: IOSMcpTool(name: "b\(index)", description: "Big tool \(index) does something")
            ))
        }

        let text = injectedSystemText(makeContextBuilder(mcpTools: tools))

        // No more `.prefix(40)`: the last tool of the 63-tool catalog is listed.
        XCTAssertTrue(text.contains("tool=b59"), text)
        XCTAssertTrue(text.contains("tool=t2"), text)
        // Small server (<=5 tools, <2KB schema total) inlines its schema.
        XCTAssertTrue(text.contains("schema={\"type\":\"object\"}"), text)
        // On-demand schema + naming + untrusted semantics are all stated.
        XCTAssertTrue(text.contains("mcp_describe_tool"), text)
        XCTAssertTrue(text.contains("Do not invent MCP servers or tool names"), text)
        XCTAssertTrue(text.contains("untrusted context"), text)
        // P0-b 管线闭环：直接调用路径（mcp__server__tool + tool_search）已写进引导。
        XCTAssertTrue(text.contains("mcp__server__tool"), text)
        XCTAssertTrue(text.contains("tool_search"), text)
        XCTAssertFalse(text.contains("未列出"), text)
    }

    func testMcpCatalogInjectionTruncatesUnderCharBudgetWithHint() {
        let longDescription = String(repeating: "这是一段很长的工具描述文本用于撑爆注入预算。", count: 80)
        var tools: [IOSMcpDiscoveredTool] = []
        for index in 0..<40 {
            tools.append(IOSMcpDiscoveredTool(
                serverName: "verbose",
                tool: IOSMcpTool(name: "v\(index)", description: longDescription)
            ))
        }

        let text = injectedSystemText(makeContextBuilder(mcpTools: tools))

        let listedLines = text.components(separatedBy: "- server=").count - 1
        XCTAssertLessThan(listedLines, 40, "catalog must be cut by the character budget")
        XCTAssertGreaterThan(listedLines, 0)
        XCTAssertTrue(text.contains("未列出"), text)
        XCTAssertTrue(text.contains("mcp_list"), text)
        XCTAssertTrue(text.contains("mcp_describe_tool"), text)
    }

    func testMcpCatalogInjectionRemainsAvailableWhenLegacyGateIsDisabled() {
        var tools: [IOSMcpDiscoveredTool] = []
        tools.append(IOSMcpDiscoveredTool(serverName: "docs", tool: IOSMcpTool(name: "search", description: "Search docs")))
        let text = injectedSystemText(makeContextBuilder(mcpTools: tools, mcpGateEnabled: false))
        XCTAssertTrue(text.contains("mcp-tools"), "capability gates are persisted-data compatibility only")
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

    func testUseSkillDoesNotExposeMcpConfigOrOversizedFiles() async throws {
        let root = tempRoot()
        let skillStore = IOSSkillFileStore(baseDirectory: root)
        let defaults = UserDefaults(suiteName: "skill-sensitive-file-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let markdown = """
        ---
        name: "guarded-skill"
        description: "Use when testing guarded skill reads."
        ---

        # Guarded
        """
        _ = try skillStore.saveSkillFiles(files: [
            "SKILL.md": markdown,
            "mcp.json": #"{"headers":{"Authorization":"Bearer secret-value"}}"#,
            "references/large.txt": String(repeating: "x", count: 256 * 1024 + 1),
        ])
        settings.setSkillEnabled(name: "guarded-skill", enabled: true)
        let mcpStore = IOSMcpConfigStore(
            userDefaults: UserDefaults(suiteName: "skill-sensitive-mcp-\(UUID().uuidString)")!
        )
        let service = IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)
            ),
            mcpConfigStore: mcpStore,
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        )

        let sensitive = await service.execute(
            toolName: "use_skill",
            arguments: #"{"name":"guarded-skill","path":"mcp.json"}"#
        )
        XCTAssertTrue(sensitive.contains(#""ok":false"#), sensitive)
        XCTAssertFalse(sensitive.contains("secret-value"), sensitive)

        let oversized = await service.execute(
            toolName: "use_skill",
            arguments: #"{"name":"guarded-skill","path":"references/large.txt"}"#
        )
        XCTAssertTrue(oversized.contains(#""ok":false"#), oversized)
        XCTAssertTrue(oversized.contains("exceeds the use_skill limit"), oversized)
    }

    func testMcpTestTargetsOneServerAndRedactsURLSecrets() async {
        let defaults = UserDefaults(suiteName: "mcp-targeted-\(UUID().uuidString)")!
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let mcpStore = IOSMcpConfigStore(
            userDefaults: UserDefaults(suiteName: "mcp-targeted-store-\(UUID().uuidString)")!
        )
        let targetClient = SkillMcpFakeClient()
        let otherClient = SkillMcpFakeClient()
        let manager = IOSMcpManager(
            serverProvider: {
                [
                    .streamableHTTP(
                        name: "target",
                        url: "https://user:password@example.com/mcp?token=secret"
                    ),
                    .streamableHTTP(name: "other", url: "https://example.com/other"),
                ]
            },
            clientFactory: { config in
                config.name == "target" ? targetClient : otherClient
            }
        )
        let service = IOSSkillMcpToolService(
            skillStore: IOSSkillFileStore(baseDirectory: tempRoot()),
            sharedSettings: settings,
            workspaceStore: IOSWorkspaceStore(
                baseDirectory: tempRoot().appendingPathComponent("ws", isDirectory: true)
            ),
            mcpConfigStore: mcpStore,
            mcpManager: manager
        )

        let result = await service.execute(
            toolName: "mcp_test",
            arguments: #"{"server_id":"target"}"#
        )

        XCTAssertTrue(targetClient.didConnect)
        XCTAssertFalse(otherClient.didConnect)
        XCTAssertTrue(result.contains(#""status":"connected""#), result)
        let data = result.data(using: .utf8)
        let object = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        let server = object?["server"] as? [String: Any]
        XCTAssertEqual(server?["url"] as? String, "https://example.com/mcp")
        XCTAssertFalse(result.contains("password"), result)
        XCTAssertFalse(result.contains("token=secret"), result)
    }

    func testSkillImportRootSingleFileAlsoCollectsSiblingMcpJSON() async throws {
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
            "/workspace/SKILL.md": markdown,
            "/workspace/mcp.json": mcpJSON,
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
        let prepared = try service.prepareSkillImport(
            arguments: #"{"workspace_path":"/workspace/SKILL.md"}"#
        )
        let imported = try service.applyPreparedSkillImport(prepared)
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

    private func writeWorkspaceText(
        _ content: String,
        path: String,
        workspace: IOSWorkspaceStore
    ) async throws {
        let input = try JSONSerialization.data(
            withJSONObject: [
                "path": path,
                "content": content,
                "overwrite": true,
            ] as [String: Any],
            options: []
        )
        let result = await workspace.executeTool(
            toolName: "workspace_file_write",
            input: String(data: input, encoding: .utf8) ?? "{}"
        )
        XCTAssertTrue(result.contains(#""ok":true"#), result)
    }

    private func makeSkillService(
        skillStore: IOSSkillFileStore,
        settings: IOSSharedSettingsStore,
        workspace: IOSWorkspaceStore
    ) -> IOSSkillMcpToolService {
        let mcpStore = IOSMcpConfigStore(userDefaults: isolatedDefaults())
        return IOSSkillMcpToolService(
            skillStore: skillStore,
            sharedSettings: settings,
            workspaceStore: workspace,
            mcpConfigStore: mcpStore,
            mcpManager: IOSMcpManager(sharedSettings: settings, configStore: mcpStore)
        )
    }

    private func makeSkillImportPending(toolCall: UIMessagePart.Tool) -> ChatPendingToolApproval {
        let model = Model(
            modelId: "skill-import-contract",
            displayName: "Skill Import Contract",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let assistant = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [toolCall],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return ChatPendingToolApproval(
            toolCall: toolCall,
            providerSetting: IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: "https://example.com/v1",
                apiKey: "test-key"
            ),
            params: params,
            runId: "run-skill-import-contract",
            startedAt: 1,
            inputDigest: "digest",
            conversationId: nil,
            baseMessages: [assistant]
        )
    }

    private func skillMarkdown(name: String, version: String) -> String {
        """
        ---
        name: \(name)
        description: \(version) package.
        ---

        # \(version)
        """
    }

    private func jsonObject(_ text: String) throws -> [String: Any] {
        let data = try XCTUnwrap(text.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func restore(_ value: Any?, forKey key: String, in defaults: UserDefaults) {
        if let value {
            defaults.set(value, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
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

@MainActor
private final class SkillMcpFakeClient: IOSMcpClienting {
    var didConnect = false

    func connect(config: IOSMcpServerConfig) async throws -> Bool {
        didConnect = true
        return true
    }

    func listTools() async throws -> [IOSMcpTool] { [] }
    func callTool(name: String, arguments: [String: Any]) async throws -> String { "" }
    func disconnect() {}
}
