import MobileCoreServices
import UIKit

extension UIViewController {
    /// A list of choices with the current one ticked, like the Android ChoiceDialog.
    func choose<T: Equatable>(
        _ title: String,
        options: [T],
        selected: T?,
        label: (T) -> String,
        from sourceView: UIView?,
        onPick: @escaping (T) -> Void
    ) {
        let sheet = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for option in options {
            let text = option == selected ? "✓ " + label(option) : label(option)
            sheet.addAction(UIAlertAction(title: text, style: .default) { _ in onPick(option) })
        }
        sheet.addAction(UIAlertAction(title: L("cancel"), style: .cancel))
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = sourceView ?? view
            popover.sourceRect = (sourceView ?? view).bounds
        }
        present(sheet, animated: true)
    }

    /// A short message at the bottom of the screen, like an Android toast.
    func toast(_ text: String) {
        guard let host = view.window else { return }
        let label = PaddedLabel()
        label.text = text
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .systemBackground
        label.backgroundColor = UIColor.label.withAlphaComponent(0.85)
        label.layer.cornerRadius = 16
        label.clipsToBounds = true
        label.alpha = 0
        label.preferredMaxLayoutWidth = host.bounds.width - 80
        label.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: host.centerXAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: host.leadingAnchor, constant: 24),
            label.bottomAnchor.constraint(equalTo: host.safeAreaLayoutGuide.bottomAnchor, constant: -72),
        ])
        UIView.animate(withDuration: 0.2, animations: { label.alpha = 1 }) { _ in
            UIView.animate(withDuration: 0.3, delay: 2.5, options: [], animations: { label.alpha = 0 }) { _ in
                label.removeFromSuperview()
            }
        }
    }
}

final class PaddedLabel: UILabel {
    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 16)))
    }

    override var intrinsicContentSize: CGSize {
        let size = super.intrinsicContentSize
        return CGSize(width: size.width + 32, height: size.height + 20)
    }
}

extension UITableViewCell {
    static func value1(_ title: String, _ value: String?) -> UITableViewCell {
        let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
        cell.textLabel?.text = title
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.text = value
        cell.detailTextLabel?.numberOfLines = 0
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    static func switchRow(_ title: String, on: Bool, onChange: @escaping (Bool) -> Void) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.text = title
        cell.textLabel?.numberOfLines = 0
        cell.selectionStyle = .none
        let toggle = ClosureSwitch(onChange)
        toggle.isOn = on
        cell.accessoryView = toggle
        return cell
    }
}

final class ClosureSwitch: UISwitch {
    private let onChange: (Bool) -> Void

