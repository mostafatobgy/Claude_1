import UIKit

enum AppLocale {
    /// The language the app is showing (Arabic, or English when the phone is in English).
    static let locale = Locale(identifier: Bundle.main.preferredLocalizations.first ?? "ar")
    static var isArabic: Bool { locale.languageCode == "ar" }
}

func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }

func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), locale: AppLocale.locale, arguments: args)
}

enum Format {
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = AppLocale.locale
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    private static let weekdayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = AppLocale.locale
        f.dateFormat = "EEEE"
        return f
    }()

    /// Saturday first, as in Egyptian calendars. ISO weekdays: 1 = Monday … 7 = Sunday.
    static let weekOrder = [6, 7, 1, 2, 3, 4, 5]

    static func time(_ date: Date) -> String { timeFormatter.string(from: date) }

    static func clock(hour: Int, minute: Int) -> String {
        time(LocalDay(Date(), timeZone: .current).at(hour: hour, minute: minute, timeZone: .current))
    }

    static func prayerName(_ prayer: Prayer) -> String {
        L(["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"][prayer.rawValue])
    }

    static func methodName(_ method: CalculationMethod) -> String { L("method_\(method.rawValue)") }

    static func asrName(_ method: AsrMethod) -> String { L(method == .standard ? "asr_standard" : "asr_hanafi") }

    static func themeName(_ theme: ThemeMode) -> String { L("theme_\(theme.rawValue)") }

    static func repeatName(_ r: Repeat) -> String {
        switch r {
        case .once: return L("once")
        case .daily: return L("every_day")
        case .workDays: return L("work_days")
        case .offDays: return L("off_days")
        case .custom: return L("custom_days")
        }
    }

    static func shortWeekday(_ iso: Int) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.locale = AppLocale.locale
        return cal.shortWeekdaySymbols[iso % 7]
    }

    static func dayList(_ days: Set<Int>) -> String {
        weekOrder.filter(days.contains).map(shortWeekday).joined(separator: AppLocale.isArabic ? "، " : ", ")
    }

    static func beforeLabel(_ minutes: Int) -> String {
        minutes == 0 ? L("at_prayer_time") : L("minutes_before", minutes)
    }

    static func prayerOffset(_ prayer: Prayer, _ offset: Int) -> String {
        let name = prayerName(prayer)
        if offset > 0 { return L("minutes_after_prayer", offset, name) }
        if offset < 0 { return L("minutes_before_prayer", -offset, name) }
        return L("at_prayer", name)
    }

    static func repeatText(_ group: AlarmGroup, _ settings: AppSettings) -> String {
        switch group.repeat {
        case .once: return L("once")
        case .daily: return L("every_day")
        case .workDays: return L("work_days_list", dash(dayList(settings.workDays)))
        case .offDays: return L("off_days_list", dash(dayList(Set(1...7).subtracting(settings.workDays))))
        case .custom: return group.customDays.isEmpty ? L("no_days") : dayList(group.customDays)
        }
    }

    static func next(_ date: Date, now: Date) -> String {
        let cal = Calendar.current
        let days = cal.dateComponents([.day], from: cal.startOfDay(for: now), to: cal.startOfDay(for: date)).day ?? 0
        switch days {
        case 0: return L("today_at", time(date))
        case 1: return L("tomorrow_at", time(date))
        default: return weekdayFormatter.string(from: date) + " " + time(date)
        }
    }

    /// "Rings in 7 h 15 min", like the Android toast after saving an alarm.
    static func ringsIn(_ date: Date?, now: Date = Date()) -> String {
        guard let date = date else { return L("wont_ring") }
        let minutes = max(0, Int((date.timeIntervalSince(now) + 59) / 60))
        let hours = minutes / 60
        if hours >= 24 { return L("rings_in_days", hours / 24, hours % 24) }
        return L("rings_in", hours, minutes % 60)
    }

    static func soundName(_ choice: SoundChoice?, defaultName: String) -> String {
        switch choice {
        case nil: return defaultName
        case .systemDefault?: return L("sound_system_default")
        case let .builtIn(file)?: return file.hasPrefix("adhan") ? L("sound_builtin_adhan") : L("sound_builtin_tone")
        case let .custom(_, name)?: return name.isEmpty ? L("custom_sound") : name
        }
    }

    /// Accepts Arabic-Indic digits and the Arabic decimal separator, e.g. "٣٠٫٠٤".
    static func parseNumber(_ text: String) -> Double? {
        let arabic: [Character: Character] = [
            "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9",
            "٫": ".", "،": ".", ",": ".", "−": "-",
        ]
        let ascii = String(text.trimmingCharacters(in: .whitespaces).map { arabic[$0] ?? $0 })
        return Double(ascii)
    }

    private static func dash(_ s: String) -> String { s.isEmpty ? "—" : s }
}
