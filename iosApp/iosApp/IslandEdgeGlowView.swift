// Capsule edge-glow renderer for the chat activity island (「安静的星核」的壳层).
// Follows the same UIKit CADisplayLink discipline as ThinkingOrbView: zero
// per-frame SwiftUI cost, 24–30fps, window/hidden/alpha pause gating. One
// conic gradient image is pre-rendered per style; each frame only clips a
// stepped-alpha capsule stroke ladder and draws the rotated image — no
// per-frame blur or heap allocation. The clock is CACurrentMediaTime(), the
// same base as OrbCanvasView, so the edge light reads as spilling from the
// orb core (光源一致性).

import SwiftUI
import UIKit
import QuartzCore

// MARK: - Pure spec (unit-testable, no UIView involved)

struct IslandGlowStop: Equatable {
    let hex: UInt
    let alpha: Double
}

struct IslandGlowStroke: Equatable {
    let width: Double
    let opacity: Double
}

struct IslandGlowSpec: Equatable {
    /// Conic gradient stops, ordered around the capsule.
    let stops: [IslandGlowStop]
    /// 秒/rev；0 = 静止（hue/terminal 不旋转）。
    let rotationPeriod: TimeInterval
    /// 仅生成中：透明度 0.7±0.3，2.6s 正弦呼吸。
    let breathing: Bool

    /// 衰减描边阶梯：紧核 + 柔光 + 环境晕染，以 alpha 递减代替逐帧模糊。
    static let strokeLadder: [IslandGlowStroke] = [
        IslandGlowStroke(width: 1.0, opacity: 0.85),
        IslandGlowStroke(width: 2.5, opacity: 0.28),
        IslandGlowStroke(width: 4.0, opacity: 0.16),
        IslandGlowStroke(width: 7.0, opacity: 0.07),
        IslandGlowStroke(width: 11.0, opacity: 0.035)
    ]

    /// AI 三态专用光谱：amber → cyan → violet → amber 低饱和循环。
    static func spectral(rotationPeriod: TimeInterval, breathing: Bool) -> IslandGlowSpec {
        IslandGlowSpec(
            stops: [
                IslandGlowStop(hex: 0xD98324, alpha: 1),
                IslandGlowStop(hex: 0x2AA0BC, alpha: 1),
                IslandGlowStop(hex: 0x5856D6, alpha: 1),
                IslandGlowStop(hex: 0xD98324, alpha: 1)
            ],
            rotationPeriod: rotationPeriod,
            breathing: breathing
        )
    }

    /// 工具语义色：单 hue 明暗两 stop，常亮不转。
    static func hue(hex: UInt) -> IslandGlowSpec {
        IslandGlowSpec(
            stops: [
                IslandGlowStop(hex: hex, alpha: 1),
                IslandGlowStop(hex: hex, alpha: 0.55),
                IslandGlowStop(hex: hex, alpha: 1)
            ],
            rotationPeriod: 0,
            breathing: false
        )
    }

    /// 终态（失败）：静态纯色边光，永不旋转。
    static func terminal(hex: UInt) -> IslandGlowSpec {
        IslandGlowSpec(
            stops: [IslandGlowStop(hex: hex, alpha: 1)],
            rotationPeriod: 0,
            breathing: false
        )
    }
}

// MARK: - Gradient image cache

@MainActor
private enum IslandGlowGradientCache {
    private static var images: [Int: CGImage] = [:]

    static func image(for stops: [IslandGlowStop]) -> CGImage {
        var hasher = Hasher()
        for stop in stops {
            hasher.combine(stop.hex)
            hasher.combine(stop.alpha)
        }
        let key = hasher.finalize()
        if let cached = images[key] { return cached }
        let rendered = render(stops: stops)
        images[key] = rendered
        return rendered
    }

    private static func render(stops: [IslandGlowStop]) -> CGImage {
        let side = 512
        let layer = CAGradientLayer()
        layer.type = .conic
        layer.frame = CGRect(x: 0, y: 0, width: side, height: side)
        layer.startPoint = CGPoint(x: 0.5, y: 0.5)
        layer.endPoint = CGPoint(x: 0.5, y: 0)
        layer.colors = stops.map { stop in
            UIColor(
                red: CGFloat((stop.hex >> 16) & 0xFF) / 255,
                green: CGFloat((stop.hex >> 8) & 0xFF) / 255,
                blue: CGFloat(stop.hex & 0xFF) / 255,
                alpha: stop.alpha
            ).cgColor
        }
        if stops.count > 1 {
            layer.locations = stops.indices.map { NSNumber(value: Double($0) / Double(stops.count - 1)) }
        }
        let renderer = UIGraphicsImageRenderer(size: layer.frame.size)
        let image = renderer.image { ctx in
            layer.render(in: ctx.cgContext)
        }
        guard let cgImage = image.cgImage else {
            preconditionFailure("IslandGlow: conic gradient render produced no CGImage")
        }
        return cgImage
    }
}

