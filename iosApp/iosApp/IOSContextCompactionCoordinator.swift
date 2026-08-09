import Foundation
@preconcurrency import Shared

private struct IOSConversationCompact: Codable, Equatable {
    let id: String
    let conversationId: String
    let summary: String
    let level: Int
    let sourceStartIndex: Int
    let sourceEndIndex: Int
    let sourceMessageIds: [String]
    let tokenEstimate: Int
    let createdAt: Int64
    let updatedAt: Int64
    let status: String
}

private struct IOSCompactPolicy {
    let enabled: Bool
    let notifyOnly: Bool
    let precompactRatio: Double
    let forceRatio: Double
    let keepRecentTurns: Int
    let maxSummaryTokens: Int

    init(_ setting: ContextCompactionSetting) {
        enabled = setting.enabled
        notifyOnly = setting.notifyOnly
        precompactRatio = Double(setting.precompactRatio)
        forceRatio = Double(setting.forceRatio)
        keepRecentTurns = Int(setting.keepRecentTurns)
        maxSummaryTokens = Int(setting.maxSummaryTokens)
    }
}

private struct IOSCompactPlan {
    let shouldCompact: Bool
    let reason: String
    let estimatedTokens: Int
    let contextWindowTokens: Int
    let sourceStartIndex: Int
    let sourceEndIndex: Int
    let sourceMessageIds: [String]

    var sourceMessageCount: Int {
        shouldCompact ? sourceEndIndex - sourceStartIndex + 1 : 0
    }
}

enum IOSContextCompactionEvent {
    case idle
    case planning
    case compacting
    case completed(summary: String)
    case failed(message: String)
}

enum ChatContextCompactEventRouter {
    static func shouldApply(
        event: IOSContextCompactionEvent,
        eventRunId: String,
        currentRunId: String?
    ) -> Bool {
        if currentRunId == eventRunId {
            return true
        }
        guard currentRunId == nil else {
            return false
        }
        switch event {
        case .idle, .completed, .failed:
            return true
        case .planning, .compacting:
            return false
        }
    }
}

@MainActor
final class IOSContextCompactionCoordinator {
    static let shared = IOSContextCompactionCoordinator()

    private let store = IOSConversationCompactStore()
    private var runningCompactions: Set<String> = []
    private var activeCompactionTasks: [String: Task<IOSConversationCompact?, Error>] = [:]

    private init() {}

    static func estimatedTokensForRequest(_ messages: [UIMessage]) -> Int {
        estimateTokens(messages)
    }

    func prepareMessagesForRequest(
        uploadMessages: [UIMessage],
        conversationId: KotlinUuid?,
        settings: Settings,
        params: TextGenerationParams,
        fallbackProvider: ProviderSetting,
        promptOverheadTokens: Int = 0,
        onEvent: ((IOSContextCompactionEvent) -> Void)? = nil
    ) async throws -> [UIMessage] {
        let policy = IOSCompactPolicy(settings.agentRuntime.contextCompaction)
        let contextMessageSize = Int(settings.resolveSessionDefaults(
            assistant: settings.getCurrentAssistant(),
            model: params.model
        ).contextMessageSize)
        let overheadEstimate = Self.requestOverheadTokens(
            params: params,
            promptOverheadTokens: promptOverheadTokens
        )

        let edited: (messages: [UIMessage], removedToolResults: Int)
        if policy.enabled {
            edited = Self.editPreparedContext(
                messages: uploadMessages,
                keepRecentMessages: max(policy.keepRecentTurns * 2, 4)
            )
        } else {
            edited = (uploadMessages, 0)
        }
        let editedMessages = edited.messages
        let removedToolResults = edited.removedToolResults

        guard policy.enabled, let conversationId else {
            return Self.limitContext(editedMessages, size: contextMessageSize)
        }

        let conversationKey = String(describing: conversationId)
        var compacts = store.load(conversationId: conversationKey)
        let plan = Self.planCompaction(
            messages: editedMessages,
            activeCompacts: compacts,
            policy: policy,
            modelContextWindowTokens: Self.intValue(params.model.contextWindowTokens),
            extraTokenEstimate: overheadEstimate
        )

        if plan.shouldCompact && !policy.notifyOnly {
            onEvent?(.planning)
            if plan.reason == "force_threshold" {
                onEvent?(.compacting)
                let result = try await compactConversation(
                    messages: uploadMessages,
                    conversationKey: conversationKey,
                    settings: settings,
                    policy: policy,
                    model: params.model,
                    fallbackProvider: fallbackProvider,
                    reason: "auto_force",
                    force: true
                )
                if result == nil {
                    onEvent?(.failed(message: "没有可压缩的历史"))
                    throw NSError(
                        domain: "AmberAgent.ContextCompaction",
                        code: 1,
                        userInfo: [NSLocalizedDescriptionKey: "上下文已超过强制压缩阈值，但没有可压缩的历史。"]
                    )
                }
                if let result {
                    onEvent?(.completed(summary: Self.timelineSummary(result.summary) ?? "上下文已压缩。"))
                }
                compacts = store.load(conversationId: conversationKey)
            } else {
                schedulePrecompact(
                    messages: uploadMessages,
                    conversationKey: conversationKey,
                    settings: settings,
                    policy: policy,
                    model: params.model,
                    fallbackProvider: fallbackProvider,
                    onEvent: onEvent
                )
            }
        }

        var preparedMessages = Self.prepareMessagesWithCompacts(
            messages: editedMessages,
            activeCompacts: compacts,
            policy: policy,
            contextMessageSize: contextMessageSize,
            removedToolResults: removedToolResults
        )
        let contextWindow = Self.estimateContextWindow(Self.intValue(params.model.contextWindowTokens))
        let forceBudget = max(Int(Double(contextWindow) * policy.forceRatio), 1)
        let softTotalBudget = max(forceBudget, 4_000)
        let targetMessageBudget = max(softTotalBudget - overheadEstimate, 1_000)
        var estimate = Self.estimateTokens(preparedMessages) + overheadEstimate

        if !policy.notifyOnly && estimate > forceBudget {
            let fitPolicy = IOSCompactPolicy(
                enabled: policy.enabled,
                notifyOnly: policy.notifyOnly,
                precompactRatio: policy.precompactRatio,
                forceRatio: policy.forceRatio,
                keepRecentTurns: max(policy.keepRecentTurns / 2, 2),
                maxSummaryTokens: policy.maxSummaryTokens
            )
            onEvent?(.compacting)
            let compact = try await compactConversation(
                messages: uploadMessages,
                conversationKey: conversationKey,
                settings: settings,
                policy: fitPolicy,
                model: params.model,
                fallbackProvider: fallbackProvider,
                reason: "auto_fit_model_window",
                force: true
            )
            compacts = store.load(conversationId: conversationKey)
            if let compact {
                onEvent?(.completed(summary: Self.timelineSummary(compact.summary) ?? "上下文已压缩。"))
            } else if let latest = Self.selectCompactsForInjection(
                activeCompacts: compacts,
                existingMessageIds: Set(editedMessages.map(Self.messageId))
            ).last {
                onEvent?(.completed(summary: Self.timelineSummary(latest.summary) ?? "上下文已压缩。"))
            } else {
                onEvent?(.idle)
            }
            preparedMessages = Self.prepareMessagesWithCompacts(
                messages: editedMessages,
                activeCompacts: compacts,
                policy: fitPolicy,
                contextMessageSize: contextMessageSize,
                removedToolResults: removedToolResults
            )
            estimate = Self.estimateTokens(preparedMessages) + overheadEstimate
        }

        if estimate > forceBudget {
            preparedMessages = Self.fitMessagesToTokenBudget(
                preparedMessages,
                maxTokens: targetMessageBudget
            )
        }
        try Self.assertFitsRequest(
            messages: preparedMessages,
            overheadTokens: overheadEstimate,
            forceBudget: forceBudget
        )
        return preparedMessages
    }

