// Thinking-orb dot engine, ported from Jakub Antalik's `thinking-orbs`
// (MIT, https://github.com/Jakubantalik/thinking-orbs). The math is a
// faithful 1:1 translation of the TypeScript engine so the six states look
// identical to the reference. This file is UI-free: pure functions mapping a
// time `t` to a z-sorted list of grayscale dots. The UIKit renderer lives in
// ThinkingOrbView.swift and draws these dots with CGContext.
//
// Honestly 3D — rotated, depth-shaded, z-sorted. Depth is carried by dot size
// and ink weight alone; no blur or gradient filters, so it renders the same on
// every substrate.

import CoreGraphics
import Foundation

// MARK: - Core primitives

struct OrbDot {
    var x: Double
    var y: Double
    var z: Double
    var r: Double
    /// Ink value: 0 = darkest ink on paper. Mirrored on dark themes.
    var white: Double
    var a: Double
}

typealias OrbOpts = [String: Double]
typealias OrbProjector = @Sendable (Double, Double, Double) -> (Double, Double, Double)

private func ov(_ o: OrbOpts, _ k: String, _ d: Double) -> Double {
    o[k] ?? d
}

/// Deterministic hash in [0, 1).
func orbHash(_ a: Double, _ b: Double) -> Double {
    let h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}

/// Stable directions on a unit sphere (Fibonacci lattice).
func orbFibDir(_ i: Int, _ n: Int) -> (Double, Double, Double) {
    let golden = Double.pi * (3 - sqrt(5))
    let y = 1 - (2 * (Double(i) + 0.5)) / Double(n)
    let rad = sqrt(1 - y * y)
    let a = Double(i) * golden
    return (rad * cos(a), y, rad * sin(a))
}

/// Shortest signed angular distance, wrapped to (-π, π].
private func orbAngleDelta(_ a: Double, _ b: Double) -> Double {
    atan2(sin(a - b), cos(a - b))
}

/// Shared spin + tilt + orthographic projection. Note `z` is returned
/// un-scaled (normalised ±1 for unit-sphere inputs) so depth reads stay
/// consistent regardless of the pixel `scale` applied to x/y.
func orbMakeProj(
    yaw: Double, tilt: Double, cx: Double, cy: Double, scale: Double
) -> OrbProjector {
    let st = sin(tilt)
    let ct = cos(tilt)
    let sy = sin(yaw)
    let cyw = cos(yaw)
    return { x, y, z in
        let x1 = x * cyw + z * sy
        let z1 = -x * sy + z * cyw
        let y1 = y * ct - z1 * st
        let z2 = y * st + z1 * ct
        return (cx + x1 * scale, cy - y1 * scale, z2)
    }
}

/// Dot radii were tuned for a 300pt frame; sub-linear scaling keeps small
/// spinners legible. Lower pow = radii shrink less with size.
func orbRadiusScale(_ size: Double, _ pow: Double) -> Double {
    Foundation.pow(size / 300, pow)
}

/// z-sort far→near, matte grayscale dots. On dark substrates the ink value is
/// mirrored (1 - white) so near dots read bright — the same depth language on
/// an inverted substrate.
func orbPaint(_ ctx: CGContext, _ dots: inout [OrbDot], dark: Bool, rMin: Double = 0.3) {
    dots.sort { $0.z < $1.z }
    for d in dots {
        if d.a < 0.02 { continue }
        let w = min(1, max(0, d.white))
        let g = (dark ? 1 - w : w)
        // Zero-allocation path: single C call, no CGColor/CGColorSpace/Array.
        ctx.setFillColor(red: CGFloat(g), green: CGFloat(g), blue: CGFloat(g), alpha: CGFloat(d.a))
        let rr = max(rMin, d.r)
        ctx.fillEllipse(in: CGRect(x: d.x - rr, y: d.y - rr, width: rr * 2, height: rr * 2))
    }
}

// MARK: - Orbits (working)

