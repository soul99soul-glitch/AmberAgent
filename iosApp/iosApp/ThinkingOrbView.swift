// SwiftUI wrapper around a UIKit canvas that renders the thinking-orb dot
// engine via CADisplayLink + CGContext. This deliberately avoids SwiftUI's
// TimelineView/Canvas combo: a permanent SwiftUI animation loop pins the
// ViewGraph display link at 60 fps and burns ~240 ms CPU/s on long chat
// lists (measured 2026-07-10, see ChatMiscViews.swift header comment). The
// UIKit path runs on the CA layer — zero per-frame SwiftUI cost.

import SwiftUI
import UIKit
import QuartzCore

// MARK: - UIViewRepresentable

struct ThinkingOrbView: UIViewRepresentable {
    let state: OrbState
    let size: CGFloat
    let preset: OrbPreset
    var speed: Double = 1
    var paused: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    func makeUIView(context: Context) -> OrbCanvasView {
        let view = OrbCanvasView()
        view.configure(
            state: state, preset: preset, size: size, speed: speed,
            paused: paused, reduceMotion: reduceMotion,
            dark: colorScheme == .dark
        )
        return view
    }

    func updateUIView(_ view: OrbCanvasView, context: Context) {
        view.configure(
            state: state, preset: preset, size: size, speed: speed,
            paused: paused, reduceMotion: reduceMotion,
            dark: colorScheme == .dark
        )
    }
}

// MARK: - UIKit canvas

/// Weak proxy breaks the CADisplayLink → target retain cycle. If the view
/// is deallocated without didMoveToWindow(nil) firing (SwiftUI edge case),
/// the next tick sees a nil weak ref and self-invalidates.
@MainActor
private final class OrbLinkProxy: NSObject {
    weak var view: OrbCanvasView?
    init(_ v: OrbCanvasView) { view = v }
    @objc func tick(_ link: CADisplayLink) {
        guard let v = view else { link.invalidate(); return }
        v.tickFromProxy()
    }
}

@MainActor
final class OrbCanvasView: UIView {
    private var resolved: OrbResolved?
    private var effSpeed: Double = 1
    private var isDark = false
    private var isReduceMotion = false
    private var isPaused = false
    private var displayLink: CADisplayLink?
    private var configuredSize: CGFloat = 24

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        // Cap at 2x: matches the reference (min(2, dpr)) and avoids the
        // iOS 26 UIScreen.main deprecation. All modern iOS devices are ≥2x.
        contentScaleFactor = 2
    }

    required init?(coder: NSCoder) { nil }

    override var intrinsicContentSize: CGSize {
        CGSize(width: configuredSize, height: configuredSize)
    }

    func configure(
        state: OrbState, preset: OrbPreset, size: CGFloat, speed: Double,
        paused: Bool, reduceMotion: Bool, dark: Bool
    ) {
        let newResolved = orbResolvePreset(state, preset)
        let configChanged = resolved?.mode != newResolved.mode || resolved?.opts != newResolved.opts
        resolved = newResolved
        effSpeed = newResolved.speed * speed
        configuredSize = size

        let darkChanged = isDark != dark
        isDark = dark

        let pausedChanged = isPaused != paused
        isPaused = paused

        let motionChanged = isReduceMotion != reduceMotion
        isReduceMotion = reduceMotion

        if configChanged || motionChanged || pausedChanged {
            updateRunning()
            setNeedsDisplay()
        } else if darkChanged {
            setNeedsDisplay()
        }
    }

    // MARK: Display link lifecycle

    private func updateRunning() {
        let shouldRun = !isPaused && !isReduceMotion && window != nil && !isHidden && alpha > 0.01
        if shouldRun {
            if displayLink == nil {
                let proxy = OrbLinkProxy(self)
                let link = CADisplayLink(target: proxy, selector: #selector(OrbLinkProxy.tick(_:)))
                // 24pt decorative animation: 30fps is visually identical to
                // 120fps on ProMotion and cuts CPU by 4x.
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
        guard let ctx = UIGraphicsGetCurrentContext(),
              let resolved else { return }
        let s = Double(configuredSize)
        let t: Double
        if isReduceMotion {
            t = 0.6   // static representative frame, matches reference
        } else {
            t = CACurrentMediaTime() * effSpeed
        }
        orbDraw(resolved.mode, ctx, size: s, t: t, dark: isDark, opts: resolved.opts)
    }
}
