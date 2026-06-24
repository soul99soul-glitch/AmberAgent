import SwiftUI
import Observation
import SwiftStreamingMarkdown
@preconcurrency import Shared

struct CouncilChatRuntimeView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let providerRegistry: ProviderRegistryStore?
    let permissionStore: IOSPermissionStore

    @Environment(\.dismiss) private var dismiss
    @State private var viewModel: CouncilChatViewModel
    @FocusState private var isComposerFocused: Bool
    /// 用户是否停在底部附近。流式/新消息只在底部附近时才自动跟随;一旦上滑看历史就不再强制拉回。
    @State private var isNearBottom = true

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore? = nil,
        permissionStore: IOSPermissionStore = IOSPermissionStore()
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
        self.permissionStore = permissionStore
        self._viewModel = State(initialValue: CouncilChatViewModel(
            settingsStore: settingsStore,
            sharedSettings: sharedSettings,
            providerRegistry: providerRegistry,
            permissionStore: permissionStore
        ))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                transcript
            }
        }
        // 离开页面(返回/切走)时立即存一份当前 transcript,避免运行中退出导致刚流式出的内容
        // 在下次进入前丢失(运行任务仍会在后台跑完并再存一次,以更完整的为准)。
        .onDisappear { viewModel.persistTranscript() }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            composer
        }
        .sheet(item: $viewModel.activeSheet) { sheet in
            switch sheet {
            case .detail(let detail):
                CouncilDiscussionDetailSheet(detail: detail)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            case .members:
                CouncilMembersSheet(
                    selectedMode: Binding(
                        get: { viewModel.selectedMode },
                        set: { viewModel.selectedMode = $0 }
                    ),
                    participants: viewModel.participants,
                    currentModelId: viewModel.currentModelId,
                    stateProvider: { viewModel.state(for: $0) },
                    failedSpeakerIds: viewModel.failedSpeakerIds,
                    isRunning: viewModel.isRunning,
                    restartAction: { viewModel.restartLastDiscussion() }
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                // 固定 sheet 底色,避免拉伸到不同高度时系统在不同材质/灰阶间切换导致整体变色。
                .presentationBackground(AmberTheme.background)
            case .settings:
                CouncilRoomSettingsSheet(
                    roomSettingsStore: viewModel.roomSettingsStore,
                    availableModelIds: viewModel.availableModelIds,
                    currentModelId: viewModel.currentModelId,
                    isReadOnly: viewModel.isRunning
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $viewModel.pendingAskUser) { state in
            askUserSheet(state)
        }
    }

    /// 主持人提问 sheet：用户输入回答或跳过，恢复议会。
    private func askUserSheet(_ state: IOSCouncilAskUserState) -> some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Label("主持人提问", systemImage: "questionmark.bubble")
                    .font(.headline)
                    .foregroundStyle(AmberTheme.accent)

                Text(state.question)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .fixedSize(horizontal: false, vertical: true)

                TextEditor(text: Binding(
                    get: { viewModel.pendingAskUser?.answerDraft ?? "" },
                    set: { viewModel.pendingAskUser?.answerDraft = $0 }
                ))
                .font(.body)
                .frame(minHeight: 80)
                .padding(8)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 10))
            }
            .padding(16)
            .navigationTitle("议会暂停")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("跳过") { viewModel.skipAskUser() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("回答") { viewModel.submitAskUserAnswer() }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(true)
    }

    private var header: some View {
        AmberGlassGroup(spacing: 16) {
            HStack(spacing: 10) {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                    dismiss()
                }

                // 整个标题区(模式 + 成员·轮次)都可点击,唤起底部 sheet 切换模式 / 看席位。
                // 不再用悬浮小菜单,点击热区也更大。
                Button {
                    viewModel.showMembers()
                } label: {
                    VStack(spacing: 2) {
                        HStack(spacing: 4) {
                            Text(viewModel.selectedMode.title)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(AmberTheme.muted)
                        }
                        Text("\(viewModel.participants.count) 位成员 · 第 \(viewModel.discussionRound) 轮")
                            .font(.system(size: 11.5))
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("议会模式与席位")

                // 顶栏始终是设置入口;停止由输入框那颗发送/停止键负责(与 Chat 页一致),
                // 不在顶栏再放一颗多余的停止键。
                AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "议会设置", size: 44, symbolSize: 18) {
                    viewModel.showSettings()
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
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
                        // 新气泡 / 小胶囊淡入 + 轻微上浮放大,避免突兀地"啪"地出现。
                        .transition(.asymmetric(
                            insertion: .opacity.combined(with: .move(edge: .bottom)).combined(with: .scale(scale: 0.97, anchor: .bottom)),
                            removal: .opacity
                        ))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .padding(.bottom, 20)
                // 用消息数量驱动插入过渡;正文流式更新(count 不变)不触发,避免逐字抖动。
                .animation(.spring(response: 0.4, dampingFraction: 0.82), value: viewModel.messages.count)
            }
            .scrollIndicators(.hidden)
            // 跟踪是否在底部附近(距底 120pt 内)。上滑看历史时 isNearBottom=false → 不再强制跟随。
            .onScrollGeometryChange(for: Bool.self) { geo in
                geo.contentOffset.y + geo.containerSize.height >= geo.contentSize.height - 120
            } action: { _, atBottom in
                isNearBottom = atBottom
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                guard isNearBottom else { return }
                scrollToBottom(proxy)
            }
            .onChange(of: viewModel.lastMessageBody) { _, _ in
                // 流式跟随用非动画的即时定位,避免每个 token 触发一次 0.2s 滚动动画造成卡顿。
                guard isNearBottom else { return }
                scrollToBottom(proxy, animated: false)
            }
        }
    }

    // 输入条与 Chat 页保持完全一致:复用 Chat 的原生 Liquid Glass 组件
    // —— 左侧输入胶囊(`composerDockGlass`)+ 右侧分离的圆形发送键
    // (`ComposerDockSendButton`)。胶囊内左侧 `@` 键沿用 Chat 的「+」位,
    // 与 Chat 一致的原生输入胶囊 + 分离圆形发送键。去掉了原来无实际作用的 @ 键
    // （runner 并不解析 @host 提及）和此前黏连成一团的玻璃操作 chips。
    private var composer: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField("输入议题开始", text: $viewModel.inputText, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(minHeight: 40)
                .focused($isComposerFocused)
                .disabled(viewModel.isRunning)
                .padding(.leading, 18)
                .padding(.trailing, 18)
                .padding(.vertical, 7)
                .composerDockGlass(cornerRadius: 27)

            ComposerDockSendButton(
                isLoading: viewModel.isRunning,
                sendEnabled: viewModel.canSend,
                diameter: 54,
                onSend: { viewModel.send() },
                onStop: { viewModel.cancelDiscussion() }
            )
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

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        guard let lastId = viewModel.messages.last?.id else { return }
        if animated {
            withAnimation(.easeOut(duration: 0.2)) {
                proxy.scrollTo(lastId, anchor: .bottom)
            }
        } else {
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
        case .user:
            userRow
        case .host, .guest, .system:
            assistantRow
        }
    }

    // 用户消息复用 Chat 页样式:右对齐的 accent 气泡(ChatUserBubble),无头像、无「你」标签。
    private var userRow: some View {
        HStack {
            Spacer(minLength: 48)
            ChatUserBubble(text: message.body)
        }
    }

    // Agent 消息:头像在左,气泡左缘对齐头像右缘;气泡右边距 = 左边距
    // (头像 30 + 间距 10 = 40),整体左右对称,气泡不再占满整行。
    private var assistantRow: some View {
        HStack(alignment: .top, spacing: 10) {
            avatar
            VStack(alignment: .leading, spacing: 5) {
                metaLine
                bubble
            }
        }
        .padding(.trailing, 40)
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
        // 复用 Chat 同款的微软流式 Markdown 渲染库(增量渲染,流式不再每个 token 全量重解析
        // AST,卡顿大幅缓解);旧的 MarkdownView 会在每次 body 变化时重跑 MarkdownBridge.parse。
        SwiftStreamingMarkdown.MarkdownView(text: message.displayBody)
            .frame(maxWidth: .infinity, alignment: .leading)
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

private struct CouncilMembersSheet: View {
    @Binding var selectedMode: CouncilDiscussionMode
    let participants: [CouncilParticipant]
    let currentModelId: String
    let stateProvider: (CouncilParticipant) -> CouncilParticipantState
    let failedSpeakerIds: Set<String>
    let isRunning: Bool
    let restartAction: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("讨论模式", selection: $selectedMode) {
                        ForEach(CouncilDiscussionMode.allCases) { mode in
                            Text(mode.title).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .disabled(isRunning)
                    .listRowBackground(AmberTheme.surface)
                } header: {
                    Text("模式")
                } footer: {
                    Text(selectedMode.intent)
                }

                Section {
                    ForEach(participants) { participant in
                        HStack(spacing: 12) {
                            Image(systemName: participant.systemImage)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(participant.tint)
                                .frame(width: 32, height: 32)
                                .background(participant.tint.opacity(0.13), in: Circle())

                            VStack(alignment: .leading, spacing: 3) {
                                Text(participant.displayName)
                                    .font(.body.weight(.semibold))
                                Text(participant.isHost ? "主持 · \(currentModelId)" : participant.roleDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            Text(failedSpeakerIds.contains(participant.id) ? "失败" : stateProvider(participant).label)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(failedSpeakerIds.contains(participant.id) ? Color.red : .secondary)
                        }
                        .padding(.vertical, 2)
                        .listRowBackground(AmberTheme.surface)
                    }
                } header: {
                    Text("席位成员（\(participants.count)）")
                } footer: {
                    Text(isRunning
                         ? "本轮议会运行中，模式与席位下一轮生效。"
                         : "席位由主持人按议题联网调研后动态组建。")
                }
            }
            // 隐藏系统 grouped 背景(那层会随 sheet 拉伸在冷灰之间切换,与暖色主题冲突),
            // 统一用主题底色,颜色不再随高度变化。
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("模型议会")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        restartAction()
                        dismiss()
                    } label: {
                        // 明确显示「重新开始」文字 + 图标(toolbar 里 Label 默认只显示图标)。
                        Label("重新开始", systemImage: "arrow.counterclockwise")
                            .labelStyle(.titleAndIcon)
                    }
                    .disabled(isRunning)
                }
            }
        }
    }
}

