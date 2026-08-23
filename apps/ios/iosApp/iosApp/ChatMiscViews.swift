import SwiftUI
import UIKit
import Shared

/// UIKit 驱动的 variableColor SF Symbol 动画(等价 SwiftUI
/// `.symbolEffect(.variableColor.iterative.reversing, isActive:)`)。
/// 动画运行在 CA/UIKit 层,不占用 SwiftUI ViewGraph 的每帧更新预算——
/// 这是"隔离常驻指示动画"的标准做法,不是动画降级。
struct ChatUIKitVariableColorSymbol: UIViewRepresentable {
    let systemName: String
    let pointSize: CGFloat
    let weight: UIFont.Weight
    let tint: UIColor
    let isActive: Bool

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.preferredSymbolConfiguration = UIImage.SymbolConfiguration(
            pointSize: pointSize,
            weight: symbolWeight
        )
        view.image = UIImage(systemName: systemName)
        view.tintColor = tint
        view.setContentHuggingPriority(.required, for: .horizontal)
        view.setContentHuggingPriority(.required, for: .vertical)
        view.setContentCompressionResistancePriority(.required, for: .horizontal)
        view.setContentCompressionResistancePriority(.required, for: .vertical)
        applyEffect(to: view, active: isActive)
        context.coordinator.effectActive = isActive
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        view.tintColor = tint
        if context.coordinator.effectActive != isActive {
            applyEffect(to: view, active: isActive)
            context.coordinator.effectActive = isActive
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator {
        var effectActive = false
    }

    private var symbolWeight: UIImage.SymbolWeight {
        switch weight {
        case .bold: return .bold
        case .medium: return .medium
        case .regular: return .regular
        default: return .semibold
        }
    }

    private func applyEffect(to view: UIImageView, active: Bool) {
        if active {
            view.addSymbolEffect(.variableColor.iterative.reversing)
        } else {
            view.removeAllSymbolEffects()
        }
    }
}

struct ChatReasoningCard: View {
    let bodyText: String
    var isThinking: Bool = false
    var startedAt: Date? = nil
    var finishedSeconds: Double? = nil
    var levelLabel: String? = nil
    var autoCloseThinking: Bool = true
    @State private var isExpanded: Bool
    @State private var userToggled = false
    /// Reduce Motion 下的自动收起跳过动画（collection 终态即刻重测，动画会错相位）。
    /// 用户手动 toggle 与思考开始展开前复位。
    @State private var suppressesShowsBodyAnimation = false
    /// 终态软收起：高度上限逐帧 ramp 到 0，cell 逐帧重测、滚动逐帧钉底，
    /// 上方内容平滑上移——替代单帧砍掉 100+pt 的跳变。收起期间 body 保持挂载。
    @State private var isCollapsingAtTerminal = false
    @State private var collapseHeightLimit: CGFloat? = nil
    @State private var collapseToken = UUID()
    /// 思考正文是否被高度上限裁切——驱动 mask 底部渐变（未裁切不洗淡短正文）。
    @State private var bodyIsClipped = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// 正文可见 = 已展开，或终态软收起进行中（高度已 ramp 到 0 但尚未卸载）。
    private var bodyPresent: Bool { showsBody || isCollapsingAtTerminal }

    /// 正文高度上限：软收起期间用 ramp 值，否则思考中 180 / 完成后 260。
    private var bodyHeightLimit: CGFloat {
        collapseHeightLimit ?? (isThinking ? 180 : 260)
    }

    init(
        bodyText: String,
        isThinking: Bool = false,
        startedAt: Date? = nil,
        finishedSeconds: Double? = nil,
        levelLabel: String? = nil,
        autoCloseThinking: Bool = true
    ) {
        self.bodyText = bodyText
        self.isThinking = isThinking
        self.startedAt = startedAt
        self.finishedSeconds = finishedSeconds
        self.levelLabel = levelLabel
        self.autoCloseThinking = autoCloseThinking
        // Streaming reasoning should be visible: it reassures the user that the agent is working.
        // The body gets a fixed live height below, so visibility does not fight chat scrolling.
        let hasInitialBodyText = Self.hasVisibleText(bodyText)
        self._isExpanded = State(initialValue: hasInitialBodyText && (isThinking ? true : !autoCloseThinking))
    }

