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
                    AgentActivityGlyph(presentation: context.state.presentation, size: 34, showsProgressRing: true)
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
                AgentActivityGlyph(presentation: context.state.presentation, size: 20, showsProgressRing: true)
            } compactTrailing: {
                Text(context.state.presentation.compactTrailingText)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.88))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            } minimal: {
                AgentActivityGlyph(presentation: context.state.presentation, size: 17, showsProgressRing: true)
            }
            .keylineTint(.amberAccent)
        }
    }
}

private struct ExpandedStatusLine: View {
    let presentation: AgentActivityPresentation

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("Amber")
                .font(.system(size: 11, weight: .bold, design: .rounded))
                .foregroundStyle(presentation.phase.widgetColor)
                .lineLimit(1)

            Text(presentation.statusText.replacingOccurrences(of: "Amber ", with: ""))
                .font(.system(size: 15, weight: .semibold, design: .default))
                .foregroundStyle(.white)
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
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(presentation.toolTitle)
                    .font(.system(size: 11, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(1)

                Capsule()
                    .fill(.white.opacity(0.12))
                    .frame(height: 4)
                    .overlay(alignment: .leading) {
                        GeometryReader { proxy in
                            Capsule()
                                .fill(presentation.phase.widgetColor)
                                .frame(width: max(10, proxy.size.width * presentation.progress))
                        }
                    }
                    .frame(maxWidth: .infinity)

                Text("\(Int((presentation.progress * 100).rounded()))%")
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.58))
            }

            HStack(spacing: 6) {
                ForEach(presentation.steps) { step in
                    StepPill(step: step)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 2)
        .padding(.bottom, 8)
    }
}

private struct StepPill: View {
    let step: AgentActivityStep

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(markerColor)
                .frame(width: 6, height: 6)

            Text(step.title)
                .font(.system(size: 10.5, weight: step.state == .current ? .semibold : .medium, design: .default))
                .foregroundStyle(textColor)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            step.state == .current ? .white.opacity(0.12) : .white.opacity(0.05),
            in: Capsule()
        )
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
                AgentActivityGlyph(presentation: presentation, size: 34, showsProgressRing: true)

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

private struct AgentActivityGlyph: View {
    let presentation: AgentActivityPresentation
    var size: CGFloat
    var showsProgressRing: Bool

    var body: some View {
        let baseLineWidth = max(0.8, size * 0.045)
        let ringLineWidth = max(1.1, size * 0.06)
        let ringInset = ringLineWidth / 2 + 0.8
        let progress = min(1, max(0.08, presentation.progress))

        ZStack {
            Circle()
                .inset(by: ringInset)
                .fill(presentation.phase.widgetColor.opacity(0.16))

            if showsProgressRing {
                Circle()
                    .inset(by: ringInset)
                    .stroke(.white.opacity(0.16), lineWidth: baseLineWidth)
                Circle()
                    .inset(by: ringInset)
                    .trim(from: 0, to: progress)
                    .stroke(
                        presentation.phase.widgetColor,
                        style: StrokeStyle(lineWidth: ringLineWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
            } else {
                Circle()
                    .inset(by: ringInset)
                    .stroke(presentation.phase.widgetColor.opacity(0.38), lineWidth: baseLineWidth)
            }

            Image(systemName: presentation.activitySymbolName)
                .font(.system(size: size * 0.34, weight: .semibold))
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
