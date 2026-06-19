import SwiftUI
import Observation
@preconcurrency import Shared

struct CouncilChatRuntimeView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: CouncilChatViewModel
    @FocusState private var isComposerFocused: Bool

    init(settingsStore: SettingsStore, sharedSettings: IOSSharedSettingsStore) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self._viewModel = State(initialValue: CouncilChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings
        ))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                modeStrip
                roster
                transcript
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            composer
        }
        .sheet(item: $viewModel.selectedDetail) { detail in
            CouncilDiscussionDetailSheet(detail: detail)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                .presentationCornerRadius(24)
                .presentationBackground(AmberTheme.glassStrong)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack(spacing: 10) {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            VStack(spacing: 2) {
                Text("实时议会")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                HStack(spacing: 5) {
                    Text("主持 · \(viewModel.hostDisplayName)")
                    Text("·")
                    Text(viewModel.roomStateText)
                }
                .font(.system(size: 11.5))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            if viewModel.isRunning {
                AmberGlassCircleButton(systemImage: "stop.fill", accessibilityLabel: "停止议会", size: 44, symbolSize: 15) {
                    viewModel.cancelDiscussion()
                }
            } else {
                AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "议会设置", size: 44, symbolSize: 18) {
                    router.navigate(to: .councilSettings)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private var modeStrip: some View {
        HStack(spacing: 6) {
            ForEach(CouncilDiscussionMode.allCases) { mode in
                Button {
                    viewModel.selectedMode = mode
                } label: {
                    Label(mode.title, systemImage: mode.systemImage)
                        .font(.caption.weight(.semibold))
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(viewModel.selectedMode == mode ? .white : AmberTheme.foreground2)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .frame(height: 34)
                        .background(
                            viewModel.selectedMode == mode ? mode.tint : AmberTheme.surface,
                            in: Capsule()
                        )
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isRunning)
                .accessibilityLabel(mode.accessibilityLabel)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var roster: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(viewModel.participants) { participant in
                    CouncilParticipantChip(
                        participant: participant,
                        state: viewModel.state(for: participant),
                        currentModelId: viewModel.currentModelId
                    ) {
                        viewModel.insertMention(participant)
                        isComposerFocused = true
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
        .padding(.bottom, 6)
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(viewModel.messages) { message in
                        CouncilMessageRow(message: message) {
                            viewModel.showCurrentDetail()
                        }
                        .id(message.id)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .padding(.bottom, 20)
            }
            .scrollIndicators(.hidden)
            .onChange(of: viewModel.messages.count) { _, _ in
                scrollToBottom(proxy)
            }
            .onChange(of: viewModel.lastMessageBody) { _, _ in
                scrollToBottom(proxy)
            }
        }
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            controlStrip

            HStack(alignment: .bottom, spacing: 10) {
                Button {
                    viewModel.insertHostMention()
                    isComposerFocused = true
                } label: {
                    Image(systemName: "at")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(width: 30, height: 30)
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isRunning)

                TextField("发给模型议会...", text: $viewModel.inputText, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.plain)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(minHeight: 38)
                    .focused($isComposerFocused)
                    .disabled(viewModel.isRunning)

                if viewModel.isRunning {
                    Button {
                        viewModel.cancelDiscussion()
                    } label: {
                        Image(systemName: "stop.fill")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 32, height: 32)
                            .background(AmberTheme.accentRed, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("停止议会")
                } else {
                    Button {
                        viewModel.send()
                    } label: {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(viewModel.canSend ? .white : AmberTheme.muted2)
                            .frame(width: 32, height: 32)
                            .background(viewModel.canSend ? AmberTheme.accent : AmberTheme.surface2, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!viewModel.canSend)
                    .accessibilityLabel("发送给议会")
                }
            }
            .padding(.leading, 8)
            .padding(.trailing, 6)
            .padding(.vertical, 6)
            .overlay {
                Capsule()
                    .stroke(AmberTheme.border.opacity(0.58), lineWidth: 0.5)
            }
            .amberGlass(cornerRadius: 25)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background {
            LinearGradient(
                colors: [AmberTheme.background.opacity(0.78), AmberTheme.background],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        }
    }

    private var controlStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                CouncilComposerChip(title: "@主持", systemImage: "crown") {
                    viewModel.insertHostMention()
                    isComposerFocused = true
                }
                CouncilComposerChip(title: "邀请席位", systemImage: "person.badge.plus") {
                    viewModel.insertInviteTemplate()
                    isComposerFocused = true
                }
                CouncilComposerChip(title: "席位回应", systemImage: "arrow.triangle.branch") {
                    viewModel.insertGuestResponseTemplate()
                    isComposerFocused = true
                }
                CouncilComposerChip(title: "主持总结", systemImage: "checkmark.seal") {
                    viewModel.requestSynthesis()
                    isComposerFocused = true
                }
            }
            .padding(.horizontal, 2)
        }
        .disabled(viewModel.isRunning)
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        guard let lastId = viewModel.messages.last?.id else { return }
        withAnimation(.easeOut(duration: 0.2)) {
            proxy.scrollTo(lastId, anchor: .bottom)
        }
    }
}

private struct CouncilParticipantChip: View {
    let participant: CouncilParticipant
    let state: CouncilParticipantState
    let currentModelId: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                ZStack(alignment: .bottomTrailing) {
                    Image(systemName: participant.systemImage)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(participant.tint)
                        .frame(width: 26, height: 26)
                        .background(participant.tint.opacity(0.13), in: Circle())

                    if state == .speaking {
                        Circle()
                            .fill(AmberTheme.accentGreen)
                            .frame(width: 7, height: 7)
                            .overlay(Circle().stroke(AmberTheme.background, lineWidth: 1))
                    }
                }

                VStack(alignment: .leading, spacing: 1) {
                    Text(participant.displayName)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)
                    Text(participant.isHost ? currentModelId : state.label)
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                }
            }
            .padding(.leading, 7)
            .padding(.trailing, 10)
            .frame(height: 42)
            .background(
                state == .speaking ? participant.tint.opacity(0.16) : AmberTheme.surface,
                in: Capsule()
            )
            .overlay {
                Capsule()
                    .stroke(participant.isHost ? AmberTheme.accent.opacity(0.34) : AmberTheme.borderSoft, lineWidth: 0.7)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("提及 \(participant.displayName)")
    }
}

private struct CouncilMessageRow: View {
    let message: CouncilChatMessage
    let onTapDetail: () -> Void

    var body: some View {
        switch message.kind {
        case .divider:
            Text(message.body)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(AmberTheme.surface, in: Capsule())
                .frame(maxWidth: .infinity)
        case .user, .host, .guest, .system:
            row
        }
    }

    private var row: some View {
        HStack(alignment: .top, spacing: 10) {
            if message.kind == .user {
                Spacer(minLength: 42)
            } else {
                avatar
            }

            VStack(alignment: message.kind == .user ? .trailing : .leading, spacing: 5) {
                metaLine
                bubble
            }

            if message.kind == .user {
                avatar
            } else {
                Spacer(minLength: 42)
            }
        }
    }

    private var metaLine: some View {
        HStack(spacing: 6) {
            Text(message.author)
                .font(.caption.weight(.semibold))
                .foregroundStyle(message.kind == .host ? AmberTheme.accent : AmberTheme.muted)
            if let subtitle = message.subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted2)
                    .lineLimit(1)
            }
        }
    }

    private var bubble: some View {
        MarkdownView(markdown: message.displayBody)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(message.backgroundColor, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(message.foregroundColor)
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(message.borderColor, lineWidth: message.kind == .host ? 0.8 : 0.4)
            }
            .onTapGesture(perform: onTapDetail)
    }

    private var avatar: some View {
        Image(systemName: message.systemImage)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(message.tint)
            .frame(width: 30, height: 30)
            .background(message.tint.opacity(0.13), in: Circle())
    }
}

private struct CouncilComposerChip: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(1)
                .padding(.horizontal, 10)
                .frame(height: 30)
                .background(AmberTheme.surface, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct CouncilDiscussionDetailSheet: View {
    let detail: CouncilDiscussionDetail

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("议会详情")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(detail.statusLine)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }

                CouncilDetailGroup(title: "目标", bodyText: detail.objective)
                CouncilDetailGroup(title: "席位", bodyText: detail.participantSummary)
                CouncilDetailGroup(title: "运行", bodyText: detail.budgetSummary)
                CouncilDetailGroup(title: "记录", bodyText: detail.transcript)
            }
            .padding(20)
        }
        .background(AmberTheme.background)
    }
}

