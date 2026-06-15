import SwiftUI

struct BoardView: View {
    @Environment(RouterPath.self) private var router
    @Environment(\.dismiss) private var dismiss

    private let leadStory = BoardStory(
        rank: "01",
        source: "财联社 电报 · NewsNow #1",
        title: "OpenAI 准备对 ChatGPT 启动史上最大改版，转型「超级应用」",
        summary: "计划整合编码工具与智能体，并新增高管看好的营收型产品线。"
    )

    private let comprehensiveStories: [BoardStory] = [
        .init(rank: "02", source: "36氪 · 8 分钟前", title: "苹果 WWDC26 前瞻：Siri 或接入第三方大模型，开放更多智能体接口"),
        .init(rank: "03", source: "酷安 · 21 分钟前", title: "华为 Mate80 系列预约破百万，Pro Max 全系标配卫星通话"),
        .init(rank: "04", source: "第一财经 · 33 分钟前", title: "国产 GPU 厂商集体冲刺 IPO，算力国产化进入兑现期")
    ]

    private let aiStories: [BoardStory] = [
        .init(rank: "01", source: "量子位 · 1 小时前", title: "DeepSeek 开源新一代 MoE 模型，推理成本再降 40%"),
        .init(rank: "02", source: "第一财经 · 1 小时前", title: "英伟达回应 H20 库存传闻：需求强劲，不存在减产"),
        .init(rank: "03", source: "爱范儿 · 2 小时前", title: "谷歌 Gemini 3 灰度开放 Agent 模式，可跨应用执行任务")
    ]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        liveStatus
                        comprehensiveSection
                        aiSection
                        footer
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("今日看板")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "gearshape", accessibilityLabel: "看板设置", size: 44, symbolSize: 17) {
                router.navigate(to: .boardSettings)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var liveStatus: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(AmberTheme.accentGreen)
                .frame(width: 7, height: 7)
                .shadow(color: AmberTheme.accentGreen.opacity(0.55), radius: 5)

            Text("刚刚更新 · 由 Amber 整理")
                .font(.system(size: 12, weight: .regular, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }

    private var comprehensiveSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "综合热点")
            AmberFormGroup {
                BoardLeadStoryRow(story: leadStory)
                BoardDivider(leading: 14)

                ForEach(Array(comprehensiveStories.enumerated()), id: \.element.id) { index, story in
                    BoardStoryRow(story: story)
                    if index < comprehensiveStories.count - 1 {
                        BoardDivider()
                    }
                }
            }
        }
    }

    private var aiSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "科技 · AI")
            AmberFormGroup {
                ForEach(Array(aiStories.enumerated()), id: \.element.id) { index, story in
                    BoardStoryRow(story: story)
                    if index < aiStories.count - 1 {
                        BoardDivider()
                    }
                }
            }
        }
    }

    private var footer: some View {
        Text("共 7 条")
            .font(.system(size: 11, weight: .regular, design: .monospaced))
            .foregroundStyle(AmberTheme.muted2)
            .frame(maxWidth: .infinity)
            .padding(.top, 8)
            .padding(.bottom, 4)
    }
}

private struct BoardStory: Identifiable {
    let id = UUID()
    let rank: String
    let source: String
    let title: String
    var summary: String?
}

private struct BoardLeadStoryRow: View {
    let story: BoardStory

    var body: some View {
        Button {
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 8) {
                    Text(story.rank)
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(AmberTheme.accent)

                    Text(story.source)
                        .font(.system(size: 11, weight: .regular, design: .monospaced))
                        .foregroundStyle(AmberTheme.muted)
                }

                Text(story.title)
                    .font(.system(size: 15.5, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)

                if let summary = story.summary {
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.foreground2)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 15)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

private struct BoardStoryRow: View {
    let story: BoardStory

    var body: some View {
        Button {
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Text(story.rank)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(AmberTheme.muted2)
                    .frame(width: 22, alignment: .leading)
                    .padding(.top, 2)

                VStack(alignment: .leading, spacing: 3) {
                    Text(story.title)
                        .font(.body)
                        .foregroundStyle(AmberTheme.foreground)
                        .lineSpacing(1)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(story.source)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
                    .padding(.top, 2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

private struct BoardDivider: View {
    var leading: CGFloat = 48

    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, leading)
    }
}

#Preview {
    NavigationStack {
        BoardView()
            .environment(RouterPath())
    }
}
