import SwiftUI
import Shared

struct AccountView: View {
    let sharedSettings: IOSSharedSettingsStore

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

                Text("当前页面预览")
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

            Text("iOS 端尚未接入账户资料存储；这里仅预览本页称呼和头像。")
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
            AccountStatsPanel(sharedSettings: sharedSettings)
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
    let sharedSettings: IOSSharedSettingsStore

    private var overviewStats: [AccountStatItem] {
        [
            .init(systemImage: "bubble.left.and.bubble.right", label: "助手数", value: "\(sharedSettings.snapshot.assistants.count)"),
            .init(systemImage: "person.2", label: "默认助手", value: sharedSettings.snapshot.assistants.first?.name ?? "Amber"),
        ]
    }

    private var detailStats: [AccountStatItem] {
        [
            .init(systemImage: "server.rack", label: "Provider 模板", value: "\(sharedSettings.snapshot.providers.count)"),
            .init(systemImage: "speaker.wave.2", label: "TTS 引擎", value: "\(sharedSettings.snapshot.ttsProviders.count)"),
            .init(systemImage: "magnifyingglass", label: "搜索服务", value: "\(sharedSettings.snapshot.searchServices.count)"),
            .init(systemImage: "power", label: "启动次数", value: "\(sharedSettings.snapshot.launchCount)"),
        ]
    }

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

                    Text("iOS 统计桥尚执行待接")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
            }

            AccountHeatmap()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 16)
    }
}

private struct AccountHeatmap: View {
    private let weeks = 21
    private let rows = 7

    var body: some View {
        GeometryReader { geometry in
            let cellGap: CGFloat = 3
            let maxCellSize: CGFloat = 12
            let availableWidth = max(geometry.size.width, 0)
            let cellSize = max(6, min(maxCellSize, (availableWidth - CGFloat(weeks - 1) * cellGap) / CGFloat(weeks)))
            let gridWidth = CGFloat(weeks) * cellSize + CGFloat(weeks - 1) * cellGap
            let gridHeight = CGFloat(rows) * cellSize + CGFloat(rows - 1) * cellGap

            ZStack {
                heatmapGrid(cellSize: cellSize, cellGap: cellGap)
                    .frame(width: gridWidth, height: gridHeight)
                    .opacity(0.42)

                Label("统计执行待接", systemImage: "chart.bar.xaxis")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(
                        AmberTheme.surface.opacity(0.92),
                        in: Capsule()
                    )
            }
            .frame(maxWidth: .infinity, minHeight: gridHeight)
        }
        .frame(height: 112)
    }

    private func heatmapGrid(cellSize: CGFloat, cellGap: CGFloat) -> some View {
        Canvas { context, _ in
            let columnPitch = cellSize + cellGap
            let rowPitch = cellSize + cellGap
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
                        with: .color(legendColor(0))
                    )
                }
            }
        }
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
        AccountView(sharedSettings: IOSSharedSettingsStore())
    }
}
