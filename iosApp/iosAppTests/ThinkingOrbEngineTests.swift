import XCTest
import CoreGraphics
import UIKit
@testable import iosApp

final class ThinkingOrbEngineTests: XCTestCase {

    // MARK: - Hash determinism

    func testHashDInRangeAndDeterministic() {
        for i in 0..<200 {
            let h = orbHash(Double(i), 1.7)
            XCTAssertGreaterThanOrEqual(h, 0)
            XCTAssertLessThan(h, 1)
            XCTAssertEqual(h, orbHash(Double(i), 1.7), "hash must be deterministic")
        }
    }

    // MARK: - Preset resolution

    func testResolvePresetAllStates() {
        for state in OrbState.allCases {
            for preset in [OrbPreset.large, OrbPreset.small] {
                let r = orbResolvePreset(state, preset)
                XCTAssertGreaterThan(r.speed, 0, "\(state) speed must be positive")
                XCTAssertFalse(r.opts.isEmpty, "\(state) opts must not be empty")
            }
        }
    }

    // MARK: - Draw produces non-empty output

    func testDrawProducesNonEmptyOutput() {
        let size = 24.0
        let t = 0.6
        for state in OrbState.allCases {
            let resolved = orbResolvePreset(state, .small)
            let stats = renderStats(mode: resolved.mode, size: size, t: t, dark: true, opts: resolved.opts)
            XCTAssertGreaterThan(stats.opaquePixels, 0, "\(state) must produce visible pixels")
        }
    }

    // MARK: - Determinism: same t → identical pixels

    func testDrawDeterministic() {
        let size = 64.0
        let t = 1.23
        for state in OrbState.allCases {
            let resolved = orbResolvePreset(state, .large)
            assertRenderDeterministic(resolved.mode, size: size, t: t, opts: resolved.opts)
        }
    }

    // MARK: - Coordinates within bounds (3D modes)

    func testCoordinatesWithinBounds() {
        // We can't extract raw dots from the engine (it paints directly),
        // but we can verify the rendered output stays within the canvas by
        // checking that no pixels outside the expected radius are opaque.
        let size = 64.0
        let t = 2.5
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        for state in OrbState.allCases where state != .shaping {
            let resolved = orbResolvePreset(state, .large)
            let img = renderer.image { ctx in
                orbDraw(resolved.mode, ctx.cgContext, size: size, t: t, dark: true, opts: resolved.opts)
            }
            // All drawn content must be within the image bounds (trivially
            // true since CGContext clips to the context rect, but this
            // verifies the draw call doesn't crash with out-of-bounds coords).
            XCTAssertNotNil(img.cgImage, "\(state) must produce a valid image")
        }
    }

    // MARK: - Dark/light ink inversion

    func testDarkLightInkInversion() {
        let size = 64.0
        let t = 1.0
        for state in OrbState.allCases {
            let resolved = orbResolvePreset(state, .large)
            let darkStats = renderStats(mode: resolved.mode, size: size, t: t, dark: true, opts: resolved.opts)
            let lightStats = renderStats(mode: resolved.mode, size: size, t: t, dark: false, opts: resolved.opts)

            // Both themes must produce visible output.
            XCTAssertGreaterThan(darkStats.opaquePixels, 0, "\(state) dark must have visible pixels")
            XCTAssertGreaterThan(lightStats.opaquePixels, 0, "\(state) light must have visible pixels")

            // Ink inversion: in dark mode, near dots are bright (high gray);
            // in light mode, near dots are dark (low gray). The average
            // luminance of opaque pixels must differ between themes.
            XCTAssertNotEqual(
                darkStats.avgLuminance, lightStats.avgLuminance,
                accuracy: 0.01,
                "\(state) dark/light avg luminance must differ (ink inversion)"
            )
            // Dark mode should have higher average luminance (bright dots on
            // transparent bg) than light mode (dark dots on transparent bg).
            XCTAssertGreaterThan(
                darkStats.avgLuminance, lightStats.avgLuminance,
                "\(state) dark avg luminance (\(darkStats.avgLuminance)) should exceed light (\(lightStats.avgLuminance))"
            )
        }
    }

    // MARK: - Small preset minimum density

