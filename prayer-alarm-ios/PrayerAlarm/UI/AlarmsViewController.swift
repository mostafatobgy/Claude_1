import UIKit

/// The list of alarm groups, each with an on/off switch and swipe actions for "skip next" and delete.
final class AlarmsViewController: UITableViewController {
    private let store = Store.shared
    private var groups: [AlarmGroup] = []
    private var timer: Timer?

    init() { super.init(style: .insetGrouped) }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = L("alarms")
        let add = UIBarButtonItem(barButtonSystemItem: .add, target: self, action: #selector(addGroup))
        add.accessibilityLabel = L("add_alarm")
        navigationItem.rightBarButtonItems = [add, settingsButton()]
        NotificationCenter.default.addObserver(self, selector: #selector(reload), name: .alarmsChanged, object: nil)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.reload() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timer?.invalidate()
    }

    @objc private func reload() {
        groups = store.groups()
        if groups.isEmpty {
            let label = UILabel()
            label.text = L("no_alarms")
            label.textAlignment = .center
            label.numberOfLines = 0
            label.textColor = .secondaryLabel
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
        tableView.reloadData()
    }

    @objc private func addGroup() {
        edit(AlarmGroup(id: store.nextId()), isNew: true)
    }

    private func edit(_ group: AlarmGroup, isNew: Bool) {
        let editor = GroupEditorViewController(group: group, isNew: isNew) { [weak self] saved in
            self?.reload()
            if let saved = saved { self?.toastNext(saved) }
        }
        present(UINavigationController(rootViewController: editor), animated: true)
    }

    private func toastNext(_ group: AlarmGroup) {
        let next = group.nextOccurrence(from: Date(), timeZone: .current, ctx: store.scheduleContext())
        toast(Format.ringsIn(next?.time))
    }

    private func setEnabled(_ group: AlarmGroup, _ on: Bool) {
        var updated = group
        updated.enabled = on
        updated.skipUntil = Date(timeIntervalSince1970: 0)
        updated.armedAt = Date()
        store.upsert(updated)
        AlarmScheduler.shared.rescheduleAll()
        reload()
        if on { toastNext(updated) }
    }

    // MARK: Table

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { groups.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let group = groups[indexPath.row]
        let now = Date()
        let ctx = store.scheduleContext()
        let settings = store.appSettings
        let next = group.nextOccurrence(from: now, timeZone: .current, ctx: ctx)
        // What the session looks like today (or its next day), for the "6:00 – 6:15" range.
        let today = LocalDay(now, timeZone: .current)
        let session = (0...7).lazy
            .map { group.session(today.plusDays($0), timeZone: .current, ctx: ctx) }
            .first { !$0.isEmpty }

        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        switch group.anchor {
        case let .fixed(hour, minute):
            cell.textLabel?.text = Format.clock(hour: hour, minute: minute)
        case let .afterPrayer(prayer, _):
            cell.textLabel?.text = session?.first.map(Format.time) ?? Format.prayerName(prayer)
        }
        cell.textLabel?.font = UIFont.monospacedDigitSystemFont(ofSize: 34, weight: .light)
        cell.textLabel?.textColor = group.enabled ? .label : .secondaryLabel

        var lines: [String] = []
        if case let .afterPrayer(prayer, offset) = group.anchor { lines.append(Format.prayerOffset(prayer, offset)) }
        if !group.label.isEmpty { lines.append(group.label) }
        if group.count > 1 {
            lines.append(L("group_summary", group.count, group.intervalMinutes, session?.last.map(Format.time) ?? "—"))
        }
        lines.append(Format.repeatText(group, settings))
        if group.enabled {
            lines.append(next.map { L("next_ring", Format.next($0.time, now: now)) } ?? L("wont_ring"))
        }
        cell.detailTextLabel?.text = lines.joined(separator: "\n")
        cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.textColor = group.enabled && next == nil ? .systemRed : .secondaryLabel

        let toggle = ClosureSwitch { [weak self] on in self?.setEnabled(group, on) }
        toggle.isOn = group.enabled
        cell.accessoryView = toggle
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        edit(groups[indexPath.row], isNew: false)
    }

    override func tableView(
        _ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        let group = groups[indexPath.row]
        let delete = UIContextualAction(style: .destructive, title: L("delete")) { [weak self] _, _, done in
            self?.store.delete(id: group.id)
            AlarmScheduler.shared.rescheduleAll()
            self?.reload()
            done(true)
        }
        var actions = [delete]
        let canSkip = group.enabled && group.repeat != .once &&
            group.nextOccurrence(from: Date(), timeZone: .current, ctx: store.scheduleContext()) != nil
        if canSkip {
            let skip = UIContextualAction(style: .normal, title: L("skip_next")) { [weak self] _, _, done in
                AlarmScheduler.shared.skipNext(group) { self?.reload() }
                done(true)
            }
            skip.backgroundColor = .appGreen
            actions.append(skip)
        }
        return UISwipeActionsConfiguration(actions: actions)
    }
}
