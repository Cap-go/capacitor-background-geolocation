import Capacitor
import Foundation
import UIKit
import CoreLocation
import AVFoundation

// Avoids a bewildering type warning.
let null = Optional<Double>.none as Any

func formatLocation(_ location: CLLocation) -> PluginCallResultData {
    var simulated = false
    if #available(iOS 15, *) {
        // Prior to iOS 15, it was not possible to detect simulated locations.
        // But in general, it is very difficult to simulate locations on iOS in
        // production.
        if location.sourceInformation != nil {
            simulated = location.sourceInformation!.isSimulatedBySoftware
        }
    }
    return [
        "latitude": location.coordinate.latitude,
        "longitude": location.coordinate.longitude,
        "accuracy": location.horizontalAccuracy,
        "altitude": location.altitude,
        "altitudeAccuracy": location.verticalAccuracy,
        "simulated": simulated,
        "speed": location.speed < 0 ? null : location.speed,
        "bearing": location.course < 0 ? null : location.course,
        "time": NSNumber(
            value: Int(
                location.timestamp.timeIntervalSince1970 * 1000
            )
        )
    ]
}

@objc(BackgroundGeolocation)
public class BackgroundGeolocation: CAPPlugin, CLLocationManagerDelegate {
    private var locationManager: CLLocationManager?
    private var created: Date?
    private var allowStale: Bool = false
    private var isUpdatingLocation: Bool = false
    private var activeCallbackId: String?
    private var audioPlayer: AVAudioPlayer?

    @objc override public func load() {
        UIDevice.current.isBatteryMonitoringEnabled = true
    }

    @objc func start(_ call: CAPPluginCall) {
        call.keepAlive = true

        // CLLocationManager requires main thread
        DispatchQueue.main.async {
            // Check if already started
            if self.locationManager != nil {
                return call.reject("Location tracking already started", "ALREADY_STARTED")
            }
            // Create fresh location manager and initialize date
            self.locationManager = CLLocationManager()
            self.locationManager!.delegate = self
            self.created = Date()

            let background = call.getString("backgroundMessage") != nil
            self.allowStale = call.getBool("stale") ?? false
            self.activeCallbackId = call.callbackId

            let externalPower = [
                .full,
                .charging
            ].contains(UIDevice.current.batteryState)
            self.locationManager!.desiredAccuracy = (
                externalPower
                    ? kCLLocationAccuracyBestForNavigation
                    : kCLLocationAccuracyBest
            )
            var distanceFilter = call.getDouble("distanceFilter")
            // It appears that setting manager.distanceFilter to 0 can prevent
            // subsequent location updates. See issue #88.
            if distanceFilter == nil || distanceFilter == 0 {
                distanceFilter = kCLDistanceFilterNone
            }
            self.locationManager!.distanceFilter = distanceFilter!
            self.locationManager!.allowsBackgroundLocationUpdates = background
            self.locationManager!.showsBackgroundLocationIndicator = background
            self.locationManager!.pausesLocationUpdatesAutomatically = false

            if call.getBool("requestPermissions") != false {
                let status = CLLocationManager.authorizationStatus()
                if [
                    .notDetermined,
                    .denied,
                    .restricted
                ].contains(status) {
                    return (
                        background
                            ? self.locationManager!.requestAlwaysAuthorization()
                            : self.locationManager!.requestWhenInUseAuthorization()
                    )
                }
                if background && status == .authorizedWhenInUse {
                    // Attempt to escalate.
                    self.locationManager!.requestAlwaysAuthorization()
                }
            }
            return self.startUpdatingLocation()
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        // CLLocationManager requires main thread
        DispatchQueue.main.async {
            self.stopUpdatingLocation()

            self.locationManager?.delegate = nil
            self.locationManager = nil
            self.created = nil

            if let callbackId = self.activeCallbackId {
                if let savedCall = self.bridge?.savedCall(withID: callbackId) {
                    self.bridge?.releaseCall(savedCall)
                }
                self.activeCallbackId = nil
            }
            return call.resolve()
        }
    }

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let settingsUrl = URL(
                string: UIApplication.openSettingsURLString
            ) else {
                return call.reject("No link to settings available")
            }

            if UIApplication.shared.canOpenURL(settingsUrl) {
                UIApplication.shared.open(settingsUrl, completionHandler: {
                    (success) in
                    if success {
                        return call.resolve()
                    } else {
                        return call.reject("Failed to open settings")
                    }
                })
            } else {
                return call.reject("Cannot open settings")
            }
        }
    }

    private func startUpdatingLocation() {
        // Avoid unnecessary calls to startUpdatingLocation, which can
        // result in extraneous invocations of didFailWithError.
        if !isUpdatingLocation, let manager = locationManager {
            manager.startUpdatingLocation()
            isUpdatingLocation = true
        }
    }

    private func stopUpdatingLocation() {
        if isUpdatingLocation, let manager = locationManager {
            manager.stopUpdatingLocation()
            isUpdatingLocation = false
        }
    }

    private func isLocationValid(_ location: CLLocation) -> Bool {
        guard let created = created else { return allowStale }
        return (
            allowStale ||
                location.timestamp >= created
        )
    }

    @objc func playSound(_ call: CAPPluginCall) {
        // Use a background queue for audio loading to avoid blocking the main thread
        DispatchQueue.global(qos: .background).async { [weak self] in
            guard let self = self else { return }

            let assetPath = "public/" + (call.getString("soundFile") ?? "")
            let assetPathSplit = assetPath.components(separatedBy: ".")
            guard let url = Bundle.main.url(forResource: assetPathSplit[0], withExtension: assetPathSplit[1]) else {
                call.reject("Sound file not found: \(assetPath)")
                return
            }

            do {
                // Initialize the audio player
                self.audioPlayer = try AVAudioPlayer(contentsOf: url)
                // Play the sound
                self.audioPlayer?.play()
            } catch {
                call.reject("Could not play the sound file: \(error.localizedDescription)")
            }
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didFailWithError error: Error
    ) {
        guard let callbackId = activeCallbackId,
              let call = self.bridge?.savedCall(withID: callbackId) else {
            return
        }

        if let clErr = error as? CLError {
            if clErr.code == .locationUnknown {
                // This error is sometimes sent by the manager if
                // it cannot get a fix immediately.
                return
            } else if clErr.code == .denied {
                stopUpdatingLocation()
                return call.reject(
                    "Permission denied.",
                    "NOT_AUTHORIZED"
                )
            }
        }
        return call.reject(error.localizedDescription, nil, error)
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let location = locations.last,
              let callbackId = activeCallbackId,
              let call = self.bridge?.savedCall(withID: callbackId) else {
            return
        }

        if isLocationValid(location) {
            return call.resolve(formatLocation(location))
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didChangeAuthorization status: CLAuthorizationStatus
    ) {
        // If this method is called before the user decides on a permission, as
        // it is on iOS 14 when the permissions dialog is presented, we ignore
        // it.
        if status != .notDetermined {
            startUpdatingLocation()
        }
    }
}
