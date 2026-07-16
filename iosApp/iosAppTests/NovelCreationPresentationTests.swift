import XCTest
@testable import iosApp

@MainActor
final class NovelCreationPresentationTests: XCTestCase {
    func testWorkspacePromotesManuscriptToTheMiddleTopLevelTab() {
        XCTAssertEqual(
            NovelWorkspaceSection.allCases,
            [.creation, .manuscript, .compendium]
        )
        XCTAssertEqual(
            NovelWorkspaceSection.allCases.map(\.title),
            ["创作", "正文", "设定"]
        )
        XCTAssertEqual(
            NovelCompendiumSection.allCases.map(\.title),
            ["角色", "世界观", "剧情", "更多"]
        )
    }

    func testFactValidationErrorsExplainTheActualReason() {
        XCTAssertEqual(
            NovelPresentation.operationErrorMessage(NovelError.invalidInput(
                "Unknown entity '朱重八' must be listed as unresolved."
            )),
            "模型提取的人物称谓没有和资料对齐，候选正文仍然保留，可以重新同步。"
        )
        XCTAssertEqual(
            NovelPresentation.operationErrorMessage(NovelError.invalidInput(
                "A derived fact contains evidence outside the authoritative manuscript."
            )),
            "模型提取的事实依据与正文不一致，候选正文仍然保留，可以重新同步。"
        )
    }

    func testMalformedPersistedStateSyncFailureHasActionableCopy() {
        XCTAssertEqual(
            NovelPresentation.stateSyncFailureMessage("The model returned malformed JSON."),
            "剧情同步模型返回的格式无法读取，请重试；若反复出现，请更换剧情同步模型。"
        )
    }

    func testStateSyncOnlyShowsPercentageAfterDurableProgressExists() {
        let projectID = NovelProjectID()
        let branchID = NovelBranchID()
        let pendingID = NovelPendingOperationID()
        let waiting = NovelStateSyncActivity(
            projectID: projectID,
            branchID: branchID,
            pendingID: pendingID,
            phase: .analyzing,
            startedAt: Date(),
            requestStartedAt: Date(),
            completedCharacters: 0,
            totalCharacters: 100,
            completedChunks: 0
        )
        let progressed = NovelStateSyncActivity(
            projectID: projectID,
            branchID: branchID,
            pendingID: pendingID,
            phase: .analyzing,
            startedAt: Date(),
            requestStartedAt: Date(),
            completedCharacters: 40,
            totalCharacters: 100,
            completedChunks: 1
        )

        XCTAssertEqual(waiting.completionFraction, 0)
        XCTAssertNil(waiting.displayedCompletionFraction)
        XCTAssertEqual(progressed.displayedCompletionFraction, 0.4)
    }

    func testChapterTitleUsesTheGeneratedMarkdownHeadingInsteadOfAGenericStoredTitle() {
        XCTAssertEqual(
            NovelPresentation.chapterDisplayTitle(
                storedTitle: "第 1 章",
                content: "# 第一章 破庙里的活人气\n\n雨是昨夜才停的。",
                ordinal: 1
            ),
            "破庙里的活人气"
        )
        XCTAssertEqual(
            NovelPresentation.chapterDisplayTitle(
                storedTitle: "第二章",
                content: "## 第二章：风雪夜归人\n\n城门将闭。",
                ordinal: 2
            ),
            "风雪夜归人"
        )
    }

    func testChapterTitlePreservesAnExplicitStoredTitleAndFallsBackWithoutAHeading() {
        XCTAssertEqual(
            NovelPresentation.chapterDisplayTitle(
                storedTitle: "夜雨入城",
                content: "# 第一章 破庙里的活人气",
                ordinal: 1
            ),
            "夜雨入城"
        )
        XCTAssertEqual(
            NovelPresentation.chapterDisplayTitle(
                storedTitle: "第 3 章",
                content: "雨是昨夜才停的。",
                ordinal: 3
            ),
            "第 3 章"
        )
    }

