import SwiftUI
import Observation
import SwiftStreamingMarkdown
@preconcurrency import Shared

struct CouncilTranscriptScrollGeometry: Equatable {
    let contentHeight: CGFloat
    let visibleHeight: CGFloat
    let isNearBottom: Bool
}

enum CouncilTranscriptFollowPolicy {
    static let bottomAnchorID = "council-transcript-bottom-anchor"
    static let liveGrowthAnimationDuration = 0.08
    private static let minimumMeasuredGrowth: CGFloat = 0.5

    static func shouldResumeFollowing(distanceToBottom: CGFloat) -> Bool {
        distanceToBottom <= ChatLayout.nearBottomResumeThreshold
    }

    static func shouldFollowMeasuredGrowth(
        previousContentHeight: CGFloat,
        currentContentHeight: CGFloat,
        followEnabled: Bool,
        followPaused: Bool,
        userDragging: Bool
    ) -> Bool {
        followEnabled &&
            !followPaused &&
            !userDragging &&
            currentContentHeight > previousContentHeight + minimumMeasuredGrowth
    }

    static func shouldFollowViewportShrink(
        previousVisibleHeight: CGFloat,
        currentVisibleHeight: CGFloat,
        followEnabled: Bool,
        followPaused: Bool,
        userDragging: Bool
    ) -> Bool {
        followEnabled &&
            !followPaused &&
            !userDragging &&
            currentVisibleHeight < previousVisibleHeight - minimumMeasuredGrowth
    }
}