// MARK: - UIKit canvas

@MainActor
private final class IslandGlowLinkProxy: NSObject {
    weak var view: IslandGlowCanvasView?
    init(_ view: IslandGlowCanvasView) { self.view = view }
    @objc func tick(_ link: CADisplayLink) {
        guard let view else { link.invalidate(); return }
        view.tickFromProxy()
    }
}

@MainActor
final class IslandGlowCanvasView: UIView {
    /// 胶囊相对画布边界的内缩，给 wash 外溢留出空间；外部用负 padding 放大画布。
    static let canvasMargin: CGFloat = 8

    private var spec = IslandGlowSpec.terminal(hex: 0x000000)
    private var effOpacity: Double = 1
    private var isReduceMotion = false
    private var gradientImage: CGImage?
    private var displayLink: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentScaleFactor = 2
    }

    required init?(coder: NSCoder) { nil }

    func configure(spec: IslandGlowSpec, opacity: Double, reduceMotion: Bool) {
        let specChanged = self.spec != spec
        self.spec = spec
        effOpacity = opacity
        let motionChanged = isReduceMotion != reduceMotion
        isReduceMotion = reduceMotion
        if specChanged {
            gradientImage = IslandGlowGradientCache.image(for: spec.stops)
        }
        if specChanged || motionChanged {
            updateRunning()
        }
        setNeedsDisplay()
    }

    // MARK: Display link lifecycle

    private var isAnimated: Bool {
        spec.rotationPeriod > 0 || spec.breathing
    }

    private func updateRunning() {
        let shouldRun = isAnimated && !isReduceMotion && window != nil && !isHidden && alpha > 0.01
        if shouldRun {
            if displayLink == nil {
                let proxy = IslandGlowLinkProxy(self)
                let link = CADisplayLink(target: proxy, selector: #selector(IslandGlowLinkProxy.tick(_:)))
                link.preferredFrameRateRange = CAFrameRateRange(minimum: 24, maximum: 30, preferred: 30)
                link.add(to: .main, forMode: .common)
                displayLink = link
            } else {
                displayLink?.isPaused = false
            }
        } else {
            displayLink?.isPaused = true
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            updateRunning()
            setNeedsDisplay()
        } else {
            displayLink?.invalidate()
            displayLink = nil
        }
    }

    override var isHidden: Bool {
        didSet { updateRunning() }
    }

    override var alpha: CGFloat {
        didSet { updateRunning() }
    }

    @objc func tickFromProxy() {
        setNeedsDisplay()
    }

    // MARK: Drawing

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let image = gradientImage else { return }
        let margin = Self.canvasMargin
        let now = CACurrentMediaTime()
        let angle: Double
        if isReduceMotion || spec.rotationPeriod <= 0 {
            angle = 0.9 // 静止帧：固定相位，减少动态时也能看到色彩分布
        } else {
            angle = (now / spec.rotationPeriod) * .pi * 2
        }
        let breath: Double
        if spec.breathing && !isReduceMotion {
            breath = 0.7 + 0.3 * sin(now * (.pi * 2 / 2.6))
        } else {
            breath = 1
        }
        let motionAlpha = isReduceMotion ? 0.4 : 1.0
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let side = max(bounds.width, bounds.height) * 1.6
        let gradientRect = CGRect(x: center.x - side / 2, y: center.y - side / 2, width: side, height: side)

        for stroke in IslandGlowSpec.strokeLadder {
            let inset = margin + stroke.width / 2
            let capsuleRect = bounds.insetBy(dx: inset, dy: inset)
            guard capsuleRect.width > 0, capsuleRect.height > 0 else { continue }
            ctx.saveGState()
            let path = UIBezierPath(
                roundedRect: capsuleRect,
                cornerRadius: capsuleRect.height / 2
            )
            ctx.addPath(path.cgPath)
            ctx.replacePathWithStrokedPath()
            ctx.clip()
            ctx.setAlpha(CGFloat(stroke.opacity * breath * motionAlpha * effOpacity))
            ctx.translateBy(x: center.x, y: center.y)
            ctx.rotate(by: angle)
            ctx.translateBy(x: -center.x, y: -center.y)
            ctx.draw(image, in: gradientRect)
            ctx.restoreGState()
        }
    }
}

// MARK: - SwiftUI wrapper

struct IslandEdgeGlowView: UIViewRepresentable {
    let spec: IslandGlowSpec
    var opacity: Double = 1

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeUIView(context: Context) -> IslandGlowCanvasView {
        let view = IslandGlowCanvasView()
        view.configure(spec: spec, opacity: opacity, reduceMotion: reduceMotion)
        return view
    }

    func updateUIView(_ view: IslandGlowCanvasView, context: Context) {
        view.configure(spec: spec, opacity: opacity, reduceMotion: reduceMotion)
    }
}
