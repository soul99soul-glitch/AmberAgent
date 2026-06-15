import SwiftUI

struct CouncilView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var isMenuOpen = false
    @State private var isDetailOpen = false
    @State private var selectedTab: CouncilDetailTab = .members
    @State private var mode: CouncilMode = .debate
    @State private var draft = ""

    private let seats = CouncilFixtures.seats

    var body: some View {
        ZStack(alignment: .bottom) {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                timeline
            }

            composer

            if isMenuOpen {
                CouncilMenuScrim {
                    withAnimation(.snappy(duration: 0.16)) {
                        isMenuOpen = false
                    }
                }
                CouncilOverflowMenu(
                    mode: mode,
                    selectMode: { nextMode in
                        mode = nextMode
                        withAnimation(.snappy(duration: 0.16)) {
                            isMenuOpen = false
                        }
                    },
                    openSettings: {
                        withAnimation(.snappy(duration: 0.16)) {
                            isMenuOpen = false
                        }
                        router.navigate(to: .councilSettings)
                    }
                )
                .zIndex(21)
            }

            if isDetailOpen {
                CouncilDetailOverlay(
                    selectedTab: $selectedTab,
                    seats: seats,
                    close: {
                        withAnimation(.snappy(duration: 0.22)) {
                            isDetailOpen = false
                        }
                    }
                )
                .zIndex(30)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                    dismiss()
                }

                Spacer(minLength: 8)

                VStack(spacing: 2) {
                    Text("议会聊天")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Button {
                        withAnimation(.snappy(duration: 0.22)) {
                            isDetailOpen = true
                        }
                    } label: {
                        HStack(spacing: 5) {
                            CouncilLiveDot(color: AmberTheme.accentAmber)
                            Text("\(mode.statusText) · 主持 GPT · 8 位成员")
                                .lineLimit(1)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 10, weight: .semibold))
                                .opacity(0.55)
                        }
                        .font(.system(size: 11.5))
                        .foregroundStyle(AmberTheme.muted)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .frame(maxWidth: .infinity)

                Spacer(minLength: 8)

                AmberGlassCircleButton(systemImage: "ellipsis", accessibilityLabel: "主持操作与模式", size: 44, symbolSize: 19) {
                    withAnimation(.snappy(duration: 0.16)) {
                        isMenuOpen.toggle()
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)
        }
        .background(AmberTheme.background)
        .overlay(alignment: .bottom) {
            LinearGradient(
                colors: [AmberTheme.background, AmberTheme.background.opacity(0)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 14)
            .offset(y: 14)
            .allowsHitTesting(false)
        }
        .zIndex(10)
    }

    private var timeline: some View {
        ScrollView {
            VStack(spacing: 0) {
                CouncilUserMessage(text: "这个功能要不要做成一个独立页面？请多模型一起讨论。")
                CouncilRoundDivider(label: "EXPLORE · 开场")

                CouncilSeatMessage(
                    seat: seats[0],
                    badge: "主持",
                    model: "gpt-5.2",
                    text: "我来主持这轮讨论。先用 Explore 模式扩展信息面。DeepSeek，你先从推理和产品结构角度判断。",
                    handoff: "交给 @DeepSeek · 结构推理",
                    style: .host
                )

                CouncilSeatMessage(
                    seat: seats[1],
                    badge: "结构推理",
                    relation: "deepseek-v4 · 由 GPT 邀请",
                    text: "应该做成独立页面。它不是普通聊天的附属功能，而是一个多参与者协作空间，状态、成员、轮次都需要独立承载。"
                )

                CouncilSeatMessage(
                    seat: seats[0],
                    badge: "主持",
                    model: "gpt-5.2",
                    text: "GLM，接着 DeepSeek 的第二点，从中文用户的使用心智补充。",
                    handoff: "交给 @GLM · 中文心智",
                    style: .host
                )

                CouncilSeatMessage(
                    seat: seats[2],
                    badge: "中文心智",
                    relation: "续 DeepSeek 的第二点",
                    text: "DeepSeek 的判断成立。对中文用户来说，「群聊」「议会」比「工具调用」更容易理解，独立页面也更贴合这个心智模型。"
                )

                CouncilSeatMessage(
                    seat: seats[0],
                    badge: "主持",
                    model: "gpt-5.2",
                    text: "Gemini，从多模态和移动端交互角度给建议。",
                    handoff: "交给 @Gemini · 多模态",
                    style: .host
                )

                CouncilSeatMessage(
                    seat: seats[3],
                    badge: "多模态",
                    relation: "gemini-3-pro · 由 GPT 邀请",
                    text: "若未来支持图片、网页截图或文件，独立页面更适合呈现多模态上下文，附件、引用、来源都能在专属布局里展开。"
                )

                CouncilSystemEvent(text: "切换到 辩论模式 · DEBATE - 收敛判断、查盲点", isModeSwitch: true)
                CouncilRoundDivider(label: "DEBATE · 交叉回应")

                CouncilSeatMessage(
                    seat: seats[4],
                    badge: "挑战假设",
                    relation: "kimi-k2 · 辩论 · 质疑成本与失控",
                    text: "补充一个风险：如果让 guest 自动互相追问，成本和循环失控会变高，必须由 Host 限定轮次与发言权。"
                )

                CouncilSeatMessage(
                    seat: seats[5],
                    badge: "反方 · 失败模式",
                    relation: "反驳 Risk 之外的盲点",
                    text: "我反对一开始就做太自由的群聊。第一版应该保持 host-led，否则用户很难理解谁对最终答案负责。"
                )

                CouncilRoundDivider(label: "主持综合 · HOST SYNTHESIS")

                CouncilSeatMessage(
                    seat: seats[0],
                    badge: "主持",
                    model: "综合 · 有证据支撑",
                    text: "收到。综合各方证据收束：第一版做成受控群聊的独立页面。guest 可以互相回应，但发言权由 Host 调度。",
                    evidence: [
                        .init(kind: "支持", color: AmberTheme.accentGreen, text: "DeepSeek（结构）+ GLM（中文心智）+ Gemini（多模态）→ 独立承载成立。"),
                        .init(kind: "约束", color: AmberTheme.accentRed, text: "Risk + Opponent → 第一版保持 host-led，必须展示预算 / 轮次 / 停止控制。")
                    ],
                    style: .synthesis
                )

                CouncilSystemEvent(text: "主持已开放追问 · guests 可在本轮互相回应")

                CouncilSeatMessage(
                    seat: seats[6],
                    badge: "工程估算",
                    relation: "发言中… · 续主持结论",
                    text: "从实现看，受控群聊只要在 Host 侧加一个发言调度队列，guest 互答复用同一条消息流，改造量不大，独立页面反而更好拆",
                    isStreaming: true
                )
            }
            .padding(.top, 6)
            .padding(.bottom, 112)
        }
        .scrollIndicators(.hidden)
    }

    private var composer: some View {
        VStack(spacing: 0) {
            LinearGradient(
                colors: [AmberTheme.background.opacity(0), AmberTheme.background],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 22)
            .allowsHitTesting(false)

            HStack(spacing: 7) {
                Button {
                    draft = draft.isEmpty ? "@" : draft
                } label: {
                    Image(systemName: "at")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .frame(width: 34, height: 34)
                        .background(AmberTheme.surface2, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("提及成员")

                TextField("发消息给议会… 用 @ 指定成员", text: $draft)
                    .font(.system(size: 15))
                    .foregroundStyle(AmberTheme.foreground)
                    .textFieldStyle(.plain)

                Button {
                    draft = ""
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 34, height: 34)
                        .background(AmberTheme.accentRed, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("停止讨论")
            }
            .padding(.leading, 6)
            .padding(.trailing, 5)
            .padding(.vertical, 5)
            .amberGlass(cornerRadius: 24)
            .padding(.horizontal, 12)
            .padding(.bottom, 16)
        }
    }
}

private enum CouncilMode: String, CaseIterable, Identifiable {
    case explore
    case debate

    var id: String { rawValue }

    var title: String {
        switch self {
        case .explore: "自由群聊"
        case .debate: "辩论"
        }
    }

    var hint: String {
        switch self {
        case .explore: "扩展信息面"
        case .debate: "收敛、查盲点"
        }
    }

    var systemImage: String {
        switch self {
        case .explore: "plus.circle"
        case .debate: "hammer"
        }
    }

    var statusText: String {
        switch self {
        case .explore: "探索中"
        case .debate: "辩论中"
        }
    }
}

private enum CouncilDetailTab: String, CaseIterable, Identifiable {
    case members
    case turns
    case synthesis

    var id: String { rawValue }

    var title: String {
        switch self {
        case .members: "成员"
        case .turns: "原始消息"
        case .synthesis: "综合"
        }
    }
}

private enum CouncilMessageStyle {
    case normal
    case host
    case synthesis
}

private struct CouncilSeat: Identifiable {
    let id: String
    let initials: String
    let name: String
    let model: String
    let lens: String
    let tint: Color
    let tintBackground: Color
    let status: String
    var isHost = false
    var isLive = false
    var isOffline = false
}

private struct CouncilEvidence: Identifiable {
    let id = UUID()
    let kind: String
    let color: Color
    let text: String
}

private enum CouncilFixtures {
    static let seats: [CouncilSeat] = [
        .init(
            id: "gpt",
            initials: "G",
            name: "GPT",
            model: "gpt-5.2",
            lens: "主持调度",
            tint: .white,
            tintBackground: AmberTheme.accent,
            status: "主持",
            isHost: true
        ),
        .init(id: "deepseek", initials: "DS", name: "DeepSeek", model: "deepseek-v4", lens: "结构推理", tint: AmberTheme.accentIndigo, tintBackground: AmberTheme.accentIndigo.opacity(0.14), status: "已发言"),
        .init(id: "glm", initials: "GLM", name: "GLM", model: "glm-4.6", lens: "中文心智", tint: AmberTheme.accentCyan, tintBackground: AmberTheme.accentCyan.opacity(0.14), status: "已发言"),
        .init(id: "gemini", initials: "GM", name: "Gemini", model: "gemini-3-pro", lens: "多模态", tint: AmberTheme.accentGreen, tintBackground: AmberTheme.accentGreen.opacity(0.16), status: "已发言"),
        .init(id: "risk", initials: "险", name: "Risk", model: "kimi-k2", lens: "挑战假设", tint: AmberTheme.accentRed, tintBackground: AmberTheme.accentRed.opacity(0.13), status: "已发言"),
        .init(id: "opponent", initials: "反", name: "Opponent", model: "claude-haiku-4.5", lens: "反方 · 失败模式", tint: AmberTheme.accentAmber, tintBackground: AmberTheme.accentAmber.opacity(0.16), status: "已发言"),
        .init(id: "engineering", initials: "工", name: "Engineering", model: "codex · CLI", lens: "工程估算", tint: AmberTheme.muted, tintBackground: AmberTheme.muted.opacity(0.16), status: "发言中", isLive: true),
        .init(id: "claude", initials: "CL", name: "Claude", model: "claude-opus-4.8", lens: "架构审查", tint: AmberTheme.muted, tintBackground: AmberTheme.accentIndigo.opacity(0.10), status: "未发言", isOffline: true)
    ]
}

private struct CouncilUserMessage: View {
    let text: String

    var body: some View {
        HStack {
            Spacer(minLength: 48)
            Text(text)
                .font(.system(size: 14.5))
                .lineSpacing(2)
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(AmberTheme.accent, in: UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: 18, bottomTrailingRadius: 6, topTrailingRadius: 18, style: .continuous))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }
}

private struct CouncilRoundDivider: View {
    let label: String

    var body: some View {
        HStack(spacing: 10) {
            Rectangle()
                .fill(AmberTheme.border)
                .frame(height: 0.5)
            Text(label)
                .font(.system(size: 10.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .tracking(0.7)
                .lineLimit(1)
            Rectangle()
                .fill(AmberTheme.border)
                .frame(height: 0.5)
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 9)
    }
}

private struct CouncilSeatMessage: View {
    let seat: CouncilSeat
    var badge: String?
    var model: String?
    var relation: String?
    let text: String
    var handoff: String?
    var evidence: [CouncilEvidence] = []
    var style: CouncilMessageStyle = .normal
    var isStreaming = false

    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            CouncilAvatar(seat: seat, size: 30)
                .padding(.top, 1)

            VStack(alignment: .leading, spacing: 0) {
                meta

                if let relation {
                    Text(relation)
                        .font(.system(size: 10.5))
                        .foregroundStyle(seat.isLive ? AmberTheme.accentGreen : AmberTheme.muted)
                        .lineLimit(2)
                        .padding(.bottom, 4)
                }

                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .lastTextBaseline, spacing: 1) {
                        Text(text)
                            .font(.system(size: 14))
                            .lineSpacing(3)
                            .foregroundStyle(style == .normal ? AmberTheme.foreground2 : AmberTheme.foreground)
                            .fixedSize(horizontal: false, vertical: true)
                        if isStreaming {
                            CouncilStreamCaret()
                        }
                    }

                    if !evidence.isEmpty {
                        VStack(alignment: .leading, spacing: 5) {
                            ForEach(evidence) { item in
                                HStack(alignment: .top, spacing: 7) {
                                    Text("\(item.kind) ›")
                                        .font(.system(size: 12.5, weight: .bold))
                                        .foregroundStyle(item.color)
                                        .frame(width: 44, alignment: .leading)
                                    Text(item.text)
                                        .font(.system(size: 12.5))
                                        .lineSpacing(2)
                                        .foregroundStyle(AmberTheme.foreground2)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 13)
                .padding(.vertical, 9)
                .background(bubbleBackground, in: UnevenRoundedRectangle(topLeadingRadius: 5, bottomLeadingRadius: 14, bottomTrailingRadius: 14, topTrailingRadius: 14, style: .continuous))
                .overlay {
                    UnevenRoundedRectangle(topLeadingRadius: 5, bottomLeadingRadius: 14, bottomTrailingRadius: 14, topTrailingRadius: 14, style: .continuous)
                        .stroke(bubbleBorder, lineWidth: 0.5)
                }

                if let handoff {
                    Label(handoff, systemImage: "arrow.right")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private var meta: some View {
        HStack(spacing: 6) {
            if let badge {
                Text(badge)
                    .font(.system(size: seat.isHost ? 9.5 : 10, weight: .bold))
                    .foregroundStyle(seat.isHost ? AmberTheme.accent : AmberTheme.foreground2)
                    .padding(.horizontal, seat.isHost ? 6 : 7)
                    .padding(.vertical, seat.isHost ? 1 : 1.5)
                    .background(seat.isHost ? AmberTheme.accentTint : AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            }

            Text(seat.name)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(AmberTheme.foreground)

            if let model {
                Text(model)
                    .font(.system(size: 10, weight: style == .synthesis ? .bold : .regular, design: .monospaced))
                    .foregroundStyle(style == .synthesis ? AmberTheme.accent : AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
                    .padding(.horizontal, style == .synthesis ? 0 : 5)
                    .padding(.vertical, style == .synthesis ? 0 : 1)
                    .background(style == .synthesis ? Color.clear : AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            }
        }
        .padding(.bottom, 3)
    }

    private var bubbleBackground: Color {
        switch style {
        case .normal:
            AmberTheme.surface
        case .host:
            AmberTheme.accent.opacity(0.055)
        case .synthesis:
            AmberTheme.accent.opacity(0.07)
        }
    }

    private var bubbleBorder: Color {
        switch style {
        case .normal:
            AmberTheme.borderSoft
        case .host:
            AmberTheme.accent.opacity(0.18)
        case .synthesis:
            AmberTheme.accent.opacity(0.28)
        }
    }
}

private struct CouncilSystemEvent: View {
    let text: String
    var isModeSwitch = false

    var body: some View {
        HStack {
            Label(text, systemImage: isModeSwitch ? "hammer" : "arrow.triangle.2.circlepath")
                .font(.system(size: 11, weight: isModeSwitch ? .semibold : .regular))
                .foregroundStyle(isModeSwitch ? AmberTheme.accent : AmberTheme.muted)
                .padding(.horizontal, 13)
                .padding(.vertical, 4)
                .background(isModeSwitch ? AmberTheme.accentTint : AmberTheme.surface, in: Capsule())
                .overlay {
                    Capsule()
                        .stroke(isModeSwitch ? AmberTheme.accent.opacity(0.20) : AmberTheme.borderSoft, lineWidth: 0.5)
                }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, 9)
        .padding(.bottom, 4)
    }
}

private struct CouncilAvatar: View {
    let seat: CouncilSeat
    var size: CGFloat

    var body: some View {
        ZStack(alignment: .top) {
            Text(seat.initials)
                .font(.system(size: size <= 20 ? 8 : 11, weight: .bold, design: .monospaced))
                .foregroundStyle(seat.tint)
                .frame(width: size, height: size)
                .background(seat.tintBackground, in: Circle())

            if seat.isHost {
                Image(systemName: "crown.fill")
                    .font(.system(size: size <= 20 ? 7 : 9, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .offset(y: -5)
            }
        }
        .opacity(seat.isOffline ? 0.58 : 1)
    }
}

private struct CouncilLiveDot: View {
    let color: Color

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 6, height: 6)
            .shadow(color: color.opacity(0.55), radius: 4)
    }
}

private struct CouncilStreamCaret: View {
    @State private var isVisible = true

    var body: some View {
        RoundedRectangle(cornerRadius: 1)
            .fill(AmberTheme.accentAmber.opacity(isVisible ? 1 : 0.18))
            .frame(width: 6, height: 14)
            .offset(y: 2)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.85).repeatForever(autoreverses: true)) {
                    isVisible.toggle()
                }
            }
    }
}

private struct CouncilMenuScrim: View {
    let close: () -> Void

    var body: some View {
        Color.clear
            .contentShape(Rectangle())
            .ignoresSafeArea()
            .onTapGesture(perform: close)
            .zIndex(20)
    }
}

private struct CouncilOverflowMenu: View {
    let mode: CouncilMode
    let selectMode: (CouncilMode) -> Void
    let openSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("讨论模式")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 11)
                .padding(.top, 7)
                .padding(.bottom, 4)

            ForEach(CouncilMode.allCases) { item in
                Button {
                    selectMode(item)
                } label: {
                    HStack(spacing: 11) {
                        Image(systemName: item.systemImage)
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(mode == item ? AmberTheme.accent : AmberTheme.muted)
                            .frame(width: 20)
                        Text(item.title)
                            .font(.system(size: 14.5, weight: mode == item ? .semibold : .regular))
                            .foregroundStyle(mode == item ? AmberTheme.accent : AmberTheme.foreground)
                        Spacer()
                        Text(item.hint)
                            .font(.system(size: 11))
                            .foregroundStyle(mode == item ? AmberTheme.accent.opacity(0.7) : AmberTheme.muted2)
                    }
                    .padding(.horizontal, 11)
                    .padding(.vertical, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            Divider()
                .overlay(AmberTheme.borderSoft)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)

            Button(action: openSettings) {
                HStack(spacing: 11) {
                    Image(systemName: "person.3")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(width: 20)
                    Text("议会成员设置")
                        .font(.system(size: 14.5))
                        .foregroundStyle(AmberTheme.foreground)
                    Spacer()
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 9)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(5)
        .frame(width: 226)
        .background(AmberTheme.glassStrong, in: RoundedRectangle(cornerRadius: 15, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 15, style: .continuous)
                .stroke(.white.opacity(0.65), lineWidth: 0.5)
        }
        .shadow(color: .black.opacity(0.18), radius: 20, y: 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .padding(.top, 86)
        .padding(.trailing, 12)
        .transition(.scale(scale: 0.92, anchor: .topTrailing).combined(with: .opacity))
    }
}

private struct CouncilDetailOverlay: View {
    @Binding var selectedTab: CouncilDetailTab
    let seats: [CouncilSeat]
    let close: () -> Void

    var body: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.20)
                .ignoresSafeArea()
                .onTapGesture(perform: close)

            VStack(spacing: 0) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(AmberTheme.border)
                    .frame(width: 38, height: 5)
                    .padding(.top, 9)
                    .padding(.bottom, 4)

                VStack(alignment: .leading, spacing: 3) {
                    Text("讨论 · 是否做成独立页面")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text("辩论模式 · 主持 GPT · 8 位成员 · 第 2 / 4 轮 · 辩论中")
                        .font(.system(size: 13))
                        .lineSpacing(2)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 6)
                .padding(.bottom, 12)

                Divider()
                    .overlay(AmberTheme.borderSoft)

                HStack(spacing: 6) {
                    ForEach(CouncilDetailTab.allCases) { tab in
                        Button {
                            selectedTab = tab
                        } label: {
                            Text(tab.title)
                                .font(.system(size: 12.5, weight: .medium))
                                .foregroundStyle(selectedTab == tab ? .white : AmberTheme.muted)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 5)
                                .background(selectedTab == tab ? AmberTheme.accent : AmberTheme.surface, in: Capsule())
                                .overlay {
                                    Capsule()
                                        .stroke(selectedTab == tab ? AmberTheme.accent : AmberTheme.borderSoft, lineWidth: 0.5)
                                }
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 11)
                .padding(.bottom, 4)

                ScrollView {
                    tabBody
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 28)
                }
                .scrollIndicators(.hidden)
            }
            .frame(maxHeight: 600)
            .background(AmberTheme.background)
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: 22, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: 22, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 24, y: -8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    @ViewBuilder
    private var tabBody: some View {
        switch selectedTab {
        case .members:
            VStack(spacing: 0) {
                ForEach(Array(seats.enumerated()), id: \.element.id) { index, seat in
                    CouncilMemberRow(seat: seat)
                    if index < seats.count - 1 {
                        Divider()
                            .overlay(AmberTheme.borderSoft)
                    }
                }
            }
        case .turns:
            VStack(spacing: 0) {
                CouncilRawTurn(seat: seats[0], round: "R1 · 主持", text: "我来主持这轮讨论。DeepSeek，你先从推理和产品结构角度判断。→ 交给 @DeepSeek")
                CouncilRawTurn(seat: seats[1], round: "R1 · 由 GPT 邀请", text: "应该做成独立页面。它不是普通聊天的附属功能，而是一个多参与者协作空间，状态、成员、轮次都需独立承载。")
                CouncilRawTurn(seat: seats[2], round: "R1 · 回应 DeepSeek", text: "DeepSeek 的判断成立。对中文用户来说，「群聊」「议会」比「工具调用」更易理解。")
            }
        case .synthesis:
            VStack(alignment: .leading, spacing: 0) {
                Text("建议：第一版做成受控群聊的独立页面。guest 可互相回应，发言权由 Host 调度。")
                    .font(.system(size: 14))
                    .lineSpacing(3)
                    .foregroundStyle(AmberTheme.foreground)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.vertical, 6)

                CouncilSynthesisRow(kind: "支持", color: AmberTheme.accentGreen, text: "DeepSeek（结构）+ GLM（中文心智）+ Gemini（多模态）→ 独立承载成立。")
                CouncilSynthesisRow(kind: "约束", color: AmberTheme.accentRed, text: "Risk + Opponent → 保持 host-led，必须展示预算 / 轮次 / 停止控制。")
            }
        }
    }
}

private struct CouncilMemberRow: View {
    let seat: CouncilSeat

    var body: some View {
        HStack(spacing: 11) {
            CouncilAvatar(seat: seat, size: 32)
            VStack(alignment: .leading, spacing: 1) {
                Text(seat.isHost ? "主持 · \(seat.name)" : seat.name)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text("\(seat.model) · \(seat.lens)")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(seat.status)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(statusColor)
                .padding(.horizontal, 10)
                .padding(.vertical, 2.5)
                .background(statusBackground, in: Capsule())
        }
        .padding(.vertical, 11)
        .opacity(seat.isOffline ? 0.58 : 1)
    }

    private var statusColor: Color {
        if seat.isHost { return AmberTheme.accent }
        if seat.isLive { return AmberTheme.accentGreen }
        if seat.isOffline { return AmberTheme.muted2 }
        return AmberTheme.muted
    }

    private var statusBackground: Color {
        if seat.isHost { return AmberTheme.accentTint }
        if seat.isLive { return AmberTheme.accentGreen.opacity(0.10) }
        return AmberTheme.surface
    }
}

private struct CouncilRawTurn: View {
    let seat: CouncilSeat
    let round: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 7) {
                CouncilAvatar(seat: seat, size: 18)
                Text(seat.isHost ? "主持 \(seat.name)" : seat.name)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(seat.model)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted2)
                Spacer()
                Text(round)
                    .font(.system(size: 9.5, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted2)
            }
            Text(text)
                .font(.system(size: 13))
                .lineSpacing(3)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 11)
        .overlay(alignment: .bottom) {
            Divider()
                .overlay(AmberTheme.borderSoft)
        }
    }
}

private struct CouncilSynthesisRow: View {
    let kind: String
    let color: Color
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 9) {
            Text(kind)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(color)
                .frame(width: 34, alignment: .leading)
            Text(text)
                .font(.system(size: 13))
                .lineSpacing(3)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 7)
        .overlay(alignment: .top) {
            Divider()
                .overlay(AmberTheme.borderSoft)
        }
    }
}

#Preview {
    NavigationStack {
        CouncilView()
            .environment(RouterPath())
    }
}
