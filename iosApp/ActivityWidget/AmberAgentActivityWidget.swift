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
                presentation: context.state.presentation,
                startedAt: context.attributes.startedAt
            )
                .activityBackgroundTint(.amberActivityBackground)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    AgentActivityPhaseBadge(presentation: context.state.presentation, size: 34)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ElapsedActivityTime(startedAt: context.attributes.startedAt)
                }
                DynamicIslandExpandedRegion(.center) {
                    ExpandedStatusLine(presentation: context.state.presentation)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ExpandedStepTrack(presentation: context.state.presentation)
                }
            } compactLeading: {
                Image(systemName: context.state.presentation.phaseSymbolName)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(phaseColor(for: context.state.presentation))
            } compactTrailing: {
                Text(context.state.presentation.compactTrailingText)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.white.opacity(0.82))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            } minimal: {
                Image(systemName: context.state.presentation.phaseSymbolName)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(phaseColor(for: context.state.presentation))
            }
            .keylineTint(.amberAccent)
        }
    }

    private func phaseColor(for presentation: AgentActivityPresentation) -> Color {
        presentation.phase.widgetColor
    }
}

private struct ExpandedStatusLine: View {
    let presentation: AgentActivityPresentation

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(presentation.statusText)
                .font(.system(size: 15, weight: .semibold, design: .default))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.78)

            Text(presentation.currentStepTitle)
                .font(.system(size: 11, weight: .medium, design: .default))
                .foregroundStyle(.white.opacity(0.62))
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 2)
    }
}

private struct ExpandedStepTrack: View {
    let presentation: AgentActivityPresentation

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                Text(presentation.toolTitle)
                    .font(.system(size: 12, weight: .semibold, design: .default))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)

                ProgressView(value: presentation.progress)
                    .progressViewStyle(.linear)
                    .tint(presentation.phase.widgetColor)
                    .frame(maxWidth: 92)
            }

            VStack(alignment: .leading, spacing: 3) {
                ForEach(presentation.steps) { step in
                    StepRow(step: step)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 2)
        .padding(.bottom, 10)
        .padding(.bottom, 2)
    }
}

private struct StepRow: View {
    let step: AgentActivityStep

    var body: some View {
        HStack(spacing: 6) {
            Text(step.state.marker)
                .font(.system(size: 11, weight: .semibold, design: .default))
                .foregroundStyle(markerColor)
                .frame(width: 12, alignment: .center)

            Text(step.title)
                .font(.system(size: 12, weight: step.state == .current ? .semibold : .regular, design: .default))
                .foregroundStyle(textColor)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var markerColor: Color {
        switch step.state {
        case .done:
            .white.opacity(0.82)
        case .current:
            .amberAccent
        case .pending:
            .white.opacity(0.44)
        case .failed:
            .red.opacity(0.9)
        }
    }

    private var textColor: Color {
        switch step.state {
        case .done:
            .white.opacity(0.82)
        case .current:
            .white
        case .pending:
            .white.opacity(0.52)
        case .failed:
            .white.opacity(0.86)
        }
    }
}

private struct LockScreenAgentActivityView: View {
    let presentation: AgentActivityPresentation
    let startedAt: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                AgentActivityPhaseBadge(presentation: presentation, size: 34)

                VStack(alignment: .leading, spacing: 2) {
                    Text(presentation.statusText)
                        .font(.headline)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                    Text(presentation.currentStepTitle)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                ElapsedActivityTime(startedAt: startedAt)
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(presentation.toolTitle)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    ProgressView(value: presentation.progress)
                        .progressViewStyle(.linear)
                        .tint(presentation.phase.widgetColor)
                }
                ForEach(presentation.steps) { step in
                    HStack(spacing: 6) {
                        Text(step.state.marker)
                            .foregroundStyle(step.state == .current ? Color.amberAccent : .secondary)
                        Text(step.title)
                            .lineLimit(1)
                    }
                    .font(.caption)
                }
            }
        }
        .padding()
    }
}

private struct AgentActivityPhaseBadge: View {
    let presentation: AgentActivityPresentation
    var size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(presentation.phase.widgetColor.opacity(0.18))
            Circle()
                .stroke(presentation.phase.widgetColor.opacity(0.38), lineWidth: 1)
            Image(systemName: presentation.phaseSymbolName)
                .font(.system(size: size * 0.38, weight: .semibold))
                .foregroundStyle(presentation.phase.widgetColor)
        }
        .frame(width: size, height: size)
    }
}

private struct ElapsedActivityTime: View {
    let startedAt: Date

    var body: some View {
        Text(startedAt, style: .timer)
            .font(.system(size: 12, weight: .semibold, design: .rounded))
            .monospacedDigit()
            .foregroundStyle(.white.opacity(0.78))
            .lineLimit(1)
            .minimumScaleFactor(0.75)
    }
}

private extension Color {
    static let amberAccent = Color(red: 1.0, green: 0.66, blue: 0.28)
    static let amberActivityBackground = Color(red: 0.10, green: 0.09, blue: 0.08)
}

private extension AgentActivityPhase {
    var widgetColor: Color {
        switch self {
        case .running:
            .amberAccent
        case .waitingForUser:
            .orange
        case .completed:
            .green
        case .failed, .cancelled:
            .red
        }
    }
}
