import SwiftUI

// 「安静的星核」活动岛：核（ThinkingOrb 六态）/ 壳（IslandEdgeGlow 辉光）/ 文（标题微光）
// 三层各一个运动系统。设计与验收见 docs/ACTIVITY_ISLAND_REDESIGN.md。

struct ChatActivityIslandState: Equatable {
    enum Kind: Equatable {
        case title
        case waiting
        case thinking
        case generating
        case tool
        case image
        case awaitingUser
    }

    let kind: Kind
    let title: String
    let detail: String?
    let systemImage: String
    let isActive: Bool
    let tint: ChatActivityIslandTint
    /// 仅工具态：与消息内 Tool part 的稳定 id 对齐，用于失败 terminalHold 匹配。
    let toolID: String?

    /// 仅包含真正参与视觉切换的字段。detail 只用于无障碍，不应触发胶囊重排或动画。
    var visualKey: String {
        [
            "\(kind)",
            title,
            systemImage,
            isActive ? "active" : "idle",
            "\(tint)"
        ].joined(separator: "|")
    }

    static func conversationTitle(_ title: String) -> ChatActivityIslandState {
        ChatActivityIslandState(
            kind: .title,
            title: title,
            detail: nil,
            systemImage: "text.bubble",
            isActive: false,
            tint: .neutral,
            toolID: nil
        )
    }

    static func activity(
        kind: Kind,
        title: String,
        detail: String? = nil,
        systemImage: String,
        tint: ChatActivityIslandTint,
        toolID: String? = nil
    ) -> ChatActivityIslandState {
        ChatActivityIslandState(
            kind: kind,
            title: title,
            detail: detail,
            systemImage: systemImage,
            isActive: true,
            tint: tint,
            toolID: toolID
        )
    }

    /// terminalHold 用的失败副本：失败事实必须出现在单行标题，原工具名留给无障碍摘要。
    func failedCopy() -> ChatActivityIslandState {
        ChatActivityIslandState(
            kind: kind,
            title: "未完成",
            detail: title,
            systemImage: systemImage,
            isActive: true,
            tint: .red,
            toolID: toolID
        )
    }
}

enum ChatActivityIslandTint: Equatable {
    case neutral
    case accent
    case amber
    case cyan
    case green
    case red
    case indigo

    var color: Color {
        switch self {
        case .neutral: AmberTheme.muted
        case .accent: AmberTheme.accent
        case .amber: AmberTheme.accentAmber
        case .cyan: AmberTheme.accentCyan
        case .green: AmberTheme.accentGreen
        case .red: AmberTheme.accentRed
        case .indigo: AmberTheme.accentIndigo
        }
    }

    /// 辉光 hue 样式的颜色源（十六进制，供纯值 spec 使用）。
    var glowHex: UInt {
        switch self {
        case .neutral: 0x6E6254
        case .accent: UInt(AmberThemeRuntime.shared.accentHex)
        case .amber: 0xD98324
        case .cyan: 0x2AA0BC
        case .green: 0x3DA35D
        case .red: 0xC8402F
        case .indigo: 0x5856D6
        }
    }
}

// MARK: - Pure mapping（单元测试直接打这里，不实例化视图）

enum ChatActivityIslandMapping {
    /// 六态全上岗：工具按语义映射到闲置的 searching/solving/shaping。
    static func orbState(kind: ChatActivityIslandState.Kind, systemImage: String) -> OrbState? {
        switch kind {
        case .waiting, .awaitingUser:
            return .listening
        case .thinking:
            return .working
        case .generating:
            return .composing
        case .image:
            return .shaping
        case .tool:
            if systemImage == "magnifyingglass" || systemImage.hasPrefix("globe") {
                return .searching
            }
            if systemImage == "photo.on.rectangle" {
                return .shaping
            }
            return .solving
        case .title:
            return nil
        }
    }

    /// 转速即状态语言；terminalHold 覆盖为静态红边光。
    static func glowSpec(
        for state: ChatActivityIslandState,
        terminalHold: Bool
    ) -> IslandGlowSpec? {
        if terminalHold {
            return .terminal(hex: ChatActivityIslandTint.red.glowHex)
        }
        switch state.kind {
        case .title:
            return nil
        case .waiting:
            return .spectral(rotationPeriod: 8, breathing: false)
        case .thinking:
            return .spectral(rotationPeriod: 14, breathing: false)
        case .generating:
            return .spectral(rotationPeriod: 20, breathing: true)
        case .tool, .image:
            return .hue(hex: state.tint.glowHex)
        case .awaitingUser:
            return .hue(hex: ChatActivityIslandTint.amber.glowHex)
        }
    }

