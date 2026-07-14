import SwiftUI

struct NovelCompendiumView: View {
    let viewModel: NovelCreationViewModel
    let sharedSettings: IOSSharedSettingsStore
    @Binding var selection: NovelCompendiumSection

    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onChooseFixedModel: () -> Void
    let onEditPolishPreference: () -> Void
    let onPreviewInjectionRules: () -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void
    let onManageBranches: () -> Void
    let onImportPackage: () -> Void
    let onExportPackage: () -> Void
    let onExportMarkdown: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Picker("设定分类", selection: $selection) {
                ForEach(NovelCompendiumSection.allCases) { section in
                    Text(section.title).tag(section)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            content
        }
        .background(AmberTheme.background)
    }

    @ViewBuilder
    private var content: some View {
        switch selection {
        case .characters:
            NovelCharacterPagesView(
                viewModel: viewModel,
                onEditMaterial: onEditMaterial,
                onAcceptProposal: onAcceptProposal
            )
        case .world:
            NovelMaterialCategoryView(
                viewModel: viewModel,
                kind: .world,
                title: "世界观",
                emptyText: "还没有世界观资料",
                onEditMaterial: onEditMaterial,
                onAcceptProposal: onAcceptProposal
            )
        case .story:
            NovelStoryCompendiumView(
                viewModel: viewModel,
                onEditMaterial: onEditMaterial,
                onAcceptProposal: onAcceptProposal
            )
        case .more:
            NovelCompendiumMoreView(
                viewModel: viewModel,
                sharedSettings: sharedSettings,
                onEditMaterial: onEditMaterial,
                onChooseFixedModel: onChooseFixedModel,
                onEditPolishPreference: onEditPolishPreference,
                onPreviewInjectionRules: onPreviewInjectionRules,
                onAcceptProposal: onAcceptProposal,
                onManageBranches: onManageBranches,
                onImportPackage: onImportPackage,
                onExportPackage: onExportPackage,
                onExportMarkdown: onExportMarkdown
            )
        }
    }
}

private struct NovelMaterialCategoryView: View {
    let viewModel: NovelCreationViewModel
    let kind: NovelMaterialKind
    let title: String
    let emptyText: String
    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void

    @State private var pendingDelete: NovelCompendiumMaterialDeleteCandidate?

    var body: some View {
        List {
            proposalSection
            Section(title) {
                if materials.isEmpty {
                    ContentUnavailableView(
                        emptyText,
                        systemImage: kind.systemImage,
                        description: Text("新增后，Agent 会按注入规则在创作时参考它。")
                    )
                } else {
                    ForEach(materials, id: \.id) { material in
                        materialButton(material)
                    }
                }

                Button {
                    onEditMaterial(nil, kind)
                } label: {
                    Label("新增\(title)", systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .disabled(!viewModel.canMutate)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除“\(candidate.title)”？"),
                message: Text("历史修订会保留；后续生成不再注入这条资料。"),
                primaryButton: .destructive(Text("删除")) {
                    Task { await viewModel.deleteMaterial(candidate.material.id) }
                },
                secondaryButton: .cancel()
            )
        }
    }

    @ViewBuilder
    private var proposalSection: some View {
        if !proposals.isEmpty {
            Section("待确认建议") {
                ForEach(proposals, id: \.id) { proposal in
                    NovelCompendiumProposalCard(
                        proposal: proposal,
                        viewModel: viewModel,
                        onAccept: { onAcceptProposal(proposal) }
                    )
                }
            }
        }
    }

    private var materials: [NovelMaterialRecord] {
        viewModel.activeMaterials.filter { $0.kind.matchesCategory(kind) }
    }

    private var proposals: [NovelSettingProposalRecord] {
        viewModel.branchSnapshot?.activeSettingProposals.filter {
            $0.suggestedMaterialKind?.matchesCategory(kind) == true
        } ?? []
    }

    private func materialButton(_ material: NovelMaterialRecord) -> some View {
        let revision = viewModel.projectSnapshot.flatMap {
            NovelPresentation.currentRevision(for: material, in: $0)
        }
        return Button {
            onEditMaterial(material, kind)
        } label: {
            NovelMaterialRow(material: material, revision: revision)
        }
        .buttonStyle(.plain)
        .disabled(!viewModel.canMutate)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                pendingDelete = NovelCompendiumMaterialDeleteCandidate(
                    material: material,
                    title: revision?.title ?? material.kind.displayName
                )
            } label: {
                Label("删除", systemImage: "trash")
            }
        }
    }
}

private struct NovelStoryCompendiumView: View {
    let viewModel: NovelCreationViewModel
    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void
    @State private var pendingDelete: NovelCompendiumMaterialDeleteCandidate?

