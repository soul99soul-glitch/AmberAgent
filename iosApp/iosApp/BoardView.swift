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

    private let handlingRows: [BoardCapabilityRow] = [
        .init(
            title: "看板内容",
            subtitle: "生成后的看板会保存在本机，下次打开继续显示。",
            value: "可用",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "刷新",
            subtitle: "当前支持手动刷新；后台定时刷新暂未开放。",
            value: "手动",
            color: AmberTheme.muted
        ),
        .init(
            title: "任务与机会",
            subtitle: "本页聚焦今日摘要，不生成可派发任务或机会卡片。",
            value: "未开放",
            color: .gray
        ),
        .init(
            title: "深度阅读",
            subtitle: "长文深度阅读和专题整理还没有放进今日看板。",
            value: "未开放",
            color: AmberTheme.accentAmber
        )
    ]

    private let sourceRows: [BoardCapabilityRow] = [
        .init(
            title: "通知 / 日历",
            subtitle: "可读取日历和提醒事项；通知内容暂不读取。",
            value: "日历可用",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "飞书消息 / 文档",
            subtitle: "需要先接入账号授权，当前不会读取。",
            value: "未开放",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "聊天记录",
            subtitle: "会从最近会话中提取少量与今日相关的线索。",
            value: "可用",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "热榜",
            subtitle: "可拉取轻量热榜；网络失败时会显示真实错误。",
            value: "可用",
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

                Text("本地线索 · 手动生成 · 自动保存")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            AmberGlassCircleButton(systemImage: "info.circle", accessibilityLabel: "看板设置", size: 44, symbolSize: 17) {
                router.navigate(to: .boardSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("今日看板会汇总最近聊天、日历提醒、时间线索和轻量热榜，生成一份可保存的今日摘要。需要账号或更高权限的来源不会自动读取。")
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
                            Text("汇总本机可用线索，并用当前模型配置生成摘要。")
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

            BoardCapabilityNote("当前只在你点击按钮时刷新，不会在后台自动运行。")
        }
    }

    private var collectionStatusSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "采集状态")
            AmberFormGroup {
                if collectionSnapshot.statuses.isEmpty {
                    Text(collectionSnapshot.pendingCount > 0 ? "已有 \(collectionSnapshot.pendingCount) 条待使用线索。点击“生成今日看板”开始整理。" : "尚未生成今日看板。点击“生成今日看板”开始整理。")
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

            BoardCapabilityNote("已处理的旧线索会自动清理，保留最近一段时间的记录。")
        }
    }

    private func runManualGeneration() {
        generationState = .running(message: "正在整理本机线索…")
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
                        message: "已整理 \(collected.count) 条可用线索，正在调用模型…",
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
                        generationState = .failed("已整理 \(collected.count) 条线索，但还没有配置模型 API Key。")
                    }
                    return
                }
                guard !modelId.isEmpty else {
                    await MainActor.run {
                        generationState = .failed("已整理 \(collected.count) 条线索，但还没有选择可用模型。")
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
                        message: "已生成今日看板，并保存到本机。",
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
            AmberSectionLabel(text: "默认设置")
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

    private var handlingSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "当前支持")
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

            BoardCapabilityNote("更多来源会在对应账号和权限接入后开放。")
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
        message: "尚未生成。点击按钮后会整理本机可用线索。",
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
            Text("本次使用的线索")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            ForEach(signals) { signal in
                VStack(alignment: .leading, spacing: 2) {
                    Text(signal.title)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                    Text(BoardSourceLabels.title(for: signal.sourceType))
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
                Text(signal.processed ? "已使用" : "待使用")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(signal.processed ? AmberTheme.muted2 : AmberTheme.accentAmber)
            }

            Text(signal.title)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(2)

            Text(IOSBoardDateFormatters.monthDayTime.string(from: Date(timeIntervalSince1970: TimeInterval(signal.signalTime) / 1_000)))
                .font(.system(size: 10))
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
