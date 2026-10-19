import Foundation

enum Prayer: Int, CaseIterable, Codable {
    case fajr, sunrise, dhuhr, asr, maghrib, isha
}

/**
 Twilight angles used by the major calculation authorities.
 Isha is either a sun angle below the horizon or a fixed number of minutes after Maghrib.
 */
enum CalculationMethod: String, CaseIterable, Codable {
    case egypt, mwl, makkah, karachi, isna, gulf

    var fajrAngle: Double {
        switch self {
        case .egypt, .gulf: return 19.5
        case .mwl, .karachi: return 18.0
        case .makkah: return 18.5
        case .isna: return 15.0
        }
    }

    var ishaAngle: Double? {
        switch self {
        case .egypt: return 17.5
        case .mwl: return 17.0
        case .karachi: return 18.0
        case .isna: return 15.0
        case .makkah, .gulf: return nil
        }
    }

    var ishaMinutes: Int? {
        switch self {
        case .makkah, .gulf: return 90
        default: return nil
        }
    }
}

/// Shadow length factor for Asr: Standard (Shafi'i, Maliki, Hanbali) = 1, Hanafi = 2.
enum AsrMethod: String, CaseIterable, Codable {
    case standard, hanafi

    var factor: Double { self == .standard ? 1.0 : 2.0 }
}

/// A calendar day without a time or time zone, like java.time.LocalDate.
struct LocalDay: Hashable, Codable, Comparable {
    let year: Int
    let month: Int
    let day: Int

    static func calendar(_ timeZone: TimeZone) -> Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal
    }

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    init(_ date: Date, timeZone: TimeZone) {
        let c = LocalDay.calendar(timeZone).dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year!, month: c.month!, day: c.day!)
    }

    /// The moment this day reaches [hour]:[minute] in [timeZone].
    func at(hour: Int, minute: Int, timeZone: TimeZone) -> Date {
        let comps = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)
        return LocalDay.calendar(timeZone).date(from: comps)!
    }

    func plusDays(_ n: Int) -> LocalDay {
        let utc = TimeZone(identifier: "UTC")!
        let date = LocalDay.calendar(utc).date(byAdding: .day, value: n, to: at(hour: 12, minute: 0, timeZone: utc))!
        return LocalDay(date, timeZone: utc)
    }

    /// ISO weekday: 1 = Monday … 7 = Sunday.
    var isoWeekday: Int {
        let utc = TimeZone(identifier: "UTC")!
        let weekday = LocalDay.calendar(utc).component(.weekday, from: at(hour: 12, minute: 0, timeZone: utc))
        return (weekday + 5) % 7 + 1
    }

    static func < (a: LocalDay, b: LocalDay) -> Bool {
        (a.year, a.month, a.day) < (b.year, b.month, b.day)
    }
}

/**
 Astronomical prayer time calculation, ported from the PrayTimes.org algorithm (v2.3),
 the same code as the Android app's PrayerTimesCalculator.kt.
 Uses the "angle based" adjustment for high latitudes, where twilight may never end.
 */
enum PrayerTimesCalculator {
    private static let riseSetAngle = 0.833

    static func calculate(
        day: LocalDay,
        latitude: Double,
        longitude: Double,
        timeZone: TimeZone,
        method: CalculationMethod,
        asrMethod: AsrMethod
    ) -> [Prayer: Date] {
        let noon = day.at(hour: 12, minute: 0, timeZone: timeZone)
        let zoneHours = Double(timeZone.secondsFromGMT(for: noon)) / 3600.0
        let jDate = julian(day.year, day.month, day.day) - longitude / (15.0 * 24.0)
        let sun = Sun(jDate: jDate, lat: latitude)

        // Initial guesses in hours, converted to fractions of a day.
        let fajr0 = sun.sunAngleTime(method.fajrAngle, 5 / 24.0, ccw: true)
        let sunrise0 = sun.sunAngleTime(riseSetAngle, 6 / 24.0, ccw: true)
        let dhuhr0 = sun.midDay(12 / 24.0)
        let asr0 = sun.asrTime(asrMethod.factor, 13 / 24.0)
        let sunset0 = sun.sunAngleTime(riseSetAngle, 18 / 24.0)
        let isha0 = method.ishaAngle.map { sun.sunAngleTime($0, 18 / 24.0) } ?? .nan

        let shift = zoneHours - longitude / 15.0
        let times = [fajr0, sunrise0, dhuhr0, asr0, sunset0, isha0].map { $0 + shift }
        var fajr = times[0]
        let maghrib = times[4]
        var isha = times[5]

        let night = timeDiff(times[4], times[1])
        fajr = adjustHighLatitude(fajr, base: times[1], angle: method.fajrAngle, night: night, ccw: true)
        if let angle = method.ishaAngle {
            isha = adjustHighLatitude(isha, base: maghrib, angle: angle, night: night, ccw: false)
        }
        if let minutes = method.ishaMinutes {
            isha = maghrib + Double(minutes) / 60.0
        }

        let hours: [Prayer: Double] = [
            .fajr: fajr, .sunrise: times[1], .dhuhr: times[2], .asr: times[3], .maghrib: maghrib, .isha: isha,
        ]
        let midnightUtc = day.at(hour: 0, minute: 0, timeZone: TimeZone(identifier: "UTC")!).timeIntervalSince1970
        var result: [Prayer: Date] = [:]
        for (prayer, h) in hours where !h.isNaN {
            // Round half up, like Kotlin's roundToLong.
            let minutes = ((h - zoneHours) * 60 + 0.5).rounded(.down)
            result[prayer] = Date(timeIntervalSince1970: midnightUtc + minutes * 60)
        }
        return result
    }

