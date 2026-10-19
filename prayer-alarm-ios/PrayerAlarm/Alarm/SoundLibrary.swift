import AVFoundation
import UserNotifications

/**
 Notification sounds on iOS must be in the app bundle or in Library/Sounds, be Linear PCM (wav, caf, aiff),
 and are cut off after 30 seconds. Imported files are converted and trimmed to fit.
 */
enum SoundLibrary {
    static let fallbackFile = "alarm.wav"
    private static let maxSeconds = 29.5

    static var soundsDirectory: URL {
        FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Sounds")
    }

    static func notificationSound(_ choice: SoundChoice) -> UNNotificationSound {
        switch choice {
        case .systemDefault:
            return .default
        case .builtIn, .custom:
            return UNNotificationSound(named: UNNotificationSoundName(fileName(choice)))
        }
    }

    /// The file to play on the ringing screen while the app is open.
    static func playableURL(_ choice: SoundChoice) -> URL? {
        let name = fileName(choice)
        if case .custom = choice, FileManager.default.fileExists(atPath: soundsDirectory.appendingPathComponent(name).path) {
            return soundsDirectory.appendingPathComponent(name)
        }
        return Bundle.main.url(forResource: name, withExtension: nil)
    }

    /// The file name to use, falling back to the built-in tone if the chosen file is missing.
    private static func fileName(_ choice: SoundChoice) -> String {
        switch choice {
        case .systemDefault:
            return fallbackFile
        case let .builtIn(file):
            return Bundle.main.url(forResource: file, withExtension: nil) != nil ? file : fallbackFile
        case let .custom(file, _):
            return FileManager.default.fileExists(atPath: soundsDirectory.appendingPathComponent(file).path) ? file : fallbackFile
        }
    }

    /// Converts any audio file AVFoundation can read into a ≤30 s Linear PCM .caf in Library/Sounds.
    static func importSound(from source: URL) throws -> SoundChoice {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }

        let input = try AVAudioFile(forReading: source)
        let format = input.processingFormat
        var remaining = min(input.length, AVAudioFramePosition(format.sampleRate * maxSeconds))

        try FileManager.default.createDirectory(at: soundsDirectory, withIntermediateDirectories: true)
        let name = "sound-\(UUID().uuidString).caf"
        let destination = soundsDirectory.appendingPathComponent(name)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        do {
            let output = try AVAudioFile(
                forWriting: destination, settings: settings,
                commonFormat: format.commonFormat, interleaved: format.isInterleaved
            )
            while remaining > 0 {
                let frames = AVAudioFrameCount(min(remaining, 32_768))
                guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { break }
                try input.read(into: buffer, frameCount: frames)
                if buffer.frameLength == 0 { break }
                try output.write(from: buffer)
                remaining -= AVAudioFramePosition(buffer.frameLength)
            }
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
        return .custom(file: name, name: source.deletingPathExtension().lastPathComponent)
    }
}
