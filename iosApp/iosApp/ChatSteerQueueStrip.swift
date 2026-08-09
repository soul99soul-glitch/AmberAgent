import SwiftUI

/// P1-a: composer 上方的生成中排队条。每条排队消息一行（单行截断）+ 44pt 撤销按钮，
/// 顶部一行队列计数；空队列零占位（由调用方 `if !isEmpty` 挂载）。
/// 视觉沿用 composer 附件族的 thinMaterial 圆角带（ComposerPendingFileCard 同款），
/// 不引入新设计语言；字体走语义字号 + ScaledMetric，Reduce Motion 下无动画。
struct ChatSteerQueueStrip: View {
    let entries: [IOSSteerQueueEntry]
    let onRemove: (String) -> Void

    @ScaledMetric(relativeTo: .caption) private var rowMinHeight: CGFloat = 44
    @ScaledMetric(relativeTo: .caption2) private var countFontSize: CGFloat = 12
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("排队消息 \(entries.count) 条")
                .font(.system(size: countFontSize))
                .foregroundStyle(AmberTheme.muted2)
                .accessibilityHidden(true)
                .padding(.bottom, 4)

            ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                HStack(spacing: 10) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(AmberTheme.muted2)
                        .frame(width: 18)
                    Text(entry.text)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.foreground2)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: 0)
                    Button {
                        onRemove(entry.id)
                    } label: {
                        Image(systemName: "arrow.uturn.backward")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(AmberTheme.muted)
                            .frame(width: 26, height: 26)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("撤销第 \(index + 1) 条排队消息")
                }
                .frame(minHeight: rowMinHeight)
                if index < entries.count - 1 {
                    Rectangle()
                        .fill(AmberTheme.borderSoft)
                        .frame(height: 1)
                        .padding(.leading, 28)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("排队消息 \(entries.count) 条")
        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
    }
}
