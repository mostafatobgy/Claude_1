import Foundation

/// One local notification to schedule.
struct PlannedAlert: Equatable {
    enum Kind: Equatable {
        /// One ring of an alarm group.
        case groupRing(groupId: Int, index: Int, remaining: Int, sessionEnd: Date)
        /// Heads-up before the first ring of a group's session, with a Skip button.
        case reminder(groupId: Int, ringAt: Date, sessionEnd: Date)
        case prayer(Prayer, minutesBefore: Int)
        /// "Open the app": rings after the last alert that fit, so alarms never stop silently.
        case refresh
    }

    let kind: Kind
    let date: Date

    var identifier: String {
        let t = Int(date.timeIntervalSince1970)
        switch kind {
        case let .groupRing(id, _, _, _): return "g\(id)-\(t)"
        case let .reminder(id, _, _): return "r\(id)-\(t)"
        case let .prayer(prayer, _): return "p\(prayer.rawValue)-\(t)"
        case .refresh: return "refresh"
        }
    }
}

/**
 iOS keeps at most 64 pending local notifications per app, and the app does not run when they fire.
 So instead of arming "the next alarm" like Android, the app schedules a rolling window: every alert
 in the coming days, earliest first, up to the limit. The window is refilled whenever the app runs
 (opened, a notification button tapped, background refresh).
 */
enum Planner {
    /// The system limit is 64; one slot is left for a snoozed alarm.
    static let limit = 63
    static let horizonDays = 14

    static func plan(
        groups: [AlarmGroup],
        prayers: PrayerSettings,
        settings: AppSettings,
        now: Date,
        timeZone: TimeZone,
        limit: Int = Planner.limit
    ) -> [PlannedAlert] {
        let until = now.addingTimeInterval(Double(horizonDays) * 86_400)
        let ctx = ScheduleContext(workDays: settings.workDays, prayers: prayers)
        var alerts: [PlannedAlert] = []

        for group in groups {
            for o in group.occurrences(from: now, until: until, timeZone: timeZone, ctx: ctx) {
                alerts.append(PlannedAlert(
                    kind: .groupRing(groupId: group.id, index: o.index, remaining: o.remaining, sessionEnd: o.sessionEnd),
                    date: o.time
                ))
                // Heads-up before the first alarm of a session only, not before each one in the group.
                let remindAt = o.time.addingTimeInterval(Double(-settings.reminderMinutes) * 60)
                if settings.reminderMinutes > 0 && o.index == 0 && remindAt > now {
                    alerts.append(PlannedAlert(
                        kind: .reminder(groupId: group.id, ringAt: o.time, sessionEnd: o.sessionEnd),
                        date: remindAt
                    ))
                }
            }
        }
        for prayer in Prayer.allCases {
            let before = prayers.alarm(for: prayer).minutesBefore
            for time in prayers.triggers(for: prayer, from: now, until: until, timeZone: timeZone) {
                alerts.append(PlannedAlert(kind: .prayer(prayer, minutesBefore: before), date: time))
            }
        }

        alerts.sort { $0.date < $1.date }
        guard alerts.count > limit else { return alerts }
        var kept = Array(alerts.prefix(limit - 1))
        kept.append(PlannedAlert(kind: .refresh, date: kept.last!.date.addingTimeInterval(60)))
        return kept
    }
}