private struct CouncilRoomSettingsSheet: View {
    @Bindable var roomSettingsStore: IOSCouncilRoomSettingsStore
    let availableModelIds: [String]
    let currentModelId: String
    let isReadOnly: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("议会设置")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(isReadOnly ? "运行中只读，下一轮生效。" : "设置会保存到本机，下一轮议会使用。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }

                settingsGroup(title: "主持人") {
                    modelMenu(
                        title: "主持模型",
                        value: roomSettingsStore.settings.host.modelId,
                        setValue: { roomSettingsStore.updateHost(modelId: $0) }
                    )
                    Divider().overlay(AmberTheme.borderSoft)
                    reasoningMenu(
                        title: "思考档位",
                        value: roomSettingsStore.settings.host.reasoning,
                        setValue: { roomSettingsStore.updateHost(reasoning: $0) }
                    )
                    Divider().overlay(AmberTheme.borderSoft)
                    TextField("主持人提示词", text: Binding(
                        get: { roomSettingsStore.settings.host.prompt },
                        set: { roomSettingsStore.updateHost(prompt: $0) }
                    ), axis: .vertical)
                    .lineLimit(2...5)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.foreground)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .disabled(isReadOnly)
                }

                settingsGroup(title: "动态席位生成") {
                    Toggle(isOn: Binding(
                        get: { roomSettingsStore.dynamicSeatGeneration },
                        set: { roomSettingsStore.dynamicSeatGeneration = $0 }
                    )) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("主持人动态生成席位")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                            Text(roomSettingsStore.dynamicSeatGeneration
                                 ? "开：主持人按议题联网调研后自由组建席位。"
                                 : "关：只在下方你添加的席位中选择角色。")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .tint(AmberTheme.accent)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                    .disabled(isReadOnly)
                }

