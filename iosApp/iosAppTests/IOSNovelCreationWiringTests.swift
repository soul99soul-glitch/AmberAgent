import UIKit
import XCTest
@testable import iosApp

@MainActor
final class IOSNovelCreationWiringTests: XCTestCase {
    func testSessionShortcutAndAppRoutesExposeNovelCreationWithoutRemovingSettingsMemory() throws {
        let projectID = NovelProjectID()
        let routes: [Route] = [.novelCreation, .novelProject(id: projectID)]
        let appShell = try source("iosApp/AppShell.swift")
        let home = try source("iosApp/PlaceholderViews.swift")

        XCTAssertEqual(routes, [.novelCreation, .novelProject(id: projectID)])
        XCTAssertTrue(appShell.contains("NovelProjectListView("))
        XCTAssertTrue(appShell.contains("NovelProjectWorkspaceView("))

        let miniApps = try XCTUnwrap(home.range(of: "title: \"小应用\""))
        let novel = try XCTUnwrap(home.range(of: "title: \"小说创作\""))
        let webMount = try XCTUnwrap(home.range(of: "title: \"WebMount\""))
        XCTAssertLessThan(miniApps.lowerBound, novel.lowerBound)
        XCTAssertLessThan(novel.lowerBound, webMount.lowerBound)
        XCTAssertTrue(home.contains("route: .novelCreation"))
        XCTAssertTrue(home.contains("title: \"核心记忆\""))
        XCTAssertTrue(home.contains("route: .memory"))
    }

    func testNovelDiscussionSharesTheChatSearchRuntime() throws {
        let appShell = try source("iosApp/AppShell.swift")
        let composition = try source("iosApp/NovelCreation/NovelCreationComposition.swift")

        XCTAssertTrue(appShell.contains("toolRuntime: backgroundToolRuntime"))
        XCTAssertTrue(composition.contains("toolRuntime: ChatToolRuntime?"))
        XCTAssertTrue(composition.contains("toolRuntime: toolRuntime"))
    }

    func testBackgroundLeaseWaitsForExpirationBeforeInterrupting() async {
        var expirationHandler: (@Sendable () -> Void)?
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        var interruptionCount = 0
        let taskID = UIBackgroundTaskIdentifier(rawValue: 41)
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginBackgroundTask: { _, handler in
                expirationHandler = handler
                return taskID
            },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )

        await Task.yield()
        XCTAssertEqual(interruptionCount, 0)
        XCTAssertTrue(endedTaskIDs.isEmpty)

