import SwiftUI
import Shared

/// Editor for mode injections (PromptInjection.ModeInjection) and lorebooks —
/// Android extensions/PromptPage parity. Previously iOS mirrored these in the
/// Settings snapshot but had no UI to create/edit/delete them.
struct PromptInjectionEditorView: View {
    @Bindable var sharedSettings: IOSSharedSettingsStore
    @Environment(\.dismiss) private var dismiss

    @State private var selectedInjection: PromptInjection.ModeInjection?
    @State private var selectedLorebook: Lorebook?
    @State private var isAddingInjection = false
    @State private var isAddingLorebook = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    modeInjectionSection
                    lorebookSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: Binding(
            get: { selectedInjection != nil },
            set: { if !$0 { selectedInjection = nil } }
        )) {
            if let selectedInjection {
                ModeInjectionEditSheet(
                    sharedSettings: sharedSettings,
                    injection: selectedInjection,
                    onDismiss: { self.selectedInjection = nil }
                )
            }
        }
        .sheet(isPresented: $isAddingInjection) {
            ModeInjectionEditSheet(
                sharedSettings: sharedSettings,
                injection: nil,
                onDismiss: { isAddingInjection = false }
            )
        }
        .sheet(isPresented: Binding(
            get: { selectedLorebook != nil },
            set: { if !$0 { selectedLorebook = nil } }
        )) {
            if let selectedLorebook {
                LorebookEditSheet(
                    sharedSettings: sharedSettings,
                    lorebook: selectedLorebook,
                    onDismiss: { self.selectedLorebook = nil }
                )
            }
        }
        .sheet(isPresented: $isAddingLorebook) {
            LorebookEditSheet(
                sharedSettings: sharedSettings,
                lorebook: nil,
                onDismiss: { isAddingLorebook = false }
            )
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }
            Spacer()
            Text("注入与 Lorebook")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)
            Spacer()
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 14)
    }

    private var modeInjectionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模式注入")
            AmberFormGroup {
                if sharedSettings.modeInjections.isEmpty {
                    Text("还没有模式注入。模式注入会按开关状态把固定内容插入对话上下文。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(sharedSettings.modeInjections.enumerated()), id: \.element.id) { index, injection in
                        Button { selectedInjection = injection } label: {
                            injectionRow(injection)
                        }
                        .buttonStyle(.plain)
                        if index < sharedSettings.modeInjections.count - 1 {
                            Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                        }
                    }
                }
            }
            Button {
                isAddingInjection = true
            } label: {
                Label("新建模式注入", systemImage: "plus.circle")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.plain)
        }
    }

    private func injectionRow(_ injection: PromptInjection.ModeInjection) -> some View {
        HStack(spacing: 12) {
            Image(systemName: injection.enabled ? "togglepower" : "power")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(injection.enabled ? AmberTheme.accentGreen : AmberTheme.muted2)
                .frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(injection.name.isEmpty ? "(未命名)" : injection.name)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(injection.content.isEmpty ? "无内容" : String(injection.content.prefix(60)))
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private var lorebookSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Lorebook（世界书）")
            AmberFormGroup {
                if sharedSettings.lorebooks.isEmpty {
                    Text("还没有 Lorebook。Lorebook 通过关键词触发，把设定插入对话上下文。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(sharedSettings.lorebooks.enumerated()), id: \.element.id) { index, lorebook in
                        Button { selectedLorebook = lorebook } label: {
                            lorebookRow(lorebook)
                        }
                        .buttonStyle(.plain)
                        if index < sharedSettings.lorebooks.count - 1 {
                            Divider().overlay(AmberTheme.borderSoft).padding(.leading, 58)
                        }
                    }
                }
            }
            Button {
                isAddingLorebook = true
            } label: {
                Label("新建 Lorebook", systemImage: "plus.circle")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.plain)
        }
    }

    private func lorebookRow(_ lorebook: Lorebook) -> some View {
        HStack(spacing: 12) {
            Image(systemName: lorebook.enabled ? "book.closed" : "book")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(lorebook.enabled ? AmberTheme.accentAmber : AmberTheme.muted2)
                .frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(lorebook.name.isEmpty ? "(未命名)" : lorebook.name)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(lorebook.description.isEmpty ? "无描述" : lorebook.description)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

// MARK: - Mode injection edit sheet

private struct ModeInjectionEditSheet: View {
    @Bindable var sharedSettings: IOSSharedSettingsStore
    let injection: PromptInjection.ModeInjection?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var content = ""
    @State private var enabled = true
    @State private var priority = 0
    @State private var position = "after_system_prompt"
    @State private var role = "user"

    private let positions = ["after_system_prompt", "before_system_prompt", "top_of_chat", "bottom_of_chat", "at_depth"]
    private let roles = ["user", "assistant", "system"]

    var body: some View {
        NavigationStack {
            Form {
                Section("基本信息") {
                    TextField("名称", text: $name)
                    Toggle("启用", isOn: $enabled)
                    Stepper("优先级 \(priority)", value: $priority, in: -10...10)
                    Picker("位置", selection: $position) {
                        ForEach(positions, id: \.self) { Text($0).tag($0) }
                    }
                    Picker("角色", selection: $role) {
                        ForEach(roles, id: \.self) { Text($0).tag($0) }
                    }
                }
                Section("注入内容") {
                    TextEditor(text: $content)
                        .frame(minHeight: 120)
                }
                if injection != nil {
                    Section {
                        Button(role: .destructive) {
                            if let id = injection?.id {
                                sharedSettings.deleteModeInjection(id: id.description)
                            }
                            onDismiss()
                        } label: {
                            Text("删除此注入")
                        }
                    }
                }
            }
            .navigationTitle(injection == nil ? "新建模式注入" : "编辑注入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { onDismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let id = injection?.id.description ?? KotlinUuid.companion.random().description
                        sharedSettings.upsertModeInjection(
                            id: id, name: name, content: content, enabled: enabled,
                            priority: priority, position: position, role: role
                        )
                        onDismiss()
                    }
                }
            }
            .onAppear {
                if let injection {
                    name = injection.name
                    content = injection.content
                    enabled = injection.enabled
                    priority = Int(injection.priority)
                    position = injection.position.name.lowercased()
                    role = injection.role.name.lowercased()
                }
            }
        }
        .presentationDetents([.large])
    }
}