    func finalizedMessagesForRequest(
        _ messages: [UIMessage],
        settings: Settings,
        params: TextGenerationParams
    ) throws -> [UIMessage] {
        let policy = IOSCompactPolicy(settings.agentRuntime.contextCompaction)
        let toolOverhead = Self.requestOverheadTokens(params: params)
        let contextWindow = Self.estimateContextWindow(Self.intValue(params.model.contextWindowTokens))
        let forceBudget = max(Int(Double(contextWindow) * policy.forceRatio), 1)
        let estimate = Self.estimateTokens(messages) + toolOverhead
        guard estimate > forceBudget else { return messages }
        let softTotalBudget = max(forceBudget, 4_000)
        let targetMessageBudget = max(softTotalBudget - toolOverhead, 1_000)
        let fitted = Self.fitMessagesToTokenBudget(messages, maxTokens: targetMessageBudget)
        try Self.assertFitsRequest(
            messages: fitted,
            overheadTokens: toolOverhead,
            forceBudget: forceBudget
        )
        return fitted
    }

    private func schedulePrecompact(
        messages: [UIMessage],
        conversationKey: String,
        settings: Settings,
        policy: IOSCompactPolicy,
        model: Model,
        fallbackProvider: ProviderSetting,
        onEvent: ((IOSContextCompactionEvent) -> Void)?
    ) {
        guard !runningCompactions.contains(conversationKey) else { return }
        runningCompactions.insert(conversationKey)
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.runningCompactions.remove(conversationKey) }
            do {
                onEvent?(.compacting)
                let compact = try await self.compactConversation(
                    messages: messages,
                    conversationKey: conversationKey,
                    settings: settings,
                    policy: policy,
                    model: model,
                    fallbackProvider: fallbackProvider,
                    reason: "auto_precompact",
                    force: false
                )
                if let compact {
                    onEvent?(.completed(summary: Self.timelineSummary(compact.summary) ?? "上下文已压缩。"))
                } else {
                    onEvent?(.idle)
                }
            } catch {
                onEvent?(.failed(message: error.localizedDescription))
                NSLog("[ContextCompact] background precompact failed: \(error.localizedDescription)")
            }
        }
    }

    private func compactConversation(
        messages: [UIMessage],
        conversationKey: String,
        settings: Settings,
        policy: IOSCompactPolicy,
        model: Model,
        fallbackProvider: ProviderSetting,
        reason: String,
        force: Bool
    ) async throws -> IOSConversationCompact? {
        while let task = activeCompactionTasks[conversationKey] {
            do {
                _ = try await task.value
            } catch {
                NSLog("[ContextCompact] previous compact task failed before \(reason): \(error.localizedDescription)")
            }
        }
        let task = Task<IOSConversationCompact?, Error> { @MainActor [weak self] in
            guard let self else { return nil }
            defer { self.activeCompactionTasks[conversationKey] = nil }
            return try await self.compactConversationLocked(
                messages: messages,
                conversationKey: conversationKey,
                settings: settings,
                policy: policy,
                model: model,
                fallbackProvider: fallbackProvider,
                reason: reason,
                force: force
            )
        }
        activeCompactionTasks[conversationKey] = task
        return try await task.value
    }

    private func compactConversationLocked(
        messages: [UIMessage],
        conversationKey: String,
        settings: Settings,
        policy: IOSCompactPolicy,
        model: Model,
        fallbackProvider: ProviderSetting,
        reason: String,
        force: Bool
    ) async throws -> IOSConversationCompact? {
        var compacts = store.load(conversationId: conversationKey)
        let plan = force
            ? Self.planForceCompaction(
                messages: messages,
                activeCompacts: compacts,
                policy: policy,
                modelContextWindowTokens: Self.intValue(model.contextWindowTokens)
            )
            : Self.planCompaction(
                messages: messages,
                activeCompacts: compacts,
                policy: policy,
                modelContextWindowTokens: Self.intValue(model.contextWindowTokens),
                extraTokenEstimate: 0
            )
        guard plan.shouldCompact else { return nil }

        let preferredCompressionModel = settings.findModelById(uuid: settings.compressModelId)
            ?? settings.getCurrentChatModel()
        let preferredProvider = preferredCompressionModel.flatMap {
            ChatProviderConfiguration.provider(for: $0, providers: settings.providers)
        }
        let canUsePreferred = preferredProvider.map(Self.supportsTextGeneration) ?? false
        let compressionModel = canUsePreferred ? (preferredCompressionModel ?? model) : model
        let rawProvider = canUsePreferred ? (preferredProvider ?? fallbackProvider) : fallbackProvider

        let sourceMessages = Array(messages[plan.sourceStartIndex...plan.sourceEndIndex])
        let sourceMessageIds = plan.sourceMessageIds
        let existingMessageIds = Set(messages.map(Self.messageId))
        let previousCompacts = Self.selectCompactsForInjection(
            activeCompacts: compacts,
            existingMessageIds: existingMessageIds
        )
        let coveredCompactIds = previousCompacts.map(\.id)
        let previousCompactContext = previousCompacts
            .map { Self.injectionText($0) }
            .joined(separator: "\n\n")
        let createdAt = Self.nowMillis()
        let prompt = Self.buildCompressionPrompt(
            basePrompt: settings.compressPrompt,
            content: Self.buildCompressionInput(sourceMessages),
            targetTokens: policy.maxSummaryTokens,
            additionalPrompt: "",
            sourceMessageIds: sourceMessageIds,
            coveredCompactIds: coveredCompactIds,
            previousCompactContext: previousCompactContext,
            createdAt: createdAt
        )

        let rawSummary = try await generateCompactSummary(
            provider: rawProvider,
            model: compressionModel,
            prompt: prompt
        )
        var normalizedSummary = Self.normalizedPayload(
            rawSummary,
            sourceMessageIds: sourceMessageIds,
            coveredCompactIds: coveredCompactIds,
            createdAt: createdAt,
            sourceContent: Self.buildCompressionInput(sourceMessages),
            carriedHandoffMarkdown: previousCompactContext
        )

        if !Self.isHighQualityPayload(normalizedSummary) {
            let retryPrompt = Self.buildCompressionPrompt(
                basePrompt: settings.compressPrompt,
                content: Self.buildCompressionInput(sourceMessages),
                targetTokens: policy.maxSummaryTokens,
                additionalPrompt: "Retry because the previous compaction did not satisfy the schema or the timeline summary was too short. Return valid JSON only. `timeline_summary` must contain 4-5 complete sentences, and `handoff_markdown` must contain the required sections.",
                sourceMessageIds: sourceMessageIds,
                coveredCompactIds: coveredCompactIds,
                previousCompactContext: previousCompactContext,
                createdAt: createdAt
            )
            let retrySummary = try await generateCompactSummary(
                provider: rawProvider,
                model: compressionModel,
                prompt: retryPrompt
            )
            normalizedSummary = Self.normalizedPayload(
                retrySummary.isEmpty ? rawSummary : retrySummary,
                sourceMessageIds: sourceMessageIds,
                coveredCompactIds: coveredCompactIds,
                createdAt: createdAt,
                sourceContent: Self.buildCompressionInput(sourceMessages),
                carriedHandoffMarkdown: previousCompactContext
            )
        }

        let compactId = UUID().uuidString.lowercased()
        let compact = IOSConversationCompact(
            id: compactId,
            conversationId: conversationKey,
            summary: normalizedSummary,
            level: 1,
            sourceStartIndex: plan.sourceStartIndex,
            sourceEndIndex: plan.sourceEndIndex,
            sourceMessageIds: sourceMessageIds,
            tokenEstimate: Self.estimateTokens([
                UIMessage.companion.system(prompt: Self.injectionText(
                    id: compactId,
                    summary: normalizedSummary,
                    sourceMessageIds: sourceMessageIds
                ))
            ]),
            createdAt: createdAt,
            updatedAt: Self.nowMillis(),
            status: "completed"
        )
        compacts = store.load(conversationId: conversationKey)
        if !compacts.contains(where: { $0.id == compact.id }) {
            compacts.append(compact)
        }
        store.save(compacts, conversationId: conversationKey)
        NSLog("[ContextCompact] \(reason) compacted \(plan.sourceMessageCount) messages into \(compact.id)")
        return compact
    }

    private func generateCompactSummary(
        provider rawProvider: ProviderSetting,
        model: Model,
        prompt: String
    ) async throws -> String {
        let provider = try await IOSCodexProviderResolver.resolved(rawProvider)
        let params = IOSCodexProviderResolver.augmentParamsForCodex(
            TextGenerationParams(
                model: model,
                temperature: nil,
                topP: nil,
                maxTokens: nil,
                tools: [],
                reasoningLevel: ReasoningLevel.off,
                customHeaders: [],
                customBody: []
            ),
            provider: provider
        )
        let chunk = try await OpenAIKmpProviderAdapter().generateText(
            providerSetting: provider,
            messages: [UIMessage.companion.user(prompt: prompt)],
            params: params
        )
        return chunk.choices.first?.message?.toText()
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}