    /// 推理正文是否含可见字符。
    ///
    /// 不用 `trimmingCharacters(in:).isEmpty`:它的成本取决于首字符是否为空白。
    /// 首字符非空白时 Foundation 走零拷贝快路径(1M 字符实测 ~2µs);一旦正文以
    /// 空白或换行开头(模型 thinking 很常见),它会真的分配一份全文副本——同规模
    /// 实测 ~90µs/次。而 `hasBodyText` 经 `showsBody` 在一次 body 求值中被求值
    /// 约 10 次(圆角/chevron/高度/mask/animation 等处各一次),流式期间每 48ms
    /// 一轮,叠加后接近 0.9ms,是 120Hz 单帧预算(8.3ms)的一成以上。
    /// `contains` 在首个非空白字符处返回,与首字符形态无关,恒为亚微秒。
    static func hasVisibleText(_ text: String) -> Bool {
        text.contains { !$0.isWhitespace }
    }

    static func animatesStreamingBody(isThinking: Bool, reduceMotion: Bool) -> Bool {
        isThinking && !reduceMotion
    }

    private var levelSuffix: String {
        guard let levelLabel, !levelLabel.isEmpty else { return "" }
        return " · \(levelLabel)"
    }

    private var hasBodyText: Bool {
        Self.hasVisibleText(bodyText)
    }

    private var showsBody: Bool {
        isExpanded && hasBodyText
    }

    private func setExpanded(_ expanded: Bool, duration: Double) {
        guard isExpanded != expanded else { return }
        if reduceMotion {
            isExpanded = expanded
        } else {
            withAnimation(.easeInOut(duration: duration)) {
                isExpanded = expanded
            }
        }
    }

    /// 终态软收起：高度上限从思考期上限逐帧 ramp 到 0（0.2s easeOut），
    /// representable 每帧按新上限重测、collection 高度连续变化，滚动逐帧钉底——
    /// 上方内容平滑上移，替代旧方案单帧砍掉百余 pt 的跳变。ramp 结束后再真正
    /// 卸载正文（此刻高度已为 0，卸载不再产生任何尺寸变化）。
    private func collapseSoftlyAtTerminal() {
        guard showsBody else {
            isExpanded = false
            return
        }
        let token = UUID()
        collapseToken = token
        isCollapsingAtTerminal = true
        // ramp 已驱动全部视觉运动；卸载时的 showsBody 翻转（chevron/圆角）
        // 不再走 0.28s 动画，避免内容收口后的「二次动作」。
        suppressesShowsBodyAnimation = true
        collapseHeightLimit = 180
        withAnimation(.easeOut(duration: 0.2)) {
            collapseHeightLimit = 0
        }
        // 0.3s：easeOut 0.2s + 完成瞬间重帧叠加的余量（旧 0.26s 偏紧）。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            guard collapseToken == token else { return }
            isExpanded = false
            isCollapsingAtTerminal = false
            collapseHeightLimit = nil
        }
    }

    private func cancelPendingCollapse() {
        collapseToken = UUID()
        isCollapsingAtTerminal = false
        collapseHeightLimit = nil
    }

    private var capsuleFill: Color {
        AmberTheme.accent.opacity(isThinking ? 0.10 : 0.08)
    }

    private var capsuleStroke: Color {
        AmberTheme.accent.opacity(isThinking ? 0.20 : 0.16)
    }

