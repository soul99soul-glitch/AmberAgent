import SwiftUI
import Shared

struct CouncilSettingsView: View {
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
                        intro
                        seatDraftSection
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
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("模型议会")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("添加或删除自定义席位。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var seatDraftSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "自定义席位")
            AmberFormGroup {
                let seats = sharedSettings.savedCouncilSeats
                if seats.isEmpty {
                    Text("暂无自定义席位。点「添加席位」进入编辑页添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(seats.enumerated()), id: \.offset) { index, seat in
                        HStack(spacing: 10) {
                            Text(seat["name"] ?? "?").font(.body.weight(.semibold))
                            Spacer()
                            Text(seat["role"] ?? "?").font(.caption).foregroundStyle(AmberTheme.muted2)
                            Button { sharedSettings.removeCouncilSeat(at: index) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 16)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 44).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < seats.count - 1 { CouncilSettingsDivider() }
                    }
                }
            }
            Button {
                router.navigate(to: .seatEditor)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "plus").font(.system(size: 17, weight: .semibold)).foregroundStyle(AmberTheme.accent).frame(width: 30, height: 30)
                    Text("添加席位").font(.body.weight(.medium)).foregroundStyle(AmberTheme.accent).frame(maxWidth: .infinity, alignment: .leading)
                }.frame(minHeight: 56).padding(.horizontal, 14).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            CouncilFootnote(text: "自定义席位会保存在本机，重启后仍然保留。")
        }
    }

}

private struct CouncilSettingsDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct CouncilFootnote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

#Preview {
    NavigationStack {
        CouncilSettingsView(sharedSettings: IOSSharedSettingsStore())
            .environment(RouterPath())
    }
}
