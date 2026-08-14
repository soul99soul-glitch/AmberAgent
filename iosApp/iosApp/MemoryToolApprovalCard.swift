import SwiftUI

struct MemoryToolApprovalCard: View {
    let request: MemoryToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "brain.head.profile")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentAmber.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(cardTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            Text(bodyPreview)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    AmberTheme.surface.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) {
                    ForEach(detailChips) { chip in
                        MemoryToolApprovalChip(chip: chip)
                    }
                    Spacer(minLength: 0)
                }
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(detailChips) { chip in
                        MemoryToolApprovalChip(chip: chip)
                    }
                }
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝记忆写入")

                Button(action: onApprove) {
                    Label(approveLabel, systemImage: approveSystemImage)
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(approveColor, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel(approveLabel)
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(cardTint.opacity(0.34), lineWidth: 0.7)
        }
    }

    private var bodyPreview: String {
        if isDestructive {
            if let targetId = request.targetId {
                return "将永久删除 ID 为 \(targetId) 的已保存记忆。\n\(request.contentPreview ?? "此操作不可撤销。")"
            }
            return "将永久删除这条已保存记忆。\n\(request.contentPreview ?? "此操作不可撤销。")"
        }
        if let contentPreview = request.contentPreview {
            return contentPreview
        }
        if let targetId = request.targetId {
            return "目标记忆 ID \(targetId)"
        }
        return "模型请求修改已保存记忆。"
    }

    private var detailChips: [MemoryToolApprovalChipModel] {
        var chips: [MemoryToolApprovalChipModel] = [
            MemoryToolApprovalChipModel(systemImage: "bolt.horizontal", title: actionLabel)
        ]
        if let scope = request.scope {
            chips.append(MemoryToolApprovalChipModel(systemImage: "tray.full", title: scopeLabel(scope)))
        }
        if let kind = request.kind {
            chips.append(MemoryToolApprovalChipModel(systemImage: "tag", title: kindLabel(kind)))
        }
        if let targetId = request.targetId {
            chips.append(MemoryToolApprovalChipModel(systemImage: "number", title: "\(targetId)"))
        }
        return chips
    }

    private var isDestructive: Bool {
        ["delete", "remove"].contains(request.action.lowercased())
    }

    private var isEdit: Bool {
        ["edit", "update"].contains(request.action.lowercased())
    }

    private var actionLabel: String {
        switch request.action.lowercased() {
        case "create", "add", "write": "保存"
        case "edit", "update": "编辑"
        case "delete", "remove": "删除"
        default: request.action
        }
    }

    private var cardTitle: String {
        if isDestructive { return "删除记忆" }
        if isEdit { return "编辑记忆" }
        return request.title
    }

    private var approveLabel: String {
        if isDestructive { return "删除" }
        if isEdit { return "保存修改" }
        return "保存"
    }

    private var approveSystemImage: String { isDestructive ? "trash" : "checkmark" }
    private var approveColor: Color { isDestructive ? AmberTheme.accentRed : AmberTheme.accent }
    private var cardTint: Color { isDestructive ? AmberTheme.accentRed : AmberTheme.accentAmber }

    private func scopeLabel(_ value: String) -> String {
        switch value {
        case "core":
            "核心"
        case "short_term":
            "短期"
        case "long_term":
            "长期"
        default:
            value
        }
    }

    private func kindLabel(_ value: String) -> String {
        switch value {
        case "note":
            "笔记"
        case "user":
            "用户"
        case "feedback":
            "反馈"
        case "project":
            "项目"
        case "reference":
            "参考"
        case "routine":
            "惯例"
        default:
            value
        }
    }
}

private struct MemoryToolApprovalChipModel: Identifiable {
    let systemImage: String
    let title: String

    var id: String { "\(systemImage)-\(title)" }
}

private struct MemoryToolApprovalChip: View {
    let chip: MemoryToolApprovalChipModel

