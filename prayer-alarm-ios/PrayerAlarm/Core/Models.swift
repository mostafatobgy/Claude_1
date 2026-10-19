import Foundation

/// When an alarm group starts: a fixed clock time, or minutes before/after a prayer (moves with it every day).
enum Anchor: Codable, Equatable {
    case fixed(hour: Int, minute: Int)
    case afterPrayer(prayer: Prayer, offsetMinutes: Int)
}

enum Repeat: String, CaseIterable, Codable {
    case once, daily, workDays, offDays, custom
}

/// A sound for an alarm. Notification sounds on iOS must live in the app bundle or in Library/Sounds.
enum SoundChoice: Codable, Equatable {
    /// The standard iOS notification sound.
    case systemDefault
    /// A file shipped inside the app, e.g. "alarm.wav" or "adhan.caf".
    case builtIn(file: String)
    /// A file the user imported, converted and saved in Library/Sounds.
    case custom(file: String, name: String)
}

/**
 One or more alarms that ring [intervalMinutes] apart, starting at [anchor].
 A single alarm is just a group with [count] = 1.
 */
struct AlarmGroup: Codable, Equatable {
    var id: Int
    var label: String = ""
    var anchor: Anchor = .fixed(hour: 7, minute: 0)
    var count: Int = 1
    var intervalMinutes: Int = 5
    var `repeat`: Repeat = .once
    /// ISO weekdays, 1 = Monday … 7 = Sunday.
    var customDays: Set<Int> = []
    var enabled: Bool = true
    /// nil = the alarm sound from settings.
    var sound: SoundChoice?
    /// Alarms at or before this are skipped ("skip next" or "I'm awake, stop the rest").
    var skipUntil: Date = Date(timeIntervalSince1970: 0)
    /// When the group was last saved or switched on. A one-time group rings in its first session after this.
    var armedAt: Date = Date()

