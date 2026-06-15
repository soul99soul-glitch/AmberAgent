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

private struct AccountStatsPanel: View {
    private let overviewStats: [AccountStatItem] = [
        .init(systemImage: "bubble.left.and.bubble.right", label: "总会话", value: "34"),
        .init(systemImage: "message", label: "总消息", value: "182")
    ]

    private let detailStats: [AccountStatItem] = [
        .init(systemImage: "cpu", label: "输入 Token", value: "2.60M"),
        .init(systemImage: "cpu", label: "输出 Token", value: "71.2K"),
        .init(systemImage: "bolt", label: "缓存节省", value: "1.55M"),
        .init(systemImage: "power", label: "启动次数", value: "74")
    ]

    var body: some View {
        VStack(spacing: 0) {
            AccountHeatmapBlock()

            AccountDivider()

            AccountStatsOverview(items: overviewStats)

            AccountDivider()

            VStack(spacing: 0) {
                ForEach(detailStats.indices, id: \.self) { index in
                    AccountStatLine(item: detailStats[index])

                    if index < detailStats.count - 1 {
                        AccountDivider()
                            .padding(.leading, 40)
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }
}

private struct AccountStatsOverview: View {
    let items: [AccountStatItem]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { index in
                AccountPrimaryMetric(item: items[index])

                if index < items.count - 1 {
                    Divider()
                        .frame(height: 54)
                        .overlay(AmberTheme.borderSoft)
                        .padding(.horizontal, 12)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 14)
    }
}

private struct AccountPrimaryMetric: View {
    let item: AccountStatItem

    var body: some View {
        VStack(alignment: .center, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: item.systemImage)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)

                Text(item.label)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }

            Text(item.value)
                .font(.system(size: 28, weight: .semibold, design: .rounded))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.76)
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

private struct AccountHeatmapBlock: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "calendar")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 28, height: 28)
                    .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                VStack(alignment: .leading, spacing: 3) {
                    Text("使用热力图统计")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("近 5 个月的本机使用活跃度")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }

            AccountHeatmap()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 16)
    }
}

private struct AccountHeatmap: View {
    private let weeks = 21
    private let rows = 7
    private let cellSize: CGFloat = 11.5
    private let cellGap: CGFloat = 3.5
    private let monthLabels: [(label: String, week: Int)] = [
        ("2月", 0),
        ("3月", 5),
        ("4月", 10),
        ("5月", 15),
        ("6月", 20)
    ]

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 7) {
                monthAxis
                    .frame(width: gridWidth, height: monthLabelHeight, alignment: .topLeading)

                heatmapGrid
                    .frame(width: gridWidth, height: gridHeight)
            }
            .frame(width: gridWidth, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .center)

            legend
                .frame(width: gridWidth, alignment: .trailing)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.top, 14)
        }
    }

    private var monthAxis: some View {
        ZStack(alignment: .topLeading) {
            ForEach(monthLabels, id: \.label) { item in
                Text(item.label)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 28, alignment: .leading)
                    .offset(x: min(CGFloat(item.week) * columnPitch, gridWidth - 24), y: 0)
            }
        }
    }

    private var legend: some View {
        HStack(spacing: 6) {
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

    private var heatmapGrid: some View {
        Canvas { context, _ in
            for week in 0..<weeks {
                for row in 0..<rows {
                    let rect = CGRect(
                        x: CGFloat(week) * columnPitch,
                        y: CGFloat(row) * rowPitch,
                        width: cellSize,
                        height: cellSize
                    )
                    context.fill(
                        Path(roundedRect: rect, cornerRadius: 2.6),
                        with: .color(heatmapColor(week: week, day: row))
                    )
                }
            }
        }
    }

    private var columnPitch: CGFloat { cellSize + cellGap }

    private var rowPitch: CGFloat { cellSize + cellGap }

    private var gridWidth: CGFloat {
        CGFloat(weeks) * cellSize + CGFloat(weeks - 1) * cellGap
    }

    private var gridHeight: CGFloat {
        CGFloat(rows) * cellSize + CGFloat(rows - 1) * cellGap
    }

    private var monthLabelHeight: CGFloat { 20 }

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

private struct AccountStatLine: View {
    let item: AccountStatItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 28, height: 28)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            Text(item.label)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(item.value)
                .font(.system(size: 17, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
        }
        .frame(minHeight: 54)
        .padding(.horizontal, 16)
        .padding(.vertical, 3)
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
