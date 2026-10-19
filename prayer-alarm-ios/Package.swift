// swift-tools-version:5.5
// Builds the app's Foundation-only core (prayer times, alarm rules, scheduling plan, storage)
// so its tests run with `swift test` on a Mac, without the simulator.
import PackageDescription

let package = Package(
    name: "PrayerAlarmCore",
    platforms: [.iOS(.v13), .macOS(.v10_15)],
    products: [.library(name: "PrayerAlarmCore", targets: ["PrayerAlarmCore"])],
    targets: [
        .target(name: "PrayerAlarmCore", path: "PrayerAlarm/Core"),
        .testTarget(name: "CoreTests", dependencies: ["PrayerAlarmCore"], path: "Tests/CoreTests"),
    ]
)
