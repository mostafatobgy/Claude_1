import UIKit

/**
 The full-screen alarm, shown when an alarm fires while the app is open (it then plays the sound itself,
 with rising volume and for the chosen ring duration), or when an alarm notification is tapped.
 */
final class RingViewController: UIViewController {
    private static var pending: (RingingAlarm, Bool)?

    private let alarm: RingingAlarm
    private let playSound: Bool
    private let player = RingPlayer()
    private var stopTimer: Timer?
    private let store = Store.shared

    static func present(_ alarm: RingingAlarm, playSound: Bool) {
        guard let root = SceneDelegate.current?.window?.rootViewController else {
            pending = (alarm, playSound)
            return
        }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        let ring = RingViewController(alarm: alarm, playSound: playSound)
        if let current = top as? RingViewController {
            // A new alarm replaces the one on screen.
            let host = current.presentingViewController ?? root
            current.stop()
            current.dismiss(animated: false) { host.present(ring, animated: true) }
        } else {
            top.present(ring, animated: true)
        }
    }

    static func presentPending() {
        guard let waiting = pending else { return }
        pending = nil
        present(waiting.0, playSound: waiting.1)
    }

    init(alarm: RingingAlarm, playSound: Bool) {
        self.alarm = alarm
        self.playSound = playSound
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        isModalInPresentation = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.07, green: 0.29, blue: 0.20, alpha: 1)

        if store.appSettings.hasBackground, let image = UIImage(contentsOfFile: SettingsViewController.backgroundURL.path) {
            let background = UIImageView(image: image)
            background.contentMode = .scaleAspectFill
            background.clipsToBounds = true
            background.frame = view.bounds
            background.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(background)
            let dim = UIView(frame: view.bounds)
            dim.backgroundColor = UIColor.black.withAlphaComponent(0.35)
            dim.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(dim)
        }

        let icon = UIImageView(image: UIImage(systemName: alarm.isPrayer ? "moon.stars.fill" : "alarm.fill"))
        icon.tintColor = .appCream
        icon.contentMode = .scaleAspectFit
        icon.heightAnchor.constraint(equalToConstant: 56).isActive = true

        let time = UILabel()
        time.text = Format.time(Date())
        time.font = UIFont.monospacedDigitSystemFont(ofSize: 72, weight: .thin)
        time.textColor = .white
        time.adjustsFontSizeToFitWidth = true

        let label = UILabel()
        label.text = alarm.label
        label.font = .preferredFont(forTextStyle: .title1)
        label.textColor = .white
        label.numberOfLines = 0
        label.textAlignment = .center

        var buttons: [UIButton] = []
        if alarm.groupId != nil, alarm.remaining > 0 {
            buttons.append(button(L("stop_remaining", alarm.remaining), filled: true, action: #selector(awake)))
        }
        buttons.append(button(L("snooze_minutes", store.appSettings.snoozeMinutes), filled: false, action: #selector(snooze)))
        buttons.append(button(L("dismiss"), filled: buttons.isEmpty || alarm.remaining == 0, action: #selector(dismissAlarm)))

        let top = UIStackView(arrangedSubviews: [icon, time, label])
        top.axis = .vertical
        top.alignment = .center
        top.spacing = 12
        let bottom = UIStackView(arrangedSubviews: buttons)
        bottom.axis = .vertical
        bottom.spacing = 12
        for stack in [top, bottom] {
            stack.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(stack)
        }
        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            top.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 24),
            top.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -24),
            top.topAnchor.constraint(equalTo: guide.topAnchor, constant: 64),
            bottom.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 24),
            bottom.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -24),
            bottom.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -32),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard playSound else { return }
        let settings = store.appSettings
        let sound = alarm.sound ?? (alarm.isPrayer ? settings.prayerSound : settings.alarmSound)
        // The adhan plays once; other alarms loop until stopped or the ring duration is over.
        player.start(sound: sound, loop: !alarm.isPrayer, ascendingSeconds: settings.ascendingSeconds, vibrate: settings.vibrate)
        stopTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(settings.ringMinutes * 60), repeats: false) { [weak self] _ in
            self?.stop()
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stop()
    }

    private func button(_ title: String, filled: Bool, action: Selector) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        b.titleLabel?.numberOfLines = 0
        b.titleLabel?.textAlignment = .center
        b.setTitleColor(filled ? UIColor(red: 0.07, green: 0.29, blue: 0.20, alpha: 1) : .white, for: .normal)
        b.backgroundColor = filled ? .appCream : UIColor.white.withAlphaComponent(0.18)
        b.layer.cornerRadius = 14
        b.contentEdgeInsets = UIEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        b.heightAnchor.constraint(greaterThanOrEqualToConstant: 54).isActive = true
        b.addTarget(self, action: action, for: .touchUpInside)
        return b
    }

    private func stop() {
        stopTimer?.invalidate()
        stopTimer = nil
        player.stop()
    }

    @objc private func snooze() {
        stop()
        AlarmScheduler.shared.snooze(alarm)
        dismiss(animated: true)
    }

    /// "I'm awake": cancel the rest of this group's alarms for this session.
    @objc private func awake() {
        stop()
        if let id = alarm.groupId, let end = alarm.sessionEnd {
            AlarmScheduler.shared.skipSession(groupId: id, sessionEnd: end)
        }
        dismiss(animated: true)
    }

    @objc private func dismissAlarm() {
        stop()
        dismiss(animated: true)
    }
}
