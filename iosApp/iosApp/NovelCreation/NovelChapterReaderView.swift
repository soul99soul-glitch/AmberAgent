import SwiftUI

struct NovelChapterReaderRoute: Identifiable, Hashable {
    let selection: NovelChapterSelection
    var id: NovelChapterID { selection.chapterID }
}

struct NovelChapterReaderView: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let sessionViewModel: NovelSessionViewModel
    let initialSelection: NovelChapterSelection
    let onPolishStarted: () -> Void

    @State private var chapterID: NovelChapterID
    @State private var activeSheet: ReaderSheet?
    @State private var failureMessage: String?
    @State private var startingAction: NovelChapterStartingAction?
    @State private var isConfirmingDelete = false

    init(
        viewModel: NovelCreationViewModel,
        sessionViewModel: NovelSessionViewModel,
        initialSelection: NovelChapterSelection,
        onPolishStarted: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.sessionViewModel = sessionViewModel
        self.initialSelection = initialSelection
        self.onPolishStarted = onPolishStarted
        self._chapterID = State(initialValue: initialSelection.chapterID)
    }

    var body: some View {
        reader
            .safeAreaInset(edge: .top, spacing: 0) {
                chapterStatusBanner
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                chapterNavigation
            }
            .background { AmberThemePageBackground(surface: .app) }
            .toolbarBackground(AmberTheme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { readerToolbar }
            .confirmationDialog(
                "删除本章？",
                isPresented: $isConfirmingDelete,
                titleVisibility: .visible
            ) {
                Button("从正文目录删除", role: .destructive) {
                    deleteChapterFromManuscript()
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text(
                    "会从当前分支的正文目录移除这一章，目录与生成都不再包含它。"
                        + "历史检查点里可能仍保留引用，不能撤销到「从未写过」；"
                        + "若只是暂时不想用，请用「废弃本章」。"
                )
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .versions(let selection):
                    NovelChapterVersionsSheet(viewModel: viewModel, selection: selection)
                case .edit(let version):
                    NovelChapterEditSheet(viewModel: viewModel, version: version)
                }
            }
            .alert("操作未完成", isPresented: Binding(
                get: { failureMessage != nil },
                set: { if !$0 { failureMessage = nil } }
            )) {
                Button("好") { failureMessage = nil }
            } message: {
                Text(failureMessage ?? "请稍后重试。")
            }
    }

    @ToolbarContentBuilder
    private var readerToolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 12) {
                    Text(chapterOrdinalTitle)
                    Text(currentChapterWordCountTitle)
                }
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                Text(currentChapterTitle)
                    .font(.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
            }
            .frame(minWidth: 180, maxWidth: 220, alignment: .leading)
        }

        if #available(iOS 26.0, *) {
            ToolbarItem(placement: .topBarTrailing) {
                readerActionsMenu
            }
            .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                readerActionsMenu
            }
        }
    }

    private var readerActionsMenu: some View {
        Menu {
            Button {
                if let selection = currentSelection {
                    activeSheet = .versions(selection)
                }
            } label: {
                Label("版本历史", systemImage: "clock.arrow.circlepath")
            }

            Button {
                if let currentVersion {
                    activeSheet = .edit(currentVersion)
                }
            } label: {
                Label(editMenuTitle, systemImage: "square.and.pencil")
            }
            .disabled(editBlockReason != nil)

            Button {
                startPolish()
            } label: {
                Label(polishMenuTitle, systemImage: "wand.and.sparkles")
            }
            .disabled(polishBlockReason != nil)

            Button {
                startRegeneration()
            } label: {
                Label(regenerateMenuTitle, systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(polishBlockReason != nil)

            Divider()

            Button(role: isCurrentChapterDiscarded ? nil : .destructive) {
                guard chapterDiscardBlockReason == nil else { return }
                setChapterDiscarded(!isCurrentChapterDiscarded)
            } label: {
                Label(
                    chapterDiscardMenuTitle,
                    systemImage: isCurrentChapterDiscarded ? "arrow.uturn.backward" : "archivebox"
                )
            }
            .disabled(chapterDiscardBlockReason != nil)

            Button(role: .destructive) {
                guard chapterDeleteBlockReason == nil else { return }
                isConfirmingDelete = true
            } label: {
                Label(chapterDeleteMenuTitle, systemImage: "trash")
            }
            .disabled(chapterDeleteBlockReason != nil)
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 40, height: 40)
                .modifier(ComposerDockCircleGlass(tint: nil))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .buttonBorderShape(.circle)
        .accessibilityLabel("章节操作")
        .disabled(startingAction != nil)
    }

    @ViewBuilder
    private var chapterStatusBanner: some View {
        if isCurrentStateSyncRunning {
            NovelStateSyncProgressBanner(
                title: currentStateSyncStatusTitle,
                activity: currentStateSyncActivity,
                secondaryHint: currentStateSyncActivity?.segmentedRebuildHint,
                canStop: {
                    guard let projectID = viewModel.selectedProjectID,
                          let branchID = viewModel.selectedBranchID else { return false }
                    return viewModel.canCancelAutomaticStateSync(
                        projectID: projectID,
                        branchID: branchID
                    )
                }(),
                onStop: {
                    guard let projectID = viewModel.selectedProjectID,
                          let branchID = viewModel.selectedBranchID else { return }
                    viewModel.cancelAutomaticStateSync(
                        projectID: projectID,
                        branchID: branchID
                    )
                },
                usesBorderedStop: false
            )
            .padding(.horizontal, 20)
            .padding(.vertical, 9)
            .background(AmberTheme.accentAmber.opacity(0.10))
        } else if let recovery = currentStateSyncRecoveryMessage {
            VStack(alignment: .leading, spacing: 8) {
                Label(recovery, systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                Button("重试同步") {
                    guard let projectID = viewModel.selectedProjectID,
                          let branchID = viewModel.selectedBranchID else { return }
                    viewModel.retryStateSync(projectID: projectID, branchID: branchID)
                }
                .font(.footnote.weight(.semibold))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .disabled(!canRetryCurrentStateSync)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 9)
            .background(AmberTheme.accentAmber.opacity(0.10))
        } else if let startingAction {
            HStack(spacing: 9) {
                ProgressView()
                    .controlSize(.small)
                Text(startingAction.progressTitle)
                    .font(.footnote.weight(.medium))
            }
            .foregroundStyle(AmberTheme.foreground2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 9)
            .background(AmberTheme.accent.opacity(0.08))
        } else if viewModel.hasStalePlot {
            VStack(alignment: .leading, spacing: 8) {
                Label("改过前面的章节后，后面的剧情指针可能过期。后文以正文为准。", systemImage: "clock.arrow.circlepath")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                Button("按正文接受") {
                    Task { @MainActor in
                        await viewModel.acceptStalePlot()
                    }
                }
                .font(.footnote.weight(.semibold))
                .buttonStyle(.bordered)
                .controlSize(.small)
                // Align with the Label's text (after the symbol inset).
                .padding(.leading, 22)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .disabled(
                    viewModel.isPerforming || viewModel.requiresReload ||
                        viewModel.projectSnapshot?.access != .readWrite
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 9)
            .background(AmberTheme.accentAmber.opacity(0.10))
        } else if isCurrentChapterDiscarded {
            Label("已废弃 · 不进入后续生成上下文", systemImage: "archivebox.fill")
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 9)
                .background(AmberTheme.accentAmber.opacity(0.10))
        }
    }

    private var reader: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // 固定锚点：切章时 scrollTo("chapter-top") 回到开头。
                Color.clear
                    .frame(height: 0)
                    .id("chapter-top")

                if let version = currentVersion {
                    ChatAssistantMarkdownView(
                        markdown: version.content,
                        renderCacheNamespace: "novel:chapter:\(version.id)"
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 22)
                    .padding(.top, 28)
                    .padding(.bottom, 44)
                } else {
                    ContentUnavailableView("章节不存在", systemImage: "doc.text.magnifyingglass")
                        .padding(.top, 80)
                }
            }
            .scrollIndicators(.hidden)
            .onChange(of: chapterID) { _, _ in
                proxy.scrollTo("chapter-top", anchor: .top)
            }
            .onAppear {
                proxy.scrollTo("chapter-top", anchor: .top)
            }
        }
    }

    private var chapterNavigation: some View {
        AmberGlassGroup(spacing: 24) {
            HStack(spacing: 24) {
                Button {
                    moveChapter(by: -1)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "chevron.left")
                        Text("上一章")
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .padding(.horizontal, 14)
                    .frame(height: 44)
                    .contentShape(Capsule())
                    .amberGlass(cornerRadius: 20)
                }
                .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
                .disabled(currentIndex <= 0 || startingAction != nil)

                Spacer(minLength: 24)

                Button {
                    moveChapter(by: 1)
                } label: {
                    HStack(spacing: 6) {
                        Text("下一章")
                        Image(systemName: "chevron.right")
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .padding(.horizontal, 14)
                    .frame(height: 44)
                    .contentShape(Capsule())
                    .amberGlass(cornerRadius: 20)
                }
                .buttonStyle(AmberPressFeedbackStyle(pressedScale: 0.96, haptic: .selection))
                .disabled(
                    currentIndex < 0 ||
                        currentIndex >= chapterSelections.count - 1 ||
                        startingAction != nil
                )
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, 20)
        .padding(.top, 2)
    }

    private var chapterSelections: [NovelChapterSelection] {
        viewModel.branchSnapshot?.chapterSelections ?? []
    }

    private var currentIndex: Int {
        chapterSelections.firstIndex { $0.chapterID == chapterID } ?? -1
    }

    private var currentSelection: NovelChapterSelection? {
        chapterSelections.first { $0.chapterID == chapterID }
    }

    private var currentVersion: NovelChapterVersionRecord? {
        guard let versionID = currentSelection?.versionID else { return nil }
        return viewModel.projectSnapshot?.chapterVersions.first { $0.id == versionID }
    }

    private var currentStateSyncActivity: NovelStateSyncActivity? {
        guard let activity = viewModel.stateSyncActivity,
              activity.projectID == viewModel.selectedProjectID,
              activity.branchID == viewModel.selectedBranchID else { return nil }
        return activity
    }

    private var isCurrentStateSyncRunning: Bool {
        guard let projectID = viewModel.selectedProjectID,
              let branchID = viewModel.selectedBranchID else { return false }
        return currentStateSyncActivity != nil ||
            viewModel.canCancelAutomaticStateSync(
                projectID: projectID,
                branchID: branchID
            ) ||
            viewModel.isStateSyncStopping(projectID: projectID, branchID: branchID)
    }

    private var currentStateSyncStatusTitle: String {
        if let projectID = viewModel.selectedProjectID,
           let branchID = viewModel.selectedBranchID,
           let title = viewModel.stateSyncStatusTitle(
               projectID: projectID,
               branchID: branchID
           ) {
            return title
        }
        return currentStateSyncActivity?.phase == .analyzing
            ? "正在同步剧情状态"
            : "正在按正文对齐剧情指针"
    }

    private var currentStateSyncRecoveryMessage: String? {
        guard let projectID = viewModel.selectedProjectID,
              let branchID = viewModel.selectedBranchID else { return nil }
        return viewModel.stateSyncRecoveryMessage(
            projectID: projectID,
            branchID: branchID
        )
    }

    private var canRetryCurrentStateSync: Bool {
        guard let projectID = viewModel.selectedProjectID,
              let branchID = viewModel.selectedBranchID else { return false }
        return viewModel.canRetryStateSync(projectID: projectID, branchID: branchID)
    }

    /// 废弃是可逆的标记,不删除任何记录:章节版本之间有事实兼容链,真删会断链。
    /// 废弃后该章不再进入生成上下文(见 NovelInjectionPlanner)。
    private var isCurrentChapterDiscarded: Bool {
        viewModel.projectSnapshot?.chapters
            .first { $0.id == chapterID }?
            .discardedAt != nil
    }

    private var chapterOrdinalTitle: String {
        guard currentIndex >= 0 else { return "正文" }
        return "第 \(currentIndex + 1) 章"
    }

    private var currentChapterTitle: String {
        guard let version = currentVersion, currentIndex >= 0 else { return "正文" }
        return NovelPresentation.chapterDisplayTitle(
            storedTitle: version.title,
            content: version.content,
            ordinal: currentIndex + 1
        )
    }

    private var currentChapterWordCountTitle: String {
        guard let currentVersion else { return "" }
        return "\(currentVersion.content.count.formatted()) 字"
    }

    private var editBlockReason: String? {
        if isProjectReadOnly { return "项目当前只读" }
        if viewModel.requiresReload { return "请先重新载入项目" }
        if sessionViewModel.isRunning || viewModel.branchSnapshot?.branch.activeRunID != nil {
            return "请先停止当前生成"
        }
        if currentBranchHasPendingOperations { return "请先完成当前分支的正文操作" }
        if viewModel.isPerforming { return "项目正在处理其他操作" }
        if !viewModel.canMutate { return "当前状态暂不能编辑" }
        return nil
    }

    private var editMenuTitle: String {
        guard let editBlockReason else { return "编辑本章" }
        return "编辑本章（\(editBlockReason)）"
    }

    private var polishBlockReason: String? {
        if isProjectReadOnly { return "项目当前只读" }
        if viewModel.requiresReload { return "请先重新载入项目" }
        if isCurrentChapterDiscarded { return "请先恢复本章" }
        if sessionViewModel.isRunning || viewModel.branchSnapshot?.branch.activeRunID != nil {
            return "请先停止当前生成"
        }
        if viewModel.branchSnapshot?.currentState.hasStaleChapterPlots == true {
            return NovelWorkspaceLedger.unresolvedPlotGateMessage
        }
        if viewModel.branchSnapshot?.branch.syncStatus == .needsSync { return "请先同步剧情状态" }
        if !sessionViewModel.unresolvedBranchPolishTransactions.isEmpty {
            return "请先处理上次润色检查"
        }
        if currentBranchHasPendingOperations { return "请先完成当前分支的正文操作" }
        if viewModel.isPerforming || sessionViewModel.isBusy { return "项目正在处理其他操作" }
        if !viewModel.canMutate { return "当前状态暂不能生成" }
        return nil
    }

    private var chapterDiscardBlockReason: String? {
        if isProjectReadOnly { return "项目当前只读" }
        if viewModel.requiresReload { return "请先重新载入项目" }
        if sessionViewModel.isRunning || viewModel.branchSnapshot?.branch.activeRunID != nil {
            return "请先停止当前生成"
        }
        if currentBranchHasPendingOperations { return "请先完成当前分支的正文操作" }
        if viewModel.isPerforming || sessionViewModel.isBusy { return "项目正在处理其他操作" }
        if !viewModel.canMutate { return "当前状态暂不能修改章节状态" }
        return nil
    }

    private var chapterDiscardMenuTitle: String {
        let action = isCurrentChapterDiscarded ? "恢复本章" : "废弃本章"
        guard let chapterDiscardBlockReason else { return action }
        return "\(action)（\(chapterDiscardBlockReason)）"
    }

    private var chapterDeleteBlockReason: String? {
        chapterDiscardBlockReason
    }

    private var chapterDeleteMenuTitle: String {
        guard let chapterDeleteBlockReason else { return "删除本章" }
        return "删除本章（\(chapterDeleteBlockReason)）"
    }

    private var isProjectReadOnly: Bool {
        guard let access = viewModel.projectSnapshot?.access else { return false }
        if case .readWrite = access { return false }
        return true
    }

    private var currentBranchHasPendingOperations: Bool {
        guard let branchID = viewModel.branchSnapshot?.branch.id else { return false }
        return viewModel.projectSnapshot?.pendingOperations.contains {
            $0.branchID == branchID
        } == true
    }

    private var polishMenuTitle: String {
        guard let polishBlockReason else { return "整章润色" }
        return "整章润色（\(polishBlockReason)）"
    }

    /// 与润色共用前置条件(只读/生成中/待同步/有未完成正文操作时都不能发起)。
    private var regenerateMenuTitle: String {
        guard let polishBlockReason else { return "整章重新生成" }
        return "整章重新生成（\(polishBlockReason)）"
    }

    private func moveChapter(by offset: Int) {
        let destination = currentIndex + offset
        guard chapterSelections.indices.contains(destination) else { return }
        chapterID = chapterSelections[destination].chapterID
    }

    /// 重新生成允许改剧情,所以走普通 prose run 而不是润色事务;产出的候选
    /// 在收录面板里默认选中「替换本章」。
    private func startRegeneration() {
        guard polishBlockReason == nil, startingAction == nil else { return }
        startingAction = .regenerate
        Task { @MainActor in
            defer { startingAction = nil }
            let started = await sessionViewModel.startWholeChapterRegeneration(chapterID: chapterID)
            guard started else {
                failureMessage = sessionViewModel.errorMessage ?? "重新生成没有开始，请稍后重试。"
                return
            }
            dismiss()
            onPolishStarted()
        }
    }

    private func startPolish() {
        guard polishBlockReason == nil, startingAction == nil else { return }
        startingAction = .polish
        Task { @MainActor in
            defer { startingAction = nil }
            let started = await sessionViewModel.startWholeChapterPolish(chapterID: chapterID)
            guard started else {
                failureMessage = sessionViewModel.errorMessage ?? "润色没有开始，请稍后重试。"
                return
            }
            dismiss()
            onPolishStarted()
        }
    }

    private func setChapterDiscarded(_ isDiscarded: Bool) {
        guard startingAction == nil else { return }
        startingAction = isDiscarded ? .discard : .restore
        Task { @MainActor in
            defer { startingAction = nil }
            viewModel.clearError()
            let succeeded = await viewModel.setChapterDiscarded(
                isDiscarded,
                chapterID: chapterID
            )
            guard succeeded else {
                failureMessage = viewModel.errorMessage ?? "章节状态没有更新，请稍后重试。"
                return
            }
        }
    }

    private func deleteChapterFromManuscript() {
        guard startingAction == nil else { return }
        startingAction = .delete
        Task { @MainActor in
            defer { startingAction = nil }
            viewModel.clearError()
            let succeeded = await viewModel.deleteChapterFromManuscript(chapterID: chapterID)
            guard succeeded else {
                failureMessage = viewModel.errorMessage ?? "章节没有从正文目录删除，请稍后重试。"
                return
            }
            dismiss()
        }
    }
}

