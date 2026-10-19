import Foundation

/// Saves settings and alarm groups as JSON in UserDefaults.
final class Store {
    static let shared = Store()

    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var prayerSettings: PrayerSettings {
        get { load("prayerSettings") ?? PrayerSettings() }
        set { save(newValue, "prayerSettings") }
    }

    var appSettings: AppSettings {
        get { load("appSettings") ?? AppSettings() }
        set { save(newValue, "appSettings") }
    }

    var snooze: SnoozeState? {
        get { load("snooze") }
        set {
            if let value = newValue { save(value, "snooze") } else { defaults.removeObject(forKey: "snooze") }
        }
    }

    func scheduleContext() -> ScheduleContext {
        ScheduleContext(workDays: appSettings.workDays, prayers: prayerSettings)
    }

    /// Fixed-time groups first by clock time, then prayer-based ones by prayer and offset.
    func groups() -> [AlarmGroup] {
        let list: [AlarmGroup] = load("groups") ?? []
        return list.sorted { sortKey($0) < sortKey($1) }
    }

    func group(id: Int) -> AlarmGroup? { groups().first { $0.id == id } }

    func upsert(_ group: AlarmGroup) { saveGroups(groups().filter { $0.id != group.id } + [group]) }

    func delete(id: Int) { saveGroups(groups().filter { $0.id != id }) }

    func nextId() -> Int { (groups().map(\.id).max() ?? 0) + 1 }

    private func sortKey(_ g: AlarmGroup) -> (Int, Int) {
        switch g.anchor {
        case let .fixed(hour, minute): return (0, hour * 60 + minute)
        case let .afterPrayer(prayer, offset): return (1, prayer.rawValue * 10_000 + offset)
        }
    }

    private func saveGroups(_ list: [AlarmGroup]) { save(list, "groups") }

    private func load<T: Decodable>(_ key: String) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, _ key: String) {
        if let data = try? encoder.encode(value) { defaults.set(data, forKey: key) }
    }
}
