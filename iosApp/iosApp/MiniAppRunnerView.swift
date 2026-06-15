import SwiftUI

struct MiniAppRunnerView: View {
    @Environment(\.dismiss) private var dismiss

    let title: String

    @State private var isMenuPresented = false
    @State private var isRunning = false
    @State private var remainingSeconds = 25 * 60
    @State private var completedCount = 0

    private let totalSeconds = 25 * 60
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        if title == "番茄钟" {
                            pomodoroStage
                        } else {
                            emptyStage
                        }
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .confirmationDialog(title, isPresented: $isMenuPresented, titleVisibility: .visible) {
            Button("打开小应用") {
                isMenuPresented = false
            }
            Button("置顶") {
                isMenuPresented = false
            }
            Button("版本历史") {
                isMenuPresented = false
            }
            Button("导出 HTML") {
                isMenuPresented = false
            }
            Button("重命名") {
                isMenuPresented = false
            }
            Button("删除", role: .destructive) {
                isMenuPresented = false
            }
            Button("取消", role: .cancel) {
                isMenuPresented = false
            }
        }
        .onReceive(timer) { _ in
            tick()
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回小应用", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text(title)
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "ellipsis", accessibilityLabel: "更多操作", size: 44, symbolSize: 18) {
                isMenuPresented = true
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var pomodoroStage: some View {
        MiniAppStage {
            VStack(spacing: 22) {
                Text("专注 · 番茄钟")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AmberTheme.muted)

                ZStack {
                    Circle()
                        .stroke(AmberTheme.surface2, lineWidth: 12)
                        .frame(width: 210, height: 210)

                    Circle()
                        .trim(from: 0, to: ringProgress)
                        .stroke(AmberTheme.accent, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 210, height: 210)
                        .animation(.snappy(duration: 0.25), value: remainingSeconds)

                    VStack(spacing: 7) {
                        Text(timeText)
                            .font(.system(size: 46, weight: .semibold, design: .monospaced))
                            .foregroundStyle(AmberTheme.foreground)
                        Text(statusText)
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }

                HStack(spacing: 10) {
                    Button(primaryButtonTitle) {
                        toggleTimer()
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(AmberTheme.accent, in: Capsule())
                    .buttonStyle(.plain)

                    Button("重置") {
                        resetTimer()
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 46)
                    .background(AmberTheme.surface2, in: Capsule())
                    .buttonStyle(.plain)
                }

                Text("今日已完成 \(completedCount) 个番茄")
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
            }
            .padding(.horizontal, 26)
            .padding(.vertical, 30)
        }
    }

    private var emptyStage: some View {
        MiniAppStage {
            VStack(spacing: 12) {
                Text(String(title.prefix(1)))
                    .font(.system(size: 19, weight: .semibold, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 48, height: 48)
                    .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("这个小应用还没有生成内容。让 Amber 帮你做一遍，内容会保存在这里。")
                    .font(.system(size: 13.5))
                    .lineSpacing(4)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 260)
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 28)
            .padding(.vertical, 56)
        }
    }

    private var ringProgress: CGFloat {
        max(0.02, CGFloat(Double(remainingSeconds) / Double(totalSeconds)))
    }

    private var timeText: String {
        let minutes = remainingSeconds / 60
        let seconds = remainingSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    private var statusText: String {
        if remainingSeconds == 0 {
            return "完成 · 休息一下"
        }
        if isRunning {
            return "专注中"
        }
        if remainingSeconds < totalSeconds {
            return "已暂停"
        }
        return "准备开始"
    }

    private var primaryButtonTitle: String {
        if isRunning {
            return "暂停"
        }
        if remainingSeconds < totalSeconds && remainingSeconds > 0 {
            return "继续"
        }
        return "开始"
    }

    private func toggleTimer() {
        if remainingSeconds == 0 {
            resetTimer()
        }
        isRunning.toggle()
    }

    private func resetTimer() {
        isRunning = false
        remainingSeconds = totalSeconds
    }

    private func tick() {
        guard isRunning else { return }
        if remainingSeconds > 1 {
            remainingSeconds -= 1
        } else {
            remainingSeconds = 0
            isRunning = false
            completedCount += 1
        }
    }
}

private struct MiniAppStage<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .background(Color.white, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
    }
}

#Preview {
    NavigationStack {
        MiniAppRunnerView(title: "番茄钟")
    }
}
