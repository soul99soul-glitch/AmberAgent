import SwiftUI
import Shared

struct CouncilSettingsView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let providerRegistry: ProviderRegistryStore?

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var roomSettingsStore = IOSCouncilRoomSettingsStore.shared
    @State private var connectivityState: CouncilModelConnectivityViewState = .idle
    @State private var connectivityTask: Task<Void, Never>?

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        hostSection
                        connectivitySection
                        limitsSection
                        seatDraftSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            roomSettingsStore.bootstrapLegacySeatsIfNeeded(sharedSettings.savedCouncilSeats, currentModelId: currentModelId)
        }
        .onDisappear {
            connectivityTask?.cancel()
            connectivityTask = nil
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("模型议会")
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

    private var intro: some View {
        Text("配置主持人、默认席位和议会运行限制。模型议会使用当前激活的 OpenAI-compatible 服务商，不混用其他服务商密钥。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var hostSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "主持人")
            AmberFormGroup {
                modelMenu(
                    title: "主持模型",
                    value: roomSettingsStore.settings.host.modelId,
                    setValue: { roomSettingsStore.updateHost(modelId: $0) }
                )
                CouncilSettingsDivider()
                reasoningMenu(
                    title: "思考档位",
                    value: roomSettingsStore.settings.host.reasoning,
                    setValue: { roomSettingsStore.updateHost(reasoning: $0) }
                )
                CouncilSettingsDivider()
                TextField("主持人提示词", text: Binding(
                    get: { roomSettingsStore.settings.host.prompt },
                    set: { roomSettingsStore.updateHost(prompt: $0) }
                ), axis: .vertical)
                .lineLimit(2...5)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            CouncilFootnote(text: "主持人会先进行本轮调研和议题完善，再按顺序拉起席位。")
        }
    }

    private var connectivitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模型连通性")
            AmberFormGroup {
                Button(action: testCurrentCouncilModels) {
                    CouncilSettingsRow(
                        systemImage: "antenna.radiowaves.left.and.right",
                        title: connectivityState.isTesting ? "正在测试当前议会模型" : "测试当前议会模型",
                        trailing: connectivityState.summary
                    )
                }
                .buttonStyle(.plain)
                .disabled(connectivityState.isTesting)

                switch connectivityState {
                case .idle, .testing:
                    EmptyView()
                case .failure(let message):
                    CouncilSettingsDivider()
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                case .results(let results):
                    ForEach(results) { result in
                        CouncilSettingsDivider()
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: result.isReachable ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundStyle(result.isReachable ? Color.green : AmberTheme.accentRed)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(result.configuredModelId)
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(AmberTheme.foreground)
                                Text(result.detail)
                                    .font(.caption)
                                    .foregroundStyle(result.isReachable ? AmberTheme.muted : AmberTheme.accentRed)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                    }
                }
            }
            CouncilFootnote(text: "只测试当前议会会实际调用的模型；每个实际模型发送一次极短生成请求，可能产生少量额度消耗。")
        }
    }

    private var limitsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "功能限制")
            AmberFormGroup {
                Stepper(
                    "最大席位 \(roomSettingsStore.settings.limits.maxSeats)",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.maxSeats },
                        set: { roomSettingsStore.updateLimits(maxSeats: $0) }
                    ),
                    in: 2...8
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                CouncilSettingsDivider()
                Stepper(
                    "默认轮数 \(roomSettingsStore.settings.limits.defaultRounds)",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.defaultRounds },
                        set: { roomSettingsStore.updateLimits(defaultRounds: $0) }
                    ),
                    in: 1...5
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                CouncilSettingsDivider()
                Stepper(
                    "单次模型无输出超时 \(roomSettingsStore.settings.limits.seatTimeoutSeconds)s",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.seatTimeoutSeconds },
                        set: { roomSettingsStore.updateLimits(seatTimeoutSeconds: $0) }
                    ),
                    in: 15...180,
                    step: 15
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                CouncilSettingsDivider()
                Stepper(
                    "输出预算 \(roomSettingsStore.settings.limits.outputBudgetCharacters)",
                    value: Binding(
                        get: { roomSettingsStore.settings.limits.outputBudgetCharacters },
                        set: { roomSettingsStore.updateLimits(outputBudgetCharacters: $0) }
                    ),
                    in: 2_000...40_000,
                    step: 2_000
                )
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
            CouncilFootnote(text: "自由群聊与辩论都使用默认轮数；追问每次追加一轮。")
        }
    }

    private var seatDraftSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "默认席位")
            AmberFormGroup {
                let seats = roomSettingsStore.settings.seats
                if seats.isEmpty {
                    Text("暂无席位。点「添加席位」进入编辑页添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(seats.enumerated()), id: \.offset) { index, seat in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(seat.name).font(.body.weight(.semibold))
                                Text("\(seat.modelId.trimmedOr(currentModelId)) · \(seat.reasoning.title)")
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.muted2)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { seat.isDefault },
                                set: { _ in roomSettingsStore.toggleDefaultSeat(id: seat.id) }
                            ))
                            .labelsHidden()
                            Button { roomSettingsStore.removeSeat(id: seat.id) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 44).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < seats.count - 1 { CouncilSettingsDivider() }
                    }
                }
            }
            Button {
                router.navigate(to: .seatEditor)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus").font(.system(size: 17, weight: .semibold)).foregroundStyle(AmberTheme.accent).frame(width: 30, height: 30)
                    Text("添加席位").font(.body.weight(.medium)).foregroundStyle(AmberTheme.accent).frame(maxWidth: .infinity, alignment: .leading)
                }.frame(minHeight: 56).padding(.horizontal, 14).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            CouncilFootnote(text: "席位配置会保存在本机，并作为实时议会 Room 的执行输入。")
        }
    }

    private var currentModelId: String {
        sharedSettings.resolveCurrentModelId()
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmedOr("gpt-4o")
    }

    private var availableModelIds: [String] {
        var seen = Set<String>()
        var ids: [String] = []
        for model in sharedSettings.resolveCurrentProviderSetting()?.models ?? []
            where model.type == ModelType.chat {
            let modelId = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !modelId.isEmpty, seen.insert(modelId).inserted else { continue }
            ids.append(modelId)
        }
        if seen.insert(currentModelId).inserted {
            ids.insert(currentModelId, at: 0)
        }
        return ids
    }

    private func modelMenu(title: String, value: String, setValue: @escaping (String) -> Void) -> some View {
        Menu {
            ForEach(availableModelIds, id: \.self) { modelId in
                Button(modelId) { setValue(modelId) }
            }
        } label: {
            CouncilSettingsRow(systemImage: "cpu", title: title, trailing: value.trimmedOr(currentModelId))
        }
        .buttonStyle(.plain)
    }

    private func reasoningMenu(title: String, value: IOSCouncilReasoningPreset, setValue: @escaping (IOSCouncilReasoningPreset) -> Void) -> some View {
        Menu {
            ForEach(IOSCouncilReasoningPreset.allCases) { option in
                Button(option.title) { setValue(option) }
            }
        } label: {
            CouncilSettingsRow(systemImage: "brain.head.profile", title: title, trailing: value.title)
        }
        .buttonStyle(.plain)
    }

    private func testCurrentCouncilModels() {
        connectivityTask?.cancel()
        guard let provider = sharedSettings.resolveCurrentProviderSetting() else {
            connectivityState = .failure("当前没有可用的聊天服务商或模型。")
            return
        }
        let settings = roomSettingsStore.settings
        let dynamicSeatGeneration = roomSettingsStore.dynamicSeatGeneration
        let modelId = currentModelId
        connectivityState = .testing
        connectivityTask = Task { @MainActor in
            let tester = IOSCouncilModelConnectivityTester()
            do {
                let results = try await tester.test(
                    settings: settings,
                    dynamicSeatGeneration: dynamicSeatGeneration,
                    providerSetting: provider,
                    currentModelId: modelId
                )
                guard !Task.isCancelled else { return }
                connectivityState = .results(results)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                connectivityState = .failure(error.localizedDescription)
            }
            connectivityTask = nil
        }
    }

}