struct CouncilChatRuntimeView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let providerRegistry: ProviderRegistryStore?
    let permissionStore: IOSPermissionStore

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var viewModel: CouncilChatViewModel
    @FocusState private var isComposerFocused: Bool
    // 底部跟随状态(与 Chat 页同款):`userDragging` 只在用户「主动拖动」时为真,
    // `followPaused` 也只在用户拖离底部时才置真——内容增长(流式/阶段衔接)导致的几何变化
    // 不会误暂停跟随。跟随本身遵循全局「跟随生成」偏好,与聊天页一致。
    @State private var userDragging = false
    @State private var followPaused = false
    @State private var transcriptNearBottom = true
    @State private var scrollPosition = ScrollPosition()
    @State private var measuredGrowthFollowTask: Task<Void, Never>?
    @State private var measuredGrowthFollowPending = false
    @State private var terminalSettleTask: Task<Void, Never>?
    @State private var scrollDriver = NativeTimelineScrollDriver()
    @State private var nativeScrollFallbackReason: NativeTimelineScrollFallbackReason?
    @State private var isNativeScrollSurfaceVisible = false
    @AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true
    @AppStorage(NativeTimelineScrollFeatureFlags.key) private var nativeScrollDriverEnabled = false

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore? = nil,
        permissionStore: IOSPermissionStore = IOSPermissionStore(),
        viewModel: CouncilChatViewModel? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
        self.permissionStore = permissionStore
        self._viewModel = State(initialValue: viewModel ?? CouncilChatViewModel(
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
        .onAppear {
            isNativeScrollSurfaceVisible = true
            viewModel.runtimeDidAppear()
        }
        // 离开页面(返回/切走)时立即存一份当前 transcript,避免运行中退出导致刚流式出的内容
        // 在下次进入前丢失(运行任务仍会在后台跑完并再存一次,以更完整的为准)。
        .onDisappear {
            isNativeScrollSurfaceVisible = false
            cancelPendingMeasuredGrowthFollow()
            terminalSettleTask?.cancel()
            scrollDriver.invalidate()
            viewModel.runtimeDidDisappear()
            viewModel.persistTranscript()
        }
        .onChange(of: followGeneration) { _, enabled in
            scrollDriver.setAutomaticFollowEnabled(enabled)
        }
        .onChange(of: nativeScrollDriverEnabled) { _, enabled in
            if !enabled {
                nativeScrollFallbackReason = nil
                scrollDriver.invalidate()
            }
        }
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
                    restartAction: { viewModel.startFreshRoom() }
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
            case .history:
                CouncilHistorySheet(
                    tasks: IOSAdvancedTaskStore.shared.recent(kind: .modelCouncil, limit: 30)
                        .filter { CouncilRoomArchiveStore.shared.exists(taskId: $0.id) },
                    activeTaskId: viewModel.activeReplayTaskId,
                    onSelect: { taskId in viewModel.openArchive(taskId: taskId) }
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
                .presentationBackground(AmberTheme.background)
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
            // ZStack:标题绝对居中(以屏幕为基准),不受左 1 颗 / 右 2 颗按钮数量差影响。
            // 之前用单 HStack + maxWidth:.infinity,标题只在「返回」和「历史」之间居中,
            // 整体偏左。改成居中标题 + 左右按钮叠加,标题真正居中。
            ZStack {
                // 整个标题区(模式 + 成员·轮次)都可点击,唤起底部 sheet 切换模式 / 看席位。
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
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("议会模式与席位")

                HStack(spacing: 10) {
                    AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                        dismiss()
                    }

                    Spacer(minLength: 0)

                    // 历史议会入口:列出「最近讨论」,点任意一场重开成只读对话。
                    AmberGlassCircleButton(systemImage: "clock.arrow.circlepath", accessibilityLabel: "历史议会", size: 44, symbolSize: 18) {
                        viewModel.showHistory()
                    }

                    // 顶栏始终是设置入口;停止由输入框那颗发送/停止键负责(与 Chat 页一致),
                    // 不在顶栏再放一颗多余的停止键。
                    AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "议会设置", size: 44, symbolSize: 18) {
                        viewModel.showSettings()
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private var transcript: some View {
        ScrollView {
            // 用非 lazy 的 VStack:议会转录是有界的(轮数 1-5 × 席位数),一次性渲染可接受,
            // 也避免 LazyVStack 回收行导致的重解析/高度跳变。
            VStack(spacing: 12) {
                ForEach(viewModel.messages) { message in
                    CouncilMessageRow(
                        message: message,
                        onTapDetail: { viewModel.showCurrentDetail() },
                        onRestart: { viewModel.restart(withObjective: $0) }
                    )
                    // Equatable:流式逐 token 改的是最后一条,只让那一行重渲染,
                    // 其余已完成气泡不再随整个 messages 数组变化而重算 body。
                    .equatable()
                    .id(message.id)
                }

                // 永久存在的物理底锚。留白只改变自身高度,不会在消息切换时从旧行
                // 删除再插到新行；异步 Markdown 二次增高也始终以同一个内容尾部为目标。
                Color.clear
                    .frame(height: viewModel.isRunning
                        ? ChatLayout.followBottomGap
                        : ChatLayout.bottomRestGap)
                    .id(CouncilTranscriptFollowPolicy.bottomAnchorID)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 18)
            .frame(maxWidth: .infinity, minHeight: 0)
            .scrollTargetLayout()
            .background {
                if isNativeScrollDriverDesired {
                    NativeTimelineScrollViewResolver(
                        onResolve: handleNativeScrollViewResolved,
                        onMetricsChanged: {
                            guard isNativeScrollDriverActive else { return }
                            scrollDriver.handleLayoutMetricsChanged()
                        }
                    )
                }
            }
        }
        .scrollPosition($scrollPosition)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        .defaultScrollAnchor(.top, for: .alignment)
        .scrollIndicators(.hidden)
        // 区分「用户主动拖动」与「程序/内容增长引起的滚动」:只有前者才允许改 followPaused。
        .onScrollPhaseChange { _, phase in
            switch phase {
            case .tracking, .interacting:
                userDragging = true
                if isNativeScrollDriverActive {
                    scrollDriver.submit(.userDragBegan)
                }
                cancelPendingMeasuredGrowthFollow()
                terminalSettleTask?.cancel()
            case .idle:
                let endedUserDrag = userDragging
                userDragging = false
                if endedUserDrag {
                    let returnedToBottom = NativeTimelineScrollReturnPolicy.returnedToBottom(
                        liveDistanceToBottom: isNativeScrollDriverActive
                            ? scrollDriver.distanceToBottomNow()
                            : nil,
                        cachedNearBottom: transcriptNearBottom,
                        threshold: ChatLayout.nearBottomResumeThreshold
                    )
                    followPaused = !returnedToBottom
                    if isNativeScrollDriverActive {
                        scrollDriver.submit(.userDragEnded(isAtBottom: returnedToBottom))
                    }
                    if returnedToBottom, followGeneration {
                        scheduleMeasuredGrowthFollowToBottom()
                    }
                }
            case .animating, .decelerating:
                break
            @unknown default:
                break
            }
        }
        // 跟随真实内容高度而非 body 信号。流式 Markdown 会异步发布 renderable,
        // 其二次自撑高度没有新的文本回调；正向高度变化是唯一可靠的布局完成证据。
        .onScrollGeometryChange(for: CouncilTranscriptScrollGeometry.self) { geometry in
            let distanceToBottom = geometry.contentSize.height - geometry.visibleRect.maxY
            return CouncilTranscriptScrollGeometry(
                contentHeight: geometry.contentSize.height,
                visibleHeight: geometry.visibleRect.height,
                isNearBottom: CouncilTranscriptFollowPolicy.shouldResumeFollowing(
                    distanceToBottom: distanceToBottom
                )
            )
        } action: { previous, current in
            transcriptNearBottom = current.isNearBottom
            if userDragging {
                followPaused = !current.isNearBottom
            }
            let shouldFollowMeasuredGrowth = CouncilTranscriptFollowPolicy.shouldFollowMeasuredGrowth(
                previousContentHeight: previous.contentHeight,
                currentContentHeight: current.contentHeight,
                followEnabled: followGeneration,
                followPaused: followPaused,
                userDragging: userDragging
            )
            let shouldFollowViewportShrink = CouncilTranscriptFollowPolicy.shouldFollowViewportShrink(
                previousVisibleHeight: previous.visibleHeight,
                currentVisibleHeight: current.visibleHeight,
                followEnabled: followGeneration,
                followPaused: followPaused,
                userDragging: userDragging
            )
            if shouldFollowMeasuredGrowth || shouldFollowViewportShrink {
                scheduleMeasuredGrowthFollowToBottom()
            }
        }
        .onChange(of: viewModel.isRunning) { wasRunning, isRunning in
            if wasRunning, !isRunning {
                scheduleTerminalBottomSettle()
            }
        }
        .onChange(of: viewModel.messages.first?.id) { _, _ in
            // 新房间/历史重放不继承上一房间的“用户暂停跟随”状态。
            followPaused = false
            if isNativeScrollDriverActive {
                scrollDriver.submit(.conversationReset)
            }
            scheduleTerminalBottomSettle()
        }
    }

    private func scheduleMeasuredGrowthFollowToBottom() {
        guard followGeneration, !followPaused, !userDragging else { return }
        if isNativeScrollDriverActive {
            scrollDriver.submit(.streamContentGrew)
            return
        }
        // A 48ms text presentation flush can be followed by multiple asynchronous
        // Markdown height publications. Keep ownership for the full 80ms animation
        // so later geometry callbacks cannot restart it mid-flight.
        guard measuredGrowthFollowTask == nil else {
            measuredGrowthFollowPending = true
            return
        }
        measuredGrowthFollowPending = false
        measuredGrowthFollowTask = Task { @MainActor in
            defer {
                let shouldReplay = measuredGrowthFollowPending &&
                    followGeneration &&
                    !followPaused &&
                    !userDragging
                measuredGrowthFollowPending = false
                measuredGrowthFollowTask = nil
                if shouldReplay {
                    scheduleMeasuredGrowthFollowToBottom()
                }
            }
            await Task.yield()
            guard !Task.isCancelled,
                  followGeneration,
                  !followPaused,
                  !userDragging else { return }
            guard !reduceMotion else {
                scrollToTranscriptBottom()
                return
            }
            withAnimation(.linear(duration: CouncilTranscriptFollowPolicy.liveGrowthAnimationDuration)) {
                scrollPosition.scrollTo(edge: .bottom)
            }
            do {
                try await Task.sleep(
                    nanoseconds: UInt64(
                        CouncilTranscriptFollowPolicy.liveGrowthAnimationDuration * 1_000_000_000
                    )
                )
            } catch {
                return
            }
        }
    }

    private func cancelPendingMeasuredGrowthFollow() {
        measuredGrowthFollowPending = false
        measuredGrowthFollowTask?.cancel()
    }

    private func scheduleTerminalBottomSettle() {
        cancelPendingMeasuredGrowthFollow()
        terminalSettleTask?.cancel()
        terminalSettleTask = Task { @MainActor in
            // Give the 96pt→26pt sentinel change and the terminal Markdown parse a
            // layout turn before the exact, non-animated bottom settle.
            await Task.yield()
            guard !Task.isCancelled,
                  followGeneration,
                  !followPaused,
                  !userDragging else { return }
            scrollToTranscriptBottom()
        }
    }

    private func scrollToTranscriptBottom() {
        if isNativeScrollDriverActive {
            scrollDriver.submit(
                .explicitBottom(source: .streamGrowth, animated: false, keyboardToken: nil)
            )
            return
        }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        transaction.animation = nil
        withTransaction(transaction) {
            scrollPosition.scrollTo(edge: .bottom)
        }
    }

    private var isNativeScrollDriverDesired: Bool {
        nativeScrollDriverEnabled &&
            nativeScrollFallbackReason == nil &&
            isNativeScrollSurfaceVisible
    }

    private var isNativeScrollDriverActive: Bool {
        isNativeScrollDriverDesired && scrollDriver.isAttached
    }

    private func handleNativeScrollViewResolved(_ scrollView: UIScrollView) {
        guard isNativeScrollDriverDesired else { return }
        let wasAttached = scrollDriver.isAttached
        scrollDriver.setAutomaticFollowEnabled(followGeneration)
        scrollDriver.onFallback = { reason, shouldReplayBottom in
            guard nativeScrollFallbackReason == nil else { return }
            nativeScrollFallbackReason = reason
            guard shouldReplayBottom, !userDragging else { return }
            scrollToTranscriptBottom()
        }
        scrollDriver.attach(scrollView)
        guard !wasAttached, scrollDriver.isAttached else { return }
        if userDragging || followPaused {
            scrollDriver.submit(.userDragBegan)
        } else {
            scrollDriver.submit(
                .explicitBottom(source: .button, animated: false, keyboardToken: nil)
            )
        }
    }

    // 输入条与 Chat 页保持完全一致:复用 Chat 的原生 Liquid Glass 组件
    // —— 左侧输入胶囊(`composerDockGlass`)+ 右侧分离的圆形发送键
    // (`ComposerDockSendButton`)。胶囊内左侧 `@` 键沿用 Chat 的「+」位,
    // 与 Chat 一致的原生输入胶囊 + 分离圆形发送键。去掉了原来无实际作用的 @ 键
    // （runner 并不解析 @host 提及）和此前黏连成一团的玻璃操作 chips。
    @ViewBuilder
    private var composer: some View {
        if viewModel.isReplay {
            replayBanner
        } else {
            liveComposer
        }
    }

    // 只读重放历史议会时的底部条:提示「只读」+ 一键开新议会(退出重放、清空房间)。
    private var replayBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(AmberTheme.muted)
            Text("历史议会 · 只读")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
            Spacer(minLength: 8)
            Button {
                viewModel.startFreshRoom()
            } label: {
                Text("开新议会")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 9)
                    .background(AmberTheme.accentTint, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
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

    private var liveComposer: some View {
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

private struct CouncilMessageRow: View, Equatable {
    let message: CouncilChatMessage
    let onTapDetail: () -> Void
    /// 以指定议题重开议会(供用户气泡的「以此为题重开 / 编辑重开」)。
    let onRestart: (String) -> Void

    @State private var editing = false
    @State private var editDraft = ""

    // 只比较影响渲染的字段(闭包每次父刷新都新建但语义不变,忽略)。内部 @State
    // (editing/editDraft)变化不受此 == 影响,SwiftUI 仍会照常更新本行。
    nonisolated static func == (lhs: CouncilMessageRow, rhs: CouncilMessageRow) -> Bool {
        lhs.message.id == rhs.message.id
            && lhs.message.body == rhs.message.body
            && lhs.message.status == rhs.message.status
            && lhs.message.author == rhs.message.author
            && lhs.message.subtitle == rhs.message.subtitle
    }

    var body: some View {
        content
            .sheet(isPresented: $editing) { editSheet }
    }

    @ViewBuilder
    private var content: some View {
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
                // 长按高亮平台裁成气泡形状(与 Chat 页一致),消除灰角。
                .contentShape(
                    .contextMenuPreview,
                    UnevenRoundedRectangle(
                        topLeadingRadius: 18,
                        bottomLeadingRadius: 18,
                        bottomTrailingRadius: 6,
                        topTrailingRadius: 18,
                        style: .continuous
                    )
                )
                .contextMenu { messageActions }
        }
    }

    // 长按菜单。复制 / 分享对两类气泡通用;「以此为题重开 / 编辑重开」仅用户气泡(议题)有。
    @ViewBuilder
    private var messageActions: some View {
        Button {
            UIPasteboard.general.string = message.body
        } label: {
            Label("复制", systemImage: "doc.on.doc")
        }
        ShareLink(item: message.body) {
            Label("分享", systemImage: "square.and.arrow.up")
        }
        if message.kind == .user {
            Divider()
            Button {
                onRestart(message.body)
            } label: {
                Label("以此为题重开", systemImage: "arrow.counterclockwise")
            }
            Button {
                editDraft = message.body
                editing = true
            } label: {
                Label("编辑重开", systemImage: "square.and.pencil")
            }
        }
    }

    // 编辑议题后重开:预填原文,提交即清空当前画面并用改写后的议题重跑。
    private var editSheet: some View {
        NavigationStack {
            VStack {
                TextEditor(text: $editDraft)
                    .font(.body)
                    .padding(8)
                    .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding(16)
            }
            .navigationTitle("编辑议题")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { editing = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("重开") {
                        editing = false
                        onRestart(editDraft)
                    }
                    .disabled(editDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    // Agent 消息:头像 + 元信息在顶部一行,气泡落到下一行占满整行宽度
    // ——气泡左缘对齐头像左缘,右边距 = 左边距(整体复用滚动内容的 16pt 水平内边距,左右对称)。
    private var assistantRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 10) {
                avatar
                metaLine
            }
            bubble
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
        // host/guest 都由 runner 的累计流式文本产生。稳定 namespace + 明确的 live/ever
        // 标记让 Council 与标准 Chat 共用同一套增量 Markdown、完成态 renderer latch 和缓存。
        ChatAssistantMarkdownView(
            markdown: message.displayBody,
            renderCacheNamespace: message.markdownRenderCacheNamespace,
            isStreaming: message.isStreamingMarkdown,
            hasEverStreamed: message.hasEverStreamedMarkdown
        )
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(message.backgroundColor, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .foregroundStyle(message.foregroundColor)
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(message.borderColor, lineWidth: message.kind == .host ? 0.8 : 0.4)
            }
            // 长按高亮平台裁成气泡圆角形状,避免方角灰底。
            .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: 16, style: .continuous))
            // 轻点看详情,长按出「复制」菜单(两者共存)。
            .onTapGesture(perform: onTapDetail)
            .contextMenu { messageActions }
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
                    // 纯文字「重开」:清空当前议会、回到空白开场(即便正在运行也先停掉再清),
                    // 不再用刷新图标,也不再是「重跑上一题」(那个会因 lastRunObjective 为空而点了没反应)。
                    Button("重开", role: .destructive) {
                        restartAction()
                        dismiss()
                    }
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
                        "单次模型超时 \(roomSettingsStore.settings.limits.seatTimeoutSeconds)s",
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
    case history

    var id: String {
        switch self {
        case .detail(let detail): "detail-\(detail.id)"
        case .members: "members"
        case .settings: "settings"
        case .history: "history"
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
    /// 只读重放:从「最近讨论」重开一场历史议会时为 true,隐藏输入条、不再归档/覆盖草稿。
    var isReplay = false

    let roomSettingsStore: IOSCouncilRoomSettingsStore

    @ObservationIgnored private let settingsStore: SettingsStore
    @ObservationIgnored private let sharedSettings: IOSSharedSettingsStore
    @ObservationIgnored private let providerRegistry: ProviderRegistryStore?
    @ObservationIgnored private let runner: IOSCouncilRoomRunner
    @ObservationIgnored private let transcriptDefaults: UserDefaults
    @ObservationIgnored private let archiveStore: CouncilRoomArchiveStore
    @ObservationIgnored private var discussionTask: Task<Void, Never>?
    @ObservationIgnored private var activeDiscussionID: UUID?
    @ObservationIgnored private var isRuntimeAttached = false
    @ObservationIgnored private var isApplicationActive = true
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
        transcriptDefaults: UserDefaults = .standard,
        archiveStore: CouncilRoomArchiveStore = .shared
    ) {
        let restoredRoom = CouncilTranscriptStore.load(defaults: transcriptDefaults)
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
        self.roomSettingsStore = roomSettingsStore
        self.runner = runner ?? IOSCouncilRoomRunner(permissionStore: permissionStore)
        self.transcriptDefaults = transcriptDefaults
        self.archiveStore = archiveStore
        self.messages = restoredRoom?.messages.map { $0.restored() } ?? []
        if let restoredRoom {
            currentTaskId = restoredRoom.taskId.trimmedNilIfBlank
            currentObjective = restoredRoom.objective
            lastRunObjective = restoredRoom.objective
            selectedMode = CouncilDiscussionMode(rawValue: restoredRoom.modeRaw) ?? .freeChat
            participants = restoredRoom.participants.map { $0.restored() }
            failedSpeakerIds = Set(restoredRoom.failedSpeakerIds)
            invitedSpeakerIds = Set(participants.filter { !$0.isHost }.map(\.id))
            roomStateOverride = restoredRoom.statusRaw.trimmedNilIfBlank
        }
        if participants.isEmpty {
            refreshSettingsBackedParticipants()
        }
    }

    func persistTranscript() {
        // 重放历史议会时不要把只读快照写回单房间草稿,否则会覆盖实时房间的进度。
        guard !isReplay else { return }
        CouncilTranscriptStore.save(
            persistedRoom(taskId: currentTaskId ?? ""),
            defaults: transcriptDefaults
        )
    }

    func runtimeDidAppear() {
        isRuntimeAttached = true
    }

    func runtimeDidDisappear() {
        isRuntimeAttached = false
        skipAskUser()
    }

    func runtimeWillEnterBackground() {
        isApplicationActive = false
        skipAskUser()
        persistTranscript()
        archiveCurrentRoom()
    }

    func runtimeDidBecomeActive() {
        isApplicationActive = true
    }

    var canSend: Bool {
        !isRunning &&
            currentConfigurationIssue == nil &&
            !inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
        let trimmed = sharedSettings.resolveCurrentModelId()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "gpt-4o" : trimmed
    }

    var hostDisplayName: String {
        participants.first(where: \.isHost)?.displayName ?? Self.hostName(for: currentModelId)
    }

    var roomStateText: String {
        if let roomStateOverride { return roomStateOverride }
        if let issue = currentConfigurationIssue {
            return issue.title
        }
        if isRunning {
            return selectedMode.runningState
        }
        return "就绪"
    }

    var lastMessageBody: String {
        messages.last?.body ?? ""
    }

    /// 当前若处于只读重放,返回被重放那场的 taskId(供历史列表高亮),否则 nil。
    var activeReplayTaskId: String? {
        isReplay ? currentTaskId : nil
    }

    func recoverInterruptedTasks(_ taskIds: [String]) {
        guard let currentTaskId, taskIds.contains(currentTaskId) else { return }
        if let room = archiveStore.load(taskId: currentTaskId) {
            currentObjective = room.objective
            lastRunObjective = room.objective
            selectedMode = CouncilDiscussionMode(rawValue: room.modeRaw) ?? selectedMode
            participants = room.participants.map { $0.restored() }
            failedSpeakerIds = Set(room.failedSpeakerIds)
            messages = room.messages.map { $0.restored() }
        }
        finishStreamingMessages(as: .failed)
        isRunning = false
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        roomStateOverride = IOSAdvancedTaskStatus.interrupted.title
        persistTranscript()
        archiveCurrentRoom()
    }

    /// 当前讨论轮次：只数真正的「第 N 轮」轮次分隔，不把开场 / 主持调研 / 席位组建 /
    /// 轮末点评 / 主持总结等阶段胶囊也算进轮次（否则开场阶段就会误显示「第 6 轮」）。
    var discussionRound: Int {
        let roundMarkers = messages.filter {
            $0.kind == .divider
                && $0.body.range(of: #"^第\s*\d+\s*轮$"#, options: .regularExpression) != nil
        }
        return max(1, roundMarkers.count)
    }

    var availableModelIds: [String] {
        var seen = Set<String>()
        var ids: [String] = []
        for model in sharedSettings.resolveCurrentProviderSetting()?.models ?? []
            where model.type == ModelType.chat {
            let modelId = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !modelId.isEmpty, seen.insert(modelId).inserted else { continue }
            ids.append(modelId)
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
        guard !text.isEmpty, !isRunning, activeDiscussionID == nil else { return }
        if let issue = currentConfigurationIssue {
            appendMessage(
                kind: .system,
                author: "议会",
                body: issue.message,
                systemImage: "exclamationmark.triangle",
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
        guard !text.isEmpty, !isRunning, activeDiscussionID == nil else { return }
        if currentTaskId != nil || !messages.isEmpty {
            resetRoom()
        }
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

        let discussionID = UUID()
        activeDiscussionID = discussionID
        isRunning = true
        discussionTask = Task { [weak self] in
            await self?.runDiscussion(
                objective: text,
                researchAllowed: researchAllowed,
                discussionID: discussionID
            )
        }
    }

    func cancelDiscussion() {
        stopActiveDiscussion()
        finishStreamingMessages(as: .failed)
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
        archiveCurrentRoom()
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

    func showHistory() {
        activeSheet = .history
    }

    /// 从「最近讨论」重开一场历史议会,把归档快照还原成只读对话。
    func openArchive(taskId: String) {
        guard var room = archiveStore.load(taskId: taskId) else { return }
        stopAndCheckpointActiveDiscussion()
        if let checkpointedRoom = archiveStore.load(taskId: taskId) {
            room = checkpointedRoom
        }
        isRunning = false
        isReplay = true
        currentTaskId = taskId
        currentObjective = room.objective
        lastRunObjective = room.objective
        if let mode = CouncilDiscussionMode(rawValue: room.modeRaw) { selectedMode = mode }
        participants = room.participants.map { $0.restored() }
        failedSpeakerIds = Set(room.failedSpeakerIds)
        activeSpeakerId = nil
        invitedSpeakerIds = Set(participants.filter { !$0.isHost }.map(\.id))
        messages = room.messages.map { $0.restored() }
        roomStateOverride = "历史议会 · 只读"
        activeSheet = nil
    }

    /// 退出只读重放,清空房间、恢复默认席位,准备开新议会。
    func startFreshRoom() {
        isReplay = false
        resetRoom()
        refreshSettingsBackedParticipants()
        roomStateOverride = nil
    }

    /// 把当前房间快照按 taskId 归档(供「最近讨论」重开)。在名册/新消息/消息完结/结束等
    /// 检查点调用;不在 per-token 的 updateMessage 上调用,避免每个 token 写一次文件。
    private func archiveCurrentRoom() {
        guard !isReplay, let taskId = currentTaskId, !messages.isEmpty else { return }
        archiveStore.save(persistedRoom(taskId: taskId))
    }

    func restartLastDiscussion() {
        let objective = lastRunObjective.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !objective.isEmpty, !isRunning else { return }
        resetRoom()
        pendingObjective = objective
        startPendingDiscussion(researchAllowed: lastResearchAllowed)
    }

    /// 以指定议题重开一场议会:清空当前画面(含只读重放),再用该议题重新开跑。
    /// 供长按用户消息的「以此为题重开」/「编辑重开」复用。
    func restart(withObjective objective: String) {
        let text = objective.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isReplay = false
        resetRoom()
        pendingObjective = text
        startPendingDiscussion(researchAllowed: sharedSettings.snapshot.enableWebSearch)
    }

    private func runDiscussion(
        objective: String,
        researchAllowed: Bool,
        discussionID: UUID
    ) async {
        guard activeDiscussionID == discussionID else { return }
        isRunning = true
        invitedSpeakerIds.removeAll()
        activeSheet = nil
        appendDivider(selectedMode.openingDivider)
        roomSettingsStore.bootstrapLegacySeatsIfNeeded(
            sharedSettings.savedCouncilSeats,
            currentModelId: currentModelId
        )
        refreshSettingsBackedParticipants()
        guard let currentModel = sharedSettings.snapshot.getCurrentChatModel(),
              let providerSetting = ChatProviderConfiguration.provider(
                  for: currentModel,
                  providers: sharedSettings.snapshot.providers
              ) else {
            appendMessage(
                kind: .system,
                author: "议会",
                body: ChatConfigurationIssue.missingProvider.message,
                systemImage: "exclamationmark.triangle",
                tint: AmberTheme.accentRed,
                subtitle: "配置阻塞",
                status: .failed
            )
            isRunning = false
            activeDiscussionID = nil
            discussionTask = nil
            roomStateOverride = "失败"
            persistTranscript()
            return
        }
        let request = IOSCouncilRoomRunRequest(
            objective: objective,
            mode: selectedMode.runMode,
            settings: roomSettingsStore.settings,
            currentModelId: currentModel.modelId,
            currentModel: currentModel,
            providerSetting: providerSetting,
            searchSettings: sharedSettings.snapshot,
            researchConsent: researchAllowed ? .allowed : .unavailable,
            dynamicSeatGeneration: roomSettingsStore.dynamicSeatGeneration
        )
        let summary = await runner.run(request: request, onEvent: { [weak self] event in
            guard let self, self.activeDiscussionID == discussionID else { return }
            self.handle(event)
        }, onAskUser: { [weak self] question in
            // 暂停议会，等用户回答。用户回答后恢复；跳过则返回 nil。
            guard let self,
                  self.activeDiscussionID == discussionID,
                  self.isRuntimeAttached,
                  self.isApplicationActive else { return nil }
            return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
                self.pendingAskUser = IOSCouncilAskUserState(
                    question: question,
                    continuation: continuation
                )
            }
        })
        guard activeDiscussionID == discussionID else { return }
        if currentTaskId == nil {
            currentTaskId = summary.taskId
        }
        finishStreamingMessages(as: summary.status == .completed ? .completed : .failed)
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        isRunning = false
        activeDiscussionID = nil
        discussionTask = nil
        roomStateOverride = summary.status == .completed ? "就绪" : summary.status.title
        updateDetail(status: roomStateOverride ?? "就绪")
        persistTranscript()
        archiveCurrentRoom()
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

    private var currentConfigurationIssue: ChatConfigurationIssue? {
        guard let model = sharedSettings.snapshot.getCurrentChatModel() else {
            return .missingModel
        }
        let provider = ChatProviderConfiguration.provider(
            for: model,
            providers: sharedSettings.snapshot.providers
        )
        return ChatProviderConfiguration.issue(for: model, provider: provider)
    }

    private func persistedRoom(taskId: String) -> CouncilPersistedRoom {
        CouncilPersistedRoom(
            taskId: taskId,
            objective: currentObjective,
            modeRaw: selectedMode.rawValue,
            statusRaw: roomStateOverride ?? "",
            failedSpeakerIds: Array(failedSpeakerIds),
            participants: participants.map(CouncilPersistedParticipant.init),
            messages: messages.map(CouncilPersistedMessage.init),
            updatedAtMs: Date().timeIntervalSince1970 * 1000
        )
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

    private func finishStreamingMessages(as status: CouncilMessageStatus) {
        for index in messages.indices where messages[index].status == .speaking {
            messages[index].status = status
        }
    }

    private func handle(_ event: IOSCouncilRoomEvent) {
        switch event {
        case .taskStarted(let id):
            currentTaskId = id
            persistTranscript()
            archiveCurrentRoom()
        case .state(let state):
            roomStateOverride = state
            updateDetail(status: state)
        case .roster(let speakers, let activeId, let failedIds):
            participants = speakers.map(CouncilParticipant.init(speaker:))
            activeSpeakerId = activeId
            failedSpeakerIds = failedIds
            invitedSpeakerIds = Set(speakers.filter { !$0.isHost }.map(\.id))
            updateDetail(status: roomStateOverride ?? selectedMode.runningState)
            archiveCurrentRoom()
        case .append(let event):
            appendMessage(event)
            archiveCurrentRoom()
        case .updateMessage(let id, let body, let status):
            let mapped = CouncilMessageStatus(status)
            updateMessage(id, body: body, status: mapped)
            // 仅在席位发言完结/失败时归档,流式中途(speaking)不写,避免逐 token 落盘。
            if mapped != .speaking { archiveCurrentRoom() }
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
            budgetSummary: "模式：\(selectedMode.title)\n最大席位：\(roomSettingsStore.settings.limits.maxSeats)\n默认轮数：\(roomSettingsStore.settings.limits.defaultRounds)\n单次模型超时：\(roomSettingsStore.settings.limits.seatTimeoutSeconds)s\n输出预算：\(roomSettingsStore.settings.limits.outputBudgetCharacters)\n提供商：\(sharedSettings.resolveCurrentProviderSetting()?.name ?? "未配置")\n主持模型：\(roomSettingsStore.settings.host.modelId.trimmedOr(currentModelId))",
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
        stopAndCheckpointActiveDiscussion()
        // 取消运行中的议会后,被 cancel 的 async 任务不会再走到末尾的 isRunning=false,
        // 这里显式复位,确保「重开」后输入框/发送键立刻回到就绪态。
        isRunning = false
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        failedSpeakerIds.removeAll()
        currentTaskId = nil
        roomStateOverride = nil
        currentObjective = ""
        // 重开 = 把画面清成真正空白(和首次进入议会一致:只剩「输入议题开始」占位),
        // 不再自动发一条「已重新开始」系统消息(画蛇添足)。
        messages = []
        // 重新开始即清空历史(存盘也同步为空白开场),避免退出后又载回旧 transcript。
        persistTranscript()
        refreshSettingsBackedParticipants()
    }

    private func stopAndCheckpointActiveDiscussion() {
        let shouldCheckpoint = activeDiscussionID != nil || isRunning
        stopActiveDiscussion()
        guard shouldCheckpoint else { return }

        finishStreamingMessages(as: .failed)
        activeSpeakerId = nil
        invitedSpeakerIds.removeAll()
        isRunning = false
        roomStateOverride = "已取消"
        updateDetail(status: "已取消")
        persistTranscript()
        archiveCurrentRoom()
    }

    private func stopActiveDiscussion() {
        discussionTask?.cancel()
        // The concrete streamer synchronously drains accepted FIFO chunks here.
        // Keep ownership valid until that exact tail has reached the current bubble.
        runner.cancel()
        activeDiscussionID = nil
        discussionTask = nil
        if var pending = pendingAskUser {
            pendingAskUser = nil
            pending.answerDraft = ""
            pending.resume()
        }
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

    var usesStreamingMarkdown: Bool {
        kind == .host || kind == .guest
    }

    var isStreamingMarkdown: Bool {
        usesStreamingMarkdown && status == .speaking
    }

    /// Every host/guest message enters through a `.speaking` runner event, so this
    /// remains true after completion and after archive restoration without adding a
    /// persistence field or changing the generation protocol.
    var hasEverStreamedMarkdown: Bool {
        usesStreamingMarkdown
    }

    var markdownRenderCacheNamespace: String {
        "council:\(id.uuidString)"
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
        case "speaking": self = .failed
        default: self = .completed
        }
    }
}

/// Codable 快照,用于把上一轮议会的 transcript 存盘,退出后再进默认载回。颜色用 hex
/// 保留(席位/主持的色彩在重载后仍准确);backgroundColor/foregroundColor/borderColor 由
/// kind+status 派生,无需存。
struct CouncilPersistedMessage: Codable, Equatable {
    let id: String
    let kind: String
    let author: String
    let body: String
    let systemImage: String
    let tintHex: String
    let subtitle: String?
    let status: String

    init(
        id: String,
        kind: String,
        author: String,
        body: String,
        systemImage: String,
        tintHex: String,
        subtitle: String?,
        status: String
    ) {
        self.id = id
        self.kind = kind
        self.author = author
        self.body = body
        self.systemImage = systemImage
        self.tintHex = tintHex
        self.subtitle = subtitle
        self.status = status
    }

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

/// UserDefaults 持久化当前房间快照。旧版只保存消息数组，读取时原样迁移。
enum CouncilTranscriptStore {
    private static let key = "app.amber.ios.councilTranscript.v1"

    static func save(_ room: CouncilPersistedRoom, defaults: UserDefaults) {
        if let data = try? JSONEncoder().encode(room) {
            defaults.set(data, forKey: key)
        }
    }

    static func load(defaults: UserDefaults) -> CouncilPersistedRoom? {
        guard let data = defaults.data(forKey: key) else { return nil }
        let decoder = JSONDecoder()
        if let room = try? decoder.decode(CouncilPersistedRoom.self, from: data) {
            return room
        }
        guard let messages = try? decoder.decode([CouncilPersistedMessage].self, from: data) else {
            return nil
        }
        return CouncilPersistedRoom(
            taskId: "",
            objective: "",
            modeRaw: CouncilDiscussionMode.freeChat.rawValue,
            statusRaw: "",
            failedSpeakerIds: [],
            participants: [],
            messages: messages,
            updatedAtMs: 0
        )
    }

    static func clear(defaults: UserDefaults) {
        defaults.removeObject(forKey: key)
    }
}

// MARK: - 历史议会归档(按 taskId 一场一文件,支持「最近讨论」重开成只读对话)

/// 单个席位/主持在重开时还原所需的展示信息。颜色用 hex 保留;派生色由 kind 重新计算。
struct CouncilPersistedParticipant: Codable, Equatable {
    let id: String
    let handle: String
    let displayName: String
    let roleDescription: String
    let shortLens: String
    let systemImage: String
    let tintHex: String
    let isHost: Bool
    let modelHint: String
    let modelId: String?

    init(_ participant: CouncilParticipant) {
        self.id = participant.id
        self.handle = participant.handle
        self.displayName = participant.displayName
        self.roleDescription = participant.roleDescription
        self.shortLens = participant.shortLens
        self.systemImage = participant.systemImage
        self.tintHex = participant.tint.councilHexString
        self.isHost = participant.isHost
        self.modelHint = participant.modelHint
        self.modelId = participant.modelId
    }

    func restored() -> CouncilParticipant {
        CouncilParticipant(
            id: id,
            handle: handle,
            displayName: displayName,
            roleDescription: roleDescription,
            shortLens: shortLens,
            systemImage: systemImage,
            tint: Color(councilHexString: tintHex),
            isHost: isHost,
            modelHint: modelHint,
            modelId: modelId
        )
    }
}

/// 一场议会的完整快照:消息流 + 席位名册 + 失败席位 + 议题/模式/状态。
/// 与单房间 `CouncilTranscriptStore`(只存当前 transcript)不同,这里按 taskId 归档每一场,
/// 用于从「最近讨论」重开任意历史议会,渲染成只读对话(深读式重开)。
struct CouncilPersistedRoom: Codable, Equatable {
    let taskId: String
    let objective: String
    let modeRaw: String
    let statusRaw: String
    let failedSpeakerIds: [String]
    let participants: [CouncilPersistedParticipant]
    let messages: [CouncilPersistedMessage]
    let updatedAtMs: Double
}

/// 文件持久化:每场议会一份 `Documents/council/<taskId>.json`。镜像 `IOSBoardPersistence`
/// 的「一 id 一文件」范式,避免把大段 transcript 塞进 UserDefaults(80 条上限),也让每场
/// 历史互不覆盖。
@MainActor
final class CouncilRoomArchiveStore {
    static let shared = CouncilRoomArchiveStore()

    private let directory: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        let base = baseDirectory
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.directory = base.appendingPathComponent("council", isDirectory: true)
    }

    func save(_ room: CouncilPersistedRoom) {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try encoder.encode(room)
            try data.write(to: fileURL(for: room.taskId), options: .atomic)
        } catch {
            // 归档失败不应打断议会;静默吞掉(下个检查点会再试)。
        }
    }

    func load(taskId: String) -> CouncilPersistedRoom? {
        let url = fileURL(for: taskId)
        guard fileManager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let room = try? decoder.decode(CouncilPersistedRoom.self, from: data) else {
            return nil
        }
        return room
    }

    func exists(taskId: String) -> Bool {
        fileManager.fileExists(atPath: fileURL(for: taskId).path)
    }

    func delete(taskId: String) {
        try? fileManager.removeItem(at: fileURL(for: taskId))
    }

    func markInterrupted(taskIds: [String]) {
        for taskId in taskIds {
            guard let room = load(taskId: taskId) else { continue }
            let messages = room.messages.map { message in
                guard message.status == "speaking" else { return message }
                return CouncilPersistedMessage(
                    id: message.id,
                    kind: message.kind,
                    author: message.author,
                    body: message.body,
                    systemImage: message.systemImage,
                    tintHex: message.tintHex,
                    subtitle: message.subtitle,
                    status: "failed"
                )
            }
            save(CouncilPersistedRoom(
                taskId: room.taskId,
                objective: room.objective,
                modeRaw: room.modeRaw,
                statusRaw: IOSAdvancedTaskStatus.interrupted.title,
                failedSpeakerIds: room.failedSpeakerIds,
                participants: room.participants,
                messages: messages,
                updatedAtMs: Date().timeIntervalSince1970 * 1000
            ))
        }
    }

    private func fileURL(for taskId: String) -> URL {
        let safe = taskId.replacingOccurrences(of: "/", with: "_")
        return directory.appendingPathComponent("\(safe).json", isDirectory: false)
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

// MARK: - 历史议会 sheet

/// 列出可重开的历史议会(仅含已归档快照的任务),点任意一场以只读方式重开。
private struct CouncilHistorySheet: View {
    let tasks: [IOSAdvancedTaskRecord]
    let activeTaskId: String?
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                AmberTheme.background.ignoresSafeArea()
                if tasks.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        AmberFormGroup {
                            ForEach(Array(tasks.enumerated()), id: \.element.id) { index, task in
                                Button {
                                    onSelect(task.id)
                                    dismiss()
                                } label: {
                                    row(task)
                                }
                                .buttonStyle(.plain)
                                if index < tasks.count - 1 {
                                    Divider()
                                        .overlay(AmberTheme.borderSoft)
                                        .padding(.leading, 14)
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                        .padding(.bottom, 24)
                    }
                    .scrollIndicators(.hidden)
                }
            }
            .navigationTitle("历史议会")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 30, weight: .light))
                .foregroundStyle(AmberTheme.muted2)
            Text("暂无可重开的历史议会")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)
            Text("完成一场议会后，这里会列出可重开的讨论。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted2)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 32)
    }

    private func row(_ task: IOSAdvancedTaskRecord) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon(for: task.status))
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor(for: task.status))
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(task.title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(task.status.title) · \(task.objective)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if task.id == activeTaskId {
                Image(systemName: "eye.fill")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accent)
            } else {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }

    private func icon(for status: IOSAdvancedTaskStatus) -> String {
        switch status {
        case .completed: "checkmark.seal.fill"
        case .failed, .timedOut, .interrupted: "exclamationmark.triangle.fill"
        case .cancelled: "xmark.circle.fill"
        default: "bubble.left.and.bubble.right.fill"
        }
    }

    private func iconColor(for status: IOSAdvancedTaskStatus) -> Color {
        switch status {
        case .completed: AmberTheme.accentGreen
        case .failed, .timedOut, .interrupted: AmberTheme.accentRed
        case .cancelled: AmberTheme.muted2
        default: AmberTheme.accent
        }
    }
}

#Preview {
    NavigationStack {
        CouncilChatRuntimeView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
