import SwiftUI

struct CouncilSettingsView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    @State private var councilEnabled = true
    @State private var rounds = 2
    @State private var maxSeats = "8 位"
    @State private var timeout = "3 分钟"
    @State private var outputLimit = "标准"
    @State private var synthesisModel = "GPT · gpt-5.2"

    private let seats: [CouncilSettingsSeat] = [
        .init(initials: "Cx", name: "工程", detail: "codex · 架构复杂度与实现成本", badge: "CLI"),
        .init(initials: "K2", name: "风险", detail: "kimi-k2 · 隐私、边界与失败模式", badge: "模型"),
        .init(initials: "Gm", name: "多模态", detail: "gemini-3-pro · 跨图文核验", badge: "模型"),
        .init(initials: "GLM", name: "中文心智", detail: "glm-4.6 · 中文语感与本地语境", badge: "模型")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        masterSection
                        runtimeSection
                        coreSeatsSection
                        lensSeatsSection
                        limitsSection
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
        Text("多个已配置的模型并行评审、或有限轮辩论同一议题，自动比较分歧与证据，再由综合模型收束出结论。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var masterSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                CouncilSettingsToggleRow(
                    systemImage: "bubble.left.and.bubble.right",
                    iconColor: AmberTheme.accent,
                    title: "启用模型议会",
                    subtitle: "在对话里用「议会」发起多模型审议",
                    isOn: $councilEnabled
                )
            }
            CouncilFootnote(text: "议会席位只做纯文本生成，不继承工具、记忆或完整聊天记录，每次只就单个议题独立审议。")
        }
    }

    private var runtimeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "运行")
            AmberFormGroup {
                Menu {
                    Button("GPT · gpt-5.2") { synthesisModel = "GPT · gpt-5.2" }
                    Button("Claude · opus-4.8") { synthesisModel = "Claude · opus-4.8" }
                    Button("Gemini · 3 Pro") { synthesisModel = "Gemini · 3 Pro" }
                } label: {
                    CouncilSettingsRow(
                        systemImage: "point.3.connected.trianglepath.dotted",
                        iconColor: AmberTheme.accentIndigo,
                        title: "综合模型",
                        subtitle: "综合各方证据、给出最终结论",
                        trailing: synthesisModel,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var coreSeatsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "核心席位 · 辩论模式自动加入")
            AmberFormGroup {
                HStack(spacing: 7) {
                    CoreSeatChip(title: "支持者", color: AmberTheme.accentGreen)
                    CoreSeatChip(title: "反对者", color: AmberTheme.accentRed)
                    CoreSeatChip(title: "裁判", color: AmberTheme.accent)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            CouncilFootnote(text: "辩论模式下自动加入这三个结构角色；自由群聊只让你配置的成员并行作答，不强加这三席。")
        }
    }

    private var lensSeatsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "领域席位 · 你预先配置")
            AmberFormGroup {
                ForEach(Array(seats.enumerated()), id: \.element.id) { index, seat in
                    Button {
                        router.navigate(to: .seatEditor)
                    } label: {
                        CouncilSeatRow(seat: seat)
                    }
                    .buttonStyle(.plain)

                    CouncilSettingsDivider()
                }

                Button {
                    router.navigate(to: .seatEditor)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "plus")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(width: 30, height: 30)

                        Text("添加席位")
                            .font(.body.weight(.medium))
                            .foregroundStyle(AmberTheme.accent)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 56)
                    .padding(.horizontal, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            CouncilFootnote(text: "主持也可以按议题临时追加领域席位；这里配的是默认就位的那几位。最多 8 席。")
        }
    }

    private var limitsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "限制")
            AmberFormGroup {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("默认轮数")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("每席发言的来回轮次，最多 5 轮")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    CouncilStepper(value: $rounds, range: 1...5)
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)

                CouncilSettingsDivider()

                CouncilMenuRow(title: "最大席位", subtitle: "含自动加入的核心三席", value: maxSeats, options: ["3 位", "5 位", "6 位", "8 位"]) {
                    maxSeats = $0
                }

                CouncilSettingsDivider()

                CouncilMenuRow(title: "席位超时", subtitle: "单个模型未返回即跳过", value: timeout, options: ["1 分钟", "3 分钟", "8 分钟", "20 分钟"]) {
                    timeout = $0
                }

                CouncilSettingsDivider()

                CouncilMenuRow(title: "输出上限", subtitle: "整场审议输出的长度上限", value: outputLimit, options: ["标准", "加长"]) {
                    outputLimit = $0
                }
            }
        }
    }
}

private struct CouncilSettingsSeat: Identifiable {
    let id = UUID()
    let initials: String
    let name: String
    let detail: String
    let badge: String
}

private struct CouncilSettingsToggleRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(AmberTheme.accent)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct CouncilSettingsRow: View {
    let systemImage: String?
    var iconColor = AmberTheme.accent
    let title: String
    var subtitle: String?
    var trailing: String?
    var showsChevron = false

    var body: some View {
        HStack(spacing: 12) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
                    .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let trailing {
                Text(trailing)
                    .font(.system(size: 13, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct CoreSeatChip: View {
    let title: String
    let color: Color

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
            Text(title)
                .font(.system(size: 12.5))
                .foregroundStyle(AmberTheme.foreground2)
        }
        .padding(.leading, 9)
        .padding(.trailing, 12)
        .padding(.vertical, 5)
        .background(AmberTheme.surface2, in: Capsule())
    }
}

private struct CouncilSeatRow: View {
    let seat: CouncilSettingsSeat

    var body: some View {
        HStack(spacing: 12) {
            Text(seat.initials)
                .font(.system(size: 11.5, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(width: 30, height: 30)
                .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(seat.name)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(seat.detail)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(seat.badge)
                .font(.system(size: 10, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 6)
                .padding(.vertical, 1.5)
                .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 5, style: .continuous))

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct CouncilMenuRow: View {
    let title: String
    let subtitle: String
    let value: String
    let options: [String]
    let onSelect: (String) -> Void

    var body: some View {
        Menu {
            ForEach(options, id: \.self) { option in
                Button(option) {
                    onSelect(option)
                }
            }
        } label: {
            CouncilSettingsRow(
                systemImage: nil,
                title: title,
                subtitle: subtitle,
                trailing: value,
                showsChevron: true
            )
        }
        .buttonStyle(.plain)
    }
}

private struct CouncilStepper: View {
    @Binding var value: Int
    let range: ClosedRange<Int>

    var body: some View {
        HStack(spacing: 0) {
            Button {
                value = max(range.lowerBound, value - 1)
            } label: {
                Image(systemName: "minus")
                    .font(.system(size: 12, weight: .bold))
                    .frame(width: 32, height: 28)
            }
            .disabled(value == range.lowerBound)

            Text("\(value)")
                .font(.system(size: 14, weight: .semibold, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground)
                .frame(minWidth: 26)

            Button {
                value = min(range.upperBound, value + 1)
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 12, weight: .bold))
                    .frame(width: 32, height: 28)
            }
            .disabled(value == range.upperBound)
        }
        .foregroundStyle(AmberTheme.accent)
        .background(AmberTheme.surface2, in: Capsule())
        .buttonStyle(.plain)
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
        CouncilSettingsView()
            .environment(RouterPath())
    }
}
