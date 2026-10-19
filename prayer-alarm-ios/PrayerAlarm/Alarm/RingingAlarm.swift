import AudioToolbox
import AVFoundation
import Foundation

/// What is ringing: carried in a notification's userInfo and shown on the ringing screen.
struct RingingAlarm {
    var label: String
    var isPrayer: Bool
    var sound: SoundChoice?
    /// Set for a ring of an alarm group, so "I'm awake" can cancel the rest of its session.
    var groupId: Int?
    var sessionEnd: Date?
    var remaining: Int = 0

    static let ringKinds: Set<String> = ["group", "prayer", "snooze"]

    init(label: String, isPrayer: Bool, sound: SoundChoice?, groupId: Int? = nil, sessionEnd: Date? = nil, remaining: Int = 0) {
        self.label = label
        self.isPrayer = isPrayer
        self.sound = sound
        self.groupId = groupId
        self.sessionEnd = sessionEnd
        self.remaining = remaining
    }

    init?(userInfo: [AnyHashable: Any]) {
        guard let kind = userInfo["kind"] as? String, RingingAlarm.ringKinds.contains(kind) else { return nil }
        label = userInfo["label"] as? String ?? ""
        isPrayer = userInfo["isPrayer"] as? Bool ?? false
        if let data = userInfo["sound"] as? Data {
            sound = try? JSONDecoder().decode(SoundChoice.self, from: data)
        }
        groupId = userInfo["groupId"] as? Int
        sessionEnd = (userInfo["sessionEnd"] as? Double).map(Date.init(timeIntervalSince1970:))
        remaining = userInfo["remaining"] as? Int ?? 0
    }

    func userInfo(kind: String) -> [AnyHashable: Any] {
        var info: [AnyHashable: Any] = ["kind": kind, "label": label, "isPrayer": isPrayer, "remaining": remaining]
        if let sound = sound, let data = try? JSONEncoder().encode(sound) { info["sound"] = data }
        if let id = groupId { info["groupId"] = id }
        if let end = sessionEnd { info["sessionEnd"] = end.timeIntervalSince1970 }
        return info
    }
}

/// Plays the alarm on the ringing screen while the app is open, with optional rising volume and vibration.
final class RingPlayer {
    private var player: AVAudioPlayer?
    private var vibrateTimer: Timer?

    func start(sound: SoundChoice, loop: Bool, ascendingSeconds: Int, vibrate: Bool) {
        let session = AVAudioSession.sharedInstance()
        // .playback keeps ringing even with the ring/silent switch set to silent.
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setActive(true)
        if let url = SoundLibrary.playableURL(sound), let player = try? AVAudioPlayer(contentsOf: url) {
            player.numberOfLoops = loop ? -1 : 0
            if ascendingSeconds > 0 {
                player.volume = 0.05
                player.play()
                player.setVolume(1, fadeDuration: TimeInterval(ascendingSeconds))
            } else {
                player.volume = 1
                player.play()
            }
            self.player = player
        }
        if vibrate {
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            vibrateTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            }
        }
    }

    func stop() {
        player?.stop()
        player = nil
        vibrateTimer?.invalidate()
        vibrateTimer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
