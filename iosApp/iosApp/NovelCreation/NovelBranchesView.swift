import SwiftUI

struct NovelBranchesView: View {
    let viewModel: NovelCreationViewModel
    let isSelectionDisabled: Bool
    let onSelect: (NovelBranchID) -> Void
    let onRename: (NovelBranchRecord) -> Void
    let onFork: (NovelBranchRecord) -> Void
    let onEditOverride: (NovelMaterialRecord) -> Void

    @State private var pendingDelete: NovelBranchDeleteCandidate?
    @State private var pendingUndoCheckpointID: NovelCheckpointID?

    var body: some View {
        List {
            branchesSection
            selectedBranchActions
            branchOverridesSection
            branchStateSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除“\(candidate.branch.name)”？"),
                message: Text("不会级联删除它的子分支，但这条分支将不再出现在项目中。"),
                primaryButton: .destructive(Text("删除")) {
                    Task { await viewModel.deleteBranch(candidate.branch.id) }
                },
                secondaryButton: .cancel()
            )
        }
        .confirmationDialog(
            "\(undoTitle)？",
            isPresented: Binding(
                get: { pendingUndoCheckpointID != nil },
                set: { if !$0 { pendingUndoCheckpointID = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(undoTitle, role: .destructive) {
                let checkpointID = pendingUndoCheckpointID
                pendingUndoCheckpointID = nil
                Task {
                    guard let checkpointID,
                          viewModel.branchSnapshot?.branch.headCheckpointID == checkpointID else {
                        viewModel.presentError(NovelError.invalidInput("当前分支已经变化，请重新选择撤销操作。"))
                        return
                    }
                    await viewModel.undoBranchHead()
                }
            }
            Button("取消", role: .cancel) { pendingUndoCheckpointID = nil }
        } message: {
            Text("分支会回到上一个存档点，不会删除历史记录。")
        }
    }

    private var branchesSection: some View {
        Section("剧情分支") {
            ForEach(viewModel.activeBranches, id: \.id) { branch in
                Button {
                    onSelect(branch.id)
                } label: {
                    NovelBranchRow(
                        branch: branch,
                        isSelected: branch.id == viewModel.selectedBranchID,
                        isMain: branch.id == viewModel.projectSnapshot?.project.mainBranchID
                    )
                }
                .buttonStyle(.plain)
                .disabled(isSelectionDisabled)
            }
        }
    }

    @ViewBuilder
    private var selectedBranchActions: some View {
        if let project = viewModel.projectSnapshot, let branch = selectedBranch {
            Section {
                if branch.activeRunID != nil {
                    Label("正在生成，写操作暂不可用", systemImage: "progress.indicator")
                        .foregroundStyle(AmberTheme.accent)
                }

                Button {
                    Task { await viewModel.setMainBranch(branch.id) }
                } label: {
                    Label("设为主分支", systemImage: "star")
                }
                .disabled(!canWrite || branch.id == project.project.mainBranchID)

                Button {
                    onRename(branch)
                } label: {
                    Label("重命名分支", systemImage: "pencil")
                }
                .disabled(!canWrite)

                Button {
                    onFork(branch)
                } label: {
                    Label("从检查点 Fork", systemImage: "arrow.triangle.branch")
                }
                .disabled(!canWrite || forkableCheckpoints.isEmpty)

                Button {
                    pendingUndoCheckpointID = branch.headCheckpointID
                } label: {
                    Label(undoTitle, systemImage: "arrow.uturn.backward")
                }
                .disabled(undoBlockReason != nil)

                Button(role: .destructive) {
                    pendingDelete = NovelBranchDeleteCandidate(branch: branch)
                } label: {
                    Label("删除分支", systemImage: "trash")
                }
                .disabled(
                    !canWrite ||
                        viewModel.activeBranches.count <= 1 ||
                        branch.id == project.project.mainBranchID
                )
            } header: {
                Text("当前分支")
            } footer: {
                if let undoBlockReason {
                    Text(undoBlockReason)
                } else if branch.id == project.project.mainBranchID {
                    Text("主分支不能直接删除；请先把另一条分支设为主分支。")
                } else {
                    Text("撤销只会回到上一个存档点，不删除历史记录。")
                }
            }
        }
    }

    @ViewBuilder
    private var branchOverridesSection: some View {
        if let project = viewModel.projectSnapshot,
           let branchSnapshot = viewModel.branchSnapshot,
           !viewModel.activeMaterials.isEmpty {
            Section {
                ForEach(viewModel.activeMaterials, id: \.id) { material in
                    let global = NovelPresentation.currentRevision(for: material, in: project)
                    let effective = NovelPresentation.effectiveRevision(
                        for: material,
                        project: project,
                        branch: branchSnapshot
                    )
                    let isOverride = effective?.id != global?.id

                    Button {
                        onEditOverride(material)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: material.kind.systemImage)
                                .foregroundStyle(AmberTheme.accent)
                                .frame(width: 28)

                            VStack(alignment: .leading, spacing: 3) {
                                Text(effective?.title ?? material.kind.displayName)
                                    .font(.body)
                                    .foregroundStyle(AmberTheme.foreground)
                                    .lineLimit(1)
                                Text(isOverride ? "使用分支版本" : "继承项目版本")
                                    .font(.caption)
                                    .foregroundStyle(isOverride ? AmberTheme.accent : AmberTheme.muted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AmberTheme.muted2)
                        }
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!canWrite || branchSnapshot.branch.syncStatus != .synchronized)
                }
            } header: {
                Text("分支设定覆盖")
            } footer: {
                Text("分支版本不会移动项目资料的全局当前版本，也不会影响其他分支。")
            }
        }
    }

    @ViewBuilder
    private var branchStateSection: some View {
        if let snapshot = viewModel.branchSnapshot {
            Section("当前剧情状态") {
                LabeledContent("同步状态", value: snapshot.branch.syncStatus.displayName)
                    .foregroundStyle(
                        snapshot.branch.syncStatus == .synchronized
                            ? AmberTheme.foreground
                            : AmberTheme.accentAmber
                    )

                if snapshot.currentState.summary.isEmpty {
                    Text("尚未形成剧情状态摘要。")
                        .foregroundStyle(AmberTheme.muted)
                } else {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("摘要")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                        Text(snapshot.currentState.summary)
                            .font(.body)
                            .textSelection(.enabled)
                    }
                    .padding(.vertical, 3)
                }

                if !snapshot.currentState.branchOutline.isEmpty {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("分支走向")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted)
                        Text(snapshot.currentState.branchOutline)
                            .font(.body)
                            .textSelection(.enabled)
                    }
                    .padding(.vertical, 3)
                }
            }