    /// 思考内容顶部底部的渐变模糊 mask。
    /// 顶部 0→1(前 band 淡出),中间全不透明；底部渐变只在内容被裁切时启用——
    /// 短正文（≤2 行）时固定 12pt 双渐变会把整段洗灰、末行「追光」。
    /// band 随高度自适应 min(12, h/3)。
    private func reasoningFadeMask(isClipped: Bool) -> some View {
        GeometryReader { geo in
            let band = min(12, geo.size.height * 0.33)
            VStack(spacing: 0) {
                LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                    .frame(height: band)
                Rectangle()
                if isClipped {
                    LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom)
                        .frame(height: band)
                }
            }
        }
    }

    private func titleText(elapsed: Int?) -> String {
        if isThinking {
            if let elapsed { return "思考中 \(elapsed) 秒\(levelSuffix)" }
            return "思考中\(levelSuffix)"
        }
        if let finishedSeconds { return "思考了 \(Self.formatFinishedSeconds(finishedSeconds)) 秒\(levelSuffix)" }
        return "思考过程\(levelSuffix)"
    }

    /// 不足 1 秒按 0.1 精度显示(最小 0.1,避免「0 秒」/「0.0 秒」);≥1 秒显示整数。
    private static func formatFinishedSeconds(_ seconds: Double) -> String {
        let rounded = (seconds * 10).rounded() / 10
        if rounded >= 1 { return "\(Int(rounded.rounded()))" }
        return String(format: "%.1f", max(0.1, rounded))
    }

    @ViewBuilder
    private var titleLabel: some View {
        if isThinking, let startedAt {
            // Live ticking elapsed counter while the model is thinking.
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(titleText(elapsed: Int(max(0, context.date.timeIntervalSince(startedAt)))))
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }
        } else {
            Text(titleText(elapsed: nil))
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
        }
    }

    // Compact cream pill: thought-cloud + "思考中 N 秒 · Auto" (live) / "思考了 N 秒 · Auto" (done) +
    // chevron. Expands to a height-capped, auto-scrolling view of the streaming reasoning text.
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                // 软收起进行中忽略点击（0.2s 窗口），避免与 ramp 互相打架。
                guard hasBodyText, !isCollapsingAtTerminal else { return }
                userToggled = true
                suppressesShowsBodyAnimation = false
                setExpanded(!isExpanded, duration: 0.22)
            } label: {
                HStack(spacing: 7) {
                    // Koboyo 实心思维泡：一眼是「在想」，小尺寸仍饱满；进行中轻呼吸。
                    ChatKoboyoSpinningIcon(
                        mark: .solidThoughtCloud,
                        pointSize: 14,
                        tint: UIColor(AmberTheme.accentAmber),
                        isActive: isThinking && !reduceMotion
                    )

                    titleLabel

                    // Collapsed: hug content (chevron sits right after the title). Expanded: push
                    // the chevron to the right edge, matching the full-width reading area below.
                    if bodyPresent { Spacer(minLength: 6) }

                    if hasBodyText {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(AmberTheme.muted)
                            .rotationEffect(.degrees(bodyPresent ? 180 : 0))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(AmberPressFeedbackStyle(pressedScale: hasBodyText ? 0.98 : 1, haptic: hasBodyText ? .selection : nil))
            // 与工具胶囊同款 hug 高度（勿再钉 44pt，否则奶油壳被撑高一截）。
            .contentShape(Rectangle())
            .accessibilityValue(hasBodyText ? (showsBody ? "已展开" : "已折叠") : "无思考正文")

            if bodyPresent {
                // 推理正文增长不再经 SwiftUI ScrollViewReader 逐 chunk 重排并回写
                // scrollTo。UITextView 自己维护文本与滚动位置，外层只接收真实高度。
                ChatReasoningBodyTextView(
                    text: bodyText,
                    maxHeight: bodyHeightLimit,
                    followsBottomOnFirstPresentation: isThinking,
                    animatesNewWords: Self.animatesStreamingBody(
                        isThinking: isThinking,
                        reduceMotion: reduceMotion
                    ),
                    onClippedChanged: { bodyIsClipped = $0 }
                )
                .frame(maxHeight: bodyHeightLimit)
                .mask(reasoningFadeMask(isClipped: bodyIsClipped))
                // 从底部滑入/滑出:展开时从下往上出现,收回时从上往下消失(底部先收)。
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(
            capsuleFill,
            in: RoundedRectangle(cornerRadius: bodyPresent ? AmberTheme.radiusLarge : 17, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: bodyPresent ? AmberTheme.radiusLarge : 17, style: .continuous)
                .stroke(capsuleStroke, lineWidth: 0.7)
        }
        // Clip to the capsule so the collapsing content can never render outside / through it.
        .clipShape(RoundedRectangle(cornerRadius: bodyPresent ? AmberTheme.radiusLarge : 17, style: .continuous))
        // 统一驱动所有依赖 showsBody/isExpanded 的视觉变化(圆角、chevron、高度增删),
        // 覆盖自动展开/收回路径(它们不经过 withAnimation)和用户 toggle 路径。
        .animation(
            reduceMotion || suppressesShowsBodyAnimation ? nil : .easeInOut(duration: 0.28),
            value: showsBody
        )
        .onChange(of: bodyText) { _, newValue in
            guard isThinking, !userToggled else { return }
            if Self.hasVisibleText(newValue) {
                setExpanded(true, duration: 0.28)
            }
        }
        .onChange(of: isThinking) { _, nowThinking in
            guard !userToggled else { return }
            if nowThinking {
                suppressesShowsBodyAnimation = false
                cancelPendingCollapse()
                setExpanded(hasBodyText, duration: 0.28)
            } else if autoCloseThinking {
                if reduceMotion {
                    // Reduce Motion：跳过动画，单次 layout 直接落到最终高度。
                    suppressesShowsBodyAnimation = true
                    isExpanded = false
                } else {
                    collapseSoftlyAtTerminal()
                }
            }
        }
    }

}

private struct ChatReasoningBodyTextView: UIViewRepresentable {
    let text: String
    let maxHeight: CGFloat
    let followsBottomOnFirstPresentation: Bool
    let animatesNewWords: Bool
    var onClippedChanged: (Bool) -> Void = { _ in }

    func makeUIView(context: Context) -> ChatReasoningTextView {
        let textView = ChatReasoningTextView()
        textView.onClippedChanged = onClippedChanged
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isSelectable = true
        // isScrollEnabled = true:保持可滚动(超长内容可查看),且不覆盖标题(VStack 布局正常)。
        // 短文本在固定 frame 内上方对齐(textContainerInset 控制留白)。
        textView.isScrollEnabled = true
        textView.showsVerticalScrollIndicator = false
        textView.textContainerInset = UIEdgeInsets(top: 2, left: 12, bottom: 10, right: 12)
        textView.textContainer.lineFragmentPadding = 0
        textView.font = UIFont.preferredFont(forTextStyle: .caption2)
        textView.textColor = UIColor(AmberTheme.muted)
        textView.adjustsFontForContentSizeCategory = true
        textView.alwaysBounceVertical = false
        return textView
    }

    func updateUIView(_ textView: ChatReasoningTextView, context: Context) {
        textView.onClippedChanged = onClippedChanged
        textView.apply(
            text: text,
            font: UIFont.preferredFont(forTextStyle: .caption2),
            color: UIColor(AmberTheme.muted),
            followsBottomOnFirstPresentation: followsBottomOnFirstPresentation,
            animatesNewWords: animatesNewWords
        )
    }

    static func dismantleUIView(_ uiView: ChatReasoningTextView, coordinator: Void) {
        uiView.prepareForRemoval()
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: ChatReasoningTextView,
        context: Context
    ) -> CGSize? {
        guard let width = proposal.width, width > 0 else { return nil }
        // Same contract as Chat `ParagraphUIView`: never call UITextView.sizeThatFits
        // with an unbounded height. That path mutates the container and relayouts
        // the whole document on every 48ms thinking beat.
        return uiView.fittingSize(forWidth: width, maxHeight: maxHeight)
    }
}

@MainActor
private final class ChatReasoningTextView: UITextView, UITextViewDelegate {
    private struct WordFade {
        let startTime: CFTimeInterval
        let duration: CFTimeInterval
        let range: NSRange
    }

    /// Finite stand-in for "unbounded". `.greatestFiniteMagnitude` makes
    /// TextKit line-fragment math pathologically slow (Chat `ParagraphUIView`).
    private static let unboundedMeasuringHeight: CGFloat = 10_000_000
    /// Live thinking is height-capped at 180pt. Keep a tail window in TextKit
    /// so a 100k-character Gemini thought does not relayout the whole document
    /// every 48ms. Full text is restored when thinking ends or the user scrolls.
    private static let liveStorageWindowUTF16 = 6_000
    private static let liveStorageTrimUTF16 = 12_000

    private static let wordFadeDuration: CFTimeInterval = 0.5

    /// 尾段淡入时长与正文 ParagraphUIView.unitFadeDuration 同构（0.5×12/N，
    /// 地板 1/30s）：快流/排空期正文墨迹快干时，思考框尾段不再独自拖 0.5s
    /// 渐隐——两处「墨量×时长 ≈ 6 字·秒」恒定，观感同速收干。
    private static func tailFadeDuration(forAppendedLength length: Int) -> CFTimeInterval {
        guard length > 12 else { return wordFadeDuration }
        return max(1.0 / 30.0, wordFadeDuration * 12.0 / CFTimeInterval(length))
    }
    private static let bottomTolerance: CGFloat = 8
    private static let followSpeed: CGFloat = 540
    private static let followSettleDuration: CFTimeInterval = 0.12

    private var renderedText = ""
    private var storageText = ""
    private var renderedFont: UIFont?
    private var renderedColor: UIColor?
    private var activeWordFades: [WordFade] = []
    private var displayLink: CADisplayLink?
    private var previousFrameTimestamp: CFTimeInterval?
    private var followsBottom = false
    private var hasAppliedContent = false
    private var smoothsFollowing = true
    private var followSettleUntil: CFTimeInterval = 0
    private var lastClipped: Bool?
    private var lastUnconstrainedHeight: CGFloat = 0
    private var lastMeasureWidth: CGFloat = 0

    /// 内容是否被高度上限裁切——供卡片决定 mask 底部渐变（短正文不洗淡）。
    var onClippedChanged: ((Bool) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        let clipped = contentSize.height > bounds.height + 1
        if clipped != lastClipped {
            lastClipped = clipped
            onClippedChanged?(clipped)
        }
    }

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        delegate = self
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        delegate = self
    }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            stopDisplayLink()
        }
    }

    func prepareForRemoval() {
        finishWordFades()
        stopDisplayLink()
    }

    func fittingSize(forWidth width: CGFloat, maxHeight: CGFloat) -> CGSize {
        if abs(width - lastMeasureWidth) < 0.5, lastUnconstrainedHeight >= maxHeight {
            return CGSize(width: width, height: maxHeight)
        }
        let measured = super.sizeThatFits(
            CGSize(width: width, height: Self.unboundedMeasuringHeight)
        )
        let height = ceil(measured.height)
        lastUnconstrainedHeight = height
        lastMeasureWidth = width
        return CGSize(width: width, height: min(maxHeight, height))
    }

    func apply(
        text newText: String,
        font newFont: UIFont,
        color newColor: UIColor,
        followsBottomOnFirstPresentation: Bool,
        animatesNewWords: Bool
    ) {
        if hasAppliedContent {
            updateFollowOwnership()
        } else {
            followsBottom = followsBottomOnFirstPresentation
            hasAppliedContent = true
        }
        smoothsFollowing = animatesNewWords

        let resolvedColor = newColor.resolvedColor(with: traitCollection)
        let styleChanged = renderedFont?.isEqual(newFont) != true ||
            renderedColor?.isEqual(resolvedColor) != true
        renderedFont = newFont
        renderedColor = resolvedColor
        font = newFont
        textColor = resolvedColor

        if newText == renderedText, !styleChanged {
            if !animatesNewWords {
                finishWordFades()
                expandToFullTextIfNeeded()
            }
            requestBottomFollow()
            return
        }

        let oldText = renderedText
        let isLogicalAppend = !styleChanged && newText.hasPrefix(oldText)
        let targetStorage = nextDisplaySlice(
            fullText: newText,
            isLive: animatesNewWords,
            isLogicalAppend: isLogicalAppend
        )
        let attributes: [NSAttributedString.Key: Any] = [
            .font: newFont,
            .foregroundColor: resolvedColor,
        ]
        if targetStorage.hasPrefix(storageText),
           (targetStorage as NSString).length > (storageText as NSString).length,
           !storageText.isEmpty {
            let oldLength = (storageText as NSString).length
            let suffix = (targetStorage as NSString).substring(from: oldLength)
            textStorage.append(NSAttributedString(string: suffix, attributes: attributes))
            if animatesNewWords {
                appendTailFade(in: NSRange(
                    location: oldLength,
                    length: (targetStorage as NSString).length - oldLength
                ))
            } else {
                finishWordFades()
            }
        } else if targetStorage != storageText {
            finishWordFades()
            attributedText = NSAttributedString(string: targetStorage, attributes: attributes)
            lastUnconstrainedHeight = 0
            lastMeasureWidth = 0
            if storageText.isEmpty, animatesNewWords, !targetStorage.isEmpty {
                appendTailFade(in: NSRange(location: 0, length: textStorage.length))
            }
        } else if !animatesNewWords {
            finishWordFades()
        }
        renderedText = newText
        storageText = targetStorage

        if !isLogicalAppend || !animatesNewWords {
            accessibilityLabel = newText
        }
        followSettleUntil = CACurrentMediaTime() + Self.followSettleDuration
        requestBottomFollow()
    }

    private func nextDisplaySlice(
        fullText: String,
        isLive: Bool,
        isLogicalAppend: Bool
    ) -> String {
        if !isLive { return fullText }
        let fullNS = fullText as NSString
        if fullNS.length <= Self.liveStorageTrimUTF16 { return fullText }
        if isLogicalAppend, !storageText.isEmpty {
            let oldLength = (renderedText as NSString).length
            let added = fullNS.substring(from: min(oldLength, fullNS.length))
            let grown = storageText + added
            let grownNS = grown as NSString
            if grownNS.length <= Self.liveStorageTrimUTF16 {
                return grown
            }
            return grownNS.substring(from: grownNS.length - Self.liveStorageWindowUTF16)
        }
        return fullNS.substring(from: fullNS.length - Self.liveStorageWindowUTF16)
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        followsBottom = false
        followSettleUntil = 0
        expandToFullTextIfNeeded()
    }

    private func expandToFullTextIfNeeded() {
        guard storageText != renderedText,
              let renderedFont,
              let renderedColor else { return }
        let oldHeight = contentSize.height
        let oldOffset = contentOffset.y
        let attributes: [NSAttributedString.Key: Any] = [
            .font: renderedFont,
            .foregroundColor: renderedColor,
        ]
        attributedText = NSAttributedString(string: renderedText, attributes: attributes)
        storageText = renderedText
        lastUnconstrainedHeight = 0
        lastMeasureWidth = 0
        accessibilityLabel = renderedText
        layoutIfNeeded()
        let extra = max(0, contentSize.height - oldHeight)
        setContentOffset(
            CGPoint(x: contentOffset.x, y: oldOffset + extra),
            animated: false
        )
    }

    private func updateFollowOwnership() {
        if isTracking || isDragging || isDecelerating {
            followsBottom = false
        } else if isAtBottom {
            followsBottom = true
        } else if displayLink == nil {
            followsBottom = false
        }
    }

    private var bottomOffsetY: CGFloat {
        max(
            -adjustedContentInset.top,
            contentSize.height - bounds.height + adjustedContentInset.bottom
        )
    }

    private var isAtBottom: Bool {
        contentSize.height <= bounds.height + 1 ||
            contentOffset.y >= bottomOffsetY - Self.bottomTolerance
    }

    private func requestBottomFollow() {
        guard followsBottom else {
            stopDisplayLinkIfIdle()
            return
        }
        if !smoothsFollowing {
            followSettleUntil = max(
                followSettleUntil,
                CACurrentMediaTime() + Self.followSettleDuration
            )
        }
        startDisplayLink()
    }

    /// 尾段整体淡入（与正文段落同构）：一拍一条 fade，替代逐词 N 条——
    /// display link 每帧重写的属性范围从「全部词」降到 1 条，思考流式期
    /// 主线程开销降一个量级。
    private func appendTailFade(in range: NSRange) {
        guard range.length > 0 else { return }
        activeWordFades.append(WordFade(
            startTime: CACurrentMediaTime(),
            duration: Self.tailFadeDuration(forAppendedLength: range.length),
            range: range
        ))
        updateWordFades(at: CACurrentMediaTime())
        startDisplayLink()
    }

    private func finishWordFades() {
        guard !activeWordFades.isEmpty, let renderedColor else { return }
        textStorage.beginEditing()
        for fade in activeWordFades where NSMaxRange(fade.range) <= textStorage.length {
            textStorage.addAttribute(
                .foregroundColor,
                value: renderedColor,
                range: fade.range
            )
        }
        textStorage.endEditing()
        activeWordFades.removeAll()
        stopDisplayLinkIfIdle()
    }

    @objc private func displayLinkTick(_ displayLink: CADisplayLink) {
        let now = CACurrentMediaTime()
        updateWordFades(at: now)
        updateBottomFollow(displayLink: displayLink, now: now)
        stopDisplayLinkIfIdle(now: now)
    }

    private func updateWordFades(at currentTime: CFTimeInterval) {
        guard !activeWordFades.isEmpty, let renderedColor else { return }
        textStorage.beginEditing()
        for fade in activeWordFades where NSMaxRange(fade.range) <= textStorage.length {
            let elapsed = currentTime - fade.startTime
            let progress = min(max(elapsed / max(fade.duration, 0.001), 0), 1)
            let eased = Self.easeOut(CGFloat(progress))
            textStorage.addAttribute(
                .foregroundColor,
                value: renderedColor.withAlphaComponent(renderedColor.cgColor.alpha * eased),
                range: fade.range
            )
        }
        textStorage.endEditing()
        activeWordFades.removeAll {
            currentTime - $0.startTime >= $0.duration
        }
    }

    /// 限速连续跟随：内部滚动按 540pt/s 逐帧推进（cadence 门禁契约：
    /// 单帧步进 ≤10pt，不能随 chunk 整行跳）。掉帧/乱跳的根因不在跟随
    /// 策略，而在每帧开销——尾段整体淡入已把 display-link 属性重写从
    /// 「全部词」降到 1 条。
    private func updateBottomFollow(displayLink: CADisplayLink, now: CFTimeInterval) {
        guard followsBottom else { return }
        if isTracking || isDragging || isDecelerating {
            followsBottom = false
            followSettleUntil = 0
            return
        }

        let targetY = bottomOffsetY
        let delta = targetY - contentOffset.y
        guard abs(delta) > 0.5 else {
            if contentOffset.y != targetY {
                setContentOffset(CGPoint(x: contentOffset.x, y: targetY), animated: false)
            }
            return
        }
        guard smoothsFollowing else {
            setContentOffset(CGPoint(x: contentOffset.x, y: targetY), animated: false)
            return
        }

        let previousTimestamp = previousFrameTimestamp ?? (displayLink.timestamp - displayLink.duration)
        let frameDuration = min(max(displayLink.timestamp - previousTimestamp, 1.0 / 240.0), 1.0 / 60.0)
        previousFrameTimestamp = displayLink.timestamp
        let maximumStep = Self.followSpeed * frameDuration
        let step = min(abs(delta), maximumStep) * (delta < 0 ? -1 : 1)
        setContentOffset(
            CGPoint(x: contentOffset.x, y: contentOffset.y + step),
            animated: false
        )
    }

    private func startDisplayLink() {
        guard displayLink == nil else { return }
        let displayLink = CADisplayLink(target: self, selector: #selector(displayLinkTick(_:)))
        displayLink.preferredFrameRateRange = CAFrameRateRange(
            minimum: 60,
            maximum: 120,
            preferred: 120
        )
        displayLink.add(to: .main, forMode: .common)
        self.displayLink = displayLink
    }

    private func stopDisplayLinkIfIdle(now: CFTimeInterval = CACurrentMediaTime()) {
        let followSettled = !followsBottom ||
            (abs(bottomOffsetY - contentOffset.y) <= 0.5 && now >= followSettleUntil)
        if activeWordFades.isEmpty, followSettled {
            stopDisplayLink()
        }
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
        previousFrameTimestamp = nil
    }

    private static func easeOut(_ progress: CGFloat) -> CGFloat {
        let squared = progress * progress
        let cubed = squared * progress
        let remaining = 1 - progress
        return 3 * remaining * remaining * progress * 0.1 +
            3 * remaining * squared + cubed
    }
}