    /// 词边界截断：超长时优先在 ≥60% 处的最近空白/标点截断，避免切出半词；
    /// 找不到边界则硬切（原行为）。截断后清掉尾随的空白与标点。
    static func compactText(_ raw: String, limit: Int) -> String {
        let compacted = raw
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard compacted.count > limit else { return compacted }
        let prefix = compacted.prefix(limit)
        let boundaryFloor = max(1, Int((Double(limit) * 0.6).rounded(.down)))
        let boundaryChars = Set<Character>([
            " ", "　", "，", "。", "、", "；", "：", ",", ".", ";", ":", "—", "-", "·", "/"
        ])
        var cutIndex: String.Index?
        var index = prefix.startIndex
        var position = 0
        while index < prefix.endIndex {
            if boundaryChars.contains(prefix[index]), position + 1 >= boundaryFloor {
                cutIndex = index
            }
            index = prefix.index(after: index)
            position += 1
        }
        let trailing = CharacterSet.whitespaces
            .union(CharacterSet(charactersIn: "，。、；：,.;:—-·/"))
        guard let cutIndex else {
            return String(prefix)
        }
        return String(prefix[prefix.startIndex..<cutIndex])
            .trimmingCharacters(in: trailing)
    }
}

// MARK: - Presentation（settle / terminalHold 的唯一权威）

enum ChatIslandPresentation: Equatable {
    case idle(ChatActivityIslandState)
    case active(ChatActivityIslandState)
    case settling(active: ChatActivityIslandState, fallback: ChatActivityIslandState, until: TimeInterval)
    case terminalHold(active: ChatActivityIslandState, fallback: ChatActivityIslandState, until: TimeInterval)

    /// 当前渲染用状态：停留期仍渲染被持有的 active 态。
    var displayedState: ChatActivityIslandState {
        switch self {
        case .idle(let state), .active(let state):
            state
        case .settling(let state, _, _), .terminalHold(let state, _, _):
            state
        }
    }

    var holdDeadline: TimeInterval? {
        switch self {
        case .settling(_, _, let until), .terminalHold(_, _, let until):
            until
        case .idle, .active:
            nil
        }
    }

    var isSettling: Bool {
        if case .settling = self { return true }
        return false
    }

    var isTerminalHold: Bool {
        if case .terminalHold = self { return true }
        return false
    }

    /// 停留期 orb 冻结（静帧），不再播放形变。
    var isFrozen: Bool {
        isSettling || isTerminalHold
    }
}

enum ChatIslandPresentationReducer {
    static let settleDuration: TimeInterval = 0.4
    static let terminalHoldDuration: TimeInterval = 2.0

    static func stateChanged(
        prev: ChatIslandPresentation,
        next: ChatActivityIslandState,
        failedToolID: String?,
        now: TimeInterval,
        reduceMotion: Bool
    ) -> ChatIslandPresentation {
        // 新活跃态永远立即接管，打断 settle/hold。
        if next.isActive {
            return .active(next)
        }
        switch prev {
        case .settling(let held, _, let until), .terminalHold(let held, _, let until):
            // 停留期间标题刷新（如会话标题更新）：保留截止时刻，仅更新回落态。
            if case .settling = prev {
                return .settling(active: held, fallback: next, until: until)
            }
            return .terminalHold(active: held, fallback: next, until: until)
        case .active(let state):
            // 刚展示的工具失败（含图片类）：红色静态边光停留 2s，再退回标题。
            if (state.kind == .tool || state.kind == .image),
               let toolID = state.toolID,
               toolID == failedToolID {
                return .terminalHold(
                    active: state.failedCopy(),
                    fallback: next,
                    until: now + terminalHoldDuration
                )
            }
            // 有 orb 的活跃态安静退场 0.4s；reduceMotion 直落。
            if !reduceMotion,
               ChatActivityIslandMapping.orbState(kind: state.kind, systemImage: state.systemImage) != nil {
                return .settling(active: state, fallback: next, until: now + settleDuration)
            }
            return .idle(next)
        case .idle:
            return .idle(next)
        }
    }

