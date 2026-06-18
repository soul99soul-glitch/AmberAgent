import SwiftUI
import Shared

struct ConversationStorageView: View {
    let sharedSettings: IOSSharedSettingsStore

    // [Slice 2] 注入 IOSConversationStore（AppShell 已 .environment(conversationStore)，
    // Phase 2 完成）。本页所有"待接"操作改为真接：
    //   - 对话文件数 → conversationStore.summaries.count（IOSConversationStore.swift:25）
    //   - 按时间清理 → 按 summary.updateAt 过滤 + 批量 deleteConversation（IOSConversationStore.swift:174）
    //   - 删除全部 → 确认 alert + 循环 deleteConversation
    //   - 用量统计 → 扫描 Documents/conversations/ 求和
    //   - 清除缓存 → v1 无附件缓存，诚实说明（不再有假动作）
    @Environment(IOSConversationStore.self) private var conversationStore

    @Environment(\.dismiss) private var dismiss

    @State private var pendingAlert: StorageAlert?
    @State private var pendingConfirmation: StorageConfirmation?
    @State private var isProcessing = false
    @State private var lastResultMessage: String?
    @State private var usageBytes: Int64 = 0
    @State private var usageFileCount: Int = 0

    private var conversationCount: Int { conversationStore.summaries.count }

