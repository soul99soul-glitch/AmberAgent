import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct NovelProjectWorkspaceView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    let viewModel: NovelCreationViewModel
    let sharedSettings: IOSSharedSettingsStore
    let projectID: NovelProjectID

    @State private var sessionViewModel: NovelSessionViewModel
    @State private var section: NovelWorkspaceSection = .creation
    @State private var compendiumSection: NovelCompendiumSection = .chapters
    @State private var activeSheet: NovelWorkspaceSheet?
    @State private var chapterReaderRoute: NovelChapterReaderRoute?
    @State private var sessionInputText = ""
    @State private var sessionInjectionOverrides = NovelInjectionOverrides.none
    @State private var sessionInputBudgetTokens = 16_000
    @State private var packageDocument: NovelProjectFileDocument?
    @State private var packageFileName = "Novel.ambernovel"
    @State private var isExportingPackage = false
    @State private var markdownDocument: NovelMarkdownFileDocument?
    @State private var markdownFileName = "Novel.md"
    @State private var isExportingMarkdown = false
    @State private var isImportingPackage = false
    @State private var isConfirmingPreviousRestore = false
    @State private var branchNotice: String?
    @State private var lifecycleCoordinator: NovelWorkspaceLifecycleCoordinator

    init(
        viewModel: NovelCreationViewModel,
        sharedSettings: IOSSharedSettingsStore,
        projectID: NovelProjectID
    ) {
        self.viewModel = viewModel
        self.sharedSettings = sharedSettings
        self.projectID = projectID
        self._sessionViewModel = State(initialValue: NovelSessionViewModel(workspace: viewModel))
        self._lifecycleCoordinator = State(
            initialValue: NovelWorkspaceLifecycleCoordinator()
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            sectionPicker
            accessBanner
            content
        }
        .background(AmberTheme.background.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $activeSheet, content: sheetContent)
        .fullScreenCover(item: $chapterReaderRoute) { route in
            NovelChapterReaderView(
                viewModel: viewModel,
                sessionViewModel: sessionViewModel,
                initialSelection: route.selection
            ) {
                section = .creation
            }
        }
        .fileExporter(
            isPresented: $isExportingPackage,
            document: packageDocument,
            contentType: .amberNovelProject,
            defaultFilename: packageFileName,
            onCompletion: handleExportResult
        )
        .fileImporter(
            isPresented: $isImportingPackage,
            allowedContentTypes: [.amberNovelProject],
            allowsMultipleSelection: false,
            onCompletion: handlePackageImport
        )
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
        .fileExporter(
            isPresented: $isExportingMarkdown,
            document: markdownDocument,
            contentType: .amberMarkdown,
            defaultFilename: markdownFileName,
            onCompletion: handleExportResult
        )
        .overlay {
            if viewModel.isLoading && viewModel.projectSnapshot == nil {
                ProgressView("正在读取小说项目")
                    .padding(18)
                    .amberGlass(cornerRadius: AmberTheme.radiusLarge, interactive: false)
            }
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
        .task(id: projectID) {
            guard viewModel.selectedProjectID != projectID || viewModel.projectSnapshot == nil else { return }
            await viewModel.selectProject(projectID)
        }
        .onChange(of: viewModel.branchSnapshot?.branch.id) { _, _ in
            sessionInputText = ""
            sessionInjectionOverrides = .none
            sessionInputBudgetTokens = 16_000
            Task { @MainActor in
                await sessionViewModel.bindToCurrentSelection()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            handleScenePhaseChange(phase)
        }
        .onDisappear {
            Task { @MainActor in
                guard chapterReaderRoute == nil else { return }
                _ = await sessionViewModel.interruptForRouteExit()
            }
        }
    }

    private func handleScenePhaseChange(_ phase: ScenePhase) {
        guard phase == .background else { return }
        lifecycleCoordinator.enterBackground { deadline in
            await sessionViewModel.interruptForBackground(deadline: deadline)
        }
    }

    private var header: some View {
        AmberGlassGroup(spacing: 16) {
            ZStack {
                Button {
                    activeSheet = .branchPicker
                } label: {
                    VStack(spacing: 2) {
                        HStack(spacing: 4) {
                            Text(projectName)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(AmberTheme.foreground)
                                .lineLimit(1)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(AmberTheme.muted)
                        }
                        Text(headerSubtitle)
                            .font(.system(size: 11.5))
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: 220)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("切换分支")
                .disabled(isSessionTransitionBusy)

                HStack(spacing: 10) {
                    AmberGlassCircleButton(
                        systemImage: "chevron.left",
                        accessibilityLabel: "返回小说项目",
                        size: 44,
                        symbolSize: 20
                    ) {
                        Task { @MainActor in
                            guard await sessionViewModel.interruptForRouteExit() else { return }
                            dismiss()
                        }
                    }
                    .disabled(isSessionTransitionBusy)

                    Spacer(minLength: 0)

                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private var sectionPicker: some View {
        Picker("小说工作区", selection: $section) {
            ForEach(NovelWorkspaceSection.allCases) { value in
                Text(sectionTitle(value)).tag(value)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .accessibilityLabel("小说工作区")
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
        } else if let project = viewModel.projectSnapshot,
                  !project.pendingOperations.isEmpty ||
                    project.activeRuns.contains(where: { $0.status == .running }) {
            Label(pendingDescription(project), systemImage: "clock.arrow.circlepath")
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(AmberTheme.accentTint)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch section {
        case .creation:
            NovelSessionView(
                workspace: viewModel,
                viewModel: sessionViewModel,
                sharedSettings: sharedSettings,
                inputText: $sessionInputText,
                injectionOverrides: $sessionInjectionOverrides,
                inputBudgetTokens: $sessionInputBudgetTokens,
                onOpenContext: { activeSheet = .sessionContext },
                onOpenModel: { activeSheet = .modelPicker },
                onOpenCollection: { activeSheet = .collectCandidate($0) },
                onOpenManualRewrite: { activeSheet = .manualRewrite($0) },
                onFork: { activeSheet = .forkCheckpoint($0) },
                onOpenSettingProposals: openSettingProposals
            )
        case .compendium:
            NovelCompendiumView(
                viewModel: viewModel,
                sharedSettings: sharedSettings,
                selection: $compendiumSection,
                onOpenChapter: { chapter in
                    chapterReaderRoute = NovelChapterReaderRoute(selection: chapter)
                },
                onEditMaterial: { material, suggestedKind in
                    activeSheet = .materialEditor(material, suggestedKind)
                },
                onChooseFixedModel: {
                    activeSheet = .modelPicker
                },
                onEditPolishPreference: {
                    activeSheet = .polishPreference
                },
                onPreviewInjectionRules: {
                    activeSheet = .injectionPreview
                },
                onAcceptProposal: { proposal in
                    activeSheet = .proposal(proposal)
                },
                onManageBranches: { activeSheet = .branchManager },
                onImportPackage: importPackage,
                onExportPackage: exportPackage,
                onExportMarkdown: exportMarkdown
            )
        }
    }

    private func selectBranchFromWorkspace(_ branchID: NovelBranchID) {
        Task { @MainActor in
            if branchID == viewModel.selectedBranchID,
               viewModel.branchSnapshot?.branch.id == branchID {
                return
            }
            guard await sessionViewModel.interruptForRouteExit() else { return }
            await viewModel.selectBranch(branchID)
            guard viewModel.errorMessage == nil,
                  viewModel.branchSnapshot?.branch.id == branchID else {
                await sessionViewModel.bindToCurrentSelection()
                return
            }
            await sessionViewModel.bindToCurrentSelection()
        }
    }

    @ViewBuilder
    private func sheetContent(_ sheet: NovelWorkspaceSheet) -> some View {
        switch sheet {
        case .branchPicker:
            NovelBranchPickerSheet(viewModel: viewModel, sessionViewModel: sessionViewModel) {
                activeSheet = nil
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)

        case .modelPicker:
            ComposerModelSheet(
                sharedSettings: sharedSettings,
                currentModel: selectedModelID
            ) { option in
                selectFixedModel(option)
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

        case .injectionPreview:
            NovelInjectionPreviewSheet(viewModel: viewModel)

        case .sessionContext:
            NovelSessionContextSheet(
                workspace: viewModel,
                mode: sessionViewModel.mode,
                granularity: sessionViewModel.granularity,
                userText: sessionInputText,
                overrides: sessionInjectionOverrides,
                budgetTokens: sessionInputBudgetTokens
            ) { overrides, budget in
                sessionInjectionOverrides = overrides
                sessionInputBudgetTokens = budget
            }

        case .collectCandidate(let candidateID):
            NovelCollectCandidateSheet(
                paragraphs: sessionViewModel.paragraphs(candidateID: candidateID),
                chapters: chapterOptions,
                suggestedGranularity: sessionViewModel.collectionGranularity(
                    for: candidateID
                )
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
                    return .pending(message: "正文与状态更新已进入待处理队列，请返回后继续重试。")
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

        case .chapterManager:
            NavigationStack {
                NovelChapterManagementView(viewModel: viewModel) { selection in
                    transition(to: .chapterVersions(selection))
                }
                .navigationTitle("正文与版本")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { activeSheet = nil }
                    }
                }
            }

        case .branchManager:
            NavigationStack {
                NovelBranchesView(
                    viewModel: viewModel,
                    isSelectionDisabled: isSessionTransitionBusy,
                    onSelect: selectBranchFromWorkspace,
                    onRename: { transition(to: .renameBranch($0)) },
                    onFork: { transition(to: .forkBranch($0)) },
                    onEditOverride: { transition(to: .branchOverride($0)) }
                )
                .navigationTitle("分支管理")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { activeSheet = nil }
                    }
                }
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

        case .renameBranch(let branch):
            NovelBranchRenameSheet(viewModel: viewModel, branch: branch)

        case .forkBranch(let branch):
            NovelBranchForkSheet(
                viewModel: viewModel,
                branch: branch,
                onCreated: showBranchNotice
            )

        case .branchOverride(let material):
            NovelBranchOverrideEditorSheet(viewModel: viewModel, material: material)

        case .chapterVersions(let selection):
            NovelChapterVersionsSheet(viewModel: viewModel, selection: selection)

        case .importPackage(let data, let preview):
            NovelProjectImportSheet(
                viewModel: viewModel,
                packageData: data,
                preview: preview,
                onImported: finishImport
            )
        }
    }

    private var projectName: String {
        viewModel.projectSnapshot?.project.name ?? "小说创作"
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
        sessionViewModel.currentChapterVersions.enumerated().map { index, version in
            NovelSessionChapterOption(
                selection: NovelChapterSelection(
                    chapterID: version.chapterID,
                    versionID: version.id
                ),
                version: version,
                ordinal: index + 1
            )
        }
    }

    private var headerSubtitle: String {
        let branch = viewModel.branchSnapshot?.branch.name ?? "读取分支"
        guard let policy = viewModel.projectSnapshot?.project.modelPolicy else { return branch }
        _ = sharedSettings.revision
        let model = NovelPresentation.modelDisplayName(for: policy, sharedSettings: sharedSettings)
        return "\(branch) · \(model)"
    }

    private var selectedModelID: String {
        guard let policy = viewModel.projectSnapshot?.project.modelPolicy else { return "" }
        _ = sharedSettings.revision
        return NovelPresentation.selectedModelID(for: policy, sharedSettings: sharedSettings)
    }

    private func pendingDescription(_ project: NovelProjectSnapshot) -> String {
        if project.activeRuns.contains(where: { $0.status == .running }) {
            return "Agent 正在生成，部分写操作暂不可用"
        }
        return "有 \(project.pendingOperations.count) 项正文状态等待同步或重试"
    }

    private func selectFixedModel(_ option: ComposerModelOption) {
        guard let providerID = NovelPresentation.providerID(
            forModelID: option.id,
            sharedSettings: sharedSettings
        ) else {
            viewModel.presentError(NovelError.invalidInput("所选模型的服务商已不存在。"))
            return
        }
        activeSheet = nil
        Task { @MainActor in
            await viewModel.setModelPolicy(.fixed(providerID: providerID, modelID: option.id))
        }
    }

    private func transition(to sheet: NovelWorkspaceSheet) {
        activeSheet = nil
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(250))
            activeSheet = sheet
        }
    }

    private func importPackage() {
        Task { @MainActor in
            if sessionViewModel.isRunning {
                guard await sessionViewModel.interruptForRouteExit() else {
                    viewModel.presentError(NovelError.projectBusy(projectID))
                    return
                }
                await sessionViewModel.bindToCurrentSelection()
            }
            guard await viewModel.stopActiveRunsForProjectOperation(projectID: projectID) else { return }
            isImportingPackage = true
        }
    }

    private func exportPackage() {
        activeSheet = nil
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            if sessionViewModel.isRunning {
                guard await sessionViewModel.interruptForRouteExit() else {
                    viewModel.presentError(NovelError.projectBusy(projectID))
                    return
                }
                await sessionViewModel.bindToCurrentSelection()
            }
            guard await viewModel.stopActiveRunsForProjectOperation(projectID: projectID) else { return }
            guard let artifact = await viewModel.exportProjectPackage() else { return }
            packageDocument = NovelProjectFileDocument(data: artifact.data)
            packageFileName = NovelPresentation.fileName(
                artifact.projectName,
                fallback: artifact.projectID.description
            ) + ".ambernovel"
            isExportingPackage = true
        }
    }

    private func exportMarkdown() {
        activeSheet = nil
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            if sessionViewModel.isRunning {
                guard await sessionViewModel.interruptForRouteExit() else {
                    viewModel.presentError(NovelError.projectBusy(projectID))
                    return
                }
                await sessionViewModel.bindToCurrentSelection()
            }
            guard await viewModel.stopActiveRunsForProjectOperation(projectID: projectID) else { return }
            guard let artifact = await viewModel.exportBranchMarkdown() else { return }
            markdownDocument = NovelMarkdownFileDocument(markdown: artifact.markdown)
            markdownFileName = artifact.fileName
            isExportingMarkdown = true
        }
    }

    private func handleExportResult(_ result: Result<URL, Error>) {
        if case .failure(let error) = result {
            viewModel.presentError(error)
        }
    }

    private func handlePackageImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure(let error) = result { viewModel.presentError(error) }
            return
        }
        Task { @MainActor in
            do {
                let data = try await Task.detached(priority: .userInitiated) {
                    try NovelProjectFileReader.readPackage(from: url)
                }.value
                guard let preview = await viewModel.previewImport(data) else { return }
                activeSheet = .importPackage(data: data, preview: preview)
            } catch {
                viewModel.presentError(error)
            }
        }
    }

    private func finishImport(_ importedProjectID: NovelProjectID) {
        guard importedProjectID != projectID else {
            Task { @MainActor in await sessionViewModel.bindToCurrentSelection() }
            return
        }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(250))
            dismiss()
        }
    }
}

