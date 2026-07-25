import SwiftUI

struct NovelMaterialsView: View {
    let viewModel: NovelCreationViewModel
    let sharedSettings: IOSSharedSettingsStore
    let onEditMaterial: (NovelMaterialRecord?) -> Void
    let onChooseFixedModel: () -> Void
    let onEditPolishPreference: () -> Void
    let onPreviewContext: () -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void

    @State private var pendingDelete: NovelMaterialDeleteCandidate?
    @State private var isPresentingQuickStartRegeneration = false

    var body: some View {
        List {
            if let project = viewModel.projectSnapshot {
                configurationSection(project)
                proposalsSection
                quickStartRegenerationSection
                materialsSection(project)
            } else {
                Section {
                    ProgressView("正在读取项目设定")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除“\(candidate.title)”？"),
                message: Text("历史修订会保留，用于旧版本、Fork 和审计。后续生成不再注入这条资料。"),
                primaryButton: .destructive(Text("删除")) {
                    Task { await viewModel.deleteMaterial(candidate.material.id) }
                },
                secondaryButton: .cancel()
            )
        }
        .sheet(isPresented: $isPresentingQuickStartRegeneration) {
            NovelQuickStartRegenerationSheet(viewModel: viewModel)
        }
    }

    @ViewBuilder
    private var quickStartRegenerationSection: some View {
        if viewModel.projectSnapshot?.project.creationMode == .quickStart {
            Section {
                Button {
                    isPresentingQuickStartRegeneration = true
                } label: {
                    NovelSettingsRow(
                        systemImage: "arrow.clockwise",
                        title: "重新生成设定建议",
                        value: "",
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
                .disabled(!viewModel.canMutate || viewModel.branchSnapshot?.branch.activeRunID != nil)
            } footer: {
                Text("会基于「快速开始」的原始题材重新生成一批设定建议，可选填调整方向。")
            }
        }
    }

    private func configurationSection(_ project: NovelProjectSnapshot) -> some View {
        Section("项目设置") {
            Menu {
                Button {
                    Task { await viewModel.setModelPolicy(.global) }
                } label: {
                    Label("跟随全局模型", systemImage: "arrow.triangle.2.circlepath")
                }

                Button {
                    onChooseFixedModel()
                } label: {
                    Label("选择固定模型", systemImage: "cpu")
                }
            } label: {
                NovelSettingsRow(
                    systemImage: "cpu",
                    title: "项目模型",
                    value: modelPolicyName(project.project.modelPolicy),
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canMutate)

            Button(action: onEditPolishPreference) {
                NovelSettingsRow(
                    systemImage: "wand.and.sparkles",
                    title: "整章润色偏好",
                    value: project.project.polishPreference.isEmpty ? "未设置" : "已设置",
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canMutate)

            Button(action: onPreviewContext) {
                NovelSettingsRow(
                    systemImage: "scope",
                    title: "本次上下文预览",
                    value: "查看注入原因与占用",
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)
            .disabled(!viewModel.canMutate)
        }
    }

    @ViewBuilder
    private var proposalsSection: some View {
        if let proposals = viewModel.branchSnapshot?.activeSettingProposals, !proposals.isEmpty {
            Section("设定建议") {
                ForEach(proposals, id: \.id) { proposal in
                    VStack(alignment: .leading, spacing: 10) {
                        Text(proposal.title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)

                        Text(proposal.content)
                            .font(.subheadline)
                            .foregroundStyle(AmberTheme.foreground2)
                            .lineLimit(4)

                        HStack(spacing: 12) {
                            Button("拒绝", role: .destructive) {
                                Task {
                                    await viewModel.resolveProposal(proposal.id, resolution: .reject)
                                }
                            }
                            .buttonStyle(.bordered)

                            Button("确认并写入") {
                                onAcceptProposal(proposal)
                            }
                            .buttonStyle(.borderedProminent)
                        }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .padding(.vertical, 6)
                    .disabled(!viewModel.canMutate)
                }
            }
        }
    }

    private func materialsSection(_ project: NovelProjectSnapshot) -> some View {
        Section {
            if viewModel.activeMaterials.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "books.vertical")
                        .font(.title2)
                        .foregroundStyle(AmberTheme.accent)
                    Text("还没有项目设定")
                        .font(.headline)
                    Text("世界观、人物、大纲和写作要求都可以独立维护。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 22)
            } else {
                ForEach(viewModel.activeMaterials, id: \.id) { material in
                    let revision = NovelPresentation.currentRevision(for: material, in: project)
                    Button {
                        onEditMaterial(material)
                    } label: {
                        NovelMaterialRow(material: material, revision: revision)
                    }
                    .buttonStyle(.plain)
                    .disabled(!viewModel.canMutate)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            pendingDelete = NovelMaterialDeleteCandidate(
                                material: material,
                                title: revision?.title ?? material.kind.displayName
                            )
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }
            }

            Button {
                onEditMaterial(nil)
            } label: {
                Label("新增资料", systemImage: "plus")
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .disabled(!viewModel.canMutate)
        } header: {
            Text("项目资料")
        } footer: {
            Text("项目资料由所有分支共享；分支需要不同版本时，在“分支”页设置显式覆盖。")
        }
    }

    private func modelPolicyName(_ policy: NovelProjectModelPolicy) -> String {
        _ = sharedSettings.revision
        return NovelPresentation.modelDisplayName(for: policy, sharedSettings: sharedSettings)
    }
}

struct NovelSettingsRow: View {
    let systemImage: String
    let title: String
    let value: String
    var showsChevron = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 28, height: 28)

            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)

            Spacer(minLength: 8)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.8)

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }
}

struct NovelMaterialRow: View {
    let material: NovelMaterialRecord
    let revision: NovelMaterialRevisionRecord?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: material.kind.systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 34, height: 34)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text(revision?.title ?? material.kind.displayName)
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(material.kind.displayName)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let revision {
                Label(revision.injectionMode.displayName, systemImage: revision.injectionMode.systemImage)
                    .labelStyle(.titleAndIcon)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }
}

private struct NovelMaterialDeleteCandidate: Identifiable {
    let material: NovelMaterialRecord
    let title: String
    var id: NovelMaterialID { material.id }
}

enum NovelMaterialKindChoice: String, CaseIterable, Identifiable {
    case world
    case character
    case masterOutline
    case writingRequirements
    case custom

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .world: "世界观"
        case .character: "人物档案"
        case .masterOutline: "总剧情大纲"
        case .writingRequirements: "写作要求"
        case .custom: "自定义"
        }
    }

