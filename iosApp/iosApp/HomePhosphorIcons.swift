import SwiftUI

/// 首页图标体系：Phosphor Icons fill 系列（设计 §1 硬约束：全部实心、currentColor 着色）。
///
/// 路径数据与设计定稿原型 home-replica.html 内嵌的 `<symbol>` 逐字节同源
/// （Phosphor fill, 256×256 viewBox, nonzero 填充规则）；
/// 回退气泡 chat-circle-fill 与置顶 push-pin-fill 取自 phosphor-icons/core 官方仓库同名资源。
/// 禁止混入 SF Symbols 线稿图标——这是此前版本被判「格格不入」的根因。
enum HomePhosphor: String, CaseIterable {
    case magnifyingGlass
    case gear
    case pen
    case imageSquare
    case bookOpen
    case notebook
    case chatCircleDots
    case squaresFour
    case globe
    case moon
    case wine
    case sword
    case crown
    case list
    case scales
    case musicNotes
    case mapPin
    case pill
    case pencil
    case chatCircle
    case pushPin

    /// 原始 SVG path data（256 坐标系）。
    var pathData: String {
        switch self {
        case .magnifyingGlass:
            return "M168,112a56,56,0,1,1-56-56A56,56,0,0,1,168,112Zm61.66,117.66a8,8,0,0,1-11.32,0l-50.06-50.07a88,88,0,1,1,11.32-11.31l50.06,50.06A8,8,0,0,1,229.66,229.66ZM112,184a72,72,0,1,0-72-72A72.08,72.08,0,0,0,112,184Z"
        case .gear:
            return "M216,130.16q.06-2.16,0-4.32l14.92-18.64a8,8,0,0,0,1.48-7.06,107.6,107.6,0,0,0-10.88-26.25,8,8,0,0,0-6-3.93l-23.72-2.64q-1.48-1.56-3-3L186,40.54a8,8,0,0,0-3.94-6,107.29,107.29,0,0,0-26.25-10.86,8,8,0,0,0-7.06,1.48L130.16,40Q128,40,125.84,40L107.2,25.11a8,8,0,0,0-7.06-1.48A107.6,107.6,0,0,0,73.89,34.51a8,8,0,0,0-3.93,6L67.32,64.27q-1.56,1.49-3,3L40.54,70a8,8,0,0,0-6,3.94,107.71,107.71,0,0,0-10.87,26.25,8,8,0,0,0,1.49,7.06L40,125.84Q40,128,40,130.16L25.11,148.8a8,8,0,0,0-1.48,7.06,107.6,107.6,0,0,0,10.88,26.25,8,8,0,0,0,6,3.93l23.72,2.64q1.49,1.56,3,3L70,215.46a8,8,0,0,0,3.94,6,107.71,107.71,0,0,0,26.25,10.87,8,8,0,0,0,7.06-1.49L125.84,216q2.16.06,4.32,0l18.64,14.92a8,8,0,0,0,7.06,1.48,107.21,107.21,0,0,0,26.25-10.88,8,8,0,0,0,3.93-6l2.64-23.72q1.56-1.48,3-3L215.46,186a8,8,0,0,0,6-3.94,107.71,107.71,0,0,0,10.87-26.25,8,8,0,0,0-1.49-7.06ZM128,168a40,40,0,1,1,40-40A40,40,0,0,1,128,168Z"
        case .pen:
            return "M227.32,73.37,182.63,28.69a16,16,0,0,0-22.63,0L36.69,152A15.86,15.86,0,0,0,32,163.31V208a16,16,0,0,0,16,16H92.69A15.86,15.86,0,0,0,104,219.31l83.67-83.66,3.48,13.9-36.8,36.79a8,8,0,0,0,11.31,11.32l40-40a8,8,0,0,0,2.11-7.6l-6.9-27.61L227.32,96A16,16,0,0,0,227.32,73.37ZM192,108.69,147.32,64l24-24L216,84.69Z"
        case .imageSquare:
            return "M208,32H48A16,16,0,0,0,32,48V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM48,48H208v77.38l-24.69-24.7a16,16,0,0,0-22.62,0L53.37,208H48ZM80,96a16,16,0,1,1,16,16A16,16,0,0,1,80,96Z"
        case .bookOpen:
            return "M240,56V200a8,8,0,0,1-8,8H160a24,24,0,0,0-24,23.94,7.9,7.9,0,0,1-5.12,7.55A8,8,0,0,1,120,232a24,24,0,0,0-24-24H24a8,8,0,0,1-8-8V56a8,8,0,0,1,8-8H88a32,32,0,0,1,32,32v87.73a8.17,8.17,0,0,0,7.47,8.25,8,8,0,0,0,8.53-8V80a32,32,0,0,1,32-32h64A8,8,0,0,1,240,56Z"
        case .notebook:
            return "M208,32H48A16,16,0,0,0,32,48V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM80,208H48V48H80Zm96-56H112a8,8,0,0,1,0-16h64a8,8,0,0,1,0,16Zm0-32H112a8,8,0,0,1,0-16h64a8,8,0,0,1,0,16Z"
        case .chatCircleDots:
            return "M128,24A104,104,0,0,0,36.18,176.88L24.83,210.93a16,16,0,0,0,20.24,20.24l34.05-11.35A104,104,0,1,0,128,24ZM84,140a12,12,0,1,1,12-12A12,12,0,0,1,84,140Zm44,0a12,12,0,1,1,12-12A12,12,0,0,1,128,140Zm44,0a12,12,0,1,1,12-12A12,12,0,0,1,128,140Zm44,0a12,12,0,1,1,12-12A12,12,0,0,1,172,140Z"
        case .squaresFour:
            return "M120,56v48a16,16,0,0,1-16,16H56a16,16,0,0,1-16-16V56A16,16,0,0,1,56,40h48A16,16,0,0,1,120,56Zm80-16H152a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V56A16,16,0,0,0,200,40Zm-96,96H56a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V152A16,16,0,0,0,104,136Zm96,0H152a16,16,0,0,0-16,16v48a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V152A16,16,0,0,0,200,136Z"
        case .globe:
            return "M128,24h0A104,104,0,1,0,232,128,104.12,104.12,0,0,0,128,24Zm78.36,64H170.71a135.28,135.28,0,0,0-22.3-45.6A88.29,88.29,0,0,1,206.37,88ZM216,128a87.61,87.61,0,0,1-3.33,24H174.16a157.44,157.44,0,0,0,0-48h38.51A87.61,87.61,0,0,1,216,128ZM128,43a115.27,115.27,0,0,1,26,45H102A115.11,115.11,0,0,1,128,43ZM102,168H154a115.11,115.11,0,0,1-26,45A115.27,115.27,0,0,1,102,168Zm-3.9-16a140.84,140.84,0,0,1,0-48h59.88a140.84,140.84,0,0,1,0,48Zm50.35,61.6a135.28,135.28,0,0,0,22.3-45.6h35.66A88.29,88.29,0,0,1,148.41,213.6Z"
        case .moon:
            return "M235.54,150.21a104.84,104.84,0,0,1-37,52.91A104,104,0,0,1,32,120,103.09,103.09,0,0,1,52.88,57.48a104.84,104.84,0,0,1,52.91-37,8,8,0,0,1,10,10,88.08,88.08,0,0,0,109.8,109.8,8,8,0,0,1,10,10Z"
        case .wine:
            return "M205.33,103.67,183.56,29.74A8,8,0,0,0,175.89,24H80.11a8,8,0,0,0-7.67,5.74L50.67,103.67a63.46,63.46,0,0,0,17.42,64.67A87.41,87.41,0,0,0,120,191.63V232H88a8,8,0,1,0,0,16h80a8,8,0,1,0,0-16H136V191.63a87.39,87.39,0,0,0,51.91-23.29A63.48,63.48,0,0,0,205.33,103.67ZM86.09,40h83.82L190,108.19c.09.3.17.6.25.9-21.42,7.68-45.54-1.6-58.63-8.23C106.43,88.11,86.43,86.49,71.68,88.93Z"
        case .sword:
            return "M216,32H152a8,8,0,0,0-6.34,3.12l-64,83.21L72,108.69a16,16,0,0,0-22.64,0l-8.69,8.7a16,16,0,0,0,0,22.63l22,22-32,32a16,16,0,0,0,0,22.63l8.69,8.68a16,16,0,0,0,22.62,0l32-32,22,22a16,16,0,0,0,22.64,0l8.69-8.7a16,16,0,0,0,0-22.63l-9.64-9.64,83.21-64A8,8,0,0,0,224,104V40A8,8,0,0,0,216,32Zm-8,68.06-81.74,62.88L115.32,152l50.34-50.34a8,8,0,0,0-11.32-11.31L104,140.68,93.07,129.74,155.94,48H208Z"
        case .crown:
            return "M248,80a28,28,0,1,0-51.12,15.77l-26.79,33L146,73.4a28,28,0,1,0-36.06,0L85.91,128.74l-26.79-33a28,28,0,1,0-26.6,12L47,194.63A16,16,0,0,0,62.78,208H193.22A16,16,0,0,0,209,194.63l14.47-86.85A28,28,0,0,0,248,80ZM128,40a12,12,0,1,1-12,12A12,12,0,0,1,128,40ZM24,80A12,12,0,1,1,36,92,12,12,0,0,1,24,80ZM220,92a12,12,0,1,1,12-12A12,12,0,0,1,220,92Z"
        case .list:
            return "M208,32H48A16,16,0,0,0,32,48V208a16,16,0,0,0,16,16H208a16,16,0,0,0,16-16V48A16,16,0,0,0,208,32ZM192,184H64a8,8,0,0,1,0-16H192a8,8,0,0,1,0,16Zm0-48H64a8,8,0,0,1,0-16H192a8,8,0,0,1,0,16Zm0-48H64a8,8,0,0,1,0-16H192a8,8,0,0,1,0,16Z"
        case .scales:
            return "M239.43,133l-32-80A8,8,0,0,0,200,48a8.27,8.27,0,0,0-1.73.21L136,62V40a8,8,0,0,0-16,0V65.58L54.27,80.21A8,8,0,0,0,48.57,85l-32,80a7.92,7.92,0,0,0-.57,3c0,23.31,24.54,32,40,32s40-8.69,40-32a7.92,7.92,0,0,0-.57-3L66.92,93.77,120,82V208H104a8,8,0,0,0,0,16h48a8,8,0,0,0,0-16H136V78.42L187,67.1,160.57,133a7.92,7.92,0,0,0-.57,3c0,23.31,24.54,32,40,32s40-8.69,40-32A7.92,7.92,0,0,0,239.43,133Zm-160,35H32.62L56,109.54Zm97.24-32L200,77.54,223.38,136Z"
        case .musicNotes:
            return "M212.92,17.71a7.89,7.89,0,0,0-6.86-1.46l-128,32A8,8,0,0,0,72,56V166.1A36,36,0,1,0,88,196V102.25l112-28V134.1A36,36,0,1,0,216,164V24A8,8,0,0,0,212.92,17.71Z"
        case .mapPin:
            return "M128,16a88.1,88.1,0,0,0-88,88c0,75.3,80,132.17,83.41,134.55a8,8,0,0,0,9.18,0C136,236.17,216,179.3,216,104A88.1,88.1,0,0,0,128,16Zm0,56a32,32,0,1,1-32,32A32,32,0,0,1,128,72Z"
        case .pill:
            return "M216.43,39.6a53.27,53.27,0,0,0-75.33,0L39.6,141.09a53.26,53.26,0,0,0,75.32,75.31L216.43,114.91A53.32,53.32,0,0,0,216.43,39.6Zm-11.32,64-50.75,50.74-52.69-52.68,50.75-50.75a37.26,37.26,0,0,1,52.69,52.69ZM189.68,82.34a8,8,0,0,1,0,11.32l-24,24a8,8,0,1,1-11.31-11.32l24-24A8,8,0,0,1,189.68,82.34Z"
        case .pencil:
            return "M227.31,73.37,182.63,28.68a16,16,0,0,0-22.63,0L36.69,152A15.86,15.86,0,0,0,32,163.31V208a16,16,0,0,0,16,16H92.69A15.86,15.86,0,0,0,104,219.31L227.31,96a16,16,0,0,0,0-22.63ZM51.31,160l90.35-90.35,16.68,16.69L68,176.68ZM48,179.31,76.69,208H48Zm48,25.38L79.31,188l90.35-90.35h0l16.68,16.69Z"
        case .chatCircle:
            return "M232,128A104,104,0,0,1,79.12,219.82L45.07,231.17a16,16,0,0,1-20.24-20.24l11.35-34.05A104,104,0,1,1,232,128Z"
        case .pushPin:
            return "M235.33,104l-53.47,53.65c4.56,12.67,6.45,33.89-13.19,60A15.93,15.93,0,0,1,157,224c-.38,0-.75,0-1.13,0a16,16,0,0,1-11.32-4.69L96.29,171,53.66,213.66a8,8,0,0,1-11.32-11.32L85,159.71l-48.3-48.3A16,16,0,0,1,38,87.63c25.42-20.51,49.75-16.48,60.4-13.14L152,20.7a16,16,0,0,1,22.63,0l60.69,60.68A16,16,0,0,1,235.33,104Z"
        }
    }

