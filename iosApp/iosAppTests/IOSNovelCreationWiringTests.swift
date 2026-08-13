import UIKit
import XCTest
@testable import iosApp

@MainActor
final class IOSNovelCreationWiringTests: XCTestCase {
    func testCharacterIdentityQuestionHasIgnoreAndCustomAnswerPaths() throws {
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(session.contains("startCharacterProposal"))
        XCTAssertTrue(session.contains("onIgnore"))
        XCTAssertTrue(session.contains("onClarify"))
        XCTAssertTrue(session.contains("NovelTextInputCommitter.perform"))
    }

    func testNovelDiscussionSharesTheChatSearchRuntime() throws {
        let appShell = try source("iosApp/AppShell.swift")
        let composition = try source("iosApp/NovelCreation/NovelCreationComposition.swift")

        XCTAssertTrue(appShell.contains("toolRuntime: backgroundToolRuntime"))
        XCTAssertTrue(composition.contains("toolRuntime: ChatToolRuntime?"))
        XCTAssertTrue(composition.contains("toolRuntime: toolRuntime"))
    }

    func testBackgroundLeaseWaitsForExpirationBeforeInterrupting() async {
        var expirationHandler: (() -> Void)?
        var begunLeaseIds: [String] = []
        var endedLeaseIds: [String] = []
        var interruptionCount = 0
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginKeepAlive: { leaseId, onExpire in
                begunLeaseIds.append(leaseId)
                expirationHandler = onExpire
            },
            endKeepAlive: { endedLeaseIds.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )

        await Task.yield()
        XCTAssertEqual(begunLeaseIds.count, 1)
        XCTAssertEqual(interruptionCount, 0)
        XCTAssertTrue(endedLeaseIds.isEmpty)

        expirationHandler?()
        let didEnd = await eventually { endedLeaseIds.count == 1 }
        XCTAssertTrue(didEnd)

        XCTAssertEqual(interruptionCount, 1)
        // 还回去的必须是拿到手的那张租约，不能是另一轮的。
        XCTAssertEqual(endedLeaseIds, begunLeaseIds)
        await completionGate.open()
    }

    func testExpirationEndsTaskAfterDurableInterruptionFinishes() async {
        var expirationHandler: (() -> Void)?
        var begunLeaseIds: [String] = []
        var endedLeaseIds: [String] = []
        let gate = NovelWorkspaceLifecycleTestGate()
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginKeepAlive: { leaseId, onExpire in
                begunLeaseIds.append(leaseId)
                expirationHandler = onExpire
            },
            endKeepAlive: { endedLeaseIds.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in await gate.wait() }
        )
        expirationHandler?()
        let didStartWaiting = await eventually { await gate.hasWaiter }
        XCTAssertTrue(didStartWaiting)
        // 中断还没落盘完就把执行权还回去，等于自己掐断自己。
        XCTAssertTrue(endedLeaseIds.isEmpty)
        await gate.open()
        let didEnd = await eventually { endedLeaseIds.count == 1 }

