@testable import PrayerAlarmCore
import XCTest

/// The Android app's AlarmGroupTest.kt cases, plus the iOS scheduling window.
final class AlarmGroupTests: XCTestCase {
    private let cairo = TimeZone(identifier: "Africa/Cairo")!
    private let ctx = ScheduleContext(
        workDays: AppSettings().workDays,
        prayers: PrayerSettings(latitude: 30.0444, longitude: 31.2357)
    )

    // 2026-10-19 is a Monday.
    private func at(_ day: Int, _ hour: Int, _ minute: Int) -> Date {
        LocalDay(year: 2026, month: 10, day: day).at(hour: hour, minute: minute, timeZone: cairo)
    }

    private func group(_ anchor: Anchor, count: Int = 1, interval: Int = 5, repeat r: Repeat = .daily) -> AlarmGroup {
        var g = AlarmGroup(id: 1)
        g.anchor = anchor
        g.count = count
        g.intervalMinutes = interval
        g.repeat = r
        g.armedAt = at(19, 0, 0)
        return g
    }

    func testGroupRingsEachAlarmInTurn() {
        let g = group(.fixed(hour: 6, minute: 0), count: 4)
        XCTAssertEqual(
            g.nextOccurrence(from: at(19, 5, 0), timeZone: cairo, ctx: ctx),
            Occurrence(time: at(19, 6, 0), index: 0, remaining: 3, sessionEnd: at(19, 6, 15))
        )
        let third = g.nextOccurrence(from: at(19, 6, 5), timeZone: cairo, ctx: ctx)!
        XCTAssertEqual(third.time, at(19, 6, 10))
        XCTAssertEqual(third.index, 2)
        // After the last one, the next is tomorrow's first.
        XCTAssertEqual(
            g.nextOccurrence(from: at(19, 6, 15), timeZone: cairo, ctx: ctx),
            Occurrence(time: at(20, 6, 0), index: 0, remaining: 3, sessionEnd: at(20, 6, 15))
        )
    }

    func testSkipUntilSkipsTheRestOfTheSession() {
        var g = group(.fixed(hour: 6, minute: 0), count: 4)
        g.skipUntil = at(19, 6, 15)
        XCTAssertEqual(g.nextOccurrence(from: at(19, 6, 0), timeZone: cairo, ctx: ctx)?.time, at(20, 6, 0))
    }

    func testWorkDaysAndOffDays() {
        let work = group(.fixed(hour: 7, minute: 0), repeat: .workDays)
        var off = work
        off.repeat = .offDays
        // Thursday 22nd after 7:00: next work day is Sunday 25th; next off day is Friday 23rd.
        XCTAssertEqual(work.nextOccurrence(from: at(22, 8, 0), timeZone: cairo, ctx: ctx)?.time, at(25, 7, 0))
        XCTAssertEqual(off.nextOccurrence(from: at(22, 8, 0), timeZone: cairo, ctx: ctx)?.time, at(23, 7, 0))
        var custom = work
        custom.repeat = .custom
        custom.customDays = [3] // Wednesday
        XCTAssertEqual(custom.nextOccurrence(from: at(19, 8, 0), timeZone: cairo, ctx: ctx)?.time, at(21, 7, 0))
    }

    func testFollowsFajrEveryDay() {
        let g = group(.afterPrayer(prayer: .fajr, offsetMinutes: 5))
        for day in [19, 26] {
            let fajr = ctx.prayers.times(for: LocalDay(year: 2026, month: 10, day: day), timeZone: cairo)[.fajr]!
            XCTAssertEqual(g.nextOccurrence(from: at(day, 0, 0), timeZone: cairo, ctx: ctx)?.time, fajr.addingTimeInterval(5 * 60))
        }
        let before = group(.afterPrayer(prayer: .fajr, offsetMinutes: -10))
        let fajr = ctx.prayers.times(for: LocalDay(year: 2026, month: 10, day: 19), timeZone: cairo)[.fajr]!
        XCTAssertEqual(before.nextOccurrence(from: at(19, 0, 0), timeZone: cairo, ctx: ctx)?.time, fajr.addingTimeInterval(-10 * 60))
    }

    func testPrayerAnchorNeedsLocation() {
        let g = group(.afterPrayer(prayer: .fajr, offsetMinutes: 5))
        let noLocation = ScheduleContext(workDays: ctx.workDays, prayers: PrayerSettings())
        XCTAssertNil(g.nextOccurrence(from: at(19, 0, 0), timeZone: cairo, ctx: noLocation))
    }

    func testDisabledGroupNeverRings() {
        var g = group(.fixed(hour: 7, minute: 0))
        g.enabled = false
        XCTAssertNil(g.nextOccurrence(from: at(19, 0, 0), timeZone: cairo, ctx: ctx))
    }

    func testOnceRingsOnlyItsFirstSessionThenExpires() {
        var g = group(.fixed(hour: 6, minute: 0), count: 2, repeat: .once)
        g.armedAt = at(19, 22, 0)
        let rings = g.occurrences(from: at(19, 22, 0), until: at(25, 0, 0), timeZone: cairo, ctx: ctx)
        XCTAssertEqual(rings.map(\.time), [at(20, 6, 0), at(20, 6, 5)])
        XCTAssertFalse(g.hasExpired(now: at(20, 6, 2), timeZone: cairo, ctx: ctx))
        XCTAssertTrue(g.hasExpired(now: at(20, 6, 5), timeZone: cairo, ctx: ctx))
    }

    func testPlanIsSortedAndAddsRemindersAndPrayers() {
        let g = group(.fixed(hour: 6, minute: 0), count: 2)
        var settings = AppSettings()
        settings.reminderMinutes = 5
        let plan = Planner.plan(groups: [g], prayers: ctx.prayers, settings: settings, now: at(19, 5, 50), timeZone: cairo)
        XCTAssertEqual(plan.map(\.date), plan.map(\.date).sorted())
        XCTAssertEqual(plan.first?.kind, .reminder(groupId: 1, ringAt: at(19, 6, 0), sessionEnd: at(19, 6, 5)))
        XCTAssertTrue(plan.contains { if case .prayer(.dhuhr, _) = $0.kind { return true }; return false })
        // Sunrise is off by default.
        XCTAssertFalse(plan.contains { if case .prayer(.sunrise, _) = $0.kind { return true }; return false })
    }

    func testPlanStaysUnderTheNotificationLimitAndEndsWithARefresh() {
        let g = group(.fixed(hour: 6, minute: 0), count: 10, interval: 1)
        let plan = Planner.plan(groups: [g], prayers: ctx.prayers, settings: AppSettings(), now: at(19, 5, 0), timeZone: cairo)
        XCTAssertEqual(plan.count, Planner.limit)
        XCTAssertEqual(plan.last?.kind, .refresh)
        XCTAssertEqual(Set(plan.map(\.identifier)).count, plan.count)
    }
}