func orbDrawOrbits(
    _ ctx: CGContext, size: Double, t: Double, dark: Bool, o: OrbOpts
) {
    let cx = size / 2
    let cy = size / 2
    let R = (size / 2) * 0.82
    let pt = orbMakeProj(yaw: t * 0.12, tilt: 0.3, cx: cx, cy: cy, scale: 1)
    let rs = orbRadiusScale(size, ov(o, "rsPow", 0.6))

    var dots: [OrbDot] = []
    let orbitN = Int(ov(o, "orbitN", 12))
    let ghostN = Int(ov(o, "ghostN", 40))
    let particles = Int(ov(o, "particles", 3))

    for orb in 0..<orbitN {
        let h1 = orbHash(Double(orb), 1.7)
        let h2 = orbHash(Double(orb), 5.2)
        let h3 = orbHash(Double(orb), 8.9)
        let ro = R * (0.45 + 0.52 * h1)
        let th = h1 * 2 * .pi
        let phi = acos(2 * h2 - 1)
        let nx = sin(phi) * cos(th)
        let ny = cos(phi)
        let nz = sin(phi) * sin(th)
        var ux = -ny
        var uy = nx
        let uz = 0.0
        let ul = max(1e-6, sqrt(ux * ux + uy * uy))
        ux /= ul
        uy /= ul
        let vx = ny * uz - nz * uy
        let vy = nz * ux - nx * uz
        let vz = nx * uy - ny * ux
        let speed = (0.25 + 0.55 * h3) * (h3 > 0.5 ? 1 : -1)

        for k in 0..<ghostN {
            let a = (Double(k) / Double(ghostN)) * 2 * .pi
            let ca = cos(a)
            let sa = sin(a)
            let (px, py, z) = pt(
                (ux * ca + vx * sa) * ro,
                (uy * ca + vy * sa) * ro,
                (uz * ca + vz * sa) * ro
            )
            let depth = (z / ro + 1) / 2
            dots.append(OrbDot(
                x: px, y: py, z: z,
                r: ov(o, "ghostR", 0.9) * rs,
                white: 0.72,
                a: ov(o, "ghostA", 0.5) * (0.4 + 0.6 * depth)
            ))
        }
        for m in 0..<particles {
            let a = t * speed + (Double(m) / Double(particles)) * 2 * .pi + h2 * 6
            let ca = cos(a)
            let sa = sin(a)
            let (px, py, z) = pt(
                (ux * ca + vx * sa) * ro,
                (uy * ca + vy * sa) * ro,
                (uz * ca + vz * sa) * ro
            )
            let depth = (z / ro + 1) / 2
            dots.append(OrbDot(
                x: px, y: py, z: z,
                r: (ov(o, "partR", 1.2) + ov(o, "partRDepth", 1.6) * depth) * rs,
                white: 0.3 - 0.22 * depth,
                a: 1
            ))
        }
    }
    orbPaint(ctx, &dots, dark: dark, rMin: ov(o, "rMin", 0.3))
}

// MARK: - Lattice modes (globe / rubik / wave)

private struct OrbMove {
    let axis: Int
    let lo: Double
    let hi: Double
    let ang: Double
}

private func orbMakeMoves(_ count: Int) -> [OrbMove] {
    var moves: [OrbMove] = []
    for i in 0..<count {
        let axis = min(2, Int(floor(orbHash(Double(i), 2.3) * 3)))
        let lo = -1.0 + 0.5 * Double(min(3, Int(floor(orbHash(Double(i), 5.9) * 4))))
        let dir: Double = orbHash(Double(i), 7.7) < 0.5 ? 1 : -1
        moves.append(OrbMove(axis: axis, lo: lo, hi: lo + 0.5, ang: dir * .pi / 2))
    }
    return moves
}

private func orbSolveCycle(
    _ time: Double, count: Int, slotDur: Double, rest: Double
) -> (amount: [Double], active: Int) {
    let cyc = 2 * Double(count) * slotDur + rest
    let tc = time.truncatingRemainder(dividingBy: cyc)
    var amount = [Double](repeating: 0, count: count)
    var active = -1
    if tc < 2 * Double(count) * slotDur {
        let slot = Int(floor(tc / slotDur))
        let p = (tc - Double(slot) * slotDur) / slotDur
        let cl = min(1, p / 0.7)
        let ep = 1 - pow(1 - cl, 3)
        if slot < count {
            for i in 0..<slot { amount[i] = 1 }
            amount[slot] = ep
            active = slot
        } else {
            let u = 2 * count - 1 - slot
            for i in 0..<u { amount[i] = 1 }
            amount[u] = 1 - ep
            active = u
        }
    }
    return (amount, active)
}

