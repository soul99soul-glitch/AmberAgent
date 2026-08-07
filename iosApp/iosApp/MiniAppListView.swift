import SwiftUI

@MainActor
struct MiniAppListView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var repository = IOSMiniAppRepository.shared
    @State private var actionError: String?
    @State private var renameTarget: IOSMiniAppRecord?
    @State private var deleteTarget: IOSMiniAppRecord?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        repositorySection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            repository.reload()
        }
        .sheet(item: $renameTarget) { app in
            MiniAppRenameSheet(app: app) { title, description in
                perform {
                    try repository.rename(id: app.id, title: title, description: description)
                }
                renameTarget = nil
            }
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .confirmationDialog("删除小应用？", isPresented: isDeleteConfirmationPresented, titleVisibility: .visible) {
            if let app = deleteTarget {
                Button("删除「\(app.title)」", role: .destructive) {
                    perform {
                        try repository.delete(id: app.id)
                    }
                    deleteTarget = nil
                }
            }
            Button("取消", role: .cancel) {
                deleteTarget = nil
            }
        } message: {
            Text("会同时删除版本、grant、audit 和 sharedData，本地操作不可恢复。")
        }
    }

    private var isDeleteConfirmationPresented: Binding<Bool> {
        Binding {
            deleteTarget != nil
        } set: { isPresented in
            if !isPresented {
                deleteTarget = nil
            }
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("小应用")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(repository.apps.isEmpty ? "在聊天里生成即可出现" : "\(repository.apps.count) 个")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "小应用设置", size: 44, symbolSize: 18) {
                router.navigate(to: .miniAppSettings)
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        EmptyView()
    }

    private var repositorySection: some View {
        VStack(spacing: 0) {
            if !repository.apps.isEmpty {
                AmberSectionLabel(text: "我的小应用")
            }

            if let storageError = repository.storageError {
                MiniAppCapabilityNote("读取失败：\(storageError)。为保护数据，暂不会覆盖本地文件。")
                    .padding(.bottom, 8)
                    .foregroundStyle(AmberTheme.accentRed)
            }

            if let actionError {
                MiniAppCapabilityNote(actionError)
                    .padding(.bottom, 8)
                    .foregroundStyle(AmberTheme.accentRed)
            }

            if repository.apps.isEmpty {
                emptyState
            } else {
                AmberFormGroup {
                    ForEach(Array(repository.apps.enumerated()), id: \.element.id) { index, app in
                        MiniAppListRow(
                            app: app,
                            metaLine: metaLine(for: app),
                            onOpen: { router.navigate(to: .miniAppRunner(appId: app.id)) },
                            onPin: {
                                perform {
                                    try repository.setPinned(id: app.id, pinned: !app.pinned)
                                }
                            },
                            onRename: { renameTarget = app },
                            onDelete: { deleteTarget = app }
                        )

                        if index < repository.apps.count - 1 {
                            MiniAppCapabilityDivider(leading: 70)
                        }
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "square.grid.2x2")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 56, height: 56)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

            VStack(spacing: 6) {
                Text("还没有小应用")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("在聊天里说「做一个番茄钟小应用」，生成后会保存在这里。")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 28)
        .padding(.vertical, 40)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(AmberTheme.border.opacity(0.55), lineWidth: 1)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private func metaLine(for app: IOSMiniAppRecord) -> String {
        var parts: [String] = ["v\(app.version)"]
        if app.runCount > 0 {
            parts.append("运行 \(app.runCount) 次")
        } else {
            parts.append("尚未打开")
        }
        if !app.permissions.isEmpty {
            let allowed = app.permissions.filter {
                repository.grantDecision(appId: app.id, permission: $0) == .allow
            }.count
            parts.append("权限 \(allowed)/\(app.permissions.count)")
        }
        return parts.joined(separator: " · ")
    }

    private func perform(_ action: () throws -> Void) {
        do {
            try action()
            actionError = nil
        } catch {
            actionError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}

struct MiniAppCapabilityRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let status: String
    let tint: Color
}

struct MiniAppCapabilityStatusRow: View {
    let row: MiniAppCapabilityRow

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.caption)
                    .lineSpacing(3)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 5) {
                Circle()
                    .fill(row.tint)
                    .frame(width: 7, height: 7)
                    .accessibilityHidden(true)
                Text(row.status)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .multilineTextAlignment(.trailing)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

struct MiniAppCapabilityDivider: View {
    var leading: CGFloat = 14

    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, leading)
    }
}

struct MiniAppCapabilityNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
    }
}

private struct MiniAppListRow: View {
    let app: IOSMiniAppRecord
    let metaLine: String
    let onOpen: () -> Void
    let onPin: () -> Void
    let onRename: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button(action: onOpen) {
                HStack(alignment: .center, spacing: 12) {
                    Text(app.iconEmoji ?? "▣")
                        .font(.system(size: 26))
                        .frame(width: 48, height: 48)
                        .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(app.title)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(AmberTheme.foreground)
                                .lineLimit(1)
                            if app.pinned {
                                Image(systemName: "pin.fill")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(AmberTheme.accentAmber)
                            }
                        }

                        if !app.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Text(app.description)
                                .font(.footnote)
                                .foregroundStyle(AmberTheme.muted)
                                .lineLimit(2)
                                .fixedSize(horizontal: false, vertical: true)
                        }

                        Text(metaLine)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted2)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(app.title)
            .accessibilityHint("打开小应用")

            Menu {
                Button(action: onOpen) {
                    Label("打开", systemImage: "play.fill")
                }
                Button(action: onPin) {
                    Label(app.pinned ? "取消置顶" : "置顶", systemImage: app.pinned ? "pin.slash" : "pin")
                }
                Button(action: onRename) {
                    Label("重命名", systemImage: "pencil")
                }
                Button(role: .destructive, action: onDelete) {
                    Label("删除", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 36, height: 36)
                    .background(AmberTheme.surface2, in: Circle())
            }
            .buttonStyle(.plain)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel("更多操作")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct MiniAppRenameSheet: View {
    @Environment(\.dismiss) private var dismiss
    let app: IOSMiniAppRecord
    let onSave: (String, String) -> Void

    @State private var title: String
    @State private var description: String

    init(app: IOSMiniAppRecord, onSave: @escaping (String, String) -> Void) {
        self.app = app
        self.onSave = onSave
        self._title = State(initialValue: app.title)
        self._description = State(initialValue: app.description)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                TextField("名称", text: $title)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1)

                TextField("描述", text: $description, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(2...4)

                Text("标题最多 40 字，描述最多 120 字。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)

                Spacer()
            }
            .padding(18)
            .background(AmberTheme.background)
            .navigationTitle("重命名小应用")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(title, description)
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        MiniAppListView()
    }
    .environment(RouterPath())
}
