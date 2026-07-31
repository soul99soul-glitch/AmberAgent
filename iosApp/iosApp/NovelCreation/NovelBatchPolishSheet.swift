import SwiftUI

/// 批量整章润色入口:选择态 → 运行态 → 报告态,三态共用一个 sheet。状态全部来自
/// `NovelSessionViewModel.batchPolishProgress`;运行中保留控制面板，只有「停止」才会
/// 取消。已采用的章都保留旧版本,可在版本历史撤销。
struct NovelBatchPolishSheet: View {
    @Environment(\.dismiss) private var dismiss

    let chapters: [NovelSessionChapterOption]
    let workspace: NovelCreationViewModel
    let sessionViewModel: NovelSessionViewModel

    @State private var selectedChapterIDs: Set<NovelChapterID>
    @State private var isEditingPolishPreference = false
    @State private var pendingAbandonTransactionID: NovelPendingOperationID?
    @State private var pendingBulkAbandonTransactionIDs: [NovelPendingOperationID] = []
    @State private var abandonTask: Task<Void, Never>?
    @State private var retryTask: Task<Void, Never>?
    @State private var retryingTransactionID: NovelPendingOperationID?
    @State private var isStopRequested = false

    init(
        chapters: [NovelSessionChapterOption],
        workspace: NovelCreationViewModel,
        sessionViewModel: NovelSessionViewModel
    ) {
        self.chapters = chapters
        self.workspace = workspace
        self.sessionViewModel = sessionViewModel
        self._selectedChapterIDs = State(initialValue: Set(chapters.map(\.id)))
    }

    private var progress: NovelBatchPolishProgress? {
        sessionViewModel.batchPolishProgress
    }

    var body: some View {
        NavigationStack {
            content
                .background(AmberTheme.background)
                .navigationTitle("批量整章润色")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbar }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .sheet(isPresented: $isEditingPolishPreference) {
            NovelPolishPreferenceSheet(viewModel: workspace)
        }
        .interactiveDismissDisabled(
            progress?.phase == .running || retryTask != nil || abandonTask != nil
        )
        .onDisappear {
            retryTask?.cancel()
            retryTask = nil
            retryingTransactionID = nil
        }
        .onChange(of: progress?.phase) { _, phase in
            if phase != .running { isStopRequested = false }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch progress?.phase {
        case .running:
            runningPhase
        case .done, .cancelled:
            reportPhase
        case nil:
            selectionPhase
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        switch progress?.phase {
        case .running:
            ToolbarItem(placement: .cancellationAction) {
                Button(isStopRequested ? "正在停止…" : "停止") {
                    requestStop()
                }
                .disabled(isStopRequested)
            }
        case .done, .cancelled:
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { dismiss() }
                    .disabled(retryTask != nil || abandonTask != nil)
            }
        case nil:
            ToolbarItem(placement: .cancellationAction) {
                Button(retryTask == nil ? "取消" : "停止检查") {
                    if let retryTask {
                        retryTask.cancel()
                    } else {
                        dismiss()
                    }
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(selectionCount > 0 ? "开始润色 (\(selectionCount))" : "开始润色") {
                    start()
                }
                .disabled(!canStart)
            }
        }
    }

    // MARK: - 选择态

    private var selectionPhase: some View {
        Form {
            unresolvedTransactionSection
            if unresolvedTransaction == nil,
               let batchPolishBlocker = sessionViewModel.batchPolishBlocker {
                Section {
                    Label(batchPolishBlocker.displayName, systemImage: "info.circle")
                        .foregroundStyle(AmberTheme.accentAmber)
                }
            } else if unresolvedTransaction == nil,
                      let errorMessage = sessionViewModel.errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.circle")
                        .foregroundStyle(AmberTheme.accentRed)
                }
            }
            Section {
                Text("按目录顺序逐章润色,并自动采用通过剧情漂移校验的结果。不会改变剧情事实;随时可停止;每一章都能在版本历史里撤销。")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
            }
            preferenceHintSection
            Section {
                ForEach(chapters) { chapter in
                    Button {
                        toggle(chapter.id)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: selectedChapterIDs.contains(chapter.id)
                                ? "checkmark.square.fill"
                                : "square")
                                .font(.system(size: 20, weight: .medium))
                                .foregroundStyle(
                                    selectedChapterIDs.contains(chapter.id)
                                        ? AmberTheme.accent
                                        : AmberTheme.muted
                                )
                                .frame(width: 24)
                            Text(chapter.displayTitle)
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(chapter.displayTitle)
                    .accessibilityValue(selectedChapterIDs.contains(chapter.id) ? "已选择" : "未选择")
                }
            } header: {
                HStack {
                    Text("选择章节")
                    Spacer()
                    Button(allSelected ? "取消全选" : "全选") {
                        setAllSelected(!allSelected)
                    }
                    .textCase(nil)
                }
            } footer: {
                Text("默认全选。文风方向来自「整章润色偏好」。")
            }
        }
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    private var preferenceHintSection: some View {
        if !hasPolishPreference {
            Section {
                Button {
                    isEditingPolishPreference = true
                } label: {
                    Label("还没设置整章润色偏好,点这里先定文风方向", systemImage: "textformat")
                }
            }
        }
    }

    private var hasPolishPreference: Bool {
        workspace.projectSnapshot?.project.polishPreference
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    // MARK: - 运行态

    private var runningPhase: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let total = max(1, progress?.total ?? 1)
            let completed = progress?.completed ?? 0
            let fraction = Double(completed) / Double(total)
            let elapsed = Int(max(0, context.date.timeIntervalSince(progress?.startedAt ?? .now)))

            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(AmberTheme.accentAmber)
                    Text(isStopRequested
                        ? "正在停止批量润色…"
                        : "正在润色第 \(min(completed + 1, total))/\(total) 章")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground2)
                    Spacer(minLength: 8)
                    Text("\(Int((fraction * 100).rounded(.down)))%")
                        .font(.footnote.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AmberTheme.accentAmber)
                }

