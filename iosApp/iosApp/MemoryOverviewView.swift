import SwiftUI

struct MemoryOverviewView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @AppStorage("app.amber.ios.memory.core") private var coreMemory = true
    @AppStorage("app.amber.ios.memory.shortTerm") private var shortTermMemory = true
    @AppStorage("app.amber.ios.memory.longTerm") private var longTermMemory = true
    @AppStorage("app.amber.ios.memory.recentConversations") private var recentConversations = true
    @AppStorage("app.amber.ios.memory.timeReminder") private var timeReminder = false
    @AppStorage("app.amber.ios.memory.autoCompaction") private var autoCompaction = true

    @State private var pendingAlert: MemoryAlert?

    private let records: [MemoryRecord] = [
        .init(scope: .core, text: "称呼我「光」，语气可以随意一点。", pinned: true),
        .init(scope: .core, text: "回答先给结论，再展开理由。", pinned: true),
        .init(scope: .core, text: "不喜欢被夸「好问题」这类客套。"),
        .init(scope: .longTerm, text: "在上海工作，做产品设计。"),
        .init(scope: .longTerm, text: "在学吉他，偏好指弹。"),
        .init(scope: .shortTerm, text: "正在做的项目：iOS Liquid Glass 原型，议会聊天那块。")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    soulSection
                    configurationSection
                    recordsSection
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
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("核心记忆")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "新增记忆", size: 44, symbolSize: 20) {
                pendingAlert = .add
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("AmberAgent 在会话之间保存和复用长期知识，并按核心 / 短期 / 长期三个层级召回。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var soulSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "灵魂 · agents.md")

            Button {
                router.navigate(to: .agentsMd)
            } label: {
                VStack(alignment: .leading, spacing: 11) {
                    HStack(spacing: 9) {
                        Image(systemName: "doc.text")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 30, height: 30)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                        Text("agents.md")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(AmberTheme.foreground)

                        Text("每次对话注入")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(AmberTheme.muted)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 6, style: .continuous))

                        Spacer(minLength: 8)

                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }

                    Text("""
                    # 行为准则
                    - 称呼用户「光」，语气随意
                    - 回答先结论后展开
                    - 不说「好问题」这类客套
                    - 中文优先，代码标识符保留原文
                    """)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineSpacing(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(11)
                    .background(AmberTheme.background, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                            .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                    }
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            }
            .buttonStyle(.plain)
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
        }
    }

    private var configurationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆配置")
            AmberFormGroup {
                MemoryToggleRow(
                    systemImage: "cylinder.split.1x2",
                    iconColor: AmberTheme.accent,
                    title: "启用核心记忆",
                    subtitle: "在会话之间保存和复用长期知识",
                    isOn: coreMemory
                ) { coreMemory.toggle() }

                MemoryDivider()

                MemoryToggleRow(
                    systemImage: "clock",
                    title: "短期记忆",
                    subtitle: "保存近期任务 / 活跃项目的简短总结",
                    isOn: shortTermMemory
                ) { shortTermMemory.toggle() }

                MemoryDivider()

                MemoryToggleRow(
                    systemImage: "chart.line.uptrend.xyaxis",
                    title: "长期记忆",
                    subtitle: "稳定偏好、长期关注点、计划与事实",
                    isOn: longTermMemory
                ) { longTermMemory.toggle() }

                MemoryDivider()

                MemoryToggleRow(
                    systemImage: "bubble.left.and.text.bubble.right",
                    title: "参考最近会话",
                    subtitle: "参考最近会话标题，保持任务连续性",
                    isOn: recentConversations
                ) { recentConversations.toggle() }

                MemoryDivider()

                MemoryToggleRow(
                    systemImage: "timer",
                    title: "时间提醒",
                    subtitle: "对话间隔较长时注入时间提醒",
                    isOn: timeReminder
                ) { timeReminder.toggle() }

                MemoryDivider()

                MemoryToggleRow(
                    systemImage: "arrow.down.right.and.arrow.up.left",
                    title: "自动上下文压缩",
                    subtitle: "上下文超长时自动摘要压缩",
                    isOn: autoCompaction
                ) { autoCompaction.toggle() }
            }
        }
    }

    private var recordsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记忆条目 · 6 条")

            VStack(spacing: 8) {
                ForEach(records) { record in
                    MemoryCard(record: record) {
                        pendingAlert = .record(record.text)
                    }
                }
            }
            .padding(.horizontal, 16)

            MemoryNote("点任意一条进入编辑；向左滑动可删除。核心记忆几乎总会注入，短期记忆过期会淡化。")
        }
    }
}

private struct MemoryRecord: Identifiable {
    let id = UUID()
    let scope: MemoryScope
    let text: String
    var pinned = false
}

private enum MemoryScope {
    case core
    case longTerm
    case shortTerm

    var title: String {
        switch self {
        case .core: "核心"
        case .longTerm: "长期"
        case .shortTerm: "短期"
        }
    }

    var foreground: Color {
        switch self {
        case .core: AmberTheme.accent
        case .longTerm: AmberTheme.accentCyan
        case .shortTerm: AmberTheme.accentAmber
        }
    }

    var background: Color {
        switch self {
        case .core: AmberTheme.accentTint
        case .longTerm: AmberTheme.accentCyan.opacity(0.12)
        case .shortTerm: AmberTheme.accentAmber.opacity(0.13)
        }
    }
}

private struct MemoryToggleRow: View {
    let systemImage: String
    var iconColor: Color = AmberTheme.foreground2
    let title: String
    let subtitle: String
    let isOn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 28, height: 28)

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

                MemorySwitch(isOn: isOn)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct MemorySwitch: View {
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

private struct MemoryDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 54)
    }
}

private struct MemoryCard: View {
    let record: MemoryRecord
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 8) {
                Text(record.scope.title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(record.scope.foreground)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(record.scope.background, in: RoundedRectangle(cornerRadius: 6, style: .continuous))

                Text(record.text)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if record.pinned {
                    Image(systemName: "pin")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.accent)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .contentShape(RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct MemoryNote: View {
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

private enum MemoryAlert: Identifiable {
    case add
    case record(String)

    var id: String {
        switch self {
        case .add: "add"
        case .record(let text): "record-\(text)"
        }
    }

    var title: String {
        switch self {
        case .add: "新增记忆"
        case .record: "编辑记忆"
        }
    }

    var message: String {
        switch self {
        case .add:
            "新增记忆需要接入 iOS 记忆库写入流程；当前只保留入口。"
        case .record(let text):
            "将继续用独立的 memEdit 屏接入编辑流程；当前选中的记忆是：\(text)"
        }
    }
}
