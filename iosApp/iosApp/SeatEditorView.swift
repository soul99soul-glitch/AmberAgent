import SwiftUI
import Shared

struct SeatEditorView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @State private var name = "工程"
    @State private var role = "工程"
    @State private var runner: SeatRunnerType = .provider
    @State private var model = "kimi-k2"
    @State private var reasoning = "高"
    @State private var cliTool = "Codex"
    @State private var cliModel = ""
    @State private var prompt = ""
    @State private var showRemoveInfo = false
    @State private var isRemoving = false
    @State private var removeResultMessage: String?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        noticeSection
                        identitySection
                        modelSection
                        previewSection
                        deleteSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert("移除席位", isPresented: $showRemoveInfo) {
            Button("移除", role: .destructive) {
                removeAllCustomSeats()
            }
            Button("取消", role: .cancel) { }
        } message: {
            Text("将从快照 agentRuntime.modelCouncil.defaultSeats 与 UserDefaults 删除全部 \(customSeatCount) 个自定义席位。KMP seed 席位不可删除。此操作不可恢复。")
        }
        .alert("移除结果", isPresented: Binding(
            get: { removeResultMessage != nil },
            set: { if !$0 { removeResultMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { }
        } message: {
            Text(removeResultMessage ?? "")
        }
    }

    // [Slice 4] 真删除：从后往前逐个调 removeCouncilSeat(at:)，
    // 每次 removeCouncilSeat 都会同步从快照（IosSettingsMutations.removeCouncilSeat）
    // 与 legacy 镜像移除。KMP seed 席位不受影响。
    private func removeAllCustomSeats() {
        isRemoving = true
        let count = customSeatCount
        // 从尾部删，避免索引位移
        for index in stride(from: count - 1, through: 0, by: -1) {
            sharedSettings.removeCouncilSeat(at: index)
        }
        isRemoving = false
        removeResultMessage = count > 0 ? "已移除 \(count) 个自定义席位。" : "没有可移除的自定义席位。"
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回模型议会", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("席位设置")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button("关闭") {
                dismiss()
            }
            .font(.system(size: 14.5, weight: .semibold))
            .foregroundStyle(AmberTheme.foreground2)
            .frame(height: 30)
            .padding(.horizontal, 13)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var noticeSection: some View {
        SeatEditorFootnote(text: "自定义席位只保存名称、角色和模型 ID，重启后保留；modelId 为 UUID 时同步写入 KMP snapshot.defaultSeats。")
            .padding(.bottom, 10)
    }

    private var identitySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "席位")
            AmberFormGroup {
                HStack(spacing: 12) {
                    Text("名称")
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .frame(width: 68, alignment: .leading)

                    TextField("未命名席位", text: $name)
                        .font(.system(size: 15))
                        .foregroundStyle(AmberTheme.foreground)
                        .multilineTextAlignment(.trailing)
                        .textFieldStyle(.plain)
                }
                .frame(minHeight: 56)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)

                SeatEditorDivider()

                Menu {
                    ForEach(["产品", "营销", "公关", "工程", "用户体验", "风险"], id: \.self) { option in
                        Button(option) {
                            role = option
                            if name.isEmpty || ["产品", "营销", "公关", "工程", "用户体验", "风险"].contains(name) {
                                name = option
                            }
                        }
                    }
                } label: {
                    SeatEditorRow(
                        systemImage: nil,
                        title: "角色预设",
                        subtitle: "决定这位席位的评审立场",
                        trailing: role,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }
            SeatEditorFootnote(text: "名称和角色会保存到自定义席位；内置预设席位仍为只读。")
        }
    }

    private var modelSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模型")
            providerRunnerGroup

            SeatEditorFootnote(text: "当前 iOS 自定义席位只保存 provider modelId。external_cli、思考档位和席位 prompt 文件不再展示为可编辑项。")
        }
    }

    private var providerRunnerGroup: some View {
        AmberFormGroup {
            Menu {
                ForEach(["kimi-k2", "codex", "gemini-3-pro", "glm-4.6", "claude-haiku-4.5"], id: \.self) { option in
                    Button(option) { model = option }
                }
            } label: {
                SeatEditorRow(
                    systemImage: "cpu",
                    iconColor: AmberTheme.accentIndigo,
                    title: "模型",
                    trailing: model,
                    showsChevron: true
                )
            }
            .buttonStyle(.plain)
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已保存席位（UserDefaults · 可读写）")
            AmberFormGroup {
                let seats = sharedSettings.savedCouncilSeats
                if seats.isEmpty {
                    Text("暂无自定义席位。填写上方名称、角色和模型后点「保存席位」添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(seats.enumerated()), id: \.offset) { index, seat in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(seat["name"] ?? "?").font(.body.weight(.semibold))
                                Text("\(seat["role"] ?? "?") · \(seat["modelId"] ?? "?")")
                                    .font(.system(size: 11, design: .monospaced)).foregroundStyle(AmberTheme.muted2)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Button { sharedSettings.removeCouncilSeat(at: index) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 18)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 48).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < seats.count - 1 { SeatEditorDivider() }
                    }
                }
                SeatEditorDivider()
                Button {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else { return }
                    sharedSettings.addCouncilSeat(name: trimmed, role: role, modelId: model)
                    name = ""
                } label: {
                    Label("保存席位", systemImage: "plus.circle.fill").font(.body.weight(.semibold)).foregroundStyle(AmberTheme.accent)
                }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.vertical, 8)
            }
            SeatEditorFootnote(text: "自定义席位保存到 UserDefaults，重启后保留；合法 UUID modelId 会同步到 snapshot。KMP seed 席位（\(sharedSettings.agentRuntime.modelCouncil.defaultSeats.count) 席）仍为只读。")
        }
    }

    // [Slice 4] 移除席位：真接。删除所有用户自定义席位（从快照
    // agentRuntime.modelCouncil.defaultSeats 与 legacy 镜像同步移除）。
    // KMP seed 席位不可删除（不在 savedCouncilSeats 里）。
    private var deleteSection: some View {
        AmberFormGroup {
            Button(role: .destructive) {
                showRemoveInfo = true
            } label: {
                HStack(spacing: 8) {
                    if isRemoving {
                        ProgressView().controlSize(.small)
                    }
                    Text(customSeatCount == 0 ? "没有可移除的自定义席位" : "移除全部 \(customSeatCount) 个自定义席位")
                        .font(.body)
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 54)
                        .contentShape(Rectangle())
                }
            }
            .buttonStyle(.plain)
            .disabled(customSeatCount == 0 || isRemoving)
        }
        .padding(.top, 20)
    }

    private var customSeatCount: Int {
        sharedSettings.savedCouncilSeats.count
    }
}

private struct SeatEditorStatusLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 46)
        .padding(.horizontal, 14)
        .padding(.vertical, 6)
    }
}

private enum SeatRunnerType: String, CaseIterable, Identifiable {
    case provider
    case cli

    var id: String { rawValue }

    var title: String {
        switch self {
        case .provider: "Provider 模型"
        case .cli: "外部 CLI"
        }
    }
}

private struct SeatEditorRow: View {
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
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
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
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct SeatEditorDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct SeatEditorFootnote: View {
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
        SeatEditorView(sharedSettings: IOSSharedSettingsStore())
    }
}
