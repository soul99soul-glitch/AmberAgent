import SwiftUI

struct AppearanceSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.light.rawValue

    private var selectedMode: IOSAppearanceMode {
        IOSAppearanceMode(rawValue: appearanceMode) ?? .light
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    appearanceModeSection
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
                    modes: IOSAppearanceMode.allCases,
                    selected: selectedMode
                ) { mode in
                    appearanceMode = mode.rawValue
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
            }
        }
    }

}

private struct AppearanceSegmentedControl: View {
    let modes: [IOSAppearanceMode]
    let selected: IOSAppearanceMode
    let onSelect: (IOSAppearanceMode) -> Void

    var body: some View {
        AmberGlassGroup(spacing: 12) {
            HStack(spacing: 6) {
                ForEach(modes) { mode in
                    Button {
                        onSelect(mode)
                    } label: {
                        AmberGlassTextChip(
                            title: mode.title,
                            isSelected: selected == mode,
                            height: 32,
                            horizontalPadding: 8,
                            fillsWidth: true,
                            font: .caption.weight(.medium)
                        )
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
                    .accessibilityAddTraits(selected == mode ? .isSelected : [])
                }
            }
            .padding(2)
        }
    }
}

private struct AppearanceStatusRow: View {
    let systemImage: String
    let title: String
    let subtitle: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(AmberTheme.muted.opacity(0.12), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}