// MARK: - Lorebook edit sheet

private struct LorebookEditSheet: View {
    @Bindable var sharedSettings: IOSSharedSettingsStore
    let lorebook: Lorebook?
    let onDismiss: () -> Void

    @State private var name = ""
    @State private var descriptionText = ""
    @State private var enabled = true

    var body: some View {
        NavigationStack {
            Form {
                Section("基本信息") {
                    TextField("名称", text: $name)
                    TextField("描述", text: $descriptionText, axis: .vertical)
                        .lineLimit(2...5)
                    Toggle("启用", isOn: $enabled)
                }
                if lorebook != nil {
                    Section {
                        Button(role: .destructive) {
                            if let id = lorebook?.id {
                                sharedSettings.deleteLorebook(id: id.description)
                            }
                            onDismiss()
                        } label: {
                            Text("删除此 Lorebook")
                        }
                    }
                }
            }
            .navigationTitle(lorebook == nil ? "新建 Lorebook" : "编辑 Lorebook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { onDismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let id = lorebook?.id.description ?? KotlinUuid.companion.random().description
                        sharedSettings.upsertLorebook(id: id, name: name, description: descriptionText, enabled: enabled)
                        onDismiss()
                    }
                }
            }
            .onAppear {
                if let lorebook {
                    name = lorebook.name
                    descriptionText = lorebook.description
                    enabled = lorebook.enabled
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