private enum NovelChapterStartingAction {
    case polish
    case regenerate
    case discard
    case restore
    case delete

    var progressTitle: String {
        switch self {
        case .polish: "正在开始整章润色"
        case .regenerate: "正在开始整章重写"
        case .discard: "正在废弃本章"
        case .restore: "正在恢复本章"
        case .delete: "正在从正文目录删除"
        }
    }
}

private enum ReaderSheet: Identifiable {
    case versions(NovelChapterSelection)
    case edit(NovelChapterVersionRecord)

    var id: String {
        switch self {
        case .versions(let selection): "versions-\(selection.chapterID)"
        case .edit(let version): "edit-\(version.id)"
        }
    }
}

private struct NovelChapterEditSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let version: NovelChapterVersionRecord

    @State private var title: String
    @State private var content: String
    @State private var isSaving = false
    @State private var isFindPresented = false
    @State private var failureMessage: String?
    @State private var isConfirmingDiscard = false
    @State private var imeBank = NovelIMEFieldBank()

    init(viewModel: NovelCreationViewModel, version: NovelChapterVersionRecord) {
        self.viewModel = viewModel
        self.version = version
        self._title = State(initialValue: version.title)
        self._content = State(initialValue: version.content)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("章节") {
                    NovelIMETextField(
                        text: $title,
                        placeholder: "章节标题",
                        bank: imeBank
                    )
                    .frame(minHeight: 36)
                    // Keep system TextEditor only for findNavigator; flush via first-responder
                    // unmark on save still covers IME for this long body field.
                    TextEditor(text: $content)
                        .frame(minHeight: 360)
                        .findNavigator(isPresented: $isFindPresented)
                }

                Section {
                    Label(
                        "保存时会把本章剧情要点一并写入，无需单独同步。",
                        systemImage: "arrow.triangle.2.circlepath"
                    )
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground2)
                }

                if let failureMessage {
                    Section {
                        Label(failureMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(AmberTheme.accentRed)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("编辑本章")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { requestDismiss() }
                    }
                        .disabled(isSaving)
                        .confirmationDialog(
                            "放弃本章改写？",
                            isPresented: $isConfirmingDiscard,
                            titleVisibility: .visible
                        ) {
                            Button("放弃更改", role: .destructive) { dismiss() }
                            Button("继续编辑", role: .cancel) {}
                        } message: {
                            Text("尚未保存的标题和正文修改会丢失。")
                        }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        // Body stays system TextEditor for findNavigator; if it is
                        // first responder, pull UIKit text before binding lag.
                        if let body = NovelTextInputCommitter.activeFirstResponder() as? UITextView {
                            body.unmarkText()
                            content = body.text ?? content
                        }
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { save() }
                    }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isFindPresented = true
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel("查找和替换")
                    .disabled(isSaving)
                }
            }
            .overlay {
                if isSaving { ProgressView("正在保存改写") }
            }
        }
        .interactiveDismissDisabled()
    }

    private var hasChanges: Bool {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedContent = content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        return normalizedTitle != version.title || normalizedContent != version.content
    }

    private func save() {
        guard !isSaving else { return }
        guard hasChanges else {
            dismiss()
            return
        }
        guard !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            failureMessage = "请填写完整的章节标题和正文。"
            return
        }
        isSaving = true
        failureMessage = nil
        Task { @MainActor in
            let saved = await viewModel.saveManualRewrite(
                chapterID: version.chapterID,
                title: title,
                content: content
            )
            isSaving = false
            guard saved else {
                failureMessage = viewModel.errorMessage ?? "改写没有保存，请稍后重试。"
                return
            }
            dismiss()
        }
    }

    private func requestDismiss() {
        if hasChanges {
            isConfirmingDiscard = true
        } else {
            dismiss()
        }
    }
}
