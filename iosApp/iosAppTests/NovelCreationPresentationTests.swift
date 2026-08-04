import XCTest
@testable import iosApp

@MainActor
final class NovelCreationPresentationTests: XCTestCase {
    func testCompendiumRoutesTheDisplayedEffectiveRevisionToItsOwningEditor() throws {
        let initial = try NovelTestFixtures.document()
        let withMaterial = try NovelReducer.apply(
            NovelTestFixtures.materialAction(
                document: initial,
                title: "项目世界观",
                content: "项目共享规则。"
            ),
            to: initial
        ).document
        let material = try XCTUnwrap(withMaterial.materials.first)
        let overrideRevisionID = NovelMaterialRevisionID()
        let overridden = try NovelReducer.apply(.setBranchMaterialOverride(
            NovelSetBranchMaterialOverrideCommand(
                context: NovelTestFixtures.context(
                    projectRevision: withMaterial.project.revision,
                    configRevision: withMaterial.project.configRevision,
                    branchHeadRevision: withMaterial.branches[0].headRevision
                ),
                projectID: withMaterial.project.id,
                branchID: withMaterial.branches[0].id,
                materialID: material.id,
                change: .createRevision(
                    revisionID: overrideRevisionID,
                    title: "当前分支世界观",
                    content: "仅当前分支采用的规则。",
                    tags: [],
                    injectionMode: .smart
                )
            )
        ), to: withMaterial).document
        let project = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: overridden,
            access: .readWrite
        ))
        let branch = overridden.branches[0]
        let branchSnapshot = NovelBranchSnapshot(
            projectID: overridden.project.id,
            projectRevision: overridden.project.revision,
            configRevision: overridden.project.configRevision,
            branch: branch,
            session: try XCTUnwrap(overridden.sessions.first { $0.id == branch.sessionID }),
            headCheckpoint: try XCTUnwrap(overridden.checkpoints.first {
                $0.id == branch.headCheckpointID
            }),
            currentState: try XCTUnwrap(overridden.stateSnapshots.first {
                $0.id == branch.currentStateSnapshotID
            }),
            chapterSelections: branch.workingChapterSelections,
            activeSettingProposals: [],
            access: .readWrite
        )

        XCTAssertEqual(
            NovelCompendiumMaterialEditTarget.resolve(
                material: material,
                project: project,
                branch: branchSnapshot
            ),
            .branchOverride
        )
        XCTAssertEqual(
            NovelCompendiumMaterialEditTarget.resolve(
                material: material,
                project: project,
                branch: nil
            ),
            .projectRevision
        )
        XCTAssertEqual(
            NovelProposalAcceptanceSheet.targetMaterialTitle(
                for: material,
                in: project
            ),
            "项目世界观",
            "接纳建议会写项目全局 revision，目标标题也必须显示全局版本"
        )
    }

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
        XCTAssertEqual(
            NovelPresentation.stateSyncFailureMessage(
                "The fact synchronization was cancelled and can be retried."
            ),
            "剧情状态同步已取消，可以重试。"
        )
        XCTAssertEqual(
            NovelPresentation.stateSyncFailureMessage(
                "Manual-sync receipt input evidence is incomplete."
            ),
            "剧情状态同步失败，请重试。"
        )
        XCTAssertEqual(
            NovelPresentation.stateSyncFailureMessage("请求失败：upstream timeout"),
            "剧情状态同步失败，请重试。"
        )
    }

    func testPendingPresentationDistinguishesWaitingFromStreaming() {
        let waitingEarly = NovelSessionPendingPresentation.label(
            for: .waitingForFirstToken,
            elapsed: 1
        )
        let waitingLate = NovelSessionPendingPresentation.label(
            for: .waitingForFirstToken,
            elapsed: 12
        )
        let streaming = NovelSessionPendingPresentation.label(
            for: .streaming,
            elapsed: 12
        )

        XCTAssertEqual(waitingEarly, "正在连接模型")
        XCTAssertEqual(waitingLate, "模型思考中 12 秒")
        XCTAssertNotEqual(
            waitingLate,
            streaming,
            "waitingForFirstToken and streaming must render visibly different copy."
        )
        XCTAssertNotEqual(
            waitingEarly,
            streaming,
            "waitingForFirstToken and streaming must render visibly different copy."
        )
    }

    func testTextInputCommitterUnmarksBeforeDeferredAction() async {
        let textField = UITextField()
        textField.text = "赵"
        let end = textField.endOfDocument
        textField.selectedTextRange = textField.textRange(from: end, to: end)
        textField.setMarkedText("大来", selectedRange: NSRange(location: 2, length: 0))
        XCTAssertNotNil(textField.markedTextRange)
        let action = expectation(description: "committed action runs on the next main turn")

        NovelTextInputCommitter.perform(firstResponder: textField) {
            XCTAssertNil(textField.markedTextRange)
            XCTAssertEqual(textField.text, "赵大来")
            action.fulfill()
        }

        XCTAssertNil(textField.markedTextRange)
        await fulfillment(of: [action], timeout: 1)
    }

    func testPresentationUsesHistoricalCharacterNamesAsEffectiveAliases() throws {
        var document = try NovelTestFixtures.document()
        let materialID = NovelMaterialID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: .character,
            title: "赵旧名",
            content: "旧的人物设定。",
            tags: [],
            injectionMode: .smart,
            aliases: []
        )), to: document).document
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: .character,
            title: "赵大来",
            content: "当前人物设定。",
            tags: [],
            injectionMode: .smart,
            aliases: []
        )), to: document).document
        let project = NovelProjectSnapshot(loaded: NovelLoadedProject(
            document: document,
            access: .readWrite
        ))
        let material = try XCTUnwrap(document.materials.first { $0.id == materialID })

        XCTAssertEqual(
            NovelPresentation.effectiveAliases(
                for: material,
                project: project,
                branch: nil
            ),
            ["赵旧名"]
        )
    }

    func testPendingPresentationFallsBackToDefaultCopyForNonQuickStartPhases() {
        // Phases outside the quickStart streaming disclosure (or no phase at all) must keep
        // rendering the exact same copy ChatAssistantPendingResponseView already used, so
        // Chat/Council callers that never pass a phase see zero behavior change.
        for elapsed in [0, 1, 2, 9] {
            XCTAssertEqual(
                NovelSessionPendingPresentation.label(for: nil, elapsed: elapsed),
                ChatAssistantPendingResponseView.defaultLabel(elapsed: elapsed)
            )
            XCTAssertEqual(
                NovelSessionPendingPresentation.label(for: .terminalAwaitingRefresh, elapsed: elapsed),
                ChatAssistantPendingResponseView.defaultLabel(elapsed: elapsed)
            )
        }
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

    func testComposerSubmissionUsesTheSameGateForButtonAndKeyboard() {
        XCTAssertTrue(NovelSessionComposerPolicy.canSubmit(canSend: true, text: "继续写"))
        XCTAssertFalse(NovelSessionComposerPolicy.canSubmit(canSend: false, text: "继续写"))
        XCTAssertFalse(NovelSessionComposerPolicy.canSubmit(canSend: true, text: " \n "))
    }

    func testAskUserExplainsWhyItCannotBeAnswered() {
        XCTAssertEqual(
            NovelSessionComposerPolicy.askUserBlocker(
                access: .degradedPrevious(primaryFailure: "primary unavailable"),
                requiresReload: false,
                isBusy: false
            ),
            .projectReadOnly
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.askUserBlocker(
                access: .readWrite,
                requiresReload: true,
                isBusy: false
            ),
            .reloadRequired
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.askUserBlocker(
                access: .readWrite,
                requiresReload: false,
                isBusy: true
            ),
            .transactionInProgress
        )
        XCTAssertNil(NovelSessionComposerPolicy.askUserBlocker(
            access: .readWrite,
            requiresReload: false,
            isBusy: false
        ))
    }

    func testPolishTransactionActionsExplainTheFirstBlockingCondition() {
        XCTAssertEqual(
            NovelSessionComposerPolicy.polishTransactionBlocker(
                access: .degradedPrevious(primaryFailure: "corrupt"),
                requiresReload: true,
                isRunning: true,
                isBusy: true
            ),
            .projectReadOnly
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.polishTransactionBlocker(
                access: .readWrite,
                requiresReload: true,
                isRunning: true,
                isBusy: true
            ),
            .reloadRequired
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.polishTransactionBlocker(
                access: .readWrite,
                requiresReload: false,
                isRunning: true,
                isBusy: true
            ),
            .generationRunning
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.polishTransactionBlocker(
                access: .readWrite,
                requiresReload: false,
                isRunning: false,
                isBusy: true
            ),
            .transactionInProgress
        )
        XCTAssertNil(NovelSessionComposerPolicy.polishTransactionBlocker(
            access: .readWrite,
            requiresReload: false,
            isRunning: false,
            isBusy: false
        ))
    }

    func testGenerationStatusFollowsTheActiveRunInsteadOfTheComposerSelection() {
        XCTAssertTrue(NovelSessionComposerPolicy.showsGenerationStatus(
            isRunning: true,
            activeRunKind: .prose
        ))
        XCTAssertTrue(NovelSessionComposerPolicy.showsGenerationStatus(
            isRunning: true,
            activeRunKind: .regenerate
        ))
        XCTAssertTrue(NovelSessionComposerPolicy.showsGenerationStatus(
            isRunning: true,
            activeRunKind: .polish
        ))
        XCTAssertFalse(NovelSessionComposerPolicy.showsGenerationStatus(
            isRunning: true,
            activeRunKind: .discussion
        ))
        XCTAssertFalse(NovelSessionComposerPolicy.showsGenerationStatus(
            isRunning: false,
            activeRunKind: .prose
        ))
    }

    func testRuntimeRowActionsExposeTheirBlockingReasonInsteadOfDroppingTaps() {
        XCTAssertEqual(
            NovelSessionComposerPolicy.runtimeActionBlocker(
                requiresReload: true,
                hasRefreshError: false,
                isBusy: false
            ),
            .reloadRequired
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.runtimeActionBlocker(
                requiresReload: false,
                hasRefreshError: true,
                isBusy: false
            ),
            .reloadRequired
        )
        XCTAssertEqual(
            NovelSessionComposerPolicy.runtimeActionBlocker(
                requiresReload: false,
                hasRefreshError: false,
                isBusy: true
            ),
            .transactionInProgress
        )
        XCTAssertNil(NovelSessionComposerPolicy.runtimeActionBlocker(
            requiresReload: false,
            hasRefreshError: false,
            isBusy: false
        ))
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

    func testWritingRequirementsProposalDefaultsToTheExistingManagedMaterial() {
        let existingID = NovelMaterialID()
        let existingRevisionID = NovelMaterialRevisionID()
        let existing = NovelMaterialRecord(
            id: existingID,
            kind: .writingRequirements,
            currentRevisionID: existingRevisionID,
            revisionIDs: [existingRevisionID]
        )
        let otherID = NovelMaterialID()
        let otherRevisionID = NovelMaterialRevisionID()
        let other = NovelMaterialRecord(
            id: otherID,
            kind: .world,
            currentRevisionID: otherRevisionID,
            revisionIDs: [otherRevisionID]
        )
        let proposal = NovelSettingProposalRecord(
            id: NovelProposalID(),
            branchID: NovelBranchID(),
            title: "写作要求",
            content: "克制叙述，保持线索公平。",
            createdAt: Date(),
            isResolved: false,
            origin: .quickStart(runID: NovelRunID(), suggestedKind: .writingRequirements)
        )

        XCTAssertEqual(
            NovelProposalAcceptanceSheet.initialTargetMaterialID(
                for: proposal,
                activeMaterials: [other, existing]
            ),
            existingID
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
            NovelCollectionTargetChoice.initial(chapterCount: 3, granularity: .wholeChapter, hasRegenerationTarget: false),
            .createNext
        )
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 0, granularity: .wholeChapter, hasRegenerationTarget: false),
            .createNext
        )
    }

    /// 重新生成的候选默认就该替换来源章——那是发起这次生成的本意;
    /// 这条优先级高于按粒度推断的默认值。
    func testCollectionTargetDefaultsToReplaceForRegeneratedCandidate() {
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(
                chapterCount: 3,
                granularity: .wholeChapter,
                hasRegenerationTarget: true
            ),
            .replaceChapter
        )
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(
                chapterCount: 3,
                granularity: .continuation,
                hasRegenerationTarget: true
            ),
            .replaceChapter
        )
    }

    func testCollectionTargetDefaultsToCurrentChapterForContinuationCandidate() {
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 3, granularity: .continuation, hasRegenerationTarget: false),
            .appendCurrent
        )
        XCTAssertEqual(
            NovelCollectionTargetChoice.initial(chapterCount: 0, granularity: .continuation, hasRegenerationTarget: false),
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

    func testDisabledOrMismatchedProviderDoesNotPresentItsFixedModelAsAvailable() throws {
        let suite = "NovelModelAvailabilityPresentationTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = IOSSharedSettingsStore(userDefaults: defaults)
        let option = try XCTUnwrap(settings.availableChatModels().first)
        let provider = try XCTUnwrap(settings.snapshot.providers.first { provider in
            provider.models.contains { $0.id.description() == option.id }
        })
        let providerID = provider.id.description()
        let modelID = option.id

        _ = settings.updateProviderBasics(
            providerId: providerID,
            name: provider.name,
            enabled: true
        )
        settings.setCurrentChatModelId(modelID)
        let selectedModel = try XCTUnwrap(provider.models.first {
            $0.id.description() == modelID
        })
        let selectedName = selectedModel.displayName
            .trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertEqual(
            NovelPresentation.modelDisplayName(for: .global, sharedSettings: settings),
            selectedName.isEmpty ? selectedModel.modelId : selectedName
        )

        XCTAssertEqual(
            NovelPresentation.modelDisplayName(
                for: .fixed(providerID: "missing-provider", modelID: modelID),
                sharedSettings: settings
            ),
            "固定模型不可用"
        )

        _ = settings.updateProviderBasics(
            providerId: providerID,
            name: provider.name,
            enabled: false
        )
        XCTAssertEqual(
            NovelPresentation.modelDisplayName(
                for: .fixed(providerID: providerID, modelID: modelID),
                sharedSettings: settings
            ),
            "固定模型不可用"
        )

        settings.setCurrentChatModelId(modelID)
        XCTAssertEqual(
            NovelPresentation.modelDisplayName(for: .global, sharedSettings: settings),
            "全局模型不可用"
        )
    }

    func testUnavailableModelFailurePointsToTheLiveSettingsEntry() {
        XCTAssertEqual(
            NovelPresentation.failureMessage(NovelFailure(
                code: "fixed_model_missing",
                message: "The configured model no longer exists.",
                isRetryable: false
            )),
            "项目模型当前不可用，请在右上角“设置”的“项目模型覆盖”中重新选择。"
        )
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

    func cancelInFlightBackgroundMutations(projectID: NovelProjectID) async {
        await base.cancelInFlightBackgroundMutations(projectID: projectID)
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        try await base.retryPendingTerminal(runID: runID)
    }
}