    init(id: Int) { self.id = id }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        anchor = try c.decodeIfPresent(Anchor.self, forKey: .anchor) ?? .fixed(hour: 7, minute: 0)
        count = max(1, try c.decodeIfPresent(Int.self, forKey: .count) ?? 1)
        intervalMinutes = max(1, try c.decodeIfPresent(Int.self, forKey: .intervalMinutes) ?? 5)
        self.repeat = try c.decodeIfPresent(Repeat.self, forKey: .repeat) ?? .once
        customDays = try c.decodeIfPresent(Set<Int>.self, forKey: .customDays) ?? []
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        sound = try c.decodeIfPresent(SoundChoice.self, forKey: .sound)
        skipUntil = try c.decodeIfPresent(Date.self, forKey: .skipUntil) ?? Date(timeIntervalSince1970: 0)
        armedAt = try c.decodeIfPresent(Date.self, forKey: .armedAt) ?? Date()
    }

    func runsOn(_ day: LocalDay, workDays: Set<Int>) -> Bool {
        switch self.repeat {
        case .once, .daily: return true
        case .workDays: return workDays.contains(day.isoWeekday)
        case .offDays: return !workDays.contains(day.isoWeekday)
        case .custom: return customDays.contains(day.isoWeekday)
        }
    }

    /// All ring times of the group for the session belonging to [day] (empty if it doesn't run that day).
    func session(_ day: LocalDay, timeZone: TimeZone, ctx: ScheduleContext) -> [Date] {
        guard runsOn(day, workDays: ctx.workDays) else { return [] }
        let start: Date
        switch anchor {
        case let .fixed(hour, minute):
            start = day.at(hour: hour, minute: minute, timeZone: timeZone)
        case let .afterPrayer(prayer, offset):
            guard let time = ctx.prayers.times(for: day, timeZone: timeZone)[prayer] else { return [] }
            start = time.addingTimeInterval(Double(offset) * 60)
        }
        return (0..<count).map { start.addingTimeInterval(Double($0 * intervalMinutes) * 60) }
    }

    /// For a one-time group: the session it rings in, i.e. the first one with a ring after [armedAt].
    func onceSession(timeZone: TimeZone, ctx: ScheduleContext) -> [Date]? {
        let first = LocalDay(armedAt, timeZone: timeZone)
        for d in -1...8 {
            let times = session(first.plusDays(d), timeZone: timeZone, ctx: ctx)
            if times.contains(where: { $0 > armedAt }) { return times }
        }
        return nil
    }

    /// A one-time group whose session is over; it switches itself off.
    func hasExpired(now: Date, timeZone: TimeZone, ctx: ScheduleContext) -> Bool {
        guard enabled, self.repeat == .once, let times = onceSession(timeZone: timeZone, ctx: ctx) else { return false }
        return times.last! <= now || times.last! <= skipUntil
    }

    /// Every ring after [from] up to and including [until].
    func occurrences(from: Date, until: Date, timeZone: TimeZone, ctx: ScheduleContext) -> [Occurrence] {
        guard enabled else { return [] }
        var sessions: [[Date]] = []
        if self.repeat == .once {
            if let times = onceSession(timeZone: timeZone, ctx: ctx) { sessions = [times] }
        } else {
            // Start a day early: a session may cross midnight.
            var day = LocalDay(from, timeZone: timeZone).plusDays(-1)
            let last = LocalDay(until, timeZone: timeZone)
            while day <= last {
                sessions.append(session(day, timeZone: timeZone, ctx: ctx))
                day = day.plusDays(1)
            }
        }
        var result: [Occurrence] = []
        for times in sessions {
            for (index, time) in times.enumerated() where time > from && time <= until && time > skipUntil {
                result.append(Occurrence(time: time, index: index, remaining: times.count - 1 - index, sessionEnd: times.last!))
            }
        }
        return result.sorted { $0.time < $1.time }
    }

    func nextOccurrence(from: Date, timeZone: TimeZone, ctx: ScheduleContext) -> Occurrence? {
        occurrences(from: from, until: from.addingTimeInterval(9 * 86_400), timeZone: timeZone, ctx: ctx).first
    }
}

/// One ring of a group: its time, position in the session, how many follow it, and when the session's last alarm rings.
struct Occurrence: Equatable {
    let time: Date
    let index: Int
    let remaining: Int
    let sessionEnd: Date
}

struct ScheduleContext {
    var workDays: Set<Int>
    var prayers: PrayerSettings
}

enum ThemeMode: String, CaseIterable, Codable {
    case system, light, dark
}

struct AppSettings: Codable, Equatable {
    var alarmSound: SoundChoice = .builtIn(file: "alarm.wav")
    var prayerSound: SoundChoice = .builtIn(file: "adhan.caf")
    /// Vibration while the ringing screen is open (notification vibration follows iOS settings).
    var vibrate: Bool = true
    /// 0 = full volume immediately. Only possible while the app is open.
    var ascendingSeconds: Int = 0
    var snoozeMinutes: Int = 10
    /// How long the ringing screen keeps playing when the app is open.
    var ringMinutes: Int = 5
    /// Notify this many minutes before a group's first alarm; 0 = off.
    var reminderMinutes: Int = 5
    /// Egypt and most of the region: Sunday to Thursday.
    var workDays: Set<Int> = [7, 1, 2, 3, 4]
    var hasBackground: Bool = false
    var theme: ThemeMode = .system

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        alarmSound = try c.decodeIfPresent(SoundChoice.self, forKey: .alarmSound) ?? d.alarmSound
        prayerSound = try c.decodeIfPresent(SoundChoice.self, forKey: .prayerSound) ?? d.prayerSound
        vibrate = try c.decodeIfPresent(Bool.self, forKey: .vibrate) ?? d.vibrate
        ascendingSeconds = try c.decodeIfPresent(Int.self, forKey: .ascendingSeconds) ?? d.ascendingSeconds
        snoozeMinutes = try c.decodeIfPresent(Int.self, forKey: .snoozeMinutes) ?? d.snoozeMinutes
        ringMinutes = try c.decodeIfPresent(Int.self, forKey: .ringMinutes) ?? d.ringMinutes
        reminderMinutes = try c.decodeIfPresent(Int.self, forKey: .reminderMinutes) ?? d.reminderMinutes
        workDays = try c.decodeIfPresent(Set<Int>.self, forKey: .workDays) ?? d.workDays
        hasBackground = try c.decodeIfPresent(Bool.self, forKey: .hasBackground) ?? d.hasBackground
        theme = try c.decodeIfPresent(ThemeMode.self, forKey: .theme) ?? d.theme
    }
}

