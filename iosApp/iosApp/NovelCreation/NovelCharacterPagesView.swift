import Foundation
import SwiftUI

enum NovelCharacterEventMatcher {
    static func matches(
        characterName: String,
        aliases: [String] = [],
        entityReferences: [String]
    ) -> Bool {
        let name = normalized(characterName)
        guard !name.isEmpty else { return false }
        let references = entityReferences.map(normalized).filter { !$0.isEmpty }
        let identityNames = Set(([characterName] + aliases).map(normalized).filter { !$0.isEmpty })
        if references.contains(where: identityNames.contains) { return true }
        guard name.count >= 2 else { return false }
        return references.contains { reference in
            reference.count >= 2 && (reference.contains(name) || name.contains(reference))
        }
    }

    static func events(
        for characterName: String,
        aliases: [String] = [],
        in events: [NovelStoryEventRecord]
    ) -> [NovelStoryEventRecord] {
        events
            .filter {
                matches(
                    characterName: characterName,
                    aliases: aliases,
                    entityReferences: $0.entityReferences
                )
            }
            .sorted { $0.sequence > $1.sequence }
    }

    private static func normalized(_ value: String) -> String {
        value
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .filter { !$0.isWhitespace }
            .lowercased()
    }
}

struct NovelCharacterPagesView: View {
    let viewModel: NovelCreationViewModel
    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void

    @State private var route: NovelCharacterRoute?
    @State private var pendingDelete: NovelCharacterDeleteCandidate?

    var body: some View {
        List {
            if !characterProposals.isEmpty {
                Section("待确认角色建议") {
                    ForEach(characterProposals, id: \.id) { proposal in
                        NovelCompendiumProposalCard(
                            proposal: proposal,
                            viewModel: viewModel,
                            onAccept: { onAcceptProposal(proposal) }
                        )
                    }
                }
            }

            Section("角色") {
                if characters.isEmpty {
                    ContentUnavailableView(
                        "还没有角色档案",
                        systemImage: "person.text.rectangle",
                        description: Text("为主要角色建立档案后，收录正文产生的经历会在这里汇总。")
                    )
                } else {
                    ForEach(characters, id: \.id) { character in
                        characterButton(character)
                    }
                }

                Button {
                    onEditMaterial(nil, .character)
                } label: {
                    Label("新增角色", systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .disabled(!viewModel.canMutate)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
        .sheet(item: $route) { route in
            NovelCharacterDetailView(
                viewModel: viewModel,
                materialID: route.materialID
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除“\(candidate.title)”？"),
                message: Text("历史档案和既有剧情记录会保留；后续生成不再注入这份角色档案。"),
                primaryButton: .destructive(Text("删除")) {
                    guard viewModel.canMutate else { return }
                    Task { await viewModel.deleteMaterial(candidate.material.id) }
                },
                secondaryButton: .cancel()
            )
        }
    }

    private var characters: [NovelMaterialRecord] {
        viewModel.activeMaterials.filter {
            if case .character = $0.kind { return true }
            return false
        }
    }

    private var characterProposals: [NovelSettingProposalRecord] {
        viewModel.branchSnapshot?.activeSettingProposals.filter {
            if case .character? = $0.suggestedMaterialKind { return true }
            return false
        } ?? []
    }

    private var currentEvents: [NovelStoryEventRecord] {
        guard let project = viewModel.projectSnapshot,
              let state = viewModel.branchSnapshot?.currentState else { return [] }
        let eventIDs = Set(state.eventIDs)
        return project.events.filter { eventIDs.contains($0.id) }
    }

    private func characterButton(_ material: NovelMaterialRecord) -> some View {
        let title = materialTitle(material)
        let latest = NovelCharacterEventMatcher.events(
            for: title,
            aliases: material.aliases,
            in: currentEvents
        ).first
        return Button {
            route = NovelCharacterRoute(materialID: material.id)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.circle")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 38, height: 38)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(latest.map {
                        "最近经历 · \($0.createdAt.formatted(date: .abbreviated, time: .omitted))"
                    } ?? "暂无经历")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                pendingDelete = NovelCharacterDeleteCandidate(material: material, title: title)
            } label: {
                Label("删除", systemImage: "trash")
            }
            .disabled(!viewModel.canMutate)
        }
    }

    private func materialTitle(_ material: NovelMaterialRecord) -> String {
        guard let project = viewModel.projectSnapshot else { return "未命名角色" }
        return NovelPresentation.effectiveRevision(
            for: material,
            project: project,
            branch: viewModel.branchSnapshot
        )?.title ?? "未命名角色"
    }
}

private struct NovelCharacterDetailView: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let materialID: NovelMaterialID
    @State private var activeSheet: NovelCharacterDetailSheet?