private extension IOSCompactPolicy {
    init(
        enabled: Bool,
        notifyOnly: Bool,
        precompactRatio: Double,
        forceRatio: Double,
        keepRecentTurns: Int,
        maxSummaryTokens: Int
    ) {
        self.enabled = enabled
        self.notifyOnly = notifyOnly
        self.precompactRatio = precompactRatio
        self.forceRatio = forceRatio
        self.keepRecentTurns = keepRecentTurns
        self.maxSummaryTokens = maxSummaryTokens
    }
}

private final class IOSConversationCompactStore {
    private let directory: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        directory = documents.appendingPathComponent("conversation-compacts", isDirectory: true)
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
    }

    func load(conversationId: String) -> [IOSConversationCompact] {
        let url = fileURL(conversationId: conversationId)
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? decoder.decode([IOSConversationCompact].self, from: data)) ?? []
    }

    func save(_ compacts: [IOSConversationCompact], conversationId: String) {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(compacts)
            try data.write(to: fileURL(conversationId: conversationId), options: .atomic)
        } catch {
            NSLog("[ContextCompact] failed to persist compacts: \(error.localizedDescription)")
        }
    }

    private func fileURL(conversationId: String) -> URL {
        let safe = conversationId
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return directory.appendingPathComponent("\(safe).json")
    }
}

private extension IOSContextCompactionCoordinator {
    static let defaultContextWindowTokens = 128_000
    static let maxTimelineSummaryChars = 1_200
    static let maxHandoffChars = 24_000

    static func estimateTokens(_ messages: [UIMessage]) -> Int {
        let chars = messages.reduce(0) { total, message in
            total + message.role.name.count + message.parts.reduce(0) { $0 + estimatedChars($1) }
        }
        return max(chars / 4, messages.count * 4)
    }

    static func estimateContextWindow(_ modelContextWindowTokens: Int?) -> Int {
        guard let value = modelContextWindowTokens, value > 0 else {
            return defaultContextWindowTokens
        }
        return value
    }

    static func planCompaction(
        messages: [UIMessage],
        activeCompacts: [IOSConversationCompact],
        policy: IOSCompactPolicy,
        modelContextWindowTokens: Int?,
        extraTokenEstimate: Int
    ) -> IOSCompactPlan {
        guard policy.enabled, !messages.isEmpty else {
            return skipped(reason: "disabled", messages: messages, modelContextWindowTokens: modelContextWindowTokens)
        }
        let estimatedTokens = estimateTokens(messages) + max(extraTokenEstimate, 0)
        let contextWindow = estimateContextWindow(modelContextWindowTokens)
        let ratio = Double(estimatedTokens) / Double(contextWindow)
        guard ratio >= policy.precompactRatio else {
            return IOSCompactPlan(
                shouldCompact: false,
                reason: "below_threshold",
                estimatedTokens: estimatedTokens,
                contextWindowTokens: contextWindow,
                sourceStartIndex: 0,
                sourceEndIndex: -1,
                sourceMessageIds: []
            )
        }

        let keepCount = max(policy.keepRecentTurns * 2, 2)
        let sourceEnd = min(messages.count - 1 - keepCount, messages.count - 1)
        guard sourceEnd >= 1 else {
            return IOSCompactPlan(
                shouldCompact: false,
                reason: "not_enough_history",
                estimatedTokens: estimatedTokens,
                contextWindowTokens: contextWindow,
                sourceStartIndex: 0,
                sourceEndIndex: -1,
                sourceMessageIds: []
            )
        }

        let existingIds = Set(messages.map(messageId))
        let latestCoveredEnd = validCompletedCompacts(activeCompacts, existingMessageIds: existingIds)
            .map(\.sourceEndIndex)
            .max() ?? -1
        guard latestCoveredEnd < sourceEnd else {
            return IOSCompactPlan(
                shouldCompact: false,
                reason: "already_compacted",
                estimatedTokens: estimatedTokens,
                contextWindowTokens: contextWindow,
                sourceStartIndex: 0,
                sourceEndIndex: -1,
                sourceMessageIds: []
            )
        }

        var start = max(latestCoveredEnd + 1, 0)
        var end = sourceEnd
        while start <= end,
              messages[start].role == MessageRole.assistant,
              messageHasExecutedTool(messages[start]) {
            start += 1
        }
        while end >= start, messageHasPendingTool(messages[end]) {
            end -= 1
        }
        guard end - start + 1 >= 2 else {
            return IOSCompactPlan(
                shouldCompact: false,
                reason: "not_enough_new_history",
                estimatedTokens: estimatedTokens,
                contextWindowTokens: contextWindow,
                sourceStartIndex: 0,
                sourceEndIndex: -1,
                sourceMessageIds: []
            )
        }
        return IOSCompactPlan(
            shouldCompact: true,
            reason: ratio >= policy.forceRatio ? "force_threshold" : "precompact_threshold",
            estimatedTokens: estimatedTokens,
            contextWindowTokens: contextWindow,
            sourceStartIndex: start,
            sourceEndIndex: end,
            sourceMessageIds: messages[start...end].map(messageId)
        )
    }

