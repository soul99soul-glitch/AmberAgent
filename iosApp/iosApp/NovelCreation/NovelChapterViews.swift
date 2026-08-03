import SwiftUI

struct NovelChapterManagementView: View {
    let viewModel: NovelCreationViewModel
    let sessionViewModel: NovelSessionViewModel
    let onOpenChapter: (NovelChapterSelection) -> Void
    let onBatchPolish: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                directoryHeader
                chapterList
            }
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .background(AmberTheme.background)
    }

    private var directoryHeader: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 12) {
                Text("目录")
                    .font(.headline)
                    .foregroundStyle(AmberTheme.foreground)
                Spacer()
                if hasPolishableChapters {
                    Button(action: onBatchPolish) {
                        Label("批量润色", systemImage: "wand.and.sparkles")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(
                                batchPolishBlocker == nil ? AmberTheme.accent : AmberTheme.muted
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(batchPolishBlocker != nil)
                    .accessibilityLabel(batchPolishAccessibilityLabel)
                }
                if let count = viewModel.branchSnapshot?.chapterSelections.count, count > 0 {
                    Text("共 \(count) 章")
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                }
            }

            if hasPolishableChapters, let batchPolishBlocker {
                Label(batchPolishBlocker.displayName, systemImage: "info.circle")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(.horizontal, 2)
    }

    private var hasPolishableChapters: Bool {
        guard let project = viewModel.projectSnapshot,
              let branch = viewModel.branchSnapshot else { return false }
        let selectedChapterIDs = Set(branch.chapterSelections.map(\.chapterID))
        return project.chapters.contains {
            selectedChapterIDs.contains($0.id) && $0.discardedAt == nil
        }
    }

    private var batchPolishBlocker: NovelSessionActionBlocker? {
        sessionViewModel.batchPolishBlocker
    }

    private var batchPolishAccessibilityLabel: String {
        guard let batchPolishBlocker else { return "批量整章润色" }
        return "批量整章润色（\(batchPolishBlocker.displayName)）"
    }

    @ViewBuilder
    private var chapterList: some View {
        if let branch = viewModel.branchSnapshot, !branch.chapterSelections.isEmpty {
            VStack(spacing: 0) {
                ForEach(Array(branch.chapterSelections.enumerated()), id: \.element.chapterID) { index, selection in
                    if let version = version(selection.versionID) {
                        Button {
                            onOpenChapter(selection)
                        } label: {
                            NovelChapterRow(
                                index: index + 1,
                                version: version,
                                isDiscarded: isDiscarded(selection.chapterID)
                            )
                        }
                        .buttonStyle(.plain)

                        if index < branch.chapterSelections.count - 1 {
                            Divider()
                                .overlay(AmberTheme.borderSoft)
                                .padding(.leading, 58)
                        }
                    }
                }
            }
            .background(
                AmberTheme.surface,
                in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
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
            .padding(.vertical, 24)
            .background(
                AmberTheme.surface,
                in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
        }
    }

    private func version(_ id: NovelChapterVersionID) -> NovelChapterVersionRecord? {
        viewModel.projectSnapshot?.chapterVersions.first { $0.id == id }
    }

    private func isDiscarded(_ chapterID: NovelChapterID) -> Bool {
        viewModel.projectSnapshot?.chapters.first { $0.id == chapterID }?.discardedAt != nil
    }

}

private struct NovelChapterRow: View {
    let index: Int
    let version: NovelChapterVersionRecord
    let isDiscarded: Bool

    var body: some View {
        HStack(spacing: 12) {
            Text("\(index)")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 32, height: 32)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 7) {
                    Text(NovelPresentation.chapterDisplayTitle(
                        storedTitle: version.title,
                        content: version.content,
                        ordinal: index
                    ))
                        .font(.body.weight(.medium))
                        .foregroundStyle(isDiscarded ? AmberTheme.muted : AmberTheme.foreground)
                        .lineLimit(1)
                    if isDiscarded {
                        Text("已废弃")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(AmberTheme.accentAmber.opacity(0.12), in: Capsule())
                    }
                }
                Text("\(version.content.count.formatted()) 字")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityValue(isDiscarded ? "已废弃，不进入生成上下文" : "")
    }
}

struct NovelChapterVersionsSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let selection: NovelChapterSelection

    @State private var selectedVersionID: NovelChapterVersionID
    @State private var isSubmitting = false
    @State private var operationProgressTitle = ""
    @State private var failureMessage: String?

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
                    .disabled(isSubmitting)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }

                Divider()
                    .overlay(AmberTheme.borderSoft)

                if isSubmitting {
                    ProgressView(operationProgressTitle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                } else if let failureMessage {
                    Label(failureMessage, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                }

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

                                    if !isSubmitting, let restoreBlockReason {
                                        Label(restoreBlockReason, systemImage: "info.circle")
                                            .font(.footnote)
                                            .foregroundStyle(AmberTheme.muted)
                                    }
                                } else {
                                    VStack(alignment: .leading, spacing: 10) {
                                        Label(
                                            "剧情事实不同，不能直接恢复",
                                            systemImage: "exclamationmark.triangle"
                                        )
                                        .font(.footnote.weight(.medium))
                                        .foregroundStyle(AmberTheme.foreground2)

                                        Button {
                                            useAsManualRewrite(version)
                                        } label: {
                                            Label("作为手动改写", systemImage: "square.and.pencil")
                                                .frame(maxWidth: .infinity)
                                        }
                                        .buttonStyle(.bordered)
                                        .disabled(!canUseAsManualRewrite)

                                        if !isSubmitting, let manualRewriteBlockReason {
                                            Label(manualRewriteBlockReason, systemImage: "info.circle")
                                                .font(.footnote)
                                                .foregroundStyle(AmberTheme.muted)
                                        }
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
                        .disabled(isSubmitting)
                }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
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
        restoreBlockReason == nil && !isSubmitting
    }

    private var canUseAsManualRewrite: Bool {
        manualRewriteBlockReason == nil && !isSubmitting
    }

    private var restoreBlockReason: String? {
        guard let selectedVersion, selectedVersion.id != selection.versionID else {
            return "请选择一个历史版本"
        }
        if isProjectReadOnly { return "项目当前只读" }
        if viewModel.requiresReload { return "请先重新载入项目" }
        if viewModel.branchSnapshot?.branch.activeRunID != nil { return "请先停止当前生成" }
        if viewModel.branchSnapshot?.branch.syncStatus != .synchronized { return "请先同步剧情状态" }
        if currentBranchHasPendingOperations { return "请先完成当前分支的正文操作" }
        if viewModel.isPerforming { return "项目正在处理其他操作" }
        if !viewModel.canMutate { return "当前状态暂不能恢复版本" }
        if !canDirectlyRestore(selectedVersion) { return "剧情事实不同，不能直接恢复" }
        return nil
    }

    private var manualRewriteBlockReason: String? {
        if isProjectReadOnly { return "项目当前只读" }
        if viewModel.requiresReload { return "请先重新载入项目" }
        if viewModel.branchSnapshot?.branch.activeRunID != nil { return "请先停止当前生成" }
        if currentBranchHasPendingOperations { return "请先完成当前分支的正文操作" }
        if viewModel.isPerforming { return "项目正在处理其他操作" }
        if !viewModel.canMutate { return "当前状态暂不能保存改写" }
        return nil
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

    private var currentVersion: NovelChapterVersionRecord? {
        versions.first { $0.id == selection.versionID }
    }

    private func canDirectlyRestore(_ version: NovelChapterVersionRecord) -> Bool {
        guard let currentVersion else { return false }
        return NovelPresentation.canDirectlyRestore(version, from: currentVersion)
    }

    private func versionLabel(_ version: NovelChapterVersionRecord) -> String {
        "\(version.kind.displayName) · \(version.createdAt.formatted(date: .numeric, time: .shortened))"
    }

    private func restore(_ versionID: NovelChapterVersionID) {
        guard canRestore else { return }
        isSubmitting = true
        operationProgressTitle = "正在恢复章节版本"
        failureMessage = nil
        Task { @MainActor in
            viewModel.clearError()
            await viewModel.restoreChapterVersion(versionID)
            isSubmitting = false
            guard viewModel.errorMessage == nil else {
                failureMessage = viewModel.errorMessage ?? "章节版本没有恢复，请稍后重试。"
                return
            }
            dismiss()
        }
    }

    private func useAsManualRewrite(_ version: NovelChapterVersionRecord) {
        guard canUseAsManualRewrite else { return }
        isSubmitting = true
        operationProgressTitle = "正在保存手动改写"
        failureMessage = nil
        Task { @MainActor in
            viewModel.clearError()
            let saved = await viewModel.saveManualRewrite(from: version)
            isSubmitting = false
            guard saved else {
                failureMessage = viewModel.errorMessage ?? "手动改写没有保存，请稍后重试。"
                return
            }
            dismiss()
        }
    }
}