    private struct Sun {
        let jDate: Double
        let lat: Double

        /// Declination and equation of time for a Julian date.
        private func position(_ jd: Double) -> (decl: Double, eqt: Double) {
            let d = jd - 2451545.0
            let g = fixAngle(357.529 + 0.98560028 * d)
            let q = fixAngle(280.459 + 0.98564736 * d)
            let l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
            let e = 23.439 - 0.00000036 * d
            let ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
            let eqt = q / 15.0 - fixHour(ra)
            let decl = darcsin(dsin(e) * dsin(l))
            return (decl, eqt)
        }

        func midDay(_ time: Double) -> Double { fixHour(12 - position(jDate + time).eqt) }

        func sunAngleTime(_ angle: Double, _ time: Double, ccw: Bool = false) -> Double {
            let decl = position(jDate + time).decl
            let noon = midDay(time)
            let t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
            return noon + (ccw ? -t : t)
        }

        func asrTime(_ factor: Double, _ time: Double) -> Double {
            let decl = position(jDate + time).decl
            let angle = -darccot(factor + dtan(abs(lat - decl)))
            return sunAngleTime(angle, time)
        }
    }

    private static func adjustHighLatitude(_ time: Double, base: Double, angle: Double, night: Double, ccw: Bool) -> Double {
        let portion = angle / 60.0 * night
        let diff = ccw ? timeDiff(time, base) : timeDiff(base, time)
        return time.isNaN || diff > portion ? base + (ccw ? -portion : portion) : time
    }

    private static func julian(_ year: Int, _ month: Int, _ day: Int) -> Double {
        var y = Double(year)
        var m = Double(month)
        if m <= 2 {
            y -= 1
            m += 12
        }
        let a = (y / 100.0).rounded(.down)
        let b = 2 - a + (a / 4).rounded(.down)
        return (365.25 * (y + 4716)).rounded(.down) + (30.6001 * (m + 1)).rounded(.down) + Double(day) + b - 1524.5
    }
}

private func timeDiff(_ a: Double, _ b: Double) -> Double { fixHour(b - a) }
private func fix(_ a: Double, _ b: Double) -> Double {
    let r = a - b * (a / b).rounded(.down)
    return r < 0 ? r + b : r
}
private func fixAngle(_ a: Double) -> Double { fix(a, 360.0) }
private func fixHour(_ a: Double) -> Double { fix(a, 24.0) }
private func dtr(_ d: Double) -> Double { d * .pi / 180.0 }
private func rtd(_ r: Double) -> Double { r * 180.0 / .pi }
private func dsin(_ d: Double) -> Double { sin(dtr(d)) }
private func dcos(_ d: Double) -> Double { cos(dtr(d)) }
private func dtan(_ d: Double) -> Double { tan(dtr(d)) }
private func darcsin(_ x: Double) -> Double { rtd(asin(x)) }
private func darccos(_ x: Double) -> Double { rtd(acos(x)) }
private func darctan2(_ y: Double, _ x: Double) -> Double { rtd(atan2(y, x)) }
private func darccot(_ x: Double) -> Double { rtd(atan(1 / x)) }