    static func timeout(prev: ChatIslandPresentation, now: TimeInterval) -> ChatIslandPresentation {
        switch prev {
        case .settling(_, let fallback, let until) where until <= now:
            return .idle(fallback)
        case .terminalHold(_, let fallback, let until) where until <= now:
            return .idle(fallback)
        default:
            return prev
        }
    }
}

// MARK: - View

struct ChatActivityIslandView: View {
    let presentation: ChatIslandPresentation
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage(IOSDisplayPreferenceKeys.activityIslandEdgeGlow) private var activityIslandEdgeGlow = false

    init(presentation: ChatIslandPresentation) {
        self.presentation = presentation
    }

    /// 兼容既有调用（wiring canary / 预览）：纯态直接包装，无停留行为。
    init(state: ChatActivityIslandState) {
        self.presentation = state.isActive ? .active(state) : .idle(state)
    }

    private var state: ChatActivityIslandState {
        presentation.displayedState
    }

    private var glintActive: Bool {
        guard state.isActive, !presentation.isFrozen, !reduceMotion else { return false }
        switch state.kind {
        case .waiting, .thinking, .generating:
            return true
        case .title, .tool, .image, .awaitingUser:
            return false
        }
    }

    var body: some View {
        islandContent
            .padding(.horizontal, 13)
            .frame(height: 40)
            .frame(maxWidth: 268)
            .fixedSize(horizontal: true, vertical: false)
            .background { glowUnderlay }
            .modifier(ChatActivityIslandGlass())
            .contentShape(Capsule())
            .animation(
                reduceMotion ? nil : .easeInOut(duration: 0.22),
                value: state.visualKey
            )
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(accessibilitySummary))
    }

    private var accessibilitySummary: String {
        if let detail = state.detail, !detail.isEmpty {
            "\(state.title)，\(detail)"
        } else {
            state.title
        }
    }

    @ViewBuilder
    private var glowUnderlay: some View {
        if activityIslandEdgeGlow,
           let spec = ChatActivityIslandMapping.glowSpec(
            for: state,
            terminalHold: presentation.isTerminalHold
        ) {
            IslandEdgeGlowView(
                spec: spec,
                isPaused: presentation.isFrozen
            )
                .padding(-IslandGlowCanvasView.canvasMargin)
                .allowsHitTesting(false)
        }
    }

    private var islandContent: some View {
        HStack(spacing: 9) {
            if let orb = ChatActivityIslandMapping.orbState(
                kind: state.kind,
                systemImage: state.systemImage
            ) {
                // awaitingUser 刻意冻结：核停下来等你，是全岛唯一「不动」的活跃态。
                ThinkingOrbView(
                    state: orb,
                    size: 24,
                    preset: .small,
                    paused: presentation.isFrozen || state.kind == .awaitingUser
                )
                .frame(width: 24, height: 24)
                .transition(.opacity)
            }

            Text(state.title)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .contentTransition(.opacity)
                .modifier(IslandTitleGlint(isActive: glintActive))
        }
    }
}

// MARK: - Glass（两行签名被 IOSSettingsWiringTests 源断言锁定，勿改写法）

private struct ChatActivityIslandGlass: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .glassEffect(.regular, in: Capsule())
        } else {
            content
                .background {
                    Capsule().fill(.ultraThinMaterial)
                        .overlay {
                            Capsule()
                                .stroke(AmberTheme.border.opacity(0.28), lineWidth: 0.5)
                        }
                }
                .shadow(color: .black.opacity(0.06), radius: 12, y: 4)
        }
    }
}

// MARK: - Title glint（生成微光：只在 AI 三态挂载，终态立即摘除）

private struct IslandTitleGlint: ViewModifier {
    let isActive: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if isActive {
            content.overlay {
                GeometryReader { proxy in
                    let bandWidth = max(proxy.size.width * 0.3, 14)
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0),
                            .init(color: .white.opacity(0.55), location: 0.5),
                            .init(color: .clear, location: 1)
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: bandWidth)
                    .phaseAnimator([false, true]) { band, phase in
                        band.offset(x: phase ? proxy.size.width + bandWidth : -bandWidth)
                    } animation: { _ in
                        .linear(duration: 2.4)
                    }
                }
                .mask(content)
                .allowsHitTesting(false)
            }
        } else {
            content
        }
    }
}
