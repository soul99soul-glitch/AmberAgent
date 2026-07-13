import XCTest
import SwiftUI
@testable import iosApp

/// Isolated spike for `ScrollPosition.scrollTo(edge: .bottom)`.
///
/// This intentionally does not touch the production chat list. Run it explicitly by
/// setting the test host environment or creating the marker file before xcodebuild:
///
/// `AA_RUN_EDGE_SPIKE=1 xcodebuild test ... -only-testing:iosAppTests/ChatScrollEdgeSpikeTests`
/// `touch /tmp/amberagent-run-edge-spike && xcodebuild test ... -only-testing:iosAppTests/ChatScrollEdgeSpikeTests`
///
/// The candidate is only useful if an edge position remains bottom-preserving while
/// content grows. A single `scrollTo(edge:)` that behaves like a one-shot jump is not
/// enough to replace the current 80ms semantic bottom anchor.
@MainActor
final class ChatScrollEdgeSpikeTests: XCTestCase {
    private static let screenSize = CGSize(width: 393, height: 852)
    private static let markerPath = "/tmp/amberagent-run-edge-spike"

    func testScrollToEdgeBottomPreservesBottomDuringGrowth() throws {
        try requireExplicitSpikeRun()

        let result = runSpike(
            label: "edge-bottom-growth",
            initialRows: 36,
            appendedRows: 48,
            appendInterval: 0.016,
            pinMode: .edgeOnceOnAppear
        )

        result.printSummary()

        XCTAssertTrue(result.completed, "spike harness did not finish streaming rows")
        XCTAssertLessThanOrEqual(
            result.maxAbsOffsetXDrift,
            0.5,
            "edge spike introduced horizontal offset drift relative to its initial geometry"
        )
        XCTAssertLessThanOrEqual(
            result.maxDistanceAfterFirstAppend,
            3.0,
            "scrollTo(edge: .bottom) did not continuously preserve the bottom during content growth"
        )
        XCTAssertLessThanOrEqual(
            result.finalDistance,
            3.0,
            "final position was not at bottom after edge-preserving growth"
        )
    }

    func testShortContentDoesNotBottomAnchorBeforeScrollable() throws {
        try requireExplicitSpikeRun()

        let result = runSpike(
            label: "edge-short-content",
            initialRows: 1,
            appendedRows: 3,
            appendInterval: 0.03,
            pinMode: .edgeOnlyAfterScrollable
        )

        result.printSummary()

        XCTAssertTrue(result.completed, "spike harness did not finish streaming rows")
        XCTAssertLessThanOrEqual(
            result.maxAbsOffsetXDrift,
            0.5,
            "short-content edge spike introduced horizontal offset drift relative to its initial geometry"
        )
        XCTAssertFalse(
            result.everProgrammaticallyPinned,
            "short content should not call scrollTo(edge:) before the content becomes scrollable"
        )
    }

    private func requireExplicitSpikeRun() throws {
        let environmentEnabled = ProcessInfo.processInfo.environment["AA_RUN_EDGE_SPIKE"] == "1"
        let markerEnabled = FileManager.default.fileExists(atPath: Self.markerPath)
        guard environmentEnabled || markerEnabled else {
            throw XCTSkip(
                "Set AA_RUN_EDGE_SPIKE=1 in the test host environment or create \(Self.markerPath) to run the isolated edge-position spike."
            )
        }
    }

    fileprivate enum PinMode {
        case edgeOnceOnAppear
        case edgeOnlyAfterScrollable
    }

