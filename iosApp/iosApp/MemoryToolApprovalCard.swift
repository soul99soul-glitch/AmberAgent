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

            Text(bodyPreview)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    AmberTheme.surface.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )

            HStack(spacing: 6) {
                ForEach(detailChips) { chip in
                    MemoryToolApprovalChip(chip: chip)
                }
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
                .accessibilityLabel("拒绝记忆写入")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("批准记忆写入")
            }
        }
        .padding(12)
        .amberGlass(cornerRadius: 18)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AmberTheme.accentAmber.opacity(0.34), lineWidth: 0.7)
        }
    }

    private var bodyPreview: String {
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
            MemoryToolApprovalChipModel(systemImage: "bolt.horizontal", title: request.action)
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

struct McpToolApprovalCard: View {
    let request: McpToolApprovalRequest
    let onApprove: () -> Void
    let onDeny: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "point.3.connected.trianglepath.dotted")
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

            HStack(spacing: 6) {
                WebMountApprovalChip(systemImage: "server.rack", title: request.serverName)
                WebMountApprovalChip(systemImage: "wrench.and.screwdriver", title: request.toolName)
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
                .accessibilityLabel("拒绝 MCP 工具")

                Button(action: onApprove) {
                    Label("批准", systemImage: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 13)
                        .frame(height: 32)
                        .background(AmberTheme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("批准 MCP 工具")
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
