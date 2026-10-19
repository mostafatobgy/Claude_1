import BackgroundTasks
import CoreLocation
import Foundation

/// Lets iOS wake the app now and then to refill the window of scheduled alarms.
enum BackgroundRefresh {
    /// Must match BGTaskSchedulerPermittedIdentifiers in Info.plist.
    static let identifier = "prayeralarm.refresh"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            schedule()
            task.expirationHandler = { task.setTaskCompleted(success: false) }
            DispatchQueue.main.async {
                AlarmScheduler.shared.rescheduleAll { task.setTaskCompleted(success: true) }
            }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }
}

/// One-shot "where am I" for the prayer time calculation.
final class LocationFetcher: NSObject, CLLocationManagerDelegate {
    enum Failure: Error { case denied, failed }

    private let manager = CLLocationManager()
    private var completion: ((Result<CLLocation, Failure>) -> Void)?

    func fetch(_ completion: @escaping (Result<CLLocation, Failure>) -> Void) {
        self.completion = completion
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        switch CLLocationManager.authorizationStatus() {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: finish(.failure(.denied))
        default: manager.requestLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        guard completion != nil else { return }
        switch status {
        case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
        case .denied, .restricted: finish(.failure(.denied))
        default: break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let location = locations.last { finish(.success(location)) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(.failure(.failed))
    }

    private func finish(_ result: Result<CLLocation, Failure>) {
        let done = completion
        completion = nil
        done?(result)
    }
}