private struct CouncilDetailGroup: View {
    let title: String
    let bodyText: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
            Text(bodyText.isEmpty ? "暂无" : bodyText)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
    }
}

@MainActor
@Observable
final class CouncilChatViewModel {
    var inputText = ""
    var selectedMode: CouncilDiscussionMode = .explore
    var messages: [CouncilChatMessage]
    var isRunning = false
    var selectedDetail: CouncilDiscussionDetail?

    let participants: [CouncilParticipant]

    @ObservationIgnored private let settingsStore: SettingsStore
    @ObservationIgnored private let sharedSettings: IOSSharedSettingsStore
    @ObservationIgnored private lazy var provider = OpenAIKmpProvider()
    @ObservationIgnored private let streamJobBox = CouncilStreamJobBox()
    @ObservationIgnored private var discussionTask: Task<Void, Never>?
    @ObservationIgnored private var activeContinuation: CheckedContinuation<String, Never>?
    @ObservationIgnored private var currentObjective = ""

    private var activeSpeakerId: String?
    private var invitedSpeakerIds: Set<String> = []

    init(settingsStore: SettingsStore, sharedSettings: IOSSharedSettingsStore) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.participants = CouncilParticipant.defaults(hostName: Self.hostName(for: settingsStore.modelId))
            + CouncilParticipant.customSeats(from: sharedSettings.savedCouncilSeats)
        self.messages = [
            CouncilChatMessage(
                kind: .system,
                author: "议会",
                body: "模型议会已就绪。输入一个问题后，我会邀请不同视角的席位接力讨论。",
                systemImage: "person.3.sequence",
                tint: AmberTheme.accentIndigo,
                subtitle: "就绪"
            )
        ]
    }

    var canSend: Bool {
        !isRunning && !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var currentModelId: String {
        let trimmed = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "gpt-4o" : trimmed
    }

    var hostDisplayName: String {
        participants.first(where: \.isHost)?.displayName ?? Self.hostName(for: currentModelId)
    }

    var roomStateText: String {
        if isRunning {
            return selectedMode.runningState
        }
        return "就绪"
    }

    var lastMessageBody: String {
        messages.last?.body ?? ""
    }

    func state(for participant: CouncilParticipant) -> CouncilParticipantState {
        if activeSpeakerId == participant.id {
            return .speaking
        }
        if invitedSpeakerIds.contains(participant.id) {
            return .invited
        }
        return .idle
    }

    func insertMention(_ participant: CouncilParticipant) {
        appendToken("@\(participant.handle)")
    }

    func insertHostMention() {
        appendToken("@host")
    }

    func insertInviteTemplate() {
        appendToken("@Gemini")
    }

    func insertGuestResponseTemplate() {
        appendToken("让 guest 互相回应一下")
    }

    func requestSynthesis() {
        selectedMode = .synthesize
        if inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            inputText = "@host 总结当前讨论，给出建议和下一步。"
        }
    }

    func send() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isRunning else { return }

        inputText = ""
        currentObjective = text
        appendMessage(
            kind: .user,
            author: "你",
            body: text,
            systemImage: "person.fill",
            tint: AmberTheme.accent,
            subtitle: nil
        )

        let guests = guestsFor(text: text, mode: selectedMode)
        discussionTask?.cancel()
        discussionTask = Task { [weak self] in
            await self?.runDiscussion(objective: text, guests: guests)
        }
    }

    func cancelDiscussion() {
        discussionTask?.cancel()
        discussionTask = nil
        streamJobBox.job?.cancel(cause: nil)
        streamJobBox.job = nil
        activeContinuation?.resume(returning: "")
        activeContinuation = nil
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        isRunning = false
        appendDivider("已停止")
        appendMessage(
            kind: .system,
            author: "议会",
            body: "本轮议会已停止。",
            systemImage: "stop.circle",
            tint: AmberTheme.accentRed,
            subtitle: "已取消"
        )
        updateDetail(status: "已停止")
    }

    func showCurrentDetail() {
        selectedDetail = selectedDetail ?? makeDetail(status: isRunning ? selectedMode.runningState : "就绪")
    }

    private func runDiscussion(objective: String, guests: [CouncilParticipant]) async {
        isRunning = true
        invitedSpeakerIds = Set(guests.map(\.id))
        selectedDetail = makeDetail(status: selectedMode.runningState)
        appendDivider(selectedMode.openingDivider)

        defer {
            activeSpeakerId = nil
            invitedSpeakerIds.removeAll()
            isRunning = false
            streamJobBox.job = nil
            activeContinuation = nil
            updateDetail(status: "就绪")
        }

        let host = participants.first(where: \.isHost) ?? participants[0]

        if selectedMode != .synthesize {
            let openingId = appendSpeakingMessage(
                speaker: host,
                subtitle: "主持 · \(currentModelId)"
            )
            _ = await generateIntoMessage(
                messageId: openingId,
                speaker: host,
                systemPrompt: hostSystemPrompt,
                userPrompt: hostOpeningPrompt(objective: objective, guests: guests)
            )
        }

        if Task.isCancelled { return }

        for (index, guest) in guests.enumerated() {
            let directive = hostDirective(for: guest, index: index, guests: guests)
            appendMessage(
                kind: .host,
                author: host.displayName,
                body: directive,
                systemImage: host.systemImage,
                tint: host.tint,
                subtitle: "邀请 \(guest.displayName)"
            )

            let messageId = appendSpeakingMessage(
                speaker: guest,
                subtitle: "\(modelLabel(for: guest)) · \(host.displayName) 邀请"
            )
            _ = await generateIntoMessage(
                messageId: messageId,
                speaker: guest,
                systemPrompt: guestSystemPrompt(for: guest),
                userPrompt: guestPrompt(objective: objective, guest: guest, hostDirective: directive)
            )
            if Task.isCancelled { return }
        }

        appendDivider("主持总结")
        let synthesisId = appendSpeakingMessage(
            speaker: host,
            subtitle: "总结 · \(currentModelId)"
        )
        _ = await generateIntoMessage(
            messageId: synthesisId,
            speaker: host,
            systemPrompt: hostSystemPrompt,
            userPrompt: hostSynthesisPrompt(objective: objective)
        )
    }

    private func generateIntoMessage(
        messageId: UUID,
        speaker: CouncilParticipant,
        systemPrompt: String,
        userPrompt: String
    ) async -> String {
        activeSpeakerId = speaker.id
        let result = await streamText(
            systemPrompt: systemPrompt,
            userPrompt: userPrompt,
            modelId: modelId(for: speaker)
        ) { [weak self] text in
            self?.updateMessage(messageId, body: text.isEmpty ? "思考中..." : text, status: .speaking)
        }
        activeContinuation = nil
        streamJobBox.job = nil
        if !Task.isCancelled {
            updateMessage(messageId, body: result.isEmpty ? "没有输出。" : result, status: .completed)
        }
        activeSpeakerId = nil
        updateDetail(status: selectedMode.runningState)
        return result
    }

    private func streamText(
        systemPrompt: String,
        userPrompt: String,
        modelId: String,
        onChunk: @escaping @MainActor (String) -> Void
    ) async -> String {
        let providerSetting = makeProviderSetting()
        let params = makeTextGenerationParams(modelId: modelId)
        let initialMessages = [
            UIMessage.companion.system(prompt: systemPrompt),
            UIMessage.companion.user(prompt: userPrompt)
        ]
        let accumulator = MessageStreamAccumulator(initialMessages: initialMessages, model: params.model)

        return await withCheckedContinuation { continuation in
            var didResume = false

            func resumeOnce(_ value: String) {
                guard !didResume else { return }
                didResume = true
                continuation.resume(returning: value)
            }

            activeContinuation = continuation
            streamJobBox.job = provider.streamTextCancellable(
                providerSetting: providerSetting,
                messages: initialMessages,
                params: params,
                onChunk: { chunk in
                    accumulator.append(chunk: chunk)
                    let text = accumulator.snapshot().last?.toText() ?? ""
                    Task { @MainActor in
                        onChunk(text)
                    }
                },
                onComplete: {
                    let text = accumulator.snapshot().last?.toText() ?? ""
                    Task { @MainActor in
                        resumeOnce(text)
                    }
                },
                onError: { error in
                    Task { @MainActor in
                        resumeOnce("Error: \(error.message ?? String(describing: error))")
                    }
                }
            )
        }
    }

    private func appendToken(_ token: String) {
        if inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            inputText = "\(token) "
        } else if inputText.hasSuffix(" ") {
            inputText += "\(token) "
        } else {
            inputText += " \(token) "
        }
    }

    @discardableResult
    private func appendMessage(
        kind: CouncilMessageKind,
        author: String,
        body: String,
        systemImage: String,
        tint: Color,
        subtitle: String?,
        status: CouncilMessageStatus = .completed
    ) -> UUID {
        let message = CouncilChatMessage(
            kind: kind,
            author: author,
            body: body,
            systemImage: systemImage,
            tint: tint,
            subtitle: subtitle,
            status: status
        )
        messages.append(message)
        updateDetail(status: isRunning ? selectedMode.runningState : "就绪")
        return message.id
    }

    private func appendSpeakingMessage(speaker: CouncilParticipant, subtitle: String) -> UUID {
        appendMessage(
            kind: speaker.isHost ? .host : .guest,
            author: speaker.displayName,
            body: "思考中...",
            systemImage: speaker.systemImage,
            tint: speaker.tint,
            subtitle: subtitle,
            status: .speaking
        )
    }

    private func appendDivider(_ text: String) {
        messages.append(
            CouncilChatMessage(
                kind: .divider,
                author: "议会",
                body: text,
                systemImage: "circle.grid.cross",
                tint: AmberTheme.muted,
                subtitle: nil
            )
        )
    }

    private func updateMessage(_ id: UUID, body: String, status: CouncilMessageStatus) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[index].body = body
        messages[index].status = status
    }

    private func guestsFor(text: String, mode: CouncilDiscussionMode) -> [CouncilParticipant] {
        if mode == .synthesize { return [] }
        let lowercased = text.lowercased()
        let directMentions = participants
            .filter { !$0.isHost && lowercased.contains("@\($0.handle.lowercased())") }
        if !directMentions.isEmpty {
            return Array(directMentions.prefix(3))
        }
        switch mode {
        case .explore:
            return participants.filter { ["deepseek", "glm", "gemini"].contains($0.id) }
        case .debate:
            return participants.filter { ["deepseek", "risk", "opponent"].contains($0.id) }
        case .synthesize:
            return []
        }
    }

    private var hostSystemPrompt: String {
        """
        You are the host Assistant in AmberAgent Council Chat.
        You are not a passive judge. You guide the room, invite temporary Assistants, pass context between them, ask for sharper thinking, and synthesize a higher-quality decision.
        Keep replies concise, concrete, and in Chinese unless the user asks otherwise.
        Make it clear why each guest perspective matters.
        """
    }

    private func guestSystemPrompt(for guest: CouncilParticipant) -> String {
        """
        You are \(guest.displayName), a temporary independent Assistant in AmberAgent Council Chat.
        Role: \(guest.roleDescription)
        You may reference the host and previous guest Assistants, agree, disagree, or continue their points.
        You speak under the host's coordination. Do not take over the room.
        Keep output concise, useful, and evidence-oriented.
        """
    }

    private func hostOpeningPrompt(objective: String, guests: [CouncilParticipant]) -> String {
        """
        User objective:
        \(objective)

        Current mode: \(selectedMode.title)
        Invited guests: \(guests.map(\.displayName).joined(separator: ", "))

        Recent room transcript:
        \(roomTranscript(limit: 10))

        Write a short host message that frames the discussion and explains who you will invite first.
        """
    }

    private func hostDirective(
        for guest: CouncilParticipant,
        index: Int,
        guests: [CouncilParticipant]
    ) -> String {
        if selectedMode == .explore {
            if index == 0 {
                return "\(guest.displayName)，你先从\(guest.shortLens)角度扩展信息面，找出这个问题里容易被忽略的可能性。"
            }
            let previous = guests[index - 1].displayName
            return "\(guest.displayName)，接着 \(previous) 的观点，从\(guest.shortLens)角度补充、扩展或提出不同路径。"
        }
        if index == 0 {
            return "\(guest.displayName)，先从\(guest.shortLens)角度判断这个方向最关键的成立条件。"
        }
        let previous = guests[index - 1].displayName
        return "\(guest.displayName)，请回应 \(previous) 的核心判断，从\(guest.shortLens)角度找盲区、反例和风险。"
    }

    private func guestPrompt(
        objective: String,
        guest: CouncilParticipant,
        hostDirective: String
    ) -> String {
        """
        User objective:
        \(objective)

        Discussion mode:
        \(selectedMode.title) - \(selectedMode.intent)

        Host instruction:
        \(hostDirective)

        Recent room transcript:
        \(roomTranscript(limit: 16))

        Respond as \(guest.displayName). If you build on or disagree with another Assistant, name that Assistant explicitly.
        """
    }

    private func hostSynthesisPrompt(objective: String) -> String {
        """
        User objective:
        \(objective)

        Full recent council transcript:
        \(roomTranscript(limit: 24))

        Produce the host synthesis. Explain what the extra perspectives changed, what decision you recommend, and the next concrete step.
        Keep it chat-native, not a long report.
        """
    }

    private func roomTranscript(limit: Int) -> String {
        messages
            .suffix(limit)
            .filter { !$0.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { "[\($0.author)] \($0.body)" }
            .joined(separator: "\n\n")
    }

    private func makeDetail(status: String) -> CouncilDiscussionDetail {
        CouncilDiscussionDetail(
            statusLine: "\(status) · \(selectedMode.title) · host \(hostDisplayName)",
            objective: currentObjective,
            participantSummary: participants
                .filter { $0.isHost || invitedSpeakerIds.contains($0.id) }
                .map { participant in
                    participant.isHost
                        ? "主持：\(participant.displayName)（\(currentModelId)）"
                        : "\(participant.displayName)：\(participant.roleDescription)（\(modelLabel(for: participant))）"
                }
                .joined(separator: "\n"),
            budgetSummary: "模式：\(selectedMode.title)\n最多席位轮次：3\n提供商：当前 OpenAI-compatible 配置\n主持模型：\(currentModelId)",
            transcript: roomTranscript(limit: 80)
        )
    }

    private func updateDetail(status: String) {
        guard selectedDetail != nil else { return }
        selectedDetail = makeDetail(status: status)
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: settingsStore.apiKey,
            baseUrl: settingsStore.baseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeTextGenerationParams(modelId: String) -> TextGenerationParams {
        let abilities = ModelRegistry.shared.MODEL_ABILITIES.getData(modelId: modelId) as? [ModelAbility] ?? []
        let model = Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: abilities,
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: selectedMode.temperature),
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    private func modelId(for participant: CouncilParticipant) -> String {
        participant.modelId?.trimmedNilIfBlank ?? currentModelId
    }

    private func modelLabel(for participant: CouncilParticipant) -> String {
        participant.modelId?.trimmedNilIfBlank ?? participant.modelHint
    }

    private static func hostName(for modelId: String) -> String {
        let lowercased = modelId.lowercased()
        if lowercased.contains("claude") { return "Claude" }
        if lowercased.contains("deepseek") { return "DeepSeek" }
        if lowercased.contains("gemini") { return "Gemini" }
        if lowercased.contains("glm") { return "GLM" }
        if lowercased.contains("qwen") { return "Qwen" }
        if lowercased.contains("kimi") { return "Kimi" }
        return "GPT"
    }
}

private final class CouncilStreamJobBox {
    var job: Kotlinx_coroutines_coreJob?

    deinit {
        job?.cancel(cause: nil)
    }
}

enum CouncilDiscussionMode: String, CaseIterable, Identifiable {
    case explore
    case debate
    case synthesize

    var id: String { rawValue }

    var title: String {
        switch self {
        case .explore: "探索"
        case .debate: "辩论"
        case .synthesize: "总结"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .explore: "自由群聊模式"
        case .debate: "辩论模式"
        case .synthesize: "主持总结模式"
        }
    }

    var intent: String {
        switch self {
        case .explore: "扩展信息面、发现选项、形成早期方向"
        case .debate: "挑战假设、发现盲区、降低决策风险"
        case .synthesize: "由主持人综合讨论，给出决策与下一步"
        }
    }

    var runningState: String {
        switch self {
        case .explore: "探索中"
        case .debate: "辩论中"
        case .synthesize: "总结中"
        }
    }

    var openingDivider: String {
        switch self {
        case .explore: "探索 · 开场"
        case .debate: "辩论 · 交叉回应"
        case .synthesize: "主持总结"
        }
    }

    var systemImage: String {
        switch self {
        case .explore: "sparkles"
        case .debate: "scale.3d"
        case .synthesize: "checkmark.seal"
        }
    }

    var tint: Color {
        switch self {
        case .explore: AmberTheme.accentCyan
        case .debate: AmberTheme.accentRed
        case .synthesize: AmberTheme.accentGreen
        }
    }

    var temperature: Float {
        switch self {
        case .explore: 0.85
        case .debate: 0.55
        case .synthesize: 0.35
        }
    }
}

struct CouncilParticipant: Identifiable {
    let id: String
    let handle: String
    let displayName: String
    let roleDescription: String
    let shortLens: String
    let systemImage: String
    let tint: Color
    let isHost: Bool
    let modelHint: String
    let modelId: String?

    init(
        id: String,
        handle: String,
        displayName: String,
        roleDescription: String,
        shortLens: String,
        systemImage: String,
        tint: Color,
        isHost: Bool,
        modelHint: String,
        modelId: String? = nil
    ) {
        self.id = id
        self.handle = handle
        self.displayName = displayName
        self.roleDescription = roleDescription
        self.shortLens = shortLens
        self.systemImage = systemImage
        self.tint = tint
        self.isHost = isHost
        self.modelHint = modelHint
        self.modelId = modelId?.trimmedNilIfBlank
    }

    static func defaults(hostName: String) -> [CouncilParticipant] {
        [
            CouncilParticipant(
                id: "host",
                handle: "host",
                displayName: "Host · \(hostName)",
                roleDescription: "主持、串联、追问和综合",
                shortLens: "主持与综合",
                systemImage: "crown",
                tint: AmberTheme.accent,
                isHost: true,
                modelHint: "主模型"
            ),
            CouncilParticipant(
                id: "deepseek",
                handle: "DeepSeek",
                displayName: "DeepSeek",
                roleDescription: "结构化推理、假设拆解和第一性原理分析",
                shortLens: "结构化推理",
                systemImage: "brain.head.profile",
                tint: AmberTheme.accentIndigo,
                isHost: false,
                modelHint: "推理视角"
            ),
            CouncilParticipant(
                id: "glm",
                handle: "GLM",
                displayName: "GLM",
                roleDescription: "中文用户直觉、表达和产品叙事",
                shortLens: "中文用户心智",
                systemImage: "text.bubble",
                tint: AmberTheme.accentGreen,
                isHost: false,
                modelHint: "语言视角"
            ),
            CouncilParticipant(
                id: "gemini",
                handle: "Gemini",
                displayName: "Gemini",
                roleDescription: "多模态、用户体验和移动端交互视角",
                shortLens: "多模态与交互",
                systemImage: "camera.metering.matrix",
                tint: AmberTheme.accentCyan,
                isHost: false,
                modelHint: "多模态视角"
            ),
            CouncilParticipant(
                id: "risk",
                handle: "Risk",
                displayName: "Risk",
                roleDescription: "风险复核、失败模式、隐私、成本、循环和安全边界",
                shortLens: "风险与边界",
                systemImage: "exclamationmark.shield",
                tint: AmberTheme.accentRed,
                isHost: false,
                modelHint: "风险视角"
            ),
            CouncilParticipant(
                id: "opponent",
                handle: "Opponent",
                displayName: "Opponent",
                roleDescription: "反方质询、反例和取舍压力测试",
                shortLens: "反方质询",
                systemImage: "hand.raised",
                tint: AmberTheme.accentAmber,
                isHost: false,
                modelHint: "质询视角"
            )
        ]
    }

    static func customSeats(from savedSeats: [[String: String]]) -> [CouncilParticipant] {
        let palette = [
            AmberTheme.accentCyan,
            AmberTheme.accentGreen,
            AmberTheme.accentAmber,
            AmberTheme.accentIndigo,
            AmberTheme.accentRed
        ]

        return savedSeats.enumerated().compactMap { index, seat in
            guard let name = seat["name"]?.trimmedNilIfBlank else { return nil }
            let role = seat["role"]?.trimmedNilIfBlank ?? "自定义视角"
            let modelId = seat["modelId"]?.trimmedNilIfBlank
            return CouncilParticipant(
                id: seat["seatId"]?.trimmedNilIfBlank ?? "custom-\(index)",
                handle: makeHandle(from: name, fallback: "seat\(index + 1)"),
                displayName: name,
                roleDescription: role,
                shortLens: role,
                systemImage: "person.crop.circle.badge.checkmark",
                tint: palette[index % palette.count],
                isHost: false,
                modelHint: modelId ?? "当前模型",
                modelId: modelId
            )
        }
    }

    private static func makeHandle(from name: String, fallback: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let withoutSpaces = trimmed.filter { !$0.isWhitespace }
        return withoutSpaces.isEmpty ? fallback : withoutSpaces
    }
}

enum CouncilParticipantState: String {
    case idle
    case invited
    case speaking

    var label: String {
        switch self {
        case .idle: "待命"
        case .invited: "已邀请"
        case .speaking: "发言中"
        }
    }
}

struct CouncilChatMessage: Identifiable {
    let id: UUID
    let kind: CouncilMessageKind
    let author: String
    var body: String
    let systemImage: String
    let tint: Color
    let subtitle: String?
    var status: CouncilMessageStatus

    init(
        id: UUID = UUID(),
        kind: CouncilMessageKind,
        author: String,
        body: String,
        systemImage: String,
        tint: Color,
        subtitle: String?,
        status: CouncilMessageStatus = .completed
    ) {
        self.id = id
        self.kind = kind
        self.author = author
        self.body = body
        self.systemImage = systemImage
        self.tint = tint
        self.subtitle = subtitle
        self.status = status
    }

    var displayBody: String {
        if status == .speaking && body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "思考中..."
        }
        return body
    }

    var backgroundColor: Color {
        switch kind {
        case .user: AmberTheme.accent
        case .host: AmberTheme.surface
        case .guest: Color.white.opacity(0.56)
        case .system: AmberTheme.surface2.opacity(0.65)
        case .divider: .clear
        }
    }

    var foregroundColor: Color {
        kind == .user ? .white : AmberTheme.foreground
    }

    var borderColor: Color {
        switch kind {
        case .host: AmberTheme.accent.opacity(0.24)
        case .guest: AmberTheme.borderSoft
        case .system: AmberTheme.borderSoft
        default: .clear
        }
    }
}

enum CouncilMessageKind {
    case user
    case host
    case guest
    case system
    case divider
}

enum CouncilMessageStatus {
    case speaking
    case completed
}

struct CouncilDiscussionDetail: Identifiable {
    let id = UUID()
    let statusLine: String
    let objective: String
    let participantSummary: String
    let budgetSummary: String
    let transcript: String
}

private extension String {
    var trimmedNilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

#Preview {
    NavigationStack {
        CouncilChatRuntimeView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