    func testCharacterEventMatcherPrefersNormalizedNameMatches() {
        XCTAssertTrue(NovelCharacterEventMatcher.matches(
            characterName: "沈 雾",
            entityReferences: ["沈雾"]
        ))
        XCTAssertTrue(NovelCharacterEventMatcher.matches(
            characterName: "沈雾",
            entityReferences: ["年轻时的沈雾"]
        ))
        XCTAssertFalse(NovelCharacterEventMatcher.matches(
            characterName: "沈雾",
            entityReferences: []
        ))
    }

    func testCharacterEventMatcherSupportsOneEventReferencingMultipleCharacters() {
        let event = NovelStoryEventRecord(
            id: NovelEventID(),
            sequence: 1,
            kind: "关系变化",
            summary: "沈雾与林澈达成同盟",
            entityReferences: ["沈雾", "林澈"],
            createdAt: Date()
        )

        XCTAssertEqual(
            NovelCharacterEventMatcher.events(for: "沈雾", in: [event]).map(\.id),
            [event.id]
        )
        XCTAssertEqual(
            NovelCharacterEventMatcher.events(for: "林澈", in: [event]).map(\.id),
            [event.id]
        )
    }

    func testComposerIntentMapsToTheExistingRequestContract() {
        XCTAssertEqual(NovelComposerIntent.discussionOptions, [.discuss])
        XCTAssertEqual(
            NovelComposerIntent.proseOptions,
            [.continueProse, .wholeChapter]
        )
        XCTAssertEqual(NovelComposerIntent.discuss.title, "讨论")
        XCTAssertEqual(NovelComposerIntent.continueProse.title, "写一段")
        XCTAssertEqual(NovelComposerIntent.wholeChapter.title, "写整章")
        XCTAssertEqual(NovelComposerIntent.discuss.requestValues.mode, .discussPlan)
        XCTAssertEqual(NovelComposerIntent.continueProse.requestValues.mode, .writeProse)
        XCTAssertEqual(
            NovelComposerIntent.continueProse.requestValues.granularity,
            .continuation
        )
        XCTAssertEqual(NovelComposerIntent.wholeChapter.requestValues.mode, .writeProse)
        XCTAssertEqual(
            NovelComposerIntent.wholeChapter.requestValues.granularity,
            .wholeChapter
        )
    }

    func testComposerIntentProjectsCurrentSessionState() {
        XCTAssertEqual(
            NovelComposerIntent(mode: .discussPlan, granularity: .continuation),
            .discuss
        )
        XCTAssertEqual(
            NovelComposerIntent(mode: .writeProse, granularity: .continuation),
            .continueProse
        )
        XCTAssertEqual(
            NovelComposerIntent(mode: .writeProse, granularity: .wholeChapter),
            .wholeChapter
        )
    }

    func testDerivedProposalRequiresAnExplicitMaterialKindAndRoutesToMore() {
        let proposal = NovelSettingProposalRecord(
            id: NovelProposalID(),
            branchID: NovelBranchID(),
            title: "新增线索",
            content: "旧港口可能藏有一条密道。",
            createdAt: Date(),
            isResolved: false,
            origin: .derivedState
        )

        XCTAssertNil(NovelProposalAcceptanceSheet.initialKindChoice(for: proposal))
        XCTAssertEqual(
            NovelSettingProposalRoute(kind: proposal.suggestedMaterialKind),
            .more
        )
    }

    func testQuickStartProposalKeepsItsSuggestedMaterialKind() {
        let proposal = NovelSettingProposalRecord(
            id: NovelProposalID(),
            branchID: NovelBranchID(),
            title: "潮汐城",
            content: "退潮会暴露旧城区。",
            createdAt: Date(),
            isResolved: false,
            origin: .quickStart(runID: NovelRunID(), suggestedKind: .world)
        )

        XCTAssertEqual(
            NovelProposalAcceptanceSheet.initialKindChoice(for: proposal),
            NovelMaterialKindChoice(kind: .world)
        )
    }