private func orbApplyMoves(
    _ p: (Double, Double, Double), moves: [OrbMove],
    sc: (amount: [Double], active: Int)
) -> (Double, Double, Double, Bool) {
    var (x, y, z) = p
    var inActive = false
    for i in 0..<moves.count {
        if sc.amount[i] <= 0 { continue }
        let mv = moves[i]
        let coord = mv.axis == 0 ? x : mv.axis == 1 ? y : z
        if coord < mv.lo || coord >= mv.hi { continue }
        if i == sc.active { inActive = true }
        let a = mv.ang * sc.amount[i]
        let ca = cos(a)
        let sa = sin(a)
        if mv.axis == 0 {
            let y2 = y * ca - z * sa
            z = y * sa + z * ca
            y = y2
        } else if mv.axis == 1 {
            let x2 = x * ca + z * sa
            z = -x * sa + z * ca
            x = x2
        } else {
            let x2 = x * ca - y * sa
            y = x * sa + y * ca
            x = x2
        }
    }
    return (x, y, z, inActive)
}

func orbDrawGlobe(
    _ ctx: CGContext, size: Double, t: Double, dark: Bool, o: OrbOpts
) {
    let spin = 0.5
    let cx = size / 2
    let cy = size / 2
    let radius = (size / 2) * 0.82
    let tilt = 0.4 + 0.06 * sin(t * 0.35)
    let pt = orbMakeProj(yaw: t * spin, tilt: tilt, cx: cx, cy: cy, scale: radius)
    let scan = t * (spin + (1.7 - spin) * ov(o, "scanMul", 1))
    let rs = orbRadiusScale(size, ov(o, "rsPow", 0.6))
    let dimBase = ov(o, "dimBase", 1)

    var dots: [OrbDot] = []
    let latRings = Int(ov(o, "latRings", 17))
    let lonDensity = Int(ov(o, "lonDensity", 44))
    for li in 0...latRings {
        let lat = -.pi / 2 + (Double(li) / Double(latRings)) * .pi
        let cosLat = cos(lat)
        let sinLat = sin(lat)
        let lonCount = max(1, Int((abs(cosLat) * Double(lonDensity)).rounded()))
        for lj in 0..<lonCount {
            let lon = (Double(lj) / Double(lonCount)) * 2 * .pi
            let (px, py, z) = pt(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            let depth = (z + 1) / 2
            let d = orbAngleDelta(lon + t * spin, scan)
            let boost = exp(-(d * d) / 0.18) * max(0, z)
            dots.append(OrbDot(
                x: px, y: py, z: z,
                r: (ov(o, "rBase", 0.6) + ov(o, "rDepth", 1.7) * depth + ov(o, "rBoost", 1) * boost) * rs,
                white: ov(o, "inkFar", 0.62) - ov(o, "inkSpan", 0.54) * depth,
                a: dimBase + (1 - dimBase) * min(1, boost)
            ))
        }
    }
    orbPaint(ctx, &dots, dark: dark, rMin: ov(o, "rMin", 0.3))
}

func orbDrawRubik(
    _ ctx: CGContext, size: Double, t: Double, dark: Bool, o: OrbOpts
) {
    let cx = size / 2
    let cy = size / 2
    let R = (size / 2) * 0.82
    let pt = orbMakeProj(
        yaw: t * 0.55, tilt: 0.35 + 0.1 * sin(t * 0.9), cx: cx, cy: cy, scale: R
    )
    let rs = orbRadiusScale(size, ov(o, "rsPow", 0.6))
    let moveCount = Int(ov(o, "moveCount", 14))
    let moves = orbMakeMoves(moveCount)
    let sc = orbSolveCycle(t, count: moveCount, slotDur: 0.42, rest: 1.2)

    var dots: [OrbDot] = []
    let latRings = Int(ov(o, "latRings", 15))
    let lonDensity = Int(ov(o, "lonDensity", 40))
    for li in 0...latRings {
        let lat = -.pi / 2 + (Double(li) / Double(latRings)) * .pi
        let cosLat = cos(lat)
        let sinLat = sin(lat)
        let lonCount = max(1, Int((abs(cosLat) * Double(lonDensity)).rounded()))
        for lj in 0..<lonCount {
            let lon = (Double(lj) / Double(lonCount)) * 2 * .pi
            let (x, y, z, inActive) = orbApplyMoves(
                (cosLat * cos(lon), sinLat, cosLat * sin(lon)), moves: moves, sc: sc
            )
            let (px, py, zr) = pt(x, y, z)
            let depth = (zr + 1) / 2
            dots.append(OrbDot(
                x: px, y: py, z: zr,
                r: (ov(o, "rBase", 0.6) + ov(o, "rDepth", 1.7) * depth + (inActive ? ov(o, "rActive", 0.3) : 0)) * rs,
                white: ov(o, "inkFar", 0.62) - ov(o, "inkSpan", 0.54) * depth - (inActive ? 0.14 : 0),
                a: 1
            ))
        }
    }
    orbPaint(ctx, &dots, dark: dark, rMin: ov(o, "rMin", 0.3))
}

func orbDrawWave(
    _ ctx: CGContext, size: Double, t: Double, dark: Bool, o: OrbOpts
) {
    let cx = size / 2
    let cy = size / 2
    let R = (size / 2) * 0.874
    let pt = orbMakeProj(yaw: t * 0.18, tilt: 0.38, cx: cx, cy: cy, scale: 1)
    let rs = orbRadiusScale(size, ov(o, "rsPow", 0.6))

    var dots: [OrbDot] = []
    let rings = Int(ov(o, "rings", 15))
    let lonDensity = Int(ov(o, "lonDensity", 40))
    for ri in 0...rings {
        let lat = -.pi / 2 + (Double(ri) / Double(rings)) * .pi
        let cosLat = cos(lat)
        let sinLat = sin(lat)
        let w = 0.62 * sin(t * 2.1 - Double(ri) * 0.52) + 0.38 * sin(t * 1.27 + Double(ri) * 0.83)
        let rr = R * (0.88 + 0.105 * w)
        let lonCount = max(1, Int((abs(cosLat) * Double(lonDensity)).rounded()))
        for lj in 0..<lonCount {
            let lon = (Double(lj) / Double(lonCount)) * 2 * .pi
            let (px, py, z) = pt(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
            let depth = (z / R + 1) / 2
            let crest = max(0, w)
            dots.append(OrbDot(
                x: px, y: py, z: z,
                r: (ov(o, "rBase", 0.6) + ov(o, "rDepth", 1.7) * depth) * (1 + 0.4 * crest) * rs,
                white: 0.66 - 0.56 * depth - 0.1 * crest,
                a: 1
            ))
        }
    }
    orbPaint(ctx, &dots, dark: dark, rMin: ov(o, "rMin", 0.3))
}

// MARK: - Ribbon (composing)

func orbDrawRibbon(
    _ ctx: CGContext, size: Double, t: Double, dark: Bool, o: OrbOpts
) {
    let cx = size / 2
    let cy = size / 2
    let R = (size / 2) * 0.78
    let spin = ov(o, "spin", 1)
    let pt = orbMakeProj(yaw: t * 0.1 * spin, tilt: 0.3, cx: cx, cy: cy, scale: 1)
    let rs = orbRadiusScale(size, ov(o, "rsPow", 0.6))

    var dots: [OrbDot] = []
    let ghostN = Int(ov(o, "ghostN", 150))
    for i in 0..<ghostN {
        let d = orbFibDir(i, ghostN)
        let (px, py, z) = pt(d.0 * R, d.1 * R, d.2 * R)
        let depth = (z / R + 1) / 2
        dots.append(OrbDot(x: px, y: py, z: z, r: 0.8 * rs, white: 0.78, a: 0.1 + 0.22 * depth))
    }

    let ya = t * 0.24 * spin
    let ta = 0.55 + 0.3 * sin(t * 0.18) * spin
    let ux = cos(ya)
    let uy = 0.0
    let uz = sin(ya)
    let vx = -uz * sin(ta)
    let vy = cos(ta)
    let vz = ux * sin(ta)
    let nx = uy * vz - uz * vy
    let ny = uz * vx - ux * vz
    let nz = ux * vy - uy * vx

    let baseLanes = ov(o, "lanes", 5)
    let segs = Int(ov(o, "segs", 88))
    let lanes = max(1, Int((baseLanes * ov(o, "bandMul", 1)).rounded()))
    for w in 0..<lanes {
        let laneOff = (Double(w) - Double(lanes - 1) / 2) * 0.075
        let edge = abs(Double(w) - Double(lanes - 1) / 2) / max(1, Double(lanes - 1) / 2)
        for k in 0..<segs {
            let a = (Double(k) / Double(segs)) * 2 * .pi
            let wob = (0.16 * sin(a * 3 - t * 1.7 + Double(w) * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * ov(o, "wobMul", 1)
            let off = laneOff + wob
            let x = ux * cos(a) + vx * sin(a) + nx * off
            let y = uy * cos(a) + vy * sin(a) + ny * off
            let z = uz * cos(a) + vz * sin(a) + nz * off
            let l = sqrt(x * x + y * y + z * z)
            let (px, py, zr) = pt((x / l) * R, (y / l) * R, (z / l) * R)
            let depth = (zr / R + 1) / 2
            dots.append(OrbDot(
                x: px, y: py, z: zr,
                r: (ov(o, "rBase", 1.1) + ov(o, "rDepth", 1.7) * depth) * (1 - 0.25 * edge) * rs,
                white: 0.52 - 0.44 * depth + 0.18 * edge,
                a: 0.4 + 0.6 * depth
            ))
        }
    }
    orbPaint(ctx, &dots, dark: dark, rMin: ov(o, "rMin", 0.3))
}

// MARK: - Morph (shaping)

private typealias OrbPath = @Sendable (Double) -> (Double, Double)

private func orbPolyPath(_ verts: [(Double, Double)]) -> OrbPath {
    let V = verts.count
    var tmpL: [Double] = []
    var tmpTotal = 0.0
    for i in 0..<V {
        let a = verts[i]
        let b = verts[(i + 1) % V]
        let l = hypot(b.0 - a.0, b.1 - a.1)
        tmpL.append(l)
        tmpTotal += l
    }
    let L = tmpL
    let total = tmpTotal
    return { f in
        var target = f * total
        var i = 0
        while target > L[i] && i < V - 1 {
            target -= L[i]
            i += 1
        }
        let a = verts[i]
        let b = verts[(i + 1) % V]
        let ff = L[i] != 0 ? min(1, target / L[i]) : 0
        return (a.0 + (b.0 - a.0) * ff, a.1 + (b.1 - a.1) * ff)
    }
}

private let orbCirclePath: OrbPath = { f in
    let a = -.pi / 2 + f * 2 * .pi
    return (cos(a) * 0.24, sin(a) * 0.24)
}

private let orbCycle: [OrbPath] = [
    orbCirclePath,
    orbPolyPath([(0.0, -0.26), (0.24, 0.16), (-0.24, 0.16)]),
    orbPolyPath([(0, -0.2), (0.2, -0.2), (0.2, 0.2), (-0.2, 0.2), (-0.2, -0.2)])
]

private func orbMorphN(_ d: Double) -> Int {
    max(6, Int((34 * d).rounded()))
}

private let orbHold = 1.4
private let orbMorph = 0.9
private let orbSeg = orbHold + orbMorph

private func orbSmoothE(_ x: Double) -> Double {
    x * x * (3 - 2 * x)
}

func orbDrawMorph(
    _ ctx: CGContext, size: Double, t: Double, dark: Bool, o: OrbOpts
) {
    let K = orbCycle.count
    let tc = t.truncatingRemainder(dividingBy: orbSeg * Double(K))
    let k = Int(floor(tc / orbSeg))
    let local = tc - Double(k) * orbSeg
    let m = local > orbHold ? orbSmoothE((local - orbHold) / orbMorph) : 0
    let sprd = ov(o, "spread", 1)

    let pA = orbCycle[k]
    let pB = orbCycle[(k + 1) % K]
    let M = 160
    var pts: [(Double, Double)] = []
    for i in 0..<M {
        let f = Double(i) / Double(M)
        let a = pA(f)
        let b = pB(f)
        pts.append(((a.0 + (b.0 - a.0) * m) * sprd, (a.1 + (b.1 - a.1) * m) * sprd))
    }
    var L: [Double] = []
    var total = 0.0
    for i in 0..<M {
        let a = pts[i]
        let b = pts[(i + 1) % M]
        let l = hypot(b.0 - a.0, b.1 - a.1)
        L.append(l)
        total += l
    }

    let n = orbMorphN(ov(o, "iconD", 1))
    let re = ov(o, "rDot", 0.021) * 1.35 * sprd
    let pulse = 1 + 0.02 * sin(local * 3.1)

    var dots: [OrbDot] = []
    let c2 = size / 2
    var seg = 0
    var acc = 0.0
    for k2 in 0..<n {
        let target = (Double(k2) / Double(n)) * total
        while acc + L[seg] < target && seg < M - 1 {
            acc += L[seg]
            seg += 1
        }
        let a = pts[seg]
        let b = pts[(seg + 1) % M]
        let f = L[seg] != 0 ? min(1, (target - acc) / L[seg]) : 0
        let x = (a.0 + (b.0 - a.0) * f) * pulse
        let y = (a.1 + (b.1 - a.1) * f) * pulse
        dots.append(OrbDot(
            x: c2 + x * size, y: c2 + y * size, z: 0,
            r: max(0.35, re * size), white: 0.1, a: 1
        ))
    }
    orbPaint(ctx, &dots, dark: dark, rMin: ov(o, "rMin", 0.25))
}

// MARK: - Profiles, presets, resolution

enum OrbState: String, CaseIterable {
    case working, searching, solving, listening, composing, shaping
}

/// Tuned density preset. The reference ships exactly two designs (64 = avatar
/// scale, 20 = inline scale); they are separate tunings, not a scale factor.
enum OrbPreset {
    case large   // 64-pt design
    case small   // 20-pt design (used for the compact activity-island glyph)

    fileprivate var key: String { self == .large ? "64" : "20" }
}

enum OrbMode: String {
    case orbits, globe, rubik, wave, ribbon, morph
}

private let orbStateToMode: [OrbState: OrbMode] = [
    .working: .orbits,
    .searching: .globe,
    .solving: .rubik,
    .listening: .wave,
    .composing: .ribbon,
    .shaping: .morph
]

struct OrbResolved {
    let mode: OrbMode
    let speed: Double
    let opts: OrbOpts
}

private let orbBaseProfiles: [OrbMode: OrbOpts] = [
    .globe: [
        "latRings": 17, "lonDensity": 44, "rBase": 0.6, "rDepth": 1.7, "rBoost": 1.0,
        "inkFar": 0.62, "inkSpan": 0.54, "rsPow": 0.6, "rMin": 0.3
    ],
    .orbits: [
        "orbitN": 12, "ghostN": 40, "ghostR": 0.9, "ghostA": 0.5, "particles": 3,
        "partR": 1.2, "partRDepth": 1.6, "rsPow": 0.6, "rMin": 0.3
    ],
    .rubik: [
        "latRings": 15, "lonDensity": 40, "moveCount": 14, "rBase": 0.6, "rDepth": 1.7,
        "rActive": 0.3, "inkFar": 0.62, "inkSpan": 0.54, "rsPow": 0.6, "rMin": 0.3
    ],
    .wave: [
        "rings": 15, "lonDensity": 40, "rBase": 0.6, "rDepth": 1.7, "rsPow": 0.6, "rMin": 0.3
    ],
    .ribbon: [
        "lanes": 5, "segs": 88, "ghostN": 150, "rBase": 1.1, "rDepth": 1.7, "rsPow": 0.6, "rMin": 0.3
    ],
    .morph: [
        "rDot": 0.021, "iconD": 1, "rMin": 0.25
    ]
]

private struct OrbPresetSpec {
    let speed: Double
    let count: Double
    let size: Double
    let extra: OrbOpts
}

private let orbPresets: [OrbMode: [String: OrbPresetSpec]] = [
    .orbits: [
        "64": OrbPresetSpec(speed: 1.885, count: 1, size: 1, extra: [:]),
        "20": OrbPresetSpec(speed: 3.9, count: 0.238, size: 2.4, extra: [:])
    ],
    .globe: [
        "64": OrbPresetSpec(speed: 2.015, count: 0.42, size: 1.15, extra: ["scanMul": 4.08, "dimBase": 0.45]),
        "20": OrbPresetSpec(speed: 2.665, count: 0.105, size: 1.75, extra: ["scanMul": 4.335, "dimBase": 0.45])
    ],
    .rubik: [
        "64": OrbPresetSpec(speed: 1.82, count: 0.35, size: 1.05, extra: [:]),
        "20": OrbPresetSpec(speed: 1.95, count: 0.088, size: 1.9, extra: [:])
    ],
    .wave: [
        "64": OrbPresetSpec(speed: 4.388, count: 0.341, size: 1, extra: [:]),
        "20": OrbPresetSpec(speed: 3.998, count: 0.105, size: 1.6, extra: [:])
    ],
    .ribbon: [
        "64": OrbPresetSpec(speed: 2.34, count: 0.25, size: 0.85, extra: ["spin": 0, "bandMul": 3.9, "wobMul": 1]),
        "20": OrbPresetSpec(speed: 3.12, count: 0.051, size: 1.073, extra: ["spin": 0, "bandMul": 4.94, "wobMul": 1])
    ],
    .morph: [
        "64": OrbPresetSpec(speed: 2.405, count: 0.54, size: 0.395, extra: ["spread": 1.45]),
        "20": OrbPresetSpec(speed: 2.08, count: 0.53, size: 1.011, extra: ["spread": 1.45])
    ]
]

private let orbCountPairs: [(String, String)] = [
    ("latRings", "lonDensity"), ("rings", "lonDensity"), ("lanes", "segs")
]
private let orbCountKeys = ["orbitN", "ghostN"]
private let orbIconDensityKeys = ["iconD"]
private let orbRadiusKeys = ["rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth"]

private func orbScaleCounts(_ opts: OrbOpts, _ scale: Double) -> OrbOpts {
    var out = opts
    var done = Set<String>()
    let rt = sqrt(scale)
    for (a, b) in orbCountPairs {
        if let va = out[a], let vb = out[b], !done.contains(a), !done.contains(b) {
            out[a] = Double(max(2, Int((va * rt).rounded())))
            out[b] = Double(max(2, Int((vb * rt).rounded())))
            done.insert(a)
            done.insert(b)
        }
    }
    for k in orbCountKeys {
        if let v = out[k], !done.contains(k) {
            out[k] = Double(max(1, Int((v * scale).rounded())))
            done.insert(k)
        }
    }
    for k in orbIconDensityKeys {
        if let v = out[k] { out[k] = max(0.02, v * scale) }
    }
    return out
}

private func orbScaleRadii(_ opts: OrbOpts, _ scale: Double) -> OrbOpts {
    var out = opts
    for k in orbRadiusKeys {
        if let v = out[k] { out[k] = v * scale }
    }
    out["rSizeMul"] = (out["rSizeMul"] ?? 1) * scale
    return out
}

/// Resolve a (state, preset) pair to its mode + fully-scaled draw options.
func orbResolvePreset(_ state: OrbState, _ preset: OrbPreset) -> OrbResolved {
    guard let mode = orbStateToMode[state] else {
        fatalError("ThinkingOrb: no mode mapping for state \(state.rawValue)")
    }
    guard let presetMap = orbPresets[mode], let spec = presetMap[preset.key] else {
        fatalError("ThinkingOrb: no preset for mode \(mode.rawValue) size \(preset.key)")
    }
    guard var opts = orbBaseProfiles[mode] else {
        fatalError("ThinkingOrb: no base profile for mode \(mode.rawValue)")
    }
    if spec.count != 1 { opts = orbScaleCounts(opts, spec.count) }
    if spec.size != 1 { opts = orbScaleRadii(opts, spec.size) }
    if !spec.extra.isEmpty { for (k, v) in spec.extra { opts[k] = v } }
    let resolved = OrbResolved(mode: mode, speed: spec.speed, opts: opts)
    return resolved
}

/// Dispatch a resolved mode into its frame painter.
func orbDraw(
    _ mode: OrbMode, _ ctx: CGContext, size: Double, t: Double, dark: Bool, opts: OrbOpts
) {
    switch mode {
    case .orbits: orbDrawOrbits(ctx, size: size, t: t, dark: dark, o: opts)
    case .globe: orbDrawGlobe(ctx, size: size, t: t, dark: dark, o: opts)
    case .rubik: orbDrawRubik(ctx, size: size, t: t, dark: dark, o: opts)
    case .wave: orbDrawWave(ctx, size: size, t: t, dark: dark, o: opts)
    case .ribbon: orbDrawRibbon(ctx, size: size, t: t, dark: dark, o: opts)
    case .morph: orbDrawMorph(ctx, size: size, t: t, dark: dark, o: opts)
    }
}
