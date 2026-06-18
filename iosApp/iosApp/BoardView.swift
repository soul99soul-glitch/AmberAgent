import SwiftUI
@preconcurrency import Shared

extension BoardSignal: @retroactive @unchecked Sendable {}

struct BoardView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore

    @State private var generationState = BoardGenerationState.idle

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
            // [Slice 1] 文案诚实拆分：时间锚点信号采集已接——BoardView.swift:255
            // IosBoardFactory.shared.createTimeCollectContext + :261 createCollectors + :263
            // 循环 collect 已在手动生成里跑通。BoardRepository / worker / 日历 / 飞书 / 热榜采集仍待接。
            subtitle: "时间锚点采集已接（IosBoardFactory.createTimeCollectContext/createCollectors，手动生成区可见真实信号）；BoardRepository、worker、日历/飞书/热榜采集仍待接。",
            value: "部分接",
            color: AmberTheme.accentAmber
        )
    ]

    private let handlingRows: [BoardCapabilityRow] = [
        .init(
            title: "看板内容",
            subtitle: "不再展示硬编码新闻标题、来源、排名或“刚刚更新”。真实内容应来自 BoardItemEntity / HotListDashboard。",
            value: "待接",
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
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "深度阅读",
            subtitle: "不读取 HotTopic、DeepRead cache、模板或字体包，也不启动隐藏阅读 Agent。",
            value: "待接",
            color: AmberTheme.accentAmber
        )
    ]

    private let sourceRows: [BoardCapabilityRow] = [
        .init(
            title: "通知 / 日历",
            subtitle: "Android collector 可从通知和日历生成信号；iOS 未接相应权限、collector 或 DB 写入。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "飞书消息 / 文档",
            subtitle: "Android 有 Feishu signal collectors；iOS 当前没有 Feishu MCP/Skill 写入 BoardSignal 的桥。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "聊天记录",
            subtitle: "Android 可从历史会话提取信号；iOS 没有接 BoardSignalCollector 或 Message/Conversation DAO。",
            value: "待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "热榜",
            subtitle: "Android HotListRepository/HotListScheduler 可抓取、缓存和筛选热点；iOS 未接网络抓取或缓存。",
            value: "待接",
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
                        manualGenerationSection
                        presetConfigSection
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

                Text("Android/KMP 已实现 · iOS 时间锚点采集已接；日历/飞书/热榜采集待接")
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
        Text("Android/KMP 的今日看板会收集通知、日历、飞书、聊天记录和热榜信号，调度 worker 调用模型生成结构化看板，并把任务、机会、日报和深度阅读缓存写入数据库。iOS 已接时间锚点信号采集 + 手动模型生成（IosBoardFactory.createCollectors/createAgent，上方\"手动生成\"区可触发真实 BoardAgent 调用）；BoardRepository、后台 worker、日历/飞书/热榜采集仍待接。本页不展示假新闻流、不刷新、不派发任务。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var manualGenerationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "手动生成")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 10) {
                        Image(systemName: generationState.isRunning ? "clock.arrow.circlepath" : "sparkles")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 34, height: 34)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                        VStack(alignment: .leading, spacing: 2) {
                            Text("生成今日看板")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("采集 iOS 时间锚点信号，并用当前 OpenAI 兼容配置调用 KMP BoardAgent。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button {
                        runManualGeneration()
                    } label: {
                        HStack(spacing: 8) {
                            if generationState.isRunning {
                                ProgressView()
                                    .controlSize(.small)
                            }
                            Text(generationState.isRunning ? "正在生成…" : "生成今日看板")
                                .font(.subheadline.weight(.semibold))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(AmberTheme.accent, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .foregroundStyle(.white)
                    }
                    .buttonStyle(.plain)
                    .disabled(generationState.isRunning)

                    if let message = generationState.message {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(generationState.isError ? AmberTheme.accentAmber : AmberTheme.muted)
                            .lineSpacing(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if !generationState.signals.isEmpty {
                        BoardSignalPreview(signals: generationState.signals)
                    }

                    if let output = generationState.output, !output.isEmpty {
                        Text(output)
                            .font(.footnote)
                            .foregroundStyle(AmberTheme.foreground2)
                            .lineSpacing(3)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(AmberTheme.surface.opacity(0.55), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }

            BoardCapabilityNote("阶段 1 仅接时间信号和手动触发；不会启动 WorkManager/BGTaskScheduler，也不会伪造日历、热榜或聊天数据。")
        }
    }

    private func runManualGeneration() {
        generationState = .running(message: "正在采集时间信号…")
        Task {
            do {
                let setting = sharedSettings.agentRuntime.todayBoard
                let factory = IosBoardFactory.shared
                let context = factory.createTimeCollectContext(
                    assistantId: "ios-manual-board",
                    anchorTime: 0,
                    limit: 50
                )
                let collectors = factory.createCollectors(setting: setting)
                var collected: [BoardSignal] = []
                for collector in collectors {
                    collected.append(contentsOf: try await collectSignals(collector: collector, context: context))
                }

                await MainActor.run {
                    generationState = .running(
                        message: "已采集 \(collected.count) 条时间信号，正在调用模型…",
                        signals: BoardSignalPreviewItem.from(collected)
                    )
                }

                let agent = factory.createAgent(
                    baseUrl: settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines),
                    apiKey: settingsStore.currentApiKey,
                    modelId: settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines),
                    chatCompletionsPath: "/chat/completions"
                )
                let output = try await generateBoard(agent: agent, signals: collected, setting: setting)
                await MainActor.run {
                    generationState = .finished(
                        message: "采集完成：\(collected.count) 条时间信号。",
                        signals: BoardSignalPreviewItem.from(collected),
                        output: output
                    )
                }
            } catch {
                await MainActor.run {
                    generationState = .failed("生成失败：\(error.localizedDescription)")
                }
            }
        }
    }

    private func collectSignals(
        collector: BoardSignalCollectorInterface,
        context: BoardCollectContext
    ) async throws -> [BoardSignal] {
        try await withCheckedThrowingContinuation { continuation in
            collector.collect(context: context) { signals, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: signals ?? [])
                }
            }
        }
    }

    private func generateBoard(
        agent: BoardAgentInterface,
        signals: [BoardSignal],
        setting: TodayBoardSetting
    ) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            agent.generate(signals: signals, setting: setting) { output, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: output ?? "模型未返回今日看板内容。")
                }
            }
        }
    }

    /// Read-only view of the REAL seeded TodayBoard defaults from
    /// `IOSSharedSettingsStore.agentRuntime.todayBoard`. Proves the read path;
    /// does NOT enable board collection/generation.
    private var presetConfigSection: some View {
        let b = sharedSettings.agentRuntime.todayBoard
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "KMP 默认看板配置（只读）")
            AmberFormGroup {
                BoardPresetKVRow(title: "启用看板", value: b.enabled ? "默认开" : "默认关")
                BoardCapabilityDivider()
                BoardPresetKVRow(title: "启用数据源数", value: "\(b.enabledSources.count)")
                BoardCapabilityDivider()
                BoardPresetKVRow(title: "热榜启用数据源数", value: "\(b.hotListEnabledSources.count)")
                BoardCapabilityDivider()
                BoardPresetKVRow(title: "热榜刷新间隔（分钟）", value: "\(b.hotListRefreshIntervalMinutes)")
                BoardCapabilityDivider()
                BoardPresetKVRow(title: "热榜仅 WiFi", value: b.hotListWifiOnly ? "默认开" : "默认关")
            }
        }
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