    var body: some View {
        Label(chip.title, systemImage: chip.systemImage)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(AmberTheme.muted)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .frame(height: 24)
            .background(AmberTheme.surface2.opacity(0.64), in: Capsule())
    }
}

struct WebMountToolApprovalCard: View {
    let request: WebMountToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "globe.badge.chevron.backward")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentCyan)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentCyan.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(request.siteName)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                Text(request.host)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                AmberTheme.surface.opacity(0.72),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )

            HStack(spacing: 6) {
                WebMountApprovalChip(systemImage: "wrench.and.screwdriver", title: request.toolName)
                WebMountApprovalChip(systemImage: "tag", title: request.siteId)
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝 WebMount 前台动作")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("批准 WebMount 前台动作")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentCyan.opacity(0.34), lineWidth: 0.7)
        }
    }
}

private struct WebMountApprovalChip: View {
    let systemImage: String
    let title: String

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(AmberTheme.muted)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .frame(height: 24)
            .background(AmberTheme.surface2.opacity(0.64), in: Capsule())
    }
}

struct SearchToolApprovalCard: View {
    let request: SearchToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: request.toolName == "scrape_web" ? "doc.text.magnifyingglass" : "magnifyingglass.circle")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentCyan)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentCyan.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(request.target)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                Text("\(request.providerName) · \(request.providerType)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                AmberTheme.surface.opacity(0.72),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )

            HStack(spacing: 6) {
                WebMountApprovalChip(systemImage: "wrench.and.screwdriver", title: request.toolName)
                WebMountApprovalChip(systemImage: "network", title: request.providerName)
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝网络搜索")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("批准网络搜索")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentCyan.opacity(0.34), lineWidth: 0.7)
        }
    }
}

struct WorkspaceToolApprovalCard: View {
    let request: WorkspaceToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: request.isWrite ? "folder.badge.gearshape" : "folder")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(request.isWrite ? AmberTheme.accentRed : AmberTheme.accentIndigo)
                    .frame(width: 30, height: 30)
                    .background((request.isWrite ? AmberTheme.accentRed : AmberTheme.accentIndigo).opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(request.action)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                Text(request.target)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                AmberTheme.surface.opacity(0.72),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )

            HStack(spacing: 6) {
                WebMountApprovalChip(systemImage: "wrench.and.screwdriver", title: request.toolName)
                WebMountApprovalChip(systemImage: request.isWrite ? "square.and.pencil" : "doc.text", title: request.isWrite ? "写入" : "读取")
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝 Workspace 工具")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("批准 Workspace 工具")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke((request.isWrite ? AmberTheme.accentRed : AmberTheme.accentIndigo).opacity(0.34), lineWidth: 0.7)
        }
    }
}

struct McpToolApprovalCard: View {
    let request: McpToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void
    @State private var showsFullSkillChanges = false

    var body: some View {
        let isThemeTryOn = request.themePackPreview != nil
        let chrome = isThemeTryOn ? AmberTheme.accent : AmberTheme.accentCyan
        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: isThemeTryOn ? "swatchpalette" : "point.3.connected.trianglepath.dotted")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(chrome)
                    .frame(width: 30, height: 30)
                    .background(chrome.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            if let preview = request.skillImportPreview {
                skillImportPreview(preview)
            } else if let preview = request.soulImportPreview {
                soulImportPreview(preview)
            } else if let preview = request.mcpImportPreview {
                mcpImportPreview(preview)
            } else if let preview = request.themePackPreview {
                themePackPreview(preview)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(request.serverName) / \(request.toolName)")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .lineLimit(1)
                    Text(request.argumentsPreview)
                        .font(.system(size: 11, weight: .regular, design: .monospaced))
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(4)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    AmberTheme.surface.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )
            }

