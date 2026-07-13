import SwiftUI

struct NovelChapterManagementView: View {
    let viewModel: NovelCreationViewModel
    let onOpenChapter: (NovelChapterSelection) -> Void

    var body: some View {
        List {
            statusSection
            chaptersSection
            sessionSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 4, for: .scrollContent)
        .background(AmberTheme.background)
    }

    @ViewBuilder
    private var statusSection: some View {
        if let project = viewModel.projectSnapshot, let branch = viewModel.branchSnapshot {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: branch.branch.syncStatus == .synchronized ? "checkmark.circle" : "exclamationmark.arrow.triangle.2.circlepath")
                        .font(.title3)
                        .foregroundStyle(
                            branch.branch.syncStatus == .synchronized
                                ? AmberTheme.accentGreen
                                : AmberTheme.accentAmber
                        )

                    VStack(alignment: .leading, spacing: 3) {
                        Text(branch.branch.name)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)
                        Text(statusDescription(project: project, branch: branch))
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    if let pending = project.pendingOperations.first(where: {
                        $0.branchID == branch.branch.id && $0.kind == .manualSync
                    }) {
                        Button {
                            Task { @MainActor in
                                await viewModel.retryPending(pending.id)
                            }
                        } label: {
                            Label(
                                pending.status == .retryable ? "重试同步" : "继续同步",
                                systemImage: "arrow.clockwise"
                            )
                        }
                        .buttonStyle(.bordered)
                        .disabled(!viewModel.canMutate)
                    } else if branch.branch.syncStatus == .needsSync,
                              !project.pendingOperations.contains(where: {
                                  $0.branchID == branch.branch.id
                              }) {
                        Button {
                            Task { @MainActor in
                                await viewModel.syncManualEdits()
                            }
                        } label: {
                            Label("同步改写", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .buttonStyle(.bordered)
                        .disabled(!viewModel.canMutate)
                    }
                }
                .padding(.vertical, 4)

                quickStartControl
            }
        }
    }

    @ViewBuilder
    private var quickStartControl: some View {
        switch viewModel.quickStartStatus {
        case .idle:
            EmptyView()
        case .starting, .generating:
            Label("正在生成可确认的创作建议", systemImage: "sparkles")
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.accent)
        case .failed(let message):
            HStack(spacing: 12) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    Task { @MainActor in
                        await viewModel.startQuickStartSuggestions()
                    }
                } label: {
                    Label("重新生成", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isPerforming)
            }
        case .persistenceBlocked(let runID, let message):
            HStack(spacing: 12) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    Task { @MainActor in
                        await viewModel.retryQuickStartPersistence(runID: runID)
                    }
                } label: {
                    Label("重试保存", systemImage: "externaldrive.badge.arrow.down")
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isPerforming)
            }
        case .refreshFailed(let message):
            HStack(spacing: 12) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    Task { @MainActor in
                        await viewModel.reloadQuickStartProject()
                    }
                } label: {
                    Label("重新载入", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .disabled(viewModel.isPerforming)
            }
        }
    }

    private var chaptersSection: some View {
        Section {
            if let branch = viewModel.branchSnapshot, !branch.chapterSelections.isEmpty {
                ForEach(Array(branch.chapterSelections.enumerated()), id: \.element.chapterID) { index, selection in
                    if let version = version(selection.versionID) {
                        Button {
                            onOpenChapter(selection)
                        } label: {
                            NovelChapterRow(index: index + 1, version: version)
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "doc.text")
                        .font(.title2)
                        .foregroundStyle(AmberTheme.accent)
                    Text("还没有正式章节")
                        .font(.headline)
                    Text("在创作对话中收录的正文会按章节出现在这里。")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 22)
            }
        } header: {
            Text("正式正文")
        } footer: {
            Text("章节只显示当前分支存档点中的正式版本；未收录候选和待同步草稿不会混入正文。")
        }
    }

    @ViewBuilder
    private var sessionSection: some View {
        if let branch = viewModel.branchSnapshot {
            Section("创作记录") {
                LabeledContent("对话消息", value: "\(branch.session.messages.count)")
                LabeledContent("存档点", value: "\(checkpointCount)")
            }
        }
    }

    private var checkpointCount: Int {
        guard let project = viewModel.projectSnapshot,
              let branch = viewModel.branchSnapshot?.branch else { return 0 }
        return NovelPresentation.checkpointLineage(for: branch, in: project).count
    }

    private func version(_ id: NovelChapterVersionID) -> NovelChapterVersionRecord? {
        viewModel.projectSnapshot?.chapterVersions.first { $0.id == id }
    }

    private func statusDescription(
        project: NovelProjectSnapshot,
        branch: NovelBranchSnapshot
    ) -> String {
        if project.access != .readWrite { return "只读恢复模式" }
        if branch.branch.activeRunID != nil { return "Agent 正在生成" }
        if !project.pendingOperations.isEmpty {
            return "有 \(project.pendingOperations.count) 项正文操作尚未完成"
        }
        return branch.branch.syncStatus.displayName
    }
}

