import SwiftUI

struct SyncBackupView: View {
    @Environment(\.dismiss) private var dismiss

    @AppStorage("app.amber.ios.syncBackup.autoSync") private var autoSync = true
    @State private var pendingAlert: SyncBackupAlert?

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    intro
                    googleDriveSection
                    autoSyncSection
                    encryptionSection
                    localBackupSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $pendingAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("同步与备份")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var intro: some View {
        Text("把 Provider 配置、助手、记忆、对话与文件加密备份到 Google Drive，或导出为本地文件。备份始终在设备上加密后再上传。")
            .font(.subheadline)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 3)
    }

    private var googleDriveSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Google Drive")
            AmberFormGroup {
                SyncInfoRow(
                    systemImage: "person",
                    iconColor: AmberTheme.accentGreen,
                    title: "Google 账号",
                    subtitle: "已连接 · g·····l@gmail.com",
                    trailing: "已连接",
                    trailingColor: AmberTheme.accentGreen
                )

                SyncDivider()

                SyncInfoRow(
                    systemImage: "checkmark.circle",
                    title: "备份状态",
                    subtitle: "最近成功：上传 2 小时前 · 下载 3 天前"
                )

                SyncDivider()

                SyncActionRow(
                    systemImage: "square.and.arrow.up",
                    title: "上传备份",
                    subtitle: "把当前数据保存到 Google Drive",
                    accent: true
                ) {
                    pendingAlert = .upload
                }

                SyncDivider()

                SyncActionRow(
                    systemImage: "square.and.arrow.down",
                    title: "下载恢复",
                    subtitle: "从 Google Drive 恢复到这台设备",
                    accent: true
                ) {
                    pendingAlert = .download
                }
            }
        }
    }

    private var autoSyncSection: some View {
        AmberFormGroup {
            Button {
                autoSync.toggle()
            } label: {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("自动同步")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("数据变动后在后台自动上传到 Drive")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    BackupSwitch(isOn: autoSync)
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 18)
    }

    private var encryptionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "加密")
            AmberFormGroup {
                SyncActionRow(
                    systemImage: nil,
                    title: "加密口令",
                    subtitle: "用于加密备份内容，请牢记",
                    trailing: "已设置",
                    showsChevron: true
                ) {
                    pendingAlert = .passphrase
                }
            }

            SyncNote("口令仅保存在本机 Keychain，不随备份上传；遗失后将无法解密云端或本地备份。")
        }
    }

    private var localBackupSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "本地备份")
            AmberFormGroup {
                SyncActionRow(
                    systemImage: "doc.badge.arrow.down",
                    title: "导出到文件",
                    subtitle: "把当前数据导出为加密备份文件",
                    showsChevron: true
                ) {
                    pendingAlert = .export
                }

                SyncDivider()

                SyncActionRow(
                    systemImage: "doc.badge.arrow.up",
                    title: "从文件导入",
                    subtitle: "从本地备份文件恢复到这台设备",
                    showsChevron: true
                ) {
                    pendingAlert = .importFile
                }
            }

            SyncNote("恢复会覆盖本机的 Provider 配置、助手、记忆与文件；对话与生成图片默认不覆盖，恢复时可单独勾选。")
        }
    }
}

private struct SyncInfoRow: View {
    let systemImage: String
    var iconColor: Color = AmberTheme.accent
    let title: String
    let subtitle: String
    var trailing: String?
    var trailingColor: Color = AmberTheme.muted

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let trailing {
                Text(trailing)
                    .font(.subheadline)
                    .foregroundStyle(trailingColor)
            }
        }
        .frame(minHeight: 56)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct SyncActionRow: View {
    let systemImage: String?
    let title: String
    let subtitle: String
    var trailing: String?
    var showsChevron = false
    var accent = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(accent ? AmberTheme.accent : AmberTheme.foreground2)
                        .frame(width: 28, height: 28)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(accent ? AmberTheme.accent : AmberTheme.foreground)
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if let trailing {
                    Text(trailing)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                }

                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SyncDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 54)
    }
}

private struct BackupSwitch: View {
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

private struct SyncNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private enum SyncBackupAlert: Identifiable {
    case upload
    case download
    case passphrase
    case export
    case importFile

    var id: String {
        switch self {
        case .upload: "upload"
        case .download: "download"
        case .passphrase: "passphrase"
        case .export: "export"
        case .importFile: "import-file"
        }
    }

    var title: String {
        switch self {
        case .upload: "上传备份"
        case .download: "下载恢复"
        case .passphrase: "加密口令"
        case .export: "导出到文件"
        case .importFile: "从文件导入"
        }
    }

    var message: String {
        switch self {
        case .upload:
            "Google Drive 上传需要 OAuth 授权、加密归档和云端快照服务接线；当前不会发起网络请求。"
        case .download:
            "下载恢复需要 Google Drive 授权、冲突检查和恢复选项；当前不会覆盖本机数据。"
        case .passphrase:
            "加密口令需要接入 Keychain 与备份加密服务；当前只保留设置入口。"
        case .export:
            "本地导出需要生成加密备份文件并打开系统文件保存面板；当前不会写出文件。"
        case .importFile:
            "本地导入需要文件选择、格式校验和恢复确认；当前不会读取或覆盖本机数据。"
        }
    }
}