            if request.soulImportPreview == nil
                && request.mcpImportPreview == nil
                && request.skillImportPreview == nil
                && request.themePackPreview == nil {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 6) {
                        WebMountApprovalChip(systemImage: "server.rack", title: request.serverName)
                        WebMountApprovalChip(systemImage: "wrench.and.screwdriver", title: request.toolName)
                        Spacer(minLength: 0)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        WebMountApprovalChip(systemImage: "server.rack", title: request.serverName)
                        WebMountApprovalChip(systemImage: "wrench.and.screwdriver", title: request.toolName)
                    }
                }
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label(
                        request.themePackPreview == nil ? "拒绝" : "还原",
                        systemImage: request.themePackPreview == nil ? "xmark" : "arrow.uturn.backward"
                    )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel(approvalAccessibilityLabel(allow: false))

                Button(action: onApprove) {
                    Label(request.themePackPreview == nil ? "批准" : "套用", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel(approvalAccessibilityLabel(allow: true))
            }
            .padding(.top, 2)
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(chrome.opacity(0.34), lineWidth: 0.7)
        }
    }

    private func approvalAccessibilityLabel(allow: Bool) -> String {
        if request.themePackPreview != nil {
            return allow ? "套用主题" : "还原主题"
        }
        let verb = allow ? "批准" : "拒绝"
        if request.soulImportPreview != nil { return "\(verb)核心指令更新" }
        if request.mcpImportPreview != nil { return "\(verb) MCP 导入" }
        if request.skillImportPreview != nil { return "\(verb) Skill 导入" }
        return "\(verb) MCP 工具"
    }

    private func themePackPreview(_ document: AmberThemePackDocument) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(document.displayName)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
            AmberThemePackMiniPreview(
                palette: (AmberThemeRuntime.Paper(rawValue: document.paper) ?? .neutral).lightPalette,
                accent: Color(hex: (try? AmberThemePackTransfer.parseHex(document.accentHex)) ?? AmberAccentOption.amberGold.accentHex),
                canvasStyle: AmberCanvasStyle(rawValue: document.canvasStyle) ?? .flat,
                paintBrandHint: document.brandMark == AmberBrandMarkStyle.paintAMBER.rawValue,
                serifBrandHint: document.brandMark == AmberBrandMarkStyle.serifWordmark.rawValue
            )
            .frame(width: 180, height: 120)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func soulImportPreview(_ preview: SoulImportPreview) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("核心指令变更")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
            Text("\(String(preview.baseHash.prefix(10))) → \(String(preview.candidateHash.prefix(10)))")
                .font(.caption2.monospaced())
                .foregroundStyle(AmberTheme.muted)
            Text(preview.afterSummary)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
            ScrollView {
                Text(preview.diffPreview)
                    .font(.caption2.monospaced())
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 120)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }

    private func mcpImportPreview(_ preview: McpImportPreview) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Skill \(preview.skillName) · \(preview.servers.count) 个服务")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
            ForEach(preview.servers) { server in
                Text("\(server.name) · \(server.transport) · \(server.origin)")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                if !server.headerNames.isEmpty {
                    Text("headers: \(server.headerNames.joined(separator: ", "))")
                        .font(.caption2.monospaced())
                        .foregroundStyle(AmberTheme.muted2)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }

    private func skillImportPreview(_ preview: McpSkillImportPreview) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(preview.skillName)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Text(preview.mutationKind == .new ? "新建" : "更新")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(preview.mutationKind == .new ? AmberTheme.accentGreen : AmberTheme.accentAmber)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        (preview.mutationKind == .new ? AmberTheme.accentGreen : AmberTheme.accentAmber).opacity(0.12),
                        in: Capsule()
                    )
            }

            Text("导入前摘要：\(preview.beforeSummary)")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
            Text("批准后摘要：\(preview.afterSummary)")
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)

            Text("包哈希  \(shortHash(preview.baseHash) ?? "无") → \(shortHash(preview.candidateHash) ?? preview.candidateHash)")
                .font(.caption2.monospaced().weight(.medium))
                .foregroundStyle(AmberTheme.muted)
                .textSelection(.enabled)

            if !preview.changedFiles.isEmpty {
                DisclosureGroup(isExpanded: $showsFullSkillChanges) {
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 8) {
                            ForEach(preview.changedFiles) { change in
                                skillFileChange(change)
                            }
                        }
                        .padding(.bottom, 4)
                    }
                    .frame(maxHeight: 180)
                } label: {
                    Label("文件变更（\(preview.changedFiles.count) 个文件）", systemImage: "doc.text.magnifyingglass")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.accentCyan)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
                .tint(AmberTheme.accentCyan)
            }

            if preview.containsMcpConfig {
                Label("包含 MCP 配置；批准不会自动连接。", systemImage: "point.3.connected.trianglepath.dotted")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.accentAmber)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }

    private func skillFileChange(_ change: McpSkillImportFileChange) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(fileChangeTitle(change.kind))  \(change.path)")
                .font(.caption2.monospaced().weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)

            if let before = change.beforeText {
                Text("导入前\n\(before.isEmpty ? "（空文件）" : before)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(AmberTheme.muted)
                    .textSelection(.enabled)
            } else if change.kind != .added {
                Text("导入前内容未展示（文件较大或非文本；已纳入包哈希）")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted)
            }
            if let after = change.afterText {
                Text("批准后\n\(after.isEmpty ? "（空文件）" : after)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(AmberTheme.foreground2)
                    .textSelection(.enabled)
            } else if change.kind != .removed {
                Text("批准后内容未展示（文件较大或非文本；已纳入包哈希）")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .padding(8)
        .background(AmberTheme.surface2.opacity(0.55), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
    }

    private func shortHash(_ hash: String?) -> String? {
        guard let hash, !hash.isEmpty else { return nil }
        return String(hash.prefix(10))
    }

    private func fileChangeTitle(_ kind: McpSkillImportFileChangeKind) -> String {
        switch kind {
        case .added: "新增"
        case .modified: "修改"
        case .removed: "删除"
        }
    }
}

/// App-shell strip when a theme try-on is live but the chat approval card is off screen.
struct AmberThemeTryOnBar: View {
    let displayName: String
    let onCommit: () -> Void
    let onRevert: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("正在试穿")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                Text(displayName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            Spacer(minLength: 8)
            Button(action: onRevert) {
                Text("还原")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .padding(.horizontal, 12)
                    .frame(height: 32)
                    .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
            }
            .buttonStyle(.plain)
            .chatApprovalHitTarget()
            .accessibilityLabel("还原主题")
            Button(action: onCommit) {
                Text("套用")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 13)
                    .frame(height: 32)
                    .background(AmberTheme.accent, in: Capsule())
            }
            .buttonStyle(.plain)
            .chatApprovalHitTarget()
            .accessibilityLabel("套用主题")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .amberGlass(cornerRadius: 16)
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AmberTheme.accent.opacity(0.28), lineWidth: 0.7)
        }
    }
}

