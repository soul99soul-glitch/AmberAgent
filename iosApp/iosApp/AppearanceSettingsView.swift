import SwiftUI

struct AppearanceSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage(IOSAppearancePreferenceKeys.mode) private var appearanceMode = IOSAppearanceMode.system.rawValue
    private let runtime = AmberThemeRuntime.shared

    private var selectedMode: IOSAppearanceMode {
        IOSAppearanceMode(rawValue: appearanceMode) ?? .system
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    section("主题") { themeSegmented }
                    section("背景色") { backgroundCards }
                    section("强调色") { accentSwatches }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 48)
            }
            .scrollIndicators(.hidden)
        }
        .safeAreaBar(edge: .top, spacing: 0) { header }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    // MARK: Chrome

    private var header: some View {
        ZStack {
            Text("外观")
                .font(.headline)
                .foregroundStyle(AmberTheme.foreground)

            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 36, symbolSize: 16) {
                    dismiss()
                }
                Spacer()
            }
        }
        .padding(.horizontal, 16)
        .frame(height: 54)
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }

    // MARK: 1 · 主题 (light / dark / system)

    private var themeSegmented: some View {
        HStack(spacing: 0) {
            ForEach(IOSAppearanceMode.allCases) { mode in
                let isSel = selectedMode == mode
                Text(mode.title)
                    .font(.subheadline.weight(isSel ? .semibold : .regular))
                    .foregroundStyle(isSel ? AmberTheme.foreground : AmberTheme.muted)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
                    .background {
                        if isSel {
                            RoundedRectangle(cornerRadius: 11, style: .continuous)
                                .fill(AmberTheme.background)
                                .shadow(color: .black.opacity(0.06), radius: 4, y: 1)
                                .padding(3)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { appearanceMode = mode.rawValue }
                    .accessibilityElement()
                    .accessibilityLabel(mode.title)
                    .accessibilityAddTraits(isSel ? [.isButton, .isSelected] : .isButton)
            }
        }
        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: 2 · 背景色 (warm paper / neutral white)

    private var backgroundCards: some View {
        // Only the neutral canvases (暖纸 / 中性白) are surfaced. The immersive single-hue
        // canvases (绛红/赭橙/姜黄/品红/藕荷) are kept as placeholders in the theme system but
        // HIDDEN here — full-bleed color didn't read well across the app's many surfaces.
        // To bring them back (or swap in new colors): adjust the palettes in `AmberTheme` +
        // the `Paper` cases, then drop the `!$0.isImmersive` filter below (or flip a case's
        // `isImmersive` to false). Nothing else needs touching — base()/picker auto-pick up.
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
            spacing: 12
        ) {
            ForEach(AmberThemeRuntime.Paper.allCases.filter { !$0.isImmersive }, id: \.self) { paper in
                backgroundCard(paper, palette: paper.lightPalette, name: paper.displayName)
            }
        }
    }

    private func backgroundCard(_ paper: AmberThemeRuntime.Paper, palette: AmberPalette, name: String) -> some View {
        let isSel = runtime.paper == paper
        return Button {
            runtime.paper = paper
        } label: {
            VStack(spacing: 0) {
                miniPreview(palette)
                    .frame(maxWidth: .infinity)
                    .frame(height: 150)
                    .background(Color(hex: palette.background))

                HStack {
                    Text(name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Spacer()
                    selectionIndicator(isSel)
                }
                .padding(.horizontal, 14)
                .frame(height: 50)
                .background(AmberTheme.surface)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(isSel ? AmberTheme.accent : AmberTheme.borderSoft, lineWidth: isSel ? 2 : 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("背景色：\(name)" + (isSel ? "，已选中" : ""))
    }

    /// Mini interface preview painted with the target theme's own tokens: canvas + a grouped
    /// surface card (accent dot + two text bars) + loose text bars on the canvas.
    private func miniPreview(_ p: AmberPalette) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 9) {
                Circle()
                    .fill(AmberTheme.accent)
                    .frame(width: 15, height: 15)
                VStack(alignment: .leading, spacing: 6) {
                    Capsule().fill(Color(hex: p.foreground2)).frame(width: 58, height: 6)
                    Capsule().fill(Color(hex: p.muted)).frame(width: 34, height: 6)
                }
                Spacer(minLength: 0)
            }
            .padding(11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(hex: p.surface), in: RoundedRectangle(cornerRadius: 11, style: .continuous))

            Capsule().fill(Color(hex: p.surface2)).frame(height: 7).frame(maxWidth: .infinity)
            Capsule().fill(Color(hex: p.surface2)).frame(width: 92, height: 7)
            Spacer(minLength: 0)
        }
        .padding(13)
    }

    private func selectionIndicator(_ isSel: Bool) -> some View {
        ZStack {
            if isSel {
                Circle().fill(AmberTheme.accent).frame(width: 24, height: 24)
                Image(systemName: "checkmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(AmberTheme.accentInk)
            } else {
                Circle().stroke(AmberTheme.muted2, lineWidth: 1.5).frame(width: 24, height: 24)
            }
        }
        .frame(width: 24, height: 24)
    }

    // MARK: 3 · 强调色 (6 swatches, no wrap, no names)

    private var accentSwatches: some View {
        HStack(spacing: 0) {
            ForEach(AmberAccentOption.allCases) { option in
                let isSel = runtime.accentHex == option.accentHex
                Button {
                    runtime.apply(option)
                } label: {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(Color(hex: option.accentHex))
                            .frame(width: 46, height: 46)

                        if isSel {
                            Image(systemName: "checkmark")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(Color(hex: option.inkHex))
                        }
                    }
                    .overlay {
                        if isSel {
                            // Dark hairline ring set 4pt outside the swatch (concentric radius).
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .stroke(AmberTheme.foreground, lineWidth: 2)
                                .frame(width: 54, height: 54)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("强调色：\(option.displayName)" + (isSel ? "，已选中" : ""))
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}
