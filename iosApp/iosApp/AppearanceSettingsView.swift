import SwiftUI

struct AppearanceSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.appearance.mode") private var appearanceMode = AppearanceMode.light.rawValue
    @AppStorage("app.amber.ios.appearance.accent") private var accentTheme = AccentTheme.terracotta.rawValue
    @AppStorage("app.amber.ios.appearance.background") private var backgroundTheme = BackgroundTheme.warm.rawValue

    private var selectedMode: AppearanceMode {
        AppearanceMode(rawValue: appearanceMode) ?? .light
    }

    private var selectedAccent: AccentTheme {
        AccentTheme(rawValue: accentTheme) ?? .terracotta
    }

    private var selectedBackground: BackgroundTheme {
        BackgroundTheme(rawValue: backgroundTheme) ?? .warm
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    appearanceModeSection
                    accentSection
                    backgroundSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("外观")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 22)
    }

    private var appearanceModeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "外观模式")
            AmberFormGroup {
                AppearanceSegmentedControl(
                    modes: AppearanceMode.allCases,
                    selected: selectedMode
                ) { mode in
                    appearanceMode = mode.rawValue
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
    }

    private var accentSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "强调色")
            AmberFormGroup {
                HStack(spacing: 10) {
                    ForEach(AccentTheme.allCases) { theme in
                        Button {
                            accentTheme = theme.rawValue
                        } label: {
                            ZStack {
                                RoundedRectangle(cornerRadius: 10, style: .continuous)
                                    .fill(theme.color)
                                    .frame(width: 36, height: 36)

                                if selectedAccent == theme {
                                    Image(systemName: "checkmark")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundStyle(.white)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(theme.title)
                    }

                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 15)
                .padding(.vertical, 14)
            }
        }
    }

    private var backgroundSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "背景色调")
            AmberFormGroup {
                HStack(spacing: 12) {
                    ForEach(BackgroundTheme.allCases) { theme in
                        Button {
                            backgroundTheme = theme.rawValue
                        } label: {
                            BackgroundSwatch(theme: theme, isSelected: selectedBackground == theme)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(theme.title)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 14)
            }
        }
    }
}

private enum AppearanceMode: String, CaseIterable, Identifiable {
    case light
    case dark
    case system

    var id: String { rawValue }

    var title: String {
        switch self {
        case .light: "浅色"
        case .dark: "深色"
        case .system: "跟随系统"
        }
    }
}

private enum AccentTheme: String, CaseIterable, Identifiable {
    case terracotta
    case sage
    case blue
    case lavender
    case rose

    var id: String { rawValue }

    var title: String {
        switch self {
        case .terracotta: "Terracotta"
        case .sage: "Sage"
        case .blue: "Blue"
        case .lavender: "Lavender"
        case .rose: "Rose"
        }
    }

    var color: Color {
        switch self {
        case .terracotta: Color(hex: 0xB5532C)
        case .sage: Color(hex: 0x4E7C5F)
        case .blue: Color(hex: 0x4A79BE)
        case .lavender: Color(hex: 0x7A6FC0)
        case .rose: Color(hex: 0xB85D74)
        }
    }
}

private enum BackgroundTheme: String, CaseIterable, Identifiable {
    case warm
    case neutral

    var id: String { rawValue }

    var title: String {
        switch self {
        case .warm: "温暖陶土"
        case .neutral: "纯净白"
        }
    }

    var color: Color {
        switch self {
        case .warm: Color(hex: 0xFBF7F1)
        case .neutral: .white
        }
    }

    var border: Color {
        switch self {
        case .warm: AmberTheme.border
        case .neutral: Color(hex: 0xD1D1D6)
        }
    }
}

private struct AppearanceSegmentedControl: View {
    let modes: [AppearanceMode]
    let selected: AppearanceMode
    let onSelect: (AppearanceMode) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(modes) { mode in
                Button {
                    onSelect(mode)
                } label: {
                    Text(mode.title)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(selected == mode ? AmberTheme.foreground : AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .frame(height: 32)
                        .background(
                            selected == mode ? AmberTheme.background : .clear,
                            in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                        )
                        .shadow(color: selected == mode ? .black.opacity(0.09) : .clear, radius: 4, y: 1)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(2)
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
    }
}

private struct BackgroundSwatch: View {
    let theme: BackgroundTheme
    let isSelected: Bool

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(theme.color)
                .overlay(alignment: .bottom) {
                    Text(theme.title)
                        .font(.caption2.weight(isSelected ? .semibold : .medium))
                        .foregroundStyle(isSelected ? AmberTheme.accent : AmberTheme.muted)
                        .padding(.bottom, 8)
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(isSelected ? AmberTheme.accent : theme.border, lineWidth: isSelected ? 2 : 1)
                }
                .frame(height: 68)

            if isSelected {
                Circle()
                    .fill(AmberTheme.accent)
                    .frame(width: 16, height: 16)
                    .overlay {
                        Image(systemName: "checkmark")
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .padding(8)
            }
        }
        .frame(maxWidth: .infinity)
    }
}
