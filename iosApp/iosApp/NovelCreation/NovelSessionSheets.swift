import SwiftUI

struct NovelSessionChapterOption: Identifiable, Equatable {
    let selection: NovelChapterSelection
    let version: NovelChapterVersionRecord
    let ordinal: Int

    var id: NovelChapterID { selection.chapterID }

    var displayTitle: String {
        NovelPresentation.chapterDisplayTitle(
            storedTitle: version.title,
            content: version.content,
            ordinal: ordinal
        )
    }
}

enum NovelSessionSheetSubmissionResult: Equatable {
    case completed
    case pending(message: String)
    case failed(message: String)
}

enum NovelDiscussionArchivePreparationResult: Equatable {
    case ready(NovelDiscussionArchiveDraft)
    case failed(String)
}

private struct NovelDiscussionArchiveEditingSnapshot: Equatable {
    let decisions: [NovelDiscussionArchiveDraftDecision]
    let selectedDecisionIDs: Set<UUID>
    let summary: String
}

struct NovelDiscussionArchiveOfferSheet: View {
    @Environment(\.dismiss) private var dismiss
    /// Distill requires a synchronized, idle branch; collection always leaves needsSync.
    let isReady: Bool
    let needsSync: Bool
    let isSyncing: Bool
    let syncFailureMessage: String?
    let onRetrySync: () -> Void
    let onContinue: () -> Void

    var body: some View {
        // 短内容贴内容高度；避免导航容器把 sheet 撑成大白页。
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(AmberTheme.accent)
                Text("收录完成")
                    .font(.headline)
                Spacer(minLength: 0)
            }

            Text("正文已进书。可选把本章已确认的讨论整理成长期记忆，先给你确认再写入。")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)

            if isSyncing {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                    Text("同步剧情中…")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                }
            } else if let syncFailureMessage {
                Label {
                    Text(syncFailureMessage)
                        .font(.footnote)
                        .fixedSize(horizontal: false, vertical: true)
                } icon: {
                    Image(systemName: "exclamationmark.triangle")
                }
                .foregroundStyle(AmberTheme.accentRed)
                Button("重试同步") { onRetrySync() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            } else if needsSync {
                Label("同步完成后可归档", systemImage: "arrow.triangle.2.circlepath")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                Button("开始同步") { onRetrySync() }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            } else if !isReady {
                Label("其他操作结束后可归档", systemImage: "clock")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
            }

            HStack(spacing: 10) {
                Button {
                    dismiss()
                } label: {
                    Text("暂不归档")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                Button {
                    onContinue()
                } label: {
                    Text("归档讨论")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!isReady)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 16)
        .presentationSizing(.fitted)
        .presentationDragIndicator(.visible)
    }
}

struct NovelDiscussionArchiveSheet: View {
    @Environment(\.dismiss) private var dismiss

    let onPrepare: @MainActor () async -> NovelDiscussionArchivePreparationResult
    let onConfirm: @MainActor (
        NovelDiscussionArchiveDraft,
        [NovelDiscussionArchiveDraftDecision],
        String
    ) async -> Bool

    @State private var draft: NovelDiscussionArchiveDraft?
    @State private var decisions: [NovelDiscussionArchiveDraftDecision] = []
    @State private var selectedDecisionIDs: Set<UUID> = []
    @State private var summary = ""
    @State private var preparationFailureMessage: String?
    @State private var submissionFailureMessage: String?
    @State private var isPreparing = false
    @State private var isSubmitting = false
    @State private var preparationTask: Task<Void, Never>?
    @State private var editingBaseline: NovelDiscussionArchiveEditingSnapshot?
    @State private var isConfirmingDiscard = false
    @State private var imeBank = NovelIMEFieldBank()

