import SwiftUI

struct NovelChapterManagementView: View {
    let viewModel: NovelCreationViewModel
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
        HStack(spacing: 12) {
            Text("目录")
                .font(.headline)
                .foregroundStyle(AmberTheme.foreground)
            Spacer()
            if hasChapters {
                Button(action: onBatchPolish) {
                    Label("批量润色", systemImage: "wand.and.sparkles")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(
                            batchPolishBlockReason == nil ? AmberTheme.accent : AmberTheme.muted
                        )
                }
                .buttonStyle(.plain)
                .disabled(batchPolishBlockReason != nil)
                .accessibilityLabel(batchPolishAccessibilityLabel)
            }
            if let count = viewModel.branchSnapshot?.chapterSelections.count, count > 0 {
                Text("共 \(count) 章")
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .padding(.horizontal, 2)
    }

    private var hasChapters: Bool {
        (viewModel.branchSnapshot?.chapterSelections.count ?? 0) > 0
    }

    /// 与阅读器 `polishBlockReason` 同义,只用 workspace 快照即可表达。真正的发起门禁
    /// 还在 `NovelSessionViewModel.canStartBatchPolish` 兜底,这里只负责按钮的禁用态。
    private var batchPolishBlockReason: String? {
        if !viewModel.canMutate { return "项目当前只读" }
        if viewModel.branchSnapshot?.branch.activeRunID != nil { return "请先停止当前生成" }
        if viewModel.branchSnapshot?.branch.syncStatus == .needsSync { return "请先同步剧情状态" }
        if viewModel.projectSnapshot?.pendingOperations.isEmpty == false { return "请先完成正文操作" }
        if viewModel.isPerforming { return "项目正在处理其他操作" }
        return nil
    }

    private var batchPolishAccessibilityLabel: String {
        guard let batchPolishBlockReason else { return "批量整章润色" }
        return "批量整章润色（\(batchPolishBlockReason)）"
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
                            NovelChapterRow(index: index + 1, version: version)
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

}

private struct NovelChapterRow: View {
    let index: Int
    let version: NovelChapterVersionRecord

    var body: some View {
        HStack(spacing: 12) {
            Text("\(index)")
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 32, height: 32)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 3) {
                Text(NovelPresentation.chapterDisplayTitle(
                    storedTitle: version.title,
                    content: version.content,
                    ordinal: index
                ))
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(version.content.count.formatted()) 字")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
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
