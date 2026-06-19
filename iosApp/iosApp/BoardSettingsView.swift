import SwiftUI
import Shared

struct BoardSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    private var tb: TodayBoardSetting { sharedSettings.agentRuntime.todayBoard }

    private var settingRows: [BoardCapabilityRow] {
        [
            .init(
                title: "enabled",
                subtitle: "KMP Settings.agentRuntime.todayBoard.enabled 真实种子值，只读展示。",
                value: tb.enabled ? "启用" : "关闭",
                color: tb.enabled ? AmberTheme.accentGreen : AmberTheme.muted2
            ),
            .init(
                title: "boardModelId",
                subtitle: "KMP todayBoard.boardModelId 真实种子值指针（Uuid）。",
                value: String(describing: tb.boardModelId).prefix(8) + "…",
                color: AmberTheme.foreground2
            ),
            .init(
                title: "triggerHours",
                subtitle: "KMP todayBoard.triggerHours 真实种子值。",
                value: tb.triggerHours.map { "\($0)" }.joined(separator: ", "),
                color: AmberTheme.foreground2
            ),
            .init(
                title: "enabledSources",
                subtitle: "KMP todayBoard.enabledSources 真实种子值；iOS 手动 runOnce 会额外把提醒事项跟随 calendar、热榜跟随 hotListEnabledSources。",
                value: "\(tb.enabledSources.count) 个来源",
                color: AmberTheme.foreground2
            ),
            .init(
                title: "hotList*",
                subtitle: "KMP todayBoard 热榜配置（刷新间隔 \(tb.hotListRefreshIntervalMinutes) 分钟，\(tb.hotListEnabledSources.count) 个源，WiFi Only \(tb.hotListWifiOnly ? "是" : "否")）。",
                value: "存在",
                color: AmberTheme.accentGreen
            ),
        ]
    }

    private let persistenceRows: [BoardCapabilityRow] = [
        .init(
            title: "Signal repository",
            subtitle: "iOS 已将 Board signals 持久化到 Documents/boards/signals/board_signals.json，并支持重启恢复。",
            value: "已接",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "去重 / processed",
            subtitle: "sourceRef 和 contentHash 去重已接；BoardAgent 成功考虑后标记 processed，并裁剪旧 processed signals。",
            value: "已接",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "前台 runOnce",
            subtitle: "手动生成会聚合聊天历史、EventKit 日历/提醒事项、轻量热榜和时间锚点，再喂给 BoardAgent。",
            value: "手动",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "后台 / 外部账号",
            subtitle: "BGTaskScheduler、通知读取、飞书账号/MCP、深度阅读和自定义热榜源仍未启用；无权限时返回空状态。",
            value: "降级",
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
                        blockedSection
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
                Text("看板字段映射")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("字段映射 · 本地采集")
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
        Text("以下字段来自 KMP Settings.agentRuntime.todayBoard 真实种子值（只读展示）。iOS 已有本地 signal repository、Documents 持久化、去重、processed 标记和前台手动采集；本页仍不写 KMP Settings，也不启用后台、通知、飞书或深度阅读。")
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
            AmberSectionLabel(text: "TodayBoardSetting 字段")
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
            AmberSectionLabel(text: "iOS 本地采集链路")
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

    private var blockedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "启用前需要")
            AmberFormGroup {
                Text("剩余需要产品/权限决定的能力：后台刷新策略是否启用 BGTaskScheduler、是否读取通知、是否接飞书账号/MCP、是否启用深度阅读缓存和自定义热榜源。当前设置页保持只读，避免保存只在 iOS 页面生效的假开关。")
                    .font(.caption)
                    .lineSpacing(4)
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
            }

            BoardCapabilityNote("本页不写 UserDefaults、SettingsStore、Keychain，也不会触发后台任务；手动采集入口在今日看板页。")
        }
    }
}

#Preview {
    NavigationStack {
        BoardSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}
