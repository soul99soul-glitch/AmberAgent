import SwiftUI
import Shared

struct SearchServicesView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var draggingProviderId: String?

    var body: some View {
        // 关键:读一次被观察的 revision 建立依赖。snapshot 是 @ObservationIgnored,直接读
        // sharedSettings.enableWebSearch 等不会触发本页刷新,会导致开关「点了没反应」(bug①)。
        let _ = sharedSettings.revision

        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 20) {
                        masterSection
                        builtinSourcesSection
                        configuredSection
                        commonOptionsSection
                        recommendedSection
                    }
                    .padding(.top, 4)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    // MARK: - Header

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

    // MARK: - Master (Agent web search)

    private var masterSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Agent 网络搜索")

            AmberFormGroup {
                SearchPresetToggleRow(
                    title: "Agent 网络搜索",
                    subtitle: "允许聊天调用 search_web 和 scrape_web，使用已启用的服务。",
                    isOn: sharedSettings.enableWebSearch
                ) { enabled in
                    sharedSettings.setEnableWebSearch(enabled)
                }

                SearchServicesDivider()

                HStack(spacing: 8) {
                    Circle()
                        .fill(statusActive ? AmberTheme.accentGreen : AmberTheme.muted2)
                        .frame(width: 8, height: 8)
                    Text("\(totalEnabledCount) / \(totalServiceCount) 个服务已启用 · 多源召回")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 40)
                .padding(.horizontal, 14)
            }
        }
    }

    private var statusActive: Bool {
        sharedSettings.enableWebSearch && totalEnabledCount > 0
    }

    // MARK: - Built-in free sources

    private var builtinSourcesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内置免费源")

            AmberFormGroup {
                builtinRow(
                    title: "Jina Search / Reader",
                    subtitle: "可无 Key 使用，尽量以 Markdown 搜索和读取网页。",
                    isOn: sharedSettings.searchBuiltinJinaEnabled
                ) { sharedSettings.setSearchBuiltinJinaEnabled($0) }
                SearchServicesDivider()
                builtinRow(
                    title: "DuckDuckGo Lite",
                    subtitle: "无需 API Key 的免费公共召回源。",
                    isOn: sharedSettings.searchBuiltinDuckDuckGoEnabled
                ) { sharedSettings.setSearchBuiltinDuckDuckGoEnabled($0) }
                SearchServicesDivider()
                builtinRow(
                    title: "Bing HTML",
                    subtitle: "免费公共兜底，遇到验证页会明确报错。",
                    isOn: sharedSettings.searchBuiltinBingEnabled
                ) { sharedSettings.setSearchBuiltinBingEnabled($0) }
                SearchServicesDivider()
                builtinRow(
                    title: "Wikipedia",
                    subtitle: "实体和背景知识源，适合百科类问题。",
                    isOn: sharedSettings.searchBuiltinWikipediaEnabled
                ) { sharedSettings.setSearchBuiltinWikipediaEnabled($0) }
                SearchServicesDivider()
                builtinRow(
                    title: "Hacker News",
                    subtitle: "技术、开源和 AI 工具生态讨论源。",
                    isOn: sharedSettings.searchBuiltinHackerNewsEnabled
                ) { sharedSettings.setSearchBuiltinHackerNewsEnabled($0) }
                SearchServicesDivider()
                builtinRow(
                    title: "Google WebView 兜底",
                    subtitle: "普通搜索弱或深度搜索时，打开可见搜索页兜底。",
                    isOn: sharedSettings.searchGoogleWebViewFallbackEnabled
                ) { sharedSettings.setSearchGoogleWebViewFallbackEnabled($0) }
            }
        }
    }

    private func builtinRow(
        title: String,
        subtitle: String,
        isOn: Bool,
        onToggle: @escaping (Bool) -> Void
    ) -> some View {
        SearchPresetToggleRow(title: title, subtitle: subtitle, isOn: isOn, onToggle: onToggle)
    }

    // MARK: - Configured custom providers

    private var configuredSection: some View {
        VStack(spacing: 0) {
            HStack {
                AmberSectionLabel(text: "已配置")
                Spacer()
                if configuredProviders.count > 1 {
                    Label("拖动调整优先级", systemImage: "arrow.up.arrow.down")
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.muted2)
                        .padding(.trailing, 16)
                        .padding(.bottom, 6)
                }
            }

            AmberFormGroup {
                if configuredProviders.isEmpty {
                    Text("暂无自定义搜索服务。点右上角「+」可添加已接入的搜索服务作为聊天默认搜索。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                } else {
                    ForEach(Array(configuredProviders.enumerated()), id: \.element.id) { offset, provider in
                        if offset > 0 { SearchServicesDivider(leading: 52) }
                        configuredRow(provider)
                    }
                }
            }
        }
    }

    private func configuredRow(_ provider: ConfiguredSearchProvider) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "line.3.horizontal")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(AmberTheme.muted2)
                .frame(width: 24)

            ZStack {
                Circle().fill(AmberTheme.accent.opacity(0.12)).frame(width: 22, height: 22)
                Text("\(provider.priority)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(AmberTheme.accent)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(provider.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(provider.typeLabel)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: Binding(
                get: { provider.enabled },
                set: { sharedSettings.setSearchProviderEnabled(serviceId: provider.id, enabled: $0) }
            ))
            .labelsHidden()
            .toggleStyle(.switch)
            .tint(AmberTheme.accent)
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .opacity(draggingProviderId == provider.id ? 0.4 : 1)
        .draggable(provider.id) {
            Text(provider.name)
                .font(.body.weight(.semibold))
                .padding(8)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 8))
        }
        .dropDestination(for: String.self) { items, _ in
            guard let draggedId = items.first else { return false }
            moveProvider(draggedId: draggedId, ontoIndex: provider.index)
            return true
        } isTargeted: { targeted in
            draggingProviderId = targeted ? provider.id : (draggingProviderId == provider.id ? nil : draggingProviderId)
        }
    }

    private func moveProvider(draggedId: String, ontoIndex: Int) {
        draggingProviderId = nil
        guard let from = configuredProviders.first(where: { $0.id == draggedId })?.index,
              from != ontoIndex else { return }
        sharedSettings.moveSearchProvider(fromIndex: from, toIndex: ontoIndex)
    }

    // MARK: - Common options (result count)

    private var commonOptionsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "通用选项")

            AmberFormGroup {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("结果数量")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("每次搜索最多返回的结果条数")
                            .font(.caption2)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    resultCountStepper
                }
                .frame(minHeight: 56)
                .padding(.horizontal, 14)
            }
        }
    }

    private var resultCountStepper: some View {
        HStack(spacing: 0) {
            stepperButton(systemImage: "minus", enabled: sharedSettings.searchResultSize > 1) {
                sharedSettings.setSearchResultSize(sharedSettings.searchResultSize - 1)
            }
            Text("\(sharedSettings.searchResultSize)")
                .font(.body.weight(.semibold).monospacedDigit())
                .foregroundStyle(AmberTheme.foreground)
                .frame(minWidth: 38)
            stepperButton(systemImage: "plus", enabled: sharedSettings.searchResultSize < 50) {
                sharedSettings.setSearchResultSize(sharedSettings.searchResultSize + 1)
            }
        }
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }

    private func stepperButton(systemImage: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(enabled ? AmberTheme.accent : AmberTheme.muted2)
                .frame(width: 40, height: 34)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    // MARK: - Recommended combos

    private var recommendedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "推荐组合")

            AmberFormGroup {
                recommendedRow(title: "零成本", detail: "Jina + DuckDuckGo + Bing")
                SearchServicesDivider()
                recommendedRow(title: "Google 结果", detail: "Serper / SerpAPI")
                SearchServicesDivider()
                recommendedRow(title: "高质量多源", detail: "Tavily 或 Brave")
            }
        }
    }

    private func recommendedRow(title: String, detail: String) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.accent)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)
            Spacer(minLength: 0)
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 14)
    }

    // MARK: - Derived data

    private var configuredProviders: [ConfiguredSearchProvider] {
        let saved = sharedSettings.savedSearchProviders
        var nameById: [String: String] = [:]
        var typeById: [String: String] = [:]
        for entry in saved {
            guard let sid = entry["serviceId"] else { continue }
            nameById[sid] = entry["name"]
            typeById[sid] = entry["serviceType"]
        }
        let enabledIds = Set(sharedSettings.snapshot.searchEnabledServiceIds.map { $0.description() })
        return sharedSettings.snapshot.searchServices.enumerated().map { index, service in
            let sid = service.id.description()
            let isDefaultBing = service is SearchServiceOptions.BingLocalOptions
            let name = nameById[sid] ?? (isDefaultBing ? "Bing HTML 兜底" : "搜索服务")
            let rawType = typeById[sid] ?? ""
            let type = rawType.isEmpty ? (isDefaultBing ? "bing_local" : "search") : rawType
            return ConfiguredSearchProvider(
                id: sid,
                index: index,
                priority: index + 1,
                name: name,
                typeLabel: type,
                enabled: enabledIds.contains(sid)
            )
        }
    }

    private var builtinEnabledCount: Int {
        [
            sharedSettings.searchBuiltinJinaEnabled,
            sharedSettings.searchBuiltinDuckDuckGoEnabled,
            sharedSettings.searchBuiltinBingEnabled,
            sharedSettings.searchBuiltinWikipediaEnabled,
            sharedSettings.searchBuiltinHackerNewsEnabled,
            sharedSettings.searchGoogleWebViewFallbackEnabled,
        ].lazy.filter { $0 }.count
    }

    private var totalEnabledCount: Int {
        builtinEnabledCount + configuredProviders.lazy.filter { $0.enabled }.count
    }

    private var totalServiceCount: Int {
        6 + configuredProviders.count
    }
}

private struct ConfiguredSearchProvider: Identifiable {
    let id: String        // KMP SearchServiceOptions.id (Uuid string)
    let index: Int        // index in snapshot.searchServices (priority order, drives reorder)
    let priority: Int     // 1-based rank for the badge
    let name: String
    let typeLabel: String
    let enabled: Bool
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
    var subtitle: String?
    let isOn: Bool
    var onToggle: ((Bool) -> Void)?

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { value in onToggle?(value) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .toggleStyle(.switch)
        .tint(AmberTheme.accent)
        .disabled(onToggle == nil)
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
        .accessibilityLabel("\(title)\(isOn ? "，开启" : "，关闭")")
    }
}

#Preview {
    NavigationStack {
        SearchServicesView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