                settingsGroup(title: "席位（\(roomSettingsStore.settings.seats.count)）") {
                    if roomSettingsStore.settings.seats.isEmpty {
                        Text(roomSettingsStore.dynamicSeatGeneration
                             ? "未添加固定席位。动态生成开启时由主持人按议题组建。"
                             : "还没有添加席位。点下方「添加席位」从预制角色中选择。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                    } else {
                        ForEach(roomSettingsStore.settings.seats) { seat in
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(seat.name)
                                        .font(.body.weight(.medium))
                                        .foregroundStyle(AmberTheme.foreground)
                                    Text(seat.rolePrompt)
                                        .font(.caption)
                                        .foregroundStyle(AmberTheme.muted)
                                        .lineLimit(2)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                Button {
                                    roomSettingsStore.removeSeat(id: seat.id)
                                } label: {
                                    Image(systemName: "minus.circle.fill")
                                        .font(.system(size: 20))
                                        .foregroundStyle(AmberTheme.accentRed)
                                }
                                .buttonStyle(.plain)
                                .disabled(isReadOnly)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            Divider().overlay(AmberTheme.borderSoft)
                        }
                    }

                    Menu {
                        if availablePresets.isEmpty {
                            Text("预制角色已全部添加")
                        } else {
                            ForEach(availablePresets) { preset in
                                Button {
                                    roomSettingsStore.addOrUpdateSeat(preset.asSeat(), currentModelId: currentModelId)
                                } label: {
                                    Text("\(preset.name) · \(preset.rolePrompt)")
                                }
                            }
                        }
                    } label: {
                        Label("添加席位", systemImage: "plus.circle.fill")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                    }
                    .disabled(isReadOnly)
                }

                settingsGroup(title: "功能限制") {
                    Stepper(
                        "最大席位 \(roomSettingsStore.settings.limits.maxSeats)",
                        value: Binding(
                            get: { roomSettingsStore.settings.limits.maxSeats },
                            set: { roomSettingsStore.updateLimits(maxSeats: $0) }
                        ),
                        in: 2...8
                    )
                    .disabled(isReadOnly)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)

                    Divider().overlay(AmberTheme.borderSoft)

                    Stepper(
                        "默认轮数 \(roomSettingsStore.settings.limits.defaultRounds)",
                        value: Binding(
                            get: { roomSettingsStore.settings.limits.defaultRounds },
                            set: { roomSettingsStore.updateLimits(defaultRounds: $0) }
                        ),
                        in: 1...6
                    )
                    .disabled(isReadOnly)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)