struct ChatEmptyState: View {
    @State private var prompt = Self.randomPrompt()

    var body: some View {
        VStack(spacing: 18) {
            AmberEmptyStateMark()
                .padding(.bottom, 2)

            VStack(spacing: 7) {
                Text("今天想聊点什么？")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text(prompt)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 34)
        .padding(.top, 104)
        .padding(.bottom, 180)
    }

    private static func randomPrompt() -> String {
        [
            "先随便说一句也可以。",
            "有个念头的话，直接丢给我。",
            "想写、想查、想整理，都可以从一句话开始。",
            "要解决问题也行，只是聊聊也行。",
            "不知道从哪开始的话，先说现在卡在哪。"
        ].randomElement() ?? "先随便说一句也可以。"
    }
}

private struct AmberEmptyStateMark: View {
    private static let markDiameter: CGFloat = 56
    private static let orbitDiameter: CGFloat = 62
    private static let orbitLineWidth: CGFloat = 1
    private static let dotDiameter: CGFloat = 5

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let progress = reduceMotion ? 0 : orbitProgress(at: timeline.date)
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [
                                AmberTheme.surface.opacity(0.94),
                                AmberTheme.accent.opacity(0.08)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: Self.markDiameter, height: Self.markDiameter)
                    .overlay {
                        Circle()
                            .stroke(AmberTheme.borderSoft.opacity(0.9), lineWidth: 0.7)
                    }
                    .shadow(color: AmberTheme.accent.opacity(0.10), radius: 9, x: 0, y: 5)

