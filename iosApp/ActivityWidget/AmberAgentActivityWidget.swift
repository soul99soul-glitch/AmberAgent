import ActivityKit
import SwiftUI
import WidgetKit

@main
struct AmberAgentActivityWidgetBundle: WidgetBundle {
    var body: some Widget {
        AmberAgentActivityWidget()
    }
}

struct AmberAgentActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AgentActivityAttributes.self) { context in
            LockScreenAgentActivityView(
                attributes: context.attributes,
                state: context.state,
                isStale: context.isStale
            )
            .activitySystemActionForegroundColor(.white)
            .widgetURL(
                context.attributes.destinationURL(
                    for: context.state.presentation.action
                )
            )
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(alignment: .center, spacing: 12) {
                        AgentActivityIslandOrb(
                            presentation: context.state.presentation,
                            isStale: context.isStale,
                            size: 40,
                            animates: true
                        )
                        .accessibilityHidden(true)

                        AgentActivityIslandHeadline(
                            conversationTitle: context.attributes.conversationTitle,
                            presentation: context.state.presentation,
                            startedAt: context.attributes.startedAt,
                            updatedAt: context.state.updatedAt,
                            isStale: context.isStale
                        )
                        .offset(y: 1)
                    }
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                }
            } compactLeading: {
                AgentActivityIslandOrb(
                    presentation: context.state.presentation,
                    isStale: context.isStale,
                    size: 20,
                    animates: false
                )
                .accessibilityHidden(true)
            } compactTrailing: {
                AgentActivityCompactStatus(
                    presentation: context.state.presentation,
                    isStale: context.isStale
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    agentActivityIslandAccessibilityLabel(
                        conversationTitle: context.attributes.conversationTitle,
                        presentation: context.state.presentation,
                        isStale: context.isStale
                    )
                )
            } minimal: {
                AgentActivityIslandOrb(
                    presentation: context.state.presentation,
                    isStale: context.isStale,
                    size: 17,
                    animates: false
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    agentActivityIslandAccessibilityLabel(
                        conversationTitle: context.attributes.conversationTitle,
                        presentation: context.state.presentation,
                        isStale: context.isStale
                    )
                )
            }
            .widgetURL(
                context.attributes.destinationURL(
                    for: context.state.presentation.action
                )
            )
            .keylineTint(.white.opacity(0.12))
        }
    }
}

private func agentActivityHeadlineText(
    conversationTitle: String?,
    presentation: AgentActivityPresentation,
    isStale: Bool
) -> (title: String, subtitle: String?) {
    let stageTitle = presentation.displayStage(isStale: isStale).title
    if let conversationTitle, !conversationTitle.isEmpty {
        return (conversationTitle, stageTitle)
    }
    return (
        stageTitle,
        presentation.kind == .response ? nil : presentation.kind.title
    )
}

private func agentActivityIslandAccessibilityLabel(
    conversationTitle: String?,
    presentation: AgentActivityPresentation,
    isStale: Bool
) -> String {
    let headline = agentActivityHeadlineText(
        conversationTitle: conversationTitle,
        presentation: presentation,
        isStale: isStale
    )
    if let subtitle = headline.subtitle {
        return "\(headline.title)，\(subtitle)"
    }
    return headline.title
}

private struct AgentActivityCompactStatus: View {
    let presentation: AgentActivityPresentation
    let isStale: Bool

    private var stage: AgentActivityStage {
        presentation.displayStage(isStale: isStale)
    }

    var body: some View {
        Text(stage.compactTitle)
            .font(.system(size: 11, weight: .semibold))
            .tracking(0.22)
            .lineLimit(1)
            .truncationMode(.tail)
            .minimumScaleFactor(0.85)
            .frame(height: 20, alignment: .center)
            .frame(maxWidth: 56, alignment: .trailing)
            .modifier(AgentActivityStatusGlint(
                isActive: presentation.displayPhase(isStale: isStale) == .running,
                trigger: stage.rawValue,
                baseOpacity: 0.9,
                peakOpacity: 1.0
            ))
    }
}

private struct AgentActivityIslandHeadline: View {
    let conversationTitle: String?
    let presentation: AgentActivityPresentation
    let startedAt: Date
    let updatedAt: Date
    let isStale: Bool

    private var headline: (title: String, subtitle: String?) {
        agentActivityHeadlineText(
            conversationTitle: conversationTitle,
            presentation: presentation,
            isStale: isStale
        )
    }

    private var stage: AgentActivityStage {
        presentation.displayStage(isStale: isStale)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(headline.title)
                    .font(.system(size: 15, weight: .semibold))
                    .tracking(-0.15)
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer(minLength: 8)

                AgentActivityIslandElapsedTimer(
                    startedAt: startedAt,
                    presentation: presentation,
                    updatedAt: updatedAt
                )
            }