    init(kind: NovelMaterialKind) {
        switch kind {
        case .world: self = .world
        case .character: self = .character
        case .masterOutline: self = .masterOutline
        case .writingRequirements: self = .writingRequirements
        case .decisionLog: self = .custom
        case .custom: self = .custom
        }
    }

    func materialKind(customName: String) -> NovelMaterialKind {
        switch self {
        case .world: .world
        case .character: .character
        case .masterOutline: .masterOutline
        case .writingRequirements: .writingRequirements
        case .custom: .custom(customName.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }
}

struct NovelMaterialEditorSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let material: NovelMaterialRecord?

    @State private var kindChoice: NovelMaterialKindChoice
    @State private var customKindName: String
    @State private var title: String
    @State private var content: String
    @State private var tags: String
    @State private var aliases: String
    @State private var injectionMode: NovelInjectionMode

    init(
        viewModel: NovelCreationViewModel,
        material: NovelMaterialRecord?,
        suggestedKind: NovelMaterialKind = .world
    ) {
        self.viewModel = viewModel
        self.material = material
        let revision = material.flatMap { material in
            viewModel.projectSnapshot.flatMap {
                NovelPresentation.currentRevision(for: material, in: $0)
            }
        }
        let kind = material?.kind ?? suggestedKind
        self._kindChoice = State(initialValue: NovelMaterialKindChoice(kind: kind))
        if case .custom(let value) = kind {
            self._customKindName = State(initialValue: value)
        } else {
            self._customKindName = State(initialValue: "")
        }
        self._title = State(initialValue: revision?.title ?? "")
        self._content = State(initialValue: revision?.content ?? "")
        self._tags = State(initialValue: revision?.tags.joined(separator: "，") ?? "")
        self._aliases = State(initialValue: material?.aliases.joined(separator: "，") ?? "")
        self._injectionMode = State(initialValue: revision?.injectionMode ?? .smart)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("分类") {
                    Picker("资料类型", selection: $kindChoice) {
                        ForEach(NovelMaterialKindChoice.allCases) { choice in
                            Text(choice.displayName).tag(choice)
                        }
                    }
                    .disabled(material != nil)

                    if kindChoice == .custom {
                        TextField("自定义类型名称", text: $customKindName)
                            .disabled(material != nil)
                    }
                }

                Section("内容") {
                    TextField("标题", text: $title)
                    if kindChoice == .character {
                        TextField("曾用名或别名，用逗号分隔", text: $aliases)
                    }
                    TextEditor(text: $content)
                        .frame(minHeight: 220)
                    TextField("标签，用逗号分隔", text: $tags)
                }

                Section {
                    Picker("默认注入", selection: $injectionMode) {
                        ForEach(NovelInjectionMode.allCases, id: \.self) { mode in
                            Label(mode.displayName, systemImage: mode.systemImage).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("默认注入")
                } footer: {
                    Text("常驻始终注入，智能按本次输入选择，关闭默认不注入。每次生成仍可临时调整。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle(material == nil ? "新增资料" : "编辑资料")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(viewModel.isPerforming)
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

    private var canSave: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            (kindChoice != .custom || !customKindName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    private func save() {
        let parsedTags = tags
            .components(separatedBy: CharacterSet(charactersIn: ",，\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let parsedAliases = aliases
            .components(separatedBy: CharacterSet(charactersIn: ",，\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.saveMaterial(
                materialID: material?.id,
                kind: kindChoice.materialKind(customName: customKindName),
                title: title,
                content: content,
                tags: parsedTags,
                injectionMode: injectionMode,
                aliases: parsedAliases
            )
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }
}

struct NovelPolishPreferenceSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    @State private var preference: String

    init(viewModel: NovelCreationViewModel) {
        self.viewModel = viewModel
        self._preference = State(initialValue: viewModel.projectSnapshot?.project.polishPreference ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $preference)
                        .frame(minHeight: 220)
                } header: {
                    Text("偏好")
                } footer: {
                    Text("这里只控制措辞、节奏、描写和对话风格。系统固定约束仍禁止新增、删除或重排剧情事实。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("整章润色偏好")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(viewModel.isPerforming)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func save() {
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.setPolishPreference(preference)
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }
}

struct NovelQuickStartRegenerationSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    @State private var guidance = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $guidance)
                        .frame(minHeight: 160)
                } header: {
                    Text("调整方向（可选）")
                } footer: {
                    Text("题材和核心创意不会改变，只是把这段说明并入本次生成请求，让模型知道应该往哪个方向调整。留空则按原方式重新生成。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("重新生成设定建议")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("开始") { start() }
                        .disabled(viewModel.isPerforming)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func start() {
        Task { @MainActor in
            viewModel.clearError()
            // startQuickStartSuggestions 的守卫(正在执行/已有活跃 run/需重载)
            // 会静默返回 nil。此时不能关闭 sheet——否则用户以为已经开始生成,
            // 实际什么都没发生。留在原地让用户可以重试。
            let runID = await viewModel.startQuickStartSuggestions(guidance: guidance)
            guard viewModel.errorMessage == nil, runID != nil else { return }
            dismiss()
        }
    }
}

struct NovelProposalAcceptanceSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let proposal: NovelSettingProposalRecord

    @State private var kindChoice: NovelMaterialKindChoice?
    @State private var customKindName = ""
    @State private var selectedMaterialID: NovelMaterialID?
    @State private var tags = ""
    @State private var aliases: String
    @State private var injectionMode: NovelInjectionMode = .smart

    init(viewModel: NovelCreationViewModel, proposal: NovelSettingProposalRecord) {
        self.viewModel = viewModel
        self.proposal = proposal
        self._kindChoice = State(initialValue: Self.initialKindChoice(for: proposal))
        self._aliases = State(
            initialValue: proposal.suggestedCharacterAliases?.joined(separator: "，") ?? ""
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("建议") {
                    Text(proposal.title)
                        .font(.headline)
                    Text(proposal.content)
                        .font(.body)
                        .textSelection(.enabled)
                }

                Section("写入位置") {
                    Picker("资料类型", selection: $kindChoice) {
                        Text("请选择资料类型").tag(nil as NovelMaterialKindChoice?)
                        ForEach(NovelMaterialKindChoice.allCases) { choice in
                            Text(choice.displayName).tag(choice as NovelMaterialKindChoice?)
                        }
                    }

                    if kindChoice == .custom {
                        TextField("自定义类型名称", text: $customKindName)
                    }

                    Picker("目标资料", selection: $selectedMaterialID) {
                        Text("新建资料").tag(nil as NovelMaterialID?)
                        ForEach(matchingMaterials, id: \.id) { material in
                            Text(materialTitle(material)).tag(material.id as NovelMaterialID?)
                        }
                    }

                    TextField("标签，用逗号分隔", text: $tags)

                    if selectedKind == .character {
                        TextField("曾用名或别名，用逗号分隔", text: $aliases)
                    }

                    Picker("默认注入", selection: $injectionMode) {
                        ForEach(NovelInjectionMode.allCases, id: \.self) { mode in
                            Text(mode.displayName).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("确认设定建议")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("写入") { accept() }
                        .disabled(!canAccept || viewModel.isPerforming)
                }
            }
        }
        .onChange(of: kindChoice) { _, _ in selectedMaterialID = nil }
        .onChange(of: customKindName) { _, _ in selectedMaterialID = nil }
        .interactiveDismissDisabled(viewModel.isPerforming)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    static func initialKindChoice(
        for proposal: NovelSettingProposalRecord
    ) -> NovelMaterialKindChoice? {
        guard case .some(.quickStart(_, let kind)) = proposal.origin else { return nil }
        return NovelMaterialKindChoice(kind: kind)
    }

    private var selectedKind: NovelMaterialKind? {
        kindChoice?.materialKind(customName: customKindName)
    }

    private var matchingMaterials: [NovelMaterialRecord] {
        guard let selectedKind else { return [] }
        return viewModel.activeMaterials.filter { $0.kind == selectedKind }
    }

    private var canAccept: Bool {
        guard let kindChoice else { return false }
        return kindChoice != .custom ||
            !customKindName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func materialTitle(_ material: NovelMaterialRecord) -> String {
        guard let project = viewModel.projectSnapshot else { return material.kind.displayName }
        return NovelPresentation.currentRevision(for: material, in: project)?.title ?? material.kind.displayName
    }

    private func accept() {
        guard let selectedKind else { return }
        let parsedTags = tags
            .components(separatedBy: CharacterSet(charactersIn: ",，\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let parsedAliases = aliases
            .components(separatedBy: CharacterSet(charactersIn: ",，\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.resolveProposal(
                proposal.id,
                resolution: .accept(
                    materialID: selectedMaterialID ?? NovelMaterialID(),
                    revisionID: NovelMaterialRevisionID(),
                    kind: selectedKind,
                    tags: parsedTags,
                    injectionMode: injectionMode,
                    aliases: parsedAliases
                )
            )
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }
}

private enum NovelPreviewMaterialOverride: String, CaseIterable, Identifiable {
    case automatic
    case include
    case exclude

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .automatic: "按默认"
        case .include: "本次加入"
        case .exclude: "本次排除"
        }
    }
}

struct NovelInjectionPreviewSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel

    @State private var mode: NovelSessionMode = .writeProse
    @State private var granularity: NovelGenerationGranularity
    @State private var userText = ""
    @State private var budgetTokens = 16_000
    @State private var materialOverrides: [NovelMaterialID: NovelPreviewMaterialOverride] = [:]
    @State private var previewSignature: String?

    init(viewModel: NovelCreationViewModel) {
        self.viewModel = viewModel
        self._granularity = State(
            initialValue: viewModel.projectSnapshot?.project.lastGenerationGranularity ?? .wholeChapter
        )
    }

    var body: some View {
        NavigationStack {
            List {
                intentSection
                materialSection
                if previewSignature == currentPreviewSignature,
                   let preview = viewModel.injectionPreview {
                    resultSection(preview)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("注入规则预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("预览") { preview() }
                        .disabled(!canPreview || viewModel.isPerforming)
                }
            }
            .overlay {
                if viewModel.isPerforming { ProgressView() }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var intentSection: some View {
        Section("生成意图") {
            Picker("模式", selection: $mode) {
                Text("写正文").tag(NovelSessionMode.writeProse)
                Text("讨论规划").tag(NovelSessionMode.discussPlan)
            }
            .pickerStyle(.segmented)

            if mode == .writeProse {
                Picker("粒度", selection: $granularity) {
                    Text("续写片段").tag(NovelGenerationGranularity.continuation)
                    Text("生成整章").tag(NovelGenerationGranularity.wholeChapter)
                }
                .pickerStyle(.segmented)
            }

            TextField("这次准备让 Agent 做什么", text: $userText, axis: .vertical)
                .lineLimit(2...5)

            Stepper(value: $budgetTokens, in: 2_000...64_000, step: 2_000) {
                LabeledContent("上下文长度", value: "约 \(budgetTokens.formatted()) 单位")
            }
        }
    }

    private var materialSection: some View {
        Section {
            if viewModel.activeMaterials.isEmpty {
                Text("当前项目没有资料。")
                    .foregroundStyle(AmberTheme.muted)
            } else {
                ForEach(viewModel.activeMaterials, id: \.id) { material in
                    Picker(materialTitle(material), selection: overrideBinding(for: material.id)) {
                        ForEach(NovelPreviewMaterialOverride.allCases) { value in
                            Text(value.displayName).tag(value)
                        }
                    }
                }
            }
        } header: {
            Text("临时资料调整")
        } footer: {
            Text("仅用于诊断，不影响实际发送。正式发送时会重新计算并留存实际清单。")
        }
    }

    private func resultSection(_ preview: NovelInjectionPreviewSnapshot) -> some View {
        Section("预览结果") {
            LabeledContent("模型", value: preview.resolvedModel.displayName)
            LabeledContent(
                "预计输入",
                value: "\(preview.plan.estimatedInputTokens.formatted()) / \(preview.effectiveInputBudgetTokens.formatted())"
            )
            LabeledContent("上下文段", value: "\(preview.plan.sections.count)")

            ForEach(Array(preview.plan.sections.enumerated()), id: \.offset) { _, section in
                VStack(alignment: .leading, spacing: 3) {
                    Text(section.label)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground)
                    Text("\(section.reason.displayName) · 约 \(section.estimatedTokens) 上下文单位")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .padding(.vertical, 2)
            }

            DisclosureGroup("资料选择明细") {
                ForEach(preview.plan.materialDecisions, id: \.materialID) { decision in
                    HStack {
                        Text(materialTitle(decision.materialID))
                            .lineLimit(1)
                        Spacer()
                        Text(decision.reason.displayName)
                            .font(.caption)
                            .foregroundStyle(decision.included ? AmberTheme.accentGreen : AmberTheme.muted)
                    }
                }
            }
        }
    }

    private var canPreview: Bool {
        viewModel.canMutate && !userText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func overrideBinding(for materialID: NovelMaterialID) -> Binding<NovelPreviewMaterialOverride> {
        Binding(
            get: { materialOverrides[materialID] ?? .automatic },
            set: { materialOverrides[materialID] = $0 }
        )
    }

    private func materialTitle(_ material: NovelMaterialRecord) -> String {
        materialTitle(material.id)
    }

    private func materialTitle(_ materialID: NovelMaterialID) -> String {
        guard let project = viewModel.projectSnapshot,
              let material = project.materials.first(where: { $0.id == materialID }) else {
            return "资料"
        }
        return NovelPresentation.currentRevision(for: material, in: project)?.title ?? material.kind.displayName
    }

    private func preview() {
        guard let projectID = viewModel.selectedProjectID,
              let branchID = viewModel.selectedBranchID else { return }
        let includeIDs = materialOverrides.compactMap { key, value in value == .include ? key : nil }
        let excludeIDs = materialOverrides.compactMap { key, value in value == .exclude ? key : nil }
        let request = NovelInjectionPreviewRequest(
            projectID: projectID,
            branchID: branchID,
            kind: mode == .writeProse ? .prose : .discussion,
            mode: mode,
            granularity: mode == .writeProse ? granularity : nil,
            userText: userText,
            sourceChapterVersionID: nil,
            injectionOverrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: includeIDs,
                forceExcludeMaterialIDs: excludeIDs
            ),
            inputBudgetTokens: budgetTokens
        )
        previewSignature = nil
        let signature = currentPreviewSignature
        Task { @MainActor in
            guard let preview = await viewModel.previewInjection(request),
                  currentPreviewSignature == signature,
                  preview.projectID == projectID,
                  preview.branchID == branchID else { return }
            previewSignature = signature
        }
    }

    private var currentPreviewSignature: String {
        let included = materialOverrides.compactMap { key, value in
            value == .include ? key.description : nil
        }.sorted().joined(separator: ",")
        let excluded = materialOverrides.compactMap { key, value in
            value == .exclude ? key.description : nil
        }.sorted().joined(separator: ",")
        return [
            viewModel.selectedProjectID?.description ?? "",
            viewModel.selectedBranchID?.description ?? "",
            String(viewModel.projectSnapshot?.project.revision ?? -1),
            String(viewModel.branchSnapshot?.branch.headRevision ?? -1),
            mode.rawValue,
            mode == .writeProse ? granularity.rawValue : "discussion",
            userText,
            String(budgetTokens),
            included,
            excluded
        ].joined(separator: "|")
    }
}
