import UIKit
import UserNotifications

extension Notification.Name {
    /// Posted after alarms were rescheduled, so screens can refresh.
    static let alarmsChanged = Notification.Name("PrayerAlarm.alarmsChanged")
}

enum NotificationCategory {
    static let alarm = "ALARM"
    static let group = "ALARM_GROUP"
    static let reminder = "REMINDER"
}

enum NotificationAction {
    static let snooze = "SNOOZE"
    static let awake = "AWAKE"
    static let skip = "SKIP"
}

/**
 Turns alarm groups and prayer alerts into local notifications.
 iOS 13 has no alarm API for apps, so each ring is a notification with a sound of up to 30 seconds.
 */
final class AlarmScheduler {
    static let shared = AlarmScheduler()
    static let snoozeId = "snooze"

    private let center = UNUserNotificationCenter.current()
    private let store = Store.shared
    /// Rescheduling runs one at a time so two refills can't interleave their removes and adds.
    private let queue = DispatchQueue(label: "PrayerAlarm.scheduler")

    /// Refills the window of upcoming notifications. Called on launch, foreground, settings changes,
    /// notification actions, background refresh, and time or time zone changes.
    func rescheduleAll(completion: (() -> Void)? = nil) {
        let now = Date()
        let timeZone = TimeZone.current
        let ctx = store.scheduleContext()
        // A one-time alarm switches itself off once its session is over.
        for group in store.groups() where group.hasExpired(now: now, timeZone: timeZone, ctx: ctx) {
            var off = group
            off.enabled = false
            store.upsert(off)
        }
        if let snooze = store.snooze, snooze.at < now { store.snooze = nil }

        let groups = Dictionary(uniqueKeysWithValues: store.groups().map { ($0.id, $0) })
        let settings = store.appSettings
        let prayers = store.prayerSettings
        let plan = Planner.plan(
            groups: Array(groups.values), prayers: prayers, settings: settings, now: now, timeZone: timeZone
        )
        let requests = plan.map { request(for: $0, groups: groups, settings: settings, prayers: prayers, timeZone: timeZone) }

        queue.async {
            let done = DispatchSemaphore(value: 0)
            self.center.getPendingNotificationRequests { pending in
                let stale = pending.map(\.identifier).filter { $0 != AlarmScheduler.snoozeId }
                self.center.removePendingNotificationRequests(withIdentifiers: stale)
                let added = DispatchGroup()
                for request in requests {
                    added.enter()
                    self.center.add(request) { _ in added.leave() }
                }
                added.notify(queue: .global()) { done.signal() }
            }
            done.wait()
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: .alarmsChanged, object: nil)
                completion?()
            }
        }
    }

    func snooze(_ alarm: RingingAlarm) {
        let minutes = store.appSettings.snoozeMinutes
        let at = Date().addingTimeInterval(TimeInterval(minutes * 60))
        store.snooze = SnoozeState(at: at, label: alarm.label, isPrayer: alarm.isPrayer, sound: alarm.sound)

        let content = UNMutableNotificationContent()
        content.title = alarm.label
        content.body = Format.time(at)
        content.sound = SoundLibrary.notificationSound(alarm.sound ?? store.appSettings.alarmSound)
        content.categoryIdentifier = NotificationCategory.alarm
        var snoozed = alarm
        snoozed.groupId = nil
        snoozed.remaining = 0
        content.userInfo = snoozed.userInfo(kind: "snooze")
        markTimeSensitive(content)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(minutes * 60), repeats: false)
        center.add(UNNotificationRequest(identifier: AlarmScheduler.snoozeId, content: content, trigger: trigger))
    }

    func cancelSnooze() {
        store.snooze = nil
        center.removePendingNotificationRequests(withIdentifiers: [AlarmScheduler.snoozeId])
    }

    /// Skips the rest of a session (or the next one): "skip" on the reminder, "skip next" in the app, or "I'm awake".
    func skipSession(groupId: Int, sessionEnd: Date, completion: (() -> Void)? = nil) {
        guard var group = store.group(id: groupId) else {
            completion?()
            return
        }
        if group.repeat == .once {
            group.enabled = false
        } else {
            group.skipUntil = max(group.skipUntil, sessionEnd)
        }
        store.upsert(group)
        removeDelivered(prefixes: ["g\(groupId)-", "r\(groupId)-"])
        rescheduleAll(completion: completion)
    }

    func skipNext(_ group: AlarmGroup, completion: (() -> Void)? = nil) {
        guard let next = group.nextOccurrence(from: Date(), timeZone: .current, ctx: store.scheduleContext()) else {
            completion?()
            return
        }
        skipSession(groupId: group.id, sessionEnd: next.sessionEnd, completion: completion)
    }

    private func removeDelivered(prefixes: [String]) {
        center.getDeliveredNotifications { delivered in
            let ids = delivered.map(\.request.identifier).filter { id in prefixes.contains { id.hasPrefix($0) } }
            self.center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    private func request(
        for alert: PlannedAlert,
        groups: [Int: AlarmGroup],
        settings: AppSettings,
        prayers: PrayerSettings,
        timeZone: TimeZone
    ) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        switch alert.kind {
        case let .groupRing(groupId, _, remaining, sessionEnd):
            let group = groups[groupId]
            let label = group?.label.isEmpty == false ? group!.label : L("alarm")
            let sound = group?.sound ?? settings.alarmSound
            content.title = label
            content.body = remaining > 0 ? Format.time(alert.date) + " · " + L("more_alarms", remaining) : Format.time(alert.date)
            content.sound = SoundLibrary.notificationSound(sound)
            content.categoryIdentifier = remaining > 0 ? NotificationCategory.group : NotificationCategory.alarm
            content.threadIdentifier = "group-\(groupId)"
            content.userInfo = RingingAlarm(
                label: label, isPrayer: false, sound: sound, groupId: groupId, sessionEnd: sessionEnd, remaining: remaining
            ).userInfo(kind: "group")
            markTimeSensitive(content)

        case let .reminder(groupId, ringAt, sessionEnd):
            let group = groups[groupId]
            let label = group?.label.isEmpty == false ? group!.label : L("alarm")
            content.title = L("upcoming_title", Format.time(ringAt))
            content.body = L("upcoming_text", label, settings.reminderMinutes)
            content.sound = .default
            content.categoryIdentifier = NotificationCategory.reminder
            content.threadIdentifier = "group-\(groupId)"
            content.userInfo = ["kind": "reminder", "groupId": groupId, "sessionEnd": sessionEnd.timeIntervalSince1970]

        case let .prayer(prayer, before):
            let name = Format.prayerName(prayer)
            let title = before > 0 ? L("prayer_in_minutes", name, before) : L("prayer_now", name)
            let prayerTime = alert.date.addingTimeInterval(TimeInterval(before * 60))
            content.title = title
            content.body = Format.time(prayerTime)
            content.sound = SoundLibrary.notificationSound(settings.prayerSound)
            content.categoryIdentifier = NotificationCategory.alarm
            content.threadIdentifier = "prayer"
            content.userInfo = RingingAlarm(label: title, isPrayer: true, sound: settings.prayerSound).userInfo(kind: "prayer")
            markTimeSensitive(content)

        case .refresh:
            content.title = L("refresh_title")
            content.body = L("refresh_body")
            content.sound = .default
            content.userInfo = ["kind": "refresh"]
        }

        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: alert.date)
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        return UNNotificationRequest(identifier: alert.identifier, content: content, trigger: trigger)
    }

    /// On iOS 15+ alarms break through Focus when the app has the Time Sensitive Notifications capability.
    private func markTimeSensitive(_ content: UNMutableNotificationContent) {
        if #available(iOS 15.0, *) {
            content.interruptionLevel = .timeSensitive
        }
    }
}