    /// 解析后的 SwiftUI Path（256×256 坐标系，缓存一次）。
    var renderedPath: Path {
        HomePhosphorPathCache.shared.path(for: self)
    }
}

/// 解析缓存：首次使用时按枚举值解析并常驻（20 个小路径，可忽略内存）。
final class HomePhosphorPathCache: @unchecked Sendable {
    static let shared = HomePhosphorPathCache()
    private let lock = NSLock()
    private var cache: [HomePhosphor: Path] = [:]

    func path(for icon: HomePhosphor) -> Path {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[icon] { return cached }
        let parsed = HomeSVGPathParser.parse(icon.pathData)
        cache[icon] = parsed
        return parsed
    }
}

/// Phosphor 实心字形：在 256 viewBox 与目标矩形之间做等比缩放（容器均给正方形 frame）。
struct HomePhosphorShape: Shape {
    let icon: HomePhosphor

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 256
        let transform = CGAffineTransform(translationX: rect.minX, y: rect.minY).scaledBy(x: scale, y: scale)
        return icon.renderedPath.applying(transform)
    }
}

/// 等价于 Phosphor 的 currentColor 用法：颜色由调用方 `.foregroundStyle` 决定。
struct HomePhosphorIcon: View {
    let icon: HomePhosphor
    var size: CGFloat = 20

