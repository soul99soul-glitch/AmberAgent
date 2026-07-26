import SwiftUI
import UIKit

struct NovelProjectWorkspaceView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let viewModel: NovelCreationViewModel
    let sharedSettings: IOSSharedSettingsStore
    let projectID: NovelProjectID

    @State private var sessionViewModel: NovelSessionViewModel
    @State private var section: NovelWorkspaceSection = .creation
    @State private var mountedSection: NovelWorkspaceSection?
    @State private var compendiumSection: NovelCompendiumSection = .characters
    @State private var activeSheet: NovelWorkspaceSheet?
    @State private var chapterReaderRoute: NovelChapterReaderRoute?
    @State private var sessionInputText = ""
    @State private var sessionInjectionOverrides = NovelInjectionOverrides.none
    @State private var sessionInputBudgetTokens = 16_000
    @State private var isConfirmingPreviousRestore = false
    @State private var branchNotice: String?
    @State private var hasCompletedInitialNavigation = false
    @State private var routedProjectLoadFailure: String?

    init(
        viewModel: NovelCreationViewModel,
        sharedSettings: IOSSharedSettingsStore,
        projectID: NovelProjectID
    ) {
        self.viewModel = viewModel
        self.sharedSettings = sharedSettings
        self.projectID = projectID
        self._sessionViewModel = State(initialValue: NovelSessionViewModel(workspace: viewModel))
    }

    var body: some View {
        VStack(spacing: 0) {
            if hasCompletedInitialNavigation && hasLoadedRoutedProject {
                VStack(spacing: 0) {
                    accessBanner
                    content
                }
                .safeAreaBar(edge: .top, spacing: 0) {
                    sectionPicker
                }
            } else if let routedProjectLoadFailure {
                VStack(spacing: 16) {
                    ContentUnavailableView(
                        "无法读取小说项目",
                        systemImage: "exclamationmark.triangle",
                        description: Text(routedProjectLoadFailure)
                    )
                    Button("重新读取") {
                        Task { @MainActor in await loadRoutedProject() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.isProjectSelectionBlocked)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ProgressView("正在读取小说项目")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(AmberTheme.background.ignoresSafeArea())
        .background {
            NovelNavigationDidAppearObserver {
                guard !hasCompletedInitialNavigation else { return }
                hasCompletedInitialNavigation = true
            }
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { workspaceToolbar }
        .sheet(item: $activeSheet, content: sheetContent)
        .navigationDestination(item: $chapterReaderRoute) { route in
            NovelChapterReaderView(
                viewModel: viewModel,
                sessionViewModel: sessionViewModel,
                initialSelection: route.selection
            ) {
                section = .creation
            }
        }
        .confirmationDialog(
            "恢复上一个有效版本？",
            isPresented: $isConfirmingPreviousRestore,
            titleVisibility: .visible
        ) {
            Button("恢复并继续编辑") {
                Task { @MainActor in
                    await viewModel.restorePreviousProject()
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("当前损坏的主文件会被保留用于排查，上一个有效版本将成为新的可写版本。")
        }
        .task(id: hasCompletedInitialNavigation) {
            guard hasCompletedInitialNavigation else { return }
            await loadRoutedProject()
        }
        .overlay(alignment: .top) {
            if let branchNotice {
                Text(branchNotice)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .amberGlass(cornerRadius: AmberTheme.radiusMedium, interactive: false)
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .onChange(of: viewModel.branchSnapshot?.branch.id) { _, _ in
            sessionInputText = ""
            sessionInjectionOverrides = .none
            sessionInputBudgetTokens = 16_000
            if hasCompletedInitialNavigation {
                viewModel.scheduleAutomaticStateSyncIfNeeded()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active,
                  hasCompletedInitialNavigation,
                  hasLoadedRoutedProject else { return }
            viewModel.scheduleAutomaticStateSyncIfNeeded()
        }
        .onDisappear {
            sessionViewModel.detachConsumer()
        }
    }

    @ToolbarContentBuilder
    private var workspaceToolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Button {
                activeSheet = .writingContext
            } label: {
                VStack(spacing: 1) {
                    HStack(spacing: 4) {
                        Text(projectName)
                            .font(.headline)
                            .foregroundStyle(AmberTheme.foreground)
                            .lineLimit(1)
                        Image(systemName: "chevron.down")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                    }
                    Text(headerSubtitle)
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                }
                .frame(maxWidth: 220)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("写作偏好与上下文")
            .disabled(!hasLoadedRoutedProject || isSessionTransitionBusy)
        }

        ToolbarItem(id: NovelCreationToolbarID.settings, placement: .topBarTrailing) {
            NovelCreationSettingsToolbarButton {
                router.navigate(to: .novelCreationSettings)
            }
        }
    }

    private var sectionPicker: some View {
        NovelWorkspaceGlassTabBar(
            selection: $section,
            title: sectionTitle
        )
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .disabled(isSessionTransitionBusy)
    }

    @ViewBuilder
    private var accessBanner: some View {
        if viewModel.requiresReload {
            HStack(spacing: 12) {
                Label("操作已提交，需要重新载入项目", systemImage: "arrow.clockwise.circle")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button("重新载入") {
                    Task { @MainActor in
                        await viewModel.retryCommittedMutationReload()
                    }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(viewModel.isPerforming)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .background(AmberTheme.accentAmber.opacity(0.10))
        } else if let project = viewModel.projectSnapshot, project.access != .readWrite {
            HStack(spacing: 12) {
                Label("已从上一个有效版本恢复，当前项目只读", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Button {
                    isConfirmingPreviousRestore = true
                } label: {
                    Label("恢复可写", systemImage: "arrow.counterclockwise")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(viewModel.isPerforming)
            }
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(AmberTheme.accentAmber.opacity(0.10))
        }
    }

    // 切 Tab 时先只提交这个轻量占位,重内容推迟一拍再挂载:
    // 点击当帧不做建视图的活,「点下去卡住」的观感由此消除。
    private var content: some View {
        let mounted: NovelWorkspaceSection? = mountedSection == section ? section : nil
        return ZStack {
            if let mounted {
                sectionContent(mounted)
                    .transition(.opacity)
            } else {
                ProgressView()
                    .controlSize(.large)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: mounted)
        .task(id: section) {
            guard mountedSection != section else { return }
            await Task.yield()
            guard !Task.isCancelled else { return }
            mountedSection = section
        }
    }

    @ViewBuilder
    private func sectionContent(_ section: NovelWorkspaceSection) -> some View {
        switch section {
        case .creation:
            NovelSessionView(
                workspace: viewModel,
                viewModel: sessionViewModel,
                sharedSettings: sharedSettings,
                inputText: $sessionInputText,
                injectionOverrides: $sessionInjectionOverrides,
                inputBudgetTokens: $sessionInputBudgetTokens,
                onOpenModel: { activeSheet = .modelPicker(.creation) },
                onOpenCollection: { activeSheet = .collectCandidate($0) },
                onOpenManualRewrite: { activeSheet = .manualRewrite($0) },
                onFork: { activeSheet = .forkCheckpoint($0) },
                onOpenSettingProposals: openSettingProposals,
                onArchiveDiscussion: { activeSheet = .discussionArchive(nil) }
            )
        case .manuscript:
            NovelChapterManagementView(
                viewModel: viewModel,
                onOpenChapter: { chapter in
                    chapterReaderRoute = NovelChapterReaderRoute(selection: chapter)
                }
            )
        case .compendium:
            NovelCompendiumView(
                viewModel: viewModel,
                selection: $compendiumSection,
                onEditMaterial: { material, suggestedKind in
                    activeSheet = .materialEditor(material, suggestedKind)
                },
                onAcceptProposal: { proposal in
                    activeSheet = .proposal(proposal)
                }
            )
        }
    }

    @ViewBuilder
    private func sheetContent(_ sheet: NovelWorkspaceSheet) -> some View {
        switch sheet {
        case .writingContext:
            NovelWritingContextSheet(
                workspace: viewModel,
                mode: sessionViewModel.mode,
                granularity: sessionViewModel.granularity,
                userText: sessionInputText,
                overrides: sessionInjectionOverrides,
                budgetTokens: sessionInputBudgetTokens,
                onEditWritingRequirements: {
                    transition(to: .materialEditor(writingRequirementsMaterial, .writingRequirements))
                },
                onEditPolishPreference: { transition(to: .polishPreference) },
                onApply: { overrides, budget in
                    sessionInjectionOverrides = overrides
                    sessionInputBudgetTokens = budget
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)

        case .modelPicker(let purpose):
            ComposerModelSheet(
                sharedSettings: sharedSettings,
                currentModel: selectedModelID(for: purpose),
                title: purpose.pickerTitle,
                fallbackTitle: "跟随小说默认",
                onFallback: {
                    selectModelPolicy(.global, for: purpose)
                }
            ) { option in
                selectFixedModel(option, for: purpose)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)

        case .materialEditor(let material, let suggestedKind):
            NovelMaterialEditorSheet(
                viewModel: viewModel,
                material: material,
                suggestedKind: suggestedKind
            )

        case .polishPreference:
            NovelPolishPreferenceSheet(viewModel: viewModel)

        case .collectCandidate(let candidateID):
            NovelCollectCandidateSheet(
                paragraphs: sessionViewModel.paragraphs(candidateID: candidateID),
                chapters: chapterOptions,
                regenerationTarget: sessionViewModel
                    .regenerationTargetChapterID(for: candidateID)
                    .flatMap { chapterID in
                        chapterOptions.first { $0.selection.chapterID == chapterID }
                    },
                suggestedGranularity: sessionViewModel.collectionGranularity(
                    for: candidateID
                ),
                onCompleted: { target in
                    // 「归档讨论」的语义是「这一章写完了」。替换已有章节是修订,
                    // 不是新写一章,而且该章首次写成时多半已经归档过一次,
                    // 再弹一次会在同一章下产生第二条归档记录。
                    if case .replaceChapter = target { return }
                    guard sessionViewModel.collectionGranularity(for: candidateID) == .wholeChapter else {
                        return
                    }
                    transition(to: .discussionArchiveOffer(chapterID(for: target)))
                }
            ) { selection, target in
                let succeeded = await sessionViewModel.collectCandidate(
                    candidateID,
                    selection: selection,
                    target: target
                )
                if succeeded { return .completed }
                if sessionViewModel.branchPendingOperations.contains(where: {
                    $0.candidateID == candidateID
                }) {
                    return .pending(message: "旧版收录仍有剧情状态同步任务，请返回后重试。")
                }
                return .failed(
                    message: sessionViewModel.errorMessage ?? "收录没有完成，请检查项目状态后重试。"
                )
            }

        case .manualRewrite(let candidateID):
            NovelManualRewriteCandidateSheet(
                content: sessionViewModel.candidate(id: candidateID)?.content ?? ""
            ) {
                let succeeded = await sessionViewModel.convertPolishCandidateToManualRewrite(candidateID)
                return succeeded
                    ? .completed
                    : .failed(
                        message: sessionViewModel.errorMessage ??
                            "改写没有保存，请检查源章节是否仍为当前版本。"
                    )
            }

        case .forkCheckpoint(let checkpointID):
            if let branch = viewModel.branchSnapshot?.branch {
                NovelSessionForkSheet(
                    viewModel: sessionViewModel,
                    branchName: branch.name,
                    checkpointID: checkpointID,
                    onCreated: showBranchNotice
                )
            }

        case .proposal(let proposal):
            NovelProposalAcceptanceSheet(viewModel: viewModel, proposal: proposal)

        case .discussionArchiveOffer(let chapterID):
            NovelDiscussionArchiveOfferSheet(
                isReady: !sessionViewModel.needsSync && !sessionViewModel.isBusy
            ) {
                transition(to: .discussionArchive(chapterID))
            }

        case .discussionArchive(let chapterID):
            NovelDiscussionArchiveSheet {
                guard let draft = await sessionViewModel.distillDiscussionArchive(
                    chapterID: chapterID
                ) else {
                    return .failed(sessionViewModel.errorMessage ?? "讨论整理失败，请稍后重试。")
                }
                return .ready(draft)
            } onConfirm: { draft, decisions, summary in
                await sessionViewModel.confirmDiscussionArchive(
                    draft,
                    decisions: decisions,
                    summary: summary
                )
            }

        }
    }

    private var projectName: String {
        if viewModel.projectSnapshot?.project.id == projectID,
           let name = viewModel.projectSnapshot?.project.name {
            return name
        }
        return viewModel.projects.first(where: { $0.id == projectID })?.name ?? "小说创作"
    }

    private var hasLoadedRoutedProject: Bool {
        viewModel.selectedProjectID == projectID &&
            viewModel.projectSnapshot?.project.id == projectID &&
            viewModel.branchSnapshot?.projectID == projectID
    }

    private func loadRoutedProject() async {
        routedProjectLoadFailure = nil
        if !hasLoadedRoutedProject {
            guard await viewModel.selectProject(projectID) else {
                routedProjectLoadFailure = viewModel.errorMessage ?? "项目读取失败，请重试。"
                return
            }
        }
        viewModel.scheduleAutomaticStateSyncIfNeeded()
    }

    private var writingRequirementsMaterial: NovelMaterialRecord? {
        viewModel.activeMaterials.first {
            if case .writingRequirements = $0.kind { return true }
            return false
        }
    }

    private func openSettingProposals(_ route: NovelSettingProposalRoute) {
        section = .compendium
        compendiumSection = switch route {
        case .characters: .characters
        case .world: .world
        case .story: .story
        case .more: .more
        }
    }

    private func showBranchNotice(_ name: String) {
        activeSheet = nil
        withAnimation(.easeOut(duration: 0.2)) {
            branchNotice = "已切换到分支「\(name)」"
        }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            guard branchNotice == "已切换到分支「\(name)」" else { return }
            withAnimation(.easeIn(duration: 0.2)) { branchNotice = nil }
        }
    }

    private func sectionTitle(_ value: NovelWorkspaceSection) -> String {
        guard value == .compendium else { return value.title }
        let count = viewModel.branchSnapshot?.activeSettingProposals.count ?? 0
        return count == 0 ? value.title : "\(value.title) · \(count)"
    }

    private var isSessionTransitionBusy: Bool {
        sessionViewModel.isPerformingAction ||
            (viewModel.isPerforming && !sessionViewModel.isStarting)
    }

    private var chapterOptions: [NovelSessionChapterOption] {
        // 已废弃的章不参与收录:注入侧早已把它们排除出生成上下文
        // (NovelInjectionPlanner 取「最后一个未废弃的选择」),收录侧若还列着它们,
        // 就会出现「并入当前章」把新文并进一个已废弃章的情况。
        // 序号仍按完整章节表算,过滤不会让后面的章改名。
        let discarded = Set(
            (viewModel.projectSnapshot?.chapters ?? [])
                .filter { $0.discardedAt != nil }
                .map(\.id)
        )
        return sessionViewModel.currentChapterVersions.enumerated().compactMap { index, version in
            guard !discarded.contains(version.chapterID) else { return nil }
            return NovelSessionChapterOption(
                selection: NovelChapterSelection(
                    chapterID: version.chapterID,
                    versionID: version.id
                ),
                version: version,
                ordinal: index + 1
            )
        }
    }

    private func chapterID(for target: NovelCollectionTarget) -> NovelChapterID {
        switch target {
        case .appendToChapter(let chapterID): chapterID
        case .replaceChapter(let chapterID): chapterID
        case .createNextChapter(let chapterID, _): chapterID
        }
    }

    private var headerSubtitle: String {
        guard hasLoadedRoutedProject else { return "读取项目" }
        let branch = viewModel.branchSnapshot?.branch.name ?? "读取分支"
        let model = modelDisplayName(for: .creation)
        return "\(branch) · \(model)"
    }

    private func selectedModelID(for purpose: NovelModelRole) -> String {
        guard let project = viewModel.projectSnapshot?.project else { return "" }
        _ = sharedSettings.revision
        return NovelPresentation.selectedModelID(
            for: effectivePolicy(project.configuredModelPolicy(for: purpose), purpose: purpose),
            sharedSettings: sharedSettings
        )
    }

    private func modelDisplayName(for purpose: NovelModelRole) -> String {
        guard let project = viewModel.projectSnapshot?.project else { return "选择模型" }
        _ = sharedSettings.revision
        let configured = project.configuredModelPolicy(for: purpose)
        let effective = effectivePolicy(configured, purpose: purpose)
        let name = NovelPresentation.modelDisplayName(for: effective, sharedSettings: sharedSettings)
        return configured == .global ? "默认 · \(name)" : name
    }

    private func effectivePolicy(
        _ configured: NovelProjectModelPolicy,
        purpose: NovelModelRole
    ) -> NovelProjectModelPolicy {
        guard case .global = configured else { return configured }
        return NovelCreationModelPreferences.shared.policy(for: purpose)
    }

    private func selectFixedModel(_ option: ComposerModelOption, for purpose: NovelModelRole) {
        guard let providerID = NovelPresentation.providerID(
            forModelID: option.id,
            sharedSettings: sharedSettings
        ) else {
            viewModel.presentError(NovelError.invalidInput("所选模型的服务商已不存在。"))
            return
        }
        selectModelPolicy(.fixed(providerID: providerID, modelID: option.id), for: purpose)
    }

    private func selectModelPolicy(_ policy: NovelProjectModelPolicy, for purpose: NovelModelRole) {
        activeSheet = nil
        Task { @MainActor in
            await viewModel.setModelPolicy(policy, for: purpose)
        }
    }

    private func transition(to sheet: NovelWorkspaceSheet) {
        activeSheet = nil
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(250))
            activeSheet = sheet
        }
    }

}

private struct NovelWorkspaceGlassTabBar: View {
    @Binding var selection: NovelWorkspaceSection
    let title: (NovelWorkspaceSection) -> String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.isEnabled) private var isEnabled
    @Namespace private var selectionNamespace

    var body: some View {
        Group {
            if #available(iOS 26.0, *) {
                tabs
                    .padding(3)
                    .glassEffect(.regular.interactive(), in: Capsule())
            } else {
                tabs
                    .padding(3)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay {
                        Capsule()
                            .stroke(.white.opacity(0.55), lineWidth: 0.5)
                    }
                    .shadow(color: .black.opacity(0.10), radius: 12, y: 2)
            }
        }
        .frame(height: 44)
        .opacity(isEnabled ? 1 : 0.5)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("小说工作区")
    }

    private var tabs: some View {
        HStack(spacing: 0) {
            ForEach(NovelWorkspaceSection.allCases) { value in
                tab(value)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.22), value: selection)
    }

    private func tab(_ value: NovelWorkspaceSection) -> some View {
        let isSelected = selection == value

        return Button {
            guard !isSelected else { return }
            selection = value
        } label: {
            Text(title(value))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(isSelected ? AmberTheme.foreground : AmberTheme.foreground2)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background {
                    if isSelected {
                        Capsule()
                            .fill(selectionFill)
                            .overlay {
                                Capsule()
                                    .stroke(selectionStroke, lineWidth: 0.5)
                            }
                            .shadow(
                                color: .black.opacity(colorScheme == .dark ? 0.18 : 0.08),
                                radius: 5,
                                y: 1
                            )
                            .matchedGeometryEffect(
                                id: "selected-workspace-section",
                                in: selectionNamespace
                            )
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.98, haptic: .selection))
        .accessibilityLabel(title(value))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var selectionFill: Color {
        colorScheme == .dark ? .white.opacity(0.12) : .black.opacity(0.065)
    }

    private var selectionStroke: Color {
        colorScheme == .dark ? .white.opacity(0.16) : .white.opacity(0.62)
    }
}

private struct NovelNavigationDidAppearObserver: UIViewControllerRepresentable {
    let action: () -> Void

    func makeUIViewController(context: Context) -> ObserverViewController {
        ObserverViewController(action: action)
    }

    func updateUIViewController(_ controller: ObserverViewController, context: Context) {
        controller.action = action
    }

    final class ObserverViewController: UIViewController {
        var action: () -> Void
        private var hasReportedAppearance = false

        init(action: @escaping () -> Void) {
            self.action = action
            super.init(nibName: nil, bundle: nil)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .clear
            view.isUserInteractionEnabled = false
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !hasReportedAppearance else { return }
            hasReportedAppearance = true
            action()
        }
    }
}

private enum NovelWorkspaceSheet: Identifiable {
    case writingContext
    case modelPicker(NovelModelRole)
    case materialEditor(NovelMaterialRecord?, NovelMaterialKind)
    case polishPreference
    case collectCandidate(NovelCandidateID)
    case manualRewrite(NovelCandidateID)
    case forkCheckpoint(NovelCheckpointID)
    case proposal(NovelSettingProposalRecord)
    case discussionArchiveOffer(NovelChapterID?)
    case discussionArchive(NovelChapterID?)

    var id: String {
        switch self {
        case .writingContext: "writing-context"
        case .modelPicker(let purpose): "model-picker-\(purpose.rawValue)"
        case .materialEditor(let material, let suggestedKind):
            "material-\(material?.id.description ?? suggestedKind.displayName)"
        case .polishPreference: "polish-preference"
        case .collectCandidate(let candidateID): "collect-\(candidateID)"
        case .manualRewrite(let candidateID): "manual-rewrite-\(candidateID)"
        case .forkCheckpoint(let checkpointID): "fork-checkpoint-\(checkpointID)"
        case .proposal(let proposal): "proposal-\(proposal.id)"
        case .discussionArchiveOffer(let chapterID):
            "discussion-archive-offer-\(chapterID?.description ?? "current")"
        case .discussionArchive(let chapterID):
            "discussion-archive-\(chapterID?.description ?? "current")"
        }
    }
}
