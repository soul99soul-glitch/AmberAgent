import SwiftUI

struct AgentsMarkdownView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.agentsMd.draft") private var content = """
    # 行为准则

    - 称呼用户「光」，语气随意
    - 回答先结论后展开
    - 不说「好问题」这类客套
    - 中文优先，代码标识符保留原文
    """

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header

                    AgentsNote("这段应用级 Markdown 行为准则会在每次对话前注入到 System Prompt。")
                        .padding(.bottom, 10)

                    editor

                    AgentsNote("改动会在下一次对话开始时生效。")
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

            Text("agents.md")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AgentsDoneButton {
                dismiss()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var editor: some View {
        TextEditor(text: $content)
            .font(.system(.body, design: .monospaced))
            .foregroundStyle(AmberTheme.foreground2)
            .lineSpacing(3)
            .scrollContentBackground(.hidden)
            .padding(12)
            .frame(minHeight: 340)
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
    }
}

private struct AgentsDoneButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("完成")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(height: 36)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("完成")
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
