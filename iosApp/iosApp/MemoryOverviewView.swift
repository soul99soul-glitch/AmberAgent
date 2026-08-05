import SwiftUI
@preconcurrency import Shared

struct MemoryOverviewView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @State private var persistence = IOSMemoryPersistence.shared
    @State private var query = ""
    @State private var scopeFilter: IOSMemoryScopeFilter = .all
    @State private var auditStore = IOSMemoryWriteAuditStore.shared
    @State private var pendingDeleteRecord: MemoryRecord?
    @State private var operationError: String?
    @State private var showClearAuditConfirmation = false

    private var filteredRecords: [MemoryRecord] {
        IOSMemoryLibrary.filteredRecords(records: persistence.records, query: query, scopeFilter: scopeFilter)
    }

    private var recallRecords: [MemoryRecord] {
        IOSMemoryLibrary.recallCandidates(records: persistence.records, runtime: sharedSettings.agentRuntime)
    }

    private var recallRecordIds: Set<Int32> {
        Set(recallRecords.map(\.id))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    loadStatusSection
                    runtimeSection
                    searchSection
                    recallSection
                    recordsSection
                    auditSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: refresh)
        .alert("无法修改记忆", isPresented: Binding(
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

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("核心记忆")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("\(persistence.records.count) 条本地记忆")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

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
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
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

    private var searchSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "搜索与过滤")
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

                ScrollView(.horizontal) {
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
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                            .accessibilityAddTraits(scopeFilter == filter ? .isSelected : [])
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            .padding(.horizontal, 16)
        }
    }

    private var recallSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "召回解释")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) {
                        ZStack {
                            Circle()
                                .fill(recallRecords.isEmpty ? AmberTheme.surface2 : AmberTheme.accentCyan.opacity(0.14))
                            Image(systemName: recallRecords.isEmpty ? "brain.head.profile" : "brain.head.profile.fill")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(recallRecords.isEmpty ? AmberTheme.muted2 : AmberTheme.accentCyan)
                        }
                        .frame(width: 32, height: 32)
                        .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(recallRecords.count) 条当前可参与召回")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(IOSMemoryLibrary.recallExplanation(records: persistence.records, runtime: sharedSettings.agentRuntime))
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }

                    if !recallRecords.isEmpty {
                        VStack(alignment: .leading, spacing: 5) {
                            ForEach(recallRecords.prefix(3), id: \.id) { record in
                                Text("• \(IOSMemoryLibrary.preview(record.content, limit: 80))")
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.foreground2)
                                    .lineLimit(2)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
    }

    private var recordsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆库")
            if filteredRecords.isEmpty {
                AmberFormGroup {
                    MemoryEmptyState(isSearching: !persistence.records.isEmpty)
                }
            } else {
                AmberFormGroup {
                    ForEach(Array(filteredRecords.enumerated()), id: \.element.id) { index, record in
                        MemoryRecordRow(
                            record: record,
                            isRecallCandidate: recallRecordIds.contains(record.id),
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

private struct MemoryRecordRow: View {
    let record: MemoryRecord
    let isRecallCandidate: Bool
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
                MemoryTag(text: "#\(record.id)")
                MemoryTag(text: IOSMemoryLibrary.scopeTitle(record.scope))
                MemoryTag(text: IOSMemoryLibrary.kindTitle(record.kind))
                if record.pinned {
                    MemoryTag(text: "置顶", tint: AmberTheme.accentAmber)
                }
                if isRecallCandidate {
                    MemoryTag(text: "本次候选", tint: AmberTheme.accentCyan)
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
        var parts: [String] = []
        if let memoryId = record.memoryId { parts.append("#\(memoryId)") }
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
            .frame(height: 32)
            .padding(.horizontal, 13)
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