            if let project = viewModel.projectSnapshot {
                let eventIDs = Set(snapshot.currentState.eventIDs)
                let events = project.events
                    .filter { eventIDs.contains($0.id) }
                    .sorted { $0.sequence > $1.sequence }
                if !events.isEmpty {
                    Section("事件记录") {
                        ForEach(events, id: \.id) { event in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(event.summary)
                                    .font(.body)
                                    .foregroundStyle(AmberTheme.foreground)
                                Text(event.kind)
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted)
                            }
                            .padding(.vertical, 3)
                        }
                    }
                }
            }
        }
    }

    private var selectedBranch: NovelBranchRecord? {
        viewModel.projectSnapshot?.branches.first { $0.id == viewModel.selectedBranchID }
    }

    private var checkpointLineage: [NovelBranchCheckpointRecord] {
        guard let project = viewModel.projectSnapshot, let branch = selectedBranch else { return [] }
        return NovelPresentation.actionCheckpointLineage(for: branch, in: project)
    }

    private var forkableCheckpoints: [NovelBranchCheckpointRecord] {
        guard let project = viewModel.projectSnapshot, let branch = selectedBranch else { return [] }
        return NovelPresentation.forkableCheckpoints(for: branch, in: project)
    }

    private var canWrite: Bool {
        viewModel.canMutate && selectedBranch?.activeRunID == nil && !viewModel.isPerforming
    }

    private var undoTitle: String {
        guard let kind = semanticUndoKind else { return "撤销上一次操作" }
        return switch kind {
        case .collection: "撤销上一次收录"
        case .polish: "撤销上一次润色"
        case .manualSync: "撤销上一次同步"
        case .discussionArchive: "撤销上一次讨论归档"
        case .restore: "撤销上一次恢复"
        case .initial: "撤销上一次操作"
        }
    }

    private var undoBlockReason: String? {
        guard canWrite else { return "项目正在处理其他操作，暂时不能撤销。" }
        if let branch = selectedBranch,
           branch.headCheckpointID == branch.forkOrigin?.checkpointID {
            return "当前分支还没有可撤销的创作记录。"
        }
        guard checkpointLineage.count >= 2 else { return "当前分支还没有可撤销的创作记录。" }
        guard let project = viewModel.projectSnapshot,
              let branch = selectedBranch,
              let head = checkpointLineage.first,
              NovelBranchSemantics.canUndoHead(
                  head,
                  branch: branch,
                  checkpoints: project.checkpoints
              ) else {
            return "请先同步手动改写，再撤销上一次操作。"
        }
        guard let branchID = selectedBranch?.id else { return "当前分支尚未载入。" }
        if project.pendingOperations.contains(where: { $0.branchID == branchID }) ||
            project.polishTransactions.contains(where: {
                $0.branchID == branchID &&
                    ($0.status == .pending || $0.status == .retryable || $0.status == .blocked)
            }) {
            return "当前分支还有未完成的正文操作。"
        }
        return nil
    }

    private var semanticUndoKind: NovelCheckpointKind? {
        guard let project = viewModel.projectSnapshot,
              let branch = selectedBranch,
              let head = checkpointLineage.first,
              let target = NovelBranchSemantics.undoTarget(
                  for: head,
                  branch: branch,
                  checkpoints: project.checkpoints
              ) else {
            return nil
        }
        guard let directParentID = head.parentCheckpointID,
              target.id != directParentID,
              let directParent = project.checkpoints.first(where: { $0.id == directParentID }) else {
            return head.kind
        }
        return directParent.kind
    }
}

