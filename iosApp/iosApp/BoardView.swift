import SwiftUI
@preconcurrency import Shared

extension BoardSignal: @retroactive @unchecked Sendable {}

struct BoardView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore

    @State private var generationState = BoardGenerationState.idle
    @State private var collectionSnapshot = IOSBoardCollectionSnapshot.empty
    // [Board MVP] Guards the .task restore so in-session re-navigation to this
    // page doesn't overwrite a just-generated state with the persisted restore.
    @State private var hasRestoredPersistedBoard = false

    @Environment(RouterPath.self) private var router
    @Environment(IOSConversationStore.self) private var conversationStore
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
            subtitle: "Swift 原生 signal repository 已接 Documents 持久化、sourceRef/contentHash 去重、processed 标记；聊天、EventKit、热榜和时间锚点由手动 runOnce 聚合。",
            value: "采集中",
            color: AmberTheme.accentAmber
        )
    ]

    private let handlingRows: [BoardCapabilityRow] = [
        .init(
            // [Board MVP] 看板内容已接：模型生成的 Markdown 持久化到
            // Documents/boards/<date>.json（IOSBoardPersistence），重启后 .task
            // 加载最近一条重新显示。范围只做今日看板内容，不做结构化任务流。
            title: "看板内容",
            subtitle: "已接：模型生成的 Markdown 经 IOSBoardPersistence 按日期持久化，重启后重新显示上一份看板。",
            value: "已接",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "刷新",
            subtitle: "iOS 当前提供前台手动 runOnce 聚合框架；BGTaskScheduler 仍未启用，不在后台伪造运行。",
            value: "前台",
            color: AmberTheme.muted
        ),
        .init(
            // 任务流/机会/派发明确不在 iOS 范围（产品决定：只做今日看板内容）。
            title: "任务流 / 机会（不做）",
            subtitle: "iOS 范围只做今日看板内容；任务流/机会/日报派发/BoardItemEntity 不做（产品决定）。",
            value: "不做",
            color: .gray
        ),
        .init(
            title: "深度阅读",
            subtitle: "不读取 HotTopic、DeepRead cache、模板或字体包，也不启动隐藏阅读 Agent。",
            value: "独立工程",
            color: AmberTheme.accentAmber
        )
    ]

    private let sourceRows: [BoardCapabilityRow] = [
        .init(
            title: "通知 / 日历",
            subtitle: "日历和提醒事项通过 EventKit adapter 读取；未授权时返回空状态和错误。通知仍不读取。",
            value: "日历已接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "飞书消息 / 文档",
            subtitle: "Android 有 Feishu signal collectors；iOS 当前没有 Feishu MCP/Skill 写入 BoardSignal 的桥。",
            value: "缺账号桥",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "聊天记录",
            subtitle: "从 IOSConversationStore 只读导出近期会话尾部内容，并用关键词/深度启发式过滤为 chat_history 信号。",
            value: "已接",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "热榜",
            subtitle: "轻量 provider 框架已接；默认可拉取 Hacker News，网络失败时显示真实错误，不填假数据。",
            value: "轻量",
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
                        collectionStatusSection
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
        // [Board MVP] On entry, redisplay the most recent persisted board so a
        // generated board survives app restart. Honest: if none saved, stays idle.
        // Guard on idle so in-session navigation back to this page doesn't
        // clobber a just-generated state with the "重启后恢复" message.
        .task {
            guard !hasRestoredPersistedBoard else { return }
            hasRestoredPersistedBoard = true
            collectionSnapshot = IOSBoardCollectionSnapshot(
                statuses: [],
                recentSignals: IOSBoardSignalRepository.shared.recentSignals(limit: 10),
                pendingCount: IOSBoardSignalRepository.shared.countUnprocessedSignals(),
                lastRunAt: nil,
                lastRunError: nil
            )
            if !generationState.isRunning,
               let recent = IOSBoardPersistence.shared.loadMostRecent(),
               let output = recent.markdown.isEmpty ? nil : recent.markdown {
                generationState = BoardGenerationState(
                    isRunning: false,
                    message: "上次生成的今日看板（\(recent.boardDate)，\(recent.signalCount) 条信号）— 重启后恢复显示。",
                    signals: [],
                    output: output,
                    isError: false
                )
            }
        }
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

                Text("本地 signals · 手动 runOnce · Documents 持久化")
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
        Text("iOS 今日看板（范围：只做今日看板内容）：手动 runOnce 会采集聊天历史、EventKit 日历/提醒事项、轻量热榜和 KMP 时间锚点，写入 Documents/boards/signals/ 并做 sourceRef/contentHash 去重，再把筛选后的真实信号交给 BoardAgent 生成 Markdown，保存到 Documents/boards/。任务流/机会/日报派发/深度阅读不做；通知、飞书等需要更大权限或账号边界的来源不伪造。")
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
                            Text("采集本地 Board signals，去重持久化后用当前 OpenAI 兼容配置调用 KMP BoardAgent。")
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

            BoardCapabilityNote("当前只做前台手动 runOnce；不会启动 BGTaskScheduler，不读取通知，不伪造日历、热榜、飞书或聊天数据。")
        }
    }

    private var collectionStatusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "采集状态")
            AmberFormGroup {
                if collectionSnapshot.statuses.isEmpty {
                    Text("尚未运行本地 collector。已有 \(collectionSnapshot.pendingCount) 条未处理 signals；点击“生成今日看板”会执行一次前台 runOnce。")
                        .font(.caption)
                        .lineSpacing(3)
                        .foregroundStyle(AmberTheme.foreground2)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(collectionSnapshot.statuses.enumerated()), id: \.element.id) { index, status in
                        BoardCollectorStatusRow(status: status)
                        if index < collectionSnapshot.statuses.count - 1 {
                            BoardCapabilityDivider()
                        }
                    }
                }
            }

            if !collectionSnapshot.recentSignals.isEmpty {
                AmberSectionLabel(text: "最近信号")
                    .padding(.top, 10)
                AmberFormGroup {
                    ForEach(Array(collectionSnapshot.recentSignals.enumerated()), id: \.element.id) { index, signal in
                        BoardRecentSignalRow(signal: signal)
                        if index < collectionSnapshot.recentSignals.count - 1 {
                            BoardCapabilityDivider()
                        }
                    }
                }
            }

            BoardCapabilityNote("Signals 持久化在 Documents/boards/signals/board_signals.json；processed=true 的旧信号会按最小 7 天窗口裁剪。")
        }
    }

    private func runManualGeneration() {
        generationState = .running(message: "正在采集本地 Board signals…")
        Task {
            do {
                let setting = sharedSettings.agentRuntime.todayBoard
                let repository = IOSBoardSignalRepository.shared
                let aggregator = IOSBoardSignalAggregator(
                    repository: repository,
                    collectors: makeBoardSignalCollectors(setting: setting)
                )
                let run = await aggregator.runOnce(
                    limitPerCollector: 50,
                    agentLimit: 80,
                    enabledSources: enabledIOSBoardSources(setting: setting)
                )
                let collected = run.boardSignals

                await MainActor.run {
                    collectionSnapshot = run.snapshot
                    generationState = .running(
                        message: "本轮采集 \(run.snapshot.totalCollected) 条、入库 \(run.snapshot.totalIngested) 条，筛选 \(collected.count) 条信号，正在调用模型…",
                        signals: BoardSignalPreviewItem.from(collected)
                    )
                }

                if collected.isEmpty {
                    repository.markSignalsProcessed(ids: run.batch.consideredIds)
                    let output = "今日暂无可用于生成看板的本地信号。"
                    IOSBoardPersistence.shared.save(board: .init(
                        boardDate: IOSBoardPersistence.shared.todayBoardDate(),
                        markdown: output,
                        signalCount: 0,
                        generatedAt: Int64(Date().timeIntervalSince1970 * 1000),
                        sourceCounts: run.snapshot.sourceCounts
                    ))
                    await MainActor.run {
                        generationState = .finished(
                            message: "采集完成：暂无可用于生成的真实信号；已保存空状态。",
                            signals: [],
                            output: output
                        )
                    }
                    return
                }

                let apiKey = settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
                let modelId = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !apiKey.isEmpty else {
                    await MainActor.run {
                        generationState = .failed("已采集并持久化 \(collected.count) 条信号，但未配置 OpenAI API Key；信号保留为未处理。")
                    }
                    return
                }
                guard !modelId.isEmpty else {
                    await MainActor.run {
                        generationState = .failed("已采集并持久化 \(collected.count) 条信号，但未配置看板模型；信号保留为未处理。")
                    }
                    return
                }

                let agent = factory.createAgent(
                    baseUrl: settingsStore.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines),
                    apiKey: apiKey,
                    modelId: modelId,
                    chatCompletionsPath: "/chat/completions"
                )
                let output = try await generateBoard(agent: agent, signals: collected, setting: setting)
                repository.markSignalsProcessed(ids: run.batch.consideredIds)
                // [Board MVP] Persist the generated board Markdown by date so it
                // survives restart and redisplay on next launch. Scope = 今日看板
                // 内容 only (no task-flow / BoardItemEntity / dispatch).
                IOSBoardPersistence.shared.save(board: .init(
                    boardDate: IOSBoardPersistence.shared.todayBoardDate(),
                    markdown: output,
                    signalCount: collected.count,
                    generatedAt: Int64(Date().timeIntervalSince1970 * 1000),
                    sourceCounts: run.snapshot.sourceCounts
                ))
                await MainActor.run {
                    collectionSnapshot = IOSBoardCollectionSnapshot(
                        statuses: run.snapshot.statuses,
                        recentSignals: repository.recentSignals(limit: 10),
                        pendingCount: repository.countUnprocessedSignals(),
                        lastRunAt: run.snapshot.lastRunAt,
                        lastRunError: run.snapshot.lastRunError
                    )
                    generationState = .finished(
                        message: "采集完成：\(collected.count) 条信号已进入 BoardAgent；已标记 processed 并保存到 Documents/boards/。",
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

    private var factory: IosBoardFactory { IosBoardFactory.shared }

    private func makeBoardSignalCollectors(setting: TodayBoardSetting) -> [IOSBoardSignalCollector] {
        [
            IOSChatHistorySignalCollector(source: conversationStore),
            IOSEventKitCalendarSignalCollector(),
            IOSEventKitReminderSignalCollector(),
            IOSHotlistSignalCollector(),
            IOSKMPTimeSignalCollector(setting: setting)
        ]
    }

    private func enabledIOSBoardSources(setting: TodayBoardSetting) -> Set<String> {
        var enabled = Set(setting.enabledSources.map { String(describing: $0) })
        if enabled.contains(IOSBoardSignalSourceType.calendar) {
            enabled.insert(IOSBoardSignalSourceType.reminder)
        }
        if !setting.hotListEnabledSources.isEmpty {
            enabled.insert(IOSBoardSignalSourceType.hotlist)
        }
        enabled.insert(IOSBoardSignalSourceType.time)
        return enabled
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

private struct BoardCollectorStatusRow: View {
    let status: IOSBoardCollectorStatus

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: status.errorMessage == nil ? "checkmark.circle" : "exclamationmark.triangle")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(status.errorMessage == nil ? AmberTheme.accentGreen : AmberTheme.accentAmber)
                .frame(width: 30, height: 30)
                .background(AmberTheme.surface.opacity(0.7), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(BoardSourceLabels.title(for: status.sourceType))
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)

                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 2) {
                Text("\(status.ingestedCount)/\(status.collectedCount)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(status.ingestedCount > 0 ? AmberTheme.accentGreen : AmberTheme.foreground2)
                if status.duplicateCount > 0 {
                    Text("去重 \(status.duplicateCount)")
                        .font(.system(size: 10))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }

    private var subtitle: String {
        if let error = status.errorMessage, !error.isEmpty {
            return error
        }
        if let title = status.latestTitle, !title.isEmpty {
            return "最近：\(title)"
        }
        if let message = status.statusMessage, !message.isEmpty {
            return message
        }
        return "暂无新信号"
    }
}

private struct BoardRecentSignalRow: View {
    let signal: IOSBoardSignalRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                Text(BoardSourceLabels.title(for: signal.sourceType))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                Text(signal.processed ? "processed" : "pending")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundStyle(signal.processed ? AmberTheme.muted2 : AmberTheme.accentAmber)
            }

            Text(signal.title)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(2)

            Text("\(signal.sourceRef) · \(IOSBoardDateFormatters.monthDayTime.string(from: Date(timeIntervalSince1970: TimeInterval(signal.signalTime) / 1_000)))")
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(AmberTheme.muted2)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }
}

private enum BoardSourceLabels {
    static func title(for sourceType: String) -> String {
        switch sourceType {
        case IOSBoardSignalSourceType.chatHistory:
            return "聊天历史"
        case IOSBoardSignalSourceType.calendar:
            return "日历"
        case IOSBoardSignalSourceType.reminder:
            return "提醒事项"
        case IOSBoardSignalSourceType.hotlist:
            return "热榜"
        case IOSBoardSignalSourceType.time:
            return "时间锚点"
        case IOSBoardSignalSourceType.notification:
            return "通知"
        case IOSBoardSignalSourceType.feishuMessage:
            return "飞书消息"
        case IOSBoardSignalSourceType.feishuDocument:
            return "飞书文档"
        default:
            return sourceType
        }
    }
}

#Preview {
    NavigationStack {
        BoardView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
            .environment(IOSConversationStore())
    }
}
