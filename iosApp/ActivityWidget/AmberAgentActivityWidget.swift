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
                DynamicIslandExpandedRegion(.leading) {
                    AgentActivityGlyph(
                        presentation: context.state.presentation,
                        isStale: context.isStale,
                        size: 32
                    )
                    .accessibilityHidden(true)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    AgentActivityExpandedFact(
                        presentation: context.state.presentation,
                        startedAt: context.attributes.startedAt,
                        isStale: context.isStale
                    )
                }
                DynamicIslandExpandedRegion(.center) {
                    AgentActivityHeadline(
                        conversationTitle: context.attributes.conversationTitle,
                        presentation: context.state.presentation,
                        isStale: context.isStale
                    )
                }
                DynamicIslandExpandedRegion(.bottom) {
                    AgentActivityExpandedFooter(
                        attributes: context.attributes,
                        state: context.state,
                        isStale: context.isStale
                    )
                }
            } compactLeading: {
                AgentActivityGlyph(
                    presentation: context.state.presentation,
                    isStale: context.isStale,
                    size: 20
                )
                .accessibilityHidden(true)
            } compactTrailing: {
                AgentActivityCompactStatus(
                    presentation: context.state.presentation,
                    isStale: context.isStale
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    context.state.presentation.accessibilitySummary(
                        isStale: context.isStale
                    )
                )
            } minimal: {
                AgentActivityGlyph(
                    presentation: context.state.presentation,
                    isStale: context.isStale,
                    size: 17
                )
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    context.state.presentation.accessibilitySummary(
                        isStale: context.isStale
                    )
                )
            }
            .widgetURL(
                context.attributes.destinationURL(
                    for: context.state.presentation.action
                )
            )
            .keylineTint(.amberAccent)
        }
    }
}

private struct AgentActivityCompactStatus: View {
    let presentation: AgentActivityPresentation
    let isStale: Bool

    var body: some View {
        Text(presentation.displayStage(isStale: isStale).compactTitle)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.white.opacity(0.9))
            .lineLimit(1)
            .minimumScaleFactor(0.72)
            .frame(maxWidth: 56, alignment: .trailing)
    }
}

/// 展开态主标题。iOS 27 Siri 原则：展开必须回答“哪个对话在跑什么”，
/// 而不是重复 compact 已经说的“正在生成”。会话标题做主标题，阶段做副标题。
private struct AgentActivityHeadline: View {
    let conversationTitle: String?
    let presentation: AgentActivityPresentation
    let isStale: Bool

    private var stageTitle: String {
        presentation.displayStage(isStale: isStale).title
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            if let conversationTitle, !conversationTitle.isEmpty {
                Text(conversationTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                Text(stageTitle)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.white.opacity(0.58))
                    .lineLimit(1)
            } else {
                // 无标题（工具活动等）：回退到阶段做主标题
                Text(stageTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                if presentation.kind != .response {
                    Text(presentation.kind.title)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.white.opacity(0.58))
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct AgentActivityExpandedFact: View {
    let presentation: AgentActivityPresentation
    let startedAt: Date
    let isStale: Bool

    var body: some View {
        Group {
            if let fact = presentation.priorityFact(isStale: isStale) {
                Text(fact)
            } else {
                Text(startedAt, style: .timer)
                    .monospacedDigit()
            }
        }
        .font(.caption.weight(.semibold))
        .foregroundStyle(.white.opacity(0.88))
        .lineLimit(1)
        .minimumScaleFactor(0.72)
    }
}

/// 展开态底部。只在有实质信息时显示：进度条、度量明细、可执行动作。
/// 不重复 trailing 已经显示的计时器。
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
            || (state.presentation.action != nil
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
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                AgentActivityGlyph(
                    presentation: state.presentation,
                    isStale: isStale,
                    size: 34
                )
                .accessibilityHidden(true)

                AgentActivityHeadline(
                    conversationTitle: attributes.conversationTitle,
                    presentation: state.presentation,
                    isStale: isStale
                )

                Spacer(minLength: 8)

                AgentActivityExpandedFact(
                    presentation: state.presentation,
                    startedAt: attributes.startedAt,
                    isStale: isStale
                )
            }

            AgentActivityExpandedFooter(
                attributes: attributes,
                state: state,
                isStale: isStale
            )
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

private struct AgentActivityGlyph: View {
    let presentation: AgentActivityPresentation
    let isStale: Bool
    let size: CGFloat

    private var displayPhase: AgentActivityPhase {
        presentation.displayPhase(isStale: isStale)
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

    /// 运行中用星核静帧表示活跃；其余相位保留 SF Symbol。
    /// 系统岛是 glance surface，不做多帧动画——系统会限频，静帧已足够传达“还在跑”。
    @ViewBuilder
    private var glyphContent: some View {
        if displayPhase == .running {
            AgentActivityStaticOrb(stage: presentation.stage, size: size * 0.85)
        } else {
            Image(systemName: presentation.displaySymbolName(isStale: isStale))
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(displayPhase.widgetColor)
        }
    }
}

/// 单帧星核标识。纯函数渲染，按 stage/state 缓存，零刷新预算。
private struct AgentActivityStaticOrb: View {
    let stage: AgentActivityStage
    let size: CGFloat

    private var orbState: OrbState {
        switch stage {
        case .searching, .readingSources, .readingWeb, .readingDocument:
            .searching
        case .generatingImage:
            .shaping
        case .generating:
            .composing
        default:
            .working
        }
    }

    var body: some View {
        let image = AgentActivityOrbFrameCache.frame(state: orbState, size: size)
        Image(uiImage: image)
            .resizable()
            .frame(width: size, height: size)
    }
}

@MainActor
private enum AgentActivityOrbFrameCache {
    private static var cache: [String: UIImage] = [:]

    static func frame(state: OrbState, size: CGFloat) -> UIImage {
        let key = "\(state.rawValue)-\(Int(size))"
        if let cached = cache[key] { return cached }
        let resolved = orbResolvePreset(state, .small)
        let format = UIGraphicsImageRendererFormat()
        format.scale = UIScreen.main.scale
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format)
        let image = renderer.image { context in
            orbDraw(resolved.mode, context.cgContext, size: Double(size), t: 0, dark: true, opts: resolved.opts)
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
