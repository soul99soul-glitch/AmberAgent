import AVFoundation
import Observation

/// iOS system TTS player using AVSpeechSynthesizer.
///
/// HONESTY: only system TTS is supported (AVSpeechSynthesizer). Cloud TTS
/// providers (MiniMax/Gemini/etc.) require network API calls — not implemented.
/// The speak() method uses the iOS system voice to read text aloud, proving
/// the TTS playback chain works on iOS.
@MainActor
@Observable
final class IOSTTSPlayer: NSObject, AVSpeechSynthesizerDelegate {
    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    var isSpeaking = false
    var lastError: String?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Speak text using the iOS system voice.
    /// - Parameters:
    ///   - text: The text to speak.
    ///   - language: BCP-47 tag (e.g. "zh-CN"). Defaults to system locale.
    ///   - rate: Speed multiplier (0...1, where AVSpeechUtteranceDefaultSpeechRate ≈ 0.5).
    func speak(text: String, language: String? = nil, rate: Float = AVSpeechUtteranceDefaultSpeechRate) {
        guard !text.isEmpty else { return }
        stop()
        let utterance = AVSpeechUtterance(string: text)
        let lang = language ?? Locale.current.identifier
        if let voice = AVSpeechSynthesisVoice(identifier: lang) {
            utterance.voice = voice
        }
        utterance.rate = rate
        isSpeaking = true
        lastError = nil
        synthesizer.speak(utterance)
    }

    /// Stop any current speech.
    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        isSpeaking = false
    }

    // MARK: - AVSpeechSynthesizerDelegate

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = false }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.isSpeaking = false }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        // isSpeaking already set in speak()
    }
}
