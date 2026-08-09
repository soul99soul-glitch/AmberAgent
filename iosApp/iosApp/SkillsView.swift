import SwiftUI
import Shared

struct SkillsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    let sharedSettings: IOSSharedSettingsStore

    @State private var scannedSkills: [IosSkillFactory.SkillMetadata] = []

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    scannedSkillsSection
                    extensionSection
                    managementSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        // 从详情删除返回后也要重扫；仅依赖首次 onAppear 会留下已删条目。
        .onAppear { rescanSkills() }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("技能")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassIconButton(
                systemImage: "plus",
                accessibilityLabel: "添加技能",
                size: 44,
                symbolSize: 20,
                tint: AmberTheme.accent,
                prominent: true
            ) {
                router.navigate(to: .skillAdd)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var scannedSkillsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "本机技能")
            AmberFormGroup {
                if scannedSkills.isEmpty {
                    SkillEmptyState()
                } else {
                    ForEach(Array(scannedSkills.enumerated()), id: \.offset) { index, skill in
                        let enabled = sharedSettings.isSkillEnabled(skill.dirName)
                        Button {
                            router.navigate(to: .skillDetail(name: skill.name, dirName: skill.dirName))
                        } label: {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(skill.name)
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(AmberTheme.foreground)
                                    if !skill.description_.isEmpty {
                                        Text(skill.description_)
                                            .font(.caption)
                                            .foregroundStyle(AmberTheme.muted)
                                            .lineLimit(2)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                Text(enabled ? "启用" : "关闭")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(enabled ? AmberTheme.accentGreen : AmberTheme.muted2)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AmberTheme.muted2)
                            }
                            .frame(minHeight: 52)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if index < scannedSkills.count - 1 {
                            Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                        }
                    }
                }
            }
        }
    }

    private var extensionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "扩展")
            AmberFormGroup {
                SkillUtilityRow(
                    systemImage: "point.3.connected.trianglepath.dotted",
                    iconColor: AmberTheme.accentCyan,
                    title: "MCP 服务器",
                    subtitle: "管理可供聊天使用的外部工具服务器"
                ) {
                    router.navigate(to: .mcpServers)
                }
            }
        }
    }

    private var managementSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "管理")
            AmberFormGroup {
                SkillUtilityRow(
                    systemImage: "arrow.triangle.2.circlepath",
                    iconColor: AmberTheme.accent,
                    title: "扫描本机技能",
                    subtitle: "刷新本机技能列表"
                ) {
                    rescanSkills()
                }
            }
        }
    }

    private func rescanSkills() {
        if let docsDir = try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true).path {
            scannedSkills = IosSkillFactory.shared.listSkills(documentsDir: docsDir)
        }
    }
}

private struct SkillEmptyState: View {
    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(AmberTheme.surface2.opacity(0.82))
                Image(systemName: "doc.questionmark")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(width: 52, height: 52)

            VStack(spacing: 4) {
                Text("暂无本机技能")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("点右上角添加技能，或在下方重新扫描本机目录。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 24)
    }
}

private struct SkillUtilityRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    var trailing: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(iconColor)
                    .frame(width: 32, height: 32)
                    .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
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
                        .foregroundStyle(AmberTheme.muted)
                }

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 60)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