enum CouncilModelConnectivityViewState {
    case idle
    case testing
    case results([IOSCouncilModelConnectivityResult])
    case failure(String)

    var isTesting: Bool {
        if case .testing = self { return true }
        return false
    }

    var summary: String {
        switch self {
        case .idle: "未测试"
        case .testing: "测试中"
        case .results(let results): "\(results.filter(\.isReachable).count)/\(results.count) 可用"
        case .failure: "失败"
        }
    }
}

struct IOSCouncilModelConnectivityResult: Identifiable, Equatable {
    let configuredModelId: String
    let effectiveModelId: String
    let errorMessage: String?

    var id: String { configuredModelId }
    var isReachable: Bool { errorMessage == nil }

    var detail: String {
        let route = configuredModelId == effectiveModelId
            ? effectiveModelId
            : "\(effectiveModelId)（实际回退）"
        if let errorMessage {
            return "\(route) · \(errorMessage)"
        }
        return "\(route) · 可用"
    }
}

enum IOSCouncilModelConnectivityError: LocalizedError, Equatable {
    case currentModelUnavailable(String)
    case timedOut(String)
    case emptyOutput(String)

    var errorDescription: String? {
        switch self {
        case .currentModelUnavailable(let modelId): "当前服务商中找不到当前模型 \(modelId)。"
        case .timedOut(let modelId): "\(modelId) 在 20 秒内没有完成测试。"
        case .emptyOutput(let modelId): "\(modelId) 返回了空内容。"
        }
    }
}

@MainActor
final class IOSCouncilModelConnectivityTester {
    private let streamer: any IOSCouncilTextStreaming
    private let timeoutNanoseconds: UInt64

    init(
        streamer: any IOSCouncilTextStreaming = IOSCouncilTextStreamer(),
        timeoutNanoseconds: UInt64 = 20_000_000_000
    ) {
        self.streamer = streamer
        self.timeoutNanoseconds = timeoutNanoseconds
    }

