import SwiftUI

struct WatchTaskRootView: View {
    @ObservedObject var model: WatchTaskViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                header
                if let decision = model.snapshot.decision {
                    decisionCard(decision)
                } else {
                    statusCard
                }
                if let summary = model.snapshot.summary, !summary.isEmpty {
                    summaryCard(summary)
                }
                if model.isDictating || model.snapshot.decision?.allowsVoice == true {
                    voiceCard
                }
                actions
                if let statusMessage = model.statusMessage {
                    Text(statusMessage)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
        }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(model.snapshot.headline)
                    .font(.headline)
                    .lineLimit(2)
                Text(model.snapshot.detail ?? phaseTitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 4)
            if let metric = model.snapshot.metricText {
                Text(metric)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.orange)
            }
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(phaseTitle, systemImage: phaseSymbol)
                .font(.subheadline.weight(.semibold))
            if model.snapshot.isActive {
                Text(model.snapshot.stage.replacingOccurrences(of: "_", with: " "))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                Text("没有进行中的任务")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func decisionCard(_ decision: WatchDecision) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(decision.title)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(riskTitle(decision.riskLevel))
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(riskColor(decision.riskLevel))
            }
            Text(decision.body)
                .font(.caption)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

            if decision.type == .approval {
                HStack(spacing: 8) {
                    Button("拒绝") { model.deny() }
                        .buttonStyle(.bordered)
                        .disabled(model.isSending)
                    Button("允许") { model.approve() }
                        .buttonStyle(.borderedProminent)
                        .tint(.orange)
                        .disabled(model.isSending)
                }
            }

            ForEach(decision.options.filter { option in
                switch decision.type {
                case .approval:
                    option.style == .openOnPhone
                case .askUser, .voiceReply:
                    // skip reuses .deny style without inventing a new option style.
                    option.style == .choice || option.style == .deny || option.style == .dictate || option.style == .openOnPhone
                }
            }) { option in
                Button(option.title) {
                    model.choose(optionId: option.id)
                }
                .buttonStyle(.bordered)
                .disabled(model.isSending)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func summaryCard(_ summary: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("总结")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(summary)
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var voiceCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("语音 / 短回答")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            TextField("对 iPhone 说下一步…", text: $model.draftAnswer, axis: .vertical)
                .lineLimit(2...4)
                .textFieldStyle(.plain)
            HStack {
                Button("提交") { model.submitDraftAnswer() }
                    .buttonStyle(.borderedProminent)
                    .tint(.orange)
                    .disabled(model.isSending || model.draftAnswer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("收起") {
                    model.isDictating = false
                }
                .buttonStyle(.bordered)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var actions: some View {
        VStack(spacing: 8) {
            if model.snapshot.actions.contains(.openOnPhone) {
                Button("在 iPhone 打开") { model.openOnPhone() }
                    .buttonStyle(.bordered)
                    .disabled(model.isSending)
            }
            if model.snapshot.actions.contains(.cancel) {
                Button("取消任务", role: .destructive) { model.cancel() }
                    .disabled(model.isSending)
            }
            Button("刷新") { model.refresh() }
                .buttonStyle(.bordered)
                .disabled(model.isSending)
        }
        .frame(maxWidth: .infinity)
    }

    private var phaseTitle: String {
        switch model.snapshot.phase {
        case "running": "进行中"
        case "waitingForUser": "等待你"
        case "reconnecting": "重连中"
        case "completed": "已完成"
        case "failed": "需要处理"
        case "cancelled": "已取消"
        case "stale": "状态过期"
        default: "待命"
        }
    }

    private var phaseSymbol: String {
        switch model.snapshot.phase {
        case "running": "waveform"
        case "waitingForUser": "hand.raised.fill"
        case "completed": "checkmark.circle.fill"
        case "failed": "exclamationmark.triangle.fill"
        case "cancelled": "stop.circle"
        default: "sparkles"
        }
    }

    private func riskTitle(_ risk: WatchRiskLevel) -> String {
        switch risk {
        case .low: "低风险"
        case .medium: "需确认"
        case .high: "高风险"
        }
    }

    private func riskColor(_ risk: WatchRiskLevel) -> Color {
        switch risk {
        case .low: .green
        case .medium: .orange
        case .high: .red
        }
    }
}
