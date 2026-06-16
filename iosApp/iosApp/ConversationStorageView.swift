import SwiftUI
import Shared

struct ConversationStorageView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @State private var pendingAlert: StorageAlert?

    private var usageItems: [StorageUsageItem] {
        [
            .init(title: "助手条目", detail: "\(sharedSettings.snapshot.assistants.count) 个", color: AmberTheme.accent),
            .init(title: "Provider 模板", detail: "\(sharedSettings.snapshot.providers.count) 个", color: AmberTheme.accentAmber),
            .init(title: "快捷消息", detail: "\(sharedSettings.snapshot.quickMessages.count) 条", color: AmberTheme.accentCyan),
            .init(title: "对话文件", detail: "待接（需 iOS 文件系统扫描）", color: AmberTheme.muted2)
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
        Text("iOS 端尚未接入真实对话、附件和缓存用量统计。此页先标出需要接线的范围，不会清理或删除本地数据。")
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
                    StorageUsageBar()
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
                StorageActionRow(
                    systemImage: "arrow.clockwise",
                    title: "清除缓存尚执行待接",
                    subtitle: "需要真实缓存统计与清理服务"
                ) {
                    pendingAlert = .cache
                }

                StorageDivider()

                StorageActionRow(
                    systemImage: "calendar.badge.clock",
                    title: "按时间清理尚执行待接",
                    subtitle: "需要对话存储层、置顶规则与备份检查"
                ) {
                    pendingAlert = .oldConversations
                }
            }

            StorageNote("当前清理入口只显示接线缺口；不会执行文件或数据库操作。")
        }
    }

    private var deleteSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                Button {
                    pendingAlert = .deleteAll
                } label: {
                    Text("删除全部对话尚执行待接")
                        .font(.body.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 18)

            StorageNote("真实删除需要安全的对话存储事务、附件清理和备份决策；当前不会删除任何本地数据。")
        }
    }
}

private struct StorageUsageItem: Identifiable {
    let id = UUID()
    let title: String
    let detail: String
    let color: Color
}

private struct StorageUsageBar: View {
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

            Label("用量统计执行待接", systemImage: "externaldrive.badge.questionmark")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(AmberTheme.surface.opacity(0.9), in: Capsule())
        }
        .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
        .frame(height: 44)
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
    case oldConversations
    case deleteAll

    var id: String {
        switch self {
        case .cache: "cache"
        case .oldConversations: "old-conversations"
        case .deleteAll: "delete-all"
        }
    }

    var title: String {
        switch self {
        case .cache: "清除缓存尚执行待接"
        case .oldConversations: "按时间清理尚执行待接"
        case .deleteAll: "删除全部对话尚执行待接"
        }
    }

    var message: String {
        switch self {
        case .cache:
            "当前 iOS 端还没有接入真实缓存统计与清理服务；此按钮先保留确认入口。"
        case .oldConversations:
            "真实按时间清理需要接入对话存储层，当前不会删除任何本地对话。"
        case .deleteAll:
            "真实删除全部对话需要接入安全的存储事务与备份检查，当前不会删除任何本地对话。"
        }
    }

    var primaryTitle: String {
        switch self {
        case .cache: "知道了"
        case .oldConversations: "知道了"
        case .deleteAll: "知道了"
        }
    }

}