        XCTAssertTrue(didEnd)
        XCTAssertEqual(endedLeaseIds, begunLeaseIds)
        await completionGate.open()
    }

    func testReturningForegroundEndsLeaseWithoutInterruptingGeneration() async {
        var begunLeaseIds: [String] = []
        var endedLeaseIds: [String] = []
        var interruptionCount = 0
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginKeepAlive: { leaseId, _ in begunLeaseIds.append(leaseId) },
            endKeepAlive: { endedLeaseIds.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )
        coordinator.enterForeground()

        let didEnd = await eventually { endedLeaseIds.count == 1 }
        XCTAssertTrue(didEnd)
        XCTAssertEqual(endedLeaseIds, begunLeaseIds)
        XCTAssertEqual(interruptionCount, 0)
        await completionGate.open()
    }

    func testReturningForegroundCancelsTheExpiringCycleInterruption() async {
        var expirationHandler: (() -> Void)?
        var endedLeaseIds: [String] = []
        var interruptionReachedTerminalMutation = false
        let interruptionGate = NovelWorkspaceLifecycleTestGate()
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginKeepAlive: { _, onExpire in expirationHandler = onExpire },
            endKeepAlive: { endedLeaseIds.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in
                await interruptionGate.wait()
                if !Task.isCancelled {
                    interruptionReachedTerminalMutation = true
                }
            }
        )
        expirationHandler?()
        let didStartInterruption = await eventually { await interruptionGate.hasWaiter }
        XCTAssertTrue(didStartInterruption)

        coordinator.enterForeground()
        await interruptionGate.open()
        await Task.yield()

        XCTAssertEqual(endedLeaseIds.count, 1)
        XCTAssertFalse(
            interruptionReachedTerminalMutation,
            "The expired background cycle must not continue into a new foreground run."
        )
        await completionGate.open()
    }

    func testWorkspaceExitDetachesConsumerWhileAppOwnsBackgroundLease() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")
        let appShell = try source("iosApp/AppShell.swift")
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")

        XCTAssertTrue(workspace.contains(".onDisappear"))
        XCTAssertTrue(workspace.contains("sessionViewModel.detachConsumer()"))
        XCTAssertTrue(workspace.contains("@Environment(\\.scenePhase)"))
        XCTAssertTrue(workspace.contains(".onChange(of: scenePhase)"))
        XCTAssertTrue(workspace.contains("phase == .active"))
        XCTAssertTrue(workspace.contains("scheduleAutomaticStateSyncIfNeeded()"))
        XCTAssertFalse(workspace.contains("guard chapterReaderRoute == nil else { return }"))
        XCTAssertFalse(workspace.contains("sessionViewModel.bindToCurrentSelection()"))
        XCTAssertTrue(session.contains(".task(id: bindingTaskID)"))
        XCTAssertTrue(session.contains("await viewModel.bindToCurrentSelection()"))
        XCTAssertTrue(appShell.contains("novelLifecycleCoordinator.enterBackground"))
        XCTAssertTrue(appShell.contains("waitForBackgroundGeneration"))
        XCTAssertTrue(appShell.contains("interruptSessionForBackground"))
        XCTAssertTrue(appShell.contains("novelLifecycleCoordinator.enterForeground()"))
        XCTAssertTrue(viewModel.contains("UIApplication.shared.applicationState == .active"))
        XCTAssertTrue(viewModel.contains("await creation.resumeDetachedGenerationRuns()"))
        XCTAssertTrue(viewModel.contains("await loadProjects(restoresSelection: false)"))
    }

    func testNovelGenerationOwnsTheSystemLeaseFromRuntimeStartToTerminal() throws {
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")
        let lifecycle = try source("iosApp/NovelCreation/NovelGenerationLifecycle.swift")
        let workspaceLifecycle = try source(
            "iosApp/NovelCreation/NovelWorkspaceLifecycleCoordinator.swift"
        )

        XCTAssertTrue(viewModel.contains("BackgroundGenerationKeepAlive.shared.begin"))
        XCTAssertTrue(viewModel.contains("onSystemTaskExpiration:"))
        XCTAssertTrue(viewModel.contains("beginBackgroundGeneration(for: request)"))
        XCTAssertTrue(viewModel.contains("return try await creation.start(request)"))
        let sessionStart = try XCTUnwrap(viewModel.range(of: "func startSessionRun("))
        let sessionStartBody = viewModel[sessionStart.lowerBound..<viewModel.endIndex]
        let beginOffset = try XCTUnwrap(sessionStartBody.range(
            of: "beginBackgroundGeneration(for: request)"
        ))
        let firstAwaitOffset = try XCTUnwrap(sessionStartBody.range(of: "try await creation.start"))
        XCTAssertLessThan(
            beginOffset.lowerBound,
            firstAwaitOffset.lowerBound,
            "The lease must be submitted before the first suspension in the start entry."
        )
        XCTAssertTrue(lifecycle.contains("BackgroundGenerationKeepAlive.shared.end"))
        XCTAssertTrue(workspaceLifecycle.contains("hasProtectedGenerationLease"))

        let replayStart = try XCTUnwrap(lifecycle.range(of: "func replayOrObserve("))
        let replayEnd = try XCTUnwrap(lifecycle.range(
            of: "\n    func makeRuntimeAndStream(",
            range: replayStart.upperBound..<lifecycle.endIndex
        ))
        let replay = lifecycle[replayStart.lowerBound..<replayEnd.lowerBound]
        XCTAssertTrue(replay.contains(") async throws -> NovelRun?"))
        let replayTerminal = try XCTUnwrap(replay.range(of: "yieldReplayTerminal("))
        let replayEndLease = try XCTUnwrap(replay.range(
            of: "await endBackgroundLease(for: run.id)",
            range: replayTerminal.upperBound..<replay.endIndex
        ))
        let replayReturn = try XCTUnwrap(replay.range(
            of: "return NovelRun(id: run.id, events: pair.stream)",
            range: replayEndLease.upperBound..<replay.endIndex
        ))
        XCTAssertLessThan(
            replayEndLease.lowerBound,
            replayReturn.lowerBound,
            "A terminal idempotency replay must release its per-run lease before returning."
        )
    }

    func testScenePhaseCycleDoesNotCompeteWithAnActiveNovelGenerationLease() async {
        var beginCount = 0
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginKeepAlive: { _, _ in beginCount += 1 },
            hasProtectedGenerationLease: { true }
        )

        coordinator.enterBackground(
            waitForCompletion: {},
            interrupt: { _ in }
        )
        await Task.yield()

        XCTAssertEqual(beginCount, 0)
    }

    func testLateProtectedLeaseSupersedesTheLegacySceneCycleBeforeExpiration() async {
        var expirationHandler: (() -> Void)?
        var protectedLeaseIsActive = false
        var endedLeaseIds: [String] = []
        var interruptionCount = 0
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginKeepAlive: { _, onExpire in expirationHandler = onExpire },
            endKeepAlive: { endedLeaseIds.append($0) },
            hasProtectedGenerationLease: { protectedLeaseIsActive }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )
        protectedLeaseIsActive = true
        expirationHandler?()

        let didEndLegacyLease = await eventually { endedLeaseIds.count == 1 }
        XCTAssertTrue(didEndLegacyLease)
        XCTAssertEqual(interruptionCount, 0)
        await completionGate.open()
    }

    func testWholeChapterCollectionPrefillsTheGeneratedTitleWithoutASecondRequest() throws {
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let prompts = try source("iosApp/NovelCreation/NovelPromptCatalog.swift")

        XCTAssertTrue(sheets.contains("NovelPresentation.chapterDisplayTitle("))
        XCTAssertTrue(prompts.contains("Markdown H1 chapter heading"))
        XCTAssertTrue(prompts.contains("novel.prose-whole-chapter.v3"))
        XCTAssertFalse(sheets.contains("generateChapterTitle"))
    }

    func testShortNovelSheetsUseContentFittedSizingWithoutFixedHeightCompensation() throws {
        let workspaceSheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let projectList = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let branches = try source("iosApp/NovelCreation/NovelBranchesView.swift")

        XCTAssertTrue(workspaceSheets.contains("struct NovelDiscussionArchiveOfferSheet"))
        XCTAssertTrue(workspaceSheets.contains(".presentationSizing(.fitted)"))
        // 短 offer 不用 NavigationStack 撑高；双按钮横排且文案四字。
        let offerStart = try XCTUnwrap(
            workspaceSheets.range(of: "struct NovelDiscussionArchiveOfferSheet")
        )
        let nextStruct = workspaceSheets.range(
            of: "\nstruct ",
            range: offerStart.upperBound..<workspaceSheets.endIndex
        )
        let offerEnd = nextStruct?.lowerBound ?? workspaceSheets.endIndex
        let offerBody = String(workspaceSheets[offerStart.lowerBound..<offerEnd])
        XCTAssertFalse(offerBody.contains("NavigationStack"))
        // label 内 frame 铺满半宽：Button { } label: { Text(...) }
        XCTAssertTrue(offerBody.contains("Text(\"暂不归档\")"))
        XCTAssertTrue(offerBody.contains("Text(\"归档讨论\")"))
        XCTAssertTrue(offerBody.contains("HStack(spacing: 10)"))
        XCTAssertTrue(offerBody.contains("maxWidth: .infinity, minHeight: 44"))
        XCTAssertTrue(projectList.contains("struct NovelProjectRenameSheet"))
        XCTAssertTrue(projectList.contains(".presentationSizing(.fitted)"))
        XCTAssertFalse(projectList.contains(".presentationDetents([.height(220)])"))
        XCTAssertTrue(branches.contains("struct NovelBranchRenameSheet"))
        XCTAssertTrue(branches.contains(".presentationSizing(.fitted)"))
        XCTAssertFalse(branches.contains(".presentationDetents([.height(220)])"))
    }

    func testWritingContextKeepsThisGenerationDraftWhenOpeningPreferenceEditors() throws {
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let writingContextStart = try XCTUnwrap(sheets.range(of: "struct NovelWritingContextSheet"))
        let manualRewriteStart = try XCTUnwrap(sheets.range(
            of: "struct NovelManualRewriteCandidateSheet",
            range: writingContextStart.upperBound..<sheets.endIndex
        ))
        let writingContext = sheets[writingContextStart.lowerBound..<manualRewriteStart.lowerBound]

        XCTAssertTrue(writingContext.contains("applyDraftBeforeTransition(onEditWritingRequirements)"))
        XCTAssertTrue(writingContext.contains("applyDraftBeforeTransition(onEditPolishPreference)"))
        XCTAssertTrue(writingContext.contains("onApply(overrides, budgetTokens)"))
    }

    func testPolishPreferenceDoesNotSilentlyDiscardOrFakeASave() throws {
        let materials = try source("iosApp/NovelCreation/NovelMaterialsView.swift")
        let sheetStart = try XCTUnwrap(materials.range(of: "struct NovelPolishPreferenceSheet"))
        let nextSheet = try XCTUnwrap(materials.range(
            of: "struct NovelProposalAcceptanceSheet",
            range: sheetStart.upperBound..<materials.endIndex
        ))
        let sheet = materials[sheetStart.lowerBound..<nextSheet.lowerBound]

        XCTAssertTrue(sheet.contains("hasUnsavedChanges"))
        XCTAssertTrue(sheet.contains("NovelTextInputCommitter.perform { requestDismiss() }"))
        XCTAssertTrue(sheet.contains("NovelTextInputCommitter.perform { save() }"))
        XCTAssertTrue(sheet.contains(".interactiveDismissDisabled()"))
        XCTAssertTrue(sheet.contains("let saved = await viewModel.setPolishPreference(preference)"))
        XCTAssertTrue(sheet.contains("guard saved else"))
    }

    func testBatchPolishKeepsChapterSelectionWhileEditingItsPreference() throws {
        let batch = try source("iosApp/NovelCreation/NovelBatchPolishSheet.swift")
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")

        XCTAssertTrue(batch.contains("@State private var selectedChapterIDs"))
        XCTAssertTrue(batch.contains("@State private var isEditingPolishPreference = false"))
        XCTAssertTrue(batch.contains(".sheet(isPresented: $isEditingPolishPreference)"))
        XCTAssertTrue(batch.contains("NovelPolishPreferenceSheet(viewModel: workspace)"))
        XCTAssertFalse(batch.contains("let onEditPolishPreference"))
        let batchSheetStart = try XCTUnwrap(workspace.range(of: "case .batchPolish:"))
        let nextSheet = try XCTUnwrap(workspace.range(
            of: "case .collectCandidate",
            range: batchSheetStart.upperBound..<workspace.endIndex
        ))
        let batchSheet = workspace[batchSheetStart.lowerBound..<nextSheet.lowerBound]
        XCTAssertFalse(batchSheet.contains("transition(to: .polishPreference)"))
    }

    func testChapterMutationGatesUseTheCurrentBranchAndPreserveActionableReasons() throws {
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")
        let chapters = try source("iosApp/NovelCreation/NovelChapterViews.swift")

        XCTAssertTrue(reader.contains("private var currentBranchHasPendingOperations: Bool"))
        XCTAssertTrue(chapters.contains("private var currentBranchHasPendingOperations: Bool"))
        XCTAssertFalse(reader.contains("projectSnapshot?.pendingOperations.isEmpty == false"))
        XCTAssertFalse(chapters.contains("projectSnapshot?.pendingOperations.isEmpty == false"))
        XCTAssertTrue(reader.contains("if isCurrentChapterDiscarded { return \"请先恢复本章\" }"))
        XCTAssertTrue(reader.contains(".disabled(chapterDiscardBlockReason != nil)"))

        let activeRunReason = try XCTUnwrap(reader.range(of: "if sessionViewModel.isRunning"))
        let genericMutationReason = try XCTUnwrap(reader.range(of: "if !viewModel.canMutate"))
        XCTAssertLessThan(activeRunReason.lowerBound, genericMutationReason.lowerBound)
    }

    func testChapterSheetsExposeRestoreProgressAndRejectNoopEdits() throws {
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")
        let chapters = try source("iosApp/NovelCreation/NovelChapterViews.swift")

        XCTAssertTrue(reader.contains("private var hasChanges: Bool"))
        XCTAssertTrue(chapters.contains("private var restoreBlockReason: String?"))
        XCTAssertTrue(chapters.contains(".interactiveDismissDisabled(isSubmitting)"))
        XCTAssertTrue(chapters.contains("currentBranchHasPendingOperations"))
    }

    func testLongFormMaterialAndBranchEditorsProtectUnsavedChanges() throws {
        let materials = try source("iosApp/NovelCreation/NovelMaterialsView.swift")
        let branches = try source("iosApp/NovelCreation/NovelBranchesView.swift")
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")
        let materialStart = try XCTUnwrap(materials.range(of: "struct NovelMaterialEditorSheet"))
        let materialEnd = try XCTUnwrap(materials.range(of: "struct NovelPolishPreferenceSheet"))
        let branchStart = try XCTUnwrap(branches.range(of: "private enum NovelBranchOverrideDraft"))
        let materialSheet = materials[materialStart.lowerBound..<materialEnd.lowerBound]
        let branchSheet = branches[branchStart.lowerBound...]

        XCTAssertTrue(materialSheet.contains("private var hasUnsavedChanges: Bool"))
        XCTAssertTrue(materialSheet.contains("currentDraft != initialDraft"))
        XCTAssertTrue(materialSheet.contains("aliases: normalizedAliases"))
        XCTAssertTrue(materialSheet.contains("NovelTextInputCommitter.perform { requestDismiss() }"))
        XCTAssertTrue(materialSheet.contains(
            "guard isEditable, !isSaving else { return }"
        ))
        XCTAssertTrue(materialSheet.contains("guard hasUnsavedChanges else"))

        XCTAssertTrue(branchSheet.contains("private var hasUnsavedChanges: Bool"))
        XCTAssertTrue(branchSheet.contains("currentDraft != initialDraft"))
        XCTAssertTrue(branchSheet.contains("case existing(NovelMaterialRevisionID?)"))
        XCTAssertTrue(branchSheet.contains("case newRevision("))
        XCTAssertTrue(branchSheet.contains(
            "guard viewModel.canMutate, !isSubmitting else { return }"
        ))
        XCTAssertTrue(branchSheet.contains("guard canSave else"))
        XCTAssertTrue(branchSheet.contains("NovelTextInputCommitter.perform { requestDismiss() }"))
        XCTAssertTrue(branchSheet.contains("let saved = await viewModel.setBranchMaterialOverride("))
        XCTAssertTrue(branchSheet.contains("guard saved else"))
        XCTAssertTrue(viewModel.contains(") async -> Bool"))
    }

    func testProjectCreationAndQuickStartGuidanceProtectEnteredDrafts() throws {
        let projectList = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")

        let createStart = try XCTUnwrap(projectList.range(of: "private struct NovelProjectCreateSheet"))
        let createEnd = try XCTUnwrap(projectList.range(
            of: "struct NovelProjectRenameSheet",
            range: createStart.upperBound..<projectList.endIndex
        ))
        let createSheet = projectList[createStart.lowerBound..<createEnd.lowerBound]

        XCTAssertTrue(createSheet.contains("@State private var isConfirmingDiscard = false"))
        XCTAssertTrue(createSheet.contains("private var hasUnsavedChanges: Bool"))
        XCTAssertTrue(createSheet.contains("NovelTextInputCommitter.perform { requestDismiss() }"))
        XCTAssertTrue(createSheet.contains("guard canCreate else"))
        XCTAssertTrue(createSheet.contains(".disabled(viewModel.isProjectSelectionBlocked)"))
        XCTAssertTrue(createSheet.contains(".interactiveDismissDisabled()"))

        let regenerationStart = try XCTUnwrap(compendium.range(
            of: "struct NovelQuickStartRegenerationSheet"
        ))
        let regenerationEnd = try XCTUnwrap(compendium.range(
            of: "struct NovelCompendiumProposalCard",
            range: regenerationStart.upperBound..<compendium.endIndex
        ))
        let regenerationSheet = compendium[
            regenerationStart.lowerBound..<regenerationEnd.lowerBound
        ]

        XCTAssertTrue(regenerationSheet.contains("@State private var isConfirmingDiscard = false"))
        XCTAssertTrue(regenerationSheet.contains("private var hasUnsavedChanges: Bool"))
        XCTAssertTrue(regenerationSheet.contains("if hasUnsavedChanges"))
        XCTAssertTrue(regenerationSheet.contains(
            "NovelTextInputCommitter.perform { requestDismiss() }"
        ))
        XCTAssertTrue(regenerationSheet.contains("isConfirmingCoreIdeaLoad"))
        XCTAssertTrue(regenerationSheet.contains(".interactiveDismissDisabled()"))
    }

    func testProposalAcceptanceEditsTheExactContentWrittenAtomically() throws {
        let materials = try source("iosApp/NovelCreation/NovelMaterialsView.swift")
        let actions = try source("iosApp/NovelCreation/NovelActions.swift")
        let reducer = try source("iosApp/NovelCreation/NovelProjectConfiguration.swift")
        let proposalStart = try XCTUnwrap(materials.range(of: "struct NovelProposalAcceptanceSheet"))
        let previewStart = try XCTUnwrap(materials.range(
            of: "struct NovelInjectionPreviewSheet",
            range: proposalStart.upperBound..<materials.endIndex
        ))
        let sheet = materials[proposalStart.lowerBound..<previewStart.lowerBound]

        XCTAssertTrue(sheet.contains("title: title"))
        XCTAssertTrue(sheet.contains("content: content"))
        XCTAssertTrue(sheet.contains("let saved = await viewModel.resolveProposal("))
        XCTAssertTrue(sheet.contains("if hasUnsavedChanges"))
        XCTAssertTrue(sheet.contains(".interactiveDismissDisabled()"))
        XCTAssertTrue(actions.contains("title: String"))
        XCTAssertTrue(actions.contains("content: String"))
        XCTAssertTrue(reducer.contains("title: try NovelReducer.normalizedRequired(title"))
        XCTAssertTrue(reducer.contains(
            "content: try NovelReducer.normalizedRequired(content, field: \"Proposal content\")"
        ))
    }

    func testSessionAssistantBodyAlwaysUsesMarkdownViewNotPlainText() throws {
        let bubble = try source("iosApp/NovelCreation/NovelSessionBubble.swift")
        let bodyStart = try XCTUnwrap(bubble.range(of: "private var assistantBubble"))
        let bodyEnd = try XCTUnwrap(bubble.range(
            of: "private var statusLine",
            range: bodyStart.upperBound..<bubble.endIndex
        ))
        let body = bubble[bodyStart.lowerBound..<bodyEnd.lowerBound]

        XCTAssertTrue(
            body.contains("ChatAssistantMarkdownView"),
            "Assistant manuscript/discussion body must render via markdown"
        )
        // Empty pending / muted hints may use ChatAssistantText; manuscript content must not.
        XCTAssertFalse(
            body.contains("History prose/polish"),
            "Do not reintroduce the plain-text history prose shortcut"
        )
        XCTAssertFalse(
            body.contains("Text(markdown)"),
            "Manuscript body must not use unparsed Text(markdown)"
        )
        XCTAssertTrue(
            body.contains("isStreaming: isStreaming")
                && body.contains("hasEverStreamed: hasEverStreamed"),
            "Streaming flags must match Chat: animation off when complete, sticky via hasEverStreamed"
        )
    }

    func testNovelDraftActionsCommitMarkedTextBeforeReadingState() throws {
        let support = try source("iosApp/NovelCreation/NovelPresentationSupport.swift")
        let projectList = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")
        let materials = try source("iosApp/NovelCreation/NovelMaterialsView.swift")
        let branches = try source("iosApp/NovelCreation/NovelBranchesView.swift")
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let bubble = try source("iosApp/NovelCreation/NovelSessionBubble.swift")

        XCTAssertTrue(support.contains("(firstResponder as? UITextInput)?.unmarkText()"))
        XCTAssertTrue(
            support.contains("await Task.yield()")
                || support.contains("DispatchQueue.main.async"),
            "Deferred main flush so SwiftUI bindings see committed text"
        )
        XCTAssertTrue(
            support.contains("unmark before resign")
                || support.contains("unmarkText") && support.contains("resignFirstResponder"),
            "Committer must unmark before resign so IME composition is not discarded"
        )
        XCTAssertTrue(support.contains("static func hasMarkedText"))
        for file in [projectList, compendium, materials, branches, reader, sheets, bubble] {
            XCTAssertTrue(file.contains("NovelTextInputCommitter.perform"))
        }
        // Rename must not clear FocusState before committer (that drops marked text).
        let renameStart = try XCTUnwrap(projectList.range(of: "private func commitNameAndSave"))
        let renameEnd = try XCTUnwrap(projectList.range(
            of: "private func save(_ committedName",
            range: renameStart.upperBound..<projectList.endIndex
        ))
        let renameBody = projectList[renameStart.lowerBound..<renameEnd.lowerBound]
        XCTAssertTrue(renameBody.contains("NovelTextInputCommitter.perform"))
        XCTAssertFalse(
            renameBody.contains("isNameFocused = false"),
            "Clearing FocusState before unmarkText discards the last IME composition"
        )
        // Writing-context toolbar must commit marked text before apply/dismiss.
        XCTAssertTrue(sheets.contains("fieldBank: planFieldBank"))
        XCTAssertTrue(
            sheets.contains("NovelIMETextField") && sheets.contains("planPlacementBinding"),
            "本章计划「与总纲的位置」must use UIKit-backed IME field, not plain TextField"
        )
        XCTAssertTrue(sheets.contains("NovelIMETextEditor"))
        XCTAssertTrue(sheets.contains("planFieldBank.commitAll()"))
        XCTAssertTrue(sheets.contains("planFieldsDirty"))
        XCTAssertTrue(support.contains("struct NovelIMETextField"))
        XCTAssertTrue(support.contains("struct NovelIMETextEditor"))
        XCTAssertTrue(support.contains("final class NovelIMEFieldBank"))
        XCTAssertTrue(support.contains("commitAndReadActiveUIKitText"))
        // Save-bound form sheets must flush via field bank, not plain TextField alone.
        for (file, label) in [
            (projectList, "project list"),
            (materials, "materials"),
            (branches, "branches"),
            (reader, "chapter reader"),
            (compendium, "compendium"),
            (bubble, "ask-user bubble"),
        ] {
            XCTAssertTrue(
                file.contains("NovelIMEFieldBank") || file.contains("imeBank"),
                "\(label) should own an IME field bank"
            )
            XCTAssertTrue(
                file.contains("fieldBank: imeBank") || file.contains("fieldBank: planFieldBank"),
                "\(label) save path should pass fieldBank into NovelTextInputCommitter"
            )
        }
        XCTAssertTrue(try source("iosApp/NovelCreation/NovelSessionView.swift").contains("imeBank"))
    }

    func testHistoricalCharacterIdentityFlowsThroughSessionAndCharacterSurfaces() throws {
        let characters = try source("iosApp/NovelCreation/NovelCharacterPagesView.swift")
        let sessionViewModel = try source("iosApp/NovelCreation/NovelSessionViewModel.swift")
        let creationViewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")

        XCTAssertTrue(characters.contains("viewModel.effectiveAliases(for: material)"))
        XCTAssertTrue(sessionViewModel.contains("workspace.effectiveAliases(for: material)"))
        XCTAssertTrue(creationViewModel.contains("func effectiveAliases(for material:"))
    }

    func testNovelComposerDraftCommitsMarkedTextBeforeSheetAndPersistence() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(workspace.contains(
            "@State private var sessionComposerInputController = ComposerInputController()"
        ))
        XCTAssertTrue(workspace.contains(
            "composerInputController: sessionComposerInputController"
        ))
        XCTAssertGreaterThanOrEqual(
            workspace.components(
                separatedBy: "sessionComposerInputController.committedText()"
            ).count - 1,
            2
        )
        XCTAssertTrue(workspace.contains("if phase == .background {\n                saveLoadedComposerDraft()"))
        XCTAssertTrue(session.contains("let composerInputController: ComposerInputController"))
        XCTAssertTrue(session.contains(
            "let committed = composerInputController.committedText() ?? inputText"
        ))
        XCTAssertTrue(session.contains("guard sendEnabled(for: committed) else { return }"))
        XCTAssertTrue(session.contains(".onDisappear {\n            if let committed"))
        XCTAssertFalse(session.contains(
            "@State private var composerInputController = ComposerInputController()"
        ))
    }

    func testBranchMutationGatesMatchReducerBusySemantics() throws {
        let branches = try source("iosApp/NovelCreation/NovelBranchesView.swift")

        XCTAssertTrue(branches.contains("private var hasReducerBlockingBranchOperation: Bool"))
        XCTAssertTrue(branches.contains("$0.status == .pending || $0.status == .retryable"))
        XCTAssertFalse(branches.contains(
            "$0.status == .pending || $0.status == .retryable || $0.status == .blocked"
        ))
        XCTAssertTrue(branches.contains(".disabled(!canEditBranchOverride"))
        XCTAssertTrue(branches.contains("!canDeleteBranch"))
        XCTAssertTrue(branches.contains("time: .shortened"))
    }

    func testNovelContextPanelUsesLatestInjectionReceiptDetails() throws {
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")
        let composer = try source("iosApp/ChatComposerViews.swift")
        let planner = try source("iosApp/NovelCreation/NovelInjectionPlanner.swift")

        XCTAssertTrue(session.contains(
            "NovelInjectionPanelPresentation.project(latestContextReceipt)"
        ))
        XCTAssertTrue(session.contains("novelInjection: contextPanelModel"))
        XCTAssertTrue(composer.contains("let novelInjection: NovelInjectionPanelModel?"))
        XCTAssertTrue(planner.contains("let includedMaterials: [NovelInjectionMaterialReceiptItem]?"))
        XCTAssertTrue(planner.contains("let recentMessageRoundCount: Int?"))
        XCTAssertTrue(planner.contains("let budgetExcludedItemCount: Int?"))
    }

    func testDiscussionArchiveHasOnlyManualAndPostWholeChapterEntryPoints() throws {
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")

        XCTAssertTrue(session.contains("Button(\"归档当前讨论\""))
        XCTAssertTrue(session.contains("onArchiveDiscussion"))
        XCTAssertTrue(session.contains("viewModel.needsSync"))
        XCTAssertTrue(workspace.contains(
            "sessionViewModel.collectionGranularity(for: candidateID) == .wholeChapter"
        ))
        XCTAssertTrue(workspace.contains("transition(to: .discussionArchiveOffer("))
        XCTAssertTrue(workspace.contains(
            "isReady: !sessionViewModel.needsSync && !sessionViewModel.isBusy"
        ))
        XCTAssertTrue(workspace.contains("transition(to: .discussionArchive(chapterID))"))
    }

    func testSessionSheetsPreserveUserDraftsAndSeparateArchiveRetryPaths() throws {
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let archiveStart = try XCTUnwrap(sheets.range(of: "struct NovelDiscussionArchiveSheet"))
        let collectStart = try XCTUnwrap(sheets.range(of: "struct NovelCollectCandidateSheet"))
        let forkStart = try XCTUnwrap(sheets.range(of: "struct NovelSessionForkSheet"))
        let writingContextStart = try XCTUnwrap(sheets.range(of: "struct NovelWritingContextSheet"))
        let manualRewriteStart = try XCTUnwrap(sheets.range(of: "struct NovelManualRewriteCandidateSheet"))
        let archiveSheet = sheets[archiveStart.lowerBound..<collectStart.lowerBound]
        let collectSheet = sheets[collectStart.lowerBound..<forkStart.lowerBound]
        let writingContextSheet = sheets[
            writingContextStart.lowerBound..<manualRewriteStart.lowerBound
        ]

        XCTAssertTrue(sheets.contains("@State private var preparationTask: Task<Void, Never>?"))
        XCTAssertTrue(archiveSheet.contains("if hasUnsavedChanges"))
        XCTAssertTrue(archiveSheet.contains(".interactiveDismissDisabled()"))
        XCTAssertTrue(sheets.contains(".onDisappear { preparationTask?.cancel() }"))
        XCTAssertTrue(sheets.contains("Button(\"重新整理\") { prepare() }"))
        XCTAssertTrue(sheets.contains("submissionFailureMessage == nil ? \"确认归档\" : \"重试保存\""))

        XCTAssertTrue(sheets.contains("private func refreshEditedTextAfterSelectionChange()"))
        XCTAssertTrue(sheets.contains("guard !hasEditedText else { return }"))
        XCTAssertTrue(collectSheet.contains(
            ".disabled(isSubmitting || hasDurablePending)"
        ))
        XCTAssertTrue(collectSheet.contains("let nextChapterOrdinal: Int"))
        XCTAssertTrue(collectSheet.contains("let nextOrdinal = nextChapterOrdinal"))
        XCTAssertFalse(collectSheet.contains("chapters.count + 1"))
        XCTAssertTrue(writingContextSheet.contains("NovelPresentation.effectiveRevision("))
        XCTAssertTrue(writingContextSheet.contains("branch: workspace.branchSnapshot"))
        XCTAssertFalse(writingContextSheet.contains("NovelPresentation.currentRevision("))
    }

    func testProjectTitleOpensWritingAndHierarchicalContextSheet() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")

        XCTAssertTrue(workspace.contains("activeSheet = .writingContext"))
        XCTAssertTrue(workspace.contains("NovelWritingContextSheet("))
        XCTAssertFalse(workspace.contains("NovelProjectPanelSheet("))
        XCTAssertTrue(workspace.contains("ToolbarItem(id: NovelCreationToolbarID.settings"))
        XCTAssertTrue(workspace.contains("NovelCreationSettingsToolbarButton"))
        XCTAssertTrue(workspace.contains(".novelCreationSettings"))
        XCTAssertFalse(workspace.contains(".matchedTransitionSource("))
        XCTAssertFalse(workspace.contains(".disabled(viewModel.projectSnapshot == nil"))
        XCTAssertFalse(workspace.contains("activeSheet = .projectSettings"))
        XCTAssertFalse(workspace.contains("NovelProjectSettingsSheet("))
        XCTAssertFalse(sheets.contains("struct NovelProjectSettingsSheet"))
        XCTAssertTrue(sheets.contains("Picker(\"项目控制\", selection: $selectedTab)"))
        XCTAssertTrue(sheets.contains("case .preferences: \"模式与偏好\""))
        XCTAssertTrue(sheets.contains("case .context: \"上下文注入\""))
        XCTAssertTrue(sheets.contains("Text(\"代笔进度\")"))
        XCTAssertTrue(sheets.contains("Text(ghostwriteAdvanceSectionTitle)"))
        XCTAssertTrue(sheets.contains("return \"开始代笔\""))
        XCTAssertTrue(sheets.contains("Text(\"往后几章\")"))
        XCTAssertTrue(sheets.contains("Text(\"本章计划\")"))
        XCTAssertTrue(sheets.contains("upsertUpcomingArc"))
        XCTAssertTrue(sheets.contains("boardStepSummary"))
        XCTAssertTrue(sheets.contains("reviewModelLabel"))
        XCTAssertTrue(sheets.contains("小说默认 · \\(name)"))
        XCTAssertTrue(workspace.contains("sharedSettings: sharedSettings"))
        XCTAssertTrue(sheets.contains("NavigationLink(value: ContextRoute.materials(category))"))
        XCTAssertTrue(sheets.contains("case .characters: \"人物角色\""))
        XCTAssertTrue(sheets.contains("case .world: \"世界观\""))
        XCTAssertTrue(sheets.contains("case .story: \"剧情大纲\""))
        XCTAssertFalse(workspace.contains("case .branchPicker:"))
        XCTAssertFalse(workspace.contains("NovelBranchPickerSheet"))
        XCTAssertFalse(compendium.contains("Section(\"项目\")"))
        XCTAssertFalse(compendium.contains("Section(\"模型与写作\")"))
        XCTAssertFalse(compendium.contains("Text(\"导入与导出\")"))
    }

    func testProjectSettingsExposeIndependentCreationAndSyncModels() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let settings = try source("iosApp/NovelCreation/NovelCreationSettingsView.swift")
        let projectSettings = try source("iosApp/NovelCreation/NovelProjectSettingsDetailView.swift")

        XCTAssertTrue(workspace.contains("case .modelPicker(let purpose):"))
        XCTAssertTrue(workspace.contains("await viewModel.setModelPolicy(policy, for: purpose)"))
        XCTAssertFalse(settings.contains("NovelCreationSettingsScope"))
        XCTAssertTrue(settings.contains("modelRow(for: .creation)"))
        XCTAssertTrue(settings.contains("modelRow(for: .stateSync)"))
        XCTAssertTrue(settings.contains("modelRow(for: .review)"))
        XCTAssertTrue(settings.contains("NovelProjectManagementView("))
        XCTAssertTrue(settings.contains("优先选择擅长长文与创意写作的模型"))
        XCTAssertTrue(settings.contains("优先选择稳定、便宜、结构化输出可靠的模型"))
        XCTAssertTrue(settings.contains("核对是否按计划写、查前后是否打架"))
        XCTAssertTrue(settings.contains("审稿用来核对是否按计划写"))
        XCTAssertFalse(settings.contains("未配置时跟随小说默认"))
        XCTAssertTrue(projectSettings.contains("viewModel.setModelPolicy(policy, for: purpose)"))
        XCTAssertTrue(projectSettings.contains("modelRow(for: .review)"))
        XCTAssertTrue(projectSettings.contains("Label(\"导出正文\", systemImage: \"square.and.arrow.up\")"))
    }

    func testNovelWorkspaceLoadFailureKeepsAnInlineRetryPath() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")

        XCTAssertTrue(workspace.contains("routedProjectLoadFailure"))
        XCTAssertTrue(workspace.contains("ContentUnavailableView("))
        XCTAssertTrue(workspace.contains("Button(\"重新读取\")"))
        XCTAssertTrue(workspace.contains("await loadRoutedProject()"))
    }

    func testNovelProjectSettingsExportsPackageWithoutStoppingMarkdownGeneration() throws {
        let settings = try source("iosApp/NovelCreation/NovelProjectSettingsDetailView.swift")

        XCTAssertTrue(settings.contains("Label(\"导出项目包\", systemImage: \"archivebox\")"))
        XCTAssertTrue(settings.contains("NovelProjectFileDocument(data: artifact.data)"))
        XCTAssertTrue(settings.contains("contentType: .amberNovelProject"))
        let markdownExport = try XCTUnwrap(settings.range(of: "private func exportMarkdown()"))
        let resultHandler = try XCTUnwrap(settings.range(
            of: "private func handleExportResult",
            range: markdownExport.upperBound..<settings.endIndex
        ))
        let markdownBody = settings[markdownExport.lowerBound..<resultHandler.lowerBound]
        XCTAssertFalse(markdownBody.contains("stopActiveRunsForProjectOperation"))
    }

    func testUnavailableProjectRowRetriesReadingInsteadOfOpeningDeleteConfirmation() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")

        XCTAssertTrue(list.contains("retryOpening(project)"))
        XCTAssertTrue(list.contains("Button(\"重新读取\")"))
        XCTAssertFalse(list.contains(
            "if project.loadError != nil {\n                            prepareDelete(project)"
        ))
        XCTAssertFalse(list.contains("项目文件已经损坏"))
        XCTAssertFalse(list.contains("项目文件损坏"))
    }

    func testUnavailableImportConflictDoesNotOfferImpossibleReplacement() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")

        XCTAssertTrue(list.contains("if existing.loadError == nil"))
        XCTAssertTrue(list.contains("本地项目当前无法读取，只能将导入包保留为副本。"))
        let importSheet = try XCTUnwrap(list.range(of: "struct NovelProjectImportSheet"))
        let previewFixtures = try XCTUnwrap(list.range(
            of: "#if DEBUG",
            range: importSheet.upperBound..<list.endIndex
        ))
        let body = list[importSheet.lowerBound..<previewFixtures.lowerBound]
        XCTAssertEqual(body.components(separatedBy: "Label(\"替换本地项目\"").count - 1, 1)
        XCTAssertTrue(body.contains("Label(\"保留两份\", systemImage: \"plus.square.on.square\")"))
    }

    func testProjectSettingsAlignPackageExportAndBranchSwitchWithRunningRun() throws {
        let settings = try source("iosApp/NovelCreation/NovelProjectSettingsDetailView.swift")

        XCTAssertTrue(settings.contains("private var hasRunningRun: Bool"))
        XCTAssertTrue(settings.contains(
            ".disabled(currentProject == nil || viewModel.isPerforming || hasRunningRun)"
        ))
        let markdownButton = try XCTUnwrap(settings.range(
            of: "Button(action: exportMarkdown)"
        ))
        let markdownButtonEnd = try XCTUnwrap(settings.range(
            of: "\n            }",
            range: markdownButton.upperBound..<settings.endIndex
        ))
        let markdownBody = settings[markdownButton.lowerBound..<markdownButtonEnd.lowerBound]
        XCTAssertFalse(markdownBody.contains("hasRunningRun"))
        XCTAssertTrue(settings.contains("pendingBranchSelection"))
        XCTAssertTrue(settings.contains("切换分支会停止当前生成"))
        XCTAssertTrue(settings.contains("selectPendingBranch()"))
    }

    func testProjectSettingsLoadFailureHasInlineRetryInsteadOfPermanentSpinner() throws {
        let settings = try source("iosApp/NovelCreation/NovelProjectSettingsDetailView.swift")

        XCTAssertTrue(settings.contains("projectLoadFailure"))
        XCTAssertTrue(settings.contains("Button(\"重新读取\")"))
        XCTAssertTrue(settings.contains("await loadProject()"))
        XCTAssertTrue(settings.contains("isLoadingProject"))
    }

    func testProjectListExplainsAutomaticStateSyncSelectionBlock() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")

        XCTAssertTrue(list.contains("viewModel.stateSyncActivity"))
        XCTAssertTrue(list.contains("NovelStateSyncProgressBanner("))
        XCTAssertTrue(list.contains("完成前暂不能切换项目"))
        XCTAssertTrue(list.contains("viewModel.cancelAutomaticStateSync("))
        let topInset = try XCTUnwrap(list.range(of: ".safeAreaInset(edge: .top"))
        let bottomInset = try XCTUnwrap(list.range(
            of: ".safeAreaInset(edge: .bottom",
            range: topInset.upperBound..<list.endIndex
        ))
        let routing = list[topInset.lowerBound..<bottomInset.lowerBound]
        let continuity = try XCTUnwrap(routing.range(of: "viewModel.isContinuityOperationRunning"))
        let stateSync = try XCTUnwrap(routing.range(of: "viewModel.isStateSyncOperationRunning"))
        let reload = try XCTUnwrap(routing.range(of: "viewModel.hasReloadRequirement"))
        XCTAssertLessThan(continuity.lowerBound, reload.lowerBound)
        XCTAssertLessThan(stateSync.lowerBound, reload.lowerBound)
    }

    func testProjectListLoadingStaysLightweightAndFailuresRemainRetryable() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let loadingStart = try XCTUnwrap(list.range(of: "if isPreparingImportPreview"))
        let sheetStart = try XCTUnwrap(list.range(
            of: ".sheet(item: $activeSheet)",
            range: loadingStart.upperBound..<list.endIndex
        ))
        let loadingOverlay = list[loadingStart.lowerBound..<sheetStart.lowerBound]

        XCTAssertTrue(loadingOverlay.contains("ProgressView"))
        XCTAssertFalse(loadingOverlay.contains("amberGlass"))
        XCTAssertTrue(list.contains("viewModel.projectListLoadError"))
        XCTAssertTrue(list.contains("Button(\"重新读取\")"))
        XCTAssertGreaterThanOrEqual(
            list.components(separatedBy: "loadProjects(restoresSelection: false)").count - 1,
            3
        )
        XCTAssertTrue(list.contains("let acceptedPreview = stoppingActiveRun ? nil : preview"))
        XCTAssertTrue(list.contains("preview: acceptedPreview"))
    }

    func testProjectDeletionStopsRunsOnlyWhenNeededAndShowsRowProgress() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")

        XCTAssertTrue(list.contains("if hasRunningRun(for: project.id)"))
        XCTAssertTrue(list.contains("deletingProjectID = project.id"))
        XCTAssertTrue(list.contains("defer { deletingProjectID = nil }"))
        XCTAssertTrue(list.contains("accessibilityLabel(\"正在删除项目\")"))
    }

    func testStateSyncRecoveryAndStopAreReachableOutsideTheComposer() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(workspace.contains("stateSyncRecoveryMessage("))
        XCTAssertTrue(workspace.contains("Button(\"重试同步\""))
        XCTAssertTrue(reader.contains("currentStateSyncRecoveryMessage"))
        XCTAssertTrue(reader.contains("Button(\"重试同步\""))
        XCTAssertTrue(reader.contains("cancelAutomaticStateSync("))
        XCTAssertTrue(session.contains("workspace.stateSyncRecoveryMessage("))
        XCTAssertTrue(workspace.contains(".disabled(!canRetryCurrentStateSync)"))
        XCTAssertTrue(reader.contains(".disabled(!canRetryCurrentStateSync)"))
        // Session retry uses branch-scoped canRetry only (not isBusy/isRunning/global block).
        XCTAssertTrue(
            session.contains("!workspace.canRetryStateSync(projectID: projectID, branchID: branchID)")
        )
        let bannerFn = try XCTUnwrap(session.range(of: "private func automaticStateSyncFailureBanner("))
        let bannerEnd = try XCTUnwrap(
            session.range(
                of: "private func polishRecoveryBanner(",
                range: bannerFn.upperBound..<session.endIndex
            )
        )
        let bannerBody = session[bannerFn.lowerBound..<bannerEnd.lowerBound]
        XCTAssertTrue(bannerBody.contains(".disabled("))
        XCTAssertFalse(bannerBody.contains("isBusy"))
        XCTAssertFalse(bannerBody.contains("isRunning"))
        XCTAssertFalse(bannerBody.contains("isProjectSelectionBlocked"))

        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")
        // Delete chapter must schedule plot sync the same way saveManualRewrite does.
        let deleteFn = try XCTUnwrap(viewModel.range(of: "func deleteChapterFromManuscript("))
        let nextFn = try XCTUnwrap(
            viewModel.range(
                of: "func restoreChapterVersion(",
                range: deleteFn.upperBound..<viewModel.endIndex
            )
        )
        let deleteBody = viewModel[deleteFn.lowerBound..<nextFn.lowerBound]
        XCTAssertTrue(deleteBody.contains("scheduleAutomaticStateSync("))
        XCTAssertTrue(viewModel.contains("已排队，等待当前同步结束后自动开始"))
    }

    func testStateSyncActivityIsProjectScopedAndScheduledSyncKeepsBackgroundLease() throws {
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(viewModel.contains("let projectID: NovelProjectID"))
        XCTAssertTrue(viewModel.contains("let branchID: NovelBranchID"))
        XCTAssertTrue(session.contains("let projectID = workspace.selectedProjectID"))
        XCTAssertTrue(session.contains("let branchID = workspace.selectedBranchID"))
        XCTAssertTrue(session.contains("activity.projectID == projectID"))
        XCTAssertTrue(session.contains("activity.branchID == branchID"))
        let backgroundCheck = try XCTUnwrap(viewModel.range(
            of: "private func backgroundGenerationProbe()"
        ))
        let loadProjects = try XCTUnwrap(viewModel.range(
            of: "func loadProjects(",
            range: backgroundCheck.upperBound..<viewModel.endIndex
        ))
        let backgroundBody = viewModel[backgroundCheck.lowerBound..<loadProjects.lowerBound]
        XCTAssertTrue(backgroundBody.contains("automaticStateSyncTask != nil"))
    }

    func testCreateAndImportEntrypointsUseTheProjectSelectionBlock() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")

        XCTAssertGreaterThanOrEqual(
            list.components(separatedBy: ".disabled(viewModel.isProjectSelectionBlocked)").count - 1,
            5
        )
        XCTAssertTrue(viewModel.contains(
            "if let projectID, selectedProjectID != projectID, isProjectSelectionBlocked"
        ))
    }

    func testQuickStartRegenerationEntryIsReachableFromTheLiveCompendiumView() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")

        XCTAssertTrue(workspace.contains("case .compendium:"))
        XCTAssertTrue(workspace.contains("NovelCompendiumView("))
        XCTAssertTrue(compendium.contains("private var quickStartRegenerationSection"))
        XCTAssertTrue(compendium.contains(
            "viewModel.projectSnapshot?.project.creationMode == .quickStart"
        ))
        XCTAssertTrue(compendium.contains(
            ".disabled(!viewModel.canMutate || quickStartRegenerationBlockReason != nil)"
        ))
        XCTAssertTrue(compendium.contains("quickStartRegenerationSection"))
        XCTAssertTrue(compendium.contains("NovelQuickStartRegenerationSheet(viewModel: viewModel)"))
    }

    func testLiveCompendiumManagesWritingRequirementsAndUsesEffectiveBranchRevisions() throws {
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")
        let materials = try source("iosApp/NovelCreation/NovelMaterialsView.swift")
        let effectiveRevisionCallCount = compendium.components(
            separatedBy: "NovelPresentation.effectiveRevision("
        ).count - 1

        XCTAssertTrue(compendium.contains("case .writingRequirements, .custom"))
        XCTAssertGreaterThanOrEqual(effectiveRevisionCallCount, 3)
        XCTAssertTrue(compendium.contains("NovelCompendiumMaterialEditTarget.resolve("))
        XCTAssertTrue(compendium.contains("branchOverrideRoute = NovelCompendiumBranchOverrideRoute("))
        XCTAssertTrue(compendium.contains("NovelBranchOverrideEditorSheet(viewModel:"))

        let proposalStart = try XCTUnwrap(materials.range(of: "struct NovelProposalAcceptanceSheet"))
        let previewStart = try XCTUnwrap(materials.range(of: "struct NovelInjectionPreviewSheet"))
        let proposalSheet = materials[proposalStart.lowerBound..<previewStart.lowerBound]
        XCTAssertTrue(proposalSheet.contains("NovelPresentation.currentRevision("))
        XCTAssertFalse(proposalSheet.contains("NovelPresentation.effectiveRevision("))
    }

    func testCharacterDeleteActionIsGatedByCanMutate() throws {
        let characters = try source("iosApp/NovelCreation/NovelCharacterPagesView.swift")
        let swipeStart = try XCTUnwrap(characters.range(of: ".swipeActions(edge: .trailing"))
        let swipeTail = String(characters[swipeStart.lowerBound...])
        let swipeEnd = try XCTUnwrap(swipeTail.range(of: "\n    }\n\n    private func materialTitle"))
        let swipeBody = String(swipeTail[..<swipeEnd.lowerBound])

        XCTAssertTrue(swipeBody.contains(".disabled(!viewModel.canMutate)"))
    }

    /// 真机实测缺陷的守护:带「调整方向」的快速开始失败后点重试,曾退回默认文案、
    /// 把用户填的调整方向静默丢掉(与 `isEligibleForExactRetry` 的精确重试契约相悖)。
    /// 重试必须从持久化的 user 消息取回原文并原样重发。
    func testQuickStartRetryReplaysTheOriginalUserTextInsteadOfDefaultCopy() throws {
        let sessionViewModel = try source("iosApp/NovelCreation/NovelSessionViewModel.swift")

        let retryRange = try XCTUnwrap(sessionViewModel.range(of: "if run.kind == .quickStart {"))
        let retryBody = String(sessionViewModel[retryRange.lowerBound...].prefix(700))
        XCTAssertTrue(retryBody.contains("$0.id == run.userMessageID"))
        XCTAssertTrue(retryBody.contains("exactUserText: originalUserText"))
        XCTAssertFalse(
            retryBody.contains("startQuickStartSuggestions(),"),
            "重试不得调用不带参数的版本——那会丢掉用户填写的调整方向"
        )
    }

    /// 剧情矛盾检查的入口接线守护。上一轮的教训是「数据层全绿、界面上一次都点不到」,
    /// 所以这里逐环锁住:工作区 → 设定页 → 剧情子页 → 检查区块 → ViewModel → 执行入口,
    /// 并且跳章确实接到了阅读器路由上。
    func testContinuityAuditEntryIsReachableFromTheLiveCompendiumView() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")
        let auditView = try source("iosApp/NovelCreation/NovelContinuityAuditView.swift")
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")

        XCTAssertTrue(workspace.contains("NovelCompendiumView("))
        XCTAssertTrue(workspace.contains(
            "chapterReaderRoute = NovelChapterReaderRoute(selection: selection)"
        ))
        XCTAssertTrue(compendium.contains("NovelContinuityAuditSection("))
        XCTAssertTrue(compendium.contains("onOpenChapter: onOpenChapter"))
        XCTAssertFalse(auditView.contains("@State private var isPlanning"))
        XCTAssertTrue(auditView.contains("viewModel.startContinuityAuditPlanning()"))
        XCTAssertTrue(auditView.contains("viewModel.startContinuityAudit()"))
        XCTAssertTrue(auditView.contains("viewModel.cancelContinuityAudit()"))
        XCTAssertTrue(viewModel.contains("try await creation.planContinuityAudit("))
        XCTAssertTrue(viewModel.contains("try await creation.auditContinuity("))
        XCTAssertTrue(viewModel.contains("continuityAuditTask = Task"))
        XCTAssertTrue(list.contains("viewModel.isContinuityOperationRunning"))
        XCTAssertTrue(list.contains("viewModel.cancelContinuityAudit()"))
        XCTAssertTrue(workspace.contains("viewModel.isContinuityOperationRunning"))
        XCTAssertTrue(workspace.contains("viewModel.cancelContinuityAudit()"))
    }

    private func source(_ relativePath: String) throws -> String {
        let testsDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        return try String(
            contentsOf: testsDirectory.deletingLastPathComponent().appendingPathComponent(relativePath),
            encoding: .utf8
        )
    }

    private func eventually(
        timeout: TimeInterval = 1,
        condition: @MainActor () async -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if await condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return await condition()
    }
}

private actor NovelWorkspaceLifecycleTestGate {
    private var isOpen = false
    private var continuation: CheckedContinuation<Void, Never>?

    var hasWaiter: Bool { continuation != nil }

    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { continuation in
            self.continuation = continuation
        }
    }

    func open() {
        isOpen = true
        continuation?.resume()
        continuation = nil
    }
}