    private func runSpike(
        label: String,
        initialRows: Int,
        appendedRows: Int,
        appendInterval: TimeInterval,
        pinMode: PinMode
    ) -> EdgeSpikeResult {
        var metrics: [EdgeSpikeMetric] = []
        var completed = false
        var pinned = false

        let host = UIHostingController(
            rootView: EdgeSpikeHarness(
                initialRows: initialRows,
                appendedRows: appendedRows,
                appendInterval: appendInterval,
                pinMode: pinMode,
                onMetric: { metric in metrics.append(metric) },
                onPinned: { pinned = true },
                onComplete: { completed = true }
            )
        )
        host.view.backgroundColor = .white

        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: CGRect(origin: .zero, size: Self.screenSize))
        }
        window.frame = CGRect(origin: .zero, size: Self.screenSize)
        window.rootViewController = host
        window.makeKeyAndVisible()
        window.layoutIfNeeded()

        let deadline = Date().addingTimeInterval(8)
        while !completed && Date() < deadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
            window.layoutIfNeeded()
        }

        let settleDeadline = Date().addingTimeInterval(0.35)
        while Date() < settleDeadline {
            RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.01))
            window.layoutIfNeeded()
        }

        window.isHidden = true
        window.rootViewController = nil

        return EdgeSpikeResult(
            label: label,
            completed: completed,
            everProgrammaticallyPinned: pinned,
            metrics: metrics
        )
    }
}

private struct EdgeSpikeMetric: Equatable {
    var rowCount: Int
    var distanceToBottom: CGFloat
    var contentOffsetX: CGFloat
    var contentOffsetY: CGFloat
    var visibleHeight: CGFloat
    var contentHeight: CGFloat
    var isPositionedByUser: Bool
}

private struct EdgeSpikeResult {
    var label: String
    var completed: Bool
    var everProgrammaticallyPinned: Bool
    var metrics: [EdgeSpikeMetric]

    var finalDistance: CGFloat {
        metrics.last?.distanceToBottom ?? .greatestFiniteMagnitude
    }

    var maxDistanceAfterFirstAppend: CGFloat {
        guard let firstRowCount = metrics.first?.rowCount else { return 0 }
        return metrics
            .filter { $0.rowCount > firstRowCount }
            .map(\.distanceToBottom)
            .max() ?? 0
    }

    var maxAbsOffsetXDrift: CGFloat {
        guard let first = metrics.first?.contentOffsetX else { return 0 }
        return metrics.map { abs($0.contentOffsetX - first) }.max() ?? 0
    }

    var maxAbsOffsetYChange: CGFloat {
        guard let first = metrics.first?.contentOffsetY else { return 0 }
        return metrics.map { abs($0.contentOffsetY - first) }.max() ?? 0
    }

    func printSummary() {
        let sample = metrics.suffix(8).map {
            String(
                format: "{rows=%d dx=%.2f y=%.2f dist=%.2f content=%.2f visible=%.2f user=%d}",
                $0.rowCount,
                $0.contentOffsetX,
                $0.contentOffsetY,
                $0.distanceToBottom,
                $0.contentHeight,
                $0.visibleHeight,
                $0.isPositionedByUser ? 1 : 0
            )
        }.joined(separator: " ")
        print(
            String(
                format: "EDGE-SPIKE[%@]: completed=%d pinned=%d samples=%d finalDistance=%.2f maxDistanceAfterAppend=%.2f maxAbsXDrift=%.2f maxAbsYChange=%.2f",
                label,
                completed ? 1 : 0,
                everProgrammaticallyPinned ? 1 : 0,
                metrics.count,
                finalDistance,
                maxDistanceAfterFirstAppend,
                maxAbsOffsetXDrift,
                maxAbsOffsetYChange
            )
        )
        print("EDGE-SPIKE[\(label)] tail: \(sample)")
    }
}

private struct EdgeSpikeHarness: View {
    let initialRows: Int
    let appendedRows: Int
    let appendInterval: TimeInterval
    let pinMode: ChatScrollEdgeSpikeTests.PinMode
    let onMetric: (EdgeSpikeMetric) -> Void
    let onPinned: () -> Void
    let onComplete: () -> Void

    @State private var rows: [String]
    @State private var scrollPosition = ScrollPosition()
    @State private var didStart = false
    @State private var didPin = false
    @State private var latestGeometry: EdgeSpikeGeometry?

