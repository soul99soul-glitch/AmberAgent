import SwiftUI
import Shared

struct BoardSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        templateSection
                        sourceSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回深度阅读", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("深度阅读设置")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("版式 · 来源边界")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("iOS 深度阅读默认使用单一 Amber Assistant 的当前模型环境；这里仅保留本地版式和来源边界说明，不提供总开关，也不新增假装可用的 API Key 设置。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var templateSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置版式")
            AmberFormGroup {
                ForEach(Array(IOSDeepReadTemplate.builtIns.enumerated()), id: \.element.id) { index, template in
                    HStack(spacing: 12) {
                        Image(systemName: iconName(for: template.id))
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 30, height: 30)
                            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))

                        VStack(alignment: .leading, spacing: 3) {
                            Text(template.name)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text(template.description)
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)

                    if index < IOSDeepReadTemplate.builtIns.count - 1 {
                        BoardCapabilityDivider()
                    }
                }
            }
        }
    }

    private var sourceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "来源边界")
            AmberFormGroup {
                sourceRow(title: "本地可验证", detail: "手动文本、搜索结果和当前会话可直接创建深度阅读。")
                BoardCapabilityDivider()
                sourceRow(title: "需要前台授权", detail: "文件只读取你选择的文本/PDF/DOCX 预览；图片和扫描件不会假装 OCR。")
                BoardCapabilityDivider()
                sourceRow(title: "需要已加载页面", detail: "WebMount 只读取当前前台 WKWebView 页面正文，不自动登录或跨站抓取。")
            }
        }
    }

    private func sourceRow(title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.body.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground)
            Text(detail)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    private func iconName(for templateId: String) -> String {
        switch templateId {
        case IOSDeepReadTemplate.reading.id: "book"
        case IOSDeepReadTemplate.analysis.id: "chart.xyaxis.line"
        default: "doc.richtext"
        }
    }

}

#Preview {
    NavigationStack {
        BoardSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}