private struct NovelBranchDeleteCandidate: Identifiable {
    let branch: NovelBranchRecord
    var id: NovelBranchID { branch.id }
}

private struct NovelBranchRow: View {
    let branch: NovelBranchRecord
    let isSelected: Bool
    let isMain: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isMain ? "star.fill" : "arrow.triangle.branch")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(isMain ? AmberTheme.accentAmber : AmberTheme.accent)
                .frame(width: 34, height: 34)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text(branch.name)
                    .font(.body.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                HStack(spacing: 6) {
                    Text(branch.syncStatus.displayName)
                    if branch.activeRunID != nil { Text("生成中") }
                }
                .font(.caption)
                .foregroundStyle(branch.syncStatus == .synchronized ? AmberTheme.muted : AmberTheme.accentAmber)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if isSelected {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(AmberTheme.accent)
            }
        }
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }
}

struct NovelBranchRenameSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let branch: NovelBranchRecord
    @State private var name: String

    init(viewModel: NovelCreationViewModel, branch: NovelBranchRecord) {
        self.viewModel = viewModel
        self.branch = branch
        self._name = State(initialValue: branch.name)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                Text("分支名称")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                TextField("分支名称", text: $name)
                    .textFieldStyle(.roundedBorder)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(AmberTheme.background)
            .navigationTitle("重命名分支")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationSizing(.fitted)
        .presentationDragIndicator(.visible)
    }

    private func save() {
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.renameBranch(branch.id, name: name)
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }
}