                    Divider().overlay(AmberTheme.borderSoft)

                    Stepper(
                        "席位超时 \(roomSettingsStore.settings.limits.seatTimeoutSeconds)s",
                        value: Binding(
                            get: { roomSettingsStore.settings.limits.seatTimeoutSeconds },
                            set: { roomSettingsStore.updateLimits(seatTimeoutSeconds: $0) }
                        ),
                        in: 15...180,
                        step: 15
                    )
                    .disabled(isReadOnly)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)

                    Divider().overlay(AmberTheme.borderSoft)

                    Stepper(
                        "输出预算 \(roomSettingsStore.settings.limits.outputBudgetCharacters)",
                        value: Binding(
                            get: { roomSettingsStore.settings.limits.outputBudgetCharacters },
                            set: { roomSettingsStore.updateLimits(outputBudgetCharacters: $0) }
                        ),
                        in: 2_000...40_000,
                        step: 2_000
                    )
                    .disabled(isReadOnly)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
            }
            .padding(20)
        }
        .background(AmberTheme.background)
    }

    @ViewBuilder
    /// 预制角色里尚未添加的部分(按 id 和名称去重),供「添加席位」菜单展示。
    private var availablePresets: [IOSCouncilSeatPreset] {
        let addedIds = Set(roomSettingsStore.settings.seats.map(\.id))
        let addedNames = Set(roomSettingsStore.settings.seats.map { $0.name.lowercased() })
        return IOSCouncilSeatPreset.all.filter {
            !addedIds.contains($0.id) && !addedNames.contains($0.name.lowercased())
        }
    }

    private func settingsGroup<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
            VStack(spacing: 0) {
                content()
            }
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusLarge, style: .continuous))
        }
    }

    private func modelMenu(title: String, value: String, setValue: @escaping (String) -> Void) -> some View {
        Menu {
            ForEach(availableModelIds, id: \.self) { modelId in
                Button(modelId) { setValue(modelId) }
            }
        } label: {
            settingsRow(title: title, value: value.trimmedOr(currentModelId), systemImage: "cpu")
        }
        .buttonStyle(.plain)
        .disabled(isReadOnly || availableModelIds.isEmpty)
    }

    private func reasoningMenu(title: String, value: IOSCouncilReasoningPreset, setValue: @escaping (IOSCouncilReasoningPreset) -> Void) -> some View {
        Menu {
            ForEach(IOSCouncilReasoningPreset.allCases) { option in
                Button(option.title) { setValue(option) }
            }
        } label: {
            settingsRow(title: title, value: value.title, systemImage: "brain.head.profile")
        }
        .buttonStyle(.plain)
        .disabled(isReadOnly)
    }

    private func settingsRow(title: String, value: String, systemImage: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 30, height: 30)
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
            Spacer()
            Text(value)
                .font(.caption.weight(.medium))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 52)
        .contentShape(Rectangle())
    }
}

enum CouncilRuntimeSheet: Identifiable {
    case detail(CouncilDiscussionDetail)
    case members
    case settings

    var id: String {
        switch self {
        case .detail(let detail): "detail-\(detail.id)"
        case .members: "members"
        case .settings: "settings"
        }
    }
}

/// 议会暂停等用户回答时的状态。持有 continuation，用户回答/跳过后 resume。
struct IOSCouncilAskUserState: Identifiable {
    let id = UUID()
    let question: String
    /// 用户的回答输入草稿。
    var answerDraft: String = ""
    fileprivate let continuation: CheckedContinuation<String?, Never>

    /// 用户确认回答（或留空跳过）后调用，恢复议会。
    func resume() {
        let trimmed = answerDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        continuation.resume(returning: trimmed.isEmpty ? nil : trimmed)
    }
}

@MainActor
@Observable
final class CouncilChatViewModel {
    var inputText = ""
    var selectedMode: CouncilDiscussionMode = .freeChat
    var messages: [CouncilChatMessage]
    var isRunning = false
    var activeSheet: CouncilRuntimeSheet?
    /// 主持人向用户提问时的暂停状态（非 nil = 议会暂停中，等用户回答）。
    var pendingAskUser: IOSCouncilAskUserState?
    var participants: [CouncilParticipant] = []
    var failedSpeakerIds: Set<String> = []

    let roomSettingsStore: IOSCouncilRoomSettingsStore