        expirationHandler?()
        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)

        XCTAssertEqual(interruptionCount, 1)
        XCTAssertEqual(endedTaskIDs, [taskID])
        await completionGate.open()
    }

    func testExpirationEndsTaskAfterDurableInterruptionFinishes() async {
        var expirationHandler: (@Sendable () -> Void)?
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        let taskID = UIBackgroundTaskIdentifier(rawValue: 42)
        let gate = NovelWorkspaceLifecycleTestGate()
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginBackgroundTask: { _, handler in
                expirationHandler = handler
                return taskID
            },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in await gate.wait() }
        )
        expirationHandler?()
        let didStartWaiting = await eventually { await gate.hasWaiter }
        XCTAssertTrue(didStartWaiting)
        XCTAssertTrue(endedTaskIDs.isEmpty)
        await gate.open()
        let didEnd = await eventually { endedTaskIDs.count == 1 }

        XCTAssertTrue(didEnd)
        XCTAssertEqual(endedTaskIDs, [taskID])
        await completionGate.open()
    }

    func testReturningForegroundEndsLeaseWithoutInterruptingGeneration() async {
        var endedTaskIDs: [UIBackgroundTaskIdentifier] = []
        var interruptionCount = 0
        let taskID = UIBackgroundTaskIdentifier(rawValue: 43)
        let completionGate = NovelWorkspaceLifecycleTestGate()
        let coordinator = NovelWorkspaceLifecycleCoordinator(
            beginBackgroundTask: { _, _ in taskID },
            endBackgroundTask: { endedTaskIDs.append($0) }
        )

        coordinator.enterBackground(
            waitForCompletion: { await completionGate.wait() },
            interrupt: { _ in interruptionCount += 1 }
        )
        coordinator.enterForeground()

        let didEnd = await eventually { endedTaskIDs.count == 1 }
        XCTAssertTrue(didEnd)
        XCTAssertEqual(endedTaskIDs, [taskID])
        XCTAssertEqual(interruptionCount, 0)
        await completionGate.open()
    }

    func testWorkspaceExitDetachesConsumerWhileAppOwnsBackgroundLease() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")
        let appShell = try source("iosApp/AppShell.swift")

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
    }

    func testNovelCreationUsesNativePushNavigationAndChatKeyboardDismissal() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(workspace.contains(".navigationDestination(item: $chapterReaderRoute)"))
        XCTAssertFalse(workspace.contains(".fullScreenCover(item: $chapterReaderRoute)"))
        XCTAssertFalse(workspace.contains(".navigationBarBackButtonHidden(true)"))
        XCTAssertTrue(workspace.contains("case .manuscript:"))
        XCTAssertTrue(reader.contains(".toolbar { readerToolbar }"))
        XCTAssertTrue(reader.contains(".safeAreaInset(edge: .bottom"))
        XCTAssertTrue(reader.contains("ComposerDockCircleGlass(tint: nil)"))
        XCTAssertTrue(reader.contains(".buttonBorderShape(.circle)"))
        XCTAssertTrue(reader.contains(".sharedBackgroundVisibility(.hidden)"))
        XCTAssertFalse(reader.contains(".frame(maxWidth: .infinity, alignment: .trailing)"))
        XCTAssertEqual(
            reader.components(separatedBy: "Text(currentChapterTitle)").count - 1,
            1,
            "The reader should not repeat the derived chapter title above manuscript content."
        )
        XCTAssertTrue(reader.contains("Text(currentChapterWordCountTitle)"))
        XCTAssertTrue(reader.contains("AmberGlassGroup(spacing: 24)"))
        XCTAssertGreaterThanOrEqual(
            reader.components(separatedBy: ".amberGlass(cornerRadius: 20)").count - 1,
            2
        )
        XCTAssertFalse(reader.contains(".frame(maxWidth: 360)"))
        XCTAssertFalse(reader.contains("Divider()\n                .frame(height: 20)"))
        XCTAssertFalse(reader.contains(".navigationBarBackButtonHidden(true)"))
        XCTAssertTrue(session.contains("private func dismissKeyboard()"))
        XCTAssertGreaterThanOrEqual(session.components(separatedBy: "dismissKeyboard()").count - 1, 3)
        XCTAssertTrue(session.contains("#selector(UIResponder.resignFirstResponder)"))
    }

    func testWorkspaceTabsUseOneNativeLiquidGlassNavigationPlane() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")

        XCTAssertTrue(workspace.contains("NovelWorkspaceGlassTabBar("))
        XCTAssertTrue(workspace.contains("ForEach(NovelWorkspaceSection.allCases)"))
        XCTAssertTrue(workspace.contains(".safeAreaBar(edge: .top, spacing: 0)"))
        XCTAssertTrue(workspace.contains(".glassEffect(.regular.interactive(), in: Capsule())"))
        XCTAssertTrue(workspace.contains(".background(.ultraThinMaterial, in: Capsule())"))
        XCTAssertTrue(workspace.contains("id: \"selected-workspace-section\""))
        XCTAssertTrue(workspace.contains(".black.opacity(0.065)"))
        XCTAssertTrue(workspace.contains(".stroke(selectionStroke, lineWidth: 0.5)"))
        XCTAssertTrue(workspace.contains(".accessibilityAddTraits(isSelected ? .isSelected : [])"))
        XCTAssertTrue(workspace.contains(".frame(height: 44)"))
        XCTAssertFalse(workspace.contains(".frame(minHeight: 44)"))
        XCTAssertTrue(workspace.contains(
            ".animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: selection)"
        ))
        XCTAssertFalse(workspace.contains("withAnimation(reduceMotion ? nil"))
        XCTAssertFalse(workspace.contains("Picker(\"小说工作区\""))
        XCTAssertFalse(workspace.contains(".pickerStyle(.segmented)"))
        XCTAssertTrue(compendium.contains(".safeAreaInset(edge: .top, spacing: 0)"))
        XCTAssertFalse(compendium.contains("VStack(spacing: 0) {\n            Picker(\"设定分类\""))
        XCTAssertEqual(
            workspace.components(separatedBy: ".glassEffect(").count - 1,
            1,
            "The workspace tabs should be one glass navigation plane, not stacked glass surfaces."
        )
    }

    func testManuscriptTabIsAReaderDirectoryWithoutInternalWorkflowStats() throws {
        let chapters = try source("iosApp/NovelCreation/NovelChapterViews.swift")

        XCTAssertTrue(chapters.contains("Text(\"目录\")"))
        XCTAssertTrue(chapters.contains("onOpenChapter(selection)"))
        XCTAssertTrue(chapters.contains("NovelPresentation.chapterDisplayTitle("))
        XCTAssertTrue(chapters.contains("ScrollView"))
        XCTAssertTrue(chapters.contains(".stroke(AmberTheme.borderSoft"))
        XCTAssertFalse(chapters.contains("List {"))
        XCTAssertFalse(chapters.contains("chevron.right"))
        XCTAssertFalse(chapters.contains("statusSection"))
        XCTAssertFalse(chapters.contains("sessionSection"))
        XCTAssertFalse(chapters.contains("quickStartControl"))
        XCTAssertFalse(chapters.contains("创作记录"))
        XCTAssertFalse(chapters.contains("存档点"))
        XCTAssertFalse(chapters.contains("已同步"))
        XCTAssertFalse(chapters.contains("章节只显示当前分支存档点"))
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
        XCTAssertTrue(projectList.contains("struct NovelProjectRenameSheet"))
        XCTAssertTrue(projectList.contains(".presentationSizing(.fitted)"))
        XCTAssertFalse(projectList.contains(".presentationDetents([.height(220)])"))
        XCTAssertTrue(branches.contains("struct NovelBranchRenameSheet"))
        XCTAssertTrue(branches.contains(".presentationSizing(.fitted)"))
        XCTAssertFalse(branches.contains(".presentationDetents([.height(220)])"))
    }

    func testWritingContextUsesATickedNativeBudgetSlider() throws {
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")

        XCTAssertTrue(sheets.contains("value: budgetSliderValue"))
        XCTAssertTrue(sheets.contains("step: 2_000"))
        XCTAssertTrue(sheets.contains("Self.budgetTick(for: value)"))
        XCTAssertTrue(sheets.contains("SliderTick(value)"))
        XCTAssertFalse(sheets.contains("Stepper(value: $budgetTokens"))
    }

    func testChapterEditorExposesNativeFindAndReplace() throws {
        let reader = try source("iosApp/NovelCreation/NovelChapterReaderView.swift")

        XCTAssertTrue(reader.contains("@State private var isFindPresented = false"))
        XCTAssertTrue(reader.contains(".findNavigator(isPresented: $isFindPresented)"))
        XCTAssertTrue(reader.contains("Image(systemName: \"magnifyingglass\")"))
        XCTAssertTrue(reader.contains(".accessibilityLabel(\"查找和替换\")"))
    }

    func testNovelComposerRevealsFocusedControlsWithoutPermanentSegmentedChrome() throws {
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(session.contains("if showsComposerMeta"))
        XCTAssertTrue(session.contains("private var composerMetaControls"))
        XCTAssertTrue(session.contains("currentComposerIntent.title"))
        XCTAssertTrue(session.contains("onOpenModel"))
        XCTAssertTrue(session.contains("ContextRingButton("))
        XCTAssertTrue(session.contains("ComposerContextPanel("))
        XCTAssertTrue(session.contains("novelInjection: contextPanelModel"))
        XCTAssertTrue(session.contains(".presentationCompactAdaptation(.popover)"))
        XCTAssertTrue(session.contains("private var contextRingSnapshot: ChatContextSnapshot"))
        XCTAssertFalse(session.contains("Image(systemName: \"scope\")"))
        XCTAssertFalse(session.contains("Image(systemName: \"chevron.down\")"))
        XCTAssertTrue(session.contains("Picker(\"创作方式\", selection: composerIntentBinding)"))
        XCTAssertTrue(session.contains("Text(intent.title).tag(intent)"))
        XCTAssertTrue(session.contains("Menu {"))
        XCTAssertFalse(session.contains("NovelComposerIntentPanel"))
        XCTAssertFalse(session.contains("Label(intent.title, systemImage: \"checkmark\")"))
        XCTAssertFalse(session.contains("private var sessionControls"))
        XCTAssertFalse(session.contains("Image(systemName: \"ellipsis\")"))
        XCTAssertFalse(session.contains(".pickerStyle(.segmented)"))

        let spacer = try XCTUnwrap(session.range(of: "Spacer(minLength: 0)"))
        let intent = try XCTUnwrap(session.range(of: "Picker(\"创作方式\", selection: composerIntentBinding)"))
        let ring = try XCTUnwrap(session.range(of: "ContextRingButton("))
        XCTAssertLessThan(spacer.lowerBound, intent.lowerBound)
        XCTAssertLessThan(intent.lowerBound, ring.lowerBound)
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
        let sheets = try source("iosApp/NovelCreation/NovelSessionSheets.swift")
        let bubble = try source("iosApp/NovelCreation/NovelSessionBubble.swift")

        XCTAssertTrue(session.contains("Button(\"归档当前讨论\""))
        XCTAssertTrue(session.contains("onArchiveDiscussion"))
        XCTAssertTrue(session.contains("viewModel.needsSync"))
        XCTAssertTrue(workspace.contains("case .discussionArchiveOffer"))
        XCTAssertTrue(workspace.contains("case .discussionArchive("))
        XCTAssertTrue(workspace.contains("== .wholeChapter"))
        XCTAssertTrue(workspace.contains("!sessionViewModel.needsSync && !sessionViewModel.isBusy"))
        XCTAssertTrue(workspace.contains("NovelDiscussionArchiveSheet"))
        XCTAssertTrue(sheets.contains("struct NovelDiscussionArchiveSheet"))
        XCTAssertTrue(sheets.contains("let isReady: Bool"))
        XCTAssertTrue(sheets.contains("剧情状态同步完成后可归档"))
        XCTAssertTrue(sheets.contains("TextField(\"决定主题\""))
        XCTAssertTrue(sheets.contains("Button(role: .destructive)"))
        XCTAssertTrue(bubble.contains("canCollect ? \"生成已中断 · 可收录已生成部分\" : \"生成已中断\""))
        XCTAssertFalse(workspace.contains("Timer.publish"))
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
        XCTAssertTrue(sheets.contains("Picker(\"创作设置\", selection: $selectedTab)"))
        XCTAssertTrue(sheets.contains("case .preferences: \"写作偏好\""))
        XCTAssertTrue(sheets.contains("case .context: \"上下文注入\""))
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
        XCTAssertTrue(settings.contains("NovelProjectManagementView("))
        XCTAssertTrue(settings.contains("优先选择擅长长文与创意写作的模型"))
        XCTAssertTrue(settings.contains("优先选择稳定、便宜、结构化输出可靠的模型"))
        XCTAssertTrue(projectSettings.contains("viewModel.setModelPolicy(policy, for: purpose)"))
        XCTAssertTrue(projectSettings.contains("Label(\"导出正文\", systemImage: \"square.and.arrow.up\")"))
    }

    func testNovelProjectListOwnsImportCreateAndSettingsActions() throws {
        let shell = try source("iosApp/AppShell.swift")
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")

        XCTAssertTrue(shell.contains("onOpenSettings:"))
        XCTAssertTrue(shell.contains("router.navigate(to: .novelCreationSettings)"))
        XCTAssertFalse(shell.contains(".navigationTransition(.zoom("))
        XCTAssertFalse(shell.contains("NovelSettingsTransitionSource"))
        XCTAssertTrue(list.contains("accessibilityLabel(\"导入小说项目\")"))
        XCTAssertTrue(list.contains("accessibilityLabel(\"小说创作设置\")"))
        XCTAssertTrue(list.contains("ToolbarItem(id: NovelCreationToolbarID.settings"))
        XCTAssertTrue(list.contains("NovelCreationSettingsToolbarButton("))
        XCTAssertTrue(list.contains("ToolbarSpacer(.fixed, placement: .topBarTrailing)"))
        XCTAssertFalse(list.contains("ToolbarItemGroup(placement: .topBarTrailing)"))
        XCTAssertFalse(list.contains(".matchedTransitionSource("))
        XCTAssertTrue(list.contains("private var createProjectAction"))
        XCTAssertTrue(list.contains("Label(\"新建\", systemImage: \"plus\")"))
        XCTAssertTrue(list.contains(".buttonStyle(.glassProminent)"))
    }

    func testNovelProjectListUsesAQuietInsetRowAndIMECommittedRename() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")

        XCTAssertFalse(list.contains("Image(systemName: \"chevron.right\")"))
        XCTAssertTrue(list.contains("leading: 22, bottom: 6, trailing: 22"))
        XCTAssertTrue(list.contains(".font(.system(size: 18, weight: .semibold))"))
        XCTAssertTrue(list.contains(".frame(width: 36, height: 36)"))
        XCTAssertTrue(list.contains("private func commitNameAndSave()"))
        XCTAssertTrue(list.contains("#selector(UIResponder.resignFirstResponder)"))
        XCTAssertTrue(list.contains("DispatchQueue.main.async"))
    }

    func testProjectListStartsNativePushBeforeLoadingTheWorkspace() throws {
        let list = try source("iosApp/NovelCreation/NovelProjectListView.swift")
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")

        XCTAssertTrue(list.contains("onOpen(project.id)"))
        XCTAssertFalse(list.contains("NovelProjectOpenCoordinator"))
        XCTAssertFalse(list.contains("await viewModel.selectProject(projectID)"))
        XCTAssertFalse(list.contains("pendingOpenProjectID"))
        XCTAssertFalse(list.contains(".task(id: pendingOpenProjectID)"))
        XCTAssertTrue(workspace.contains("hasCompletedInitialNavigation && hasLoadedRoutedProject"))
        let appearance = try XCTUnwrap(workspace.range(of: "NovelNavigationDidAppearObserver"))
        let deferredTask = try XCTUnwrap(workspace.range(of: ".task(id: hasCompletedInitialNavigation)"))
        let selection = try XCTUnwrap(workspace.range(
            of: "await viewModel.selectProject(projectID)",
            range: deferredTask.upperBound..<workspace.endIndex
        ))
        XCTAssertLessThan(appearance.lowerBound, deferredTask.lowerBound)
        XCTAssertLessThan(deferredTask.lowerBound, selection.lowerBound)
        XCTAssertFalse(workspace.contains(".task(id: projectID)"))
    }

    func testSessionBodyBuildsOneProjectionAndPassesItDown() throws {
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")
        let bodyStart = try XCTUnwrap(session.range(of: "var body: some View"))
        let bindingTaskStart = try XCTUnwrap(session.range(
            of: ".task(id: bindingTaskID)",
            range: bodyStart.upperBound..<session.endIndex
        ))
        let body = String(session[bodyStart.lowerBound..<bindingTaskStart.lowerBound])

        XCTAssertEqual(body.components(separatedBy: "projectedListModel()").count - 1, 1)
        XCTAssertTrue(body.contains("transcript(listModel: listModel, listSignal: listSignal)"))
        XCTAssertTrue(body.contains("composer(listModel: listModel)"))
        XCTAssertEqual(session.components(separatedBy: ".task(id: bindingTaskID)").count - 1, 1)
        XCTAssertFalse(session.contains("selectionTaskID"))
        XCTAssertFalse(session.contains("runningRunTaskID"))
        XCTAssertTrue(session.contains(
            "@State private var followState = NovelSessionBottomFollowState()"
        ))
        XCTAssertTrue(session.contains(
            "@AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true"
        ))
        XCTAssertTrue(session.contains("followEnabled: followGeneration"))
        // 2026-07-26 撤锚更正:`.defaultScrollAnchor(.bottom, for: .sizeChanges)` 曾
        // 被当作流式底部锚点的唯一所有者,但真机录屏坐实生产 ParagraphUIView 的异步
        // 增量 TextKit 布局路径下它不能同步吸收增长(−390px 结构性跳变)。生产已撤锚,
        // 增长所有权交回 measured-geometry 回调,不得回归 sizeChanges 双写。
        // 判据只看**真实挂载**,不看注释:撤锚的依据(真机 −390px 实测、Chat 893pt
        // 先例、旧探针口径为何无效)写在源码注释里是有价值的历史记录,不能因为
        // 测试用裸 contains 匹配就把它删掉——那是让证据迁就判据。
        let uncommented = session
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { line -> Substring in
                guard let commentStart = line.range(of: "//") else { return line }
                return line[line.startIndex..<commentStart.lowerBound]
            }
            .joined(separator: "\n")
        XCTAssertFalse(uncommented.contains(
            ".defaultScrollAnchor(.bottom, for: .sizeChanges)"
        ))
        XCTAssertFalse(uncommented.contains("NovelSessionSizeChangesPinModifier"))
        XCTAssertFalse(session.contains("withAnimation(.linear(duration: 0.08))"))
        let bindingTaskEnd = try XCTUnwrap(session.range(
            of: ".onChange(of: listSignal)",
            range: bindingTaskStart.upperBound..<session.endIndex
        ))
        let bindingTask = String(session[bindingTaskStart.lowerBound..<bindingTaskEnd.lowerBound])
        XCTAssertFalse(bindingTask.contains("dispatchFollowEvent"))
        XCTAssertFalse(session.contains("private var listModel:"))
    }

    /// 2026-07-26 撤锚更正的成败关键(见 PROJECT_STATE 2026-07-21 记录的 53pt
    /// 「先欠账、再用 0.08s 动画追回」回归归因):恢复 measured-geometry 写者后,
    /// `.followBottom(animated: false)` 必须走无动画路径,不能被
    /// `startExplicitBottomAnimation()` 的 0.2s easeOut 包住——那个动画只保留给
    /// `animated: true`(用户主动点击「回到底部」的 `.explicitBottomRequested`)。
    func testFollowBottomCommandRoutesUnanimatedCasesAwayFromExplicitAnimation() throws {
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        guard let commandStart = session.range(of: "private func executeFollowCommand("),
              let commandEnd = session.range(
                of: "private func startExplicitBottomAnimation()",
                range: commandStart.upperBound..<session.endIndex
              ) else {
            return XCTFail("Expected executeFollowCommand body")
        }
        let commandBody = String(session[commandStart.lowerBound..<commandEnd.lowerBound])

        XCTAssertTrue(commandBody.contains("case .followBottom(let animated):"))

        // 用逐行去缩进比较,避免测试文件自身的多行字符串缩进和生产代码缩进
        // 不一致导致假阴性——这里只关心语句顺序与结构,不关心具体缩进层级。
        func normalizedLines(_ text: String) -> [String] {
            text.components(separatedBy: "\n")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
        }
        let normalizedCommandBody = normalizedLines(commandBody)
        let expectedUnanimatedRouting = normalizedLines(
            """
            if animated {
            startExplicitBottomAnimation()
            } else if explicitBottomAnimationTask != nil {
            explicitBottomFollowPending = true
            } else {
            performLiveBottomFollow()
            }
            """
        )
        var matchedUnanimatedRouting = false
        if normalizedCommandBody.count >= expectedUnanimatedRouting.count {
            for startIndex in 0...(normalizedCommandBody.count - expectedUnanimatedRouting.count) {
                let slice = Array(normalizedCommandBody[startIndex..<(startIndex + expectedUnanimatedRouting.count)])
                if slice == expectedUnanimatedRouting {
                    matchedUnanimatedRouting = true
                    break
                }
            }
        }
        XCTAssertTrue(
            matchedUnanimatedRouting,
            "animated:false 必须走 performLiveBottomFollow(),不能与 animated:true 共用 startExplicitBottomAnimation()"
        )

        guard let performStart = session.range(of: "private func performLiveBottomFollow()"),
              let performEnd = session.range(
                of: "private func scrollToBottomWithoutAnimation()",
                range: performStart.upperBound..<session.endIndex
              ) else {
            return XCTFail("Expected performLiveBottomFollow body")
        }
        let performBody = String(session[performStart.lowerBound..<performEnd.lowerBound])
        XCTAssertFalse(
            performBody.contains("withAnimation"),
            "measured-growth 跟随的执行路径不得再包一层动画,否则 53pt 老毛病会复发"
        )

        guard let scrollWithoutAnimationStart = session.range(
            of: "private func scrollToBottomWithoutAnimation()"
        ),
        let scrollWithoutAnimationEnd = session.range(
            of: "private var isNativeScrollDriverDesired:",
            range: scrollWithoutAnimationStart.upperBound..<session.endIndex
        ) else {
            return XCTFail("Expected scrollToBottomWithoutAnimation body")
        }
        let scrollWithoutAnimationBody = String(
            session[scrollWithoutAnimationStart.lowerBound..<scrollWithoutAnimationEnd.lowerBound]
        )
        XCTAssertTrue(scrollWithoutAnimationBody.contains("transaction.animation = nil"))
        XCTAssertFalse(scrollWithoutAnimationBody.contains("withAnimation"))
    }

    func testColdProjectPushDefersRecoverySyncAndBoundsHistoryWork() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")
        let sessionViewModel = try source("iosApp/NovelCreation/NovelSessionViewModel.swift")

        XCTAssertTrue(workspace.contains("NovelNavigationDidAppearObserver"))
        XCTAssertTrue(workspace.contains("hasCompletedInitialNavigation && hasLoadedRoutedProject"))
        XCTAssertTrue(workspace.contains(".task(id: hasCompletedInitialNavigation)"))
        XCTAssertTrue(workspace.contains("await viewModel.selectProject(projectID)"))
        XCTAssertFalse(workspace.contains(".task(id: projectID)"))
        XCTAssertFalse(workspace.contains("sessionViewModel.bindToCurrentSelection()"))
        XCTAssertTrue(workspace.contains("viewModel.scheduleAutomaticStateSyncIfNeeded()"))
        XCTAssertFalse(sessionViewModel.contains("workspace.scheduleAutomaticStateSyncIfNeeded()"))
        XCTAssertTrue(session.contains("NovelSessionHistoryWindowPolicy.initialLimit"))
        XCTAssertTrue(session.contains("visibleHistoricalRows"))
        XCTAssertTrue(session.contains("更早的创作记录"))
        // 2026-07-25 按证据更新:投影缓存命中判据由 `projectionCache?.key == key`
        // 重构为 `if let cached = projectionCache, cached.key == key`(多了流式尾行
        // 快路径)。两者的命中语义完全相同——仍然只在 key 匹配时复用缓存,否则走
        // 完整重建。本 canary 守护的契约未变,只是字面量过时,故更新字面量而非放宽断言。
        XCTAssertTrue(sessionViewModel.contains("cached.key == key"))
        XCTAssertTrue(sessionViewModel.contains("func projectedListModel("))
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

    func testStateSyncActivityIsProjectScopedAndScheduledSyncKeepsBackgroundLease() throws {
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(viewModel.contains("let projectID: NovelProjectID"))
        XCTAssertTrue(viewModel.contains("let branchID: NovelBranchID"))
        XCTAssertTrue(session.contains("activity.projectID == workspace.selectedProjectID"))
        XCTAssertTrue(session.contains("activity.branchID == workspace.selectedBranchID"))
        let backgroundCheck = try XCTUnwrap(viewModel.range(
            of: "private func hasActiveBackgroundGeneration()"
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

    func testNovelWorkspaceDoesNotRepeatRoutineStatusAboveAndInsideConversation() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let bubble = try source("iosApp/NovelCreation/NovelSessionBubble.swift")
        let session = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertFalse(workspace.contains("pendingDescription("))
        XCTAssertTrue(bubble.contains("已收录为正式正文"))
        XCTAssertFalse(bubble.contains("正文与剧情状态已更新"))
        XCTAssertFalse(bubble.contains("committedChangeView("))
        XCTAssertTrue(session.contains("pending.lastError"))
        XCTAssertTrue(session.contains("!viewModel.isBusy"))
    }

    /// 用户反馈:已收录正文行的操作区(撤销收录/从这里 Fork 那一片)在同步成功后
    /// 什么标识都不留——「剧情状态同步未完成」只是按钮禁用原因,消失后原地空白。
    /// 断言接线:同一 actionBar 位置,synchronized 时补一条常驻成功标识;
    /// needsSync 时既有的 blocker 提示文案原样保留,零回归。
    func testCommittedRowActionBarShowsPersistentSyncSuccessIndicatorAlongsideForkAndUndo() throws {
        let bubble = try source("iosApp/NovelCreation/NovelSessionBubble.swift")
        let presentation = try source("iosApp/NovelCreation/NovelSessionPresentation.swift")

        // 既有的「同步未完成」按钮禁用提示保持原样,零回归。
        XCTAssertTrue(bubble.contains("case .branchNeedsSync: \"剧情状态同步未完成\""))

        let actionBarStart = try XCTUnwrap(bubble.range(of: "private var actionBar: some View"))
        let actionBarEnd = try XCTUnwrap(bubble.range(
            of: "\n    @ViewBuilder",
            range: actionBarStart.upperBound..<bubble.endIndex
        ))
        let actionBarBody = bubble[actionBarStart.lowerBound..<actionBarEnd.lowerBound]
        XCTAssertTrue(actionBarBody.contains("actions.compactMap(\\.blocker).first"))
        XCTAssertTrue(actionBarBody.contains("committedChange?.branchSyncStatus == .synchronized"))
        XCTAssertTrue(actionBarBody.contains("剧情状态已同步"))

        // 投影层承载持久化的分支同步事实,而不是渲染层自造状态。
        XCTAssertTrue(presentation.contains("let branchSyncStatus: NovelBranchSyncStatus"))
        XCTAssertTrue(presentation.contains(
            "branchSyncStatus: input.branch.syncStatus"
        ))
    }

    func testQuickStartRegenerationEntryIsReachableFromTheLiveCompendiumView() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")
        let viewModel = try source("iosApp/NovelCreation/NovelCreationViewModel.swift")

        // 链条第一环: 设定页 .compendium 分支必须路由到 NovelCompendiumView(活视图),
        // 而不是死代码文件 NovelMaterialsView。
        let compendiumCase = try XCTUnwrap(workspace.range(of: "case .compendium:"))
        let compendiumCaseEnd = try XCTUnwrap(workspace.range(
            of: "\n        }",
            range: compendiumCase.upperBound..<workspace.endIndex
        ))
        let compendiumRouting = workspace[compendiumCase.lowerBound..<compendiumCaseEnd.lowerBound]
        XCTAssertTrue(compendiumRouting.contains("NovelCompendiumView("))

        // 链条第二环: 入口本体活在 NovelCompendiumView.swift 里,不是 NovelMaterialsView.swift。
        let sectionStart = try XCTUnwrap(compendium.range(of: "private var quickStartRegenerationSection"))
        let sectionEnd = try XCTUnwrap(compendium.range(
            of: "private var otherMaterials",
            range: sectionStart.upperBound..<compendium.endIndex
        ))
        let section = compendium[sectionStart.lowerBound..<sectionEnd.lowerBound]
        XCTAssertTrue(section.contains("viewModel.projectSnapshot?.project.creationMode == .quickStart"))
        XCTAssertTrue(section.contains("重新生成设定建议"))
        XCTAssertTrue(section.contains("!viewModel.canMutate || viewModel.branchSnapshot?.branch.activeRunID != nil"))

        // 链条第三环: 入口实际挂载到「更多」子分段(NovelCompendiumMoreView)的 List 内,
        // 是常驻 Section,不依赖 proposals 是否为空。
        let moreViewStart = try XCTUnwrap(compendium.range(of: "private struct NovelCompendiumMoreView"))
        let moreViewEnd = try XCTUnwrap(compendium.range(
            of: "\nstruct NovelQuickStartRegenerationSheet",
            range: moreViewStart.upperBound..<compendium.endIndex
        ))
        let moreView = compendium[moreViewStart.lowerBound..<moreViewEnd.lowerBound]
        XCTAssertTrue(moreView.contains("quickStartRegenerationSection"))
        XCTAssertTrue(moreView.contains("isPresentingQuickStartRegeneration"))
        XCTAssertTrue(moreView.contains("NovelQuickStartRegenerationSheet(viewModel: viewModel)"))

        XCTAssertTrue(compendium.contains("struct NovelQuickStartRegenerationSheet"))
        XCTAssertTrue(compendium.contains("await viewModel.startQuickStartSuggestions(guidance: guidance)"))
        XCTAssertTrue(compendium.contains("TextEditor(text: $guidance)"))

        // 2026-07-25 按证据更新:签名新增 `exactUserText`(见下方重试契约),原先锁整行
        // 字面量的断言随之过时。改为断言构成契约的各个要素,避免再被无关的格式变化绊倒。
        XCTAssertTrue(viewModel.contains("func startQuickStartSuggestions("))
        XCTAssertTrue(viewModel.contains("guidance: String? = nil"))
        XCTAssertTrue(viewModel.contains("exactUserText: String? = nil"))
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

    func testDeadNovelMaterialsViewNoLongerCarriesTheQuickStartRegenerationEntry() throws {
        // 反向断言: 上一轮误放进死代码文件的入口已完全清理,防止将来又在
        // 不可达的 NovelMaterialsView 里重新长出一份重复入口。
        let materials = try source("iosApp/NovelCreation/NovelMaterialsView.swift")

        XCTAssertTrue(materials.contains("该视图当前无生产调用点(设定页实际使用 NovelCompendiumView)"))
        XCTAssertFalse(materials.contains("quickStartRegenerationSection"))
        XCTAssertFalse(materials.contains("isPresentingQuickStartRegeneration"))
        XCTAssertFalse(materials.contains("struct NovelQuickStartRegenerationSheet"))
    }

    func testWorkspaceSectionSwitchDefersHeavyMountAndCrossfades() throws {
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")
        let appShell = try source("iosApp/AppShell.swift")

        // 该工作区确实是生产路由(避免只断言死代码里的接线)。
        XCTAssertTrue(appShell.contains("NovelProjectWorkspaceView("))

        // 重内容挂载被门控在 mountedSection 上,且只在推迟一拍之后才置位。
        XCTAssertTrue(workspace.contains("@State private var mountedSection: NovelWorkspaceSection?"))
        XCTAssertTrue(workspace.contains("mountedSection == section ? section : nil"))
        XCTAssertTrue(workspace.contains(".task(id: section)"))
        XCTAssertTrue(workspace.contains("await Task.yield()"))
        XCTAssertTrue(workspace.contains("mountedSection = section"))
        // 未就绪时渲染的是占位而不是 sectionContent。
        XCTAssertTrue(workspace.contains("sectionContent(mounted)"))
        XCTAssertTrue(workspace.contains("private func sectionContent(_ section: NovelWorkspaceSection)"))
        // 转场存在且受 reduceMotion 门控。
        XCTAssertTrue(workspace.contains("@Environment(\\.accessibilityReduceMotion)"))
        XCTAssertTrue(
            workspace.contains(".animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: mounted)")
        )
    }

    func testCompendiumSubsectionSwitchCrossfades() throws {
        let compendium = try source("iosApp/NovelCreation/NovelCompendiumView.swift")
        let workspace = try source("iosApp/NovelCreation/NovelProjectWorkspaceView.swift")

        // 设定页确实由 .compendium 路由到该视图。
        XCTAssertTrue(workspace.contains("case .compendium:"))
        XCTAssertTrue(workspace.contains("NovelCompendiumView("))

        XCTAssertTrue(compendium.contains("@Environment(\\.accessibilityReduceMotion)"))
        XCTAssertTrue(compendium.contains(".transition(.opacity)"))
        XCTAssertTrue(
            compendium.contains(".animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: selection)")
        )
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