    static func planForceCompaction(
        messages: [UIMessage],
        activeCompacts: [IOSConversationCompact],
        policy: IOSCompactPolicy,
        modelContextWindowTokens: Int?
    ) -> IOSCompactPlan {
        var turns: [Int] = []
        var current = max(policy.keepRecentTurns, 1)
        while current > 1 {
            turns.append(current)
            current = max(current / 2, 1)
        }
        turns.append(1)
        turns = Array(NSOrderedSet(array: turns).array as? [Int] ?? turns)

        let contextWindow = estimateContextWindow(modelContextWindowTokens)
        let targetTokens = max(Int(Double(contextWindow) * policy.forceRatio), 1)
        var deepestPlan: IOSCompactPlan?
        var lastPlan: IOSCompactPlan?

        for keepRecentTurns in turns {
            let attemptPolicy = IOSCompactPolicy(
                enabled: true,
                notifyOnly: policy.notifyOnly,
                precompactRatio: 0,
                forceRatio: .greatestFiniteMagnitude,
                keepRecentTurns: keepRecentTurns,
                maxSummaryTokens: policy.maxSummaryTokens
            )
            let plan = planCompaction(
                messages: messages,
                activeCompacts: activeCompacts,
                policy: attemptPolicy,
                modelContextWindowTokens: modelContextWindowTokens,
                extraTokenEstimate: 0
            )
            lastPlan = plan
            if plan.shouldCompact {
                deepestPlan = plan
                if estimateAfterCompaction(
                    messages: messages,
                    activeCompacts: activeCompacts,
                    plan: plan,
                    maxSummaryTokens: policy.maxSummaryTokens
                ) <= targetTokens {
                    return IOSCompactPlan(
                        shouldCompact: true,
                        reason: "force_threshold",
                        estimatedTokens: plan.estimatedTokens,
                        contextWindowTokens: plan.contextWindowTokens,
                        sourceStartIndex: plan.sourceStartIndex,
                        sourceEndIndex: plan.sourceEndIndex,
                        sourceMessageIds: plan.sourceMessageIds
                    )
                }
            }
        }
        return deepestPlan.map {
            IOSCompactPlan(
                shouldCompact: $0.shouldCompact,
                reason: "force_threshold",
                estimatedTokens: $0.estimatedTokens,
                contextWindowTokens: $0.contextWindowTokens,
                sourceStartIndex: $0.sourceStartIndex,
                sourceEndIndex: $0.sourceEndIndex,
                sourceMessageIds: $0.sourceMessageIds
            )
        } ?? lastPlan ?? skipped(
            reason: "not_enough_history",
            messages: messages,
            modelContextWindowTokens: modelContextWindowTokens
        )
    }

    static func prepareMessagesWithCompacts(
        messages: [UIMessage],
        activeCompacts: [IOSConversationCompact],
        policy: IOSCompactPolicy,
        contextMessageSize: Int,
        removedToolResults: Int = 0
    ) -> [UIMessage] {
        guard policy.enabled, !activeCompacts.isEmpty else {
            return limitContext(messages, size: contextMessageSize)
        }
        let existingIds = Set(messages.map(messageId))
        let completed = validCompletedCompacts(activeCompacts, existingMessageIds: existingIds)
        guard !completed.isEmpty else {
            return limitContext(messages, size: contextMessageSize)
        }

        let selected = selectCompactsForInjection(activeCompacts: activeCompacts, existingMessageIds: existingIds)
        let summaries = selected.enumerated().map { index, compact in
            UIMessage.companion.system(prompt: injectionText(
                compact,
                removedToolResults: index == selected.count - 1 ? removedToolResults : 0
            ))
        }
        let coveredIds = Set(completed.flatMap(\.sourceMessageIds))
        let recentMessages = messages.filter { !coveredIds.contains(messageId($0)) }
        let keepLimit = contextMessageSize > 0
            ? contextMessageSize
            : max(policy.keepRecentTurns * 2, 12)
        return summaries + limitContext(recentMessages, size: keepLimit)
    }

    static func fitMessagesToTokenBudget(_ messages: [UIMessage], maxTokens: Int) -> [UIMessage] {
        let originalCount = messages.count
        guard maxTokens > 0, !messages.isEmpty else {
            return appendingTruncationNotice(
                to: Array(messages.suffix(1)),
                originalCount: originalCount,
                maxTokens: maxTokens
            )
        }
        guard estimateTokens(messages) > maxTokens else { return messages }

        let systemMessages = messages.filter { $0.role == MessageRole.system }
        let tail = messages.filter { $0.role != MessageRole.system }
        var selected: [UIMessage] = []
        for message in tail.reversed() {
            selected.insert(message, at: 0)
            let candidate = systemMessages + selected
            if estimateTokens(candidate) > maxTokens {
                selected.removeFirst()
                if selected.isEmpty {
                    // 唯一候选超预算：不再整条硬保（那会撑爆 assertFitsRequest 让
                    // 请求必败）——先就地截断其工具输出到预算内；无可截工具输出
                    // （纯文本巨消息）才保留旧行为（宁超窗不丢消息）。
                    selected.insert(
                        messageFittedToTokenBudget(message, systemMessages: systemMessages, maxTokens: maxTokens),
                        at: 0
                    )
                }
                break
            }
        }
        var result = systemMessages + selected
        guard estimateTokens(result) > maxTokens else {
            return appendingTruncationNotice(to: result, originalCount: originalCount, maxTokens: maxTokens)
        }

        result = trimmingCompactHandoffSystemMessages(in: result, maxTokens: maxTokens)
        guard estimateTokens(result) > maxTokens else {
            return appendingTruncationNotice(to: result, originalCount: originalCount, maxTokens: maxTokens)
        }

        let compactIndexes = result.indices.filter { isCompactHandoffSystemMessage(result[$0]) }
        if compactIndexes.count > 1 {
            let newestCompactIndex = compactIndexes.last
            result = result.enumerated().compactMap { index, message in
                if isCompactHandoffSystemMessage(message), index != newestCompactIndex {
                    return nil
                }
                return message
            }
        }
        return appendingTruncationNotice(to: result, originalCount: originalCount, maxTokens: maxTokens)
    }

    /// 截尾兜底：唯一候选消息超预算时,先就地截断其工具输出到预算内(单条输出
    /// 逐级压小 + 截断标记),让已持久化的巨量工具输出在请求时被救活;无可截的
    /// 纯文本巨消息返回原样(旧行为:宁超窗不丢消息)。
    static func messageFittedToTokenBudget(
        _ message: UIMessage,
        systemMessages: [UIMessage],
        maxTokens: Int
    ) -> UIMessage {
        if estimateTokens(systemMessages + [message]) <= maxTokens { return message }
        var fitted = message
        var fit = false
        for limit in [12_000, 8_000, 6_000, 4_000, 3_000, 2_000, 1_500, 1_000, 800, 600, 400, 200] {
            fitted = truncatingToolOutputs(in: message, maxCharsPerOutput: limit)
            if estimateTokens(systemMessages + [fitted]) <= maxTokens {
                fit = true
                break
            }
        }
        return fit ? fitted : message
    }

    /// 就地截断消息中所有工具输出的文本部分。复用收口上限语义:JSON 形态保形
    /// 截断、已带压缩标记的输出不二次截断、多模态输出不动。
    static func truncatingToolOutputs(in message: UIMessage, maxCharsPerOutput: Int) -> UIMessage {
        let parts = message.parts.map { part -> UIMessagePart in
            guard let tool = part as? UIMessagePart.Tool, !tool.output.isEmpty else { return part }
            let capped = ChatToolOutputFormatter.cappedToolOutputParts(tool.output, maxChars: maxCharsPerOutput)
            guard capped != tool.output else { return part }
            return UIMessagePart.Tool(
                toolCallId: tool.toolCallId,
                toolName: tool.toolName,
                input: tool.input,
                output: capped,
                approvalState: tool.approvalState,
                streamIndex: tool.streamIndex,
                metadata: tool.metadata
            )
        }
        return UIMessage(
            id: message.id,
            role: message.role,
            parts: parts,
            annotations: message.annotations,
            createdAt: message.createdAt,
            finishedAt: message.finishedAt,
            modelId: message.modelId,
            usage: message.usage,
            translation: message.translation
        )
    }