    @ObservationIgnored private let settingsStore: SettingsStore
    @ObservationIgnored private let sharedSettings: IOSSharedSettingsStore
    @ObservationIgnored private let providerRegistry: ProviderRegistryStore?
    @ObservationIgnored private let runner: IOSCouncilRoomRunner
    @ObservationIgnored private let transcriptDefaults: UserDefaults
    @ObservationIgnored private var discussionTask: Task<Void, Never>?
    @ObservationIgnored private var currentObjective = ""
    @ObservationIgnored private var currentTaskId: String?
    @ObservationIgnored private var pendingObjective = ""
    @ObservationIgnored private var lastRunObjective = ""
    @ObservationIgnored private var lastResearchAllowed = false

    private var roomStateOverride: String?
    private var activeSpeakerId: String?
    private var invitedSpeakerIds: Set<String> = []

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore?,
        permissionStore: IOSPermissionStore,
        roomSettingsStore: IOSCouncilRoomSettingsStore = .shared,
        runner: IOSCouncilRoomRunner? = nil,
        transcriptDefaults: UserDefaults = .standard
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
        self.roomSettingsStore = roomSettingsStore
        self.runner = runner ?? IOSCouncilRoomRunner(permissionStore: permissionStore)
        self.transcriptDefaults = transcriptDefaults
        // 默认载回上一轮 transcript(退出后再进仍能看到历史);为空则空白开场。
        self.messages = CouncilTranscriptStore.load(defaults: transcriptDefaults)
        refreshSettingsBackedParticipants()
    }

    func persistTranscript() {
        CouncilTranscriptStore.save(messages, defaults: transcriptDefaults)
    }

    var canSend: Bool {
        !isRunning &&
            !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 用户提交对主持人提问的回答，恢复议会。
    func submitAskUserAnswer() {
        guard var pending = pendingAskUser else { return }
        pendingAskUser = nil
        pending.resume()
    }

    /// 用户跳过提问，恢复议会（视为无补充）。
    func skipAskUser() {
        guard var pending = pendingAskUser else { return }
        pendingAskUser = nil
        pending.answerDraft = ""
        pending.resume()
    }

    var currentModelId: String {
        let trimmed = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "gpt-4o" : trimmed
    }

    var hostDisplayName: String {
        participants.first(where: \.isHost)?.displayName ?? Self.hostName(for: currentModelId)
    }

    var roomStateText: String {
        if let roomStateOverride { return roomStateOverride }
        if settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "缺少 API Key"
        }
        if isRunning {
            return selectedMode.runningState
        }
        return "就绪"
    }

    var lastMessageBody: String {
        messages.last?.body ?? ""
    }

    /// 当前讨论轮次：统计 divider 消息数量（每次 appendDivider 标记一轮开场/结束）。
    var discussionRound: Int {
        let dividerCount = messages.filter { $0.kind == .divider }.count
        return max(1, dividerCount)
    }

    var availableModelIds: [String] {
        var seen = Set<String>()
        var ids: [String] = []
        // All chat models across every configured provider (shared settings),
        // not just the legacy registry's single selected provider.
        for option in sharedSettings.availableChatModels() {
            guard seen.insert(option.modelId).inserted else { continue }
            ids.append(option.modelId)
        }
        let current = currentModelId.trimmingCharacters(in: .whitespacesAndNewlines)
        if !current.isEmpty, seen.insert(current).inserted {
            ids.insert(current, at: 0)
        }
        return ids
    }

    func state(for participant: CouncilParticipant) -> CouncilParticipantState {
        if failedSpeakerIds.contains(participant.id) {
            return .failed
        }
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
        appendToken("请主持人动态选择本轮需要加入的席位。")
    }

    func send() {
        let text = inputText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isRunning else { return }
        guard !settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            appendMessage(
                kind: .system,
                author: "议会",
                body: "当前服务商缺少 API Key，无法启动模型议会。",
                systemImage: "key.slash",
                tint: AmberTheme.accentRed,
                subtitle: "配置阻塞",
                status: .failed
            )
            return
        }
        pendingObjective = text
        // 默认同意联网调研:web search 开启即直接联网,不再弹出确认面板。
        startPendingDiscussion(researchAllowed: sharedSettings.snapshot.enableWebSearch)
    }

    func startPendingDiscussion(researchAllowed: Bool) {
        let text = pendingObjective.trimmingCharacters(in: .whitespacesAndNewlines)
        pendingObjective = ""
        guard !text.isEmpty, !isRunning else { return }
        inputText = ""
        currentObjective = text
        lastRunObjective = text
        lastResearchAllowed = researchAllowed
        roomStateOverride = nil
        failedSpeakerIds.removeAll()
        appendMessage(
            kind: .user,
            author: "你",
            body: text,
            systemImage: "person.fill",
            tint: AmberTheme.accent,
            subtitle: nil
        )

        discussionTask?.cancel()
        discussionTask = Task { [weak self] in
            await self?.runDiscussion(objective: text, researchAllowed: researchAllowed)
        }
    }

    func cancelDiscussion() {
        discussionTask?.cancel()
        discussionTask = nil
        runner.cancel()
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        failedSpeakerIds.removeAll()
        isRunning = false
        roomStateOverride = "已取消"
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
        persistTranscript()
    }

    func showCurrentDetail() {
        activeSheet = .detail(makeDetail(status: isRunning ? selectedMode.runningState : "就绪"))
    }

    func showMembers() {
        activeSheet = .members
    }

    func showSettings() {
        activeSheet = .settings
    }

    func restartLastDiscussion() {
        let objective = lastRunObjective.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !objective.isEmpty, !isRunning else { return }
        resetRoom()
        pendingObjective = objective
        startPendingDiscussion(researchAllowed: lastResearchAllowed)
    }

    private func runDiscussion(objective: String, researchAllowed: Bool) async {
        isRunning = true
        invitedSpeakerIds.removeAll()
        activeSheet = nil
        appendDivider(selectedMode.openingDivider)
        roomSettingsStore.bootstrapLegacySeatsIfNeeded(
            sharedSettings.savedCouncilSeats,
            currentModelId: currentModelId
        )
        refreshSettingsBackedParticipants()
        let request = IOSCouncilRoomRunRequest(
            objective: objective,
            mode: selectedMode.runMode,
            settings: roomSettingsStore.settings,
            currentModelId: currentModelId,
            // Resolve from the shared settings store (canonical: formal Provider
            // UI writes it, chat reads it) so a provider configured in Settings
            // is honored — not the legacy ProviderRegistryStore (key-less +
            // ignores the selected model). Legacy fallback when nothing usable.
            providerSetting: sharedSettings.resolveCurrentProviderSetting()
                ?? IOSCouncilRoomRunner.makeProviderSetting(
                    baseUrl: settingsStore.baseUrl,
                    apiKey: settingsStore.currentApiKey
                ),
            searchSettings: sharedSettings.snapshot,
            // 默认同意联网调研:主持人始终用已配置的搜索服务(无配置时走内置 DuckDuckGo/Bing
            // 兜底)做调研,不再受聊天页 web-search 总开关或确认面板影响。
            researchConsent: .allowed,
            dynamicSeatGeneration: roomSettingsStore.dynamicSeatGeneration
        )
        let summary = await runner.run(request: request, onEvent: { [weak self] event in
            self?.handle(event)
        }, onAskUser: { [weak self] question in
            // 暂停议会，等用户回答。用户回答后恢复；跳过则返回 nil。
            guard let self else { return nil }
            return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
                self.pendingAskUser = IOSCouncilAskUserState(
                    question: question,
                    continuation: continuation
                )
            }
        })
        if currentTaskId == nil {
            currentTaskId = summary.taskId
        }
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        isRunning = false
        roomStateOverride = summary.status == .completed ? "就绪" : summary.status.title
        updateDetail(status: roomStateOverride ?? "就绪")
        persistTranscript()
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

    private func handle(_ event: IOSCouncilRoomEvent) {
        switch event {
        case .taskStarted(let id):
            currentTaskId = id
        case .state(let state):
            roomStateOverride = state
            updateDetail(status: state)
        case .roster(let speakers, let activeId, let failedIds):
            participants = speakers.map(CouncilParticipant.init(speaker:))
            activeSpeakerId = activeId
            failedSpeakerIds = failedIds
            invitedSpeakerIds = Set(speakers.filter { !$0.isHost }.map(\.id))
            updateDetail(status: roomStateOverride ?? selectedMode.runningState)
        case .append(let event):
            appendMessage(event)
        case .updateMessage(let id, let body, let status):
            updateMessage(id, body: body, status: CouncilMessageStatus(status))
        case .askUser:
            // 实际的暂停/恢复由 onAskUser 回调驱动，这里不做额外处理。
            break
        }
    }

    private func appendMessage(_ event: IOSCouncilRoomMessageEvent) {
        let participant = event.speakerId.flatMap { id in participants.first(where: { $0.id == id }) }
        let kind = CouncilMessageKind(event.kind)
        let tint = participant?.tint ?? (kind == .system ? AmberTheme.accentIndigo : AmberTheme.muted)
        let image = participant?.systemImage ?? (kind == .divider ? "circle.grid.cross" : "person.3.sequence")
        messages.append(CouncilChatMessage(
            id: event.id,
            kind: kind,
            author: event.author,
            body: event.body,
            systemImage: image,
            tint: tint,
            subtitle: event.subtitle,
            status: CouncilMessageStatus(event.status)
        ))
        updateDetail(status: roomStateOverride ?? (isRunning ? selectedMode.runningState : "就绪"))
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
            budgetSummary: "模式：\(selectedMode.title)\n最大席位：\(roomSettingsStore.settings.limits.maxSeats)\n默认轮数：\(roomSettingsStore.settings.limits.defaultRounds)\n席位超时：\(roomSettingsStore.settings.limits.seatTimeoutSeconds)s\n输出预算：\(roomSettingsStore.settings.limits.outputBudgetCharacters)\n提供商：当前 OpenAI-compatible 配置\n主持模型：\(roomSettingsStore.settings.host.modelId.trimmedOr(currentModelId))",
            transcript: roomTranscript(limit: 80)
        )
    }

    private func updateDetail(status: String) {
        if case .detail = activeSheet {
            activeSheet = .detail(makeDetail(status: status))
        }
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

    private func refreshSettingsBackedParticipants() {
        roomSettingsStore.bootstrapLegacySeatsIfNeeded(sharedSettings.savedCouncilSeats, currentModelId: currentModelId)
        let settings = roomSettingsStore.settings.normalized(currentModelId: currentModelId)
        let host = IOSCouncilRoomSpeaker(
            id: "host",
            name: "主持人",
            rolePrompt: settings.host.prompt,
            modelId: settings.host.modelId,
            reasoning: settings.host.reasoning,
            prompt: settings.host.prompt,
            isHost: true
        )
        let seats = settings.defaultSeats(currentModelId: currentModelId).map {
            IOSCouncilRoomSpeaker(
                id: $0.id,
                name: $0.name,
                rolePrompt: $0.rolePrompt,
                modelId: $0.modelId,
                reasoning: $0.reasoning,
                prompt: $0.prompt,
                isHost: false
            )
        }
        participants = ([host] + seats).map(CouncilParticipant.init(speaker:))
    }

    private func resetRoom() {
        discussionTask?.cancel()
        runner.cancel()
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        failedSpeakerIds.removeAll()
        currentTaskId = nil
        roomStateOverride = nil
        currentObjective = ""
        messages = [
            CouncilChatMessage(
                kind: .system,
                author: "议会",
                body: "模型议会已重新开始。",
                systemImage: "person.3.sequence",
                tint: AmberTheme.accentIndigo,
                subtitle: "就绪"
            )
        ]
        // 重新开始即清空历史(存盘也同步为空白开场),避免退出后又载回旧 transcript。
        persistTranscript()
        refreshSettingsBackedParticipants()
    }
}