private enum NovelWorkspaceSheet: Identifiable {
    case branchPicker
    case modelPicker
    case materialEditor(NovelMaterialRecord?, NovelMaterialKind)
    case polishPreference
    case injectionPreview
    case sessionContext
    case collectCandidate(NovelCandidateID)
    case manualRewrite(NovelCandidateID)
    case chapterManager
    case branchManager
    case forkCheckpoint(NovelCheckpointID)
    case proposal(NovelSettingProposalRecord)
    case renameBranch(NovelBranchRecord)
    case forkBranch(NovelBranchRecord)
    case branchOverride(NovelMaterialRecord)
    case chapterVersions(NovelChapterSelection)
    case importPackage(data: Data, preview: NovelProjectImportPreview)

    var id: String {
        switch self {
        case .branchPicker: "branch-picker"
        case .modelPicker: "model-picker"
        case .materialEditor(let material, let suggestedKind):
            "material-\(material?.id.description ?? suggestedKind.displayName)"
        case .polishPreference: "polish-preference"
        case .injectionPreview: "injection-preview"
        case .sessionContext: "session-context"
        case .collectCandidate(let candidateID): "collect-\(candidateID)"
        case .manualRewrite(let candidateID): "manual-rewrite-\(candidateID)"
        case .chapterManager: "chapter-manager"
        case .branchManager: "branch-manager"
        case .forkCheckpoint(let checkpointID): "fork-checkpoint-\(checkpointID)"
        case .proposal(let proposal): "proposal-\(proposal.id)"
        case .renameBranch(let branch): "rename-branch-\(branch.id)"
        case .forkBranch(let branch): "fork-branch-\(branch.id)"
        case .branchOverride(let material): "branch-override-\(material.id)"
        case .chapterVersions(let selection): "chapter-\(selection.chapterID)"
        case .importPackage(_, let preview):
            "import-\(preview.sourceProjectID)-\(preview.projectSHA256)"
        }
    }
}