    /// 截尾不能静默:若 `fitMessagesToTokenBudget` 实际丢弃了消息,在注入侧追加一条
    /// system 说明,让模型知道历史有空洞。受 `maxTokens` 预算约束——预算极端紧张时
    /// 宁可不加注记也不能让请求超窗失败。
    static func appendingTruncationNotice(
        to fitted: [UIMessage],
        originalCount: Int,
        maxTokens: Int
    ) -> [UIMessage] {
        let droppedCount = originalCount - fitted.count
        guard droppedCount > 0 else { return fitted }
        let notice = UIMessage.companion.system(prompt: truncationNoticeText(droppedMessages: droppedCount))
        let withNotice = fitted + [notice]
        guard estimateTokens(withNotice) <= maxTokens else { return fitted }
        return withNotice
    }

    static func truncationNoticeText(droppedMessages: Int) -> String {
        "Context note: \(droppedMessages) older message(s) were omitted from this request to fit the model's token budget. If you need their content, re-run the relevant tool or expand history; the original conversation storage is unchanged."
    }

    static func requestOverheadTokens(
        params: TextGenerationParams,
        promptOverheadTokens: Int = 0
    ) -> Int {
        let toolChars = params.tools.reduce(0) { total, tool in
            total +
                tool.name.count +
                tool.description_.count +
                String(describing: tool.parameters()).count
        }
        let headerChars = params.customHeaders.reduce(0) { total, header in
            total + header.name.count + header.value.count
        }
        let bodyChars = params.customBody.reduce(0) { total, body in
            total + body.key.count + String(describing: body.value).count
        }
        return (toolChars + headerChars + bodyChars) / 4 + max(promptOverheadTokens, 0)
    }

    static func assertFitsRequest(
        messages: [UIMessage],
        overheadTokens: Int,
        forceBudget: Int
    ) throws {
        let estimate = estimateTokens(messages) + max(overheadTokens, 0)
        guard estimate <= forceBudget else {
            throw NSError(
                domain: "AmberAgent.ContextCompaction",
                code: 2,
                userInfo: [
                    NSLocalizedDescriptionKey:
                        "上下文压缩后仍超过模型窗口预算（估算 \(estimate) / \(forceBudget) tokens）。请减少历史、关闭部分工具或换更大上下文模型。"
                ]
            )
        }
    }

    static func trimmingCompactHandoffSystemMessages(in messages: [UIMessage], maxTokens: Int) -> [UIMessage] {
        var result = messages
        for maxChars in [12_000, 6_000, 3_000, 1_500, 800] {
            result = result.map { message in
                guard isCompactHandoffSystemMessage(message),
                      let text = systemText(message),
                      text.count > maxChars else {
                    return message
                }
                return UIMessage.companion.system(prompt: compactHandoffText(text, maxChars: maxChars))
            }
            if estimateTokens(result) <= maxTokens {
                return result
            }
        }
        return result
    }

    static func isCompactHandoffSystemMessage(_ message: UIMessage) -> Bool {
        guard message.role == MessageRole.system,
              let text = systemText(message) else { return false }
        return text.hasPrefix("[Conversation compact handoff:")
    }

    static func systemText(_ message: UIMessage) -> String? {
        let text = message.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined(separator: "\n")
        return text.isEmpty ? nil : text
    }

    static func compactHandoffText(_ text: String, maxChars: Int) -> String {
        let split = text.range(of: "\n\n")
        let header = split.map { String(text[..<$0.lowerBound]) } ?? ""
        let bodyStart = split.map(\.upperBound) ?? text.startIndex
        let body = String(text[bodyStart...])
        let trimmedBody = body.takeMiddle(maxChars: max(maxChars, 200))
        if header.isEmpty {
            return trimmedBody
        }
        return "\(header)\n\n\(trimmedBody)"
    }

    /// 发送前静默裁剪/清空旧消息工具结果的入口。返回处理后的消息,以及被改动过的
    /// 工具结果条数(trim 或 clear 任一生效都算,不重复计数),供 handoff 注入如实
    /// 标注历史空洞。
    static func editPreparedContext(messages: [UIMessage], keepRecentMessages: Int) -> (messages: [UIMessage], removedToolResults: Int) {
        let trimmed = editMessageTools(messages: messages, keepRecentMessages: keepRecentMessages) { trimToolResult($0) }
        let cleared = editMessageTools(messages: trimmed, keepRecentMessages: keepRecentMessages) { clearToolResult($0) }
        var removedToolResults = 0
        for (original, edited) in zip(messages, cleared) {
            for (originalPart, editedPart) in zip(original.parts, edited.parts) {
                if originalPart is UIMessagePart.Tool,
                   editedPart is UIMessagePart.Tool,
                   originalPart != editedPart {
                    removedToolResults += 1
                }
            }
        }
        return (cleared, removedToolResults)
    }

    static func buildCompressionInput(_ messages: [UIMessage]) -> String {
        messages.map { message in
            var lines = [
                "message_id: \(messageId(message))",
                "role: \(message.role.name.lowercased())"
            ]
            lines += message.parts.map(summaryLine)
            return lines.joined(separator: "\n")
        }
        .joined(separator: "\n\n")
    }

    static func buildCompressionPrompt(
        basePrompt: String,
        content: String,
        targetTokens: Int,
        additionalPrompt: String,
        sourceMessageIds: [String],
        coveredCompactIds: [String],
        previousCompactContext: String,
        createdAt: Int64
    ) -> String {
        let coveredIds = coveredCompactIds.map { "\"\($0)\"" }.joined(separator: ", ")
        let sourceIds = sourceMessageIds.map { "\"\($0)\"" }.joined(separator: ", ")
        let structured = """
        Return valid JSON only. Required schema:
        {
          "schema_version": 2,
          "timeline_summary": "4-5 complete human-readable sentences in the user's language for the chat timeline.",
          "handoff_markdown": "Dense Markdown continuation handoff with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files.",
          "covered_compact_ids": [\(coveredIds)],
          "source_message_ids": [\(sourceIds)],
          "created_at": \(createdAt)
        }
        `covered_compact_ids`, `source_message_ids`, and `created_at` must exactly match the values above.
        Preserve concrete names, files, commands, errors, user preferences, rejected approaches, tool outcomes, and unresolved decisions.
        The timeline summary is for the human timeline; the handoff Markdown is what the next model will receive.

        Agent-editable handoff instructions:
        \(defaultHandoffPrompt)

        Previous compact handoffs to carry forward:
        \(previousCompactContext.isEmpty ? "None." : previousCompactContext)
        """
        return basePrompt
            .replacingOccurrences(of: "{content}", with: content)
            .replacingOccurrences(of: "{target_tokens}", with: "\(targetTokens)")
            .replacingOccurrences(of: "{additional_context}", with: [structured, additionalPrompt].filter { !$0.isEmpty }.joined(separator: "\n\n"))
            .replacingOccurrences(of: "{locale}", with: Locale.current.localizedString(forIdentifier: Locale.current.identifier) ?? Locale.current.identifier)
    }

    static func normalizedPayload(
        _ raw: String,
        sourceMessageIds: [String],
        coveredCompactIds: [String],
        createdAt: Int64,
        sourceContent: String,
        carriedHandoffMarkdown: String
    ) -> String {
        let object = parseJSONObject(raw)
        let timeline = coerceTimelineSummary(
            stringValue(object?["timeline_summary"])
                ?? stringValue(object?["display_summary"])
                ?? stringValue(object?["summary"])
                ?? cleanHumanText(raw)
                ?? fallbackTimeline(sourceContent)
        )
        let handoff = cleanMarkdown(
            stringValue(object?["handoff_markdown"])
                ?? plainTextHandoff(
                    timeline: timeline,
                    sourceMessageIds: sourceMessageIds,
                    carriedHandoffMarkdown: carriedHandoffMarkdown
                )
        )
        return jsonString([
            "schema_version": 2,
            "timeline_summary": String(timeline.prefix(maxTimelineSummaryChars)),
            "handoff_markdown": String(handoff.prefix(maxHandoffChars)),
            "covered_compact_ids": Array(Set(coveredCompactIds)).sorted(),
            "source_message_ids": Array(Set(sourceMessageIds)).sorted(),
            "created_at": createdAt
        ])
    }

