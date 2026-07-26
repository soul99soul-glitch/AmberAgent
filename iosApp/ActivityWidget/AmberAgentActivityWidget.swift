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
            .activityBackgroundTint(.black)
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
                    AgentActivityPriorityFact(
                        presentation: context.state.presentation,
                        startedAt: context.attributes.startedAt,
                        isStale: context.isStale
                    )
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.state.presentation.kind.title)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.62))
                        Text(context.isStale
                             ? AgentActivityCopy.text("agent.activity.fact.stale")
                             : context.state.presentation.stage.title)
                            .font(.headline)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    AgentActivityFooter(
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
                AgentActivityPriorityFact(
                    presentation: context.state.presentation,
                    startedAt: context.attributes.startedAt,
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

private struct AgentActivityPriorityFact: View {
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
        .font(.caption2.weight(.semibold))
        .foregroundStyle(.white.opacity(0.88))
        .lineLimit(1)
        .minimumScaleFactor(0.72)
    }
}

private struct AgentActivityFooter: View {
    let attributes: AgentActivityAttributes
    let state: AgentActivityAttributes.ContentState
    let isStale: Bool

    private var displayPhase: AgentActivityPhase {
        state.presentation.displayPhase(isStale: isStale)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if displayPhase == .running,
               let progress = state.presentation.progressFraction {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(displayPhase.widgetColor)
            }

            HStack(spacing: 10) {
                Group {
                    if displayPhase == .running,
                       let detail = state.presentation.metric.detailText {
                        Text(detail)
                    } else {
                        Text(state.updatedAt, style: .relative)
                    }
                }
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
                .lineLimit(1)

                Spacer(minLength: 8)

                if let action = state.presentation.action,
                   let url = attributes.destinationURL(for: action) {
                    Link(destination: url) {
                        Label(action.title, systemImage: "arrow.up.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.amberAccent)
                            .frame(minHeight: 44)
                    }
                    .accessibilityLabel(action.title)
                }
            }
        }
        .padding(.bottom, 2)
    }
}

private struct LockScreenAgentActivityView: View {
    let attributes: AgentActivityAttributes
    let state: AgentActivityAttributes.ContentState
    let isStale: Bool

    private var displayPhase: AgentActivityPhase {
        state.presentation.displayPhase(isStale: isStale)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                AgentActivityGlyph(
                    presentation: state.presentation,
                    isStale: isStale,
                    size: 34
                )
                .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(state.presentation.kind.title)
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(displayPhase == .stale
                         ? AgentActivityCopy.text("agent.activity.fact.stale")
                         : state.presentation.stage.title)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.58))
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                AgentActivityPriorityFact(
                    presentation: state.presentation,
                    startedAt: attributes.startedAt,
                    isStale: isStale
                )
            }

            if displayPhase == .running,
               let progress = state.presentation.progressFraction {
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                    .tint(displayPhase.widgetColor)
            }

            HStack(spacing: 10) {
                Group {
                    if displayPhase == .running,
                       let detail = state.presentation.metric.detailText {
                        HStack(spacing: 5) {
                            Text(detail)
                            Text("·")
                                .accessibilityHidden(true)
                            Text(state.updatedAt, style: .relative)
                        }
                    } else {
                        Text(state.updatedAt, style: .relative)
                    }
                }
                .font(.caption)
                .foregroundStyle(.white.opacity(0.58))
                .lineLimit(1)

                Spacer(minLength: 8)

                if let action = state.presentation.action,
                   let url = attributes.destinationURL(for: action) {
                    Link(destination: url) {
                        Text(action.title)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.amberAccent)
                            .frame(minHeight: 44)
                    }
                    .accessibilityLabel(action.title)
                }
            }
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

    /// 运行中复用 in-app 活动岛的星核语言（静帧轮换）；其余相位保留 SF Symbol。
    @ViewBuilder
    private var glyphContent: some View {
        if displayPhase == .running {
            AgentActivityOrbFrames(stage: presentation.stage, size: size * 0.85)
        } else {
            Image(systemName: presentation.displaySymbolName(isStale: isStale))
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(displayPhase.widgetColor)
        }
    }
}

/// 系统岛是共享空间，不做光谱动画：仅以约 1.5s 步进轮换 3 帧静帧表示活跃。
/// 引擎是纯函数，帧在首次使用时离线渲染并缓存；系统若限频则停在首帧，仍可读。
private struct AgentActivityOrbFrames: View {
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
        TimelineView(.periodic(from: .now, by: 1.5)) { context in
            let frames = AgentActivityOrbFrameCache.frames(state: orbState, size: size)
            let index = Int(context.date.timeIntervalSince1970 / 1.5) % frames.count
            Image(uiImage: frames[index])
                .resizable()
                .frame(width: size, height: size)
        }
    }
}

@MainActor
private enum AgentActivityOrbFrameCache {
    private static var cache: [String: [UIImage]] = [:]

    static func frames(state: OrbState, size: CGFloat) -> [UIImage] {
        let key = "\(state.rawValue)-\(Int(size))"
        if let cached = cache[key] { return cached }
        let resolved = orbResolvePreset(state, .small)
        // 与 ThinkingOrbView 同一时钟约定：t = 墙钟 × resolved.speed，三帧均布 0.7s 相位差。
        let rendered = (0..<3).map { index in
            render(resolved: resolved, size: size, time: Double(index) * 0.7 * resolved.speed)
        }
        cache[key] = rendered
        return rendered
    }

    private static func render(resolved: OrbResolved, size: CGFloat, time: Double) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 2
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format)
        return renderer.image { context in
            orbDraw(resolved.mode, context.cgContext, size: Double(size), t: time, dark: true, opts: resolved.opts)
        }
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
    startedAt: .now.addingTimeInterval(-125)
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
