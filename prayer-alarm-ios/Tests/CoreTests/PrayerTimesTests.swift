@testable import PrayerAlarmCore
import XCTest

/// The same cases as the Android app's PrayerTimesCalculatorTest.kt.
final class PrayerTimesTests: XCTestCase {
    private let cairo = TimeZone(identifier: "Africa/Cairo")!

    private func assertNear(_ expected: String, _ actual: Date?, in zone: TimeZone, tolerance: Int = 2,
                            file: StaticString = #file, line: UInt = #line) {
        guard let actual = actual else { return XCTFail("missing time", file: file, line: line) }
        let parts = expected.split(separator: ":").map { Int($0)! }
        let c = LocalDay.calendar(zone).dateComponents([.hour, .minute], from: actual)
        let diff = abs(parts[0] * 60 + parts[1] - (c.hour! * 60 + c.minute!))
        XCTAssertLessThanOrEqual(diff, tolerance, "expected \(expected) but was \(c.hour!):\(c.minute!)", file: file, line: line)
    }

    func testCairo() {
        // Reference: Egyptian General Authority of Survey timetable for Cairo, 1 Jan 2025.
        let t = PrayerTimesCalculator.calculate(
            day: LocalDay(year: 2025, month: 1, day: 1), latitude: 30.0444, longitude: 31.2357,
            timeZone: cairo, method: .egypt, asrMethod: .standard
        )
        assertNear("05:18", t[.fajr], in: cairo)
        assertNear("06:49", t[.sunrise], in: cairo)
        assertNear("11:59", t[.dhuhr], in: cairo)
        assertNear("14:47", t[.asr], in: cairo)
        // Astronomical sunset; printed Egyptian timetables add ~2-3 minutes of caution (17:09).
        assertNear("17:07", t[.maghrib], in: cairo)
        assertNear("18:31", t[.isha], in: cairo)
    }

    func testMakkahIshaIsNinetyMinutesAfterMaghrib() {
        let t = PrayerTimesCalculator.calculate(
            day: LocalDay(year: 2026, month: 10, day: 19), latitude: 21.4225, longitude: 39.8262,
            timeZone: TimeZone(identifier: "Asia/Riyadh")!, method: .makkah, asrMethod: .standard
        )
        XCTAssertEqual(t[.maghrib]!.addingTimeInterval(90 * 60), t[.isha])
    }

    func testHighLatitudeStillProducesFajrAndIsha() {
        // Oslo in June: the sun never goes 18 degrees below the horizon.
        let t = PrayerTimesCalculator.calculate(
            day: LocalDay(year: 2026, month: 6, day: 21), latitude: 59.91, longitude: 10.75,
            timeZone: TimeZone(identifier: "Europe/Oslo")!, method: .mwl, asrMethod: .standard
        )
        XCTAssertEqual(t.count, 6)
        XCTAssertLessThan(t[.fajr]!, t[.sunrise]!)
        XCTAssertGreaterThan(t[.isha]!, t[.maghrib]!)
    }

    func testNextPrayerTriggerRollsToTomorrowAndAppliesOffset() {
        let settings = PrayerSettings(
            latitude: 30.0444, longitude: 31.2357,
            prayerAlarms: [.fajr: PrayerAlarm(enabled: true, minutesBefore: 10)]
        )
        let from = LocalDay(year: 2025, month: 1, day: 1).at(hour: 12, minute: 0, timeZone: cairo)
        let next = settings.nextTrigger(for: .fajr, from: from, timeZone: cairo)!
        let jan2 = LocalDay(year: 2025, month: 1, day: 2)
        XCTAssertEqual(LocalDay(next, timeZone: cairo), jan2)
        let fajr = settings.times(for: jan2, timeZone: cairo)[.fajr]!
        XCTAssertEqual(fajr.addingTimeInterval(-10 * 60), next)
        XCTAssertNil(settings.nextTrigger(for: .dhuhr, from: from, timeZone: cairo))
    }

    func testIsoWeekday() {
        XCTAssertEqual(LocalDay(year: 2026, month: 10, day: 19).isoWeekday, 1) // Monday
        XCTAssertEqual(LocalDay(year: 2026, month: 10, day: 25).isoWeekday, 7) // Sunday
        XCTAssertEqual(LocalDay(year: 2026, month: 12, day: 31).plusDays(1), LocalDay(year: 2027, month: 1, day: 1))
    }
}
