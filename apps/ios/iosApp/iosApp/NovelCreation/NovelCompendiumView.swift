import SwiftUI

enum NovelCompendiumMaterialEditTarget: Equatable {
    case projectRevision
    case branchOverride

    static func resolve(
        material: NovelMaterialRecord,
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot?
    ) -> Self {
        guard let current = NovelPresentation.currentRevision(for: material, in: project),
              let effective = NovelPresentation.effectiveRevision(
                  for: material,
                  project: project,
                  branch: branch
              ),
              current.id != effective.id else {
            return .projectRevision
        }
        return .branchOverride
    }
}

struct NovelCompendiumView: View {
    let viewModel: NovelCreationViewModel
    @Binding var selection: NovelCompendiumSection

    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void
    let onOpenChapter: (NovelChapterSelection) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var branchOverrideRoute: NovelCompendiumBranchOverrideRoute?

    var body: some View {
        ZStack {
            content
                .transition(.opacity)
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: selection)
        .safeAreaInset(edge: .top, spacing: 0) {
            categoryPicker
        }
        .background(AmberTheme.background)
        .scrollContentBackground(.hidden)
        .sheet(item: $branchOverrideRoute) { route in
            NovelBranchOverrideEditorSheet(viewModel: viewModel, material: route.material)
        }
    }

    private var categoryPicker: some View {
        Picker("设定分类", selection: $selection) {
            ForEach(NovelCompendiumSection.allCases) { section in
                Text(categoryTitle(section)).tag(section)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(AmberTheme.background)
    }

    private func categoryTitle(_ section: NovelCompendiumSection) -> String {
        let count = pendingProposalCount(for: section)
        return count == 0 ? section.title : "\(section.title) \(count)"
    }

    private func pendingProposalCount(for section: NovelCompendiumSection) -> Int {
        viewModel.branchSnapshot?.activeSettingProposals.filter { proposal in
            let route = NovelSettingProposalRoute(kind: proposal.suggestedMaterialKind)
            return switch section {
            case .characters: route == .characters
            case .world: route == .world
            case .story: route == .story
            case .more: route == .more
            }
        }.count ?? 0
    }

    @ViewBuilder
    private var content: some View {
        switch selection {
        case .characters:
            NovelCharacterPagesView(
                viewModel: viewModel,
                onEditMaterial: openMaterialEditor,
                onAcceptProposal: onAcceptProposal
            )
        case .world:
            NovelMaterialCategoryView(
                viewModel: viewModel,
                kind: .world,
                title: "世界观",
                emptyText: "还没有世界观资料",
                onEditMaterial: openMaterialEditor,
                onAcceptProposal: onAcceptProposal
            )
        case .story:
            NovelStoryCompendiumView(
                viewModel: viewModel,
                onEditMaterial: openMaterialEditor,
                onAcceptProposal: onAcceptProposal,
                onOpenChapter: onOpenChapter
            )
        case .more:
            NovelCompendiumMoreView(
                viewModel: viewModel,
                onEditMaterial: openMaterialEditor,
                onAcceptProposal: onAcceptProposal
            )
        }
    }

    private func openMaterialEditor(
        _ material: NovelMaterialRecord?,
        suggestedKind: NovelMaterialKind
    ) {
        guard let material, let project = viewModel.projectSnapshot else {
            onEditMaterial(material, suggestedKind)
            return
        }
        switch NovelCompendiumMaterialEditTarget.resolve(
            material: material,
            project: project,
            branch: viewModel.branchSnapshot
        ) {
        case .projectRevision:
            onEditMaterial(material, suggestedKind)
        case .branchOverride:
            branchOverrideRoute = NovelCompendiumBranchOverrideRoute(material: material)
        }
    }
}

private struct NovelCompendiumBranchOverrideRoute: Identifiable {
    let material: NovelMaterialRecord
    var id: NovelMaterialID { material.id }
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
                NovelSettingProposalRejectAllButton(viewModel: viewModel)
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
            NovelPresentation.effectiveRevision(
                for: material,
                project: $0,
                branch: viewModel.branchSnapshot
            )
        }
        return Button {
            onEditMaterial(material, kind)
        } label: {
            NovelMaterialRow(material: material, revision: revision)
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                pendingDelete = NovelCompendiumMaterialDeleteCandidate(
                    material: material,
                    title: revision?.title ?? material.kind.displayName
                )
            } label: {
                Label("删除", systemImage: "trash")
            }
            .disabled(!viewModel.canMutate)
        }
    }
}

private struct NovelStoryCompendiumView: View {
    let viewModel: NovelCreationViewModel
    let onEditMaterial: (NovelMaterialRecord?, NovelMaterialKind) -> Void
    let onAcceptProposal: (NovelSettingProposalRecord) -> Void
    let onOpenChapter: (NovelChapterSelection) -> Void
    @State private var pendingDelete: NovelCompendiumMaterialDeleteCandidate?

