import UIKit
import UserNotifications

private let beforeOptions = [0, 5, 10, 15, 20, 30]

/// Today's prayer times with an on/off alert for each, the location, and the calculation method.
final class PrayerTimesViewController: UITableViewController {
    private enum Section { case warning, location, times, hint, method }

    private let store = Store.shared
    private let locationFetcher = LocationFetcher()
    private var notificationsAllowed = true
    private var timer: Timer?
    private var sections: [Section] = []

    private var settings: PrayerSettings { store.prayerSettings }

    init() { super.init(style: .insetGrouped) }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = L("prayer_times")
        navigationItem.rightBarButtonItem = settingsButton()
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: .alarmsChanged, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(checkNotifications), name: UIApplication.willEnterForegroundNotification, object: nil
        )
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
        checkNotifications()
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.reload() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timer?.invalidate()
    }

    @objc private func checkNotifications() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            DispatchQueue.main.async {
                self.notificationsAllowed = s.authorizationStatus != .denied
                self.reload()
            }
        }
    }

    @objc private func reload() {
        var list: [Section] = notificationsAllowed ? [] : [.warning]
        list.append(.location)
        list.append(settings.hasLocation ? .times : .hint)
        list.append(.method)
        sections = list
        tableView.reloadData()
    }

    private func update(_ transform: (inout PrayerSettings) -> Void) {
        var s = store.prayerSettings
        transform(&s)
        store.prayerSettings = s
        // Prayer-based alarm groups move with the prayer times too.
        AlarmScheduler.shared.rescheduleAll()
        reload()
    }

    // MARK: Table

    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sections[section] {
        case .warning, .hint: return 1
        case .location: return 3
        case .times: return Prayer.allCases.count
        case .method: return 2
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        switch sections[indexPath.section] {
        case .warning:
            let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
            cell.textLabel?.text = L("notifications_off")
            cell.textLabel?.numberOfLines = 0
            cell.textLabel?.textColor = .systemRed
            cell.detailTextLabel?.text = L("open_settings")
            cell.detailTextLabel?.textColor = .appGreen
            cell.imageView?.image = UIImage(systemName: "exclamationmark.triangle.fill")
            cell.imageView?.tintColor = .systemRed
            return cell
        case .location:
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            switch indexPath.row {
            case 0:
                if let lat = settings.latitude, let lng = settings.longitude {
                    cell.textLabel?.text = String(format: "%.4f, %.4f", locale: Locale(identifier: "en_US_POSIX"), lat, lng)
                } else {
                    cell.textLabel?.text = L("no_location")
                }
                cell.textLabel?.font = .preferredFont(forTextStyle: .headline)
                cell.imageView?.image = UIImage(systemName: "location.fill")
                cell.selectionStyle = .none
            case 1:
                cell.textLabel?.text = L("use_my_location")
                cell.textLabel?.textColor = .appGreen
            default:
                cell.textLabel?.text = L("enter_manually")
                cell.textLabel?.textColor = .appGreen
            }
            return cell
        case .hint:
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            cell.textLabel?.text = L("set_location_hint")
            cell.textLabel?.numberOfLines = 0
            cell.textLabel?.textColor = .secondaryLabel
            cell.selectionStyle = .none
            return cell
        case .times:
            return prayerCell(Prayer.allCases[indexPath.row])
        case .method:
            return indexPath.row == 0
                ? .value1(L("calculation_method"), Format.methodName(settings.method))
                : .value1(L("asr_method"), Format.asrName(settings.asrMethod))
        }
    }

    private func prayerCell(_ prayer: Prayer) -> UITableViewCell {
        let now = Date()
        let times = settings.times(for: LocalDay(now, timeZone: .current), timeZone: .current)
        let next = times.filter { $0.key != .sunrise && $0.value > now }.min { $0.value < $1.value }?.key
        let isNext = prayer == next
        let alarm = settings.alarm(for: prayer)

        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.text = Format.prayerName(prayer)
        cell.textLabel?.font = isNext ? .preferredFont(forTextStyle: .headline) : .preferredFont(forTextStyle: .body)
        cell.detailTextLabel?.text = alarm.enabled ? Format.beforeLabel(alarm.minutesBefore) : L("alarm_off")
        cell.detailTextLabel?.textColor = .appGreen
        if isNext { cell.backgroundColor = UIColor.appGreen.withAlphaComponent(0.12) }

        let time = UILabel()
        time.text = times[prayer].map(Format.time) ?? "—"
        time.font = UIFont.monospacedDigitSystemFont(ofSize: 20, weight: isNext ? .bold : .regular)
        let toggle = ClosureSwitch { [weak self] on in
            self?.update {
                let before = $0.alarm(for: prayer).minutesBefore
                $0.prayerAlarms[prayer] = PrayerAlarm(enabled: on, minutesBefore: before)
            }
        }
        toggle.isOn = alarm.enabled
        let stack = UIStackView(arrangedSubviews: [time, toggle])
        stack.spacing = 12
        stack.alignment = .center
        stack.frame.size = stack.systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
        cell.accessoryView = stack
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let cell = tableView.cellForRow(at: indexPath)
        switch sections[indexPath.section] {
        case .warning:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        case .location:
            if indexPath.row == 1 { useMyLocation() }
            if indexPath.row == 2 { enterManually() }
        case .times:
            let prayer = Prayer.allCases[indexPath.row]
            choose(L("alert_for", Format.prayerName(prayer)), options: beforeOptions,
                   selected: settings.alarm(for: prayer).minutesBefore, label: Format.beforeLabel, from: cell) { [weak self] minutes in
                self?.update { $0.prayerAlarms[prayer] = PrayerAlarm(enabled: true, minutesBefore: minutes) }
            }
        case .method:
            if indexPath.row == 0 {
                choose(L("calculation_method"), options: CalculationMethod.allCases, selected: settings.method,
                       label: Format.methodName, from: cell) { [weak self] m in self?.update { $0.method = m } }
            } else {
                choose(L("asr_method"), options: AsrMethod.allCases, selected: settings.asrMethod,
                       label: Format.asrName, from: cell) { [weak self] a in self?.update { $0.asrMethod = a } }
            }
        case .hint:
            break
        }
    }

    // MARK: Location

    private func useMyLocation() {
        toast(L("locating"))
        locationFetcher.fetch { [weak self] result in
            switch result {
            case let .success(location):
                self?.update {
                    $0.latitude = location.coordinate.latitude
                    $0.longitude = location.coordinate.longitude
                }
            case .failure(.denied):
                self?.toast(L("location_denied"))
            case .failure(.failed):
                self?.toast(L("location_failed"))
            }
        }
    }

    private func enterManually() {
        let alert = UIAlertController(title: L("enter_manually"), message: L("manual_hint"), preferredStyle: .alert)
        let save = UIAlertAction(title: L("ok"), style: .default) { [weak self, weak alert] _ in
            guard let fields = alert?.textFields,
                  let lat = Format.parseNumber(fields[0].text ?? ""),
                  let lng = Format.parseNumber(fields[1].text ?? "") else { return }
            self?.update {
                $0.latitude = lat
                $0.longitude = lng
            }
        }
        let validate = { [weak alert] in
            guard let fields = alert?.textFields else { return }
            let lat = Format.parseNumber(fields[0].text ?? "")
            let lng = Format.parseNumber(fields[1].text ?? "")
            save.isEnabled = (lat.map { (-90.0...90.0).contains($0) } ?? false) && (lng.map { (-180.0...180.0).contains($0) } ?? false)
        }
        for (placeholder, value) in [(L("latitude"), settings.latitude), (L("longitude"), settings.longitude)] {
            alert.addTextField { field in
                field.placeholder = placeholder
                field.text = value.map { String($0) }
                field.keyboardType = .numbersAndPunctuation
                field.on(.editingChanged, perform: validate)
            }
        }
        alert.addAction(UIAlertAction(title: L("cancel"), style: .cancel))
        alert.addAction(save)
        validate()
        present(alert, animated: true)
    }
}

private final class ControlAction: NSObject {
    let action: () -> Void
    init(_ action: @escaping () -> Void) { self.action = action }
    @objc func run() { action() }
}

private var controlActionKey = 0

extension UIControl {
    /// Target-action with a closure (UIAction needs iOS 14).
    func on(_ event: UIControl.Event, perform action: @escaping () -> Void) {
        let target = ControlAction(action)
        addTarget(target, action: #selector(ControlAction.run), for: event)
        objc_setAssociatedObject(self, &controlActionKey, target, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
}
