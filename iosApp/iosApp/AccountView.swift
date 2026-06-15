import SwiftUI

struct AccountView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var displayName = "Amber"

    private var avatarInitial: String {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return String((trimmed.isEmpty ? "A" : trimmed).prefix(1)).uppercased()
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        hero
                        profileSection
                        statsSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回首页", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("我的账户")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var hero: some View {
        VStack(spacing: 10) {
            Text(avatarInitial)
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .foregroundStyle(AmberTheme.foreground)
                .frame(width: 72, height: 72)
                .background(AmberTheme.surface2, in: Circle())
                .overlay {
                    Circle()
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }

            VStack(spacing: 4) {
                Text(displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Amber" : displayName)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)

                Text("用于头像和本机显示名称")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 18)
    }

    private var profileSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "个人资料")
            AmberFormGroup {
                AccountTextFieldRow(title: "昵称", placeholder: "输入你的称呼", text: $displayName)
                AccountDivider()
                AccountPreviewRow(title: "头像字母", value: avatarInitial)
            }

            Text("这里只影响本机界面的称呼和头像预览。")
                .font(.caption)
                .lineSpacing(3)
                .foregroundStyle(AmberTheme.muted2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.top, 8)
        }
    }

    private var statsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "统计")
            AccountStatsPanel()
                .padding(.horizontal, 16)
        }
    }
}

private struct AccountTextFieldRow: View {
    let title: String
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(width: 62, alignment: .leading)

            TextField(placeholder, text: $text)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct AccountPreviewRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct AccountStatsPanel: View {
    private let stats: [AccountStatItem] = [
        .init(systemImage: "bubble.left.and.bubble.right", label: "总会话", value: "34"),
        .init(systemImage: "message", label: "总消息", value: "182"),
        .init(systemImage: "cpu", label: "输入 Token", value: "2.60M"),
        .init(systemImage: "cpu", label: "输出 Token", value: "71.2K"),
        .init(systemImage: "bolt", label: "缓存节省", value: "1.55M"),
        .init(systemImage: "rocket", label: "启动次数", value: "74")
    ]

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: "chart.bar.xaxis")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)

                VStack(alignment: .leading, spacing: 2) {
                    Text("聊天热力图")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text("近 5 个月的本机聊天活跃度")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.top, 14)
            .padding(.bottom, 12)

            AccountHeatmap()
                .padding(.horizontal, 14)
                .padding(.bottom, 14)

            AccountDivider()

            VStack(spacing: 0) {
                ForEach(0..<3, id: \.self) { row in
                    HStack(spacing: 0) {
                        AccountStatCell(item: stats[row * 2])
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                        AccountStatCell(item: stats[row * 2 + 1])
                    }

                    if row < 2 {
                        AccountDivider()
                    }
                }
            }
        }
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }
}

private struct AccountHeatmap: View {
    private let weeks = Array(0..<19)
    private let days = Array(0..<7)
    private let monthLabels = ["2月", "3月", "4月", "5月", "6月"]

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 0) {
                Color.clear
                    .frame(width: 20, height: 14)
                ForEach(Array(monthLabels.enumerated()), id: \.offset) { index, label in
                    Text(label)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: index == 0 ? .leading : .center)
                }
            }

            HStack(alignment: .top, spacing: 6) {
                VStack(spacing: 4) {
                    ForEach(days, id: \.self) { day in
                        Text(weekdayLabel(day))
                            .font(.caption2)
                            .foregroundStyle(AmberTheme.muted)
                            .frame(width: 16, height: 9)
                    }
                }
                .padding(.top, 1)

                HStack(alignment: .top, spacing: 3) {
                    ForEach(weeks, id: \.self) { week in
                        VStack(spacing: 3) {
                            ForEach(days, id: \.self) { day in
                                RoundedRectangle(cornerRadius: 2.8, style: .continuous)
                                    .fill(heatmapColor(week: week, day: day))
                                    .frame(width: 8.8, height: 8.8)
                            }
                        }
                    }
                }
            }

            HStack(spacing: 5) {
                Spacer()
                Text("少")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted2)
                ForEach(0..<5, id: \.self) { level in
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(legendColor(level))
                        .frame(width: 10, height: 10)
                }
                Text("多")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
    }

    private func weekdayLabel(_ day: Int) -> String {
        switch day {
        case 1: "一"
        case 3: "三"
        case 5: "五"
        default: ""
        }
    }

    private func heatmapColor(week: Int, day: Int) -> Color {
        if week < 14 {
            let isQuiet = (week * 11 + day * 7).isMultiple(of: 17)
            return isQuiet ? legendColor(1) : legendColor(0)
        }
        let recentPatterns: [[Int]] = [
            [0, 0, 1, 0, 0, 1, 0],
            [0, 1, 0, 2, 0, 0, 1],
            [1, 2, 0, 3, 0, 2, 0],
            [2, 0, 3, 4, 3, 0, 1],
            [3, 4, 0, 4, 2, 0, 1]
        ]
        let recentIndex = min(max(week - 14, 0), recentPatterns.count - 1)
        return legendColor(recentPatterns[recentIndex][day])
    }

    private func legendColor(_ level: Int) -> Color {
        switch level {
        case 0:
            return AmberTheme.surface2.opacity(0.36)
        case 1:
            return AmberTheme.accent.opacity(0.24)
        case 2:
            return AmberTheme.accent.opacity(0.42)
        case 3:
            return AmberTheme.accent.opacity(0.66)
        default:
            return AmberTheme.accent
        }
    }
}

private struct AccountStatItem {
    let systemImage: String
    let label: String
    let value: String
}

private struct AccountStatCell: View {
    let item: AccountStatItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: item.systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 26, height: 26)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            Text(item.value)
                .font(.system(size: 22, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.82)

            Text(item.label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity, minHeight: 92, alignment: .topLeading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct AccountDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

#Preview {
    NavigationStack {
        AccountView()
    }
}
