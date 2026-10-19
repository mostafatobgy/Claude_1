import UIKit

private let ascendingOptions = [0, 15, 30, 60, 120]
private let snoozeOptions = [5, 10, 15, 20, 30]
private let ringOptions = [1, 3, 5, 10, 15]
private let reminderOptions = [0, 5, 10, 15, 30, 60]

final class SettingsViewController: UITableViewController, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private enum Section: CaseIterable { case sound, ringing, week, appearance, reliability }

    private let store = Store.shared
    private lazy var soundPicker = SoundPicker(host: self)
    private var settings: AppSettings { store.appSettings }

    static var backgroundURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("background.jpg")
    }

    init() { super.init(style: .insetGrouped) }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = L("settings")
    }

    private func update(_ transform: (inout AppSettings) -> Void) {
        var s = store.appSettings
        transform(&s)
        store.appSettings = s
        NotificationHandler.registerCategories()
        SceneDelegate.current?.applyTheme()
        // Work days, sounds and the reminder time change the scheduled notifications.
        AlarmScheduler.shared.rescheduleAll()
        tableView.reloadData()
    }

    // MARK: Table

    override func numberOfSections(in tableView: UITableView) -> Int { Section.allCases.count }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section.allCases[section] {
        case .sound: return L("section_sound")
        case .ringing: return L("section_ringing")
        case .week: return L("section_week")
        case .appearance: return L("section_appearance")
        case .reliability: return L("section_reliability")
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch Section.allCases[section] {
        case .sound: return L("sound_file_hint") + "\n" + L("vibrate_hint")
        case .ringing: return L("ring_duration") + ", " + L("ascending_volume") + ": " + L("in_app_only")
        case .week: return L("work_days_hint")
        case .appearance: return nil
        case .reliability: return L("silent_hint") + "\n\n" + L("keep_open_hint")
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section.allCases[section] {
        case .sound: return 3
        case .ringing: return 4
        case .week: return 2
        case .appearance: return 2
        case .reliability: return 1
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let s = settings
        switch (Section.allCases[indexPath.section], indexPath.row) {
        case (.sound, 0): return .value1(L("alarm_sound"), Format.soundName(s.alarmSound, defaultName: ""))
        case (.sound, 1): return .value1(L("adhan_sound"), Format.soundName(s.prayerSound, defaultName: ""))
        case (.sound, _):
            return .switchRow(L("vibrate"), on: s.vibrate) { [weak self] on in self?.update { $0.vibrate = on } }
        case (.ringing, 0): return .value1(L("snooze_length"), L("n_minutes", s.snoozeMinutes))
        case (.ringing, 1): return .value1(L("upcoming_notification"), reminderLabel(s.reminderMinutes))
        case (.ringing, 2): return .value1(L("ring_duration"), L("n_minutes", s.ringMinutes))
        case (.ringing, _): return .value1(L("ascending_volume"), ascendingLabel(s.ascendingSeconds))
        case (.week, 0):
            return DayButtonsView(selected: s.workDays) { [weak self] day in
                self?.update { st in
                    if st.workDays.contains(day) { st.workDays.remove(day) } else { st.workDays.insert(day) }
                }
            }.embeddedCell()
        case (.week, _):
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            cell.selectionStyle = .none
            cell.textLabel?.numberOfLines = 0
            let work = Format.dayList(s.workDays)
            let off = Format.dayList(Set(1...7).subtracting(s.workDays))
            cell.textLabel?.text = L("work_days_list", work.isEmpty ? "—" : work) + "\n" + L("off_days_list", off.isEmpty ? "—" : off)
            return cell
        case (.appearance, 0): return .value1(L("theme"), Format.themeName(s.theme))
        case (.appearance, _):
            return .value1(L("alarm_background"), L(s.hasBackground ? "background_picture" : "background_default"))
        case (.reliability, _): return .value1(L("notification_settings"), nil)
        }
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let cell = tableView.cellForRow(at: indexPath)
        let s = settings
        switch (Section.allCases[indexPath.section], indexPath.row) {
        case (.sound, 0):
            soundPicker.pick(title: L("alarm_sound"), current: s.alarmSound, builtIn: AppSettings().alarmSound,
                             allowDefault: false, from: cell) { [weak self] c in
                if let c = c { self?.update { $0.alarmSound = c } }
            }
        case (.sound, 1):
            soundPicker.pick(title: L("adhan_sound"), current: s.prayerSound, builtIn: AppSettings().prayerSound,
                             allowDefault: false, from: cell) { [weak self] c in
                if let c = c { self?.update { $0.prayerSound = c } }
            }
        case (.ringing, 0):
            choose(L("snooze_length"), options: snoozeOptions, selected: s.snoozeMinutes,
                   label: { L("n_minutes", $0) }, from: cell) { [weak self] v in self?.update { $0.snoozeMinutes = v } }
        case (.ringing, 1):
            choose(L("upcoming_notification"), options: reminderOptions, selected: s.reminderMinutes,
                   label: reminderLabel, from: cell) { [weak self] v in self?.update { $0.reminderMinutes = v } }
        case (.ringing, 2):
            choose(L("ring_duration"), options: ringOptions, selected: s.ringMinutes,
                   label: { L("n_minutes", $0) }, from: cell) { [weak self] v in self?.update { $0.ringMinutes = v } }
        case (.ringing, 3):
            choose(L("ascending_volume"), options: ascendingOptions, selected: s.ascendingSeconds,
                   label: ascendingLabel, from: cell) { [weak self] v in self?.update { $0.ascendingSeconds = v } }
        case (.appearance, 0):
            choose(L("theme"), options: ThemeMode.allCases, selected: s.theme,
                   label: Format.themeName, from: cell) { [weak self] v in self?.update { $0.theme = v } }
        case (.appearance, 1):
            choose(L("alarm_background"), options: [false, true], selected: s.hasBackground,
                   label: { L($0 ? "background_pick" : "background_default") }, from: cell) { [weak self] pick in
                if pick { self?.pickBackground() } else { self?.update { $0.hasBackground = false } }
            }
        case (.reliability, _):
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        default:
            break
        }
    }

    private func reminderLabel(_ minutes: Int) -> String { minutes == 0 ? L("off") : L("n_minutes_before", minutes) }

    private func ascendingLabel(_ seconds: Int) -> String {
        if seconds == 0 { return L("off") }
        return seconds < 60 ? L("over_n_seconds", seconds) : L("over_n_minutes", seconds / 60)
    }

    // MARK: Background picture

    private func pickBackground() {
        let picker = UIImagePickerController()
        picker.sourceType = .photoLibrary
        picker.delegate = self
        present(picker, animated: true)
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        guard let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.85) else { return }
        do {
            try data.write(to: SettingsViewController.backgroundURL, options: .atomic)
            update { $0.hasBackground = true }
        } catch {
            update { $0.hasBackground = false }
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }
}