                Circle()
                    .stroke(AmberTheme.accent.opacity(0.22), lineWidth: Self.orbitLineWidth)
                    .frame(width: Self.orbitDiameter, height: Self.orbitDiameter)
                    .opacity(reduceMotion ? 0.12 : 1)

                Text("A")
                    .font(.system(size: 30, weight: .semibold, design: .serif))
                    .foregroundStyle(AmberTheme.foreground)
                    .offset(y: -1)

                orbitDot(progress: progress)
            }
            .frame(width: 76, height: 76)
        }
    }

    private func orbitDot(progress: Double) -> some View {
        let angle = progress * 2 * .pi - .pi / 2
        let radius = Self.orbitDiameter / 2
        let x = CGFloat(cos(angle)) * radius
        let y = CGFloat(sin(angle)) * radius

        return Circle()
            .fill(AmberTheme.accent)
            .frame(width: Self.dotDiameter, height: Self.dotDiameter)
            .offset(x: x, y: y)
            .shadow(color: AmberTheme.accent.opacity(0.45), radius: 5, x: 0, y: 0)
            .opacity(0.9)
    }

    private func orbitProgress(at date: Date) -> Double {
        let cycle = 7.2
        return date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle) / cycle
    }
}

// MARK: - Image attachment helpers

/// Compresses an image into a self-contained `data:` URL (sent to the model) plus a small
/// JPEG used only for the composer thumbnail. Downscaling keeps the persisted payload small.
enum ChatImageEncoder {
    static let maxSendDimension: CGFloat = 1536
    static let maxThumbnailDimension: CGFloat = 160