    var body: some View {
        NavigationStack {
            Form {
                if isPreparing {
                    Section {
                        ProgressView("正在整理本轮讨论")
                    }
                } else if let preparationFailureMessage {
                    Section("整理失败") {
                        Label(preparationFailureMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(AmberTheme.accentRed)
                        Button("重新整理") { prepare() }
                    }
                } else if draft != nil {
                    if let submissionFailureMessage {
                        Section("归档未保存") {
                            Label(submissionFailureMessage, systemImage: "exclamationmark.triangle")
                                .foregroundStyle(AmberTheme.accentRed)
                        }
                    }

                    Section("讨论摘要") {
                        NovelIMETextEditor(
                            text: $summary,
                            placeholder: "讨论摘要",
                            minHeight: 90,
                            bank: imeBank
                        )
                        .frame(minHeight: 90)
                        Text("\(summary.count)/300")
                            .font(.caption)
                            .foregroundStyle(summary.count <= 300 ? AmberTheme.muted : AmberTheme.accentRed)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                    }

                    Section("确认决定") {
                        ForEach($decisions) { $decision in
                            VStack(alignment: .leading, spacing: 10) {
                                Toggle(
                                    "收录此决定",
                                    isOn: selectionBinding(for: decision.id)
                                )
                                NovelIMETextField(
                                    text: $decision.topic,
                                    placeholder: "决定主题",
                                    bank: imeBank
                                )
                                .frame(minHeight: 36)
                                NovelIMETextEditor(
                                    text: $decision.decision,
                                    placeholder: "决定内容",
                                    minHeight: 72,
                                    bank: imeBank
                                )
                                .frame(minHeight: 72)
                                Button(role: .destructive) {
                                    removeDecision(decision.id)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .disabled(isSubmitting)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("归档讨论")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { requestDismiss() }
                    }
                        .disabled(isSubmitting)
                        .confirmationDialog(
                            "放弃归档调整？",
                            isPresented: $isConfirmingDiscard,
                            titleVisibility: .visible
                        ) {
                            Button("放弃更改", role: .destructive) {
                                cancelPreparationAndDismiss()
                            }
                            Button("继续编辑", role: .cancel) {}
                        } message: {
                            Text("尚未归档的摘要和决定修改会丢失。")
                        }
                }
                if draft != nil, preparationFailureMessage == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(confirmedDecisions.isEmpty
                            ? "取消归档"
                            : (submissionFailureMessage == nil ? "确认归档" : "重试保存")) {
                            NovelTextInputCommitter.perform(fieldBank: imeBank) { submit() }
                        }
                        .disabled(isSubmitting)
                    }
                }
            }
            .overlay {
                if isSubmitting {
                    ProgressView("正在保存归档")
                }
            }
        }
        .interactiveDismissDisabled()
        .onAppear { prepare() }
        .onDisappear { preparationTask?.cancel() }
    }

    private var confirmedDecisions: [NovelDiscussionArchiveDraftDecision] {
        decisions.filter { selectedDecisionIDs.contains($0.id) }
    }

    private var canSubmit: Bool {
        if confirmedDecisions.isEmpty { return true }
        let trimmedSummary = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmedSummary.isEmpty &&
            trimmedSummary.count <= 300 &&
            confirmedDecisions.allSatisfy {
                !$0.topic.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                    !$0.decision.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
    }

    private var hasUnsavedChanges: Bool {
        guard let editingBaseline else { return false }
        return editingBaseline != NovelDiscussionArchiveEditingSnapshot(
            decisions: decisions,
            selectedDecisionIDs: selectedDecisionIDs,
            summary: summary
        )
    }

    private func selectionBinding(for id: UUID) -> Binding<Bool> {
        Binding(
            get: { selectedDecisionIDs.contains(id) },
            set: { selected in
                if selected {
                    selectedDecisionIDs.insert(id)
                } else {
                    selectedDecisionIDs.remove(id)
                }
            }
        )
    }

    private func removeDecision(_ id: UUID) {
        decisions.removeAll { $0.id == id }
        selectedDecisionIDs.remove(id)
    }

    private func prepare() {
        guard !isPreparing else { return }
        isPreparing = true
        preparationFailureMessage = nil
        preparationTask = Task { @MainActor in
            let result = await onPrepare()
            guard !Task.isCancelled else {
                isPreparing = false
                preparationTask = nil
                return
            }
            isPreparing = false
            preparationTask = nil
            switch result {
            case .ready(let prepared):
                draft = prepared
                decisions = prepared.decisions
                selectedDecisionIDs = Set(prepared.decisions.map(\.id))
                summary = prepared.summary
                editingBaseline = NovelDiscussionArchiveEditingSnapshot(
                    decisions: prepared.decisions,
                    selectedDecisionIDs: Set(prepared.decisions.map(\.id)),
                    summary: prepared.summary
                )
            case .failed(let message):
                preparationFailureMessage = message
            }
        }
    }

    private func cancelPreparationAndDismiss() {
        preparationTask?.cancel()
        preparationTask = nil
        dismiss()
    }

    private func requestDismiss() {
        if hasUnsavedChanges {
            isConfirmingDiscard = true
        } else {
            cancelPreparationAndDismiss()
        }
    }

    private func submit() {
        guard let draft else { return }
        let confirmed = confirmedDecisions
        guard !confirmed.isEmpty else {
            dismiss()
            return
        }
        guard canSubmit else {
            submissionFailureMessage = "请填写完整的讨论摘要和决定内容。"
            return
        }
        isSubmitting = true
        submissionFailureMessage = nil
        Task { @MainActor in
            let succeeded = await onConfirm(draft, confirmed, summary)
            isSubmitting = false
            if succeeded {
                dismiss()
            } else {
                submissionFailureMessage = "归档没有保存，请检查项目状态后重试。"
            }
        }
    }
}

struct NovelCollectCandidateSheet: View {
    @Environment(\.dismiss) private var dismiss

    let paragraphs: [NovelParagraphRecord]
    let chapters: [NovelSessionChapterOption]
    let nextChapterOrdinal: Int
    /// 非 nil 表示这个候选来自「整章重新生成」,可以替换该章。
    let regenerationTarget: NovelSessionChapterOption?
    let onCompleted: @MainActor (NovelCollectionTarget) -> Void
    let onCollect: @MainActor (
        NovelParagraphSelection,
        NovelCollectionTarget
    ) async -> NovelSessionSheetSubmissionResult
    private let initialSelectedParagraphIDs: Set<NovelParagraphID>
    private let initialEditedText: String
    private let initialTargetChoice: NovelCollectionTargetChoice
    private let initialNextChapterTitle: String

    @State private var selectedParagraphIDs: Set<NovelParagraphID>
    @State private var editedText: String
    @State private var hasEditedText = false
    @State private var targetChoice: NovelCollectionTargetChoice
    @State private var nextChapterTitle: String
    @State private var isSubmitting = false
    @State private var submissionResult: NovelSessionSheetSubmissionResult?
    @State private var isConfirmingDiscard = false
    @State private var imeBank = NovelIMEFieldBank()

    init(
        paragraphs: [NovelParagraphRecord],
        chapters: [NovelSessionChapterOption],
        nextChapterOrdinal: Int,
        regenerationTarget: NovelSessionChapterOption? = nil,
        suggestedGranularity: NovelGenerationGranularity,
        onCompleted: @escaping @MainActor (NovelCollectionTarget) -> Void = { _ in },
        onCollect: @escaping @MainActor (
            NovelParagraphSelection,
            NovelCollectionTarget
        ) async -> NovelSessionSheetSubmissionResult
    ) {
        self.paragraphs = paragraphs
        self.chapters = chapters
        self.nextChapterOrdinal = nextChapterOrdinal
        self.regenerationTarget = regenerationTarget
        self.onCompleted = onCompleted
        self.onCollect = onCollect
        let paragraphIDs = Set(paragraphs.map(\.id))
        let candidateText = paragraphs.map(\.text).joined(separator: "\n\n")
        let targetChoice = NovelCollectionTargetChoice.initial(
            chapterCount: chapters.count,
            granularity: suggestedGranularity,
            hasRegenerationTarget: regenerationTarget != nil
        )
        let nextOrdinal = nextChapterOrdinal
        let nextTitle = NovelPresentation.chapterDisplayTitle(
            storedTitle: "第 \(nextOrdinal) 章",
            content: candidateText,
            ordinal: nextOrdinal
        )
        self.initialSelectedParagraphIDs = paragraphIDs
        self.initialEditedText = candidateText
        self.initialTargetChoice = targetChoice
        self.initialNextChapterTitle = nextTitle
        self._selectedParagraphIDs = State(initialValue: paragraphIDs)
        self._editedText = State(initialValue: candidateText)
        self._targetChoice = State(initialValue: targetChoice)
        self._nextChapterTitle = State(initialValue: nextTitle)
    }

    var body: some View {
        NavigationStack {
            Form {
                submissionSection
                paragraphSection
                    .disabled(isSubmitting || hasDurablePending)
                previewSection
                    .disabled(isSubmitting || hasDurablePending)
                targetSection
                    .disabled(isSubmitting || hasDurablePending)
            }
            .disabled(isSubmitting)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("收录正文")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { requestDismiss() }
                    }
                        .disabled(isSubmitting)
                        .confirmationDialog(
                            "放弃本次收录调整？",
                            isPresented: $isConfirmingDiscard,
                            titleVisibility: .visible
                        ) {
                            Button("放弃更改", role: .destructive) { dismiss() }
                            Button("继续编辑", role: .cancel) {}
                        } message: {
                            Text("尚未收录的正文编辑、段落选择和章节位置会丢失。")
                        }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("收录") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { collect() }
                    }
                        .disabled(isSubmitting || hasDurablePending)
                }
            }
            .overlay {
                if isSubmitting {
                    ProgressView("正在更新正文与剧情状态")
                }
            }
        }
        .interactiveDismissDisabled()
    }

    @ViewBuilder
    private var submissionSection: some View {
        switch submissionResult {
        case .pending(let message):
            Section {
                Label(message, systemImage: "externaldrive.badge.checkmark")
                    .foregroundStyle(AmberTheme.foreground2)
                Button("返回创作页继续") { dismiss() }
            } header: {
                Text("正文已安全保留")
            }
        case .failed(let message):
            Section {
                Label(message, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(AmberTheme.accentRed)
            } header: {
                Text("收录未完成")
            }
        case .completed, nil:
            EmptyView()
        }
    }

    private var paragraphSection: some View {
        Section {
            ForEach(paragraphs, id: \.id) { paragraph in
                Button {
                    toggle(paragraph.id)
                } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: selectedParagraphIDs.contains(paragraph.id)
                            ? "checkmark.square.fill"
                            : "square")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(
                                selectedParagraphIDs.contains(paragraph.id)
                                    ? AmberTheme.accent
                                    : AmberTheme.muted
                            )
                            .frame(width: 24)

                        Text(paragraph.text)
                            .font(.subheadline)
                            .foregroundStyle(AmberTheme.foreground)
                            .lineLimit(5)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(paragraph.text)
                .accessibilityValue(selectedParagraphIDs.contains(paragraph.id) ? "已选择" : "未选择")
            }
        } header: {
            HStack {
                Text("选择段落")
                Spacer()
                Button(allSelected ? "取消全选" : "全选") {
                    setAllSelected(!allSelected)
                }
                .textCase(nil)
            }
        } footer: {
            if hasEditedText {
                Text("调整段落后会保留你的编辑；如需按当前选择重新生成正文，请点“按当前选择重置”。")
            } else {
                Text("默认收录全部段落。未选择的段落仍保留在聊天气泡中。")
            }
        }
    }

    private var previewSection: some View {
        Section {
            NovelIMETextEditor(
                text: editedTextBinding,
                placeholder: "收录前编辑",
                isEnabled: !selectedParagraphIDs.isEmpty && !isSubmitting,
                minHeight: 190,
                bank: imeBank
            )
            .frame(minHeight: 190)
            .accessibilityLabel("收录前编辑")

            if hasEditedText {
                Button {
                    resetEditedText()
                } label: {
                    Label("按当前选择重置", systemImage: "arrow.counterclockwise")
                }
                .disabled(selectedParagraphIDs.isEmpty || isSubmitting)
            }
        } header: {
            Text("收录前编辑")
        } footer: {
            Text("这里的修改只影响本次收录，不会改写原聊天气泡。")
        }
    }

    @ViewBuilder
    private var targetSection: some View {
        Section("章节位置") {
            if !chapters.isEmpty {
                Picker("收录方式", selection: $targetChoice) {
                    if let regenerationTarget {
                        Text("替换\(regenerationTarget.displayTitle)")
                            .tag(NovelCollectionTargetChoice.replaceChapter)
                    }
                    Text(appendCurrentLabel).tag(NovelCollectionTargetChoice.appendCurrent)
                    Text("新开第 \(nextChapterOrdinal) 章")
                        .tag(NovelCollectionTargetChoice.createNext)
                }
                .pickerStyle(.segmented)
                .disabled(isSubmitting)
            }

            if targetChoice == .replaceChapter, let regenerationTarget {
                LabeledContent("将替换", value: regenerationTarget.displayTitle)
                Text("原版本会保留在该章的版本历史里。因为重写允许改变剧情，"
                    + "回到旧版本需要在版本历史里用「以手工编辑恢复」，不能直接回滚。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            } else if targetChoice == .appendCurrent, let chapter = chapters.last {
                LabeledContent("当前章节", value: chapter.displayTitle)
            } else {
                NovelIMETextField(
                    text: $nextChapterTitle,
                    placeholder: "章节标题",
                    isEnabled: !isSubmitting,
                    bank: imeBank
                )
                .frame(minHeight: 36)
            }
        }
    }

    private var allSelected: Bool {
        !paragraphs.isEmpty && selectedParagraphIDs.count == paragraphs.count
    }

    private var canCollect: Bool {
        guard !selectedParagraphIDs.isEmpty,
              !editedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        if targetChoice == .createNext {
            return !nextChapterTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if targetChoice == .replaceChapter {
            return regenerationTarget != nil
        }
        return chapters.last != nil
    }

    private var hasDurablePending: Bool {
        if case .pending = submissionResult { return true }
        return false
    }

    private var shouldConfirmDiscard: Bool {
        hasUnsavedChanges && !hasDurablePending
    }

    private var hasUnsavedChanges: Bool {
        selectedParagraphIDs != initialSelectedParagraphIDs ||
            editedText != initialEditedText ||
            targetChoice != initialTargetChoice ||
            nextChapterTitle != initialNextChapterTitle
    }

    private var appendCurrentLabel: String {
        guard let chapter = chapters.last else { return "并入当前章" }
        let title = chapter.version.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.isEmpty || title == "第 \(chapter.ordinal) 章" {
            return "并入第 \(chapter.ordinal) 章"
        }
        return "并入第 \(chapter.ordinal) 章《\(title)》"
    }

    private var selectedText: String {
        paragraphs
            .filter { selectedParagraphIDs.contains($0.id) }
            .map(\.text)
            .joined(separator: "\n\n")
    }

    private var editedTextBinding: Binding<String> {
        Binding(
            get: { editedText },
            set: { value in
                editedText = value
                hasEditedText = value != selectedText
            }
        )
    }

    private func toggle(_ paragraphID: NovelParagraphID) {
        if selectedParagraphIDs.contains(paragraphID) {
            selectedParagraphIDs.remove(paragraphID)
        } else {
            selectedParagraphIDs.insert(paragraphID)
        }
        refreshEditedTextAfterSelectionChange()
    }

    private func setAllSelected(_ selected: Bool) {
        selectedParagraphIDs = selected ? Set(paragraphs.map(\.id)) : []
        refreshEditedTextAfterSelectionChange()
    }

    private func refreshEditedTextAfterSelectionChange() {
        guard !hasEditedText else { return }
        // Mid-IME composition may not yet be reflected in `editedText`, so
        // hasEditedText is still false. Resetting here would clobber the
        // TextEditor and drop the last marked glyphs.
        if imeBank.hasAnyMarkedText || NovelTextInputCommitter.hasMarkedText() {
            NovelTextInputCommitter.perform(fieldBank: imeBank) {
                if editedText != selectedText {
                    hasEditedText = true
                } else {
                    resetEditedText()
                }
            }
            return
        }
        resetEditedText()
    }

    private func resetEditedText() {
        editedText = selectedText
        hasEditedText = false
    }

    private func collect() {
        guard canCollect else {
            submissionResult = .failed(message: "请选择正文并填写完整的章节信息。")
            return
        }
        let orderedIDs = paragraphs
            .filter { selectedParagraphIDs.contains($0.id) }
            .map(\.id)
        let selection = NovelParagraphSelection(
            paragraphIDs: orderedIDs,
            editedText: editedText == selectedText ? nil : editedText
        )
        let target: NovelCollectionTarget
        switch targetChoice {
        case .appendCurrent:
            guard let chapterID = chapters.last?.selection.chapterID else { return }
            target = .appendToChapter(chapterID)
        case .replaceChapter:
            guard let chapterID = regenerationTarget?.selection.chapterID else { return }
            target = .replaceChapter(chapterID)
        case .createNext:
            target = .createNextChapter(
                chapterID: NovelChapterID(),
                title: nextChapterTitle.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        }
        isSubmitting = true
        submissionResult = nil
        Task { @MainActor in
            let result = await onCollect(selection, target)
            isSubmitting = false
            submissionResult = result
            if result == .completed {
                onCompleted(target)
                dismiss()
            }
        }
    }

    private func requestDismiss() {
        if shouldConfirmDiscard {
            isConfirmingDiscard = true
        } else {
            dismiss()
        }
    }

}

enum NovelCollectionTargetChoice: String, Hashable {
    case appendCurrent
    case createNext
    /// 「整章重新生成」专用:替换来源章节,而不是追加或新建。
    case replaceChapter

    static func initial(
        chapterCount: Int,
        granularity: NovelGenerationGranularity,
        hasRegenerationTarget: Bool
    ) -> NovelCollectionTargetChoice {
        // 重新生成的候选默认就是替换来源章——那是发起这次生成的本意。
        if hasRegenerationTarget { return .replaceChapter }
        guard chapterCount > 0 else { return .createNext }
        return granularity == .wholeChapter ? .createNext : .appendCurrent
    }
}

struct NovelSessionForkSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelSessionViewModel
    let branchName: String
    let checkpointID: NovelCheckpointID
    let onCreated: (String) -> Void

    @State private var name: String
    @State private var isSubmitting = false
    @State private var failureMessage: String?
    @State private var imeBank = NovelIMEFieldBank()

    init(
        viewModel: NovelSessionViewModel,
        branchName: String,
        checkpointID: NovelCheckpointID,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        self.viewModel = viewModel
        self.branchName = branchName
        self.checkpointID = checkpointID
        self.onCreated = onCreated
        self._name = State(initialValue: "\(branchName) · 新走向")
    }

    var body: some View {
        NavigationStack {
            Form {
                if let failureMessage {
                    Section("Fork 未完成") {
                        Label(failureMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(AmberTheme.accentRed)
                    }
                }

                Section("新分支") {
                    NovelIMETextField(
                        text: $name,
                        placeholder: "分支名称",
                        bank: imeBank
                    )
                    .frame(minHeight: 36)
                }

                Section {
                    Label("从这条创作记录对应的检查点开始", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.foreground2)
                } footer: {
                    Text("新分支只继承该检查点以前的正文、剧情状态、分支设定和创作对话。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("Fork 剧情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { create() }
                    }
                        .disabled(
                            name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                isSubmitting
                        )
                }
            }
            .overlay {
                if isSubmitting { ProgressView() }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
    }

    private func create() {
        isSubmitting = true
        failureMessage = nil
        viewModel.clearError()
        let branchName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        Task { @MainActor in
            let branchID = await viewModel.forkFromCheckpoint(checkpointID, name: branchName)
            isSubmitting = false
            guard branchID != nil else {
                failureMessage = viewModel.errorMessage ?? "分支没有创建完成，请重新载入项目后再试。"
                return
            }
            dismiss()
            onCreated(branchName)
        }
    }
}

struct NovelWritingContextSheet: View {
    @Environment(\.dismiss) private var dismiss

    let workspace: NovelCreationViewModel
    let session: NovelSessionViewModel
    let sharedSettings: IOSSharedSettingsStore
    let mode: NovelSessionMode
    let granularity: NovelGenerationGranularity
    let userText: String
    let onEditWritingRequirements: () -> Void
    let onEditPolishPreference: () -> Void
    let onApply: (NovelInjectionOverrides, Int) -> Void

    @State private var selectedTab = SheetTab.preferences
    @State private var budgetTokens: Int
    @State private var materialChoices: [NovelMaterialID: MaterialChoice]
    @State private var previewSignature: String?
    @State private var selectedMode: NovelCollaborationMode
    @State private var pauseOnBlockingContinuity: Bool
    @State private var planPlacement: String
    @State private var planGoal: String
    @State private var planMustHappen: String
    @State private var planMustNotHappen: String
    @State private var planEndingHook: String
    @State private var planVisibleFacts: String
    @State private var upcomingArcBeats: String
    @State private var modeSwitchMessage: String?
    @State private var pauseToggleMessage: String?
    @State private var planMessage: String?
    @State private var isPresentingGhostwriteRevision = false
    @State private var planMessageIsError = false
    @State private var arcMessage: String?
    @State private var arcMessageIsError = false
    @State private var confirmClearPlan = false
    @State private var confirmClearArc = false
    /// UIKit-backed plan/arc fields; save flushes marked text into bindings here.
    @State private var planFieldBank = NovelIMEFieldBank()
    @State private var planFieldsDirty = false
    @State private var arcFieldsDirty = false
    @State private var isReloadingPlanFields = false
    @State private var isReloadingArcFields = false
    /// 根据前文生成草稿本章计划（模型调用中）。
    @State private var isProposingPlanDraft = false

    init(
        workspace: NovelCreationViewModel,
        session: NovelSessionViewModel,
        sharedSettings: IOSSharedSettingsStore,
        mode: NovelSessionMode,
        granularity: NovelGenerationGranularity,
        userText: String,
        overrides: NovelInjectionOverrides,
        budgetTokens: Int,
        onEditWritingRequirements: @escaping () -> Void,
        onEditPolishPreference: @escaping () -> Void,
        onApply: @escaping (NovelInjectionOverrides, Int) -> Void
    ) {
        self.workspace = workspace
        self.session = session
        self.sharedSettings = sharedSettings
        self.mode = mode
        self.granularity = granularity
        self.userText = userText
        self.onEditWritingRequirements = onEditWritingRequirements
        self.onEditPolishPreference = onEditPolishPreference
        self.onApply = onApply
        self._budgetTokens = State(initialValue: budgetTokens)
        var choices: [NovelMaterialID: MaterialChoice] = [:]
        for materialID in overrides.forceIncludeMaterialIDs {
            choices[materialID] = .include
        }
        for materialID in overrides.forceExcludeMaterialIDs {
            choices[materialID] = .exclude
        }
        self._materialChoices = State(initialValue: choices)
        self._previewSignature = State(initialValue: nil)
        let existingMode = workspace.projectSnapshot?.project.collaborationMode ?? .cocreation
        self._selectedMode = State(initialValue: existingMode)
        self._pauseOnBlockingContinuity = State(
            initialValue: workspace.projectSnapshot?.project.pauseGhostwriteOnBlockingContinuity ?? true
        )
        let existingPlan = workspace.selectedBranchID.flatMap {
            workspace.projectSnapshot?.chapterPlan(for: $0)
        }
        self._planPlacement = State(initialValue: existingPlan?.outlinePlacement ?? "")
        self._planGoal = State(initialValue: existingPlan?.goalAndConflict ?? "")
        self._planMustHappen = State(
            initialValue: existingPlan?.mustHappen.joined(separator: "\n") ?? ""
        )
        self._planMustNotHappen = State(
            initialValue: existingPlan?.mustNotHappen.joined(separator: "\n") ?? ""
        )
        self._planEndingHook = State(initialValue: existingPlan?.endingHook ?? "")
        self._planVisibleFacts = State(
            initialValue: existingPlan?.visibleFacts.joined(separator: "\n") ?? ""
        )
        let existingArc = workspace.selectedBranchID.flatMap {
            workspace.projectSnapshot?.upcomingArc(for: $0)
        }
        self._upcomingArcBeats = State(
            initialValue: existingArc?.beats.joined(separator: "\n") ?? ""
        )
        self._modeSwitchMessage = State(initialValue: nil)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("项目控制", selection: $selectedTab) {
                    ForEach(SheetTab.allCases) { tab in
                        Text(tab.title).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                switch selectedTab {
                case .preferences:
                    preferencesList
                case .context:
                    contextList
                }
            }
            .background(AmberTheme.background)
            .navigationTitle("项目控制")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: ContextRoute.self) { route in
                switch route {
                case .materials(let category):
                    materialChoicesList(category)
                case .preview:
                    contextPreview
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        // Flush UIKit plan/arc fields before tear-down.
                        NovelTextInputCommitter.perform(fieldBank: planFieldBank) {
                            dismiss()
                        }
                    }
                }
                ToolbarItemGroup(placement: .confirmationAction) {
                    if collaborationMode == .ghostwrite {
                        // 代笔模式：右上角是代笔操作入口（比预览实用得多）。
                        // 代笔进行中→暂停；未进行→开始/继续/下一批。
                        if session.isGhostwriting {
                            Button("暂停") {
                                session.pauseGhostwrite()
                            }
                        } else if session.ghostwriteProgress?.pauseReason == .planProposedForNewBatch {
                            // 新批首章计划已自动拟定，等用户确认后连写。
                            Button("确认计划，开始写") {
                                _ = session.continueGhostwriteChapter()
                            }
                            .disabled(!session.canStartGhostwriteChapter)
                        } else if shouldShowContinueGhostwrite {
                            Button(continueGhostwriteButtonTitle) {
                                _ = session.continueGhostwriteChapter()
                            }
                            .disabled(!session.canStartGhostwriteChapter)
                        } else {
                            let isNextBatch = session.ghostwriteProgress?.pauseReason == .batchCompleted
                                || session.ghostwriteProgress?.pauseReason == .chapterCompleted
                            Button(isNextBatch ? "代笔下一批" : "开始代笔") {
                                let n = NovelGhostwriteBatch.clamp(session.ghostwriteTargetChapterCount)
                                _ = session.startGhostwriteChapter(targetChapterCount: n)
                            }
                            .disabled(!session.canStartGhostwriteChapter)
                        }
                    } else {
                        // 共创模式：预览注入计划。
                        Button("预览") {
                            NovelTextInputCommitter.perform(fieldBank: planFieldBank) {
                                preview()
                            }
                        }
                        .disabled(!canPreview || workspace.isPerforming)
                    }
                }
            }
            .overlay {
                if workspace.isPerforming { ProgressView() }
            }
        }
        .interactiveDismissDisabled(workspace.isPerforming || isProposingPlanDraft)
        .onDisappear {
            // 面板关闭（下滑或切走）时兜底：计划有未保存改动 → 存草稿；
            // 预算兜底回写（滑块拖动中不触发，关闭时落定）。
            // 资料覆盖已在勾选时即时回写，无需重复。
            // 正在根据前文生成时不写本地 dirty，避免盖掉模型刚落盘的草稿。
            NovelTextInputCommitter.perform(fieldBank: planFieldBank) {
                if planFieldsDirty, !isProposingPlanDraft {
                    Task { await saveChapterPlan(status: .draft) }
                }
                onApply(overrides, budgetTokens)
            }
        }
        .confirmationDialog(
            "清除本章计划？",
            isPresented: $confirmClearPlan,
            titleVisibility: .visible
        ) {
            Button("清除计划", role: .destructive) {
                NovelTextInputCommitter.perform(fieldBank: planFieldBank) {
                    Task { await clearChapterPlan() }
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("清除后要重新写好并确认，才能继续代笔写整章。")
        }
        .confirmationDialog(
            "清除往后几章的备注？",
            isPresented: $confirmClearArc,
            titleVisibility: .visible
        ) {
            Button("清除备注", role: .destructive) {
                NovelTextInputCommitter.perform(fieldBank: planFieldBank) {
                    Task { await clearUpcomingArc() }
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("清除后，写整章时就不再参考这些备注。")
        }
        .sheet(isPresented: $isPresentingGhostwriteRevision) {
            let receipt = session.ghostwriteProgress?.lastFailureReceipt
            NovelGhostwriteRevisionSheet(
                recommendedBrief: receipt?.recommendedRevisionBrief() ?? "",
                // 中断摘要用审稿意见，不把离页/重启元信息塞进「原因」。
                detail: {
                    let summary = receipt?.summary.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let summary, !summary.isEmpty { return summary }
                    let missing = receipt?.missingMustHappen.filter {
                        !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    } ?? []
                    if !missing.isEmpty {
                        return "须补写：\n" + missing.map { "· \($0)" }.joined(separator: "\n")
                    }
                    return nil
                }(),
                onCancel: { isPresentingGhostwriteRevision = false },
                onStart: { brief in
                    let started = session.startGhostwriteRevision(brief: brief)
                    if started {
                        isPresentingGhostwriteRevision = false
                    }
                    return started
                }
            )
        }
    }

    private var preferencesList: some View {
        let blockers = ghostwriteSwitchBlockers
        return List {
            Section {
                Picker("创作模式", selection: $selectedMode) {
                    ForEach(NovelCollaborationMode.allCases, id: \.self) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(!workspace.canMutate || workspace.isPerforming || session.isGhostwriting)
                .onChange(of: selectedMode) { _, newMode in
                    Task { await selectCollaborationMode(newMode) }
                }
                .onChange(of: collaborationMode) { _, newMode in
                    if selectedMode != newMode {
                        selectedMode = newMode
                    }
                }

                if session.isGhostwriting {
                    Text("代笔进行中，暂停后可切回共创。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                }

                if let modeSwitchMessage, !modeSwitchMessage.isEmpty {
                    Label(modeSwitchMessage, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.accentRed)
                }

                if collaborationMode == .cocreation, !blockers.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("切入代笔还需：")
                            .font(.footnote.weight(.semibold))
                        ForEach(blockers, id: \.self) { issue in
                            Text("· \(issue.displayName)")
                                .font(.footnote)
                        }
                    }
                    .foregroundStyle(AmberTheme.muted)
                }
            } header: {
                Text("创作模式")
            } footer: {
                Text(modeSectionFooter)
            }

            if collaborationMode == .ghostwrite {
                Section {
                    if let progress = session.ghostwriteProgress {
                        LabeledContent("状态") {
                            Text(progress.statusLabel)
                                .multilineTextAlignment(.trailing)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        LabeledContent("进度") {
                            // 进度只报步骤码 + 已收录；章序号留给「状态」，避免两行两套 x/5。
                            Text(progress.boardStepSummary)
                                .multilineTextAlignment(.trailing)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        LabeledContent("本章计划", value: planStatusLabel)
                        // 多章时「进度」已有「已收 k/N」；仅待同步计章时单独标出。
                        if progress.pendingSyncChapterCredit {
                            let counted = progress.completedChapterCount + 1
                            LabeledContent("本批已收录", value: "\(counted) 章（待同步计章）")
                        } else if progress.targetChapterCount == 1 {
                            LabeledContent(
                                "本批已收录",
                                value: "\(progress.completedChapterCount) 章"
                            )
                        }
                        LabeledContent("审稿模型", value: reviewModelLabel)
                        LabeledContent("往后几章", value: upcomingArcStatusLabel)
                        if let detail = progress.detailMessage, !detail.isEmpty {
                            let detailIsError = progress.phase == .failed
                                || progress.pauseReason == .healBudgetExhausted
                                || (
                                    progress.phase == .paused
                                        && progress.pauseReason != .userPaused
                                        && progress.pauseReason != .cancelled
                                )
                            if detailIsError {
                                // 与同文件同步失败 Label 一致：顶对齐 + 多行可长。
                                HStack(alignment: .top, spacing: 8) {
                                    Image(systemName: "exclamationmark.triangle")
                                        .font(.footnote)
                                        .foregroundStyle(AmberTheme.accentRed)
                                    Text(detail)
                                        .font(.footnote)
                                        .foregroundStyle(AmberTheme.accentRed)
                                        .multilineTextAlignment(.leading)
                                        .fixedSize(horizontal: false, vertical: true)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .padding(.top, 2)
                                .accessibilityElement(children: .combine)
                            } else {
                                Text(detail)
                                    .font(.footnote)
                                    .foregroundStyle(AmberTheme.muted)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    } else {
                        LabeledContent("本章计划", value: planStatusLabel)
                        LabeledContent("审稿模型", value: reviewModelLabel)
                        LabeledContent("往后几章", value: upcomingArcStatusLabel)
                        Text("先确认本章计划，再开始代笔。可在下方「本章计划」一键根据前文生成草稿；多章时后续计划会自动拟定。")
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                    }
                } header: {
                    Text("代笔进度")
                } footer: {
                    Text("只读；暂时看不到费用明细。")
                }

                Section {
                    Toggle("连续性出现「严重」问题时暂停", isOn: $pauseOnBlockingContinuity)
                        .disabled(!workspace.canMutate || workspace.isPerforming || session.isGhostwriting)
                        .onChange(of: pauseOnBlockingContinuity) { _, enabled in
                            Task { await setPauseOnBlockingContinuity(enabled) }
                        }
                        .onChange(of: storedPauseOnBlockingContinuity) { _, enabled in
                            if pauseOnBlockingContinuity != enabled {
                                pauseOnBlockingContinuity = enabled
                            }
                        }
                    if let pauseToggleMessage, !pauseToggleMessage.isEmpty {
                        Label(pauseToggleMessage, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.accentRed)
                    }

                    Stepper(
                        value: Binding(
                            get: { ghostwriteDisplayedTargetCount },
                            set: { session.ghostwriteTargetChapterCount = NovelGhostwriteBatch.clamp($0) }
                        ),
                        in: NovelGhostwriteBatch.minChapterCount...NovelGhostwriteBatch.maxChapterCount
                    ) {
                        Text(
                            shouldShowContinueGhostwrite
                                ? "本批固定 \(ghostwriteDisplayedTargetCount) 章"
                                : "本批目标 \(ghostwriteDisplayedTargetCount) 章"
                        )
                    }
                    // 进行中或本批未终态续跑：N 已锁定，禁止改 Stepper 误导用户。
                    .disabled(
                        session.isGhostwriting
                            || workspace.isPerforming
                            || shouldShowContinueGhostwrite
                    )
                    .accessibilityLabel("本批目标章数")
                    .accessibilityValue("\(ghostwriteDisplayedTargetCount) 章")

                    if !session.isGhostwriting,
                       let blocker = session.ghostwriteBlocker,
                       !session.canStartGhostwriteChapter {
                        Text(session.ghostwriteReadinessIssue?.displayName ?? blocker.displayName)
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                    }

                    // 启动/续跑失败等操作错误：进度区未必有对应 detail，这里兜底露出。
                    // 与红色 detail 相同则不重复渲染。
                    if let operationError = session.operationErrorMessage,
                       !operationError.isEmpty,
                       operationError != session.ghostwriteProgress?.detailMessage {
                        Label(operationError, systemImage: "exclamationmark.triangle")
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.accentRed)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Group {
                        if session.isGhostwriting {
                            Button {
                                session.pauseGhostwrite()
                            } label: {
                                Text("暂停")
                                    .frame(maxWidth: .infinity, minHeight: 44)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.regular)
                            .contentShape(Rectangle())
                        } else if session.ghostwriteProgress?.pauseReason == .planProposedForNewBatch {
                            // 与右上角按钮同文案同动作。
                            Button {
                                _ = session.continueGhostwriteChapter()
                            } label: {
                                Text("确认计划，开始写")
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.regular)
                            .contentShape(Rectangle())
                            .disabled(!session.canStartGhostwriteChapter)
                        } else if shouldShowContinueGhostwrite {
                            if session.ghostwriteProgress?.shouldOfferRevisionSheet == true {
                                Button {
                                    isPresentingGhostwriteRevision = true
                                } label: {
                                    Text("按审稿意见润修")
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.85)
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.regular)
                                .contentShape(Rectangle())
                                .disabled(!session.canStartGhostwriteChapter)

                                Button {
                                    _ = session.continueGhostwriteChapter()
                                } label: {
                                    Text(continueGhostwriteButtonTitle)
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.regular)
                                .contentShape(Rectangle())
                                .disabled(!session.canStartGhostwriteChapter)
                            } else {
                                Button {
                                    _ = session.continueGhostwriteChapter()
                                } label: {
                                    Text(continueGhostwriteButtonTitle)
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                }
                                .buttonStyle(.borderedProminent)
                                .controlSize(.regular)
                                .contentShape(Rectangle())
                                .disabled(!session.canStartGhostwriteChapter)
                            }
                        } else {
                            let n = NovelGhostwriteBatch.clamp(session.ghostwriteTargetChapterCount)
                            let isStartingNextBatch = session.ghostwriteProgress?.pauseReason == .batchCompleted
                                || session.ghostwriteProgress?.pauseReason == .chapterCompleted
                            Button {
                                _ = session.startGhostwriteChapter(targetChapterCount: n)
                            } label: {
                                Text(isStartingNextBatch
                                    ? (n == 1 ? "代笔下一章" : "代笔下一批 · \(n) 章")
                                    : (n == 1 ? "开始代笔本章" : "开始代笔 · \(n) 章")
                                )
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                            }
                            .buttonStyle(.borderedProminent)
                            .controlSize(.regular)
                            .contentShape(Rectangle())
                            .disabled(!session.canStartGhostwriteChapter)
                        }
                    }
                } header: {
                    Text(ghostwriteAdvanceSectionTitle)
                } footer: {
                    Text(ghostwriteAdvanceSectionFooter)
                }
            }

            Section {
                LabeledContent("计划状态", value: planStatusLabel)

                VStack(alignment: .leading, spacing: 6) {
                    Text("与总纲的位置").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextField(
                        text: planPlacementBinding,
                        placeholder: "例如：第 3 章",
                        isEnabled: canEditChapterPlan,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 36)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("目标与冲突").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextEditor(
                        text: planGoalBinding,
                        placeholder: "本章要解决什么",
                        isEnabled: canEditChapterPlan,
                        minHeight: 88,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 88)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("必发生（每行一条）").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextEditor(
                        text: planMustHappenBinding,
                        placeholder: "至少一条",
                        isEnabled: canEditChapterPlan,
                        minHeight: 72,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 72)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("禁止发生（每行一条）").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextEditor(
                        text: planMustNotHappenBinding,
                        placeholder: "可空",
                        isEnabled: canEditChapterPlan,
                        minHeight: 64,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 64)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("章末钩子").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextEditor(
                        text: planEndingHookBinding,
                        placeholder: "可空",
                        isEnabled: canEditChapterPlan,
                        minHeight: 56,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 56)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("POV 可见要点（每行一条）").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextEditor(
                        text: planVisibleFactsBinding,
                        placeholder: "可空",
                        isEnabled: canEditChapterPlan,
                        minHeight: 64,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 64)
                }

                if let planMessage, !planMessage.isEmpty {
                    Label(
                        planMessage,
                        systemImage: planMessageIsError
                            ? "exclamationmark.triangle"
                            : "checkmark.circle.fill"
                    )
                    .font(.footnote)
                    .foregroundStyle(planMessageIsError ? AmberTheme.accentRed : AmberTheme.muted)
                }

                if canShowProposePlanDraft {
                    Button {
                        Task { await proposeChapterPlanDraft() }
                    } label: {
                        HStack(spacing: 8) {
                            if isProposingPlanDraft {
                                ProgressView()
                                    .controlSize(.small)
                            }
                            Text(proposePlanDraftButtonTitle)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.regular)
                    .contentShape(Rectangle())
                    .disabled(!canProposePlanDraft)
                    .accessibilityLabel(proposePlanDraftButtonTitle)
                }

                HStack(spacing: 12) {
                    Button("保存草稿") {
                        commitPlanFieldsThen {
                            Task { await saveChapterPlan(status: .draft) }
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                    .disabled(!canEditChapterPlan || isProposingPlanDraft)

                    Button("确认计划") {
                        commitPlanFieldsThen {
                            Task { await saveChapterPlan(status: .confirmed) }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                    .disabled(!canEditChapterPlan || isProposingPlanDraft)

                    Spacer(minLength: 0)

                    if currentChapterPlan != nil {
                        Button("清除", role: .destructive) {
                            confirmClearPlan = true
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                        .disabled(!canEditChapterPlan || isProposingPlanDraft)
                    }
                }
            } header: {
                Text("本章计划")
            } footer: {
                Text(chapterPlanSectionFooter)
            }

            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("后面几章想往哪走（每行一条）").font(.footnote).foregroundStyle(AmberTheme.muted)
                    NovelIMETextEditor(
                        text: upcomingArcBeatsBinding,
                        placeholder: "例如：使者身份曝光",
                        isEnabled: canEditUpcomingArc,
                        minHeight: 96,
                        bank: planFieldBank
                    )
                    .frame(minHeight: 96)
                }

                if let arcMessage, !arcMessage.isEmpty {
                    Label(
                        arcMessage,
                        systemImage: arcMessageIsError
                            ? "exclamationmark.triangle"
                            : "checkmark.circle.fill"
                    )
                    .font(.footnote)
                    .foregroundStyle(arcMessageIsError ? AmberTheme.accentRed : AmberTheme.muted)
                }

                HStack(spacing: 12) {
                    Button("保存") {
                        commitPlanFieldsThen {
                            Task { await saveUpcomingArc() }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                    .disabled(!canEditUpcomingArc)

                    Spacer(minLength: 0)

                    if currentUpcomingArc != nil {
                        Button("清除", role: .destructive) {
                            confirmClearArc = true
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                        .disabled(!canEditUpcomingArc)
                    }
                }
            } header: {
                Text("往后几章")
            } footer: {
                Text("最多 \(NovelUpcomingArcRecord.maxBeats) 条备注；写整章时会参考，不替代本章计划。")
            }

            Section("写作偏好") {
                Button {
                    applyDraftBeforeTransition(onEditWritingRequirements)
                } label: {
                    NovelSettingsRow(
                        systemImage: "text.badge.checkmark",
                        title: "写作要求",
                        value: hasWritingRequirements ? "已设置" : "未设置",
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
                .disabled(!workspace.canMutate)

                Button {
                    applyDraftBeforeTransition(onEditPolishPreference)
                } label: {
                    NovelSettingsRow(
                        systemImage: "wand.and.sparkles",
                        title: "整章润色偏好",
                        value: hasPolishPreference ? "已设置" : "未设置",
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
                .disabled(!workspace.canMutate)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AmberTheme.background)
        .onChange(of: chapterPlanFieldSyncToken) { _, newToken in
            // Local dirty edits win over snapshot echo. Also never clobber IME.
            if planFieldsDirty || planFieldBank.hasAnyMarkedText
                || NovelTextInputCommitter.hasMarkedText() {
                if newToken == "none", !planFieldBank.hasAnyMarkedText {
                    // Plan cleared externally while we were not composing.
                    reloadPlanFieldsFromWorkspace()
                }
                return
            }
            reloadPlanFieldsFromWorkspace()
        }
        .onChange(of: upcomingArcFieldSyncToken) { _, newToken in
            if arcFieldsDirty || planFieldBank.hasAnyMarkedText
                || NovelTextInputCommitter.hasMarkedText() {
                if newToken == "none", !planFieldBank.hasAnyMarkedText {
                    reloadUpcomingArcFromWorkspace()
                }
                return
            }
            reloadUpcomingArcFromWorkspace()
        }
    }

    private var planPlacementBinding: Binding<String> {
        Binding(
            get: { planPlacement },
            set: { newValue in
                planPlacement = newValue
                if !isReloadingPlanFields { planFieldsDirty = true }
            }
        )
    }

    private var planGoalBinding: Binding<String> {
        Binding(
            get: { planGoal },
            set: { newValue in
                planGoal = newValue
                if !isReloadingPlanFields { planFieldsDirty = true }
            }
        )
    }

    private var planMustHappenBinding: Binding<String> {
        Binding(
            get: { planMustHappen },
            set: { newValue in
                planMustHappen = newValue
                if !isReloadingPlanFields { planFieldsDirty = true }
            }
        )
    }

    private var planMustNotHappenBinding: Binding<String> {
        Binding(
            get: { planMustNotHappen },
            set: { newValue in
                planMustNotHappen = newValue
                if !isReloadingPlanFields { planFieldsDirty = true }
            }
        )
    }

    private var planEndingHookBinding: Binding<String> {
        Binding(
            get: { planEndingHook },
            set: { newValue in
                planEndingHook = newValue
                if !isReloadingPlanFields { planFieldsDirty = true }
            }
        )
    }

    private var planVisibleFactsBinding: Binding<String> {
        Binding(
            get: { planVisibleFacts },
            set: { newValue in
                planVisibleFacts = newValue
                if !isReloadingPlanFields { planFieldsDirty = true }
            }
        )
    }

    private var upcomingArcBeatsBinding: Binding<String> {
        Binding(
            get: { upcomingArcBeats },
            set: { newValue in
                upcomingArcBeats = newValue
                if !isReloadingArcFields { arcFieldsDirty = true }
            }
        )
    }

    private func commitPlanFieldsThen(_ action: @escaping @MainActor () -> Void) {
        // Synchronous UIKit flush so save reads the last marked glyphs.
        NovelTextInputCommitter.perform(fieldBank: planFieldBank, action)
    }

    private var collaborationMode: NovelCollaborationMode {
        workspace.projectSnapshot?.project.collaborationMode ?? .cocreation
    }

    private var storedPauseOnBlockingContinuity: Bool {
        workspace.projectSnapshot?.project.pauseGhostwriteOnBlockingContinuity ?? true
    }

    /// Workspace 合同身份变化时（清除 / 换稿）驱动本地字段回填。
    private var chapterPlanFieldSyncToken: String {
        guard let plan = currentChapterPlan else { return "none" }
        return "\(plan.id.rawValue.uuidString)|\(plan.contentDigest)|\(plan.status.rawValue)"
    }

    private var modeSectionFooter: String {
        var parts = [collaborationMode.shortSummary]
        if collaborationMode == .ghostwrite {
            if session.ghostwriteProgress?.pauseReason == .planProposedForNewBatch {
                parts.append("已自动拟定下一章计划。确认后开始写，批内后续章节全自动连写。")
            } else if shouldShowContinueGhostwrite {
                parts.append("本批未完成：可继续；质量问题会自动改写几次，仍不过再停住。")
            } else if session.isGhostwriting {
                parts.append("代笔进行中，可在下方暂停。")
            } else if session.ghostwriteProgress?.pauseReason == .batchCompleted
                        || session.ghostwriteProgress?.pauseReason == .chapterCompleted {
                parts.append("上一批已完成。在下方「代笔」区点按钮开始下一批。")
            } else {
                parts.append("可用「开始代笔」按批自动写整章并验收收录；也可以继续自己点。")
            }
        }
        return parts.joined(separator: " ")
    }

    /// 与 `NovelGhostwriteProgress.shouldContinueSameBatch` 一致：完批/取消后显示「开始」。
    private var shouldShowContinueGhostwrite: Bool {
        guard let progress = session.ghostwriteProgress else { return false }
        return progress.shouldContinueSameBatch
    }

    private var ghostwriteAdvanceSectionTitle: String {
        if session.isGhostwriting { return "代笔进行中" }
        // planProposedForNewBatch 优先级在 shouldShowContinueGhostwrite 之前，
        // 与 toolbar 按钮分支顺序一致，避免文案矛盾。
        if session.ghostwriteProgress?.pauseReason == .planProposedForNewBatch {
            return "确认计划后开始写"
        }
        if shouldShowContinueGhostwrite { return "继续本批代笔" }
        // 完批/完章后：用户看到的应是「下一批」而非看起来像初始的「开始代笔」。
        if session.ghostwriteProgress?.pauseReason == .batchCompleted {
            return "开启下一批代笔"
        }
        if session.ghostwriteProgress?.pauseReason == .chapterCompleted {
            return "代笔下一章"
        }
        return "开始代笔"
    }

    private var ghostwriteAdvanceSectionFooter: String {
        if session.ghostwriteProgress?.pauseReason == .planProposedForNewBatch {
            return "已自动拟定下一章计划。确认后开始写本章，批内后续章节全自动连写。"
        }
        if shouldShowContinueGhostwrite {
            if session.ghostwriteProgress?.shouldOfferRevisionSheet == true {
                return "建议先「按审稿意见润修」（可改要求）；也可整章重写或先改本章计划。不会用旧稿再验。"
            }
            if session.ghostwriteProgress?.mustRewriteCandidateOnResume == true {
                return "继续将重写本章，不会用同一篇旧稿再验收。"
            }
            return "继续本批：先处理同步或拟定计划，再往下写。"
        }
        // 完批后明确告诉用户：上一批已完成，点按钮开始下一批。
        if session.ghostwriteProgress?.pauseReason == .batchCompleted {
            return "上一批已全部完成并收录。点「代笔下一批」继续连写，或修改章数后再开始。"
        }
        if session.ghostwriteProgress?.pauseReason == .chapterCompleted {
            return "本章已完成。点「代笔下一章」继续，或修改章数后再开始。"
        }
        return "最多连续 \(NovelGhostwriteBatch.maxChapterCount) 章。首章可用「根据前文生成草稿」再确认；之后自动拟计划并连写。写不过会自动改写几次，仍不过会停，不会假装写完。"
    }

    /// 质量失败时标明「将重写」，避免用户以为再点继续是复验旧稿。
    private var continueGhostwriteButtonTitle: String {
        guard let progress = session.ghostwriteProgress else { return "继续代笔" }
        if progress.pauseReason == .healBudgetExhausted {
            return "继续重写本章"
        }
        if progress.mustRewriteCandidateOnResume {
            return "继续代笔 · 将重写"
        }
        return "继续代笔"
    }

    private var ghostwriteDisplayedTargetCount: Int {
        if shouldShowContinueGhostwrite,
           let fixed = session.ghostwriteProgress?.targetChapterCount {
            return NovelGhostwriteBatch.clamp(fixed)
        }
        return NovelGhostwriteBatch.clamp(session.ghostwriteTargetChapterCount)
    }

    private var currentChapterPlan: NovelChapterPlanRecord? {
        workspace.selectedBranchID.flatMap { workspace.projectSnapshot?.chapterPlan(for: $0) }
    }

    private var currentUpcomingArc: NovelUpcomingArcRecord? {
        workspace.selectedBranchID.flatMap { workspace.projectSnapshot?.upcomingArc(for: $0) }
    }

    private var planStatusLabel: String {
        guard let plan = currentChapterPlan else { return "未创建" }
        switch plan.status {
        case .draft: return "草稿"
        case .confirmed: return "已确认"
        }
    }

    private var upcomingArcStatusLabel: String {
        guard let arc = currentUpcomingArc, !arc.beats.isEmpty else { return "未设置" }
        return "\(arc.beats.count) 条"
    }

    private var reviewModelLabel: String {
        _ = sharedSettings.revision
        let configured = workspace.projectSnapshot?.project.configuredModelPolicy(for: .review)
            ?? .global
        let effective: NovelProjectModelPolicy = {
            if case .global = configured {
                return NovelCreationModelPreferences.shared.policy(for: .review)
            }
            return configured
        }()
        let name = NovelPresentation.modelDisplayName(
            for: effective,
            sharedSettings: sharedSettings
        )
        if case .global = configured {
            return "小说默认 · \(name)"
        }
        return name
    }

    private var upcomingArcFieldSyncToken: String {
        guard let arc = currentUpcomingArc else { return "none" }
        return "\(arc.branchID.rawValue.uuidString)|\(arc.beats.joined(separator: "|"))|\(arc.updatedAt.timeIntervalSince1970)"
    }

    private var ghostwriteSwitchBlockers: [NovelGhostwriteReadinessIssue] {
        workspace.ghostwriteReadinessIssues(requireChapterPlan: false)
    }

    private var canEditChapterPlan: Bool {
        workspace.canMutate && !workspace.isPerforming && !session.isGhostwriting && !isProposingPlanDraft
    }

    private var canEditUpcomingArc: Bool {
        workspace.canMutate && !workspace.isPerforming && !session.isGhostwriting && !isProposingPlanDraft
    }

    /// 尚无确认计划时露出「根据前文生成」；已确认则隐藏（避免盖掉手改合同）。
    private var canShowProposePlanDraft: Bool {
        currentChapterPlan?.status != .confirmed
    }

    private var canProposePlanDraft: Bool {
        canEditChapterPlan && !isProposingPlanDraft
    }

    private var proposePlanDraftButtonTitle: String {
        if isProposingPlanDraft { return "正在根据前文生成…" }
        if currentChapterPlan?.status == .draft { return "重新根据前文生成" }
        return "根据前文生成草稿"
    }

    private var chapterPlanSectionFooter: String {
        if collaborationMode == .ghostwrite {
            if canShowProposePlanDraft {
                return "可先「根据前文生成草稿」，核对后点确认计划，再开始代笔。代笔进行中不能改。"
            }
            return "代笔写整章前要先确认计划；代笔进行中不能改。"
        }
        if canShowProposePlanDraft {
            return "可先「根据前文生成草稿」，也可手写；确认后写整章时会带上。"
        }
        return "可以先写好本章计划；确认后写整章时会带上。"
    }

    private func reloadPlanFieldsFromWorkspace() {
        isReloadingPlanFields = true
        let plan = currentChapterPlan
        planPlacement = plan?.outlinePlacement ?? ""
        planGoal = plan?.goalAndConflict ?? ""
        planMustHappen = plan?.mustHappen.joined(separator: "\n") ?? ""
        planMustNotHappen = plan?.mustNotHappen.joined(separator: "\n") ?? ""
        planEndingHook = plan?.endingHook ?? ""
        planVisibleFacts = plan?.visibleFacts.joined(separator: "\n") ?? ""
        planFieldsDirty = false
        isReloadingPlanFields = false
    }

    private func reloadUpcomingArcFromWorkspace() {
        isReloadingArcFields = true
        upcomingArcBeats = currentUpcomingArc?.beats.joined(separator: "\n") ?? ""
        arcFieldsDirty = false
        isReloadingArcFields = false
    }

    private func selectCollaborationMode(_ mode: NovelCollaborationMode) async {
        guard mode != collaborationMode else { return }
        modeSwitchMessage = nil
        if mode == .cocreation, session.isGhostwriting || session.isRunning {
            selectedMode = collaborationMode
            modeSwitchMessage = "当前生成仍在进行，请先停止再切回共创。"
            return
        }
        if mode == .ghostwrite, !ghostwriteSwitchBlockers.isEmpty {
            selectedMode = collaborationMode
            modeSwitchMessage = "无法切入代笔，请先补齐下方缺项。"
            return
        }
        let saved = await workspace.setCollaborationMode(mode)
        if saved {
            selectedMode = mode
        } else {
            selectedMode = collaborationMode
            modeSwitchMessage = workspace.errorMessage ?? "模式切换失败，请重试。"
        }
    }

    private func setPauseOnBlockingContinuity(_ enabled: Bool) async {
        guard enabled != storedPauseOnBlockingContinuity else { return }
        pauseToggleMessage = nil
        let saved = await workspace.setPauseGhostwriteOnBlockingContinuity(enabled)
        if !saved {
            pauseOnBlockingContinuity = storedPauseOnBlockingContinuity
            pauseToggleMessage = workspace.errorMessage ?? "未能更新连续性暂停设置。"
        }
    }

    private func saveChapterPlan(status: NovelChapterPlanStatus) async {
        planMessage = nil
        planMessageIsError = false
        // Belt-and-suspenders: bank already flushed on the button path.
        planFieldBank.commitAll()
        let saved = await workspace.upsertChapterPlan(
            status: status,
            outlinePlacement: planPlacement,
            goalAndConflict: planGoal,
            mustHappen: planLines(from: planMustHappen),
            mustNotHappen: planLines(from: planMustNotHappen),
            endingHook: planEndingHook,
            visibleFacts: planLines(from: planVisibleFacts)
        )
        if saved {
            planFieldsDirty = false
            planMessage = status == .confirmed ? "已确认，可以按这个写。" : "草稿已保存。"
            planMessageIsError = false
        } else {
            planMessage = workspace.errorMessage ?? "本章计划保存失败。"
            planMessageIsError = true
        }
    }

    /// 用总纲/剧情状态/上一批摘要生成草稿本章计划，不自动确认。
    private func proposeChapterPlanDraft() async {
        guard canProposePlanDraft,
              let projectID = workspace.selectedProjectID,
              let branchID = workspace.selectedBranchID else {
            planMessage = "当前无法生成计划。"
            planMessageIsError = true
            return
        }
        planMessage = nil
        planMessageIsError = false
        planFieldBank.commitAll()
        isProposingPlanDraft = true
        defer { isProposingPlanDraft = false }

        let branch = workspace.projectSnapshot?.branches.first { $0.id == branchID }
        let ordinal = max(1, (branch?.workingChapterSelections.count ?? 0) + 1)
        let previousSummary = session.ghostwriteProgress?.lastCompletedPlanSummary
        do {
            let plan = try await workspace.proposeNextChapterPlanDraft(
                projectID: projectID,
                branchID: branchID,
                nextChapterOrdinal: ordinal,
                previousPlanSummary: previousSummary
            )
            // 优先用返回值回填，避免 refresh 滞后时字段仍空。
            applyChapterPlanToFields(plan)
            planMessage = "已根据前文生成草稿，请核对后点「确认计划」。"
            planMessageIsError = false
        } catch {
            planMessage = NovelPresentation.operationErrorMessage(error)
            planMessageIsError = true
        }
    }

    private func applyChapterPlanToFields(_ plan: NovelChapterPlanRecord) {
        isReloadingPlanFields = true
        planPlacement = plan.outlinePlacement
        planGoal = plan.goalAndConflict
        planMustHappen = plan.mustHappen.joined(separator: "\n")
        planMustNotHappen = plan.mustNotHappen.joined(separator: "\n")
        planEndingHook = plan.endingHook
        planVisibleFacts = plan.visibleFacts.joined(separator: "\n")
        planFieldsDirty = false
        isReloadingPlanFields = false
    }

    private func clearChapterPlan() async {
        planMessage = nil
        planMessageIsError = false
        planFieldBank.commitAll()
        let cleared = await workspace.clearChapterPlan()
        if cleared {
            isReloadingPlanFields = true
            planPlacement = ""
            planGoal = ""
            planMustHappen = ""
            planMustNotHappen = ""
            planEndingHook = ""
            planVisibleFacts = ""
            planFieldsDirty = false
            isReloadingPlanFields = false
            planMessage = "计划已清除。"
            planMessageIsError = false
        } else {
            planMessage = workspace.errorMessage ?? "清除本章计划失败。"
            planMessageIsError = true
        }
    }

    private func saveUpcomingArc() async {
        arcMessage = nil
        arcMessageIsError = false
        planFieldBank.commitAll()
        let beats = planLines(from: upcomingArcBeats)
        guard !beats.isEmpty else {
            arcMessage = "请至少写一条。"
            arcMessageIsError = true
            return
        }
        let saved = await workspace.upsertUpcomingArc(beats: beats)
        if saved {
            arcFieldsDirty = false
            arcMessage = "已保存。"
            arcMessageIsError = false
        } else {
            arcMessage = workspace.errorMessage ?? "保存失败。"
            arcMessageIsError = true
        }
    }

    private func clearUpcomingArc() async {
        arcMessage = nil
        arcMessageIsError = false
        planFieldBank.commitAll()
        let cleared = await workspace.clearUpcomingArc()
        if cleared {
            isReloadingArcFields = true
            upcomingArcBeats = ""
            arcFieldsDirty = false
            isReloadingArcFields = false
            arcMessage = "备注已清除。"
            arcMessageIsError = false
        } else {
            arcMessage = workspace.errorMessage ?? "清除失败。"
            arcMessageIsError = true
        }
    }

    private func planLines(from text: String) -> [String] {
        text
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private func applyDraftBeforeTransition(_ transition: @escaping () -> Void) {
        NovelTextInputCommitter.perform(fieldBank: planFieldBank) {
            onApply(overrides, budgetTokens)
            transition()
        }
    }

    private var contextList: some View {
        List {
            Section("本次生成") {
                LabeledContent("模式", value: mode == .writeProse ? "写正文" : "讨论规划")
                if mode == .writeProse {
                    LabeledContent(
                        "粒度",
                        value: granularity == .wholeChapter ? "生成整章" : "续写片段"
                    )
                }
            }

            Section {
                ForEach(MaterialCategory.allCases) { category in
                    NavigationLink(value: ContextRoute.materials(category)) {
                        Label {
                            LabeledContent(category.title, value: categorySummary(category))
                        } icon: {
                            Image(systemName: category.systemImage)
                                .foregroundStyle(AmberTheme.accent)
                        }
                    }
                }
            } header: {
                Text("资料注入")
            } footer: {
                Text("进入分类后选择本次加入或排除；不会修改资料的默认注入方式。")
            }

            Section("高级") {
                Slider(
                    value: budgetSliderValue,
                    in: 2_000...64_000,
                    step: 2_000
                ) {
                    Text("上下文长度")
                } currentValueLabel: {
                    Text("约 \(budgetTokens.formatted())")
                } minimumValueLabel: {
                    Text("2K")
                } maximumValueLabel: {
                    Text("64K")
                } tick: { value in
                    Self.budgetTick(for: value)
                }
            }

            if matchingPreview != nil {
                Section {
                    NavigationLink("查看预计上下文", value: ContextRoute.preview)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AmberTheme.background)
    }

    private func materialChoicesList(_ category: MaterialCategory) -> some View {
        List {
            Section {
                if materials(in: category).isEmpty {
                    ContentUnavailableView(
                        "没有\(category.title)资料",
                        systemImage: category.systemImage
                    )
                } else {
                    ForEach(materials(in: category), id: \.id) { material in
                        Picker(materialTitle(material), selection: choiceBinding(material.id)) {
                            ForEach(MaterialChoice.allCases) { choice in
                                Text(choice.title).tag(choice)
                            }
                        }
                        .pickerStyle(.menu)
                    }
                }
            } footer: {
                Text("按默认会沿用每条资料自己的注入设置。")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AmberTheme.background)
        .navigationTitle(category.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var contextPreview: some View {
        if let preview = matchingPreview {
            List {
                Section("预计上下文") {
                    LabeledContent("模型", value: preview.resolvedModel.displayName)
                    LabeledContent(
                        "预计输入",
                        value: "\(preview.plan.estimatedInputTokens.formatted()) / \(preview.effectiveInputBudgetTokens.formatted())"
                    )
                }
                Section("注入内容") {
                    ForEach(Array(preview.plan.sections.enumerated()), id: \.offset) { _, section in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(section.label)
                                .font(.subheadline.weight(.medium))
                            Text("\(section.reason.displayName) · 约 \(section.estimatedTokens) 上下文单位")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("预计上下文")
            .navigationBarTitleDisplayMode(.inline)
        } else {
            ContentUnavailableView("预览已失效", systemImage: "arrow.clockwise")
                .navigationTitle("预计上下文")
        }
    }

    private var overrides: NovelInjectionOverrides {
        NovelInjectionOverrides(
            forceIncludeMaterialIDs: materialChoices.compactMap { id, choice in
                choice == .include ? id : nil
            },
            forceExcludeMaterialIDs: materialChoices.compactMap { id, choice in
                choice == .exclude ? id : nil
            }
        )
    }

    private var budgetSliderValue: Binding<Double> {
        Binding(
            get: { Double(budgetTokens) },
            set: { newValue in
                budgetTokens = Int(newValue.rounded())
            }
        )
    }

    private static func budgetTick(for value: Double) -> SliderTick<Double>? {
        let tokens = Int(value.rounded())
        guard [8_000, 16_000, 32_000].contains(tokens) else { return nil }
        return SliderTick(value) {
            Text("\(tokens / 1_000)K")
        }
    }

    private var matchingPreview: NovelInjectionPreviewSnapshot? {
        guard let preview = workspace.injectionPreview,
              previewSignature == currentPreviewSignature,
              preview.projectID == workspace.selectedProjectID,
              preview.branchID == workspace.selectedBranchID else {
            return nil
        }
        // 预算和 overrides 签名已在 currentPreviewSignature 里覆盖；
        // revision 变化（收录章节、同步状态等）不影响已算出的注入计划，
        // 旧实现把它们也纳入匹配，导致预览频繁「已失效」。
        return preview
    }

    private var canPreview: Bool {
        workspace.canMutate && !userText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var hasWritingRequirements: Bool {
        workspace.activeMaterials.contains {
            if case .writingRequirements = $0.kind { return true }
            return false
        }
    }

    private var hasPolishPreference: Bool {
        workspace.projectSnapshot?.project.polishPreference.isEmpty == false
    }

    private func materials(in category: MaterialCategory) -> [NovelMaterialRecord] {
        workspace.activeMaterials.filter { category.contains($0.kind) }
    }

    private func categorySummary(_ category: MaterialCategory) -> String {
        let categoryMaterials = materials(in: category)
        let adjusted = categoryMaterials.filter {
            (materialChoices[$0.id] ?? .automatic) != .automatic
        }.count
        guard adjusted > 0 else { return "\(categoryMaterials.count) 条" }
        return "\(categoryMaterials.count) 条 · 已调整 \(adjusted)"
    }

    private func choiceBinding(_ materialID: NovelMaterialID) -> Binding<MaterialChoice> {
        Binding(
            get: { materialChoices[materialID] ?? .automatic },
            set: { choice in
                if choice == .automatic {
                    materialChoices.removeValue(forKey: materialID)
                } else {
                    materialChoices[materialID] = choice
                }
                // 即时回写：勾选/取消即时生效，不需要再点「应用」。
                onApply(overrides, budgetTokens)
            }
        )
    }

    private func materialTitle(_ material: NovelMaterialRecord) -> String {
        guard let project = workspace.projectSnapshot else { return material.kind.displayName }
        return NovelPresentation.effectiveRevision(
            for: material,
            project: project,
            branch: workspace.branchSnapshot
        )?.title ?? material.kind.displayName
    }

    private func preview() {
        guard let projectID = workspace.selectedProjectID,
              let branchID = workspace.selectedBranchID else { return }
        let request = NovelInjectionPreviewRequest(
            projectID: projectID,
            branchID: branchID,
            kind: mode == .writeProse ? .prose : .discussion,
            mode: mode,
            granularity: mode == .writeProse ? granularity : nil,
            userText: userText,
            sourceChapterVersionID: nil,
            injectionOverrides: overrides,
            inputBudgetTokens: budgetTokens
        )
        previewSignature = nil
        let requestSignature = currentPreviewSignature
        Task { @MainActor in
            guard let preview = await workspace.previewInjection(request),
                  currentPreviewSignature == requestSignature,
                  preview.projectID == projectID,
                  preview.branchID == branchID,
                  preview.requestedInputBudgetTokens == budgetTokens else { return }
            previewSignature = requestSignature
        }
    }

    private var currentPreviewSignature: String {
        let included = overrides.forceIncludeMaterialIDs.map(\.description).sorted().joined(separator: ",")
        let excluded = overrides.forceExcludeMaterialIDs.map(\.description).sorted().joined(separator: ",")
        let projectRevision = workspace.projectSnapshot?.project.revision ?? -1
        let configRevision = workspace.projectSnapshot?.project.configRevision ?? -1
        let headRevision = workspace.branchSnapshot?.branch.headRevision ?? -1
        return [
            mode.rawValue,
            mode == .writeProse ? granularity.rawValue : "discussion",
            String(userText.hashValue),
            String(budgetTokens),
            String(projectRevision),
            String(configRevision),
            String(headRevision),
            included,
            excluded
        ].joined(separator: "|")
    }

    private enum MaterialChoice: String, CaseIterable, Identifiable {
        case automatic
        case include
        case exclude

        var id: String { rawValue }

        var title: String {
            switch self {
            case .automatic: "按默认"
            case .include: "本次加入"
            case .exclude: "本次排除"
            }
        }
    }

    private enum SheetTab: String, CaseIterable, Identifiable {
        case preferences
        case context

        var id: String { rawValue }

        var title: String {
            switch self {
            case .preferences: "模式与偏好"
            case .context: "上下文注入"
            }
        }
    }

    private enum ContextRoute: Hashable {
        case materials(MaterialCategory)
        case preview
    }

    private enum MaterialCategory: String, CaseIterable, Identifiable, Hashable {
        case characters
        case world
        case story
        case other

        var id: String { rawValue }

        var title: String {
            switch self {
            case .characters: "人物角色"
            case .world: "世界观"
            case .story: "剧情大纲"
            case .other: "其他资料"
            }
        }

        var systemImage: String {
            switch self {
            case .characters: "person.2"
            case .world: "globe.asia.australia"
            case .story: "point.3.connected.trianglepath.dotted"
            case .other: "doc.text"
            }
        }

        func contains(_ kind: NovelMaterialKind) -> Bool {
            switch (self, kind) {
            case (.characters, .character), (.world, .world), (.story, .masterOutline):
                true
            case (.other, .writingRequirements), (.other, .custom):
                true
            default:
                false
            }
        }
    }
}

/// 代笔质量门失败后的人工润修确认面：预填审稿 brief，可改后开写。
struct NovelGhostwriteRevisionSheet: View {
    @Environment(\.dismiss) private var dismiss

    let recommendedBrief: String
    /// 展示给用户的中断摘要（优先审稿意见，不含离页/重启元信息）。
    let detail: String?
    let onCancel: () -> Void
    /// 返回是否已开始；false 时 sheet 留在原地并显示错误。
    let onStart: (String) -> Bool

    @State private var brief: String
    @State private var hasCustomized = false
    @State private var startError: String?
    @State private var revisionFieldBank = NovelIMEFieldBank()

    init(
        recommendedBrief: String,
        detail: String?,
        onCancel: @escaping () -> Void,
        onStart: @escaping (String) -> Bool
    ) {
        self.recommendedBrief = recommendedBrief
        self.detail = detail
        self.onCancel = onCancel
        self.onStart = onStart
        _brief = State(initialValue: recommendedBrief)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let detail, !detail.isEmpty {
                    Section {
                        Text(detail)
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    } header: {
                        Text("审稿意见")
                    }
                }

                if let startError, !startError.isEmpty {
                    Section {
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.footnote)
                            Text(startError)
                                .font(.footnote)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .foregroundStyle(AmberTheme.accentRed)
                    }
                }

                Section {
                    NovelIMETextEditor(
                        text: $brief,
                        placeholder: "润修要求",
                        isEnabled: true,
                        minHeight: 160,
                        bank: revisionFieldBank
                    )
                    .frame(minHeight: 160)
                    .onChange(of: brief) { _, _ in
                        hasCustomized = true
                        startError = nil
                    }
                    if hasCustomized, brief != recommendedBrief {
                        Button("重置为推荐") {
                            NovelTextInputCommitter.perform(fieldBank: revisionFieldBank) {
                                brief = recommendedBrief
                                hasCustomized = false
                            }
                        }
                        .frame(minHeight: 44)
                    }
                } header: {
                    Text("润修要求")
                } footer: {
                    Text("会按本章合同重写整章，并重新验收；不会把失败旧稿整篇塞进上下文。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("按审稿意见润修")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        NovelTextInputCommitter.perform(fieldBank: revisionFieldBank) {
                            onCancel()
                            dismiss()
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("开始润修") {
                        NovelTextInputCommitter.perform(fieldBank: revisionFieldBank) {
                            let started = onStart(brief)
                            if started {
                                dismiss()
                            } else {
                                startError = "代笔暂时不能开始，请检查本章计划后重试。"
                            }
                        }
                    }
                    .disabled(brief.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(false)
    }
}

struct NovelManualRewriteCandidateSheet: View {
    @Environment(\.dismiss) private var dismiss

    let content: String
    let onConfirm: @MainActor () async -> NovelSessionSheetSubmissionResult

    @State private var isSubmitting = false
    @State private var failureMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let failureMessage {
                        Label(failureMessage, systemImage: "exclamationmark.triangle")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(AmberTheme.accentRed)
                    }

                    Label("这会作为剧情改写保存", systemImage: "exclamationmark.triangle.fill")
                        .font(.headline)
                        .foregroundStyle(AmberTheme.foreground2)

                    Text("系统检测到润色候选可能改变剧情事实。保存后分支会进入待同步，正式生成前需要重新同步剧情状态。")
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.foreground2)
                        .fixedSize(horizontal: false, vertical: true)

                    ChatAssistantMarkdownView(
                        markdown: content,
                        renderCacheNamespace: "novel:manual-rewrite-preview"
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(20)
            }
            .background(AmberTheme.background)
            .navigationTitle("保存为剧情改写")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存改写") { confirm() }
                        .disabled(isSubmitting)
                }
            }
            .overlay {
                if isSubmitting { ProgressView() }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
    }

    private func confirm() {
        isSubmitting = true
        failureMessage = nil
        Task { @MainActor in
            let result = await onConfirm()
            isSubmitting = false
            switch result {
            case .completed:
                dismiss()
            case .pending(let message), .failed(let message):
                failureMessage = message
            }
        }
    }
}
