import SwiftUI

struct ImageGenerationView: View {
    @Bindable var settingsStore: SettingsStore

    @Environment(\.dismiss) private var dismiss
    @State private var imageSettings = IOSImageGenerationSettingsStore.shared
    @State private var historyStore = IOSImageGenerationHistoryStore.shared
    @State private var prompt = ""
    @State private var isGenerating = false
    @State private var errorMessage: String?
    @State private var latestRecordId: String?

    private var configurationIssue: IOSImageGenerationError? {
        imageSettings.configurationIssue(settingsStore: settingsStore)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                ScrollView {
                    VStack(spacing: 0) {
                        configurationSection
                        promptSection
                        generateSection
                        historySection
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

            VStack(spacing: 2) {
                Text("图片生成")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("\(historyStore.records.count) 组历史")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            AmberGlassCircleButton(systemImage: "photo.stack", accessibilityLabel: "历史图库", size: 44, symbolSize: 18) {
                latestRecordId = historyStore.records.first?.id
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var configurationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "模型与参数")
            AmberFormGroup {
                ImageGenerationTextFieldRow(
                    title: "模型",
                    placeholder: "gpt-image-1",
                    text: $imageSettings.model,
                    monospace: true
                )
                ImageGenerationDivider()
                ImageGenerationMenuRow(title: "比例", value: imageSettings.aspectRatio.title) {
                    ForEach(IOSImageAspectRatio.allCases) { ratio in
                        Button(ratio.title) {
                            imageSettings.aspectRatio = ratio
                        }
                    }
                }
                ImageGenerationDivider()
                Stepper(value: $imageSettings.count, in: 1...4) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("数量")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("\(imageSettings.count) 张")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 58)
                ImageGenerationDivider()
                ImageGenerationTextFieldRow(
                    title: "风格",
                    placeholder: "photo, watercolor, poster...",
                    text: $imageSettings.style
                )

                if let configurationIssue {
                    ImageGenerationDivider()
                    ImageGenerationStatusMessage(text: configurationIssue.localizedDescription, tint: AmberTheme.accentRed)
                }
            }
        }
    }

    private var promptSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "提示词")
            ZStack(alignment: .topLeading) {
                TextEditor(text: $prompt)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground2)
                    .lineSpacing(3)
                    .scrollContentBackground(.hidden)
                    .padding(12)
                    .frame(minHeight: 154)

                if prompt.isEmpty {
                    Text("描述主体、风格、构图、光线、情绪")
                        .font(.body)
                        .foregroundStyle(AmberTheme.muted2)
                        .padding(.horizontal, 17)
                        .padding(.vertical, 20)
                        .allowsHitTesting(false)
                }
            }
            .background(AmberTheme.surface)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                    .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
            }
            .padding(.horizontal, 16)
        }
    }

    private var generateSection: some View {
        AmberFormGroup {
            Button {
                Task { await generate() }
            } label: {
                Label(isGenerating ? "生成中" : "生成图片", systemImage: isGenerating ? "clock" : "sparkles")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(canGenerate ? AmberTheme.accent : AmberTheme.muted2)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!canGenerate)

            if let errorMessage {
                ImageGenerationDivider()
                ImageGenerationStatusMessage(text: errorMessage, tint: AmberTheme.accentRed)
            } else if let latestRecord {
                ImageGenerationDivider()
                ImageGenerationRecordView(record: latestRecord, isLatest: true) {
                    historyStore.delete(id: latestRecord.id)
                    latestRecordId = nil
                }
            }
        }
        .padding(.top, 20)
    }

    private var historySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "历史图库")
            if historyStore.records.isEmpty {
                AmberFormGroup {
                    ContentUnavailableView("暂无图片", systemImage: "photo.on.rectangle", description: Text("生成成功后会保存在本机历史图库。"))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 28)
                }
            } else {
                AmberFormGroup {
                    ForEach(Array(historyStore.records.enumerated()), id: \.element.id) { index, record in
                        ImageGenerationRecordView(record: record, isLatest: record.id == latestRecordId) {
                            historyStore.delete(id: record.id)
                            if latestRecordId == record.id { latestRecordId = nil }
                        }
                        if index < historyStore.records.count - 1 {
                            ImageGenerationDivider()
                        }
                    }
                }
            }
        }
    }

    private var latestRecord: IOSImageGenerationRecord? {
        guard let latestRecordId else { return nil }
        return historyStore.records.first { $0.id == latestRecordId }
    }

    private var canGenerate: Bool {
        configurationIssue == nil &&
            !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !isGenerating
    }

    private func generate() async {
        guard canGenerate else { return }
        isGenerating = true
        errorMessage = nil
        latestRecordId = nil
        defer { isGenerating = false }
        do {
            let record = try await IOSImageGenerationRepository.shared.generate(
                request: imageSettings.request(prompt: prompt),
                settingsStore: settingsStore
            )
            latestRecordId = record.id
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct ImageGenerationRecordView: View {
    let record: IOSImageGenerationRecord
    let isLatest: Bool
    let onDelete: () -> Void

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 120), spacing: 8)]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(record.prompt)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                        .lineLimit(2)
                    Text("\(record.model) · \(record.aspectRatio.title) · \(record.files.count) 张")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isLatest {
                    Text("最新")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(AmberTheme.accentGreen)
                        .padding(.horizontal, 7)
                        .frame(height: 22)
                        .background(AmberTheme.accentGreen.opacity(0.12), in: Capsule())
                }

                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentRed)
                }
                .buttonStyle(.plain)
            }

            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(record.files) { file in
                    ImageGenerationFileTile(file: file)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct ImageGenerationFileTile: View {
    let file: IOSGeneratedImageFile

    var body: some View {
        let url = URL(fileURLWithPath: file.path)
        VStack(spacing: 6) {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFill()
                case .failure:
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundStyle(AmberTheme.accentRed)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                case .empty:
                    ProgressView()
                        .tint(AmberTheme.accent)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                @unknown default:
                    EmptyView()
                }
            }
            .frame(height: 126)
            .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))

            ShareLink(item: url) {
                Label("分享", systemImage: "square.and.arrow.up")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 28)
                    .background(AmberTheme.accentTint, in: Capsule())
            }
        }
    }
}

private struct ImageGenerationTextFieldRow: View {
    let title: String
    let placeholder: String
    @Binding var text: String
    var monospace = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
            TextField(placeholder, text: $text)
                .font(monospace ? .system(size: 14, weight: .regular, design: .monospaced) : .body)
                .foregroundStyle(AmberTheme.foreground)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
    }
}

private struct ImageGenerationMenuRow<MenuContent: View>: View {
    let title: String
    let value: String
    @ViewBuilder let menuContent: MenuContent

    var body: some View {
        Menu {
            menuContent
        } label: {
            HStack(spacing: 10) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 58)
            .padding(.horizontal, 15)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
    }
}

private struct ImageGenerationStatusMessage: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(tint)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
    }
}

private struct ImageGenerationDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}