enum CouncilDiscussionMode: String, CaseIterable, Identifiable {
    case freeChat = "free_chat"
    case debate

    var id: String { rawValue }

    var title: String {
        switch self {
        case .freeChat: "自由群聊"
        case .debate: "辩论"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .freeChat: "自由群聊模式"
        case .debate: "辩论模式"
        }
    }

    var intent: String {
        switch self {
        case .freeChat: "扩展信息面、发现选项、形成早期方向"
        case .debate: "挑战假设、发现盲区、降低决策风险"
        }
    }

    var runningState: String {
        switch self {
        case .freeChat: "群聊中"
        case .debate: "辩论中"
        }
    }

    var openingDivider: String {
        switch self {
        case .freeChat: "自由群聊 · 开场"
        case .debate: "辩论 · 交叉回应"
        }
    }

    var systemImage: String {
        switch self {
        case .freeChat: "bubble.left.and.bubble.right"
        case .debate: "scale.3d"
        }
    }

    var tint: Color {
        switch self {
        case .freeChat: AmberTheme.accentCyan
        case .debate: AmberTheme.accentRed
        }
    }

    var runMode: IOSCouncilRoomRunMode {
        switch self {
        case .freeChat: .freeChat
        case .debate: .debate
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

    init(speaker: IOSCouncilRoomSpeaker) {
        self.init(
            id: speaker.id,
            handle: Self.makeHandle(from: speaker.name, fallback: speaker.isHost ? "host" : speaker.id),
            displayName: speaker.isHost ? "Host · \(speaker.modelId)" : speaker.name,
            roleDescription: speaker.rolePrompt,
            shortLens: speaker.shortLens,
            systemImage: speaker.isHost ? "crown" : Self.icon(for: speaker.id),
            tint: speaker.isHost ? AmberTheme.accent : Self.tint(for: speaker.id),
            isHost: speaker.isHost,
            modelHint: speaker.modelId,
            modelId: speaker.modelId
        )
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

    private static func icon(for id: String) -> String {
        if id.contains("risk") { return "exclamationmark.shield" }
        if id.contains("opponent") { return "hand.raised" }
        if id.contains("product") { return "rectangle.3.group.bubble" }
        if id.contains("engineering") { return "hammer" }
        return "person.crop.circle.badge.checkmark"
    }

    private static func tint(for id: String) -> Color {
        let palette = [
            AmberTheme.accentCyan,
            AmberTheme.accentGreen,
            AmberTheme.accentAmber,
            AmberTheme.accentIndigo,
            AmberTheme.accentRed
        ]
        let value = abs(id.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) })
        return palette[value % palette.count]
    }
}

enum CouncilParticipantState: String {
    case idle
    case invited
    case speaking
    case failed