    func testEnglishGenerationFailuresUseAChinesePresentationMessage() {
        XCTAssertEqual(
            NovelPresentation.failureMessage(NovelFailure(
                code: "invalid_quick_start_output",
                message: "The quick-start output was invalid.",
                isRetryable: true
            )),
            "模型返回的创作建议格式不完整，请重新生成。"
        )
        XCTAssertEqual(
            NovelPresentation.failureMessage(NovelFailure(
                code: "network",
                message: "网络连接已断开",
                isRetryable: true
            )),
            "网络连接已断开"
        )
        XCTAssertEqual(
            NovelPresentation.failureMessage(NovelFailure(
                code: "provider_stream_failed",
                message: #"OpenAI stream error: {"type":"upstream_error","message":"Upstream request failed"}"#,
                isRetryable: true
            )),
            "模型上游服务在生成过程中中断，已保留当前回复，可以重试。"
        )
    }

    func testCollectionTargetDefaultsToNewChapterForWholeChapterCandidate() {
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 3, granularity: .wholeChapter),
            .createNext
        )
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 0, granularity: .wholeChapter),
            .createNext
        )
    }

    func testCollectionTargetDefaultsToCurrentChapterForContinuationCandidate() {
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 3, granularity: .continuation),
            .appendCurrent
        )
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 0, granularity: .continuation),
            .createNext
        )
    }

    func testCompositionCreatesAFileBackedViewModelAndReloadsIt() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("NovelCompositionTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let suite = "NovelCompositionTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = IOSSharedSettingsStore(userDefaults: defaults)

        let first = try NovelCreationComposition.makeViewModel(
            sharedSettings: settings,
            rootDirectory: root
        )
        let createdProjectID = await first.createProject(name: "测试路由", mode: .blank)
        let projectID = try XCTUnwrap(createdProjectID)

        let restarted = try NovelCreationComposition.makeViewModel(
            sharedSettings: settings,
            rootDirectory: root
        )
        await restarted.loadProjects(selecting: projectID)

        XCTAssertEqual(restarted.projectSnapshot?.project.name, "测试路由")
        XCTAssertEqual(restarted.selectedBranchID, restarted.projectSnapshot?.project.mainBranchID)
        XCTAssertNil(restarted.errorMessage)
    }

    func testModelSelectionResolvesStableOwnerWithoutChangingGlobalChatModel() async throws {
        let suite = "NovelModelPresentationTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let globalModelIDBefore = settings.snapshot.getCurrentChatModel()?.id.description()
        let provider = try XCTUnwrap(settings.snapshot.providers.first { !$0.models.isEmpty })
        let model = try XCTUnwrap(provider.models.first)
        let providerID = provider.id.description()
        let modelID = model.id.description()

        XCTAssertEqual(
            NovelPresentation.providerID(forModelID: modelID, sharedSettings: settings),
            providerID
        )

        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: InMemoryNovelProjectRepository())
        )
        _ = await viewModel.createProject(name: "模型隔离", mode: .blank)
        await viewModel.setModelPolicy(.fixed(providerID: providerID, modelID: modelID))

        XCTAssertEqual(
            viewModel.projectSnapshot?.project.modelPolicy,
            .fixed(providerID: providerID, modelID: modelID)
        )
        XCTAssertEqual(settings.snapshot.getCurrentChatModel()?.id.description(), globalModelIDBefore)
    }

    func testCheckpointLineageIsHeadToRootAndStopsAtInitialCheckpoint() throws {
        let document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let snapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: document,
            access: .readWrite
        ))

        let lineage = NovelPresentation.checkpointLineage(
            for: document.branches[0],
            in: snapshot
        )

        let parentCheckpointID = try XCTUnwrap(lineage.first?.parentCheckpointID)
        XCTAssertEqual(lineage.map(\.id), [
            document.branches[0].headCheckpointID,
            parentCheckpointID,
        ])
        XCTAssertEqual(lineage.last?.kind, .initial)
        let forkable = NovelPresentation.forkableCheckpoints(
            for: document.branches[0],
            in: snapshot
        )
        XCTAssertEqual(forkable.map(\.id), [document.branches[0].headCheckpointID])
        XCTAssertFalse(forkable.contains(where: { $0.kind == .initial }))
    }

    func testActionCheckpointLineageStopsAtForkBoundaryAndIncludesLocalHistory() throws {
        let source = try NovelTestFixtures.documentWithForkableCheckpoint()
        let sourceBranch = source.branches[0]
        let childID = NovelBranchID()
        let command = NovelBranchTestFixtures.forkCommand(
            document: source,
            sourceBranchID: sourceBranch.id,
            checkpointID: sourceBranch.headCheckpointID,
            branchID: childID,
            name: "支线"
        )
        let forked = try NovelReducer.apply(.forkBranch(command), to: source).document
        let child = try XCTUnwrap(forked.branches.first(where: { $0.id == childID }))
        let freshSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: forked,
            access: .readWrite
        ))

        XCTAssertEqual(
            NovelPresentation.actionCheckpointLineage(for: child, in: freshSnapshot).map(\.id),
            [sourceBranch.headCheckpointID]
        )
        XCTAssertEqual(
            NovelPresentation.forkableCheckpoints(for: child, in: freshSnapshot).map(\.id),
            [sourceBranch.headCheckpointID]
        )

        var advanced = forked
        let localCheckpoint = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .manualSync,
            createdOnBranchID: childID,
            parentCheckpointID: child.headCheckpointID,
            chapterSelections: child.workingChapterSelections,
            stateSnapshotID: child.currentStateSnapshotID,
            sessionCursor: .empty,
            branchOverrideRevisionIDs: child.overrideRevisionIDs,
            sourceCandidateID: nil,
            baseHeadRevision: child.headRevision,
            operationID: NovelOperationID(),
            createdAt: Date()
        )
        advanced.checkpoints.append(localCheckpoint)
        let childIndex = try XCTUnwrap(advanced.branches.firstIndex(where: { $0.id == childID }))
        advanced.branches[childIndex].headCheckpointID = localCheckpoint.id
        advanced.branches[childIndex].headRevision += 1
        let advancedSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: advanced,
            access: .readWrite
        ))

        XCTAssertEqual(
            NovelPresentation.actionCheckpointLineage(
                for: advanced.branches[childIndex],
                in: advancedSnapshot
            ).map(\.id),
            [localCheckpoint.id, sourceBranch.headCheckpointID]
        )
    }

    func testCheckpointChapterOrdinalUsesTheBranchSelectionOrder() {
        let branchID = NovelBranchID()
        let projectChapterIDs = [NovelChapterID(), NovelChapterID(), NovelChapterID()]
        let branchChapterIDs = [projectChapterIDs[0], projectChapterIDs[2]]
        let parent = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .collection,
            createdOnBranchID: branchID,
            parentCheckpointID: nil,
            chapterSelections: branchChapterIDs.map {
                NovelChapterSelection(chapterID: $0, versionID: NovelChapterVersionID())
            },
            stateSnapshotID: NovelStateSnapshotID(),
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 0,
            operationID: NovelOperationID(),
            createdAt: Date()
        )
        var selections = parent.chapterSelections
        selections[1] = NovelChapterSelection(
            chapterID: branchChapterIDs[1],
            versionID: NovelChapterVersionID()
        )
        let child = NovelBranchCheckpointRecord(
            id: NovelCheckpointID(),
            kind: .collection,
            createdOnBranchID: branchID,
            parentCheckpointID: parent.id,
            chapterSelections: selections,
            stateSnapshotID: NovelStateSnapshotID(),
            sessionCursor: .empty,
            branchOverrideRevisionIDs: [],
            sourceCandidateID: nil,
            baseHeadRevision: 1,
            operationID: NovelOperationID(),
            createdAt: Date()
        )

        XCTAssertEqual(
            NovelCheckpointLabel.chapterOrdinal(
                for: child,
                checkpoints: [parent, child]
            ),
            2
        )
    }

    func testDirectChapterRestoreRequiresTheSameFactCompatibilityLineage() {
        let chapterID = NovelChapterID()
        let compatibilityID = UUID()
        let current = NovelChapterVersionRecord(
            id: NovelChapterVersionID(),
            chapterID: chapterID,
            kind: .polish,
            title: "Current",
            content: "Current",
            factCompatibilityID: compatibilityID,
            sourceCandidateID: nil,
            createdAt: Date(),
            operationID: NovelOperationID()
        )
        let compatible = NovelChapterVersionRecord(
            id: NovelChapterVersionID(),
            chapterID: chapterID,
            kind: .restore,
            title: "Compatible",
            content: "Compatible",
            factCompatibilityID: compatibilityID,
            sourceCandidateID: nil,
            createdAt: Date(),
            operationID: NovelOperationID()
        )
        let incompatible = NovelChapterVersionRecord(
            id: NovelChapterVersionID(),
            chapterID: chapterID,
            kind: .manualEdit,
            title: "Incompatible",
            content: "Incompatible",
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: Date(),
            operationID: NovelOperationID()
        )

        XCTAssertTrue(NovelPresentation.canDirectlyRestore(compatible, from: current))
        XCTAssertFalse(NovelPresentation.canDirectlyRestore(incompatible, from: current))
        XCTAssertFalse(NovelPresentation.canDirectlyRestore(current, from: current))
    }

    func testDegradedSnapshotDisablesViewModelMutations() throws {
        let document = try NovelTestFixtures.document()
        let viewModel = NovelCreationViewModel(
            creation: DefaultNovelCreation(repository: InMemoryNovelProjectRepository())
        )
        viewModel.projectSnapshot = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: document,
            access: .degradedPrevious(primaryFailure: "corrupt primary")
        ))

        XCTAssertFalse(viewModel.canMutate)
    }

    func testLateProjectSnapshotCannotReplaceNewerSelection() async throws {
        let repository = InMemoryNovelProjectRepository()
        let firstDocument = try NovelTestFixtures.document()
        let secondDocument = try NovelTestFixtures.document()
        _ = try await repository.createProject(firstDocument)
        _ = try await repository.createProject(secondDocument)
        let base = DefaultNovelCreation(repository: repository)
        let delayed = DelayedProjectSnapshotNovelCreation(
            base: base,
            delayedProjectID: firstDocument.project.id
        )
        let viewModel = NovelCreationViewModel(creation: delayed)

        let firstSelection = Task { @MainActor in
            _ = await viewModel.selectProject(firstDocument.project.id)
        }
        try await Task.sleep(for: .milliseconds(30))
        await viewModel.selectProject(secondDocument.project.id)
        await firstSelection.value

        XCTAssertEqual(viewModel.selectedProjectID, secondDocument.project.id)
        XCTAssertEqual(viewModel.projectSnapshot?.project.id, secondDocument.project.id)
        XCTAssertEqual(viewModel.branchSnapshot?.projectID, secondDocument.project.id)
    }
}

private actor DelayedProjectSnapshotNovelCreation: NovelCreation {
    let base: any NovelCreation
    let delayedProjectID: NovelProjectID

    init(base: any NovelCreation, delayedProjectID: NovelProjectID) {
        self.base = base
        self.delayedProjectID = delayedProjectID
    }

    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        if case .project(let projectID) = scope, projectID == delayedProjectID {
            try await Task.sleep(for: .milliseconds(150))
        }
        return try await base.snapshot(scope)
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        try await base.perform(action)
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        try await base.start(request)
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        await base.interruptForBackground(
            projectID: projectID,
            deadline: deadline,
            runID: runID
        )
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }
}