struct PrayerAlarm: Codable, Equatable {
    var enabled: Bool
    var minutesBefore: Int = 0
}

struct PrayerSettings: Codable, Equatable {
    var latitude: Double?
    var longitude: Double?
    var method: CalculationMethod = .egypt
    var asrMethod: AsrMethod = .standard
    var prayerAlarms: [Prayer: PrayerAlarm] = Dictionary(
        uniqueKeysWithValues: Prayer.allCases.map { ($0, PrayerAlarm(enabled: $0 != .sunrise)) }
    )

    init() {}

    init(latitude: Double?, longitude: Double?, prayerAlarms: [Prayer: PrayerAlarm]? = nil) {
        self.latitude = latitude
        self.longitude = longitude
        if let alarms = prayerAlarms { self.prayerAlarms = alarms }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = PrayerSettings()
        latitude = try c.decodeIfPresent(Double.self, forKey: .latitude)
        longitude = try c.decodeIfPresent(Double.self, forKey: .longitude)
        method = try c.decodeIfPresent(CalculationMethod.self, forKey: .method) ?? d.method
        asrMethod = try c.decodeIfPresent(AsrMethod.self, forKey: .asrMethod) ?? d.asrMethod
        prayerAlarms = try c.decodeIfPresent([Prayer: PrayerAlarm].self, forKey: .prayerAlarms) ?? d.prayerAlarms
    }

    var hasLocation: Bool { latitude != nil && longitude != nil }

    func times(for day: LocalDay, timeZone: TimeZone) -> [Prayer: Date] {
        guard let lat = latitude, let lng = longitude else { return [:] }
        return PrayerTimesCalculator.calculate(
            day: day, latitude: lat, longitude: lng, timeZone: timeZone, method: method, asrMethod: asrMethod
        )
    }

    func alarm(for prayer: Prayer) -> PrayerAlarm { prayerAlarms[prayer] ?? PrayerAlarm(enabled: false) }

    /// Every time the alert for [prayer] should ring after [from] up to [until]; empty if it is off or there is no location.
    func triggers(for prayer: Prayer, from: Date, until: Date, timeZone: TimeZone) -> [Date] {
        let alarm = self.alarm(for: prayer)
        guard alarm.enabled, hasLocation else { return [] }
        var result: [Date] = []
        var day = LocalDay(from, timeZone: timeZone)
        let last = LocalDay(until, timeZone: timeZone).plusDays(1)
        while day <= last {
            if let time = times(for: day, timeZone: timeZone)[prayer] {
                let trigger = time.addingTimeInterval(Double(-alarm.minutesBefore) * 60)
                if trigger > from && trigger <= until { result.append(trigger) }
            }
            day = day.plusDays(1)
        }
        return result
    }

    func nextTrigger(for prayer: Prayer, from: Date, timeZone: TimeZone) -> Date? {
        triggers(for: prayer, from: from, until: from.addingTimeInterval(8 * 86_400), timeZone: timeZone).first
    }
}

/// A snoozed alarm waiting to ring again.
struct SnoozeState: Codable {
    var at: Date
    var label: String
    var isPrayer: Bool
    var sound: SoundChoice?
}