    var body: some View {
        List {
            if !proposals.isEmpty {
                Section("待确认建议") {
                    ForEach(proposals, id: \.id) { proposal in
                        NovelCompendiumProposalCard(
                            proposal: proposal,
                            viewModel: viewModel,
                            onAccept: { onAcceptProposal(proposal) }
                        )
                    }
                }
            }

            Section("总剧情大纲") {
                if outlines.isEmpty {
                    Text("还没有总剧情大纲")
                        .foregroundStyle(AmberTheme.muted)
                } else {
                    ForEach(outlines, id: \.id) { material in
                        let revision = viewModel.projectSnapshot.flatMap {
                            NovelPresentation.currentRevision(for: material, in: $0)
                        }
                        Button {
                            onEditMaterial(material, .masterOutline)
                        } label: {
                            NovelMaterialRow(material: material, revision: revision)
                        }
                        .buttonStyle(.plain)
                        .disabled(!viewModel.canMutate)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                pendingDelete = NovelCompendiumMaterialDeleteCandidate(
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
                    onEditMaterial(nil, .masterOutline)
                } label: {
                    Label("新增剧情大纲", systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .disabled(!viewModel.canMutate)
            }

            if let snapshot = viewModel.branchSnapshot {
                Section("当前分支走向") {
                    LabeledContent("同步状态", value: snapshot.branch.syncStatus.displayName)
                    if !snapshot.currentState.summary.isEmpty {
                        NovelCompendiumTextBlock(title: "剧情摘要", text: snapshot.currentState.summary)
                    }
                    if !snapshot.currentState.branchOutline.isEmpty {
                        NovelCompendiumTextBlock(title: "接下来可能的走向", text: snapshot.currentState.branchOutline)
                    }
                }

                if !events.isEmpty {
                    Section("事件时间线") {
                        ForEach(events, id: \.id) { event in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(event.summary)
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
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除“\(candidate.title)”？"),
                message: Text("历史修订会保留；后续生成不再注入这份剧情大纲。"),
                primaryButton: .destructive(Text("删除")) {
                    Task { await viewModel.deleteMaterial(candidate.material.id) }
                },
                secondaryButton: .cancel()
            )
        }
    }

    private var outlines: [NovelMaterialRecord] {
        viewModel.activeMaterials.filter { $0.kind.matchesCategory(.masterOutline) }
    }

    private var proposals: [NovelSettingProposalRecord] {
        viewModel.branchSnapshot?.activeSettingProposals.filter {
            NovelSettingProposalRoute(kind: $0.suggestedMaterialKind) == .story
        } ?? []
    }

    private var events: [NovelStoryEventRecord] {
        guard let project = viewModel.projectSnapshot,
              let state = viewModel.branchSnapshot?.currentState else { return [] }
        let eventIDs = Set(state.eventIDs)
        return project.events
            .filter { eventIDs.contains($0.id) }
            .sorted { $0.sequence > $1.sequence }
    }
}

private struct NovelCompendiumMoreView: View {
    let viewModel: NovelCreationViewModel
    let sharedSettings: IOSSharedSettingsStore
    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onChooseFixedModel: () -> Void
    let onEditPolishPreference: () -> Void
    let onPreviewInjectionRules: () -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void
    let onManageBranches: () -> Void
    let onImportPackage: () -> Void
    let onExportPackage: () -> Void
    let onExportMarkdown: () -> Void

    @State private var projectName = ""
    @State private var pendingDelete: NovelCompendiumMaterialDeleteCandidate?

    var body: some View {
        List {
            if !proposals.isEmpty {
                Section("其他待确认建议") {
                    ForEach(proposals, id: \.id) { proposal in
                        NovelCompendiumProposalCard(
                            proposal: proposal,
                            viewModel: viewModel,
                            onAccept: { onAcceptProposal(proposal) }
                        )
                    }
                }
            }

            Section("项目") {
                TextField("小说名称", text: $projectName)
                    .disabled(!viewModel.canMutate)
                Button("保存名称") {
                    Task { await viewModel.renameProject(projectName) }
                }
                .disabled(!canSaveName)
            }

            Section("模型与写作") {
                Menu {
                    Button {
                        Task { await viewModel.setModelPolicy(.global) }
                    } label: {
                        Label("跟随全局模型", systemImage: "arrow.triangle.2.circlepath")
                    }
                    Button(action: onChooseFixedModel) {
                        Label("选择固定模型", systemImage: "cpu")
                    }
                } label: {
                    NovelSettingsRow(
                        systemImage: "cpu",
                        title: "项目模型",
                        value: modelName,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
                .disabled(!viewModel.canMutate)

                Button(action: onEditPolishPreference) {
                    NovelSettingsRow(
                        systemImage: "wand.and.sparkles",
                        title: "整章润色偏好",
                        value: polishPreferenceLabel,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
                .disabled(!viewModel.canMutate)

                Button(action: onPreviewInjectionRules) {
                    NovelSettingsRow(
                        systemImage: "scope",
                        title: "注入规则预览",
                        value: "仅用于诊断",
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }

            Section("其他资料") {
                ForEach(otherMaterials, id: \.id) { material in
                    let revision = viewModel.projectSnapshot.flatMap {
                        NovelPresentation.currentRevision(for: material, in: $0)
                    }
                    Button {
                        onEditMaterial(material, material.kind)
                    } label: {
                        NovelMaterialRow(material: material, revision: revision)
                    }
                    .buttonStyle(.plain)
                    .disabled(!viewModel.canMutate)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            pendingDelete = NovelCompendiumMaterialDeleteCandidate(
                                material: material,
                                title: revision?.title ?? material.kind.displayName
                            )
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }

                Menu {
                    Button("写作要求") { onEditMaterial(nil, .writingRequirements) }
                    Button("自定义资料") { onEditMaterial(nil, .custom("自定义")) }
                } label: {
                    Label("新增资料", systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .disabled(!viewModel.canMutate)
            }

            Section("管理") {
                Button(action: onManageBranches) {
                    Label("分支管理", systemImage: "arrow.triangle.branch")
                }
            }

            Section {
                Button(action: onImportPackage) {
                    Label("导入项目包", systemImage: "square.and.arrow.down")
                }
                .disabled(viewModel.isPerforming)
                Button(action: onExportPackage) {
                    Label("导出完整项目包", systemImage: "shippingbox")
                }
                .disabled(viewModel.isPerforming)
                Button(action: onExportMarkdown) {
                    Label("导出当前分支正文", systemImage: "doc.plaintext")
                }
                .disabled(viewModel.isPerforming)
            } header: {
                Text("导入与导出")
            } footer: {
                if viewModel.isPerforming {
                    Text("项目正在处理其他操作，完成后可导入或导出。")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
        .task(id: viewModel.projectSnapshot?.project.id) {
            projectName = viewModel.projectSnapshot?.project.name ?? ""
        }
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除“\(candidate.title)”？"),
                message: Text("历史修订会保留；后续生成不再注入这条资料。"),
                primaryButton: .destructive(Text("删除")) {
                    Task { await viewModel.deleteMaterial(candidate.material.id) }
                },
                secondaryButton: .cancel()
            )
        }
    }

    private var canSaveName: Bool {
        let value = projectName.trimmingCharacters(in: .whitespacesAndNewlines)
        return viewModel.canMutate && !value.isEmpty && value != viewModel.projectSnapshot?.project.name
    }

    private var modelName: String {
        guard let policy = viewModel.projectSnapshot?.project.modelPolicy else { return "读取中" }
        _ = sharedSettings.revision
        return NovelPresentation.modelDisplayName(for: policy, sharedSettings: sharedSettings)
    }

    private var polishPreferenceLabel: String {
        viewModel.projectSnapshot?.project.polishPreference.isEmpty == false ? "已设置" : "未设置"
    }

    private var otherMaterials: [NovelMaterialRecord] {
        viewModel.activeMaterials.filter {
            switch $0.kind {
            case .writingRequirements, .custom: return true
            case .world, .character, .masterOutline: return false
            }
        }
    }

    private var proposals: [NovelSettingProposalRecord] {
        viewModel.branchSnapshot?.activeSettingProposals.filter {
            NovelSettingProposalRoute(kind: $0.suggestedMaterialKind) == .more
        } ?? []
    }
}

struct NovelCompendiumProposalCard: View {
    let proposal: NovelSettingProposalRecord
    let viewModel: NovelCreationViewModel
    let onAccept: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(proposal.title)
                .font(.body.weight(.semibold))
            Text(proposal.content)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(4)
            HStack(spacing: 12) {
                Spacer()
                Button("拒绝", role: .destructive) {
                    Task { await viewModel.resolveProposal(proposal.id, resolution: .reject) }
                }
                .buttonStyle(.bordered)
                Button("确认并写入", action: onAccept)
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 6)
        .disabled(!viewModel.canMutate)
    }
}

private struct NovelCompendiumTextBlock: View {
    let title: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
            Text(text)
                .foregroundStyle(AmberTheme.foreground)
                .textSelection(.enabled)
        }
        .padding(.vertical, 3)
    }
}

private struct NovelCompendiumMaterialDeleteCandidate: Identifiable {
    let material: NovelMaterialRecord
    let title: String
    var id: NovelMaterialID { material.id }
}

extension NovelSettingProposalRecord {
    var suggestedMaterialKind: NovelMaterialKind? {
        guard case .some(.quickStart(_, let suggestedKind)) = origin else { return nil }
        return suggestedKind
    }
}

private extension NovelMaterialKind {
    func matchesCategory(_ category: NovelMaterialKind) -> Bool {
        switch (self, category) {
        case (.world, .world), (.character, .character),
             (.masterOutline, .masterOutline), (.writingRequirements, .writingRequirements):
            true
        case (.custom, .custom):
            true
        default:
            false
        }
    }
}
