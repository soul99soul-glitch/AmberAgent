import SwiftUI
import Shared

struct BoardSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    private var tb: TodayBoardSetting { sharedSettings.agentRuntime.todayBoard }

    private var settingRows: [BoardCapabilityRow] {
        [
            .init(
                title: "看板功能",
                subtitle: "是否启用今日看板。",
                value: tb.enabled ? "启用" : "关闭",
                color: tb.enabled ? AmberTheme.accentGreen : AmberTheme.muted2
            ),
            .init(
                title: "默认模型",
                subtitle: "生成看板时使用的默认模型。",
                value: String(describing: tb.boardModelId).prefix(8) + "…",
                color: AmberTheme.foreground2
            ),
            .init(
                title: "自动刷新时间",
                subtitle: "预设的刷新小时；当前页面仍以手动刷新为主。",
                value: tb.triggerHours.map { "\($0)" }.joined(separator: ", "),
                color: AmberTheme.foreground2
            ),
            .init(
                title: "数据来源",
                subtitle: "当前默认启用的数据来源数量。",
                value: "\(tb.enabledSources.count) 个来源",
                color: AmberTheme.foreground2
            ),
            .init(
                title: "热榜",
                subtitle: "刷新间隔 \(tb.hotListRefreshIntervalMinutes) 分钟，\(tb.hotListEnabledSources.count) 个来源。",
                value: tb.hotListWifiOnly ? "仅 Wi-Fi" : "可用",
                color: AmberTheme.accentGreen
            ),
        ]
    }

    private let persistenceRows: [BoardCapabilityRow] = [
        .init(
            title: "本地线索",
            subtitle: "今日看板会保存近期可用线索，并在下次打开时恢复。",
            value: "可用",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "自动去重",
            subtitle: "重复线索会合并，旧线索会自动清理。",
            value: "可用",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "手动刷新",
            subtitle: "点击生成时会整理聊天、日历提醒、热榜和时间线索。",
            value: "手动",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "后台 / 外部账号",
            subtitle: "后台刷新、通知读取、飞书和深度阅读暂未开放。",
            value: "未开放",
            color: AmberTheme.accentAmber
        )
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        settingMapSection
                        persistenceSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回今日看板", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("看板设置")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("默认设置 · 数据来源")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("查看今日看板当前使用的默认设置和数据来源。部分自动化能力暂未开放，当前以手动生成最可靠。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var settingMapSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "默认设置")
            AmberFormGroup {
                ForEach(Array(settingRows.enumerated()), id: \.element.id) { index, row in
                    BoardCapabilityStatusRow(row: row)
                    if index < settingRows.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }
        }
    }

    private var persistenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "数据与刷新")
            AmberFormGroup {
                ForEach(Array(persistenceRows.enumerated()), id: \.element.id) { index, row in
                    BoardCapabilityStatusRow(row: row)
                    if index < persistenceRows.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }
        }
    }

}

#Preview {
    NavigationStack {
        BoardSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}
