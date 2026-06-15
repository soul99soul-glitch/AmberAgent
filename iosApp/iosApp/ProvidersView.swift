import SwiftUI

struct ProvidersView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var alert: ProviderAlert?

    private let enabledProviders: [ProviderRowModel] = [
        .init(initial: "O", name: "opencode", endpoint: "opencode.ai/v1"),
        .init(initial: "G", name: "Gemini OAuth", endpoint: "generativelanguage.googleapis.com"),
        .init(initial: "O", name: "OpenAI Codex OAuth", endpoint: "api.openai.com/v1"),
        .init(initial: "G", name: "Gemini", endpoint: "generativelanguage.googleapis.com"),
        .init(initial: "D", name: "DeepSeek", endpoint: "api.deepseek.com/v1"),
        .init(initial: "K", name: "Kimi", endpoint: "api.moonshot.cn/v1"),
        .init(initial: "G", name: "智谱 GLM", endpoint: "open.bigmodel.cn/api/paas/v4"),
        .init(initial: "M", name: "小米 MiMo", endpoint: "api.mimo.ai/v1")
    ]

    private let disabledProviders: [ProviderRowModel] = [
        .init(initial: "M", name: "minimax", endpoint: "已停用", isEnabled: false, isDimmed: true),
        .init(initial: "O", name: "OpenRouter", endpoint: "未添加模型", isEnabled: false)
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    searchPill
                    providerSection(title: "已启用", rows: enabledProviders)
                    providerSection(title: "停用 / 未配置", rows: disabledProviders)
                }
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(
                systemImage: "chevron.left",
                accessibilityLabel: "返回设置",
                size: 44,
                symbolSize: 20
            ) {
                dismiss()
            }

            Spacer()

            Text("服务商")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                alert = .addProvider
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: 22)
            .accessibilityLabel("添加服务商")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var searchPill: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AmberTheme.muted.opacity(0.72))

            Text("搜索")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Spacer()
        }
        .frame(height: 40)
        .padding(.horizontal, 13)
        .background(
            AmberTheme.surface2.opacity(0.82),
            in: RoundedRectangle(cornerRadius: AmberTheme.radiusPill, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: AmberTheme.radiusPill, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
        .padding(.top, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("搜索服务商")
    }

    private func providerSection(title: String, rows: [ProviderRowModel]) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)

            AmberFormGroup {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, provider in
                    ProviderRow(provider: provider) {
                        router.navigate(to: .providerDetail(name: provider.name, endpoint: provider.endpoint))
                    }

                    if index < rows.count - 1 {
                        ProviderDivider()
                    }
                }
            }
        }
    }
}

private enum ProviderAlert: Identifiable {
    case addProvider

    var id: String {
        switch self {
        case .addProvider:
            "add-provider"
        }
    }

    var title: String {
        switch self {
        case .addProvider:
            "添加服务商尚未接线"
        }
    }

    var message: String {
        switch self {
        case .addProvider:
            "当前保留已有 API 配置页作为服务商详情入口，" +
                "不在列表页创建凭据或启动 OAuth。"
        }
    }
}

private struct ProviderRowModel: Identifiable {
    let id = UUID()
    let initial: String
    let name: String
    let endpoint: String
    var isEnabled = true
    var isDimmed = false
}

private struct ProviderRow: View {
    let provider: ProviderRowModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(provider.initial)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.surface2, in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(provider.name)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(1)

                    Text(provider.endpoint)
                        .font(
                            .system(
                                size: provider.isEnabled ? 11 : 12,
                                weight: .regular,
                                design: provider.isEnabled ? .monospaced : .default
                            )
                        )
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if provider.isEnabled {
                    Circle()
                        .fill(AmberTheme.accentGreen)
                        .frame(width: 7, height: 7)
                        .shadow(color: AmberTheme.accentGreen.opacity(0.22), radius: 0, x: 0, y: 0)
                        .overlay {
                            Circle()
                                .stroke(AmberTheme.accentGreen.opacity(0.13), lineWidth: 4)
                        }
                        .padding(.trailing, 2)
                }

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(provider.isDimmed ? 0.6 : 1)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(provider.name)，\(provider.endpoint)")
    }
}

private struct ProviderDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 58)
    }
}

#Preview {
    NavigationStack {
        ProvidersView()
            .environment(RouterPath())
    }
}
