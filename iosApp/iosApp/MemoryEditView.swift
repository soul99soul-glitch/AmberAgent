import SwiftUI

struct MemoryEditView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var text: String
    @State private var scope: MemoryEditScope
    @State private var pinned: Bool
    @State private var showDeleteAlert = false

    init(initialText: String, initialScope: String, initialPinned: Bool) {
        self._text = State(initialValue: initialText)
        self._scope = State(initialValue: MemoryEditScope(title: initialScope))
        self._pinned = State(initialValue: initialPinned)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    contentSection
                    classificationSection
                    deleteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert("删除这条记忆", isPresented: $showDeleteAlert) {
            Button("保留", role: .cancel) { }
            Button("删除", role: .destructive) {
                dismiss()
            }
        } message: {
            Text("当前编辑页尚未接入真实记忆库；不会删除本地数据。")
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回核心记忆", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("编辑记忆")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            MemoryDoneButton {
                dismiss()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var contentSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "内容")

            ZStack(alignment: .topLeading) {
                TextEditor(text: $text)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineSpacing(3)
                    .scrollContentBackground(.hidden)
                    .padding(12)
                    .frame(minHeight: 176)

                if text.isEmpty {
                    Text("记一条值得长期留存的信息...")
                        .font(.body)
                        .foregroundStyle(AmberTheme.muted2)
                        .padding(.horizontal, 17)
                        .padding(.vertical, 20)
                        .allowsHitTesting(false)
                }
            }
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
        }
    }

    private var classificationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "归类")
            AmberFormGroup {
                Menu {
                    ForEach(MemoryEditScope.allCases) { option in
                        Button(option.title) {
                            scope = option
                        }
                    }
                } label: {
                    MemoryValueRow(
                        title: "记忆层级",
                        subtitle: "核心几乎总注入 · 长期稳定保留 · 短期会过期",
                        value: scope.title
                    )
                }

                MemoryEditDivider()

                Button {
                    pinned.toggle()
                } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("置顶")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                            Text("置顶后召回优先级最高、几乎每次对话都会注入")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        MemoryEditSwitch(isOn: pinned)
                    }
                    .frame(minHeight: 58)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            MemoryEditNote(text.isEmpty ? "-" : "本地草稿 · \(scope.title) · \(pinned ? "已置顶" : "未置顶")")
                .padding(.top, 2)
        }
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                showDeleteAlert = true
            } label: {
                Text("删除这条记忆")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
    }
}

private enum MemoryEditScope: String, CaseIterable, Identifiable {
    case core
    case longTerm
    case shortTerm

    init(title: String) {
        switch title {
        case "长期":
            self = .longTerm
        case "短期":
            self = .shortTerm
        default:
            self = .core
        }
    }

    var id: String { rawValue }

    var title: String {
        switch self {
        case .core: "核心"
        case .longTerm: "长期"
        case .shortTerm: "短期"
        }
    }
}

private struct MemoryValueRow: View {
    let title: String
    let subtitle: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
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
                .font(.subheadline)
                .foregroundStyle(AmberTheme.muted)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct MemoryEditSwitch: View {
    let isOn: Bool

    var body: some View {
        Capsule()
            .fill(isOn ? AmberTheme.accent : AmberTheme.surface2)
            .frame(width: 48, height: 28)
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(Color.white)
                    .frame(width: 24, height: 24)
                    .shadow(color: .black.opacity(0.16), radius: 3, y: 1)
                    .padding(2)
            }
            .animation(.snappy(duration: 0.18), value: isOn)
    }
}

private struct MemoryEditDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct MemoryEditNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
    }
}

private struct MemoryDoneButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("完成")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(height: 36)
                .padding(.horizontal, 14)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .amberGlass(cornerRadius: AmberTheme.radiusPill)
        .accessibilityLabel("完成")
    }
}