    static func injectionText(_ compact: IOSConversationCompact, removedToolResults: Int = 0) -> String {
        injectionText(
            id: compact.id,
            summary: compact.summary,
            sourceMessageIds: compact.sourceMessageIds,
            removedToolResults: removedToolResults
        )
    }

    static func injectionText(id: String, summary: String, sourceMessageIds: [String], removedToolResults: Int = 0) -> String {
        let payload = payloadObject(summary)
        let handoff = cleanMarkdown(stringValue(payload?["handoff_markdown"]) ?? timelineSummary(summary) ?? summary)
        let covered = stringList(payload?["covered_compact_ids"])
        var lines = [
            "[Conversation compact handoff: \(id)]",
            "Source message ids: \(sourceMessageIds.joined(separator: ", "))"
        ]
        if !covered.isEmpty {
            lines.append("Covered compact ids: \(covered.joined(separator: ", "))")
        }
        if removedToolResults > 0 {
            lines.append(compactedToolResultsNote(removedToolResults))
        }
        lines.append("")
        lines.append(handoff)
        return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// handoff 注入中的事实说明:有多少条旧消息的工具结果被压缩处理过,模型如需原始
    /// 内容可以重新调用工具(workspace 文件、file search、web search 均可重取)。
    static func compactedToolResultsNote(_ count: Int) -> String {
        "Note: \(count) tool result(s) from older messages were removed or trimmed from this prepared context. If you need their exact original content, re-run the relevant tool — workspace files, file search, and web search can re-fetch it. The original conversation storage is unchanged."
    }

    static func isHighQualityPayload(_ summary: String) -> Bool {
        guard let payload = payloadObject(summary) else { return false }
        let handoff = stringValue(payload["handoff_markdown"]) ?? ""
        let timeline = stringValue(payload["timeline_summary"]) ?? ""
        return handoff.count >= 80 && sentenceCount(timeline) >= 4
    }

    static func timelineSummary(_ summary: String) -> String? {
        guard let payload = payloadObject(summary) else {
            return cleanHumanText(summary)
        }
        return coerceTimelineSummary(
            stringValue(payload["timeline_summary"])
                ?? stringValue(payload["display_summary"])
                ?? stringValue(payload["summary"])
                ?? ""
        )
    }

    static func selectCompactsForInjection(
        activeCompacts: [IOSConversationCompact],
        existingMessageIds: Set<String>
    ) -> [IOSConversationCompact] {
        let completed = validCompletedCompacts(activeCompacts, existingMessageIds: existingMessageIds)
        guard !completed.isEmpty else { return [] }
        guard let latestPayload = completed.reversed().first(where: { compact in
            guard let payload = payloadObject(compact.summary) else { return false }
            return !(stringValue(payload["handoff_markdown"]) ?? "").isEmpty
        }) else {
            return completed
        }
        let byId = Dictionary(uniqueKeysWithValues: completed.map { ($0.id, $0) })
        let covered = transitiveCoveredIds(latestPayload, byId: byId)
        return completed.filter { $0.id != latestPayload.id && !covered.contains($0.id) } + [latestPayload]
    }

    static func validCompletedCompacts(
        _ activeCompacts: [IOSConversationCompact],
        existingMessageIds: Set<String>
    ) -> [IOSConversationCompact] {
        activeCompacts
            .filter {
                $0.status == "completed" &&
                    !$0.sourceMessageIds.isEmpty &&
                    $0.sourceMessageIds.allSatisfy(existingMessageIds.contains)
            }
            .sorted {
                $0.sourceEndIndex == $1.sourceEndIndex
                    ? $0.createdAt < $1.createdAt
                    : $0.sourceEndIndex < $1.sourceEndIndex
            }
    }
}

private extension IOSContextCompactionCoordinator {
    static let defaultHandoffPrompt = """
    # Context Compaction Handoff

    Write a continuation handoff for another model that will resume the same conversation.

    Return valid JSON only. The JSON must include:
    - `schema_version`: 2
    - `timeline_summary`: 4-5 human-readable sentences in the user's language, written for the chat timeline
    - `handoff_markdown`: dense Markdown for the next model, with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files
    - `covered_compact_ids`: the compact ids from the provided previous handoffs that this handoff carries forward
    - `source_message_ids`: exactly the source ids provided for this compact pass
    - `created_at`: the unix epoch millis provided by the app

    The timeline summary is for humans. The handoff Markdown is for the model. Preserve concrete names, files, commands, errors, user preferences, approvals, rejected approaches, and unresolved decisions. Do not include raw tool logs unless they are needed to continue safely.
    """

    static func skipped(reason: String, messages: [UIMessage], modelContextWindowTokens: Int?) -> IOSCompactPlan {
        IOSCompactPlan(
            shouldCompact: false,
            reason: reason,
            estimatedTokens: estimateTokens(messages),
            contextWindowTokens: estimateContextWindow(modelContextWindowTokens),
            sourceStartIndex: 0,
            sourceEndIndex: -1,
            sourceMessageIds: []
        )
    }

    static func estimateAfterCompaction(
        messages: [UIMessage],
        activeCompacts: [IOSConversationCompact],
        plan: IOSCompactPlan,
        maxSummaryTokens: Int
    ) -> Int {
        guard plan.shouldCompact else { return plan.estimatedTokens }
        let existingIds = Set(messages.map(messageId))
        let completed = validCompletedCompacts(activeCompacts, existingMessageIds: existingIds)
        let carried = selectCompactsForInjection(activeCompacts: activeCompacts, existingMessageIds: existingIds)
        let carriedIds = transitiveCompactIds(carried)
        let remainingSummaryMessages = completed
            .filter { !carriedIds.contains($0.id) }
            .map { UIMessage.companion.system(prompt: injectionText($0)) }
        let coveredIds = Set(completed.flatMap(\.sourceMessageIds) + plan.sourceMessageIds)
        let recent = messages.filter { !coveredIds.contains(messageId($0)) }
        return estimateTokens(remainingSummaryMessages) + estimateTokens(recent) + max(maxSummaryTokens, 256)
    }

    static func transitiveCompactIds(_ compacts: [IOSConversationCompact]) -> Set<String> {
        let byId = Dictionary(uniqueKeysWithValues: compacts.map { ($0.id, $0) })
        var seen = Set<String>()
        func visit(_ id: String) {
            guard !seen.contains(id) else { return }
            seen.insert(id)
            guard let compact = byId[id],
                  let payload = payloadObject(compact.summary) else { return }
            stringList(payload["covered_compact_ids"]).forEach(visit)
        }
        compacts.forEach { visit($0.id) }
        return seen
    }

    static func transitiveCoveredIds(
        _ compact: IOSConversationCompact,
        byId: [String: IOSConversationCompact]
    ) -> Set<String> {
        var seen = Set<String>()
        func visit(_ id: String) {
            guard !seen.contains(id) else { return }
            seen.insert(id)
            guard let parent = byId[id],
                  let payload = payloadObject(parent.summary) else { return }
            stringList(payload["covered_compact_ids"]).forEach(visit)
        }
        payloadObject(compact.summary)
            .map { stringList($0["covered_compact_ids"]).forEach(visit) }
        return seen
    }