    var body: some View {
        List {
            if !proposals.isEmpty {
                Section("待确认建议") {
                    NovelSettingProposalRejectAllButton(viewModel: viewModel)
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
                            NovelPresentation.effectiveRevision(
                                for: material,
                                project: $0,
                                branch: viewModel.branchSnapshot
                            )
                        }
                        Button {
                            onEditMaterial(material, .masterOutline)
                        } label: {
                            NovelMaterialRow(material: material, revision: revision)
                        }
                        .buttonStyle(.plain)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                pendingDelete = NovelCompendiumMaterialDeleteCandidate(
                                    material: material,
                                    title: revision?.title ?? material.kind.displayName
                                )
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                            .disabled(!viewModel.canMutate)
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

            NovelContinuityAuditSection(
                viewModel: viewModel,
                onOpenChapter: onOpenChapter
            )

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
                    NovelSettingProposalRejectAllButton(viewModel: viewModel)
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
                        NovelPresentation.effectiveRevision(
                            for: material,
                            project: $0,
                            branch: viewModel.branchSnapshot
                        )
                    }
                    Button {
                        onEditMaterial(material, material.kind)
                    } label: {
                        NovelMaterialRow(material: material, revision: revision)
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            pendingDelete = NovelCompendiumMaterialDeleteCandidate(
                                material: material,
                                title: revision?.title ?? material.kind.displayName
                            )
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                        .disabled(!viewModel.canMutate)
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
            Section("设定建议") {
                Button {
                    isPresentingQuickStartRegeneration = true
                } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 44, height: 44)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(
                                cornerRadius: 13,
                                style: .continuous
                            ))

                        VStack(alignment: .leading, spacing: 4) {
                            Text("重新生成建议")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(quickStartRegenerationSubtitle)
                                .font(.caption)
                                .foregroundStyle(AmberTheme.foreground2)
                                .fixedSize(horizontal: false, vertical: true)
                        }

                        Spacer(minLength: 8)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(AmberTheme.surface, in: RoundedRectangle(
                        cornerRadius: AmberTheme.radiusXLarge,
                        style: .continuous
                    ))
                    .overlay {
                        RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 1)
                    }
                    .contentShape(RoundedRectangle(
                        cornerRadius: AmberTheme.radiusXLarge,
                        style: .continuous
                    ))
                }
                .buttonStyle(.plain)
                .listRowInsets(.init(top: 6, leading: 16, bottom: 6, trailing: 16))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .disabled(!viewModel.canMutate || quickStartRegenerationBlockReason != nil)
                .opacity(viewModel.canMutate && quickStartRegenerationBlockReason == nil ? 1 : 0.55)
                .accessibilityLabel("重新生成建议")
                .accessibilityHint(quickStartRegenerationSubtitle)
            }
        }
    }

    private var quickStartRegenerationSubtitle: String {
        quickStartRegenerationBlockReason ?? "调整本轮想法，生成一套新的设定建议"
    }

    private var quickStartRegenerationBlockReason: String? {
        if case .awaitingUser = viewModel.quickStartStatus {
            return "请先回答创作页中的规划问题，再重新生成建议。"
        }
        let hasRunningRun = viewModel.branchSnapshot?.branch.activeRunID != nil ||
            viewModel.projectSnapshot?.activeRuns.contains(where: { $0.status == .running }) == true
        guard hasRunningRun else { return nil }
        return "已有生成任务正在运行；请先停止或等待完成，再重新生成建议。"
    }

    private var otherMaterials: [NovelMaterialRecord] {
        viewModel.activeMaterials.filter {
            switch $0.kind {
            case .writingRequirements, .custom:
                return true
            case .world, .character, .relationship, .masterOutline, .decisionLog:
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

private enum NovelQuickStartRegenerationDraftMode {
    case guidance
    case coreIdea
}

struct NovelQuickStartRegenerationSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    @State private var draft = ""
    @State private var draftMode = NovelQuickStartRegenerationDraftMode.guidance
    @State private var isStarting = false
    @State private var failureMessage: String?
    @State private var isConfirmingDiscard = false
    @State private var isConfirmingCoreIdeaLoad = false
    @State private var imeBank = NovelIMEFieldBank()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NovelIMETextEditor(
                        text: $draft,
                        placeholder: editorTitle,
                        minHeight: 160,
                        bank: imeBank
                    )
                    .frame(minHeight: 160)
                    .accessibilityLabel(editorTitle)
                } header: {
                    HStack(spacing: 12) {
                        Text(editorTitle)
                        Spacer(minLength: 8)
                        if originalCoreIdea != nil {
                            Button {
                                requestCoreIdeaLoad()
                            } label: {
                                Text("载入核心想法")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AmberTheme.foreground)
                                    .padding(.horizontal, 11)
                                    .frame(height: 32)
                                    .background(AmberTheme.surface2, in: Capsule())
                            }
                            .buttonStyle(.plain)
                            .frame(minHeight: 44)
                            .fixedSize(horizontal: true, vertical: false)
                            .accessibilityHint(
                                "将快速开始时保存的核心想法填入编辑框，不会立即生成"
                            )
                        }
                    }
                    .textCase(nil)
                } footer: {
                    Text(editorFooter)
                }
                if let failureMessage {
                    Section {
                        Label(failureMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(AmberTheme.accentRed)
                    }
                }
            }
            .disabled(isStarting)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("重新生成建议")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { requestDismiss() }
                    }
                        .disabled(isStarting)
                        .confirmationDialog(
                            "放弃本轮编辑？",
                            isPresented: $isConfirmingDiscard,
                            titleVisibility: .visible
                        ) {
                            Button("放弃更改", role: .destructive) { dismiss() }
                            Button("继续编辑", role: .cancel) {}
                        } message: {
                            Text("输入框中尚未发送的内容会丢失。")
                        }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("生成") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { start() }
                    }
                        .disabled(isStarting || viewModel.isPerforming)
                }
            }
            .overlay {
                if isStarting {
                    ProgressView("正在开始生成建议")
                }
            }
            .confirmationDialog(
                "载入最初的核心想法？",
                isPresented: $isConfirmingCoreIdeaLoad,
                titleVisibility: .visible
            ) {
                Button("替换当前内容") { loadOriginalCoreIdea() }
                Button("取消", role: .cancel) {}
            } message: {
                Text("编辑框中尚未发送的内容会被替换，最初保存的内容不会改变。")
            }
        }
        .interactiveDismissDisabled()
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
    }

    private var originalCoreIdea: String? {
        viewModel.projectSnapshot?.project.quickStartSeed?.coreIdea
    }

    private var editorTitle: String {
        switch draftMode {
        case .guidance:
            "调整方向（可选）"
        case .coreIdea:
            "本轮核心想法"
        }
    }

    private var editorFooter: String {
        let outcome = "成功后替换当前未处理建议；失败时保留。"
        switch draftMode {
        case .guidance:
            return "补充本轮调整方向；留空会按最初的题材和核心想法重新生成。\(outcome)"
        case .coreIdea:
            return "可以在最初输入上修改；只影响本轮建议，不会覆盖最初保存的内容。\(outcome)"
        }
    }

    private var hasUnsavedChanges: Bool {
        !draft.isEmpty
    }

    private func requestDismiss() {
        if hasUnsavedChanges {
            isConfirmingDiscard = true
        } else {
            dismiss()
        }
    }

    private func requestCoreIdeaLoad() {
        guard let originalCoreIdea else { return }
        if !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           draft != originalCoreIdea {
            isConfirmingCoreIdeaLoad = true
        } else {
            loadOriginalCoreIdea()
        }
    }

    private func loadOriginalCoreIdea() {
        guard let originalCoreIdea else { return }
        draft = originalCoreIdea
        draftMode = .coreIdea
    }

    private func start() {
        guard !isStarting else { return }
        isStarting = true
        failureMessage = nil
        Task { @MainActor in
            viewModel.clearError()
            // startQuickStartSuggestions 的守卫(正在执行/已有活跃 run/需重载)
            // 会静默返回 nil。此时不能关闭 sheet——否则用户以为已经开始生成,
            // 实际什么都没发生。留在原地让用户可以重试。
            let runID = await viewModel.startQuickStartSuggestions(
                guidance: draftMode == .guidance ? draft : nil,
                coreIdeaOverride: draftMode == .coreIdea ? draft : nil
            )
            isStarting = false
            guard viewModel.errorMessage == nil, runID != nil else {
                failureMessage = viewModel.errorMessage ?? "建议生成没有开始，请稍后重试。"
                return
            }
            dismiss()
        }
    }
}

struct NovelSettingProposalRejectAllButton: View {
    let viewModel: NovelCreationViewModel

    var body: some View {
        Button("拒绝全部待确认", role: .destructive) {
            Task { await viewModel.rejectActiveSettingProposals() }
        }
        .disabled(!viewModel.canMutate)
        .accessibilityLabel("拒绝全部待确认设定建议")
        .accessibilityHint("拒绝当前分支上所有分类的待确认设定建议，不会写入资料")
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
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                Button("确认并写入", action: onAccept)
                    .buttonStyle(.borderedProminent)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
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
        switch origin {
        case .some(.quickStart(_, let suggestedKind)),
             .some(.contextualCharacter(_, _, let suggestedKind)):
            return suggestedKind
        case .some(.derivedState), nil:
            return nil
        }
    }
}

private extension NovelMaterialKind {
    func matchesCategory(_ category: NovelMaterialKind) -> Bool {
        switch (self, category) {
        case (.world, .world), (.character, .character),
             (.relationship, .relationship),
             (.masterOutline, .masterOutline), (.writingRequirements, .writingRequirements):
            true
        case (.custom, .custom):
            true
        default:
            false
        }
    }
}
