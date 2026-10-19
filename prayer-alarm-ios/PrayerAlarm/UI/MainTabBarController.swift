import UIKit

/// Two tabs, as on Android: prayer times and alarms. Settings open from the gear in either tab.
final class MainTabBarController: UITabBarController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let prayers = UINavigationController(rootViewController: PrayerTimesViewController())
        prayers.tabBarItem = UITabBarItem(title: L("prayer_times"), image: UIImage(systemName: "moon.stars"), tag: 0)
        let alarms = UINavigationController(rootViewController: AlarmsViewController())
        alarms.tabBarItem = UITabBarItem(title: L("alarms"), image: UIImage(systemName: "alarm"), tag: 1)
        viewControllers = [prayers, alarms]
    }
}

extension UIViewController {
    func settingsButton() -> UIBarButtonItem {
        let item = UIBarButtonItem(image: UIImage(systemName: "gear"), style: .plain, target: self, action: #selector(openSettings))
        item.accessibilityLabel = L("settings")
        return item
    }

    @objc func openSettings() {
        navigationController?.pushViewController(SettingsViewController(), animated: true)
    }
}