private struct NovelChapterRow: View {
    let index: Int
    let version: NovelChapterVersionRecord

    var body: some View {
        HStack(spacing: 12) {
            Text("\(index)")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 34, height: 34)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text("第 \(index) 章 · \(version.title)")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(version.content.count.formatted()) 字")
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
}

struct NovelChapterVersionsSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let selection: NovelChapterSelection

    @State private var selectedVersionID: NovelChapterVersionID

    init(viewModel: NovelCreationViewModel, selection: NovelChapterSelection) {
        self.viewModel = viewModel
        self.selection = selection
        self._selectedVersionID = State(initialValue: selection.versionID)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if versions.count > 1 {
                    Picker("章节版本", selection: $selectedVersionID) {
                        ForEach(versions, id: \.id) { version in
                            Text(versionLabel(version)).tag(version.id)
                        }
                    }
                    .pickerStyle(.menu)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }

                Divider()
                    .overlay(AmberTheme.borderSoft)

                ScrollView {
                    if let version = selectedVersion {
                        VStack(alignment: .leading, spacing: 16) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(version.title)
                                    .font(.title3.weight(.semibold))
                                    .foregroundStyle(AmberTheme.foreground)
                                Text("\(version.kind.displayName) · \(version.createdAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted)
                            }

                            ChatAssistantMarkdownView(
                                markdown: version.content,
                                renderCacheNamespace: "novel:chapter:\(version.id)"
                            )
                            .frame(maxWidth: .infinity, alignment: .leading)

                            if version.id != selection.versionID {
                                if canDirectlyRestore(version) {
                                    Button {
                                        restore(version.id)
                                    } label: {
                                        Label("恢复为当前版本", systemImage: "clock.arrow.circlepath")
                                            .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .disabled(!canRestore)
                                } else {
                                    VStack(alignment: .leading, spacing: 10) {
                                        Label(
                                            "剧情事实不同，不能直接恢复",
                                            systemImage: "exclamationmark.triangle"
                                        )
                                        .font(.footnote.weight(.medium))
                                        .foregroundStyle(AmberTheme.accentAmber)

                                        Button {
                                            useAsManualRewrite(version)
                                        } label: {
                                            Label("作为手动改写", systemImage: "square.and.pencil")
                                                .frame(maxWidth: .infinity)
                                        }
                                        .buttonStyle(.bordered)
                                        .disabled(!canUseAsManualRewrite)
                                    }
                                }
                            }
                        }
                        .padding(20)
                    }
                }
                .scrollIndicators(.hidden)
            }
            .background(AmberTheme.background)
            .navigationTitle("章节版本")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .interactiveDismissDisabled(viewModel.isPerforming)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private var versions: [NovelChapterVersionRecord] {
        viewModel.projectSnapshot?.chapterVersions
            .filter { $0.chapterID == selection.chapterID }
            .sorted { $0.createdAt > $1.createdAt } ?? []
    }

    private var selectedVersion: NovelChapterVersionRecord? {
        versions.first { $0.id == selectedVersionID }
    }

    private var canRestore: Bool {
        viewModel.canMutate &&
            viewModel.branchSnapshot?.branch.activeRunID == nil &&
            viewModel.branchSnapshot?.branch.syncStatus == .synchronized &&
            selectedVersion.map(canDirectlyRestore) == true &&
            !viewModel.isPerforming
    }

    private var canUseAsManualRewrite: Bool {
        viewModel.canMutate &&
            viewModel.branchSnapshot?.branch.activeRunID == nil &&
            viewModel.branchSnapshot?.branch.syncStatus == .synchronized &&
            viewModel.projectSnapshot?.pendingOperations.isEmpty == true &&
            !viewModel.isPerforming
    }

    private var currentVersion: NovelChapterVersionRecord? {
        versions.first { $0.id == selection.versionID }
    }

    private func canDirectlyRestore(_ version: NovelChapterVersionRecord) -> Bool {
        guard let currentVersion else { return false }
        return NovelPresentation.canDirectlyRestore(version, from: currentVersion)
    }

    private func versionLabel(_ version: NovelChapterVersionRecord) -> String {
        "\(version.kind.displayName) · \(version.createdAt.formatted(date: .numeric, time: .omitted))"
    }

    private func restore(_ versionID: NovelChapterVersionID) {
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.restoreChapterVersion(versionID)
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }

    private func useAsManualRewrite(_ version: NovelChapterVersionRecord) {
        Task { @MainActor in
            viewModel.clearError()
            guard await viewModel.saveManualRewrite(from: version) else { return }
            dismiss()
        }
    }
}