    init(
        initialRows: Int,
        appendedRows: Int,
        appendInterval: TimeInterval,
        pinMode: ChatScrollEdgeSpikeTests.PinMode,
        onMetric: @escaping (EdgeSpikeMetric) -> Void,
        onPinned: @escaping () -> Void,
        onComplete: @escaping () -> Void
    ) {
        self.initialRows = initialRows
        self.appendedRows = appendedRows
        self.appendInterval = appendInterval
        self.pinMode = pinMode
        self.onMetric = onMetric
        self.onPinned = onPinned
        self.onComplete = onComplete
        _rows = State(initialValue: (0..<initialRows).map(Self.rowText(index:)))
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                ForEach(rows.indices, id: \.self) { index in
                    Text(rows[index])
                        .font(.system(size: 17))
                        .lineSpacing(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 12)
                        .background(index.isMultiple(of: 2) ? Color(.systemGray6) : Color(.secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 20)
            .scrollTargetLayout()
        }
        .scrollPosition($scrollPosition)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        .defaultScrollAnchor(.top, for: .alignment)
        .scrollIndicators(.hidden)
        .onScrollGeometryChange(for: EdgeSpikeGeometry.self) { geometry in
            EdgeSpikeGeometry(
                distanceToBottom: max(0, geometry.contentSize.height - geometry.visibleRect.maxY),
                contentOffsetX: geometry.contentOffset.x,
                contentOffsetY: geometry.contentOffset.y,
                visibleHeight: max(1, geometry.visibleRect.height),
                contentHeight: geometry.contentSize.height
            )
        } action: { _, geometry in
            latestGeometry = geometry
            maybePinAfterScrollable(geometry)
            publish(geometry)
        }
        .task {
            guard !didStart else { return }
            didStart = true
            await start()
        }
    }

    private func start() async {
        if pinMode == .edgeOnceOnAppear {
            pinToBottomEdge()
        }

        try? await Task.sleep(nanoseconds: 160_000_000)
        for step in 0..<appendedRows {
            rows.append(Self.rowText(index: initialRows + step))
            if let latestGeometry {
                publish(latestGeometry)
            }
            let nanos = UInt64(appendInterval * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanos)
        }
        try? await Task.sleep(nanoseconds: 250_000_000)
        onComplete()
    }

    private func maybePinAfterScrollable(_ geometry: EdgeSpikeGeometry) {
        guard pinMode == .edgeOnlyAfterScrollable,
              !didPin,
              geometry.contentHeight > geometry.visibleHeight else { return }
        pinToBottomEdge()
    }

    private func pinToBottomEdge() {
        didPin = true
        onPinned()
        var transaction = Transaction()
        transaction.disablesAnimations = true
        transaction.animation = nil
        withTransaction(transaction) {
            scrollPosition.scrollTo(edge: .bottom)
        }
    }

    private func publish(_ geometry: EdgeSpikeGeometry) {
        onMetric(EdgeSpikeMetric(
            rowCount: rows.count,
            distanceToBottom: geometry.distanceToBottom,
            contentOffsetX: geometry.contentOffsetX,
            contentOffsetY: geometry.contentOffsetY,
            visibleHeight: geometry.visibleHeight,
            contentHeight: geometry.contentHeight,
            isPositionedByUser: scrollPosition.isPositionedByUser
        ))
    }

    private static func rowText(index: Int) -> String {
        let suffix = String(repeating: "内容增长验证 ", count: 2 + index % 3)
        return "\(index + 1). 边缘钉住 spike 行。\(suffix)这行用于触发真实 SwiftUI 测量和换行。"
    }
}

private struct EdgeSpikeGeometry: Equatable {
    var distanceToBottom: CGFloat
    var contentOffsetX: CGFloat
    var contentOffsetY: CGFloat
    var visibleHeight: CGFloat
    var contentHeight: CGFloat
}
