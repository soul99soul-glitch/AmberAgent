import Foundation
@preconcurrency import Shared

/// Host-side reference-image binding for chat `generate_image`.
///
/// Codex image2 already accepts `input_image` when `source_image_url` is set.
/// Models cannot practically copy multi-megabyte `data:` URLs into tool args, so
/// when they set `use_attached_image` (or aliases), the host injects the latest
/// user-attached image URL from the conversation messages.
enum ChatImageGenerationReference {
    enum EnrichmentError: Equatable, Error {
        case attachedImageRequestedButMissing
    }

    /// System guidance injected when `generate_image` is available.
    static let routingGuidancePrompt = """
        Image-generation routing guidance for AmberAgent iOS:
        - When the user asks for a photographic, painted, illustrated, poster, wallpaper, concept-art, or character-art image, call `generate_image` exactly once instead of answering with SVG/HTML.
        - Preserve the user's subject, style, aspect-ratio cues, and language. Prefer a detailed prompt with subject, composition, lighting, mood, and visual style.
        - If the user attached an image and wants a style transfer, remake, edit, or "based on this image" result, call `generate_image` with `use_attached_image=true` so the host pads that attachment into Codex image2. Do not rely on a text-only redraw when the attached pixels are the reference.
        - If an earlier generated chat image URL is already known, you may pass it as `source_image_url` instead.
        - If the request references named fiction/IP, avoid brittle prompt wording like "fan art of <character> from <franchise>". Use an original inspired depiction that keeps the user's requested vibe and recognizable high-level visual cues without asking for an exact copyrighted character, logo, actor, or celebrity likeness.
        - If `generate_image` fails, report the failure honestly and ask whether to retry or adjust the prompt. Do not substitute an SVG/code sketch as if image generation succeeded unless the user explicitly asks for a fallback sketch.
        """

    static func wantsAttachedImage(_ input: String) -> Bool {
        guard let object = jsonObject(input) else { return false }
        if boolValue(object["use_attached_image"]) { return true }
        if boolValue(object["use_reference_image"]) { return true }
        if let source = stringValue(object["source_image_url"])?.lowercased() {
            return source == "attached" || source == "latest" || source == "latest_user_image"
        }
        return false
    }

    static func latestUserAttachedImageURL(in messages: [UIMessage]) -> String? {
        for message in messages.reversed() where message.role == MessageRole.user {
            let urls = message.parts.compactMap { part -> String? in
                guard let image = part as? UIMessagePart.Image else { return nil }
                let url = image.url.trimmingCharacters(in: .whitespacesAndNewlines)
                return url.isEmpty ? nil : url
            }
            if let url = urls.last {
                return url
            }
        }
        return nil
    }

    static func enrichToolInput(
        _ input: String,
        messages: [UIMessage]
    ) -> Result<String, EnrichmentError> {
        guard let object = jsonObject(input) else {
            // Non-JSON prompt-only calls never request attached-image binding.
            return .success(input)
        }

        let existingSource = stringValue(object["source_image_url"])
        let hasConcreteSource: Bool = {
            guard let existingSource else { return false }
            let lower = existingSource.lowercased()
            return !(lower == "attached" || lower == "latest" || lower == "latest_user_image")
        }()

        if hasConcreteSource {
            return .success(input)
        }

        guard wantsAttachedImage(input) else {
            return .success(input)
        }

        guard let attached = latestUserAttachedImageURL(in: messages) else {
            return .failure(.attachedImageRequestedButMissing)
        }

        var enriched = object
        enriched["source_image_url"] = attached
        guard JSONSerialization.isValidJSONObject(enriched),
              let data = try? JSONSerialization.data(
                withJSONObject: enriched,
                options: [.sortedKeys, .withoutEscapingSlashes]
              ),
              let text = String(data: data, encoding: .utf8) else {
            return .failure(.attachedImageRequestedButMissing)
        }
        return .success(text)
    }

    private static func jsonObject(_ input: String) -> [String: Any]? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return object
    }

    private static func stringValue(_ value: Any?) -> String? {
        switch value {
        case let text as String:
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        default:
            return nil
        }
    }

    private static func boolValue(_ value: Any?) -> Bool {
        switch value {
        case let flag as Bool:
            return flag
        case let number as NSNumber:
            return number.boolValue
        case let text as String:
            switch text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "1", "yes":
                return true
            default:
                return false
            }
        default:
            return false
        }
    }
}