    static func estimatedChars(_ part: UIMessagePart) -> Int {
        switch part {
        case let text as UIMessagePart.Text:
            return text.text.weightedTokenChars
        case let reasoning as UIMessagePart.Reasoning:
            return reasoning.reasoning.weightedTokenChars
        case let tool as UIMessagePart.Tool:
            return tool.input.weightedTokenChars + tool.output.reduce(0) { $0 + estimatedChars($1) }
        case let document as UIMessagePart.Document:
            return document.fileName.count + 80
        case let miniApp as UIMessagePart.MiniApp:
            return miniApp.title.weightedTokenChars + miniApp.description_.weightedTokenChars + 120
        case is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio:
            return 4_500
        default:
            return String(describing: part).count
        }
    }

    static func summaryLine(_ part: UIMessagePart) -> String {
        switch part {
        case let text as UIMessagePart.Text:
            return "text: \(text.text.takeMiddle(maxChars: 8_000))"
        case let reasoning as UIMessagePart.Reasoning:
            return "reasoning_marker: \(reasoning.reasoning.count) chars"
        case let tool as UIMessagePart.Tool:
            return "tool: \(tool.toolName) id=\(tool.toolCallId) executed=\(!tool.output.isEmpty) input=\(tool.input.takeMiddle(maxChars: 2_000)) output=\(summarizeToolOutput(tool.output))"
        case let image as UIMessagePart.Image:
            return "image: \(String(image.url.suffix(80)))"
        case let video as UIMessagePart.Video:
            return "video: \(String(video.url.suffix(80)))"
        case let audio as UIMessagePart.Audio:
            return "audio: \(String(audio.url.suffix(80)))"
        case let document as UIMessagePart.Document:
            return "document: \(document.fileName) mime=\(document.mime)"
        case let miniApp as UIMessagePart.MiniApp:
            return "mini_app: \(miniApp.title) id=\(miniApp.appId)"
        default:
            return String(describing: part)
        }
    }

    static func summarizeToolOutput(_ parts: [UIMessagePart], maxChars: Int = 8_000) -> String {
        let raw = parts.map { part -> String in
            if let text = part as? UIMessagePart.Text { return text.text }
            if let tool = part as? UIMessagePart.Tool {
                return "nested_tool:\(tool.toolName):\(summarizeToolOutput(tool.output, maxChars: maxChars / 2))"
            }
            return String(describing: part)
        }.joined(separator: "\n")
        return raw.takeMiddle(maxChars: maxChars)
    }

    static func editMessageTools(
        messages: [UIMessage],
        keepRecentMessages: Int,
        transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
    ) -> [UIMessage] {
        messages.enumerated().map { index, message in
            guard index < messages.count - max(keepRecentMessages, 0),
                  !messageHasMultimodalPart(message) else { return message }
            var changed = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard let tool = part as? UIMessagePart.Tool else { return part }
                let next = transform(tool)
                if next != tool { changed = true }
                return next
            }
            guard changed else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt,
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    /// 被压缩处理过的工具输出原位留下的可见占位标记,让模型识别历史有空洞。
    static let compactedToolOutputMarker = "[tool output compacted]"

    static func trimToolResult(_ tool: UIMessagePart.Tool) -> UIMessagePart.Tool {
        guard canEditPreparedResult(tool) else { return tool }
        let outputChars = outputChars(tool.output)
        guard outputChars > 16_000 else { return tool }
        let preview = summarizeToolOutput(tool.output, maxChars: 8_000)
        return replacingToolOutput(tool, text: """
            \(compactedToolOutputMarker) — original result trimmed to a preview.
            \(jsonString([
                "status": "trimmed_tool_result",
                "tool_name": tool.toolName,
                "tool_call_id": tool.toolCallId,
                "original_output_chars": outputChars,
                "preview": preview
            ]))
            """)
    }

    static func clearToolResult(_ tool: UIMessagePart.Tool) -> UIMessagePart.Tool {
        guard canEditPreparedResult(tool),
              safeToClearPreparedResult(tool) else { return tool }
        let outputChars = outputChars(tool.output)
        guard outputChars > 2_000 else { return tool }
        return replacingToolOutput(tool, text: """
            \(compactedToolOutputMarker) — historical result removed from this prepared context.
            \(jsonString([
                "status": "cleared_tool_result",
                "tool_name": tool.toolName,
                "tool_call_id": tool.toolCallId,
                "input_chars": tool.input.count,
                "original_output_chars": outputChars,
                "reason": "Historical result was cleared from prepared context only. Original conversation storage is unchanged; call the tool again or expand history if exact output is needed."
            ]))
            """)
    }

    static func replacingToolOutput(_ tool: UIMessagePart.Tool, text: String) -> UIMessagePart.Tool {
        UIMessagePart.Tool(
            toolCallId: tool.toolCallId,
            toolName: tool.toolName,
            input: tool.input,
            output: [UIMessagePart.Text(text: text, metadata: nil)],
            approvalState: tool.approvalState,
            streamIndex: tool.streamIndex,
            metadata: tool.metadata
        )
    }

    static func canEditPreparedResult(_ tool: UIMessagePart.Tool) -> Bool {
        !tool.output.isEmpty &&
            !partsContainMultimodal(tool.output) &&
            !outputLooksFailedOrDenied(tool.output)
    }

    static func safeToClearPreparedResult(_ tool: UIMessagePart.Tool) -> Bool {
        clearableToolNames.contains(tool.toolName) ||
            tool.toolName.hasPrefix("conversation_") ||
            (tool.toolName.hasPrefix("session_") && !sensitiveSessionTools.contains(tool.toolName))
    }

    static let clearableToolNames: Set<String> = [
        "file_list",
        "file_read",
        "file_search",
        "tools_list",
        "tool_search",
        "tool_policy_explain",
        "conversation_context_status",
        "conversation_search",
        "conversation_expand",
        "agent_runtime_status",
        "agent_task_list",
        "agent_task_read",
        "mcp_list"
    ]

    static let sensitiveSessionTools: Set<String> = ["session_read", "session_expand"]
}

private extension IOSContextCompactionCoordinator {
    static func limitContext(_ messages: [UIMessage], size: Int) -> [UIMessage] {
        guard size > 0, messages.count > size else { return messages }
        let startIndex = messages.count - size
        var adjustedStartIndex = startIndex
        var visited = Set<Int>()
        var needsAdjustment = true

        while needsAdjustment && adjustedStartIndex > 0 {
            needsAdjustment = false
            guard !visited.contains(adjustedStartIndex) else { break }
            visited.insert(adjustedStartIndex)
            let current = messages[adjustedStartIndex]
            if messageHasExecutedTool(current) {
                for index in stride(from: adjustedStartIndex - 1, through: 0, by: -1) {
                    if messageHasPendingTool(messages[index]) {
                        adjustedStartIndex = index
                        needsAdjustment = true
                        break
                    }
                }
            }
            if messageHasPendingTool(current) {
                for index in stride(from: adjustedStartIndex - 1, through: 0, by: -1) {
                    if messages[index].role == MessageRole.user {
                        adjustedStartIndex = index
                        needsAdjustment = true
                        break
                    }
                }
            }
        }
        return Array(messages[adjustedStartIndex..<messages.count])
    }

    static func messageId(_ message: UIMessage) -> String {
        String(describing: message.id)
    }

    static func messageHasExecutedTool(_ message: UIMessage) -> Bool {
        message.parts.contains { ($0 as? UIMessagePart.Tool)?.output.isEmpty == false }
    }