private struct BoardGenerationState {
    var isRunning: Bool
    var message: String?
    var signals: [BoardSignalPreviewItem]
    var output: String?
    var isError: Bool

    static let idle = BoardGenerationState(
        isRunning: false,
        message: "尚未生成。点击按钮后会采集真实 iOS 时间信号。",
        signals: [],
        output: nil,
        isError: false
    )

    static func running(message: String, signals: [BoardSignalPreviewItem] = []) -> BoardGenerationState {
        BoardGenerationState(isRunning: true, message: message, signals: signals, output: nil, isError: false)
    }

    static func finished(message: String, signals: [BoardSignalPreviewItem], output: String) -> BoardGenerationState {
        BoardGenerationState(isRunning: false, message: message, signals: signals, output: output, isError: false)
    }

    static func failed(_ message: String) -> BoardGenerationState {
        BoardGenerationState(isRunning: false, message: message, signals: [], output: nil, isError: true)
    }
}

private struct BoardSignalPreviewItem: Identifiable {
    let id = UUID()
    let sourceType: String
    let sourceRef: String
    let title: String

    static func from(_ signals: [BoardSignal]) -> [BoardSignalPreviewItem] {
        signals.map {
            BoardSignalPreviewItem(
                sourceType: $0.sourceType,
                sourceRef: $0.sourceRef,
                title: $0.title
            )
        }
    }
}

private struct BoardSignalPreview: View {
    let signals: [BoardSignalPreviewItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("已采集信号")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            ForEach(signals) { signal in
                VStack(alignment: .leading, spacing: 2) {
                    Text(signal.title)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                    Text("\(signal.sourceType) · \(signal.sourceRef)")
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(AmberTheme.muted2)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(10)
        .background(AmberTheme.surface.opacity(0.45), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
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

/// Read-only key/value row for a real seeded TodayBoard setting.
struct BoardPresetKVRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel("\(title)，\(value)")
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
        BoardView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
