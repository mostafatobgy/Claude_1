import UIKit

private let intervalOptions = [1, 2, 3, 5, 10, 15, 20, 30]
private let maxAlarms = 10
private let offsetStep = 5
private let offsetRange = -60...180

/// Add or edit an alarm group: label, start time (fixed or relative to a prayer), how many alarms, repeat, sound.
final class GroupEditorViewController: UITableViewController, UITextFieldDelegate {
    private enum Section { case label, start, count, repeatDays, sound, delete }

    private let store = Store.shared
    private var draft: AlarmGroup
    private let isNew: Bool
    /// Called with the saved group, or nil after delete or cancel.
    private let onDone: (AlarmGroup?) -> Void
    private lazy var soundPicker = SoundPicker(host: self)
    private var sections: [Section] { isNew ? [.label, .start, .count, .repeatDays, .sound] : [.label, .start, .count, .repeatDays, .sound, .delete] }

    init(group: AlarmGroup, isNew: Bool, onDone: @escaping (AlarmGroup?) -> Void) {
        draft = group
        self.isNew = isNew
        self.onDone = onDone
        super.init(style: .insetGrouped)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = L(isNew ? "new_alarm" : "edit_alarm")
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: L("cancel"), style: .plain, target: self, action: #selector(cancel))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: L("save"), style: .done, target: self, action: #selector(save))
        tableView.keyboardDismissMode = .onDrag
    }

    @objc private func cancel() {
        dismiss(animated: true) { self.onDone(nil) }
    }

    @objc private func save() {
        view.endEditing(true)
        draft.enabled = true
        draft.skipUntil = Date(timeIntervalSince1970: 0)
        draft.armedAt = Date()
        store.upsert(draft)
        AlarmScheduler.shared.rescheduleAll()
        let saved = draft
        dismiss(animated: true) { self.onDone(saved) }
    }

    private func change(_ transform: (inout AlarmGroup) -> Void) {
        transform(&draft)
        tableView.reloadData()
    }

    // MARK: Table

