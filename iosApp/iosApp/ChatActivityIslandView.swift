import SwiftUI

struct ChatActivityIslandState: Equatable {
    enum Kind: Equatable {
        case title
        case waiting
        case thinking
        case generating
        case tool
        case image
    }

    let kind: Kind
    let title: String
    let detail: String?
    let systemImage: String
    let isActive: Bool
    let tint: ChatActivityIslandTint

    var animationKey: String {
        [
            "\(kind)",
            title,
            detail ?? "",
            systemImage,
            isActive ? "active" : "idle",
            "\(tint)"
        ].joined(separator: "|")
    }

    var flipKey: String {
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
            tint: .neutral
        )
    }

    static func activity(
        kind: Kind,
        title: String,
        detail: String? = nil,
        systemImage: String,
        tint: ChatActivityIslandTint
    ) -> ChatActivityIslandState {
        ChatActivityIslandState(
            kind: kind,
            title: title,
            detail: detail,
            systemImage: systemImage,
            isActive: true,
            tint: tint
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
}

struct ChatActivityIslandView: View {
    let state: ChatActivityIslandState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if state.isActive {
                activeContent
                    .id(state.flipKey)
                    .transition(.chatIslandFlip)
            } else {
                titleContent
                    .id(state.flipKey)
                    .transition(.chatIslandFlip)
            }
        }
        .padding(.horizontal, state.isActive ? 13 : 14)
        .padding(.vertical, state.isActive ? 7 : 8)
        .frame(minHeight: state.isActive ? 42 : 34)
        .frame(maxWidth: state.isActive ? 268 : 230)
        .fixedSize(horizontal: true, vertical: false)
        .modifier(ChatActivityIslandSoftField(tint: state.tint.color, isActive: state.isActive))
        .modifier(ChatActivityIslandGlass())
        .contentShape(Capsule())
        .animation(
            reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.82),
            value: state.flipKey
        )
    }

    private var titleContent: some View {
        Text(state.title)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(AmberTheme.foreground)
            .lineLimit(1)
            .minimumScaleFactor(0.84)
            .contentTransition(.opacity)
    }

    private var activeContent: some View {
        HStack(spacing: 9) {
            ChatActivityIslandGlyph(
                systemImage: state.systemImage,
                tint: state.tint.color,
                isActive: state.isActive
            )

            VStack(alignment: .leading, spacing: 1) {
                Text(state.title)
                    .font(.system(size: 13.5, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)

                if let detail = state.detail, !detail.isEmpty {
                    Text(detail)
                        .font(.system(size: 10.5, weight: .medium))
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.78)
                }
            }

            Spacer(minLength: 0)
        }
        .contentTransition(.opacity)
    }
}

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

private struct ChatActivityIslandSoftField: ViewModifier {
    let tint: Color
    let isActive: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        content
            .background {
                if isActive {
                    Capsule()
                        .fill(tint.opacity(0.07))
                        .blur(radius: 12)
                        .padding(.horizontal, -7)
                        .padding(.vertical, -4)
                        .allowsHitTesting(false)
                }
            }
    }
}

private struct ChatActivityIslandGlyph: View {
    let systemImage: String
    let tint: Color
    let isActive: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle()
                .fill(tint.opacity(0.12))
                .frame(width: 24, height: 24)

            if isActive && !reduceMotion {
                ChatActivityIslandHalo(tint: tint)
                    .frame(width: 24, height: 24)
            }

            Image(systemName: systemImage)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(tint)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.variableColor.iterative.reversing, isActive: isActive && !reduceMotion)
                .animation(.spring(response: 0.35, dampingFraction: 0.72), value: systemImage)
        }
        .frame(width: 24, height: 24)
    }
}

private struct ChatActivityIslandHalo: View {
    let tint: Color

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { context in
            let rotation = context.date.timeIntervalSinceReferenceDate * 140
            ZStack {
                Circle()
                    .stroke(tint.opacity(0.16), lineWidth: 1)

                Circle()
                    .trim(from: 0.08, to: 0.42)
                    .stroke(
                        tint.opacity(0.42),
                        style: StrokeStyle(lineWidth: 1.2, lineCap: .round)
                    )
                    .rotationEffect(.degrees(rotation))
            }
        }
    }
}

private extension AnyTransition {
    static var chatIslandFlip: AnyTransition {
        .asymmetric(
            insertion: .modifier(
                active: ChatActivityIslandFlipModifier(angle: -72, yOffset: 7, opacity: 0),
                identity: ChatActivityIslandFlipModifier(angle: 0, yOffset: 0, opacity: 1)
            ),
            removal: .modifier(
                active: ChatActivityIslandFlipModifier(angle: 72, yOffset: -7, opacity: 0),
                identity: ChatActivityIslandFlipModifier(angle: 0, yOffset: 0, opacity: 1)
            )
        )
    }
}

private struct ChatActivityIslandFlipModifier: ViewModifier {
    let angle: Double
    let yOffset: CGFloat
    let opacity: Double

    func body(content: Content) -> some View {
        content
            .rotation3DEffect(
                .degrees(angle),
                axis: (x: 1, y: 0, z: 0),
                anchor: .center,
                perspective: 0.68
            )
            .offset(y: yOffset)
            .opacity(opacity)
    }
}