    static func messageHasPendingTool(_ message: UIMessage) -> Bool {
        message.parts.contains { ($0 as? UIMessagePart.Tool)?.output.isEmpty == true }
    }

    static func messageHasMultimodalPart(_ message: UIMessage) -> Bool {
        partsContainMultimodal(message.parts)
    }

    static func partsContainMultimodal(_ parts: [UIMessagePart]) -> Bool {
        parts.contains { part in
            switch part {
            case is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio, is UIMessagePart.Document:
                return true
            case let tool as UIMessagePart.Tool:
                return partsContainMultimodal(tool.output)
            default:
                return false
            }
        }
    }

    static func outputLooksFailedOrDenied(_ parts: [UIMessagePart]) -> Bool {
        parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.contains { text in
            let lower = text.lowercased()
            return lower.contains("\"status\":\"failed\"") ||
                lower.contains("\"status\":\"denied\"") ||
                lower.contains("\"approval_required\"") ||
                lower.contains("\"error\"")
        }
    }

    static func outputChars(_ parts: [UIMessagePart]) -> Int {
        parts.reduce(0) { total, part in
            if let text = part as? UIMessagePart.Text { return total + text.text.count }
            if let reasoning = part as? UIMessagePart.Reasoning { return total + reasoning.reasoning.count }
            if let tool = part as? UIMessagePart.Tool { return total + tool.input.count + outputChars(tool.output) }
            return total + String(describing: part).count
        }
    }

    static func intValue(_ value: KotlinInt?) -> Int? {
        value.map { Int(truncating: $0) }
    }

    static func supportsTextGeneration(_ provider: ProviderSetting) -> Bool {
        provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude
    }

    static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

private extension IOSContextCompactionCoordinator {
    static func parseJSONObject(_ text: String) -> [String: Any]? {
        guard let jsonText = locateJSONObject(text),
              let data = jsonText.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }

    static func payloadObject(_ summary: String) -> [String: Any]? {
        guard let object = parseJSONObject(summary) else { return nil }
        if object["timeline_summary"] != nil ||
            object["handoff_markdown"] != nil ||
            (object["schema_version"] as? Int) == 2 {
            return object
        }
        return nil
    }

    static func locateJSONObject(_ text: String) -> String? {
        let characters = Array(text)
        guard let start = characters.firstIndex(of: "{") else { return nil }
        var depth = 0
        var inString = false
        var escaped = false
        for index in start..<characters.count {
            let char = characters[index]
            if escaped {
                escaped = false
                continue
            }
            if inString && char == "\\" {
                escaped = true
                continue
            }
            if char == "\"" {
                inString.toggle()
                continue
            }
            guard !inString else { continue }
            if char == "{" { depth += 1 }
            if char == "}" {
                depth -= 1
                if depth == 0 {
                    return String(characters[start...index])
                }
            }
        }
        return nil
    }

    static func stringValue(_ value: Any?) -> String? {
        if let string = value as? String {
            return string.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let value {
            return String(describing: value).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    static func stringList(_ value: Any?) -> [String] {
        if let array = value as? [String] { return array }
        if let array = value as? [Any] { return array.compactMap { stringValue($0) } }
        return []
    }

    static func jsonString(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    static func cleanHumanText(_ text: String) -> String? {
        let cleaned = text
            .replacingOccurrences(of: "```json", with: "")
            .replacingOccurrences(of: "```", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, !cleaned.hasPrefix("{") else { return nil }
        return cleaned.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    static func cleanMarkdown(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func coerceTimelineSummary(_ text: String) -> String {
        let cleaned = text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else {
            return "Conversation history was compacted for continuation."
        }
        return String(cleaned.prefix(maxTimelineSummaryChars))
    }

    static func fallbackTimeline(_ sourceContent: String) -> String {
        let preview = sourceContent.takeMiddle(maxChars: 700)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return "Conversation history was compacted. Key source content included: \(preview)"
    }

    static func plainTextHandoff(
        timeline: String,
        sourceMessageIds: [String],
        carriedHandoffMarkdown: String
    ) -> String {
        [
            "# Goal",
            timeline,
            "",
            "# Current State",
            "The earlier conversation segment was compacted. Continue from the preserved timeline and concrete details.",
            "",
            "# Critical Context",
            carriedHandoffMarkdown.isEmpty ? "No previous compact handoff." : carriedHandoffMarkdown,
            "",
            "# Relevant Source Messages",
            sourceMessageIds.joined(separator: ", ")
        ].joined(separator: "\n")
    }

    static func sentenceCount(_ text: String) -> Int {
        let count = text.reduce(0) { partial, char in
            "。！？.!?".contains(char) ? partial + 1 : partial
        }
        return max(count, text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0 : 1)
    }
}

#if DEBUG
@MainActor
enum ChatGenerationRequestPreparationTestSupport {
    static func finalizedUploadMessagesForTesting(
        uploadMessages: [UIMessage],
        maxTokens: Int,
        messagesByInjectingRuntimeContext: ([UIMessage]) -> [UIMessage]
    ) -> [UIMessage] {
        let runtimeInjectedMessages = messagesByInjectingRuntimeContext(uploadMessages)
        let fitted = IOSContextCompactionCoordinator.fitMessagesToTokenBudget(
            runtimeInjectedMessages,
            maxTokens: maxTokens
        )
        return ChatRuntimeContextBuilder.coalescingSystemMessages(fitted)
    }
}
#endif

#if DEBUG
/// G9 契约测试支撑:把 coordinator 的私有静态入口暴露给 iosAppTests,验证占位标记、
/// 移除计数与截尾注记的真实行为(不经过 provider/存储)。
@MainActor
enum ContextCompactionEditTestSupport {
    static func editedMessagesWithCount(
        messages: [UIMessage],
        keepRecentMessages: Int
    ) -> (messages: [UIMessage], removedToolResults: Int) {
        IOSContextCompactionCoordinator.editPreparedContext(
            messages: messages,
            keepRecentMessages: keepRecentMessages
        )
    }

    static func injectedHandoffText(
        id: String,
        summary: String,
        sourceMessageIds: [String],
        removedToolResults: Int
    ) -> String {
        IOSContextCompactionCoordinator.injectionText(
            id: id,
            summary: summary,
            sourceMessageIds: sourceMessageIds,
            removedToolResults: removedToolResults
        )
    }

    static func fittedMessagesWithBudget(messages: [UIMessage], maxTokens: Int) -> [UIMessage] {
        IOSContextCompactionCoordinator.fitMessagesToTokenBudget(messages, maxTokens: maxTokens)
    }

    static let compactedToolOutputMarker = IOSContextCompactionCoordinator.compactedToolOutputMarker
}
#endif

private extension String {
    var weightedTokenChars: Int {
        unicodeScalars.reduce(0) { total, scalar in
            total + (scalar.isCJK ? 4 : 1)
        }
    }

    func takeMiddle(maxChars: Int) -> String {
        guard count > maxChars else { return self }
        let half = max((maxChars - 40), 16) / 2
        return "\(prefix(half))\n... [\(count - half * 2) chars omitted] ...\n\(suffix(half))"
    }
}

private extension UnicodeScalar {
    var isCJK: Bool {
        switch value {
        case 0x4E00...0x9FFF,
             0x3400...0x4DBF,
             0x20000...0x2A6DF,
             0x2A700...0x2B73F,
             0x2B740...0x2B81F,
             0x2B820...0x2CEAF,
             0xF900...0xFAFF,
             0x2F800...0x2FA1F:
            true
        default:
            false
        }
    }
}