    private var usageItems: [StorageUsageItem] {
        [
            .init(title: "助手条目", detail: "\(sharedSettings.snapshot.assistants.count) 个", color: AmberTheme.accent),
            .init(title: "Provider 模板", detail: "\(sharedSettings.snapshot.providers.count) 个", color: AmberTheme.accentAmber),
            .init(title: "快捷消息", detail: "\(sharedSettings.snapshot.quickMessages.count) 条", color: AmberTheme.accentCyan),
            .init(title: "对话文件", detail: "\(conversationCount) 个", color: AmberTheme.accentGreen)
        ]
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    usageSection
                    cleanupSection
                    deleteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $pendingAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text(alert.primaryTitle)) { }
            )
        }
        // 破坏性操作用 confirmationDialog 二次确认，避免误删。
        .confirmationDialog(
            pendingConfirmation?.title ?? "",
            isPresented: Binding(
                get: { pendingConfirmation != nil },
                set: { if !$0 { pendingConfirmation = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingConfirmation
        ) { confirmation in
            Button("删除", role: .destructive) {
                runConfirmation(confirmation)
            }
            Button("取消", role: .cancel) { }
        } message: { confirmation in
            Text(confirmation.message)
        }
        .task {
            refreshUsageStats()
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("对话存储")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("对话通过 IOSConversationStore 真实持久化在 Documents/conversations/（index.json + 每个会话一个 {id}.json）。本页显示真实文件数与磁盘占用，并支持按时间清理和删除全部。v1 无独立附件缓存。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var usageSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "用量")
            AmberFormGroup {
                VStack(spacing: 0) {
                    StorageUsageBar(bytes: usageBytes, fileCount: usageFileCount)
                        .padding(.horizontal, 15)
                        .padding(.top, 14)
                        .padding(.bottom, 12)

                    VStack(spacing: 6) {
                        ForEach(usageItems) { item in
                            StorageLegendRow(item: item)
                        }
                    }
                    .padding(.horizontal, 15)
                    .padding(.bottom, 8)
                }
            }
        }
    }

    private var cleanupSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "清理")
            AmberFormGroup {
                // [Slice 2] 清除缓存：v1 无附件缓存（图片/文档都以 base64 或本地引用存在
                // 消息体里，没有独立缓存目录）。诚实改成说明行，不再有假删除动作。
                StorageActionRow(
                    systemImage: "arrow.clockwise",
                    title: "清除缓存",
                    subtitle: "v1 无独立附件缓存，无需清理",
                    trailing: "无需清理"
                ) {
                    pendingAlert = .cache
                }

                StorageDivider()

                // [Slice 2] 按时间清理：真接。过滤 updateAt 早于 30 天的非置顶会话，
                // 调 IOSConversationStore.deleteConversation 批量删除。
                StorageActionRow(
                    systemImage: "calendar.badge.clock",
                    title: "清理 30 天前的对话",
                    subtitle: "删除 updateAt 早于 30 天的非置顶会话（\(staleConversationTargetCount) 个候选）",
                    trailing: isProcessing ? "处理中…" : nil
                ) {
                    requestTimeBasedCleanup()
                }
            }

            StorageNote("删除前会弹出二次确认。置顶会话不会被时间清理删除。")
        }
    }

    private var deleteSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                // [Slice 2] 删除全部：真接。确认后循环调 deleteConversation。
                Button {
                    requestDeleteAll()
                } label: {
                    HStack(spacing: 8) {
                        if isProcessing {
                            ProgressView().controlSize(.small)
                        }
                        Text("删除全部对话（\(conversationCount) 个）")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accentAmber)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 52)
                            .contentShape(Rectangle())
                    }
                }
                .buttonStyle(.plain)
                .disabled(isProcessing || conversationCount == 0)
            }
            .padding(.top, 18)

            if let lastResultMessage {
                Text(lastResultMessage)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 7)
            } else {
                StorageNote("删除全部会清空 Documents/conversations/ 下所有会话文件；不可恢复，删除前会二次确认。")
            }
        }
    }

    // MARK: - Derived

    /// 30 天前（毫秒）的时间阈值。
    private var staleThresholdMs: Int64 {
        Int64(Date().addingTimeInterval(-30 * 24 * 60 * 60).timeIntervalSince1970 * 1000)
    }

    /// 符合时间清理条件的会话数（updateAt 早于 30 天且未置顶）。
    private var staleConversationTargetCount: Int {
        let threshold = staleThresholdMs
        return conversationStore.summaries.filter { summary in
            !summary.isPinned && summary.updateAt.toEpochMilliseconds() < threshold
        }.count
    }

    // MARK: - Actions

    private func requestTimeBasedCleanup() {
        let targets = conversationStore.summaries.filter { summary in
            !summary.isPinned && summary.updateAt.toEpochMilliseconds() < staleThresholdMs
        }
        guard !targets.isEmpty else {
            pendingAlert = .noStaleConversations
            return
        }
        pendingConfirmation = .timeCleanup(count: targets.count, ids: targets.map { $0.id })
    }

    private func requestDeleteAll() {
        guard conversationCount > 0 else { return }
        pendingConfirmation = .deleteAll(
            count: conversationCount,
            ids: conversationStore.summaries.map { $0.id }
        )
    }

    private func runConfirmation(_ confirmation: StorageConfirmation) {
        isProcessing = true
        lastResultMessage = nil
        let ids = confirmation.ids
        Task { @MainActor in
            var deleted = 0
            for id in ids {
                await conversationStore.deleteConversation(id: id)
                deleted += 1
            }
            isProcessing = false
            refreshUsageStats()
            lastResultMessage = "已删除 \(deleted) 个会话。"
        }
    }

    /// 扫描 Documents/conversations/ 统计文件总大小与文件数。
    /// 与 IOSConversationStore 用同一目录约定（IOSConversationStore.swift:47）。
    private func refreshUsageStats() {
        let fm = FileManager.default
        guard let documents = fm.urls(for: .documentDirectory, in: .userDomainMask).first else {
            usageBytes = 0
            usageFileCount = 0
            return
        }
        let conversationsDir = documents.appendingPathComponent("conversations")
        var totalBytes: Int64 = 0
        var count = 0
        if let enumerator = fm.enumerator(at: conversationsDir, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) {
            for case let url as URL in enumerator {
                let values = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                if values?.isRegularFile == true, let size = values?.fileSize {
                    totalBytes += Int64(size)
                    count += 1
                }
            }
        }
        usageBytes = totalBytes
        usageFileCount = count
    }
}

