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
                subtitle: "KMP todayBoard.enabledSources 真实种子值。",
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
            title: "关注规则",
            subtitle: "Android 通过 BoardFocusRuleDAO 增删改关注点；iOS 当前没有 DAO 或输入保存。",
            value: "执行待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "来源权重",
            subtitle: "Android 将完成、忽略、聊天反馈写入 BoardWeightEntity，并可自动 hard-mute。",
            value: "执行待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "自定义热榜源",
            subtitle: "Android 通过 HotListRepository 写入 HotListSourceEntity 并触发 HotListScheduler.runOnce()。",
            value: "执行待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "搜索服务复用",
            subtitle: "Android 深度阅读复用 Settings.searchEnabledServiceIds；iOS 搜索服务桥也仍未完成。",
            value: "执行待接",
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
                Text("今日看板设置")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("字段映射 · 不保存")
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
        Text("以下字段来自 KMP Settings.agentRuntime.todayBoard 真实种子值（只读展示）。持久化/DAO/调度/采集仍待 iOS 原生实现。")
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
            AmberSectionLabel(text: "设置页持久化")
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
                Text("需要先定义 iOS 侧 Settings.agentRuntime.todayBoard 持久化、Board DAO/Repository、信号 collector、WorkManager 等价调度、模型生成与 JSON 校验、热榜缓存、深度阅读缓存和任务流写回。没有这些链路前，任何开关或选择器都会变成只在本页生效的假设置。")
                    .font(.caption)
                    .lineSpacing(4)
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
            }

            BoardCapabilityNote("本页不写 UserDefaults、SettingsStore、Keychain、数据库，也不会触发 BoardScheduler 或 HotListScheduler。")
        }
    }
}

#Preview {
    NavigationStack {
        BoardSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}
