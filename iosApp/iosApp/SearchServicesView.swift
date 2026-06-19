import SwiftUI
import Shared

struct SearchServicesView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        presetServicesSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("搜索服务")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加搜索服务", size: 44, symbolSize: 17) {
                router.navigate(to: .searchProvider)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var presetServicesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "搜索服务")

            AmberFormGroup {
                SearchPresetToggleRow(title: "Agent 网络搜索", isOn: sharedSettings.enableWebSearch) { enabled in
                    sharedSettings.setEnableWebSearch(enabled)
                }
                SearchServicesDivider()
                SearchPresetToggleRow(title: "DuckDuckGo Lite", isOn: sharedSettings.searchBuiltinDuckDuckGoEnabled) { enabled in
                    sharedSettings.setSearchBuiltinDuckDuckGoEnabled(enabled)
                }
                SearchServicesDivider()
                SearchPresetToggleRow(title: "Bing HTML", isOn: sharedSettings.searchBuiltinBingEnabled) { enabled in
                    sharedSettings.setSearchBuiltinBingEnabled(enabled)
                }
            }
        }
    }
}

private struct SearchServicesDivider: View {
    var leading: CGFloat = 14

    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, leading)
    }
}

private struct SearchPresetToggleRow: View {
    let title: String
    let isOn: Bool
    var onToggle: ((Bool) -> Void)?

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { value in
            onToggle?(value)
        })) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
        }
        .toggleStyle(.switch)
        .tint(AmberTheme.accent)
        .disabled(onToggle == nil)
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .accessibilityLabel("\(title)\(isOn ? "，开启" : "，关闭")")
    }
}

#Preview {
    NavigationStack {
        SearchServicesView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
