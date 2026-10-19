import UIKit
import UserNotifications

/// Handles alarms arriving while the app is open and the buttons on alarm notifications.
final class NotificationHandler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationHandler()

    /// Registers the notification buttons. Called again when the snooze length changes, since it is in the title.
    static func registerCategories() {
        let snooze = UNNotificationAction(
            identifier: NotificationAction.snooze,
            title: L("snooze_minutes", Store.shared.appSettings.snoozeMinutes),
            options: []
        )
        let awake = UNNotificationAction(identifier: NotificationAction.awake, title: L("awake"), options: [.destructive])
        let skip = UNNotificationAction(identifier: NotificationAction.skip, title: L("skip"), options: [.destructive])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(
                identifier: NotificationCategory.alarm, actions: [snooze], intentIdentifiers: [], options: []
            ),
            UNNotificationCategory(
                identifier: NotificationCategory.group, actions: [awake, snooze], intentIdentifiers: [], options: []
            ),
            UNNotificationCategory(
                identifier: NotificationCategory.reminder, actions: [skip], intentIdentifiers: [], options: []
            ),
        ])
    }

    /// The app is open: show the ringing screen and play the alarm in the app instead of a banner.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let request = notification.request
        guard let alarm = RingingAlarm(userInfo: request.content.userInfo) else {
            completionHandler([.alert, .sound])
            return
        }
        if request.identifier == AlarmScheduler.snoozeId { Store.shared.snooze = nil }
        DispatchQueue.main.async {
            RingViewController.present(alarm, playSound: true)
            AlarmScheduler.shared.rescheduleAll()
        }
        completionHandler([])
    }

    /// A notification was tapped, or one of its buttons. iOS gives the app a short time in the background for buttons.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let request = response.notification.request
        let info = request.content.userInfo
        let alarm = RingingAlarm(userInfo: info)
        let action = response.actionIdentifier
        DispatchQueue.main.async {
            if request.identifier == AlarmScheduler.snoozeId { Store.shared.snooze = nil }
            switch action {
            case NotificationAction.snooze:
                if let alarm = alarm { AlarmScheduler.shared.snooze(alarm) }
                AlarmScheduler.shared.rescheduleAll(completion: completionHandler)
            case NotificationAction.awake, NotificationAction.skip:
                if let id = info["groupId"] as? Int, let end = info["sessionEnd"] as? Double {
                    AlarmScheduler.shared.skipSession(
                        groupId: id, sessionEnd: Date(timeIntervalSince1970: end), completion: completionHandler
                    )
                } else {
                    completionHandler()
                }
            case UNNotificationDefaultActionIdentifier:
                // Tapping the alarm stops its sound; offer snooze and "I'm awake" on the ringing screen.
                if let alarm = alarm { RingViewController.present(alarm, playSound: false) }
                AlarmScheduler.shared.rescheduleAll(completion: completionHandler)
            default:
                AlarmScheduler.shared.rescheduleAll(completion: completionHandler)
            }
        }
    }
}
