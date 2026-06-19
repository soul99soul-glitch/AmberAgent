import SwiftUI
import Shared

struct MemoryEditView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var text: String
    @State private var scope: MemoryEditScope
    @State private var pinned: Bool
    @State private var showDeleteInfo = false
    @State private var iosRecords: [MemoryRecord] = []

    init(initialText: String, initialScope: String, initialPinned: Bool) {
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
                    iosMemorySection
                    noticeSection
                    contentSection
                    classificationSection
                    previewSection
                    deleteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert("清空全部记忆？", isPresented: $showDeleteInfo) {
            Button("清空", role: .destructive) {
                // [Slice 6] 真清空：逐条 deleteMemory（KMP 内存层），最后统一
                // persist() 一次写空文件。重启后 load() 读到空。
                for record in iosRecords {
                    IosMemoryFactory.shared.deleteMemory(id: record.id)
                }
                IOSMemoryPersistence.shared.persist()
                iosRecords = IosMemoryFactory.shared.getAllRecords()
            }
            Button("取消", role: .cancel) { }
        } message: {
            Text("将删除全部 \(iosRecords.count) 条记忆并写入 Documents/memories/。此操作不可恢复。")
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回核心记忆", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("记忆库")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            MemoryDraftCloseButton {
                dismiss()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    /// Real iOS memory store backed by KMP IosMemoryFactory plus Swift JSON
    /// persistence in Documents/memories/memories.json. This is not Android Room,
    /// but it survives app restart through AppShell load/persist hooks.
    private var iosMemorySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆库（iOS 本地持久化）")

            AmberFormGroup {
                ForEach(Array(iosRecords.enumerated()), id: \.offset) { index, record in
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(record.content)
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                                .lineLimit(3)
                            Text("#\(record.id) · \(record.scope.name) · \(record.kind.name)")
                                .font(.system(size: 10, weight: .regular, design: .monospaced))
                                .foregroundStyle(AmberTheme.muted2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Button {
                            IosMemoryFactory.shared.deleteMemory(id: record.id)
                            // [Slice 6] Persist the deletion to Documents/memories/memories.json
                            // so it survives restart.
                            IOSMemoryPersistence.shared.persist()
                            iosRecords = IosMemoryFactory.shared.getAllRecords()
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 14))
                                .foregroundStyle(AmberTheme.accentRed)
                        }
                        .buttonStyle(.plain)
                    }
                    .frame(minHeight: 52)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 4)

                    if index < iosRecords.count - 1 {
                        Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                    }
                }

                if iosRecords.isEmpty {
                    Text("记忆库为空。在下方「内容」输入文字后点「保存到记忆库」添加。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                }
            }

            HStack(spacing: 12) {
                Button {
                    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    // Map UI scope title to KMP MemoryScope + assistantId bucket.
                    let memScope: MemoryScope
                    let bucket: String
                    switch scope.title {
                    case "核心": memScope = MemoryScope.core; bucket = IosMemoryFactory.shared.GLOBAL_MEMORY_ID
                    case "短期": memScope = MemoryScope.shortTerm; bucket = IosMemoryFactory.shared.SHORT_TERM_MEMORY_ID
                    default: memScope = MemoryScope.longTerm; bucket = IosMemoryFactory.shared.LONG_TERM_MEMORY_ID
                    }
                    IosMemoryFactory.shared.addMemory(
                        scope: memScope,
                        kind: memScope == MemoryScope.shortTerm ? MemoryKind.project : MemoryKind.note,
                        content: trimmed,
                        assistantId: bucket,
                    )
                    // [Slice 6] Persist the new memory to Documents/memories/memories.json.
                    IOSMemoryPersistence.shared.persist()
                    iosRecords = IosMemoryFactory.shared.getAllRecords()
                    text = ""
                } label: {
                    Label("保存到记忆库", systemImage: "plus.circle.fill")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)

            Text("注意：这是 iOS 本地 JSON 记忆库，可读写并随 AppShell 启动恢复；不是 Android Room。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 6)
        }
        .onAppear {
            iosRecords = IosMemoryFactory.shared.getAllRecords()
        }
    }

    private var noticeSection: some View {
        // [Slice 6] 增删已持久化：addMemory/deleteMemory 经 IOSMemoryPersistence
        // 写入 Documents/memories/memories.json，重启后由 AppShell 启动时 load() 恢复。
        MemoryEditNote("记忆增删已持久化到 Documents/memories/memories.json（重启保留）。KMP 端为内存 StateFlow + Swift 持久化层；非 Room。")
            .padding(.bottom, 10)
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
                    Text("写一条记忆；保存后会进入本地记忆库")
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
                        subtitle: "保存时写入 MemoryScope，并影响 ChatViewModel 的 scope 过滤。",
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
                            Text("当前置顶标记仅预览；保存时尚未写入 MemoryRecord.pinned。")
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

            MemoryEditNote(text.isEmpty ? "待保存 · 空内容" : "待保存 · \(scope.title) · \(pinned ? "置顶预览" : "未置顶")")
                .padding(.top, 2)
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "处理方式")
            AmberFormGroup {
                // [Slice 6] 保存已接：addMemory 后 IOSMemoryPersistence.persist()
                // 写入 Documents/memories/memories.json（原子写），重启后 load() 恢复。
                MemoryPreviewLine(label: "保存", value: "已接（持久化）")
                MemoryEditDivider()
                MemoryPreviewLine(label: "写入位置", value: "Documents/memories/")
                MemoryEditDivider()
                MemoryPreviewLine(label: "真实后端", value: "iOS 本地 JSON（非 Room）")
            }
        }
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                showDeleteInfo = true
            } label: {
                Text("清空全部记忆（\(iosRecords.count) 条）")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(iosRecords.isEmpty)
        }
        .padding(.top, 20)
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

    var id: String { rawValue }

    var title: String {
        switch self {
        case .core: "核心"
        case .longTerm: "长期"
        case .shortTerm: "短期"
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