struct CouncilToolApprovalCard: View {
    let request: CouncilToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: request.systemImage)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentIndigo)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentIndigo.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }

                Spacer(minLength: 0)
            }

            Text(request.objectivePreview)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(4)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    AmberTheme.surface.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )

            if let maxSeats = request.maxSeats {
                Label("最多 \(maxSeats) 个席位", systemImage: "person.2")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }

            HStack(spacing: 8) {
                Spacer()
                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝模型议会")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("批准模型议会")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentIndigo.opacity(0.34), lineWidth: 0.7)
        }
    }
}

struct IshHandoffToolApprovalCard: View {
    let request: IshHandoffToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "terminal")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentAmber.opacity(0.13), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(3)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(request.filename)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                Text(request.commandPreview)
                    .font(.system(size: 11, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                AmberTheme.surface.opacity(0.72),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )

            HStack(spacing: 6) {
                WebMountApprovalChip(systemImage: request.primaryChip.systemImage, title: request.primaryChip.title)
                WebMountApprovalChip(systemImage: request.secondaryChip.systemImage, title: request.secondaryChip.title)
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝 iSH 工具")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("批准 iSH 工具")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentAmber.opacity(0.38), lineWidth: 0.7)
        }
    }
}

struct ChatAskUserCard: View {
    let request: ChatAskUserRequest
    let onAnswer: (String) -> Void
    let onSkip: () -> Void

