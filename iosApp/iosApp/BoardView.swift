import SwiftUI

struct BoardView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private let evidenceRows: [BoardCapabilityRow] = [
        .init(
            title: "TodayBoardSetting",
            subtitle: "commonMain model 保存 enabled、boardModelId、信号来源、触发时间、热榜、深度阅读和后台策略。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "BoardViewModel",
            subtitle: "Android 订阅看板 items、任务流、机会、日报和热榜 dashboard，并处理刷新、派发、完成、忽略。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "BoardRepository / DAOs",
            subtitle: "Android 持久化 board_signal、board_item、board_weight、board_focus_rule、daily_review 等表。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "BoardScheduler / BoardWorker",
            subtitle: "Android 使用 WorkManager 调度锚点、增量和手动 run，执行信号收集、生成、清理和日报。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "BoardAgent",
            subtitle: "Android 基于已评分信号调用真实模型，解析/校验 JSON 后写入今日看板条目。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "iOS 数据源桥",
            subtitle: "当前 SwiftUI 没有 Settings.agentRuntime.todayBoard、BoardRepository、worker 或热榜/信号/任务数据源。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
    ]

    private let handlingRows: [BoardCapabilityRow] = [
        .init(
            title: "看板内容",
            subtitle: "不再展示硬编码新闻标题、来源、排名或“刚刚更新”。真实内容应来自 BoardItemEntity / HotListDashboard。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "刷新",
            subtitle: "Android 的刷新会调用 BoardScheduler.runOnce() 和 HotListScheduler.runOnce()；iOS 当前不触发后台任务。",
            value: "禁用",
            color: AmberTheme.muted
        ),
        .init(
            title: "任务流 / 机会",
            subtitle: "不读取 BoardTaskRepository 或 OpportunityRepository，也不派发任务或创建聊天会话。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "深度阅读",
            subtitle: "不读取 HotTopic、DeepRead cache、模板或字体包，也不启动隐藏阅读 Agent。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
    ]

    private let sourceRows: [BoardCapabilityRow] = [
        .init(
            title: "通知 / 日历",
            subtitle: "Android collector 可从通知和日历生成信号；iOS 未接相应权限、collector 或 DB 写入。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "飞书消息 / 文档",
            subtitle: "Android 有 Feishu signal collectors；iOS 当前没有 Feishu MCP/Skill 写入 BoardSignal 的桥。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "聊天记录",
            subtitle: "Android 可从历史会话提取信号；iOS 没有接 BoardSignalCollector 或 Message/Conversation DAO。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "热榜",
            subtitle: "Android HotListRepository/HotListScheduler 可抓取、缓存和筛选热点；iOS 未接网络抓取或缓存。",
            value: "未接线",
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
                        evidenceSection
                        handlingSection
                        sourceSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("今日看板")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("Android/KMP 已实现 · iOS 数据源桥未接线")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "看板设置", size: 44, symbolSize: 17) {
                router.navigate(to: .boardSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("Android/KMP 的今日看板会收集通知、日历、飞书、聊天记录和热榜信号，调度 worker 调用模型生成结构化看板，并把任务、机会、日报和深度阅读缓存写入数据库。iOS 当前没有这些数据源、DAO、worker 或模型生成桥，本页只展示能力证据，不展示假新闻流、不刷新、不派发任务。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    BoardCapabilityStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }
        }
    }

    private var handlingSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                ForEach(Array(handlingRows.enumerated()), id: \.element.id) { index, row in
                    BoardCapabilityStatusRow(row: row)
                    if index < handlingRows.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }
        }
    }

    private var sourceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "信号来源")
            AmberFormGroup {
                ForEach(Array(sourceRows.enumerated()), id: \.element.id) { index, row in
                    BoardCapabilityStatusRow(row: row)
                    if index < sourceRows.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }

            BoardCapabilityNote("设置按钮保留为字段映射入口；它不会保存 enabled、模型、后台策略、热榜源或关注规则。")
        }
    }
}

struct BoardCapabilityRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

struct BoardCapabilityStatusRow: View {
    let row: BoardCapabilityRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(row.value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(row.color)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

struct BoardCapabilityDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

struct BoardCapabilityNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

#Preview {
    NavigationStack {
        BoardView()
            .environment(RouterPath())
    }
}
