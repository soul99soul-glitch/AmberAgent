import SwiftUI

struct NovelCompendiumView: View {
    let viewModel: NovelCreationViewModel
    @Binding var selection: NovelCompendiumSection

    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void

    var body: some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                categoryPicker
            }
            .background(AmberTheme.background)
    }

    private var categoryPicker: some View {
        Picker("设定分类", selection: $selection) {
            ForEach(NovelCompendiumSection.allCases) { section in
                Text(section.title).tag(section)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
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
                onEditMaterial: onEditMaterial,
                onAcceptProposal: onAcceptProposal
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
    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void

    @State private var pendingDelete: NovelCompendiumMaterialDeleteCandidate?
    @State private var isPresentingQuickStartRegeneration = false

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
                    Button("自定义资料") { onEditMaterial(nil, .custom("自定义")) }
                } label: {
                    Label("新增资料", systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .disabled(!viewModel.canMutate)
            }

            quickStartRegenerationSection
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
                    Label("重新生成设定建议", systemImage: "arrow.clockwise")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .disabled(!viewModel.canMutate || viewModel.branchSnapshot?.branch.activeRunID != nil)
            } footer: {
                Text("会基于「快速开始」的原始题材重新生成一批设定建议，可选填调整方向。")
            }
        }
    }

    private var otherMaterials: [NovelMaterialRecord] {
        viewModel.activeMaterials.filter {
            switch $0.kind {
            case .custom: return true
            case .world, .character, .masterOutline, .writingRequirements, .decisionLog:
                return false
            }
        }
    }

    private var proposals: [NovelSettingProposalRecord] {
        viewModel.branchSnapshot?.activeSettingProposals.filter {
            NovelSettingProposalRoute(kind: $0.suggestedMaterialKind) == .more
        } ?? []
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