    @State private var selectedOption: String?
    @State private var customAnswer = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "questionmark.bubble.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accent.opacity(0.12), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Text("回答后会继续当前任务")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }

                Spacer(minLength: 0)
            }

            Text(request.question)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    AmberTheme.surface.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )

            if !request.options.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(request.options, id: \.self) { option in
                        Button {
                            selectedOption = option
                            customAnswer = ""
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: selectedOption == option
                                    ? "checkmark.circle.fill"
                                    : "circle")
                                    .foregroundStyle(selectedOption == option
                                        ? AmberTheme.accent
                                        : AmberTheme.muted)
                                Text(option)
                                    .font(.caption)
                                    .foregroundStyle(AmberTheme.foreground)
                                    .multilineTextAlignment(.leading)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, 10)
                            .frame(maxWidth: .infinity, minHeight: 34, alignment: .leading)
                            .background(
                                selectedOption == option
                                    ? AmberTheme.accentTint
                                    : AmberTheme.surface2.opacity(0.72),
                                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                            )
                        }
                        .buttonStyle(.plain)
                        .chatApprovalHitTarget()
                    }
                }
            }

            TextField(
                request.options.isEmpty ? "输入你的回答" : "或者输入自己的回答",
                text: $customAnswer,
                axis: .vertical
            )
            .lineLimit(1...4)
            .textFieldStyle(.plain)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(
                AmberTheme.surface.opacity(0.72),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .onChange(of: customAnswer) { _, value in
                if !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    selectedOption = nil
                }
            }

            HStack(spacing: 8) {
                Spacer()
                Button(action: onSkip) {
                    Label("跳过", systemImage: "forward")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("跳过提问")

                Button {
                    onAnswer(resolvedAnswer)
                } label: {
                    Label("回答", systemImage: "arrow.up")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .disabled(!canSubmit)
                .opacity(canSubmit ? 1 : 0.55)
                .accessibilityLabel("提交回答")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accent.opacity(0.34), lineWidth: 0.7)
        }
    }

    private var resolvedAnswer: String {
        let custom = customAnswer.trimmingCharacters(in: .whitespacesAndNewlines)
        if !custom.isEmpty { return custom }
        return selectedOption?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private var canSubmit: Bool {
        !resolvedAnswer.isEmpty
    }
}

private extension View {
    func chatApprovalHitTarget() -> some View {
        frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

// MARK: - Wave B2: Recipe 审批卡（mutation step / recipe_import）

/// 复用现有审批卡的视觉结构（amberGlass + 图标 + 摘要块 + 拒绝/批准胶囊），
/// 展示 recipe 专属内容：step 卡显示步骤/工具/参数与 effect class；import 卡
/// 显示 manifest 摘要、权限包络、base/candidate 短哈希、步骤列表（长列表进
/// 可滚动区，§14.2）与「批准后从下一模型轮生效」文案。
struct RecipeToolApprovalCard: View {
    let request: RecipeToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void
    @State private var showsFullRecipeSteps = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "point.3.connected.trianglepath.dotted")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentAmber)
                    .frame(width: 30, height: 30)
                    .background(AmberTheme.accentAmber.opacity(0.13), in: Circle())

                VStack(alignment: .leading, spacing: 3) {
                    Text(request.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)

                    Text(request.reason)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Spacer(minLength: 0)
            }