    var label: String {
        switch self {
        case .idle: "待命"
        case .invited: "已邀请"
        case .speaking: "发言中"
        case .failed: "失败"
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
        if status == .failed {
            return AmberTheme.accentRed.opacity(0.08)
        }
        switch kind {
        case .user: return AmberTheme.accent
        case .host: return AmberTheme.surface
        case .guest: return Color.white.opacity(0.56)
        case .system: return AmberTheme.surface2.opacity(0.65)
        case .divider: return .clear
        }
    }

    var foregroundColor: Color {
        kind == .user ? .white : AmberTheme.foreground
    }

    var borderColor: Color {
        if status == .failed {
            return AmberTheme.accentRed.opacity(0.42)
        }
        switch kind {
        case .host: return AmberTheme.accent.opacity(0.24)
        case .guest: return AmberTheme.borderSoft
        case .system: return AmberTheme.borderSoft
        default: return .clear
        }
    }
}

enum CouncilMessageKind {
    case user
    case host
    case guest
    case system
    case divider

    init(_ kind: IOSCouncilRoomMessageKind) {
        switch kind {
        case .host: self = .host
        case .seat: self = .guest
        case .system: self = .system
        case .divider: self = .divider
        }
    }

    var rawKey: String {
        switch self {
        case .user: "user"; case .host: "host"; case .guest: "guest"; case .system: "system"; case .divider: "divider"
        }
    }

