import SwiftUI

/// P1-a: composer 上方排队条。与 dock 行同宽（右缘对齐发送键）；材质走
/// `composerDockGlass`。空队列零占位；≤3 条按内容高度，超出才限高滚动。
struct ChatSteerQueueStrip: View {
    let entries: [IOSSteerQueueEntry]
    let onRemove: (String) -> Void

    private static let maxVisibleRows = 3
    private static let cornerRadius: CGFloat = 18

    @ScaledMetric(relativeTo: .caption) private var rowMinHeight: CGFloat = 36
    @ScaledMetric(relativeTo: .caption2) private var countFontSize: CGFloat = 12
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("排队消息 \(entries.count) 条")
                .font(.system(size: countFontSize))
                .foregroundStyle(AmberTheme.muted2)
                .accessibilityHidden(true)
                .padding(.bottom, 2)

            if entries.count > Self.maxVisibleRows {
                ScrollView {
                    rows
                }
                .frame(maxHeight: rowMinHeight * CGFloat(Self.maxVisibleRows) + CGFloat(Self.maxVisibleRows - 1))
            } else {
                rows
            }
        }
        .padding(.leading, 10)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .composerDockGlass(cornerRadius: Self.cornerRadius)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("排队消息 \(entries.count) 条")
        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
    }

    private var rows: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                HStack(spacing: 8) {
                    Image(systemName: entry.hasAttachments ? "paperclip" : "clock.arrow.circlepath")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(AmberTheme.muted2)
                        .frame(width: 16)
                    Text(Self.rowTitle(for: entry))
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 0)
                    Button {
                        onRemove(entry.id)
                    } label: {
                        Image(systemName: "arrow.uturn.backward")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(AmberTheme.muted)
                            .frame(width: 28, height: 28)
                            .contentShape(.interaction, Rectangle().inset(by: -8))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("撤销第 \(index + 1) 条排队消息")
                }
                .frame(minHeight: rowMinHeight)
                if index < entries.count - 1 {
                    Rectangle()
                        .fill(AmberTheme.borderSoft.opacity(0.7))
                        .frame(height: 1)
                        .padding(.leading, 24)
                }
            }
        }
    }

    /// 测试与预览共用的行标题规则。
    static func rowTitle(for entry: IOSSteerQueueEntry) -> String {
        let trimmed = entry.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        let imageLabel: String? = {
            guard !entry.images.isEmpty else { return nil }
            return entry.images.count == 1 ? "图片" : "\(entry.images.count) 张图片"
        }()
        if let imageLabel, let file = entry.selectedFile {
            return "\(imageLabel) · \(file.fileName)"
        }
        if let imageLabel { return imageLabel }
        if let file = entry.selectedFile { return file.fileName }
        return "排队消息"
    }
}
