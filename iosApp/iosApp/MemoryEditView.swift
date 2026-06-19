import SwiftUI
@preconcurrency import Shared

struct MemoryEditView: View {
    @Environment(\.dismiss) private var dismiss

    private let recordId: Int?

    @State private var text: String
    @State private var scope: MemoryEditScope
    @State private var pinned: Bool
    @State private var existingRecord: MemoryRecord?
    @State private var showDeleteInfo = false
    @State private var saveError: String?

    init(recordId: Int? = nil, initialText: String, initialScope: String, initialPinned: Bool) {
        self.recordId = recordId
        self._text = State(initialValue: initialText)
        self._scope = State(initialValue: MemoryEditScope(title: initialScope))
        self._pinned = State(initialValue: initialPinned)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    sourceSection
                    contentSection
                    classificationSection
                    saveSection
                    deleteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: loadRecordIfNeeded)
        .alert(deleteTitle, isPresented: $showDeleteInfo) {
            Button("删除", role: .destructive) {
                deleteCurrentRecord()
            }
            Button("取消", role: .cancel) { }
        } message: {
            Text(deleteMessage)
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回核心记忆", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text(recordId == nil ? "新增记忆" : "编辑记忆")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(recordId.map { "#\($0)" } ?? "本地保存")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            MemoryDraftCloseButton {
                dismiss()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    @ViewBuilder
    private var sourceSection: some View {
        if let existingRecord {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "来源")
                AmberFormGroup {
                    MemoryPreviewLine(label: "来源", value: IOSMemoryLibrary.sourceSummary(existingRecord))
                    MemoryEditDivider()
                    MemoryPreviewLine(label: "置信度", value: String(format: "%.2f", existingRecord.confidence))
                    MemoryEditDivider()
                    MemoryPreviewLine(label: "更新时间", value: dateText(existingRecord.updatedAt))
                    if let lastUsedAt = existingRecord.lastUsedAt?.int64Value {
                        MemoryEditDivider()
                        MemoryPreviewLine(label: "最近使用", value: dateText(lastUsedAt))
                    }
                }
            }
        }
    }

    private var contentSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内容")

            ZStack(alignment: .topLeading) {
                TextEditor(text: $text)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineSpacing(3)
                    .scrollContentBackground(.hidden)
                    .padding(12)
                    .frame(minHeight: 176)

                if text.isEmpty {
                    Text("写一条偏好、事实或项目上下文")
                        .font(.body)
                        .foregroundStyle(AmberTheme.muted2)
                        .padding(.horizontal, 17)
                        .padding(.vertical, 20)
                        .allowsHitTesting(false)
                }
            }
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)

            if let saveError {
                Text(saveError)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 6)
            }
        }
    }

    private var classificationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "归类")
            AmberFormGroup {
                Menu {
                    ForEach(MemoryEditScope.allCases) { option in
                        Button(option.title) {
                            scope = option
                        }
                    }
                } label: {
                    MemoryValueRow(
                        title: "记忆层级",
                        subtitle: "决定聊天参考这条记忆的场景。",
                        value: scope.title
                    )
                }

                MemoryEditDivider()

                Button {
                    pinned.toggle()
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("置顶")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                            Text("置顶记忆会更优先被聊天参考。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        MemoryEditSwitch(isOn: pinned)
                    }
                    .frame(minHeight: 58)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            MemoryEditNote(text.isEmpty ? "待保存 · 空内容" : "待保存 · \(scope.title) · \(pinned ? "置顶" : "未置顶")")
                .padding(.top, 2)
        }
    }

    private var saveSection: some View {
        AmberFormGroup {
            Button {
                saveMemory()
            } label: {
                Label(recordId == nil ? "保存到记忆库" : "保存修改", systemImage: "checkmark.circle.fill")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(canSave ? AmberTheme.accent : AmberTheme.muted2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!canSave)
        }
        .padding(.top, 20)
    }

    @ViewBuilder
    private var deleteSection: some View {
        if recordId != nil {
            AmberFormGroup {
                Button(role: .destructive) {
                    showDeleteInfo = true
                } label: {
                    Text("删除这条记忆")
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 20)
        }
    }

    private var canSave: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var deleteTitle: String {
        recordId == nil ? "删除记忆？" : "删除这条记忆？"
    }

    private var deleteMessage: String {
        "删除后不会再被聊天召回。此操作不可恢复。"
    }

    private func loadRecordIfNeeded() {
        guard let recordId else { return }
        guard let record = IosMemoryFactory.shared.getAllRecords().first(where: { Int($0.id) == recordId }) else {
            saveError = "这条记忆不存在，可能已经被删除。"
            return
        }
        existingRecord = record
        text = record.content
        scope = MemoryEditScope(scope: record.scope)
        pinned = record.pinned
    }

    private func saveMemory() {
        saveError = nil
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            saveError = "内容不能为空。"
            return
        }

        if let existingRecord {
            let updatedAt = nowMillis()
            let updated = MemoryRecord(
                id: existingRecord.id,
                content: trimmed,
                scope: scope.memoryScope,
                kind: existingRecord.kind,
                assistantId: scope.bucket,
                sourceConversationId: existingRecord.sourceConversationId,
                sourceMessageIds: existingRecord.sourceMessageIds,
                supersedesIds: existingRecord.supersedesIds,
                expiresAt: existingRecord.expiresAt,
                confidence: existingRecord.confidence,
                pinned: pinned,
                archived: existingRecord.archived,
                createdAt: existingRecord.createdAt,
                updatedAt: updatedAt,
                lastUsedAt: KotlinLong(value: updatedAt)
            )
            guard let saved = IosMemoryFactory.shared.updateRecord(record: updated) else {
                saveError = "保存失败：这条记忆不存在。"
                IOSMemoryWriteAuditStore.shared.record(action: "edit", status: "failed", reason: "memory not found", memoryId: Int(existingRecord.id))
                return
            }
            IOSMemoryPersistence.shared.persist()
            IOSMemoryWriteAuditStore.shared.record(
                action: "edit",
                status: "user_saved",
                memoryId: Int(saved.id),
                scope: saved.scope.wireName,
                kind: saved.kind.wireName,
                contentPreview: IOSMemoryLibrary.preview(saved.content)
            )
        } else {
            let record = IosMemoryFactory.shared.addDetailedMemory(
                scope: scope.memoryScope,
                kind: scope.memoryScope == MemoryScope.shortTerm ? MemoryKind.project : MemoryKind.note,
                content: trimmed,
                assistantId: scope.bucket,
                sourceConversationId: nil,
                sourceMessageIds: [],
                supersedesIds: [],
                expiresAt: nil,
                confidence: 1,
                pinned: pinned,
                archived: false
            )
            IOSMemoryPersistence.shared.persist()
            IOSMemoryWriteAuditStore.shared.record(
                action: "create",
                status: "user_saved",
                memoryId: Int(record.id),
                scope: record.scope.wireName,
                kind: record.kind.wireName,
                contentPreview: IOSMemoryLibrary.preview(record.content)
            )
        }
        dismiss()
    }

    private func deleteCurrentRecord() {
        guard let recordId else { return }
        IosMemoryFactory.shared.deleteMemory(id: Int32(recordId))
        IOSMemoryPersistence.shared.persist()
        IOSMemoryWriteAuditStore.shared.record(action: "delete", status: "user_deleted", memoryId: recordId)
        dismiss()
    }

    private func dateText(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
        return DateFormatter.localizedString(from: date, dateStyle: .medium, timeStyle: .short)
    }

    private func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}