    init(_ icon: HomePhosphor, size: CGFloat = 20) {
        self.icon = icon
        self.size = size
    }

    var body: some View {
        HomePhosphorShape(icon: icon)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// 最小 SVG path 解析器：支持 M/L/H/V/C/S/Q/T/A/Z（含小写相对指令与隐式重复），
/// 椭圆弧按 SVG 1.1 F.6.5 endpoint→center 参数化后拆为 ≤90° 的三次贝塞尔段。
/// 只覆盖 Phosphor fill 实际使用的指令集；遇到无法解析的输入返回空 Path（契约测试会拦截）。
enum HomeSVGPathParser {
    static func parse(_ d: String) -> Path {
        var scanner = HomeSVGPathScanner(string: d)
        var path = Path()
        var current = CGPoint.zero
        var subpathStart = CGPoint.zero
        var previousCubicControl: CGPoint?
        var previousQuadControl: CGPoint?

        func resetSmooth(for command: Character) {
            if command != "C", command != "c", command != "S", command != "s" { previousCubicControl = nil }
            if command != "Q", command != "q", command != "T", command != "t" { previousQuadControl = nil }
        }

        while let command = scanner.nextCommand() {
            let relative = command.isLowercase
            let upper = Character(command.uppercased())

            func point() -> CGPoint? {
                guard let x = scanner.nextNumber(), let y = scanner.nextNumber() else { return nil }
                return relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
            }

            switch upper {
            case "M":
                guard let first = point() else { return path }
                path.move(to: first)
                current = first
                subpathStart = first
                // 隐式：后续坐标对按 L 处理。
                while let next = point() {
                    path.addLine(to: next)
                    current = next
                }
                resetSmooth(for: upper)
            case "L":
                while let next = point() {
                    path.addLine(to: next)
                    current = next
                }
                resetSmooth(for: upper)
            case "H":
                while let x = scanner.nextNumber() {
                    current = CGPoint(x: relative ? current.x + x : x, y: current.y)
                    path.addLine(to: current)
                }
                resetSmooth(for: upper)
            case "V":
                while let y = scanner.nextNumber() {
                    current = CGPoint(x: current.x, y: relative ? current.y + y : y)
                    path.addLine(to: current)
                }
                resetSmooth(for: upper)
            case "C":
                while let c1 = point(), let c2 = point(), let end = point() {
                    path.addCurve(to: end, control1: c1, control2: c2)
                    previousCubicControl = c2
                    current = end
                }
                previousQuadControl = nil
            case "S":
                while let c2 = point(), let end = point() {
                    let c1 = previousCubicControl.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
                    path.addCurve(to: end, control1: c1, control2: c2)
                    previousCubicControl = c2
                    current = end
                }
                previousQuadControl = nil
            case "Q":
                while let control = point(), let end = point() {
                    path.addQuadCurve(to: end, control: control)
                    previousQuadControl = control
                    current = end
                }
                previousCubicControl = nil
            case "T":
                while let end = point() {
                    let control = previousQuadControl.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
                    path.addQuadCurve(to: end, control: control)
                    previousQuadControl = control
                    current = end
                }
                previousCubicControl = nil
            case "A":
                while let rx = scanner.nextNumber(), let ry = scanner.nextNumber(), let rotation = scanner.nextNumber(),
                      let largeArc = scanner.nextNumber(), let sweep = scanner.nextNumber(),
                      let end = point() {
                    addArc(to: &path, from: current, to: end,
                           rx: rx, ry: ry, rotationDegrees: rotation,
                           largeArc: largeArc != 0, sweep: sweep != 0)
                    current = end
                }
                resetSmooth(for: upper)
            case "Z":
                path.closeSubpath()
                current = subpathStart
                previousCubicControl = nil
                previousQuadControl = nil
            default:
                return path
            }
        }
        return path
    }

    /// SVG 椭圆弧 → 多段三次贝塞尔（SVG 1.1 F.6.5 / F.6.6）。
    private static func addArc(
        to path: inout Path,
        from start: CGPoint, to end: CGPoint,
        rx rawRX: CGFloat, ry rawRY: CGFloat,
        rotationDegrees: CGFloat,
        largeArc: Bool, sweep: Bool
    ) {
        var rx = abs(rawRX)
        var ry = abs(rawRY)
        guard rx > .ulpOfOne, ry > .ulpOfOne, start != end else {
            path.addLine(to: end)
            return
        }

        let phi = rotationDegrees * .pi / 180
        let cosPhi = cos(phi)
        let sinPhi = sin(phi)

        // F.6.5.1：变换到单位圆坐标系。
        let dx = (start.x - end.x) / 2
        let dy = (start.y - end.y) / 2
        let x1p = cosPhi * dx + sinPhi * dy
        let y1p = -sinPhi * dx + cosPhi * dy

        // F.6.6.2：半径不足时放大。
        let lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if lambda > 1 {
            let factor = sqrt(lambda)
            rx *= factor
            ry *= factor
        }

        // F.6.5.2：求圆心。
        let numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        let denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        let sign: CGFloat = largeArc != sweep ? 1 : -1
        let coefficient = sign * sqrt(max(0, numerator / denominator))
        let cxp = coefficient * rx * y1p / ry
        let cyp = -coefficient * ry * x1p / rx
        let cx = cosPhi * cxp - sinPhi * cyp + (start.x + end.x) / 2
        let cy = sinPhi * cxp + cosPhi * cyp + (start.y + end.y) / 2

        // F.6.5.3/.5：起止角与扫掠角。
        func angle(ux: CGFloat, uy: CGFloat, vx: CGFloat, vy: CGFloat) -> CGFloat {
            let dot = ux * vx + uy * vy
            let length = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            var value = acos(Swift.min(1, Swift.max(-1, dot / length)))
            if ux * vy - uy * vx < 0 { value = -value }
            return value
        }
        let theta1 = angle(ux: 1, uy: 0, vx: (x1p - cxp) / rx, vy: (y1p - cyp) / ry)
        var delta = angle(
            ux: (x1p - cxp) / rx, uy: (y1p - cyp) / ry,
            vx: (-x1p - cxp) / rx, vy: (-y1p - cyp) / ry
        )
        if sweep, delta < 0 { delta += 2 * .pi }
        if !sweep, delta > 0 { delta -= 2 * .pi }

        // F.6.6：拆成 ≤90° 段，逐段转三次贝塞尔。
        let segmentCount = max(1, Int(ceil(abs(delta) / (.pi / 2))))
        let segment = delta / CGFloat(segmentCount)
        for index in 0..<segmentCount {
            let t1 = theta1 + CGFloat(index) * segment
            let t2 = t1 + segment
            let alpha = 4 / 3 * tan((t2 - t1) / 4)

            let p2 = CGPoint(x: cx + rx * cosPhi * cos(t2) - ry * sinPhi * sin(t2),
                             y: cy + rx * sinPhi * cos(t2) + ry * cosPhi * sin(t2))
            let d1 = CGPoint(x: -rx * cosPhi * sin(t1) - ry * sinPhi * cos(t1),
                             y: -rx * sinPhi * sin(t1) + ry * cosPhi * cos(t1))
            let d2 = CGPoint(x: -rx * cosPhi * sin(t2) - ry * sinPhi * cos(t2),
                             y: -rx * sinPhi * sin(t2) + ry * cosPhi * cos(t2))

            let p1 = CGPoint(x: cx + rx * cosPhi * cos(t1) - ry * sinPhi * sin(t1),
                             y: cy + rx * sinPhi * cos(t1) + ry * cosPhi * sin(t1))
            let control1 = CGPoint(x: p1.x + alpha * d1.x, y: p1.y + alpha * d1.y)
            let control2 = CGPoint(x: p2.x - alpha * d2.x, y: p2.y - alpha * d2.y)
            path.addCurve(to: index == segmentCount - 1 ? end : p2, control1: control1, control2: control2)
        }
    }
}

/// SVG path 词法扫描：命令字母 + 紧凑浮点（允许 "1-3"、".28" 形态）。
private struct HomeSVGPathScanner {
    private let scalars: [Character]
    private var index = 0

    init(string: String) {
        scalars = Array(string)
    }

    private static let separators: Set<Character> = [",", " ", "\t", "\n", "\r"]

    private mutating func skipSeparators() {
        while index < scalars.count, HomeSVGPathScanner.separators.contains(scalars[index]) { index += 1 }
    }

    mutating func nextCommand() -> Character? {
        skipSeparators()
        guard index < scalars.count else { return nil }
        let scalar = scalars[index]
        if scalar.isLetter {
            index += 1
            return scalar
        }
        return nil
    }

    /// 只有在下一非分隔字符不是命令字母时才读取数字；读不到返回 nil（该参数组结束）。
    mutating func nextNumber() -> CGFloat? {
        skipSeparators()
        guard index < scalars.count, !scalars[index].isLetter else { return nil }
        let start = index
        if scalars[index] == "-" || scalars[index] == "+" { index += 1 }
        var sawDigit = false
        var sawDot = false
        while index < scalars.count {
            let scalar = scalars[index]
            if scalar.isNumber {
                sawDigit = true
                index += 1
            } else if scalar == ".", !sawDot {
                sawDot = true
                index += 1
            } else {
                break
            }
        }
        guard sawDigit else {
            index = start
            return nil
        }
        // 科学计数法（Phosphor 未使用，防御性支持）。
        if index < scalars.count, scalars[index] == "e" || scalars[index] == "E" {
            var lookahead = index + 1
            if lookahead < scalars.count, scalars[lookahead] == "-" || scalars[lookahead] == "+" { lookahead += 1 }
            if lookahead < scalars.count, scalars[lookahead].isNumber {
                index = lookahead
                while index < scalars.count, scalars[index].isNumber { index += 1 }
            }
        }
        defer { skipSeparators() }
        return CGFloat(Double(String(scalars[start..<index])) ?? 0)
    }
}
