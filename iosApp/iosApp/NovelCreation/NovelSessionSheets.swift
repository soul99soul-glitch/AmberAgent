import SwiftUI

struct NovelSessionChapterOption: Identifiable, Equatable {
    let selection: NovelChapterSelection
    let version: NovelChapterVersionRecord
    let ordinal: Int

    var id: NovelChapterID { selection.chapterID }

    var displayTitle: String {
        let title = version.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? "第 \(ordinal) 章" : title
    }
}

enum NovelSessionSheetSubmissionResult: Equatable {
    case completed
    case pending(message: String)
    case failed(message: String)
}

struct NovelCollectCandidateSheet: View {
    @Environment(\.dismiss) private var dismiss

    let paragraphs: [NovelParagraphRecord]
    let chapters: [NovelSessionChapterOption]
    let onCollect: @MainActor (
        NovelParagraphSelection,
        NovelCollectionTarget
    ) async -> NovelSessionSheetSubmissionResult

    @State private var selectedParagraphIDs: Set<NovelParagraphID>
    @State private var editedText: String
    @State private var hasEditedText = false
    @State private var targetChoice: NovelCollectionTargetChoice
    @State private var nextChapterTitle: String
    @State private var isSubmitting = false
    @State private var submissionResult: NovelSessionSheetSubmissionResult?

    init(
        paragraphs: [NovelParagraphRecord],
        chapters: [NovelSessionChapterOption],
        suggestedGranularity: NovelGenerationGranularity,
        onCollect: @escaping @MainActor (
            NovelParagraphSelection,
            NovelCollectionTarget
        ) async -> NovelSessionSheetSubmissionResult
    ) {
        self.paragraphs = paragraphs
        self.chapters = chapters
        self.onCollect = onCollect
        let paragraphIDs = Set(paragraphs.map(\.id))
        self._selectedParagraphIDs = State(initialValue: paragraphIDs)
        self._editedText = State(initialValue: paragraphs.map(\.text).joined(separator: "\n\n"))
        self._targetChoice = State(initialValue: NovelCollectionTargetChoice.initial(
            chapterCount: chapters.count,
            granularity: suggestedGranularity
        ))
        self._nextChapterTitle = State(initialValue: "第 \(chapters.count + 1) 章")
    }

    var body: some View {
        NavigationStack {
            Form {
                submissionSection
                paragraphSection
                previewSection
                targetSection
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("收录正文")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("收录") { collect() }
                        .disabled(!canCollect || isSubmitting || hasDurablePending)
                }
            }
            .overlay {
                if isSubmitting {
                    ProgressView("正在更新正文与剧情状态")
                        .padding(16)
                        .amberGlass(cornerRadius: AmberTheme.radiusLarge, interactive: false)
                }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
    }

    @ViewBuilder
    private var submissionSection: some View {
        switch submissionResult {
        case .pending(let message):
            Section {
                Label(message, systemImage: "externaldrive.badge.checkmark")
                    .foregroundStyle(AmberTheme.accentAmber)
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
            Text("默认收录全部段落。未选择的段落仍保留在聊天气泡中。")
        }
    }

    private var previewSection: some View {
        Section {
            TextEditor(text: editedTextBinding)
                .font(.body)
                .frame(minHeight: 190)
                .disabled(selectedParagraphIDs.isEmpty || isSubmitting)
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
                    Text(appendCurrentLabel).tag(NovelCollectionTargetChoice.appendCurrent)
                    Text("新开第 \(chapters.count + 1) 章")
                        .tag(NovelCollectionTargetChoice.createNext)
                }
                .pickerStyle(.segmented)
                .disabled(isSubmitting)
            }

            if targetChoice == .appendCurrent, let chapter = chapters.last {
                LabeledContent("当前章节", value: chapter.displayTitle)
            } else {
                TextField("章节标题", text: $nextChapterTitle)
                    .disabled(isSubmitting)
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
        return chapters.last != nil
    }

    private var hasDurablePending: Bool {
        if case .pending = submissionResult { return true }
        return false
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
        resetEditedText()
    }

    private func setAllSelected(_ selected: Bool) {
        selectedParagraphIDs = selected ? Set(paragraphs.map(\.id)) : []
        resetEditedText()
    }

    private func resetEditedText() {
        editedText = selectedText
        hasEditedText = false
    }

    private func collect() {
        guard canCollect else { return }
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
            if result == .completed { dismiss() }
        }
    }

}

enum NovelCollectionTargetChoice: String, Hashable {
    case appendCurrent
    case createNext

    static func initial(
        chapterCount: Int,
        granularity: NovelGenerationGranularity
    ) -> NovelCollectionTargetChoice {
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
                    TextField("分支名称", text: $name)
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
                    Button("创建") { create() }
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

    init(
        workspace: NovelCreationViewModel,
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
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("创作设置", selection: $selectedTab) {
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
            .navigationTitle("创作设置")
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
                    Button("取消") { dismiss() }
                }
                ToolbarItemGroup(placement: .confirmationAction) {
                    if selectedTab == .context {
                        Button("预览") { preview() }
                            .disabled(!canPreview || workspace.isPerforming)
                    }
                    Button("应用") {
                        onApply(overrides, budgetTokens)
                        dismiss()
                    }
                    .disabled(workspace.isPerforming)
                }
            }
            .overlay {
                if workspace.isPerforming { ProgressView() }
            }
        }
        .interactiveDismissDisabled(workspace.isPerforming)
    }

    private var preferencesList: some View {
        List {
            Section("写作偏好") {
                Button(action: onEditWritingRequirements) {
                    NovelSettingsRow(
                        systemImage: "text.badge.checkmark",
                        title: "写作要求",
                        value: hasWritingRequirements ? "已设置" : "未设置",
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
                .disabled(!workspace.canMutate)

                Button(action: onEditPolishPreference) {
                    NovelSettingsRow(
                        systemImage: "wand.and.sparkles",
                        title: "章节风格",
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
                Stepper(value: $budgetTokens, in: 2_000...64_000, step: 2_000) {
                    LabeledContent(
                        "上下文长度",
                        value: "约 \(budgetTokens.formatted()) 上下文单位"
                    )
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

    private var matchingPreview: NovelInjectionPreviewSnapshot? {
        guard let preview = workspace.injectionPreview,
              previewSignature == currentPreviewSignature,
              preview.projectID == workspace.selectedProjectID,
              preview.branchID == workspace.selectedBranchID,
              preview.requestedInputBudgetTokens == budgetTokens,
              preview.projectRevision == workspace.projectSnapshot?.project.revision,
              preview.configRevision == workspace.projectSnapshot?.project.configRevision,
              preview.branchHeadRevision == workspace.branchSnapshot?.branch.headRevision else {
            return nil
        }
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
            }
        )
    }

    private func materialTitle(_ material: NovelMaterialRecord) -> String {
        guard let project = workspace.projectSnapshot else { return material.kind.displayName }
        return NovelPresentation.currentRevision(for: material, in: project)?.title
            ?? material.kind.displayName
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
            case .preferences: "写作偏好"
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
                        .foregroundStyle(AmberTheme.accentAmber)

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