    init(_ onChange: @escaping (Bool) -> Void) {
        self.onChange = onChange
        super.init(frame: .zero)
        addTarget(self, action: #selector(changed), for: .valueChanged)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func changed() { onChange(isOn) }
}

/// A row with a text and − / + buttons.
final class StepperCell: UITableViewCell {
    private let stepper = UIStepper()
    private let onChange: (Double) -> Void

    init(text: String, value: Double, min: Double, max: Double, step: Double = 1, onChange: @escaping (Double) -> Void) {
        self.onChange = onChange
        super.init(style: .default, reuseIdentifier: nil)
        selectionStyle = .none
        textLabel?.text = text
        textLabel?.numberOfLines = 0
        stepper.minimumValue = min
        stepper.maximumValue = max
        stepper.stepValue = step
        stepper.value = value
        stepper.addTarget(self, action: #selector(changed), for: .valueChanged)
        accessoryView = stepper
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func changed() { onChange(stepper.value) }
}

/// Seven round day buttons, Saturday first.
final class DayButtonsView: UIStackView {
    private let onToggle: (Int) -> Void

    init(selected: Set<Int>, onToggle: @escaping (Int) -> Void) {
        self.onToggle = onToggle
        super.init(frame: .zero)
        axis = .horizontal
        distribution = .fillEqually
        spacing = 6
        for iso in Format.weekOrder {
            let button = UIButton(type: .system)
            button.tag = iso
            button.setTitle(Format.shortWeekday(iso), for: .normal)
            button.titleLabel?.font = .preferredFont(forTextStyle: .footnote)
            button.titleLabel?.adjustsFontSizeToFitWidth = true
            button.layer.cornerRadius = 20
            button.heightAnchor.constraint(equalToConstant: 40).isActive = true
            let on = selected.contains(iso)
            button.backgroundColor = on ? .appGreen : .tertiarySystemFill
            button.setTitleColor(on ? .systemBackground : .label, for: .normal)
            button.accessibilityTraits = on ? [.button, .selected] : .button
            button.addTarget(self, action: #selector(tapped(_:)), for: .touchUpInside)
            addArrangedSubview(button)
        }
    }

    required init(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func tapped(_ sender: UIButton) { onToggle(sender.tag) }

    func embeddedCell() -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.selectionStyle = .none
        translatesAutoresizingMaskIntoConstraints = false
        cell.contentView.addSubview(self)
        let margins = cell.contentView.layoutMarginsGuide
        NSLayoutConstraint.activate([
            leadingAnchor.constraint(equalTo: margins.leadingAnchor),
            trailingAnchor.constraint(equalTo: margins.trailingAnchor),
            topAnchor.constraint(equalTo: margins.topAnchor),
            bottomAnchor.constraint(equalTo: margins.bottomAnchor),
        ])
        return cell
    }
}

/// Picks an alarm sound: the built-in tone, the iPhone notification sound, or an audio file the user has.
final class SoundPicker: NSObject, UIDocumentPickerDelegate {
    private weak var host: UIViewController?
    private var onPick: ((SoundChoice?) -> Void)?

    init(host: UIViewController) {
        self.host = host
    }

    /// [allowDefault] adds "Default (from settings)" (nil), for a single alarm.
    func pick(title: String, current: SoundChoice?, builtIn: SoundChoice, allowDefault: Bool, from view: UIView?,
              onPick: @escaping (SoundChoice?) -> Void) {
        guard let host = host else { return }
        enum Option: Equatable { case fromSettings, builtIn, system, file }
        var options: [Option] = allowDefault ? [.fromSettings] : []
        options += [.builtIn, .system, .file]
        let selected: Option
        switch current {
        case nil: selected = .fromSettings
        case .systemDefault?: selected = .system
        case .builtIn?: selected = .builtIn
        case .custom?: selected = .file
        }
        let builtInName = Format.soundName(builtIn, defaultName: "")
        host.choose(title, options: options, selected: selected, label: { option in
            switch option {
            case .fromSettings: return L("sound_default_from_settings")
            case .builtIn: return builtInName
            case .system: return L("sound_system_default")
            case .file:
                if case let .custom(_, name)? = current { return L("sound_file") + " (" + name + ")" }
                return L("sound_file")
            }
        }, from: view) { [weak self] option in
            switch option {
            case .fromSettings: onPick(nil)
            case .builtIn: onPick(builtIn)
            case .system: onPick(.systemDefault)
            case .file: self?.openFiles(onPick)
            }
        }
    }

    private func openFiles(_ onPick: @escaping (SoundChoice?) -> Void) {
        self.onPick = onPick
        // iOS 13 API; .import copies the file into the app's inbox.
        let picker = UIDocumentPickerViewController(documentTypes: [kUTTypeAudio as String], in: .import)
        picker.delegate = self
        host?.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first, let onPick = onPick else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let result = try? SoundLibrary.importSound(from: url)
            DispatchQueue.main.async {
                if let choice = result {
                    onPick(choice)
                    self.host?.toast(L("sound_file_hint"))
                } else {
                    self.host?.toast(L("sound_import_failed"))
                }
            }
        }
    }
}