    override func numberOfSections(in tableView: UITableView) -> Int { sections.count }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch sections[section] {
        case .label: return L("label")
        case .start: return L("start_time")
        case .count: return L("number_of_alarms")
        case .repeatDays: return L("repeat")
        case .sound: return L("sound")
        case .delete: return nil
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch sections[section] {
        case .start:
            guard case .afterPrayer = draft.anchor else { return nil }
            return store.prayerSettings.hasLocation ? L("moves_with_prayer") : L("needs_location")
        case .count:
            guard draft.count > 1 else { return nil }
            let preview = previewSession().map(Format.time).joined(separator: "  ·  ")
            return (preview.isEmpty ? "" : preview + "\n\n") + L("group_hint")
        case .repeatDays:
            return draft.repeat == .workDays || draft.repeat == .offDays ? L("work_days_in_settings") : nil
        default:
            return nil
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sections[section] {
        case .label, .sound, .delete: return 1
        case .start:
            if case .fixed = draft.anchor { return 2 }
            return 3
        case .count: return draft.count > 1 ? 2 : 1
        case .repeatDays: return Repeat.allCases.count + (draft.repeat == .custom ? 1 : 0)
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        switch sections[indexPath.section] {
        case .label: return labelCell()
        case .start: return startCell(indexPath.row)
        case .count: return countCell(indexPath.row)
        case .repeatDays: return repeatCell(indexPath.row)
        case .sound:
            return .value1(L("sound"), Format.soundName(draft.sound, defaultName: L("sound_default_from_settings")))
        case .delete:
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            cell.textLabel?.text = L("delete")
            cell.textLabel?.textColor = .systemRed
            cell.textLabel?.textAlignment = .center
            return cell
        }
    }

    private func labelCell() -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.selectionStyle = .none
        let field = UITextField()
        field.text = draft.label
        field.placeholder = L("label_hint")
        field.clearButtonMode = .whileEditing
        field.returnKeyType = .done
        field.delegate = self
        field.on(.editingChanged) { [weak self, weak field] in self?.draft.label = field?.text ?? "" }
        field.translatesAutoresizingMaskIntoConstraints = false
        cell.contentView.addSubview(field)
        let margins = cell.contentView.layoutMarginsGuide
        NSLayoutConstraint.activate([
            field.leadingAnchor.constraint(equalTo: margins.leadingAnchor),
            field.trailingAnchor.constraint(equalTo: margins.trailingAnchor),
            field.topAnchor.constraint(equalTo: margins.topAnchor),
            field.bottomAnchor.constraint(equalTo: margins.bottomAnchor),
            field.heightAnchor.constraint(greaterThanOrEqualToConstant: 30),
        ])
        return cell
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    private func startCell(_ row: Int) -> UITableViewCell {
        if row == 0 {
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            cell.selectionStyle = .none
            let control = UISegmentedControl(items: [L("fixed_time"), L("prayer_based")])
            if case .fixed = draft.anchor { control.selectedSegmentIndex = 0 } else { control.selectedSegmentIndex = 1 }
            control.on(.valueChanged) { [weak self, weak control] in
                guard let self = self, let control = control else { return }
                switch (control.selectedSegmentIndex, self.draft.anchor) {
                case (0, .afterPrayer): self.change { $0.anchor = .fixed(hour: 7, minute: 0) }
                case (1, .fixed): self.change { $0.anchor = .afterPrayer(prayer: .fajr, offsetMinutes: 5) }
                default: break
                }
            }
            embed(control, in: cell)
            return cell
        }
        switch draft.anchor {
        case let .fixed(hour, minute):
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            cell.selectionStyle = .none
            let picker = UIDatePicker()
            picker.datePickerMode = .time
            if #available(iOS 13.4, *) { picker.preferredDatePickerStyle = .wheels }
            picker.locale = AppLocale.locale
            picker.date = LocalDay(Date(), timeZone: .current).at(hour: hour, minute: minute, timeZone: .current)
            picker.on(.valueChanged) { [weak self, weak picker] in
                guard let date = picker?.date else { return }
                let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                // No reload: the wheel would jump while it is turning.
                self?.draft.anchor = .fixed(hour: c.hour ?? 0, minute: c.minute ?? 0)
                self?.refreshFooters()
            }
            embed(picker, in: cell)
            return cell
        case let .afterPrayer(prayer, offset):
            if row == 1 { return .value1(L("prayer"), Format.prayerName(prayer)) }
            return StepperCell(
                text: Format.prayerOffset(prayer, offset), value: Double(offset),
                min: Double(offsetRange.lowerBound), max: Double(offsetRange.upperBound), step: Double(offsetStep)
            ) { [weak self] value in
                self?.change { $0.anchor = .afterPrayer(prayer: prayer, offsetMinutes: Int(value)) }
            }
        }
    }

    private func countCell(_ row: Int) -> UITableViewCell {
        if row == 0 {
            let text = L("alarm_count", draft.count)
            return StepperCell(text: text, value: Double(draft.count), min: 1, max: Double(maxAlarms)) { [weak self] value in
                self?.change { $0.count = Int(value) }
            }
        }
        let index = intervalOptions.firstIndex(of: draft.intervalMinutes) ?? 3
        return StepperCell(
            text: L("interval") + ": " + L("every_n_minutes", intervalOptions[index]),
            value: Double(index), min: 0, max: Double(intervalOptions.count - 1)
        ) { [weak self] value in
            self?.change { $0.intervalMinutes = intervalOptions[Int(value)] }
        }
    }

    private func repeatCell(_ row: Int) -> UITableViewCell {
        if row == Repeat.allCases.count {
            return DayButtonsView(selected: draft.customDays) { [weak self] day in
                self?.change { g in
                    if g.customDays.contains(day) { g.customDays.remove(day) } else { g.customDays.insert(day) }
                }
            }.embeddedCell()
        }
        let r = Repeat.allCases[row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.text = Format.repeatName(r)
        if r == .workDays || r == .offDays {
            var preview = draft
            preview.repeat = r
            cell.detailTextLabel?.text = Format.repeatText(preview, store.appSettings)
            cell.detailTextLabel?.textColor = .secondaryLabel
        }
        cell.accessoryType = draft.repeat == r ? .checkmark : .none
        return cell
    }

    private func embed(_ view: UIView, in cell: UITableViewCell) {
        view.translatesAutoresizingMaskIntoConstraints = false
        cell.contentView.addSubview(view)
        let margins = cell.contentView.layoutMarginsGuide
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: margins.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: margins.trailingAnchor),
            view.topAnchor.constraint(equalTo: margins.topAnchor),
            view.bottomAnchor.constraint(equalTo: margins.bottomAnchor),
        ])
    }

    /// Updates the preview line under the count without touching the time wheel.
    private func refreshFooters() {
        guard let index = sections.firstIndex(of: .count) else { return }
        UIView.performWithoutAnimation { tableView.reloadSections(IndexSet(integer: index), with: .none) }
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let cell = tableView.cellForRow(at: indexPath)
        switch sections[indexPath.section] {
        case .start:
            guard indexPath.row == 1, case let .afterPrayer(prayer, offset) = draft.anchor else { return }
            choose(L("prayer"), options: Prayer.allCases, selected: prayer, label: Format.prayerName, from: cell) { [weak self] p in
                self?.change { $0.anchor = .afterPrayer(prayer: p, offsetMinutes: offset) }
            }
        case .repeatDays:
            guard indexPath.row < Repeat.allCases.count else { return }
            change { $0.repeat = Repeat.allCases[indexPath.row] }
        case .sound:
            soundPicker.pick(
                title: L("sound"), current: draft.sound, builtIn: .builtIn(file: SoundLibrary.fallbackFile),
                allowDefault: true, from: cell
            ) { [weak self] choice in self?.change { $0.sound = choice } }
        case .delete:
            store.delete(id: draft.id)
            AlarmScheduler.shared.rescheduleAll()
            dismiss(animated: true) { self.onDone(nil) }
        case .label, .count:
            break
        }
    }

    /// The next run of the group's alarms (ignoring on/off and skips), for the preview line.
    private func previewSession() -> [Date] {
        var g = draft
        g.repeat = .daily
        let now = Date()
        let today = LocalDay(now, timeZone: .current)
        let ctx = store.scheduleContext()
        for d in 0...1 {
            let times = g.session(today.plusDays(d), timeZone: .current, ctx: ctx)
            if let last = times.last, last > now { return times }
        }
        return g.session(today, timeZone: .current, ctx: ctx)
    }
}
