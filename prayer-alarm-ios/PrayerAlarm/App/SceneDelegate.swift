import UIKit
import UserNotifications

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    static weak var current: SceneDelegate?

    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        SceneDelegate.current = self
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainTabBarController()
        window.tintColor = .appGreen
        self.window = window
        applyTheme()
        window.makeKeyAndVisible()

        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            DispatchQueue.main.async { AlarmScheduler.shared.rescheduleAll() }
        }
        DispatchQueue.main.async { RingViewController.presentPending() }
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        AlarmScheduler.shared.rescheduleAll()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        BackgroundRefresh.schedule()
    }

    func applyTheme() {
        switch Store.shared.appSettings.theme {
        case .system: window?.overrideUserInterfaceStyle = .unspecified
        case .light: window?.overrideUserInterfaceStyle = .light
        case .dark: window?.overrideUserInterfaceStyle = .dark
        }
    }
}

extension UIColor {
    static let appGreen = UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.42, green: 0.80, blue: 0.62, alpha: 1)
            : UIColor(red: 0.11, green: 0.42, blue: 0.29, alpha: 1)
    }
    static let appCream = UIColor(red: 0.96, green: 0.89, blue: 0.70, alpha: 1)
}