private struct StorageUsageItem: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let color: Color
}

private struct StorageUsageBar: View {
    let bytes: Int64
    let fileCount: Int

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .fill(AmberTheme.surface2)

            Canvas { context, size in
                let stripeWidth: CGFloat = 16
                var x: CGFloat = -size.height
                while x < size.width {
                    var path = Path()
                    path.move(to: CGPoint(x: x, y: size.height))
                    path.addLine(to: CGPoint(x: x + stripeWidth, y: 0))
                    path.addLine(to: CGPoint(x: x + stripeWidth + 5, y: 0))
                    path.addLine(to: CGPoint(x: x + 5, y: size.height))
                    path.closeSubpath()
                    context.fill(path, with: .color(AmberTheme.borderSoft.opacity(0.8)))
                    x += stripeWidth
                }
            }

            // [Slice 2] 用量统计：真实扫描 Documents/conversations/ 的字节数 + 文件数。
            Label("\(formatBytes(bytes)) · \(fileCount) 个文件", systemImage: "externaldrive")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(AmberTheme.surface.opacity(0.9), in: Capsule())
        }
        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
        .frame(height: 44)
    }

    private func formatBytes(_ bytes: Int64) -> String {
        // 用静态形式 + 默认单位集（含 bytes），与 SyncBackupView 一致；
        // 避免 allowedUnits 排除 bytes 导致 < 1KB 时显示 "Zero KB"。
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

private struct StorageLegendRow: View {
    let item: StorageUsageItem

    var body: some View {
        HStack(spacing: 7) {
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(item.color)
                .frame(width: 9, height: 9)

            Text(item.title)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)

            Spacer(minLength: 8)

            Text(item.detail)
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(minHeight: 21)
    }
}

private struct StorageActionRow: View {
    let systemImage: String
    let title: String
    let subtitle: String
    var trailing: String?
    var showsChevron = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if let trailing {
                    Text(trailing)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                }

                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct StorageDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 54)
    }
}

private struct StorageNote: View {
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
            .padding(.top, 7)
    }
}

private enum StorageAlert: Identifiable {
    case cache
    case noStaleConversations

    var id: String {
        switch self {
        case .cache: "cache"
        case .noStaleConversations: "no-stale-conversations"
        }
    }

    var title: String {
        switch self {
        case .cache: "无需清理"
        case .noStaleConversations: "没有符合条件的对话"
        }
    }

    var message: String {
        switch self {
        case .cache:
            "v1 无独立附件缓存（图片/文档以 base64 或本地引用存在消息体里），无需清理。"
        case .noStaleConversations:
            "没有早于 30 天的非置顶会话。"
        }
    }

    var primaryTitle: String {
        "知道了"
    }
}

/// 破坏性操作的二次确认载荷。
private enum StorageConfirmation: Identifiable {
    case timeCleanup(count: Int, ids: [KotlinUuid])
    case deleteAll(count: Int, ids: [KotlinUuid])

    var id: String {
        switch self {
        case .timeCleanup: "time-cleanup"
        case .deleteAll: "delete-all"
        }
    }

    var title: String {
        switch self {
        case .timeCleanup(let count, _):
            "清理 \(count) 个旧对话？"
        case .deleteAll(let count, _):
            "删除全部 \(count) 个对话？"
        }
    }

    var message: String {
        switch self {
        case .timeCleanup:
            return "将删除 updateAt 早于 30 天的非置顶会话。此操作不可恢复。"
        case .deleteAll:
            return "将清空 Documents/conversations/ 下所有会话文件。此操作不可恢复。"
        }
    }

    var ids: [KotlinUuid] {
        switch self {
        case .timeCleanup(_, let ids), .deleteAll(_, let ids):
            return ids
        }
    }
}
