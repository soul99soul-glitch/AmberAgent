import XCTest
@testable import iosApp

@MainActor
final class NovelCreationViewModelTests: XCTestCase {
    func testSessionOperationRequiresTheMatchingOwnerToReleaseBusyState() {
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: InMemoryNovelProjectRepository())
        )
        let ownerID = UUID()

        XCTAssertTrue(viewModel.acquireSessionOperation(ownerID: ownerID))
        XCTAssertTrue(viewModel.isPerforming)
        XCTAssertFalse(viewModel.acquireSessionOperation(ownerID: UUID()))

        viewModel.releaseSessionOperation(ownerID: UUID())
        XCTAssertTrue(viewModel.isPerforming)

        viewModel.releaseSessionOperation(ownerID: ownerID)
        XCTAssertFalse(viewModel.isPerforming)
    }

    func testCreateMaterialAndReloadThroughDeepInterface() async throws {
        let repository = InMemoryNovelProjectRepository()
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )

        let projectID = await viewModel.createProject(
            name: "潮汐城",
            mode: .blank
        )
        let createdID = try XCTUnwrap(projectID)
        await viewModel.saveMaterial(
            materialID: nil,
            kind: .world,
            title: "潮汐法则",
            content: "每次退潮都会暴露一条旧街。",
            tags: ["潮汐", "城市"],
            injectionMode: .always
        )

        XCTAssertNil(viewModel.errorMessage)
        XCTAssertEqual(viewModel.projectSnapshot?.project.id, createdID)
        XCTAssertEqual(viewModel.activeMaterials.count, 1)

        let restarted = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await restarted.loadProjects(selecting: createdID)

        XCTAssertEqual(restarted.projectSnapshot?.materials.count, 1)
        XCTAssertEqual(restarted.projectSnapshot?.materialRevisions.first?.title, "潮汐法则")
        XCTAssertEqual(restarted.projectSnapshot?.materialRevisions.first?.injectionMode, .always)
    }

    func testModelPolicyAndMaterialDeletionReloadFromRepository() async throws {
        let repository = InMemoryNovelProjectRepository()
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        let createdProjectID = await viewModel.createProject(name: "星港", mode: .blank)
        let projectID = try XCTUnwrap(createdProjectID)
        await viewModel.saveMaterial(
            materialID: nil,
            kind: .character,
            title: "林澈",
            content: "修理废弃导航仪。",
            tags: [],
            injectionMode: .smart
        )
        let materialID = try XCTUnwrap(viewModel.activeMaterials.first?.id)

        await viewModel.setModelPolicy(.fixed(providerID: "provider-1", modelID: "model-1"))
        await viewModel.deleteMaterial(materialID)

        let loaded = try await repository.loadProject(id: projectID)
        XCTAssertEqual(
            loaded.document.project.modelPolicy,
            .fixed(providerID: "provider-1", modelID: "model-1")
        )
        XCTAssertTrue(try XCTUnwrap(loaded.document.materials.first).isDeleted)
        XCTAssertFalse(loaded.document.materialRevisions.isEmpty)
        XCTAssertTrue(viewModel.activeMaterials.isEmpty)
    }

    func testFailedInjectionPreviewDoesNotReuseThePreviousResult() async throws {
        let repository = InMemoryNovelProjectRepository()
        let adapter = ScriptedNovelModelAdapter(resolvedModel: NovelResolvedModel(
            providerID: "provider",
            ownerProviderID: "provider",
            modelID: "model",
            wireModelID: "model",
            displayName: "Model",
            contextWindowTokens: 32_000
        ))
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository, modelRunner: adapter)
        )
        let createdProjectID = await viewModel.createProject(name: "预览", mode: .blank)
        let projectID = try XCTUnwrap(createdProjectID)
        let branchID = try XCTUnwrap(viewModel.selectedBranchID)

        func request(inputBudgetTokens: Int) -> NovelInjectionPreviewRequest {
            NovelInjectionPreviewRequest(
                projectID: projectID,
                branchID: branchID,
                kind: .discussion,
                mode: .discussPlan,
                granularity: nil,
                userText: "讨论下一步",
                sourceChapterVersionID: nil,
                injectionOverrides: .none,
                inputBudgetTokens: inputBudgetTokens
            )
        }

        let first = await viewModel.previewInjection(request(inputBudgetTokens: 8_000))
        XCTAssertNotNil(first)
        XCTAssertEqual(viewModel.injectionPreview, first)

        let failed = await viewModel.previewInjection(request(inputBudgetTokens: 0))
        XCTAssertNil(failed)
        XCTAssertNil(viewModel.injectionPreview)
        XCTAssertNotNil(viewModel.errorMessage)
    }

    func testBranchActionsUseCurrentRevisionAndSelectFork() async throws {
        let repository = InMemoryNovelProjectRepository()
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        _ = try await repository.createProject(document)
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await viewModel.loadProjects(selecting: document.project.id)

        let createdBranchID = await viewModel.forkBranch(
            from: document.branches[0].id,
            checkpointID: document.branches[0].headCheckpointID,
            name: "另一种潮汐"
        )

        let forkID = try XCTUnwrap(viewModel.selectedBranchID)
        XCTAssertEqual(createdBranchID, forkID)
        XCTAssertNotEqual(forkID, document.branches[0].id)
        XCTAssertEqual(viewModel.activeBranches.count, 2)
        await viewModel.renameBranch(forkID, name: "红月线")
        await viewModel.setMainBranch(forkID)

        XCTAssertEqual(viewModel.projectSnapshot?.project.mainBranchID, forkID)
        XCTAssertEqual(
            viewModel.projectSnapshot?.branches.first(where: { $0.id == forkID })?.name,
            "红月线"
        )
        XCTAssertNil(viewModel.errorMessage)
    }

    func testPackageExportDeleteAndImportRestoresSemanticSnapshot() async throws {
        let repository = InMemoryNovelProjectRepository()
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        let createdProjectID = await viewModel.createProject(name: "纸灯", mode: .blank)
        let projectID = try XCTUnwrap(createdProjectID)
        await viewModel.saveMaterial(
            materialID: nil,
            kind: .masterOutline,
            title: "主线",
            content: "一个守灯人寻找熄灭的太阳。",
            tags: [],
            injectionMode: .always
        )
        let before = try await repository.loadProject(id: projectID)
        let exportedArtifact = await viewModel.exportProjectPackage()
        let artifact = try XCTUnwrap(exportedArtifact)

        await viewModel.deleteProject()
        let afterDeletion = try await repository.listProjects()
        XCTAssertTrue(afterDeletion.isEmpty)
        let importResult = await viewModel.importProject(artifact.data, choice: .reject)

        let restored = try await repository.loadProject(id: projectID)
        XCTAssertEqual(importResult, .selected(projectID))
        XCTAssertEqual(restored.document, before.document)
        XCTAssertEqual(viewModel.selectedProjectID, projectID)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testUnavailableProjectDeletesFromItsSummaryWithoutLoading() async throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let document = try NovelTestFixtures.document()
        _ = try await repository.createProject(document)
        let primaryURL = root.appendingPathComponent("projects", isDirectory: true)
            .appendingPathComponent("\(document.project.id.description).json")
        try Data("corrupt".utf8).write(to: primaryURL, options: [.atomic])
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await viewModel.loadProjects()
        let unavailable = try XCTUnwrap(viewModel.projects.first)
        XCTAssertNotNil(unavailable.loadError)
        XCTAssertNil(viewModel.projectSnapshot)

        await viewModel.deleteProject(unavailable)

        XCTAssertTrue(viewModel.projects.isEmpty)
        XCTAssertNil(viewModel.errorMessage)
        let remainingProjects = try await repository.listProjects()
        XCTAssertTrue(remainingProjects.isEmpty)
    }

    func testKeepBothImportSelectsRemappedProjectWithoutReplacingSource() async throws {
        let repository = InMemoryNovelProjectRepository()
        let source = try NovelTestFixtures.document()
        _ = try await repository.createProject(source)
        let artifact = try NovelProjectPackageCodec.encode(source)
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await viewModel.loadProjects(selecting: source.project.id)

        let importResult = await viewModel.importProject(artifact.data, choice: .keepBoth)

        let selectedID = try XCTUnwrap(viewModel.selectedProjectID)
        XCTAssertEqual(importResult, .selected(selectedID))
        XCTAssertNotEqual(selectedID, source.project.id)
        let projects = try await repository.listProjects()
        XCTAssertEqual(projects.count, 2)
        XCTAssertEqual(viewModel.projectSnapshot?.project.id, selectedID)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testQuickStartAndBranchOverrideUseRealModuleActions() async throws {
        let repository = InMemoryNovelProjectRepository()
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "provider",
                ownerProviderID: "provider",
                modelID: "model",
                wireModelID: "model-wire",
                displayName: "Model",
                contextWindowTokens: 32_000
            ),
            scripts: [NovelModelScript(steps: [
                .delta(quickStartSuggestionsJSON),
                .complete
            ])]
        )
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository, modelRunner: adapter)
        )
        let createdID = await viewModel.createProject(
            name: "雾海列车",
            mode: .quickStart,
            genre: "奇幻悬疑",
            coreIdea: "一列火车只在失去记忆的人面前出现。"
        )
        let projectID = try XCTUnwrap(createdID)
        for _ in 0..<100 where viewModel.projectSnapshot?.settingProposals.count != 4 {
            try await Task.sleep(for: .milliseconds(10))
        }
        let modelRequests = await adapter.requests
        XCTAssertEqual(modelRequests.map(\.purpose), [.quickStart])
        XCTAssertTrue(viewModel.activeMaterials.isEmpty)
        let proposal = try XCTUnwrap(viewModel.projectSnapshot?.settingProposals.first(where: {
            if case .some(.quickStart(_, .world)) = $0.origin { return true }
            return false
        }))
        await viewModel.resolveProposal(
            proposal.id,
            resolution: .accept(
                materialID: NovelMaterialID(),
                revisionID: NovelMaterialRevisionID(),
                kind: .world,
                tags: ["列车"],
                injectionMode: .smart
            )
        )
        let material = try XCTUnwrap(viewModel.activeMaterials.first)
        let globalRevisionID = material.currentRevisionID

        await viewModel.setBranchMaterialOverride(
            materialID: material.id,
            change: .createRevision(
                revisionID: NovelMaterialRevisionID(),
                title: "雾海规则 · 主线",
                content: "这条主线里，终点站允许第二次停靠。",
                tags: ["主线"],
                injectionMode: .always
            )
        )

        let loaded = try await repository.loadProject(id: projectID).document
        XCTAssertEqual(loaded.project.creationMode, .quickStart)
        XCTAssertEqual(loaded.project.quickStartSeed?.genre, "奇幻悬疑")
        XCTAssertEqual(loaded.materials[0].currentRevisionID, globalRevisionID)
        XCTAssertEqual(loaded.branches[0].overrideRevisionIDs.count, 1)
        XCTAssertNotEqual(loaded.branches[0].overrideRevisionIDs[0], globalRevisionID)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testQuickStartWithoutSuggestionsRehydratesARealRetryStateAfterRestart() async throws {
        let repository = InMemoryNovelProjectRepository()
        let first = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        let createdID = await first.createProject(
            name: "未完成的开场",
            mode: .quickStart,
            genre: "悬疑",
            coreIdea: "一封信会忘记自己的收件人。"
        )
        let projectID = try XCTUnwrap(createdID)
        for _ in 0..<100 where first.errorMessage == nil {
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertNotNil(first.errorMessage)

        let restarted = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await restarted.loadProjects(selecting: projectID)

        guard case .failed = restarted.quickStartStatus else {
            return XCTFail("A quick-start project without proposals must offer regeneration.")
        }
    }

    func testReplaceImportUsesPreviewRevisionAndRestoresPackage() async throws {
        let repository = InMemoryNovelProjectRepository()
        let source = try NovelTestFixtures.document()
        _ = try await repository.createProject(source)
        let artifact = try NovelProjectPackageCodec.encode(source)
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await viewModel.loadProjects(selecting: source.project.id)
        await viewModel.renameProject("本地改名")
        XCTAssertEqual(viewModel.projectSnapshot?.project.name, "本地改名")

        await viewModel.importProject(artifact.data, choice: .replace)

        let replaced = try await repository.loadProject(id: source.project.id).document
        XCTAssertEqual(replaced, source)
        XCTAssertEqual(viewModel.projectSnapshot?.project.name, source.project.name)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testRestoreChapterVersionThroughViewModelCreatesNewHeadVersion() async throws {
        let repository = InMemoryNovelProjectRepository()
        let fixture = try documentWithChapter()
        _ = try await repository.createProject(fixture.document)
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await viewModel.loadProjects(selecting: fixture.document.project.id)
        await viewModel.restoreChapterVersion(fixture.versionID)

        let restored = try await repository.loadProject(id: fixture.document.project.id).document
        let currentVersionID = try XCTUnwrap(restored.branches[0].workingChapterSelections.first?.versionID)
        let currentVersion = try XCTUnwrap(restored.chapterVersions.first { $0.id == currentVersionID })
        XCTAssertNotEqual(currentVersionID, fixture.versionID)
        XCTAssertEqual(currentVersion.kind, .restore)
        XCTAssertEqual(currentVersion.sourceChapterVersionID, fixture.versionID)
        XCTAssertNil(viewModel.errorMessage)
    }

    func testIncompatibleHistoricalVersionBecomesManualRewriteAndRequiresSync() async throws {
        var fixture = try documentWithChapter()
        let currentVersion = try XCTUnwrap(fixture.document.chapterVersions.first(where: {
            $0.id == fixture.versionID
        }))
        let historical = NovelChapterVersionRecord(
            id: NovelChapterVersionID(),
            chapterID: currentVersion.chapterID,
            kind: .collected,
            title: "另一条第一章",
            content: "列车没有停靠，主角也没有登车。",
            factCompatibilityID: UUID(),
            sourceChapterVersionID: currentVersion.id,
            sourceCandidateID: nil,
            createdAt: currentVersion.createdAt.addingTimeInterval(60),
            operationID: fixture.document.appliedOperations[0].operationID
        )
        fixture.document.chapterVersions.append(historical)
        try NovelDocumentValidator.validate(fixture.document)
        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(fixture.document)
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: repository)
        )
        await viewModel.loadProjects(selecting: fixture.document.project.id)

        let saved = await viewModel.saveManualRewrite(from: historical)
        XCTAssertTrue(saved)

        let edited = try await repository.loadProject(id: fixture.document.project.id).document
        let branch = edited.branches[0]
        let selectedID = try XCTUnwrap(branch.workingChapterSelections.first?.versionID)
        let selected = try XCTUnwrap(edited.chapterVersions.first(where: { $0.id == selectedID }))
        XCTAssertEqual(selected.kind, .manualEdit)
        XCTAssertEqual(selected.title, historical.title)
        XCTAssertEqual(selected.content, historical.content)
        XCTAssertNotEqual(selected.factCompatibilityID, historical.factCompatibilityID)
        XCTAssertEqual(branch.syncStatus, .needsSync)
        XCTAssertEqual(branch.headCheckpointID, fixture.document.branches[0].headCheckpointID)
        XCTAssertEqual(branch.headRevision, fixture.document.branches[0].headRevision)
        XCTAssertEqual(edited.stateSnapshots, fixture.document.stateSnapshots)
        XCTAssertNil(viewModel.errorMessage)

        await viewModel.syncManualEdits()

        XCTAssertNotNil(viewModel.errorMessage)
        XCTAssertEqual(viewModel.projectSnapshot?.pendingOperations.count, 1)
        XCTAssertEqual(viewModel.projectSnapshot?.pendingOperations.first?.kind, .manualSync)
        XCTAssertEqual(viewModel.projectSnapshot?.pendingOperations.first?.status, .retryable)
    }

    private func documentWithChapter() throws -> (
        document: NovelProjectDocumentV1,
        versionID: NovelChapterVersionID
    ) {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branch = document.branches[0]
        let chapterID = NovelChapterID()
        let versionID = NovelChapterVersionID()
        let operationID = document.appliedOperations[0].operationID
        document.chapters.append(NovelChapterRecord(
            id: chapterID,
            createdAt: document.project.updatedAt
        ))
        document.chapterVersions.append(NovelChapterVersionRecord(
            id: versionID,
            chapterID: chapterID,
            kind: .collected,
            title: "第一章",
            content: "雾从停靠两次的终点站涌来。",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: document.project.updatedAt,
            operationID: operationID
        ))
        let selection = NovelChapterSelection(chapterID: chapterID, versionID: versionID)
        let checkpointIndex = try XCTUnwrap(document.checkpoints.firstIndex {
            $0.id == branch.headCheckpointID
        })
        let checkpoint = document.checkpoints[checkpointIndex]
        document.checkpoints[checkpointIndex] = NovelBranchCheckpointRecord(
            id: checkpoint.id,
            kind: checkpoint.kind,
            createdOnBranchID: checkpoint.createdOnBranchID,
            parentCheckpointID: checkpoint.parentCheckpointID,
            chapterSelections: [selection],
            stateSnapshotID: checkpoint.stateSnapshotID,
            sessionCursor: checkpoint.sessionCursor,
            branchOverrideRevisionIDs: checkpoint.branchOverrideRevisionIDs,
            sourceCandidateID: checkpoint.sourceCandidateID,
            baseHeadRevision: checkpoint.baseHeadRevision,
            operationID: checkpoint.operationID,
            createdAt: checkpoint.createdAt
        )
        document.branches[0].workingChapterSelections = [selection]
        try NovelDocumentValidator.validate(document)
        return (document, versionID)
    }

    private var quickStartSuggestionsJSON: String {
        """
        {
          "schemaVersion": 1,
          "overview": "一列以记忆为票价的列车穿行雾海。",
          "world": {
            "title": "雾海列车规则",
            "content": "列车不能在同一座车站停靠两次。"
          },
          "characters": {
            "title": "失忆乘客",
            "content": "主角用逐渐消失的记忆追查列车终点。"
          },
          "masterOutline": {
            "title": "三段旅程",
            "content": "登车、发现代价、选择保留最后一段记忆。"
          },
          "writingRequirements": {
            "title": "悬疑节奏",
            "content": "每章揭示一条规则，并保留一个可回收线索。"
          }
        }
        """
    }
}
