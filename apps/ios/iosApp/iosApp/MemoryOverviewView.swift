import SwiftUI
@preconcurrency import Shared

struct MemoryOverviewView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore

    @State private var persistence = IOSMemoryPersistence.shared
    @State private var query = ""
    @State private var selectedTab: MemorySettingsTab = .soul
    @Namespace private var tabSelection
    @State private var scopeFilter: IOSMemoryScopeFilter = .all
    @State private var showSoulEditor = false
    @State private var soulDraft = ""
    @State private var showSoulRollbackConfirmation = false
    @State private var soulPreviousStore = IOSSoulPreviousStore()
    @State private var auditStore = IOSMemoryWriteAuditStore.shared
    @State private var pendingDeleteRecord: MemoryRecord?
    @State private var operationError: String?
    @State private var showClearAuditConfirmation = false
    /// P2-a: 受外部内容影响（POLLUTED）的会话列表；空态时整节不显示。
    @State private var pollutedConversations: [ConversationSummary] = []

    private var filteredRecords: [MemoryRecord] {
        IOSMemoryLibrary.filteredRecords(records: persistence.records, query: query, scopeFilter: scopeFilter)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    chrome
                    switch selectedTab {
                    case .soul:
                        soulSection
                    case .memory:
                        intro
                        loadStatusSection
                        runtimeSection
                        pollutionSection
                        recordsSection
                        auditSection
                    }
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: refresh)
        .alert("无法保存", isPresented: Binding(
            get: { operationError != nil },
            set: { if !$0 { operationError = nil } }
        )) {
            Button("好") { operationError = nil }
        } message: {
            Text(operationError ?? "未知错误")
        }
        .confirmationDialog(
            "删除这条记忆？",
            isPresented: Binding(
                get: { pendingDeleteRecord != nil },
                set: { if !$0 { pendingDeleteRecord = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let record = pendingDeleteRecord {
                    delete(record)
                }
                pendingDeleteRecord = nil
            }
            Button("取消", role: .cancel) { pendingDeleteRecord = nil }
        } message: {
            Text("删除后不可恢复。")
        }
        .confirmationDialog(
            "清除写入审批记录？",
            isPresented: $showClearAuditConfirmation,
            titleVisibility: .visible
        ) {
            Button("清除记录", role: .destructive) {
                auditStore.clear()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只会清除审批历史，不会删除已保存的记忆。")
        }
    }

    private var chrome: some View {
        VStack(spacing: 10) {
            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                    dismiss()
                }

                Spacer()

                VStack(spacing: 2) {
                    Text("灵魂与记忆")
                        .font(.title2.weight(.bold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(selectedTab == .soul ? "Amber 的核心指令" : "\(persistence.records.count) 条本地记忆")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                }

                Spacer()

                if selectedTab == .memory {
                    AmberGlassIconButton(
                        systemImage: "plus",
                        accessibilityLabel: "新增记忆",
                        size: 44,
                        symbolSize: 20,
                        tint: AmberTheme.accent,
                        prominent: true
                    ) {
                        router.navigate(to: .memoryEdit(recordId: nil, text: "", scope: "核心", pinned: false))
                    }
                    .disabled(persistence.loadState == .unreadable)
                } else {
                    Color.clear.frame(width: 44, height: 44)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)

            tabPicker
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
        }
    }

    private var tabPicker: some View {
        HStack(spacing: 4) {
            ForEach(MemorySettingsTab.allCases) { tab in
                Button {
                    withAnimation(.smooth(duration: 0.2)) {
                        selectedTab = tab
                    }
                } label: {
                    Text(tab.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(selectedTab == tab ? AmberTheme.foreground : AmberTheme.foreground2)
                        .frame(maxWidth: .infinity)
                        .frame(height: 38)
                        .background {
                            if selectedTab == tab {
                                RoundedRectangle(cornerRadius: 11, style: .continuous)
                                    .fill(AmberTheme.background.opacity(0.92))
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                                            .stroke(AmberTheme.borderSoft.opacity(0.9), lineWidth: 0.5)
                                    }
                                    .matchedGeometryEffect(id: "selection", in: tabSelection)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selectedTab == tab ? .isSelected : [])
            }
        }
        .padding(4)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }

    private var soulSection: some View {
        VStack(spacing: 0) {
            Text("下次模型请求才会使用这里的正文，不会写入聊天历史。")
                .font(.callout)
                .foregroundStyle(AmberTheme.foreground2)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 6)

            AmberSectionLabel(text: "灵魂")
            AmberFormGroup {
                let soul = sharedSettings.agentRuntime.agentSoulMarkdown.trimmingCharacters(in: .whitespacesAndNewlines)
                VStack(alignment: .leading, spacing: 10) {
                    ScrollView {
                        Text(soul.isEmpty ? "还没有核心指令。" : soul)
                            .font(.system(.footnote, design: .monospaced))
                            .foregroundStyle(soul.isEmpty ? AmberTheme.muted : AmberTheme.foreground)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                    }
                    .frame(maxHeight: 220)
                    Button("编辑核心指令") {
                        soulDraft = sharedSettings.agentRuntime.agentSoulMarkdown
                        showSoulEditor = true
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                    if canRollbackSoul {
                        Button("回退上一个核心指令") {
                            showSoulRollbackConfirmation = true
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.accentAmber)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
        .sheet(isPresented: $showSoulEditor) {
            NavigationStack {
                TextEditor(text: $soulDraft)
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .scrollContentBackground(.hidden)
                    .background(AmberTheme.background)
                    .padding(.horizontal, 12)
                    .navigationTitle("编辑核心指令")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("取消") { showSoulEditor = false }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("保存") {
                                sharedSettings.setAgentSoulMarkdown(soulDraft)
                                showSoulEditor = false
                            }
                        }
                    }
            }
            .presentationDetents([.large])
        }
        .confirmationDialog(
            "回退上一个核心指令？",
            isPresented: $showSoulRollbackConfirmation,
            titleVisibility: .visible
        ) {
            Button("回退", role: .destructive) {
                rollbackSoul()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只有当前版本仍是上次应用版本时才会恢复。之后的手工修改不会被覆盖。")
        }
    }

    private var canRollbackSoul: Bool {
        _ = sharedSettings.revision
        return IOSSoulService(
            workspaceStore: .shared,
            sharedSettings: sharedSettings,
            previousStore: soulPreviousStore
        ).canRollback
    }

    private func rollbackSoul() {
        do {
            try IOSSoulService(
                workspaceStore: .shared,
                sharedSettings: sharedSettings,
                previousStore: soulPreviousStore
            ).rollbackPrevious()
        } catch {
            operationError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    @ViewBuilder
    private var loadStatusSection: some View {
        if persistence.loadState == .unreadable {
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 8) {
                    Label("现有记忆无法读取", systemImage: "exclamationmark.triangle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.accentRed)
                    Text(persistence.lastErrorMessage ?? "已停止写入以保护原文件。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                    Button("重新读取") {
                        persistence.load()
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(minHeight: 44)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            .padding(.top, 14)
        }
    }

    private var intro: some View {
        Text("管理 Amber 会在聊天中参考的本地记忆。记录可以搜索、按范围过滤、查看来源，并在模型尝试写入时留下审批痕迹。")
            .font(.callout)
            .foregroundStyle(AmberTheme.foreground2)
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 6)
    }

    private var runtimeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆范围")
            AmberFormGroup {
                MemoryPresetRow(
                    title: "核心记忆",
                    subtitle: "长期偏好与重要事实。",
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.enableCoreMemory },
                        set: { sharedSettings.setMemoryRuntimeEnabled(core: $0) }
                    )
                )
                MemoryDivider()
                MemoryPresetRow(
                    title: "短期记忆",
                    subtitle: "近期项目上下文。",
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.enableShortTermMemory },
                        set: { sharedSettings.setMemoryRuntimeEnabled(shortTerm: $0) }
                    )
                )
                MemoryDivider()
                MemoryPresetRow(
                    title: "长期记忆",
                    subtitle: "跨会话保留的背景信息。",
                    isOn: Binding(
                        get: { sharedSettings.agentRuntime.enableLongTermMemory },
                        set: { sharedSettings.setMemoryRuntimeEnabled(longTerm: $0) }
                    )
                )
            }
        }
    }

    /// P2-a：受外部内容影响的会话（memoryMode == POLLUTED）。这些会话作为记忆
    /// 抽取源的资格已被暂停；每项可手动「恢复」回 ENABLED。空态不显示本小节。
    @ViewBuilder
    private var pollutionSection: some View {
        if !pollutedConversations.isEmpty {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "受外部内容影响的会话")
                AmberFormGroup {
                    ForEach(Array(pollutedConversations.enumerated()), id: \.element.id) { index, summary in
                        HStack(spacing: 10) {
                            Image(systemName: "globe")
                                .accessibilityHidden(true)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(AmberTheme.accentAmber)
                                .frame(width: 28, height: 28)

                            VStack(alignment: .leading, spacing: 3) {
                                Text(summary.title.isEmpty ? "未命名会话" : summary.title)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(AmberTheme.foreground)
                                    .lineLimit(1)
                                Text("\(pollutedTime(summary.updateAt)) 曾接触外部内容，已暂停记忆抽取")
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            Button("恢复") {
                                restorePollutedConversation(summary.id)
                            }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                            .accessibilityLabel("恢复「\(summary.title.isEmpty ? "未命名会话" : summary.title)」的记忆抽取")
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)

                        if index < pollutedConversations.count - 1 {
                            MemoryDivider(leading: 52)
                        }
                    }
                }
            }
        }
    }

    private func pollutedTime(_ updateAt: KotlinInstant) -> String {
        let seconds = TimeInterval(updateAt.toEpochMilliseconds()) / 1000.0
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: Date(timeIntervalSince1970: seconds), relativeTo: Date())
    }

    private func restorePollutedConversation(_ id: KotlinUuid) {
        Task { @MainActor in
            if await conversationStore.resetConversationMemoryPollution(id) {
                pollutedConversations = await conversationStore.pollutedConversationSummaries()
            } else {
                operationError = "恢复失败，请重试。"
            }
        }
    }

    /// 记忆库工具条：搜索 + 四枚范围过滤（等分铺开，不做横滑簇拥）。
    private var libraryToolbar: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(AmberTheme.muted)
                    .accessibilityHidden(true)
                TextField("搜索内容、来源或标签", text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .buttonStyle(.plain)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
                    .accessibilityLabel("清除搜索")
                }
            }
            .font(.body)
            .padding(.horizontal, 12)
            .frame(height: 44)
            .amberGlass(cornerRadius: AmberTheme.radiusLarge)
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }

            HStack(spacing: 8) {
                ForEach(IOSMemoryScopeFilter.allCases) { filter in
                    Button {
                        scopeFilter = filter
                    } label: {
                        MemoryScopeFilterChip(
                            title: filter.title,
                            isSelected: scopeFilter == filter
                        )
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                    .accessibilityAddTraits(scopeFilter == filter ? .isSelected : [])
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    private var recordsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆库")
            libraryToolbar
            if filteredRecords.isEmpty {
                AmberFormGroup {
                    MemoryEmptyState(isSearching: !persistence.records.isEmpty)
                }
            } else {
                AmberFormGroup {
                    ForEach(Array(filteredRecords.enumerated()), id: \.element.id) { index, record in
                        MemoryRecordRow(
                            record: record,
                            onEdit: {
                                router.navigate(to: .memoryEdit(
                                    recordId: Int(record.id),
                                    text: record.content,
                                    scope: IOSMemoryLibrary.scopeTitle(record.scope),
                                    pinned: record.pinned
                                ))
                            },
                            onDelete: {
                                pendingDeleteRecord = record
                            }
                        )

                        if index < filteredRecords.count - 1 {
                            MemoryDivider(leading: 14)
                        }
                    }
                }
            }
        }
    }

    private var auditSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "写入审批记录")
            AmberFormGroup {
                if auditStore.records.isEmpty {
                    Text("暂无模型写入审批记录。聊天里的新增、修改或删除请求会记录在这里；需要确认时会在聊天中提示。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(auditStore.records.prefix(5))) { record in
                        MemoryAuditRow(record: record)
                        MemoryDivider(leading: 14)
                    }

                    Button(role: .destructive) {
                        showClearAuditConfirmation = true
                    } label: {
                        Text("清除审批记录")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accentRed)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .frame(minHeight: 44)
                            .padding(.horizontal, 14)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func refresh() {
        persistence.refresh()
        Task { @MainActor in
            pollutedConversations = await conversationStore.pollutedConversationSummaries()
        }
    }

    private func delete(_ record: MemoryRecord) {
        guard persistence.records.contains(where: { $0.id == record.id && $0.updatedAt == record.updatedAt }) else {
            operationError = "这条记忆已在其他地方更新或删除，请重试。"
            persistence.refresh()
            return
        }
        let previousRecords = persistence.records
        IosMemoryFactory.shared.deleteMemory(id: record.id)
        guard persistence.persist(previousRecords: previousRecords) else {
            operationError = persistence.lastErrorMessage ?? "无法写入记忆。"
            return
        }
        IOSMemoryWriteAuditStore.shared.record(
            action: "delete",
            status: "user_deleted",
            memoryId: Int(record.id),
            scope: record.scope.wireName,
            kind: record.kind.wireName,
            contentPreview: IOSMemoryLibrary.preview(record.content)
        )
        refresh()
    }
}

private enum MemorySettingsTab: String, CaseIterable, Identifiable {
    case soul
    case memory

    var id: String { rawValue }

    var title: String {
        switch self {
        case .soul: "灵魂"
        case .memory: "记忆"
        }
    }
}

private struct MemoryRecordRow: View {
    let record: MemoryRecord
    let onEdit: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(record.content)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(4)
                    Text(IOSMemoryLibrary.sourceSummary(record))
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 10) {
                    Button(action: onEdit) {
                        Image(systemName: "pencil")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("编辑记忆")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())

                    Button(role: .destructive, action: onDelete) {
                        Image(systemName: "trash")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(AmberTheme.accentRed)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("删除记忆")
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
                }
            }

            HStack(spacing: 6) {
                // 不对用户暴露内部 id / 召回候选等控制台语义。
                MemoryTag(text: IOSMemoryLibrary.scopeTitle(record.scope))
                MemoryTag(text: IOSMemoryLibrary.kindTitle(record.kind))
                if record.pinned {
                    MemoryTag(text: "置顶", tint: AmberTheme.accentAmber)
                }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct MemoryAuditRow: View {
    let record: IOSMemoryWriteAuditRecord

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: statusIcon)
                .accessibilityHidden(true)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(statusColor)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text("\(IOSMemoryLibrary.actionDisplay(record.action)) · \(statusTitle)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private var detail: String {
        // 不对用户展示内部 memoryId（如 #12）；范围/类型/预览足够定位。
        var parts: [String] = []
        if let scope = record.scope { parts.append(IOSMemoryLibrary.scopeDisplay(scope)) }
        if let kind = record.kind { parts.append(IOSMemoryLibrary.kindDisplay(kind)) }
        if let contentPreview = record.contentPreview { parts.append(contentPreview) }
        if parts.isEmpty, !record.reason.isEmpty { return record.reason }
        return parts.joined(separator: " · ")
    }

    private var statusTitle: String {
        switch record.status {
        case "approved": "已批准"
        case "user_saved": "用户保存"
        case "needs_user_action": "等待确认"
        case "denied", "denied_by_user": "已拒绝"
        case "user_deleted": "用户删除"
        default: "失败"
        }
    }

    private var statusIcon: String {
        switch record.status {
        case "approved", "user_saved": "checkmark.circle.fill"
        case "needs_user_action": "hand.raised.fill"
        case "denied", "denied_by_user": "xmark.circle.fill"
        case "user_deleted": "trash.fill"
        default: "exclamationmark.triangle.fill"
        }
    }

    private var statusColor: Color {
        switch record.status {
        case "approved", "user_saved": AmberTheme.accentGreen
        case "needs_user_action": AmberTheme.accentAmber
        case "denied", "denied_by_user", "user_deleted": AmberTheme.accentRed
        default: AmberTheme.accentAmber
        }
    }
}

private struct MemoryTag: View {
    let text: String
    var tint: Color = AmberTheme.muted

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(tint)
            .lineLimit(1)
            .padding(.horizontal, 7)
            .frame(height: 22)
            .background(tint.opacity(0.12), in: Capsule())
    }
}

private struct MemoryScopeFilterChip: View {
    let title: String
    let isSelected: Bool

    var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(isSelected ? AmberTheme.accentInk : AmberTheme.foreground2)
            .lineLimit(1)
            .frame(maxWidth: .infinity)
            .frame(height: 32)
            .background(
                isSelected ? AmberTheme.accent : AmberTheme.surface,
                in: Capsule()
            )
            .overlay {
                Capsule()
                    .stroke(
                        isSelected ? AmberTheme.accent.opacity(0.16) : AmberTheme.borderSoft,
                        lineWidth: 0.5
                    )
            }
    }
}

private struct MemoryEmptyState: View {
    let isSearching: Bool

    private var title: String {
        isSearching ? "没有匹配结果" : "暂无记忆"
    }

    private var message: String {
        isSearching ? "换个关键词或范围再试。" : "点右上角新增，或在聊天中批准模型写入。"
    }

    private var systemImage: String {
        isSearching ? "magnifyingglass" : "tray"
    }

    var body: some View {
        VStack(spacing: 13) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(AmberTheme.surface2.opacity(0.82))
                Image(systemName: systemImage)
                    .font(.system(size: 30, weight: .medium))
                    .foregroundStyle(AmberTheme.muted2)
                    .accessibilityHidden(true)
            }
            .frame(width: 58, height: 58)

            VStack(spacing: 5) {
                Text(title)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 34)
    }
}

private struct MemoryPresetRow: View {
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(AmberTheme.accent)
        }
        .frame(minHeight: 60)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel(title)
    }
}

private struct MemoryDivider: View {
    var leading: CGFloat = 14

    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft.opacity(0.82))
            .frame(height: 0.5)
            .padding(.leading, leading)
    }
}
