import SwiftUI
import Shared
import UniformTypeIdentifiers

struct SyncBackupView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    @State private var passphrase = ""
    @State private var exportedFile: IOSSyncBackupDocument?
    @State private var isExportingFile = false
    @State private var isImportingFile = false
    @State private var alert: SyncBackupAlert?

    private var sync: SyncSettings { sharedSettings.snapshot.syncSettings }

    private var evidenceRows: [SyncBackupRow] {
        [
            .init(
                title: "SyncSettings",
                subtitle: "commonMain 模型保存 Google 账号、mode、autoSync、deviceId 等。iOS 只读展示种子值。",
                value: "只读可用",
                color: AmberTheme.accentGreen
            ),
            .init(
                title: "autoSync",
                subtitle: "KMP syncSettings.autoSyncEnabled 真实种子值。",
                value: sync.autoSyncEnabled ? "启用" : "关闭",
                color: sync.autoSyncEnabled ? AmberTheme.accentGreen : AmberTheme.muted2
            ),
            .init(
                title: "mode",
                subtitle: "KMP syncSettings.mode（同步模式）。",
                value: String(describing: sync.mode),
                color: AmberTheme.foreground2
            ),
            .init(
                title: "deviceId",
                subtitle: "KMP syncSettings.deviceId。",
                value: sync.deviceId.isEmpty ? "(空)" : String(sync.deviceId.prefix(12)) + "…",
                color: AmberTheme.foreground2
            ),
        ]
    }

    private var headerSubtitle: String {
        "Settings JSON 加密导出/导入已接通 · 纯本地 AES-GCM"
    }

    private let currentRows: [SyncBackupRow] = [
        .init(
            title: "Google 账号",
            subtitle: "不读取 GoogleDriveAuthSession，也不恢复 Android syncSettings.googleAccountEmail。",
            value: "执行待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "备份状态",
            subtitle: "阶段 1 本地导出/导入不会写 Android lastUploadAt / lastDownloadAt；iOS 恢复本地 Settings 快照。",
            value: "本地可用",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "上传 / 下载",
            subtitle: "没有 Google OAuth、Drive AppData client、云端快照列表、冲突确认或恢复预览。",
            value: "禁用",
            color: AmberTheme.muted
        ),
        .init(
            title: "自动同步",
            subtitle: "不再写本地 @AppStorage；真实字段应来自 SyncSettings.autoSyncEnabled。",
            value: "执行待接",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "加密口令",
            subtitle: "PBKDF2WithHmacSHA256 210,000 次 + AES/GCM/NoPadding；留空时使用 Android NO_PASSPHRASE_FALLBACK。",
            value: "已接通",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "本地导出 / 导入",
            subtitle: "SwiftUI document exporter/importer 打开系统文件面板，读写 .amberbackup 文件。",
            value: "已接通",
            color: AmberTheme.accentGreen
        )
    ]

    private let archiveRows: [SyncBackupRow] = [
        .init(
            title: "Settings JSON",
            subtitle: "iOS 阶段 1 写入真实 KMP Settings JSON；secrets、Room tables 和 file roots 后续阶段再接。",
            value: "iOS 已接",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "Room tables",
            subtitle: "同步 conversations、messages、memory、files、board、mini_app、daily_review、hot_list_source 等表。",
            value: "Android 实现",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "File roots",
            subtitle: "同步 upload、skills、images、chat_images；恢复时校验安全相对路径。",
            value: "Android 实现",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "RestoreScope",
            subtitle: "支持 CONFIG_ONLY / EVERYTHING，并可保留本机 conversations 与 generated media。",
            value: "Android 实现",
            color: AmberTheme.accentGreen
        )
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        localBackupSection
                        evidenceSection
                        currentHandlingSection
                        archiveScopeSection
                        blockedSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .fileExporter(
            isPresented: $isExportingFile,
            document: exportedFile,
            contentType: .amberBackup,
            defaultFilename: "amber-settings-\(exportFileStamp()).amberbackup"
        ) { result in
            switch result {
            case .success:
                alert = .success("已导出加密 Settings 备份")
            case .failure(let error):
                alert = .error("导出文件失败：\(error.localizedDescription)")
            }
        }
        .fileImporter(
            isPresented: $isImportingFile,
            allowedContentTypes: [.amberBackup, .zip, .data, .item],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                importSettingsBackup(from: url)
            case .failure(let error):
                alert = .error("选择文件失败：\(error.localizedDescription)")
            }
        }
        .alert(item: $alert) { alert in
            Alert(title: Text(alert.title), message: Text(alert.message), dismissButton: .default(Text("好")))
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("同步与备份")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(headerSubtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("阶段 1 已接通纯本地 Settings JSON 加密导出/导入：iOS 使用 KMP Settings 真实序列化，按 Android .amberbackup 结构写入 manifest.json + payload.enc，并用 PBKDF2WithHmacSHA256 + AES/GCM/NoPadding 加密。不会连接 Google Drive、WebDAV、S3，也不会上传任何数据。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private var localBackupSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "本地 Settings 备份")
            AmberFormGroup {
                VStack(alignment: .leading, spacing: 10) {
                    SecureField("加密口令（留空使用 Android 兼容 fallback）", text: $passphrase)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    HStack(spacing: 10) {
                        Button(action: exportSettingsBackup) {
                            Label("导出备份", systemImage: "square.and.arrow.up")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)

                        Button(action: { isImportingFile = true }) {
                            Label("导入备份", systemImage: "tray.and.arrow.down")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 13)
            }
            SyncBackupNote("导出内容是真实 KMP Settings JSON。导入会解密并恢复 iOS 本地 sharedSettings 快照；阶段 1 不包含 Room 表、文件树或云同步。")
        }
    }

    private var evidenceSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "能力证据")
            AmberFormGroup {
                ForEach(Array(evidenceRows.enumerated()), id: \.element.id) { index, row in
                    SyncBackupStatusRow(row: row)
                    if index < evidenceRows.count - 1 {
                        SyncBackupDivider()
                    }
                }
            }
        }
    }

    private var currentHandlingSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "iOS 当前处理")
            AmberFormGroup {
                ForEach(Array(currentRows.enumerated()), id: \.element.id) { index, row in
                    SyncBackupStatusRow(row: row)
                    if index < currentRows.count - 1 {
                        SyncBackupDivider()
                    }
                }
            }
        }
    }

    private var archiveScopeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "归档范围")
            AmberFormGroup {
                ForEach(Array(archiveRows.enumerated()), id: \.element.id) { index, row in
                    SyncBackupStatusRow(row: row)
                    if index < archiveRows.count - 1 {
                        SyncBackupDivider()
                    }
                }
            }

            SyncBackupNote("Android BackupVM 在成功后写回 SyncSettings 的时间戳、版本、设备名和 lastError；iOS 当前没有读取或写回这些字段。")
        }
    }

    private var blockedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "后续阶段")
            AmberFormGroup {
                Text("阶段 1 只做本地 Settings JSON 加密备份。Google Drive OAuth、Drive AppData、WebDAV、S3、Room tables、文件树、恢复事务与冲突处理仍未接通，后续阶段再实现。")
                    .font(.caption)
                    .lineSpacing(4)
                    .foregroundStyle(AmberTheme.foreground2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 13)
            }
        }
    }

    private func exportSettingsBackup() {
        do {
            let data = try IOSSyncBackup.export(settings: sharedSettings.snapshot, passphrase: passphrase)
            exportedFile = IOSSyncBackupDocument(data: data)
            isExportingFile = true
        } catch {
            alert = .error("导出失败：\(error.localizedDescription)")
        }
    }

    private func importSettingsBackup(from url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        do {
            let data = try Data(contentsOf: url)
            let result = try IOSSyncBackup.import(data: data, passphrase: passphrase)
            sharedSettings.restoreSnapshot(result.settings)
            alert = .success("已导入 Settings 备份：\(result.preview.manifest.appVersionName)，\(ByteCountFormatter.string(fromByteCount: result.preview.sizeBytes, countStyle: .file))")
        } catch {
            alert = .error("导入失败：\(error.localizedDescription)")
        }
    }

    private func exportFileStamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return formatter.string(from: Date())
    }
}

private struct SyncBackupRow: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let value: String
    let color: Color
}

private struct SyncBackupStatusRow: View {
    let row: SyncBackupRow

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(row.subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(row.value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(row.color)
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
    }
}

private struct SyncBackupDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct SyncBackupNote: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}