            if let subtitle = headline.subtitle {
                Text(subtitle)
                    .font(.system(size: 11, weight: .medium))
                    .tracking(0.22)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .modifier(AgentActivityStatusGlint(
                        isActive: presentation.displayPhase(isStale: isStale) == .running,
                        trigger: stage.rawValue,
                        baseOpacity: 0.55,
                        peakOpacity: 0.9
                    ))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(agentActivityIslandAccessibilityLabel(
            conversationTitle: conversationTitle,
            presentation: presentation,
            isStale: isStale
        ))
    }
}

private struct AgentActivityIslandElapsedTimer: View {
    let startedAt: Date
    let presentation: AgentActivityPresentation
    let updatedAt: Date

    private var frozenEndDate: Date? {
        AgentActivityElapsedTimePolicy.frozenEndDate(
            for: presentation.phase,
            updatedAt: updatedAt
        )
    }

    var body: some View {
        Group {
            if let frozenEndDate {
                Text(Self.elapsedText(from: startedAt, to: frozenEndDate))
            } else {
                Text(startedAt, style: .timer)
            }
        }
            .font(.system(size: 12, weight: .medium))
            .tracking(0.24)
            .monospacedDigit()
            .foregroundStyle(.white.opacity(0.55))
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
    }

    private static func elapsedText(from startedAt: Date, to endedAt: Date) -> String {
        let elapsed = max(0, Int(endedAt.timeIntervalSince(startedAt)))
        let hours = elapsed / 3_600
        let minutes = (elapsed % 3_600) / 60
        let seconds = elapsed % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

/// WidgetKit caps Live Activity animations at two seconds. This keeps the Chat
/// title glint's 30% text mask while playing one sweep per status update.
private struct AgentActivityStatusGlint: ViewModifier {
    let isActive: Bool
    let trigger: String
    let baseOpacity: Double
    let peakOpacity: Double

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced

    private var overlayOpacity: Double {
        (peakOpacity - baseOpacity) / (1 - baseOpacity)
    }

    @ViewBuilder
    func body(content: Content) -> some View {
        if isActive, !reduceMotion, !isLuminanceReduced {
            content
                .foregroundStyle(.white.opacity(baseOpacity))
                .overlay {
                    GeometryReader { proxy in
                        let bandWidth = max(proxy.size.width * 0.30, 14)
                        LinearGradient(
                            stops: [
                                .init(color: .clear, location: 0),
                                .init(color: .white.opacity(overlayOpacity), location: 0.5),
                                .init(color: .clear, location: 1)
                            ],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: bandWidth)
                        .phaseAnimator([false, true], trigger: trigger) { band, phase in
                            band.offset(x: phase ? proxy.size.width + bandWidth : -bandWidth)
                        } animation: { _ in
                            .linear(duration: 2.0)
                        }
                    }
                    .mask(content.foregroundStyle(.white))
                    .allowsHitTesting(false)
                }
        } else {
            content.foregroundStyle(.white.opacity(baseOpacity))
        }
    }
}

/// 展开态主标题。iOS 27 Siri 原则：展开必须回答“哪个对话在跑什么”，
/// 而不是重复 compact 已经说的“正在生成”。会话标题做主标题，阶段做副标题。
private struct AgentActivityHeadline: View {
    let conversationTitle: String?
    let presentation: AgentActivityPresentation
    let startedAt: Date
    let updatedAt: Date
    let isStale: Bool

    private var headline: (title: String, subtitle: String?) {
        agentActivityHeadlineText(
            conversationTitle: conversationTitle,
            presentation: presentation,
            isStale: isStale
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(headline.title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer(minLength: 8)

                AgentActivityIslandElapsedTimer(
                    startedAt: startedAt,
                    presentation: presentation,
                    updatedAt: updatedAt
                )
            }

            if let subtitle = headline.subtitle {
                Text(subtitle)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.58))
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 锁屏补充区只承载真实进度和必须显式处理的动作。
private struct AgentActivityExpandedFooter: View {
    let attributes: AgentActivityAttributes
    let state: AgentActivityAttributes.ContentState
    let isStale: Bool

    private var displayPhase: AgentActivityPhase {
        state.presentation.displayPhase(isStale: isStale)
    }

    private var hasContent: Bool {
        (displayPhase == .running && state.presentation.progressFraction != nil)
            || (displayPhase == .running && state.presentation.metric.detailText != nil)
            || (state.presentation.action?.showsLockScreenLabel == true
                && attributes.destinationURL(for: state.presentation.action) != nil)
    }

    var body: some View {
        if hasContent {
            VStack(alignment: .leading, spacing: 6) {
                if displayPhase == .running,
                   let progress = state.presentation.progressFraction {
                    ProgressView(value: progress)
                        .progressViewStyle(.linear)
                        .tint(displayPhase.widgetColor)
                }

                HStack(spacing: 10) {
                    if displayPhase == .running,
                       let detail = state.presentation.metric.detailText {
                        Text(detail)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.58))
                            .lineLimit(1)
                    }

                    Spacer(minLength: 8)

                    if let action = state.presentation.action,
                       action.showsLockScreenLabel,
                       attributes.destinationURL(for: action) != nil {
                        Label(action.title, systemImage: "arrow.up.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.amberAccent)
                    }
                }
            }
            .padding(.bottom, 2)
        }
    }
}

private struct LockScreenAgentActivityView: View {
    let attributes: AgentActivityAttributes
    let state: AgentActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AgentActivityGlyph(
                presentation: state.presentation,
                isStale: isStale,
                size: 34
            )
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 8) {
                AgentActivityHeadline(
                    conversationTitle: attributes.conversationTitle,
                    presentation: state.presentation,
                    startedAt: attributes.startedAt,
                    updatedAt: state.updatedAt,
                    isStale: isStale
                )

                AgentActivityExpandedFooter(
                    attributes: attributes,
                    state: state,
                    isStale: isStale
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

/// 展开态运行中复用 Chat 的六态点阵；compact/minimal 与非运行相位使用明确的
/// 单色语义图标。动效不接 token 回调，也不创建第二套 ActivityKit 更新时钟。
private struct AgentActivityIslandOrb: View {
    let presentation: AgentActivityPresentation
    let isStale: Bool
    let size: CGFloat
    let animates: Bool

    private var displayPhase: AgentActivityPhase {
        presentation.displayPhase(isStale: isStale)
    }

    private var animationTrigger: String {
        let phase = presentation.displayPhase(isStale: isStale)
        let stage = presentation.displayStage(isStale: isStale)
        return "\(phase.rawValue):\(stage.rawValue)"
    }

    var body: some View {
        Group {
            if displayPhase == .running, animates {
                AgentActivityAnimatedOrb(
                    state: AgentActivityIslandOrbMapping.state(
                        for: presentation.displayStage(isStale: isStale)
                    ),
                    size: size,
                    animates: true,
                    animationTrigger: animationTrigger
                )
            } else {
                Image(systemName: presentation.displaySymbolName(isStale: isStale))
                    .font(.system(size: size * 0.46, weight: .semibold))
                    .foregroundStyle(.white.opacity(displayPhase == .running ? 0.9 : 0.72))
            }
        }
        .frame(width: size, height: size)
    }
}

private enum AgentActivityIslandOrbMapping {
    static func state(for stage: AgentActivityStage) -> OrbState {
        switch stage {
        case .preparing, .waitingForConfirmation, .reconnecting, .stale:
            .listening
        case .thinking:
            .working
        case .generating:
            .composing
        case .searching, .readingSources, .readingWeb:
            .searching
        case .generatingImage:
            .shaping
        case .readingDocument, .updatingMemory, .runningTool, .organizing:
            .solving
        case .completed, .failed, .cancelled:
            .working
        }
    }
}

private struct AgentActivityGlyph: View {
    let presentation: AgentActivityPresentation
    let isStale: Bool
    let size: CGFloat

    private var displayPhase: AgentActivityPhase {
        presentation.displayPhase(isStale: isStale)
    }

    private var animationTrigger: String {
        let stage = presentation.displayStage(isStale: isStale)
        return "\(displayPhase.rawValue):\(stage.rawValue)"
    }

    var body: some View {
        ZStack {
            if displayPhase == .running,
               let progress = presentation.progressFraction {
                Circle()
                    .stroke(.white.opacity(0.16), lineWidth: max(1, size * 0.05))
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(
                        Color.amberAccent,
                        style: StrokeStyle(
                            lineWidth: max(1.2, size * 0.07),
                            lineCap: .round
                        )
                    )
                    .rotationEffect(.degrees(-90))
            }

            glyphContent
        }
        .frame(width: size, height: size)
    }

    /// 运行中复用对应星核动效；其余相位保留 SF Symbol。
    @ViewBuilder
    private var glyphContent: some View {
        if displayPhase == .running {
            AgentActivityAnimatedOrb(
                state: AgentActivityIslandOrbMapping.state(for: presentation.stage),
                size: size * 0.85,
                animates: true,
                animationTrigger: animationTrigger
            )
        } else {
            Image(systemName: presentation.displaySymbolName(isStale: isStale))
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(displayPhase.widgetColor)
        }
    }
}

private struct AgentActivityAnimatedOrb: View {
    let orbState: OrbState
    let size: CGFloat
    let animates: Bool
    let animationTrigger: String

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced

    init(
        state: OrbState,
        size: CGFloat,
        animates: Bool,
        animationTrigger: String
    ) {
        orbState = state
        self.size = size
        self.animates = animates
        self.animationTrigger = animationTrigger
    }

    @ViewBuilder
    var body: some View {
        if animates, !reduceMotion, !isLuminanceReduced {
            AgentActivityOrbFrame(
                orbState: orbState,
                size: size,
                phase: Double(AgentActivityOrbFrameCache.restingPhase)
            )
                .keyframeAnimator(
                    initialValue: Double(AgentActivityOrbFrameCache.restingPhase),
                    trigger: animationTrigger
                ) { _, phase in
                    AgentActivityOrbFrame(
                        orbState: orbState,
                        size: size,
                        phase: phase
                    )
                } keyframes: { initialPhase in
                    LinearKeyframe(
                        initialPhase + Double(AgentActivityOrbFrameCache.phases.count),
                        duration: AgentActivityOrbAnimationTiming.duration(
                            speed: orbResolvePreset(orbState, .small).speed
                        )
                    )
                }
        } else {
            AgentActivityOrbFrame(
                orbState: orbState,
                size: size,
                phase: Double(AgentActivityOrbFrameCache.restingPhase)
            )
        }
    }
}

private struct AgentActivityOrbFrame: View {
    let orbState: OrbState
    let size: CGFloat
    let phase: Double

    var body: some View {
        let phaseCount = AgentActivityOrbFrameCache.phases.count
        let roundedPhase = Int(phase.rounded())
        let normalizedPhase = (roundedPhase % phaseCount + phaseCount) % phaseCount
        Image(uiImage: AgentActivityOrbFrameCache.frame(
            state: orbState,
            size: size,
            phase: normalizedPhase
        ))
        .resizable()
        .frame(width: size, height: size)
    }
}

@MainActor
private enum AgentActivityOrbFrameCache {
    static let phases = Array(0..<16)
    static let restingPhase = 0

    private static var cache: [String: UIImage] = [:]

    static func frame(state: OrbState, size: CGFloat, phase: Int) -> UIImage {
        let normalizedPhase = phase % phases.count
        let key = "\(state.rawValue)-\(Int(size.rounded()))-\(normalizedPhase)"
        if let cached = cache[key] { return cached }
        let resolved = orbResolvePreset(state, .small)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format)
        let image = renderer.image { context in
            let progress = Double(normalizedPhase) / Double(phases.count)
            orbDraw(
                resolved.mode,
                context.cgContext,
                size: Double(size),
                t: progress * 2 * .pi,
                dark: true,
                opts: resolved.opts
            )
        }
        cache[key] = image
        return image
    }
}

private extension Color {
    static let amberAccent = Color(red: 1.0, green: 0.66, blue: 0.28)
}

private extension AgentActivityPhase {
    var widgetColor: Color {
        switch self {
        case .running:
            .amberAccent
        case .reconnecting, .waitingForUser:
            .orange
        case .stale:
            .yellow
        case .completed:
            .green
        case .failed, .cancelled:
            .red
        }
    }
}

#if DEBUG
private let previewAttributes = AgentActivityAttributes(
    runId: "preview-run",
    conversationId: "01234567-89ab-cdef-0123-456789abcdef",
    startedAt: .now.addingTimeInterval(-125),
    conversationTitle: "帮我写一首关于春天的诗"
)

#Preview("Lock Screen", as: .content, using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(
            kind: .document,
            completed: 12,
            total: 30,
            unit: .item
        ),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}

#Preview("Dynamic Island Compact", as: .dynamicIsland(.compact), using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(
            kind: .document,
            completed: 12,
            total: 30,
            unit: .item
        ),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}

#Preview("Dynamic Island Expanded", as: .dynamicIsland(.expanded), using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(
            kind: .document,
            completed: 12,
            total: 30,
            unit: .item
        ),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}

#Preview("Dynamic Island Minimal", as: .dynamicIsland(.minimal), using: previewAttributes) {
    AmberAgentActivityWidget()
} contentStates: {
    AgentActivityAttributes.ContentState(presentation: .defaultRunning, updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: .measurablePreview(
            kind: .document,
            completed: 12,
            total: 30,
            unit: .item
        ),
        updatedAt: .now
    )
    AgentActivityAttributes.ContentState(presentation: .waitingForUser(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .reconnecting(), updatedAt: .now)
    AgentActivityAttributes.ContentState(
        presentation: AgentActivityPresentation(
            kind: .workflow,
            phase: .stale,
            stage: .stale,
            action: .openTask
        ),
        updatedAt: .now.addingTimeInterval(-300)
    )
    AgentActivityAttributes.ContentState(presentation: .completed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .failed(), updatedAt: .now)
    AgentActivityAttributes.ContentState(presentation: .cancelled(), updatedAt: .now)
}
#endif
