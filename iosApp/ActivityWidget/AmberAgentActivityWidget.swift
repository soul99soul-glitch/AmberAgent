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
            LockScreenAgentActivityView(presentation: context.state.presentation)
                .activityBackgroundTint(.black.opacity(0.78))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.center) {
                    ExpandedStatusLine(presentation: context.state.presentation)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    ExpandedStepTrack(presentation: context.state.presentation)
                }
            } compactLeading: {
                Text(compactLeadingText(for: context.state.presentation))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            } compactTrailing: {
                Text(compactTrailingText(for: context.state.presentation))
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.white.opacity(0.82))
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            } minimal: {
                Text(minimalText(for: context.state.presentation))
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(minimalColor(for: context.state.presentation))
            }
            .keylineTint(.amberAccent)
        }
    }

    private func compactLeadingText(for presentation: AgentActivityPresentation) -> String {
        switch presentation.phase {
        case .waitingForUser:
            "确认"
        case .completed:
            "完成"
        case .failed:
            "失败"
        case .cancelled:
            "停止"
        case .running:
            "Amber"
        }
    }

    private func compactTrailingText(for presentation: AgentActivityPresentation) -> String {
        switch presentation.phase {
        case .running:
            "执行中"
        case .waitingForUser:
            "等待"
        case .completed:
            "已完成"
        case .failed:
            "问题"
        case .cancelled:
            "已停"
        }
    }

    private func minimalText(for presentation: AgentActivityPresentation) -> String {
        switch presentation.phase {
        case .running:
            "●"
        case .waitingForUser:
            "!"
        case .completed:
            "✓"
        case .failed, .cancelled:
            "!"
        }
    }

    private func minimalColor(for presentation: AgentActivityPresentation) -> Color {
        switch presentation.phase {
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

private struct ExpandedStatusLine: View {
    let presentation: AgentActivityPresentation

    var body: some View {
        Text(presentation.statusText)
            .font(.system(size: 15, weight: .semibold, design: .default))
            .foregroundStyle(.white)
            .lineLimit(1)
            .minimumScaleFactor(0.78)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 18)
            .padding(.top, 2)
    }
}

private struct ExpandedStepTrack: View {
    let presentation: AgentActivityPresentation

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(presentation.toolTitle)
                .font(.system(size: 12, weight: .semibold, design: .default))
                .foregroundStyle(.white.opacity(0.72))
                .lineLimit(1)
                .minimumScaleFactor(0.8)

            VStack(alignment: .leading, spacing: 3) {
                ForEach(presentation.steps) { step in
                    StepRow(step: step)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(presentation.statusText)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.82)

            VStack(alignment: .leading, spacing: 4) {
                Text(presentation.toolTitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
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

private extension Color {
    static let amberAccent = Color(red: 1.0, green: 0.66, blue: 0.28)
}
