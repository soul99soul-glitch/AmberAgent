import SwiftUI

struct AgentsMarkdownView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header

                    AgentsNote("Android/KMP 有 Settings.agentRuntime.agentSoulMarkdown，并由 GenerationPrompts 注入；iOS 当前没有把该设置接入 ChatViewModel。")
                        .padding(.bottom, 10)

                    unavailableState
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回核心记忆", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("agents.md")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AgentsCloseButton {
                dismiss()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var unavailableState: some View {
        AmberFormGroup {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "doc.text")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.accentAmber.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text("编辑入口未开放")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text("等 iOS ChatViewModel 消费 agentSoulMarkdown 后再开放编辑和持久化；当前不提供只在本页生效的假预览。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
        }
    }
}

private struct AgentsCloseButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("关闭")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(height: 36)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("关闭")
    }
}

private struct AgentsNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
    }
}