struct NovelBranchForkSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let branch: NovelBranchRecord
    let onCreated: (String) -> Void

    @State private var name: String
    @State private var checkpointID: NovelCheckpointID
    @State private var isSubmitting = false
    @State private var failureMessage: String?

    init(
        viewModel: NovelCreationViewModel,
        branch: NovelBranchRecord,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        self.viewModel = viewModel
        self.branch = branch
        self.onCreated = onCreated
        self._name = State(initialValue: "\(branch.name) · 新走向")
        self._checkpointID = State(initialValue: branch.headCheckpointID)
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
                    TextField("分支名称", text: $name)
                }

                Section {
                    Picker("检查点", selection: $checkpointID) {
                        ForEach(lineage, id: \.id) { checkpoint in
                            Text(checkpointLabel(checkpoint)).tag(checkpoint.id)
                        }
                    }
                } header: {
                    Text("起点")
                } footer: {
                    Text("新分支会继承该检查点的正文、剧情状态、分支设定覆盖和此前对话；源分支保持不变。")
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
                    Button("创建") { fork() }
                        .disabled(
                            name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                !lineage.contains(where: { $0.id == checkpointID }) ||
                                isSubmitting
                        )
                }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var lineage: [NovelBranchCheckpointRecord] {
        guard let project = viewModel.projectSnapshot else { return [] }
        return NovelPresentation.forkableCheckpoints(for: branch, in: project)
    }

    private func checkpointLabel(_ checkpoint: NovelBranchCheckpointRecord) -> String {
        let chapterNumber = viewModel.projectSnapshot.flatMap {
            NovelCheckpointLabel.chapterOrdinal(
                for: checkpoint,
                checkpoints: $0.checkpoints
            )
        }
        let action = switch checkpoint.kind {
        case .collection: chapterNumber.map { "第 \($0) 章收录后" } ?? "正文收录后"
        case .polish: chapterNumber.map { "第 \($0) 章润色后" } ?? "正文润色后"
        case .manualSync: "剧情同步后"
        case .discussionArchive: chapterNumber.map { "第 \($0) 章讨论归档后" } ?? "讨论归档后"
        case .restore: chapterNumber.map { "第 \($0) 章恢复后" } ?? "章节恢复后"
        case .initial: "项目开始"
        }
        return "\(action) · \(checkpoint.createdAt.formatted(date: .abbreviated, time: .omitted))"
    }

    private func fork() {
        isSubmitting = true
        failureMessage = nil
        Task { @MainActor in
            viewModel.clearError()
            let branchName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let branchID = await viewModel.forkBranch(
                from: branch.id,
                checkpointID: checkpointID,
                name: branchName
            )
            isSubmitting = false
            guard branchID != nil else {
                failureMessage = viewModel.presentedMessage ?? "分支没有创建完成，请重新载入项目后再试。"
                return
            }
            dismiss()
            onCreated(branchName)
        }
    }
}

enum NovelCheckpointLabel {
    static func chapterOrdinal(
        for checkpoint: NovelBranchCheckpointRecord,
        checkpoints: [NovelBranchCheckpointRecord]
    ) -> Int? {
        guard let parentID = checkpoint.parentCheckpointID,
              let parent = checkpoints.first(where: { $0.id == parentID }) else { return nil }
        let parentVersions = Dictionary(
            uniqueKeysWithValues: parent.chapterSelections.map { ($0.chapterID, $0.versionID) }
        )
        let changedChapterIDs = checkpoint.chapterSelections.compactMap { selection in
            parentVersions[selection.chapterID] == selection.versionID ? nil : selection.chapterID
        }
        guard changedChapterIDs.count == 1,
              let index = checkpoint.chapterSelections.firstIndex(where: {
                  $0.chapterID == changedChapterIDs[0]
              }) else {
            return nil
        }
        return index + 1
    }
}