    var body: some View {
        NavigationStack {
            List {
                if let material, let revision {
                    Section("档案") {
                        Text(revision.content)
                            .textSelection(.enabled)
                        Button {
                            activeSheet = hasBranchOverride
                                ? .branchOverride(material)
                                : .materialEditor(material)
                        } label: {
                            Label("编辑角色档案", systemImage: "square.and.pencil")
                        }
                        .disabled(!viewModel.canMutate)
                    }

                    if !relatedProposals.isEmpty {
                        Section("待确认建议") {
                            ForEach(relatedProposals, id: \.id) { proposal in
                                NovelCompendiumProposalCard(
                                    proposal: proposal,
                                    viewModel: viewModel,
                                    onAccept: { activeSheet = .proposal(proposal) }
                                )
                            }
                        }
                    }

                    Section {
                        if relatedEvents.isEmpty {
                            Text("当前分支暂无相关经历。")
                                .foregroundStyle(AmberTheme.muted)
                        } else {
                            ForEach(relatedEvents, id: \.id) { event in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(event.summary)
                                        .foregroundStyle(AmberTheme.foreground)
                                    HStack {
                                        Text(event.kind)
                                        Spacer()
                                        Text(event.createdAt.formatted(date: .abbreviated, time: .omitted))
                                    }
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    } header: {
                        Text("经历")
                    } footer: {
                        Text("经历按名字自动关联，改名后可能需要手动核对。")
                    }
                } else {
                    ContentUnavailableView("角色档案不存在", systemImage: "person.slash")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle(revision?.title ?? "角色")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .materialEditor(let material):
                NovelMaterialEditorSheet(
                    viewModel: viewModel,
                    material: material,
                    suggestedKind: .character
                )
            case .branchOverride(let material):
                NovelBranchOverrideEditorSheet(viewModel: viewModel, material: material)
            case .proposal(let proposal):
                NovelProposalAcceptanceSheet(viewModel: viewModel, proposal: proposal)
            }
        }
    }

    private var material: NovelMaterialRecord? {
        viewModel.activeMaterials.first { $0.id == materialID }
    }

    private var revision: NovelMaterialRevisionRecord? {
        guard let material, let project = viewModel.projectSnapshot else { return nil }
        return NovelPresentation.effectiveRevision(
            for: material,
            project: project,
            branch: viewModel.branchSnapshot
        )
    }

    private var hasBranchOverride: Bool {
        guard let material,
              let project = viewModel.projectSnapshot,
              let current = NovelPresentation.currentRevision(for: material, in: project),
              let effective = revision else { return false }
        return current.id != effective.id
    }

    private var relatedEvents: [NovelStoryEventRecord] {
        guard let project = viewModel.projectSnapshot,
              let state = viewModel.branchSnapshot?.currentState,
              let name = revision?.title else { return [] }
        let eventIDs = Set(state.eventIDs)
        return NovelCharacterEventMatcher.events(
            for: name,
            aliases: material?.aliases ?? [],
            in: project.events.filter { eventIDs.contains($0.id) }
        )
    }

    private var relatedProposals: [NovelSettingProposalRecord] {
        guard let name = revision?.title else { return [] }
        return viewModel.branchSnapshot?.activeSettingProposals.filter {
            guard case .character? = $0.suggestedMaterialKind else { return false }
            return $0.title.localizedCaseInsensitiveContains(name) ||
                $0.content.localizedCaseInsensitiveContains(name)
        } ?? []
    }
}

private enum NovelCharacterDetailSheet: Identifiable {
    case materialEditor(NovelMaterialRecord)
    case branchOverride(NovelMaterialRecord)
    case proposal(NovelSettingProposalRecord)

    var id: String {
        switch self {
        case .materialEditor(let material): "material-\(material.id)"
        case .branchOverride(let material): "override-\(material.id)"
        case .proposal(let proposal): "proposal-\(proposal.id)"
        }
    }
}

private struct NovelCharacterRoute: Identifiable {
    let materialID: NovelMaterialID
    var id: NovelMaterialID { materialID }
}

private struct NovelCharacterDeleteCandidate: Identifiable {
    let material: NovelMaterialRecord
    let title: String
    var id: NovelMaterialID { material.id }
}