private enum MemoryEditScope: String, CaseIterable, Identifiable {
    case core
    case longTerm
    case shortTerm

    init(title: String) {
        switch title {
        case "长期":
            self = .longTerm
        case "短期":
            self = .shortTerm
        default:
            self = .core
        }
    }

    init(scope: MemoryScope) {
        if scope == MemoryScope.shortTerm {
            self = .shortTerm
        } else if scope == MemoryScope.longTerm {
            self = .longTerm
        } else {
            self = .core
        }
    }

    var id: String { rawValue }

    var title: String {
        switch self {
        case .core: "核心"
        case .longTerm: "长期"
        case .shortTerm: "短期"
        }
    }

    var memoryScope: MemoryScope {
        switch self {
        case .core: MemoryScope.core
        case .longTerm: MemoryScope.longTerm
        case .shortTerm: MemoryScope.shortTerm
        }
    }

    var bucket: String {
        switch self {
        case .core: IosMemoryFactory.shared.GLOBAL_MEMORY_ID
        case .shortTerm: IosMemoryFactory.shared.SHORT_TERM_MEMORY_ID
        case .longTerm: IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
        }
    }
}

private struct MemoryValueRow: View {
    let title: String
    let subtitle: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct MemoryEditSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private struct MemoryPreviewLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 15)
        .padding(.vertical, 6)
    }
}

private struct MemoryEditDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct MemoryEditNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
    }
}

private struct MemoryDraftCloseButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("关闭")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(height: 36)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("关闭")
    }
}