private enum NovelBranchOverrideMode: String, CaseIterable, Identifiable {
    case inherit
    case existing
    case newRevision

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .inherit: "继承项目"
        case .existing: "历史版本"
        case .newRevision: "新建分支版本"
        }
    }
}

struct NovelBranchOverrideEditorSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let material: NovelMaterialRecord

    @State private var mode: NovelBranchOverrideMode
    @State private var selectedRevisionID: NovelMaterialRevisionID?
    @State private var title: String
    @State private var content: String
    @State private var tags: String
    @State private var injectionMode: NovelInjectionMode

    init(viewModel: NovelCreationViewModel, material: NovelMaterialRecord) {
        self.viewModel = viewModel
        self.material = material
        let project = viewModel.projectSnapshot
        let global = project.flatMap { NovelPresentation.currentRevision(for: material, in: $0) }
        let effective = project.flatMap {
            NovelPresentation.effectiveRevision(
                for: material,
                project: $0,
                branch: viewModel.branchSnapshot
            )
        }
        let hasOverride = effective?.id != global?.id
        self._mode = State(initialValue: hasOverride ? .existing : .inherit)
        self._selectedRevisionID = State(initialValue: effective?.id)
        self._title = State(initialValue: effective?.title ?? global?.title ?? "")
        self._content = State(initialValue: effective?.content ?? global?.content ?? "")
        self._tags = State(initialValue: (effective?.tags ?? global?.tags ?? []).joined(separator: "，"))
        self._injectionMode = State(initialValue: effective?.injectionMode ?? global?.injectionMode ?? .smart)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("覆盖方式") {
                    Picker("覆盖方式", selection: $mode) {
                        ForEach(NovelBranchOverrideMode.allCases) { value in
                            Text(value.displayName).tag(value)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                if mode == .existing {
                    Section("历史版本") {
                        Picker("资料版本", selection: $selectedRevisionID) {
                            ForEach(revisions, id: \.id) { revision in
                                Text("版本 \(revision.revision) · \(revision.title)")
                                    .tag(revision.id as NovelMaterialRevisionID?)
                            }
                        }
                    }
                } else if mode == .newRevision {
                    Section("分支版本") {
                        TextField("标题", text: $title)
                        TextEditor(text: $content)
                            .frame(minHeight: 220)
                        TextField("标签，用逗号分隔", text: $tags)
                        Picker("默认注入", selection: $injectionMode) {
                            ForEach(NovelInjectionMode.allCases, id: \.self) { value in
                                Text(value.displayName).tag(value)
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                } else {
                    Section {
                        Text("该分支将重新使用项目资料的当前版本。")
                            .foregroundStyle(AmberTheme.foreground2)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("分支设定覆盖")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(!canSave || viewModel.isPerforming)
                }
            }
        }
        .interactiveDismissDisabled(viewModel.isPerforming)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var revisions: [NovelMaterialRevisionRecord] {
        viewModel.projectSnapshot?.materialRevisions
            .filter { $0.materialID == material.id }
            .sorted { $0.revision > $1.revision } ?? []
    }

    private var canSave: Bool {
        switch mode {
        case .inherit:
            true
        case .existing:
            selectedRevisionID != nil
        case .newRevision:
            !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    private func save() {
        let change: NovelBranchMaterialOverrideChange
        switch mode {
        case .inherit:
            change = .inherit
        case .existing:
            guard let selectedRevisionID else { return }
            change = .useRevision(selectedRevisionID)
        case .newRevision:
            let parsedTags = tags
                .components(separatedBy: CharacterSet(charactersIn: ",，\n"))
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            change = .createRevision(
                revisionID: NovelMaterialRevisionID(),
                title: title,
                content: content,
                tags: parsedTags,
                injectionMode: injectionMode
            )
        }
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.setBranchMaterialOverride(materialID: material.id, change: change)
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }
}