    static func encode(_ image: UIImage) -> (dataUrl: String, previewData: Data)? {
        let sized = downscaled(image, maxDimension: maxSendDimension)
        guard let jpeg = sized.jpegData(compressionQuality: 0.7) else { return nil }
        let dataUrl = "data:image/jpeg;base64,\(jpeg.base64EncodedString())"
        let thumb = downscaled(image, maxDimension: maxThumbnailDimension)
        let previewData = thumb.jpegData(compressionQuality: 0.6) ?? jpeg
        return (dataUrl, previewData)
    }

    private static func downscaled(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let size = image.size
        let longest = max(size.width, size.height)
        guard longest > maxDimension, longest > 0 else { return image }
        let scale = maxDimension / longest
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}

/// Right-aligned "visual recognition in progress" indicator shown on the user side while
/// the OCR-fallback vision model reads the image, with a breathing animation.
struct VisionRecognitionIndicator: View {
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack {
            Spacer(minLength: 40)
            HStack(spacing: 7) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .scaleEffect(reduceMotion ? 1 : (pulse ? 1.18 : 0.86))
                    .opacity(reduceMotion ? 1 : (pulse ? 1.0 : 0.55))
                Text("视觉识别中…")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(AmberTheme.surface, in: Capsule())
            .overlay(Capsule().stroke(AmberTheme.borderSoft, lineWidth: 1))
        }
        .onAppear {
            startPulseIfNeeded()
        }
        .onChange(of: reduceMotion) { _, _ in
            startPulseIfNeeded()
        }
    }

    private func startPulseIfNeeded() {
        guard !reduceMotion else {
            pulse = false
            return
        }
        withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
            pulse = true
        }
    }
}

/// Thin SwiftUI wrapper over `UIImagePickerController` for the 拍照 (camera) path.
struct CameraPicker: UIViewControllerRepresentable {
    let onComplete: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onComplete: onComplete) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onComplete: (UIImage?) -> Void
        init(onComplete: @escaping (UIImage?) -> Void) { self.onComplete = onComplete }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            onComplete(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onComplete(nil)
        }
    }
}