            HStack(spacing: 6) {
                Text("\(request.recipeName) v\(request.recipeVersion)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }

            switch request.payload {
            case .step(let payload):
                recipeStepPayload(payload)
            case .recipeImport(let payload):
                recipeImportPayload(payload)
            }

            HStack(spacing: 8) {
                Spacer()

                Button(action: onDeny) {
                    Label("拒绝", systemImage: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground2)
                        .padding(.horizontal, 12)
                        .frame(height: 32)
                        .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("拒绝 Recipe 操作")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .chatApprovalHitTarget()
                .accessibilityLabel("批准 Recipe 操作")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentAmber.opacity(0.34), lineWidth: 0.7)
        }
    }

    private func recipeStepPayload(_ payload: RecipeStepApprovalPayload) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("步骤 \(payload.stepId)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(effectClassTitle(payload.effectClass))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(payload.effectClass == .sideEffect ? AmberTheme.accentAmber : AmberTheme.accentGreen)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        (payload.effectClass == .sideEffect ? AmberTheme.accentAmber : AmberTheme.accentGreen).opacity(0.12),
                        in: Capsule()
                    )
            }

            Text("工具：\(payload.tool)")
                .font(.caption2.monospaced().weight(.medium))
                .foregroundStyle(AmberTheme.muted)
                .textSelection(.enabled)
                .lineLimit(2)

            Text(payload.argumentsPreview.isEmpty ? "（无参数）" : payload.argumentsPreview)
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(6)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }

    private func recipeImportPayload(_ payload: RecipeImportApprovalPayload) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text("Recipe \(request.recipeName)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Text(payload.mutationKind == .new ? "新建" : "更新")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(payload.mutationKind == .new ? AmberTheme.accentGreen : AmberTheme.accentAmber)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(
                        (payload.mutationKind == .new ? AmberTheme.accentGreen : AmberTheme.accentAmber).opacity(0.12),
                        in: Capsule()
                    )
            }

            Text(payload.description)
                .font(.caption)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)

            Label(payload.permissionSummary, systemImage: "lock.shield")
                .font(.caption2)
                .foregroundStyle(payload.effectClassRawValue == IOSToolEffectClass.sideEffect.rawValue
                    ? AmberTheme.accentAmber : AmberTheme.muted)
                .fixedSize(horizontal: false, vertical: true)

            Text("包哈希  \(shortHash(payload.baseHash) ?? "无") → \(shortHash(payload.candidateHash) ?? payload.candidateHash)")
                .font(.caption2.monospaced().weight(.medium))
                .foregroundStyle(AmberTheme.muted)
                .textSelection(.enabled)

            if !payload.inputsSummary.isEmpty {
                Text("输入：\(payload.inputsSummary)")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !payload.stepsSummary.isEmpty {
                DisclosureGroup(isExpanded: $showsFullRecipeSteps) {
                    ScrollView(.vertical) {
                        LazyVStack(alignment: .leading, spacing: 6) {
                            ForEach(Array(payload.stepsSummary.enumerated()), id: \.offset) { index, row in
                                Text("\(index + 1). \(row)")
                                    .font(.caption2.monospaced())
                                    .foregroundStyle(AmberTheme.foreground2)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .textSelection(.enabled)
                            }
                        }
                        .padding(.bottom, 4)
                    }
                    .frame(maxHeight: 180)
                } label: {
                    Label("步骤列表（\(payload.stepsSummary.count) 步）", systemImage: "list.number")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.accentCyan)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }
                .tint(AmberTheme.accentCyan)
            }

            if !payload.outputsSummary.isEmpty {
                Text("输出：\(payload.outputsSummary)")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Label("批准后从下一模型轮生效。", systemImage: "arrow.triangle.2.circlepath")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.accentGreen)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
    }

    private func effectClassTitle(_ effectClass: IOSToolEffectClass) -> String {
        switch effectClass {
        case .pure: "只读"
        case .networkRead: "只读网络"
        case .idempotent: "幂等"
        case .sideEffect: "有副作用"
        }
    }

    private func shortHash(_ hash: String?) -> String? {
        guard let hash, !hash.isEmpty else { return nil }
        return String(hash.prefix(10))
    }
}