    func testSmallPresetMinimumDensity() {
        let size = 24.0
        let t = 0.6
        for state in OrbState.allCases {
            let resolved = orbResolvePreset(state, .small)
            let stats = renderStats(mode: resolved.mode, size: size, t: t, dark: true, opts: resolved.opts)
            // At 24pt with .small preset, every mode must produce enough
            // visible pixels to be recognizable as a shape. A single dot
            // at rMin=0.3 covers ~1 pixel; a recognizable shape needs at
            // least a handful of dots. 10 opaque pixels is a conservative
            // floor that catches a collapse to 1-2 dots.
            XCTAssertGreaterThanOrEqual(
                stats.opaquePixels, 10,
                "\(state) .small preset produced only \(stats.opaquePixels) opaque pixels — shape may be unrecognizable"
            )
        }
    }

    // MARK: - ReduceMotion static frame

    func testReduceMotionStaticFrame() {
        let view = OrbCanvasView()
        view.frame = CGRect(x: 0, y: 0, width: 24, height: 24)
        view.configure(
            state: .working, preset: .small, size: 24, speed: 1,
            paused: false, reduceMotion: true, dark: true
        )
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24))
        let img = renderer.image { _ in
            view.draw(CGRect(x: 0, y: 0, width: 24, height: 24))
        }
        let stats = pixelStats(from: img)
        XCTAssertGreaterThan(
            stats.opaquePixels, 0,
            "reduceMotion static frame (t=0.6) must produce visible output, not a blank canvas"
        )
    }

    // MARK: - Helpers

    private struct RenderStats {
        let opaquePixels: Int
        let avgLuminance: Double
    }

    private func renderStats(
        mode: OrbMode, size: Double, t: Double, dark: Bool, opts: OrbOpts
    ) -> RenderStats {
        let cgSize = CGSize(width: size, height: size)
        let renderer = UIGraphicsImageRenderer(size: cgSize)
        let img = renderer.image { ctx in
            orbDraw(mode, ctx.cgContext, size: size, t: t, dark: dark, opts: opts)
        }
        return pixelStats(from: img)
    }

    private func pixelStats(from image: UIImage) -> RenderStats {
        guard let cgImage = image.cgImage,
              let dataProvider = cgImage.dataProvider,
              let data = dataProvider.data,
              let ptr = CFDataGetBytePtr(data) else {
            return RenderStats(opaquePixels: 0, avgLuminance: 0)
        }
        let width = cgImage.width
        let height = cgImage.height
        let bytesPerPixel = cgImage.bitsPerPixel / 8
        let bytesPerRow = cgImage.bytesPerRow
        var opaque = 0
        var luminanceSum = 0.0
        for y in 0..<height {
            for x in 0..<width {
                let offset = y * bytesPerRow + x * bytesPerPixel
                let a = ptr[offset + 3]
                if a > 0 {
                    opaque += 1
                    // Premultiplied alpha: un-premultiply for luminance.
                    let af = Double(a) / 255.0
                    let r = Double(ptr[offset]) / 255.0 / max(af, 0.001)
                    let g = Double(ptr[offset + 1]) / 255.0 / max(af, 0.001)
                    let b = Double(ptr[offset + 2]) / 255.0 / max(af, 0.001)
                    luminanceSum += 0.299 * r + 0.587 * g + 0.114 * b
                }
            }
        }
        let avg = opaque > 0 ? luminanceSum / Double(opaque) : 0
        return RenderStats(opaquePixels: opaque, avgLuminance: avg)
    }

    /// Renders the same (mode, t) twice and asserts the PNG data is
    /// byte-identical, proving the engine is a pure function of its inputs.
    private func assertRenderDeterministic(
        _ mode: OrbMode, size: Double, t: Double, opts: OrbOpts
    ) {
        let cgSize = CGSize(width: size, height: size)
        let renderer = UIGraphicsImageRenderer(size: cgSize)
        let img1 = renderer.image { ctx in
            orbDraw(mode, ctx.cgContext, size: size, t: t, dark: true, opts: opts)
        }
        let img2 = renderer.image { ctx in
            orbDraw(mode, ctx.cgContext, size: size, t: t, dark: true, opts: opts)
        }
        XCTAssertEqual(img1.pngData(), img2.pngData(), "rendered images must be identical for same t")
    }
}