    init(rawKey: String) {
        switch rawKey {
        case "host": self = .host
        case "guest": self = .guest
        case "system": self = .system
        case "divider": self = .divider
        default: self = .user
        }
    }
}

enum CouncilMessageStatus {
    case speaking
    case completed
    case failed

    init(_ status: IOSCouncilRoomMessageStatus) {
        switch status {
        case .speaking: self = .speaking
        case .completed: self = .completed
        case .failed: self = .failed
        }
    }

    var rawKey: String {
        switch self {
        case .speaking: "speaking"; case .completed: "completed"; case .failed: "failed"
        }
    }

    init(rawKey: String) {
        switch rawKey {
        case "failed": self = .failed
        // 重载会把仍标记为 speaking 的消息当作已完成(上一轮早已结束,不应再显示"思考中")。
        default: self = .completed
        }
    }
}

/// Codable 快照,用于把上一轮议会的 transcript 存盘,退出后再进默认载回。颜色用 hex
/// 保留(席位/主持的色彩在重载后仍准确);backgroundColor/foregroundColor/borderColor 由
/// kind+status 派生,无需存。
private struct CouncilPersistedMessage: Codable {
    let id: String
    let kind: String
    let author: String
    let body: String
    let systemImage: String
    let tintHex: String
    let subtitle: String?
    let status: String

    init(_ message: CouncilChatMessage) {
        self.id = message.id.uuidString
        self.kind = message.kind.rawKey
        self.author = message.author
        self.body = message.body
        self.systemImage = message.systemImage
        self.tintHex = message.tint.councilHexString
        self.subtitle = message.subtitle
        self.status = message.status.rawKey
    }

    func restored() -> CouncilChatMessage {
        CouncilChatMessage(
            id: UUID(uuidString: id) ?? UUID(),
            kind: CouncilMessageKind(rawKey: kind),
            author: author,
            body: body,
            systemImage: systemImage,
            tint: Color(councilHexString: tintHex),
            subtitle: subtitle,
            status: CouncilMessageStatus(rawKey: status)
        )
    }
}

/// UserDefaults 持久化上一轮议会 transcript(单房间,只保留最近一份)。
enum CouncilTranscriptStore {
    private static let key = "app.amber.ios.councilTranscript.v1"

    static func save(_ messages: [CouncilChatMessage], defaults: UserDefaults) {
        let dtos = messages.map(CouncilPersistedMessage.init)
        if let data = try? JSONEncoder().encode(dtos) {
            defaults.set(data, forKey: key)
        }
    }

    static func load(defaults: UserDefaults) -> [CouncilChatMessage] {
        guard let data = defaults.data(forKey: key),
              let dtos = try? JSONDecoder().decode([CouncilPersistedMessage].self, from: data) else {
            return []
        }
        return dtos.map { $0.restored() }
    }

    static func clear(defaults: UserDefaults) {
        defaults.removeObject(forKey: key)
    }
}

private extension Color {
    /// `#RRGGBB`。SwiftUI Color → UIColor 取分量;转换失败回退中性灰。
    var councilHexString: String {
        let ui = UIColor(self)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        guard ui.getRed(&r, green: &g, blue: &b, alpha: &a) else { return "#999999" }
        return String(format: "#%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
    }

    init(councilHexString: String) {
        let hex = councilHexString.trimmingCharacters(in: CharacterSet(charactersIn: "# "))
        var value: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&value)
        self = Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
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
    func trimmedOr(_ fallback: String) -> String {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

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
