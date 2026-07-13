import SwiftUI

struct NovelChapterReaderRoute: Identifiable {
    let selection: NovelChapterSelection
    var id: NovelChapterID { selection.chapterID }
}

struct NovelChapterReaderView: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let sessionViewModel: NovelSessionViewModel
    let initialSelection: NovelChapterSelection
    let onPolishStarted: () -> Void

    @State private var chapterID: NovelChapterID
    @State private var activeSheet: ReaderSheet?
    @State private var failureMessage: String?

    init(
        viewModel: NovelCreationViewModel,
        sessionViewModel: NovelSessionViewModel,
        initialSelection: NovelChapterSelection,
        onPolishStarted: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.sessionViewModel = sessionViewModel
        self.initialSelection = initialSelection
        self.onPolishStarted = onPolishStarted
        self._chapterID = State(initialValue: initialSelection.chapterID)
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(AmberTheme.borderSoft)
            reader
            chapterNavigation
        }
        .background(AmberTheme.background.ignoresSafeArea())
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .versions(let selection):
                NovelChapterVersionsSheet(viewModel: viewModel, selection: selection)
            case .edit(let version):
                NovelChapterEditSheet(viewModel: viewModel, version: version)
            }
        }
        .alert("操作未完成", isPresented: Binding(
            get: { failureMessage != nil },
            set: { if !$0 { failureMessage = nil } }
        )) {
            Button("好") { failureMessage = nil }
        } message: {
            Text(failureMessage ?? "请稍后重试。")
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("关闭阅读")

            VStack(alignment: .leading, spacing: 2) {
                Text(chapterOrdinalTitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                Text(currentVersion?.title ?? "正文")
                    .font(.headline)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Menu {
                Button {
                    if let selection = currentSelection {
                        activeSheet = .versions(selection)
                    }
                } label: {
                    Label("版本历史", systemImage: "clock.arrow.circlepath")
                }

                Button {
                    if let currentVersion {
                        activeSheet = .edit(currentVersion)
                    }
                } label: {
                    Label(editMenuTitle, systemImage: "square.and.pencil")
                }
                .disabled(editBlockReason != nil)

                Button {
                    startPolish()
                } label: {
                    Label(polishMenuTitle, systemImage: "wand.and.sparkles")
                }
                .disabled(polishBlockReason != nil)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("章节操作")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
    }

    private var reader: some View {
        ScrollView {
            if let version = currentVersion {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(version.title)
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(AmberTheme.foreground)
                        Text("\(version.content.count.formatted()) 字")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }

                    ChatAssistantMarkdownView(
                        markdown: version.content,
                        renderCacheNamespace: "novel:chapter:\(version.id)"
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 22)
                .padding(.top, 28)
                .padding(.bottom, 44)
            } else {
                ContentUnavailableView("章节不存在", systemImage: "doc.text.magnifyingglass")
                    .padding(.top, 80)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var chapterNavigation: some View {
        HStack(spacing: 12) {
            Button {
                moveChapter(by: -1)
            } label: {
                Label("上一章", systemImage: "chevron.left")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(currentIndex <= 0)

            Button {
                moveChapter(by: 1)
            } label: {
                Label("下一章", systemImage: "chevron.right")
                    .labelStyle(.titleAndIcon)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(currentIndex < 0 || currentIndex >= chapterSelections.count - 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(AmberTheme.surface)
    }

    private var chapterSelections: [NovelChapterSelection] {
        viewModel.branchSnapshot?.chapterSelections ?? []
    }

    private var currentIndex: Int {
        chapterSelections.firstIndex { $0.chapterID == chapterID } ?? -1
    }

    private var currentSelection: NovelChapterSelection? {
        chapterSelections.first { $0.chapterID == chapterID }
    }

    private var currentVersion: NovelChapterVersionRecord? {
        guard let versionID = currentSelection?.versionID else { return nil }
        return viewModel.projectSnapshot?.chapterVersions.first { $0.id == versionID }
    }

    private var chapterOrdinalTitle: String {
        guard currentIndex >= 0 else { return "正文" }
        return "第 \(currentIndex + 1) 章"
    }

    private var editBlockReason: String? {
        if !viewModel.canMutate { return "项目当前只读" }
        if viewModel.branchSnapshot?.branch.activeRunID != nil { return "请先停止当前生成" }
        if viewModel.projectSnapshot?.pendingOperations.isEmpty == false { return "请先完成正文操作" }
        if viewModel.isPerforming { return "项目正在处理其他操作" }
        return nil
    }

    private var editMenuTitle: String {
        guard let editBlockReason else { return "编辑本章" }
        return "编辑本章（\(editBlockReason)）"
    }

    private var polishBlockReason: String? {
        if !viewModel.canMutate { return "项目当前只读" }
        if sessionViewModel.isRunning { return "请先停止当前生成" }
        if viewModel.branchSnapshot?.branch.syncStatus == .needsSync { return "请先同步剧情状态" }
        if viewModel.projectSnapshot?.pendingOperations.isEmpty == false { return "请先完成正文操作" }
        if viewModel.isPerforming || sessionViewModel.isBusy { return "项目正在处理其他操作" }
        return nil
    }

    private var polishMenuTitle: String {
        guard let polishBlockReason else { return "整章润色" }
        return "整章润色（\(polishBlockReason)）"
    }

    private func moveChapter(by offset: Int) {
        let destination = currentIndex + offset
        guard chapterSelections.indices.contains(destination) else { return }
        chapterID = chapterSelections[destination].chapterID
    }

    private func startPolish() {
        guard polishBlockReason == nil else { return }
        Task { @MainActor in
            let started = await sessionViewModel.startWholeChapterPolish(chapterID: chapterID)
            guard started else {
                failureMessage = sessionViewModel.errorMessage ?? "润色没有开始，请稍后重试。"
                return
            }
            dismiss()
            onPolishStarted()
        }
    }
}

private enum ReaderSheet: Identifiable {
    case versions(NovelChapterSelection)
    case edit(NovelChapterVersionRecord)

    var id: String {
        switch self {
        case .versions(let selection): "versions-\(selection.chapterID)"
        case .edit(let version): "edit-\(version.id)"
        }
    }
}

private struct NovelChapterEditSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let version: NovelChapterVersionRecord

    @State private var title: String
    @State private var content: String
    @State private var isSaving = false
    @State private var failureMessage: String?

    init(viewModel: NovelCreationViewModel, version: NovelChapterVersionRecord) {
        self.viewModel = viewModel
        self.version = version
        self._title = State(initialValue: version.title)
        self._content = State(initialValue: version.content)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("章节") {
                    TextField("章节标题", text: $title)
                    TextEditor(text: $content)
                        .frame(minHeight: 360)
                }

                Section {
                    Label(
                        "保存后需要同步剧情状态，正式生成前会先提醒你同步。",
                        systemImage: "arrow.triangle.2.circlepath"
                    )
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground2)
                }

                if let failureMessage {
                    Section {
                        Label(failureMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(AmberTheme.accentRed)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("编辑本章")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(!canSave || isSaving)
                }
            }
            .overlay {
                if isSaving { ProgressView("正在保存改写") }
            }
        }
        .interactiveDismissDisabled(isSaving)
    }

    private var canSave: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func save() {
        isSaving = true
        failureMessage = nil
        Task { @MainActor in
            let saved = await viewModel.saveManualRewrite(
                chapterID: version.chapterID,
                title: title,
                content: content
            )
            isSaving = false
            guard saved else {
                failureMessage = viewModel.errorMessage ?? "改写没有保存，请稍后重试。"
                return
            }
            dismiss()
        }
    }
}