private struct NovelBranchPickerSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let sessionViewModel: NovelSessionViewModel
    let onSelected: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(viewModel.activeBranches, id: \.id) { branch in
                    Button {
                        select(branch.id)
                    } label: {
                        NovelBranchPickerRow(
                            branch: branch,
                            isSelected: branch.id == viewModel.selectedBranchID,
                            isMain: branch.id == viewModel.projectSnapshot?.project.mainBranchID
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("切换分支")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func select(_ branchID: NovelBranchID) {
        Task { @MainActor in
            if branchID == viewModel.selectedBranchID,
               viewModel.branchSnapshot?.branch.id == branchID {
                dismiss()
                onSelected()
                return
            }
            guard await sessionViewModel.interruptForRouteExit() else { return }
            await viewModel.selectBranch(branchID)
            guard viewModel.errorMessage == nil,
                  viewModel.branchSnapshot?.branch.id == branchID else {
                await sessionViewModel.bindToCurrentSelection()
                return
            }
            await sessionViewModel.bindToCurrentSelection()
            dismiss()
            onSelected()
        }
    }
}

private struct NovelBranchPickerRow: View {
    let branch: NovelBranchRecord
    let isSelected: Bool
    let isMain: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isMain ? "star.fill" : "arrow.triangle.branch")
                .foregroundStyle(isMain ? AmberTheme.accentAmber : AmberTheme.accent)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(branch.name)
                    .foregroundStyle(AmberTheme.foreground)
                Text(branch.syncStatus.displayName)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            Spacer()
            if isSelected {
                Image(systemName: "checkmark")
                    .foregroundStyle(AmberTheme.accent)
            }
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }
}
