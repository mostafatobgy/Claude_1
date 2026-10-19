import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // The delegate must be set before launch finishes to receive taps on notifications that launched the app.
        UNUserNotificationCenter.current().delegate = NotificationHandler.shared
        NotificationHandler.registerCategories()
        BackgroundRefresh.register()

        // Fixed clock times and prayer times shift when the clock or time zone changes.
        for name in [UIApplication.significantTimeChangeNotification, .NSSystemTimeZoneDidChange] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                AlarmScheduler.shared.rescheduleAll()
            }
        }
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }
}