                ProgressView(value: fraction)
                    .tint(AmberTheme.accentAmber)

                if let title = progress?.currentTitle {
                    Text("《\(title)》")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                }

                Text("已用时 \(elapsed) 秒 · 每章采用前会做一次剧情漂移校验")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted2)
                    .monospacedDigit()

                Button(role: .destructive) {
                    requestStop()
                } label: {
                    Label(
                        isStopRequested ? "正在停止…" : "停止批量润色",
                        systemImage: "stop.circle"
                    )
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.top, 4)
                .disabled(isStopRequested)

                Spacer()
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - 报告态

    private var reportPhase: some View {
        Form {
            unresolvedTransactionSection
            Section {
                summaryRow("已润色", count: progress?.adoptedCount ?? 0,
                           icon: "checkmark.circle.fill", color: AmberTheme.accent)
                summaryRow("跳过（剧情漂移）", count: progress?.skippedCount ?? 0,
                           icon: "arrow.uturn.left.circle.fill", color: AmberTheme.accentAmber)
                summaryRow("失败", count: progress?.failedCount ?? 0,
                           icon: "exclamationmark.triangle.fill", color: AmberTheme.accentRed)
                if (progress?.cancelledCount ?? 0) > 0 {
                    summaryRow("未处理（已停止）", count: progress?.cancelledCount ?? 0,
                               icon: "minus.circle.fill", color: AmberTheme.muted)
                }
            } header: {
                Text(unresolvedTransaction == nil
                    ? (progress?.phase == .cancelled ? "已停止" : "润色完成")
                    : "批量已暂停")
            }

            Section {
                ForEach(progress?.results ?? [], id: \.chapterID) { result in
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: icon(for: result.outcome))
                            .foregroundStyle(color(for: result.outcome))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(result.title)
                                .font(.body.weight(.medium))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(detail(for: result))
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                        }
                    }
                }
            } header: {
                Text("逐章结果")
            } footer: {
                Text("已润色的章都保留了旧版本,可在该章的版本历史里撤销。")
            }

            Section {
                if unresolvedTransaction == nil,
                   (progress?.failedCount ?? 0) + (progress?.cancelledCount ?? 0) > 0 {
                    Button("重试失败和未处理章节") {
                        sessionViewModel.retryFailedBatchPolish()
                    }
                    .disabled(sessionViewModel.batchPolishBlocker != nil)
                }
                if unresolvedTransaction == nil {
                    Button("再润色一批") { sessionViewModel.clearBatchPolish() }
                }
                if unresolvedTransaction == nil,
                   let blocker = sessionViewModel.batchPolishBlocker {
                    Label(blocker.displayName, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    private func summaryRow(
        _ title: String,
        count: Int,
        icon: String,
        color: Color
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(color)
            Text(title)
                .foregroundStyle(AmberTheme.foreground)
            Spacer()
            Text("\(count)")
                .monospacedDigit()
                .foregroundStyle(AmberTheme.muted)
        }
    }

    private func icon(for outcome: NovelBatchPolishChapterResult.Outcome) -> String {
        switch outcome {
        case .adopted: "checkmark.circle.fill"
        case .skippedDrift: "arrow.uturn.left.circle.fill"
        case .failed: "exclamationmark.triangle.fill"
        case .cancelled: "minus.circle.fill"
        }
    }

    private func color(for outcome: NovelBatchPolishChapterResult.Outcome) -> Color {
        switch outcome {
        case .adopted: AmberTheme.accent
        case .skippedDrift: AmberTheme.accentAmber
        case .failed: AmberTheme.accentRed
        case .cancelled: AmberTheme.muted
        }
    }

    private func detail(for result: NovelBatchPolishChapterResult) -> String {
        switch result.outcome {
        case .adopted: "已采用为新版本"
        case .skippedDrift: result.message ?? "润色改动了剧情事实,已跳过"
        case .failed: result.message ?? "润色失败"
        case .cancelled: result.message ?? "批量停止,未处理"
        }
    }

    @ViewBuilder
    private var unresolvedTransactionSection: some View {
        if let transaction = unresolvedTransaction {
            Section {
                Label(
                    unresolvedTransactionCount > 1
                        ? "还有 \(unresolvedTransactionCount) 项润色检查需要处理"
                        : "有一项润色检查需要处理",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .foregroundStyle(AmberTheme.accentAmber)

                if let message = transaction.lastFailure?.message,
                   !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                }

                if let errorMessage = sessionViewModel.errorMessage {
                    Label(errorMessage, systemImage: "exclamationmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.accentRed)
                }

                if transaction.status == .pending || transaction.status == .retryable {
                    if retryingTransactionID == transaction.id {
                        Button("停止检查", systemImage: "stop.circle") {
                            retryTask?.cancel()
                        }
                    } else {
                        Button("重试检查", systemImage: "arrow.clockwise") {
                            retryPolishTransaction(transaction.id)
                        }
                        .disabled(
                            transactionActionBlocker != nil ||
                                retryTask != nil ||
                                abandonTask != nil
                        )
                    }
                }

                Button("放弃这次润色", systemImage: "xmark.circle", role: .destructive) {
                    pendingAbandonTransactionID = transaction.id
                }
                .disabled(
                    transactionActionBlocker != nil ||
                        retryTask != nil ||
                        abandonTask != nil
                )
                .confirmationDialog(
                    "放弃这次润色？",
                    isPresented: singleAbandonConfirmationBinding,
                    titleVisibility: .visible
                ) {
                    Button("放弃润色", role: .destructive) {
                        guard let transactionID = pendingAbandonTransactionID else { return }
                        pendingAbandonTransactionID = nil
                        abandonTransactions([transactionID])
                    }
                    Button("取消", role: .cancel) {
                        pendingAbandonTransactionID = nil
                    }
                } message: {
                    Text("候选会保留在创作记录中，但不能再作为这次润色版采用。")
                }

                if unresolvedTransactionCount > 1 {
                    Button(
                        "放弃全部 \(unresolvedTransactionCount) 项",
                        systemImage: "xmark.circle.fill",
                        role: .destructive
                    ) {
                        pendingBulkAbandonTransactionIDs = sessionViewModel
                            .unresolvedBranchPolishTransactions
                            .map(\.id)
                    }
                    .disabled(
                        transactionActionBlocker != nil ||
                            retryTask != nil ||
                            abandonTask != nil
                    )
                    .confirmationDialog(
                        "放弃全部 \(pendingBulkAbandonTransactionIDs.count) 项润色？",
                        isPresented: bulkAbandonConfirmationBinding,
                        titleVisibility: .visible
                    ) {
                        Button("全部放弃", role: .destructive) {
                            let transactionIDs = pendingBulkAbandonTransactionIDs
                            pendingBulkAbandonTransactionIDs = []
                            abandonTransactions(transactionIDs)
                        }
                        Button("取消", role: .cancel) {
                            pendingBulkAbandonTransactionIDs = []
                        }
                    } message: {
                        Text("这些候选会保留在创作记录中，但不能再作为润色版采用。")
                    }
                }

                if abandonTask != nil {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("正在放弃未完成的润色…")
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }

                if let transactionActionBlocker,
                   retryTask == nil,
                   abandonTask == nil {
                    Label(transactionActionBlocker.displayName, systemImage: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.muted)
                }
            } header: {
                Text("需要先处理")
            } footer: {
                Text("处理完当前项目后，才能继续批量润色；不会影响已经采用的章节。")
            }
        }
    }

    private var unresolvedTransaction: NovelPendingPolishTransactionRecord? {
        sessionViewModel.unresolvedBranchPolishTransactions.first
    }

    private var unresolvedTransactionCount: Int {
        sessionViewModel.unresolvedBranchPolishTransactions.count
    }

    private var transactionActionBlocker: NovelSessionActionBlocker? {
        NovelSessionComposerPolicy.polishTransactionBlocker(
            access: workspace.projectSnapshot?.access,
            requiresReload: workspace.requiresReload,
            isRunning: sessionViewModel.isRunning,
            isBusy: sessionViewModel.isBusy
        )
    }

    private var singleAbandonConfirmationBinding: Binding<Bool> {
        Binding(
            get: { pendingAbandonTransactionID != nil },
            set: { presented in
                if !presented { pendingAbandonTransactionID = nil }
            }
        )
    }

    private var bulkAbandonConfirmationBinding: Binding<Bool> {
        Binding(
            get: { !pendingBulkAbandonTransactionIDs.isEmpty },
            set: { presented in
                if !presented { pendingBulkAbandonTransactionIDs = [] }
            }
        )
    }

    private func abandonTransactions(_ transactionIDs: [NovelPendingOperationID]) {
        guard !transactionIDs.isEmpty,
              abandonTask == nil,
              retryTask == nil,
              transactionActionBlocker == nil else { return }
        abandonTask = Task { @MainActor in
            _ = await sessionViewModel.abandonPolishTransactions(transactionIDs)
            abandonTask = nil
        }
    }

    private func retryPolishTransaction(_ transactionID: NovelPendingOperationID) {
        guard retryTask == nil,
              abandonTask == nil,
              transactionActionBlocker == nil else { return }
        retryingTransactionID = transactionID
        retryTask = Task { @MainActor in
            defer {
                retryTask = nil
                retryingTransactionID = nil
            }
            await sessionViewModel.retryPolishTransaction(transactionID)
        }
    }

    // MARK: - 选择状态

    private func toggle(_ id: NovelChapterID) {
        if selectedChapterIDs.contains(id) {
            selectedChapterIDs.remove(id)
        } else {
            selectedChapterIDs.insert(id)
        }
    }

    private var allSelected: Bool {
        !chapters.isEmpty && chapters.allSatisfy { selectedChapterIDs.contains($0.id) }
    }

    private func setAllSelected(_ all: Bool) {
        selectedChapterIDs = all ? Set(chapters.map(\.id)) : []
    }

    private var selectionCount: Int {
        chapters.filter { selectedChapterIDs.contains($0.id) }.count
    }

    private var canStart: Bool {
        selectionCount > 0 && sessionViewModel.canStartBatchPolish
    }

    private func start() {
        isStopRequested = false
        let ordered = chapters
            .map(\.selection.chapterID)
            .filter { selectedChapterIDs.contains($0) }
        _ = sessionViewModel.startBatchPolish(chapterIDs: ordered)
    }

    private func requestStop() {
        guard !isStopRequested else { return }
        isStopRequested = true
        sessionViewModel.cancelBatchPolish()
    }
}
