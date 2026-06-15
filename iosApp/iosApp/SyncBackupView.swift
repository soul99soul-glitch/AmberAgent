import SwiftUI

struct SyncBackupView: View {
    @Environment(\.dismiss) private var dismiss

    private let evidenceRows: [SyncBackupRow] = [
        .init(
            title: "SyncSettings",
            subtitle: "commonMain model 保存 Google 账号、mode、autoSync、deviceId、lastUpload/download/export、remoteRevision 和 lastError。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "BackupVM",
            subtitle: "Android ViewModel 处理 Google 授权、上传、下载、冲突、导出、导入和恢复确认。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "SyncArchiveManager",
            subtitle: "Android 生成加密归档，写入 Settings、secrets、Room 表和文件树，并支持校验/恢复。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "GoogleDriveSyncRepository",
            subtitle: "Android 通过 Google Identity + Drive AppData 管理云端快照，最多保留 5 份。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "LocalBackupRepository",
            subtitle: "Android 使用系统文档 URI 导出、检查和恢复 .amberbackup 文件。",
            value: "存在",
            color: AmberTheme.accentGreen
        ),
        .init(
            title: "iOS 同步桥",
            subtitle: "当前 SwiftUI 没有 SettingsStore.syncSettings、SyncArchiveManager、Google Drive OAuth 或本地备份 repository。",
            value: "未接线",
            color: AmberTheme.accentAmber
        )
    ]

    private let currentRows: [SyncBackupRow] = [
        .init(
            title: "Google 账号",
            subtitle: "不读取 GoogleDriveAuthSession，也不恢复 Android syncSettings.googleAccountEmail。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "备份状态",
            subtitle: "不读取 lastBackupVersionName、lastUploadAt、lastDownloadAt 或 lastLocalExportAt。",
            value: "未接线",
            color: AmberTheme.accentAmber
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
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "加密口令",
            subtitle: "Android 归档用 PBKDF2WithHmacSHA256 + AES/GCM；iOS 没有口令存储或归档服务。",
            value: "未接线",
            color: AmberTheme.accentAmber
        ),
        .init(
            title: "本地导出 / 导入",
            subtitle: "没有 iOS 文件导出、文件选择、归档格式检查、恢复确认或覆盖事务。",
            value: "禁用",
            color: AmberTheme.muted
        )
    ]

    private let archiveRows: [SyncBackupRow] = [
        .init(
            title: "Settings + secrets",
            subtitle: "归档 Settings；STANDARD 会遮蔽敏感字段，FULL 会包含 WebMount OAuth 和 OpenAI Codex OAuth raw JSON。",
            value: "Android 实现",
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

                Text("Android/KMP 已实现 · iOS 备份桥未接线")
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
        Text("Android/KMP 已有真实同步与备份能力：Settings、数据库、文件树和部分 OAuth secrets 会被打包成加密归档，可上传到 Google Drive AppData 或导出到本地文件。iOS 当前没有这条 repository / OAuth / 归档 / 恢复链路，本页只展示能力证据和缺口，不连接账号、不上传下载、不写 Keychain、不覆盖本机数据。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
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
            AmberSectionLabel(text: "启用前需要")
            AmberFormGroup {
                Text("需要先定义 iOS 侧 SyncSettings 持久化、Keychain/secret redaction 策略、Google Drive OAuth AppData client、本地文件导入导出、归档格式兼容校验、恢复事务与回滚策略。没有这些链路前，按钮会让用户误以为备份真的发生。")
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
