import SwiftUI
@preconcurrency import Shared

/// Identifiable wrapper so a tapped tool can drive `.sheet(item:)`.
struct ToolDetailTarget: Identifiable {
    let id = UUID()
    let tool: UIMessagePart.Tool
}

/// Detail sheet shown when a tool / subagent capsule is tapped. Mirrors the
/// Android `ToolCallPreviewSheet` / `SubAgentRunSheet`: for a normal tool it
/// shows the call arguments + rendered output; for a subagent it shows the
/// task objective, status, and the generated report. Live token streaming for a
/// still-running subagent is layered on top via `SubAgentLiveModel` (only used
/// when a live flow exists; otherwise this falls back to the stored output).
struct ChatToolDetailSheet: View {
    let tool: UIMessagePart.Tool
    /// Optional live model for a running subagent (nil for normal tools or when
    /// no live flow is available — then the stored `tool.output` is shown).
    var live: SubAgentLiveModel? = nil

    @Environment(\.dismiss) private var dismiss

    private var isSubAgent: Bool { tool.toolName.contains("subagent_dispatch") }
    private var executed: Bool { !tool.output.isEmpty }

    private var storedOutputText: String {
        tool.output.compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var outputImages: [UIMessagePart.Image] {
        tool.output.compactMap { $0 as? UIMessagePart.Image }
    }

    /// Subagent body text: prefer live stream, fall back to the stored report.
    private var subAgentText: String {
        if let liveText = live?.text, !liveText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return liveText
        }
        return storedOutputText
    }

    private var isRunning: Bool {
        if let live { return live.isRunning }
        return !executed
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if isSubAgent { subAgentSections } else { toolSections }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .background(AmberTheme.background)
            .navigationTitle(isSubAgent ? "子智能体" : friendlyName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task { await live?.start() }
        .onDisappear { live?.stop() }
    }

    // MARK: - Normal tool

    @ViewBuilder private var toolSections: some View {
        section("工具") {
            Text(tool.toolName.isEmpty ? friendlyName : tool.toolName)
                .font(.system(.footnote, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .textSelection(.enabled)
        }
        if let pretty = Self.prettyJSON(tool.input) {
            section("参数") { codeBlock(pretty) }
        }
        section("结果") {
            if !executed {
                statusLine("尚未执行或无返回")
            } else {
                if !storedOutputText.isEmpty { codeBlock(storedOutputText) }
                ForEach(Array(outputImages.enumerated()), id: \.offset) { _, img in
                    AsyncImage(url: URL(string: img.url)) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        ProgressView()
                    }
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                if storedOutputText.isEmpty && outputImages.isEmpty {
                    statusLine("(无文本结果)")
                }
            }
        }
    }

    // MARK: - Subagent

    @ViewBuilder private var subAgentSections: some View {
        if let objective = Self.subAgentObjective(from: tool.input) {
            section("任务目标") {
                Text(objective)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.foreground)
                    .textSelection(.enabled)
            }
        }
        section("状态") {
            HStack(spacing: 6) {
                if isRunning {
                    ProgressView().controlSize(.mini).tint(AmberTheme.accent)
                    Text("正在工作").foregroundStyle(AmberTheme.accent)
                } else {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(AmberTheme.accentGreen)
                    Text("已完成").foregroundStyle(AmberTheme.accentGreen)
                }
            }
            .font(.footnote.weight(.medium))
        }
        section("生成内容") {
            let text = subAgentText
            if text.isEmpty {
                statusLine(isRunning ? "等待输出…" : "(无输出)")
            } else {
                Text(Self.markdownAttributed(text))
                    .font(.callout)
                    .foregroundStyle(AmberTheme.foreground)
                    .textSelection(.enabled)
            }
        }
    }

    // MARK: - Building blocks

    private var friendlyName: String {
        if isSubAgent { return "子智能体" }
        if tool.toolName.isEmpty { return "工具调用" }
        return tool.toolName
    }

    @ViewBuilder private func section<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
                .textCase(.uppercase)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func codeBlock(_ text: String) -> some View {
        Text(text)
            .font(.system(.footnote, design: .monospaced))
            .foregroundStyle(AmberTheme.foreground2)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func statusLine(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(AmberTheme.muted)
    }

    // MARK: - Parsing helpers

    static func prettyJSON(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let data = trimmed.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let pretty = try? JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]),
              let str = String(data: pretty, encoding: .utf8)
        else { return trimmed }
        return str
    }

    static func subAgentObjective(from input: String) -> String? {
        guard let data = input.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        // iOS subagent_dispatch uses "objective"; the KMP path nests under task.objective.
        if let objective = obj["objective"] as? String, !objective.isEmpty { return objective }
        if let task = obj["task"] as? [String: Any], let objective = task["objective"] as? String, !objective.isEmpty {
            return objective
        }
        return nil
    }

    static func markdownAttributed(_ text: String) -> AttributedString {
        (try? AttributedString(
            markdown: text,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(text)
    }
}
