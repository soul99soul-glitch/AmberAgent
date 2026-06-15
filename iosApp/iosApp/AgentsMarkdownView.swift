import SwiftUI

struct AgentsMarkdownView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var content = ""

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header

                    AgentsNote("Android/KMP 有 Settings.agentRuntime.agentSoulMarkdown，并由 GenerationPrompts 注入；iOS 当前没有把该设置接入 ChatViewModel。")
                        .padding(.bottom, 10)

                    editor

                    AgentsNote("当前内容只保留在本页草稿状态；关闭页面会丢弃，不会写入 UserDefaults、SettingsStore 或下一次对话请求。")
                        .padding(.top, 7)
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

            Text("agents.md 草稿")
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

    private var editor: some View {
        ZStack(alignment: .topLeading) {
            TextEditor(text: $content)
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground2)
                .lineSpacing(3)
                .scrollContentBackground(.hidden)
                .padding(12)
                .frame(minHeight: 340)

            if content.isEmpty {
                Text("草稿 Markdown；当前不会注入 System Prompt")
                    .font(.body)
                    .foregroundStyle(AmberTheme.muted2)
                    .padding(.horizontal, 17)
                    .padding(.vertical, 20)
                    .allowsHitTesting(false)
            }
        }
        .background(AmberTheme.surface)
        .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
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