    func test(
        settings: IOSCouncilRoomSettings,
        dynamicSeatGeneration: Bool,
        providerSetting: ProviderSetting,
        currentModelId: String
    ) async throws -> [IOSCouncilModelConnectivityResult] {
        let chatModels = providerSetting.models.filter { $0.type == ModelType.chat }
        guard let currentModel = chatModels.first(where: { $0.modelId == currentModelId }) else {
            throw IOSCouncilModelConnectivityError.currentModelUnavailable(currentModelId)
        }
        let supportedIds = Set(chatModels.map(\.modelId))
        let normalized = settings.normalized(currentModelId: currentModelId)
        let configuredIds = Self.configuredModelIds(
            settings: normalized,
            dynamicSeatGeneration: dynamicSeatGeneration,
            currentModelId: currentModelId
        )
        var outcomes: [String: String?] = [:]
        var effectiveModels: [String: Model] = [:]
        for configuredId in configuredIds {
            let effectiveId = supportedIds.contains(configuredId) ? configuredId : currentModelId
            effectiveModels[effectiveId] = chatModels.first(where: { $0.modelId == effectiveId }) ?? currentModel
        }

        for effectiveId in effectiveModels.keys.sorted() {
            try Task.checkCancellation()
            guard let model = effectiveModels[effectiveId] else { continue }
            if let issue = ChatProviderConfiguration.issue(for: model, provider: providerSetting) {
                outcomes[effectiveId] = issue.message
                continue
            }
            do {
                let output = try await probe(model: model, providerSetting: providerSetting)
                outcomes[effectiveId] = output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ? IOSCouncilModelConnectivityError.emptyOutput(effectiveId).localizedDescription
                    : nil
            } catch is CancellationError {
                streamer.cancel()
                throw CancellationError()
            } catch {
                outcomes[effectiveId] = error.localizedDescription
            }
        }

        return configuredIds.map { configuredId in
            let effectiveId = supportedIds.contains(configuredId) ? configuredId : currentModelId
            return IOSCouncilModelConnectivityResult(
                configuredModelId: configuredId,
                effectiveModelId: effectiveId,
                errorMessage: outcomes[effectiveId] ?? nil
            )
        }
    }

    static func configuredModelIds(
        settings: IOSCouncilRoomSettings,
        dynamicSeatGeneration: Bool,
        currentModelId: String
    ) -> [String] {
        let seats = dynamicSeatGeneration ? [] : settings.defaultSeats(currentModelId: currentModelId)
        var seen = Set<String>()
        return ([settings.host.modelId] + seats.map(\.modelId)).compactMap { rawId in
            let modelId = rawId.trimmingCharacters(in: .whitespacesAndNewlines).trimmedOr(currentModelId)
            return seen.insert(modelId).inserted ? modelId : nil
        }
    }

    private func probe(model: Model, providerSetting: ProviderSetting) async throws -> String {
        let params = TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0),
            topP: nil,
            maxTokens: KotlinInt(value: 16),
            tools: [],
            reasoningLevel: .off,
            customHeaders: model.customHeaders,
            customBody: model.customBodies
        )
        let messages = [UIMessage.companion.user(prompt: "只回复 OK")]
        let timeoutNanoseconds = timeoutNanoseconds
        let modelId = model.modelId
        let operation = IOSCouncilModelProbeOperation(
            streamer: streamer,
            providerSetting: providerSetting,
            messages: messages,
            params: params
        )
        do {
            return try await withThrowingTaskGroup(of: String.self) { group in
                group.addTask {
                    try await operation.run()
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: timeoutNanoseconds)
                    throw IOSCouncilModelConnectivityError.timedOut(modelId)
                }
                do {
                    guard let result = try await group.next() else {
                        throw IOSCouncilModelConnectivityError.timedOut(modelId)
                    }
                    group.cancelAll()
                    return result
                } catch {
                    group.cancelAll()
                    operation.cancel()
                    throw error
                }
            }
        } catch {
            streamer.cancel()
            throw error
        }
    }
}

@MainActor
private final class IOSCouncilModelProbeOperation {
    private let streamer: any IOSCouncilTextStreaming
    private let providerSetting: ProviderSetting
    private let messages: [UIMessage]
    private let params: TextGenerationParams

    init(
        streamer: any IOSCouncilTextStreaming,
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) {
        self.streamer = streamer
        self.providerSetting = providerSetting
        self.messages = messages
        self.params = params
    }

    func run() async throws -> String {
        try await streamer.streamText(
            providerSetting: providerSetting,
            messages: messages,
            params: params,
            onUpdate: { _, _ in }
        )
    }

    func cancel() {
        streamer.cancel()
    }
}

private struct CouncilSettingsRow: View {
    let systemImage: String
    let title: String
    let trailing: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 32, height: 32)
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
            Spacer()
            Text(trailing)
                .font(.caption.weight(.medium))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .contentShape(Rectangle())
    }
}

private struct CouncilSettingsDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct CouncilFootnote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private extension String {
    func trimmedOr(_ fallback: String) -> String {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }
}

#Preview {
    NavigationStack {
        CouncilSettingsView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
