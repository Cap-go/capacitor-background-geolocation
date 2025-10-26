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
public class BackgroundGeolocation: CAPPlugin, CLLocationManagerDelegate, CAPBridgedPlugin {
    private let PLUGIN_VERSION: String = "7.2.0"
    public let identifier = "BackgroundGeolocationPlugin"
    public let jsName = "BackgroundGeolocation"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPlannedRoute", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
    ]
    private var locationManager: CLLocationManager?
    private var created: Date?
    private var allowStale: Bool = false
    private var isUpdatingLocation: Bool = false
    private var activeCallbackId: String?
    private var audioPlayer: AVAudioPlayer?
    private var plannedRoute: [[Double]] = []
    private var isOffRoute: Bool = true
    private var distanceThreshold: Double = 50.0 // Default distance threshold in meters

    // Earth radius in meters for distance calculations
    private static let EARTH_RADIUS_M: Double = 6371000.0

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

    @objc func setPlannedRoute(_ call: CAPPluginCall) {
        DispatchQueue.global(qos: .background).async { [weak self] in
            guard let self = self else { return }

            guard let soundFile = call.getString("soundFile") else {
                call.reject("Sound file is required")
                return
            }

            let routeArray = call.getArray("route", Any.self) ?? []
            var route: [[Double]] = []

            for routePoint in routeArray {
                if let pointArray = routePoint as? [Double], pointArray.count == 2 {
                    route.append(pointArray)
                }
            }

            let distance = call.getDouble("distance") ?? 50.0

            let assetPath = "public/" + soundFile
            let assetPathSplit = assetPath.components(separatedBy: ".")
            guard let url = Bundle.main.url(forResource: assetPathSplit[0], withExtension: assetPathSplit[1]) else {
                call.reject("Sound file not found: \(assetPath)")
                return
            }

            do {
                self.audioPlayer?.stop()
                self.audioPlayer = nil
                self.audioPlayer = try AVAudioPlayer(contentsOf: url)

                // Store route configuration
                self.plannedRoute = route
                self.distanceThreshold = distance
                self.isOffRoute = true

                call.resolve()
            } catch {
                call.reject("Could not load the sound file: \(error.localizedDescription)")
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

    private func toRadians(_ degrees: Double) -> Double {
        return degrees * Double.pi / 180.0
    }

    private func haversine(_ point1: [Double], _ point2: [Double]) -> Double {
        let lon1 = point1[0]
        let lat1 = point1[1]
        let lon2 = point2[0]
        let lat2 = point2[1]

        let dLat = toRadians(lat2 - lat1)
        let dLon = toRadians(lon2 - lon1)

        let aaa = sin(dLat / 2) * sin(dLat / 2) +
            cos(toRadians(lat1)) * cos(toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)

        let ccc = 2 * atan2(sqrt(aaa), sqrt(1 - aaa))

        return BackgroundGeolocation.EARTH_RADIUS_M * ccc
    }

    private func distancePointToLineSegment(_ point: [Double], _ lineStart: [Double], _ lineEnd: [Double]) -> Double {
        // Calculate the distances between the three points using Haversine
        let distAB = haversine(point, lineStart)
        let distAC = haversine(point, lineEnd)
        let distBC = haversine(lineStart, lineEnd)

        // Handle the edge case where the line segment is a single point
        if distBC == 0 {
            return distAB
        }

        // Check if the angles at the line segment's endpoints are obtuse.
        // We use the Law of Cosines (c^2 = a^2 + b^2 - 2ab*cos(C))
        // If cos(C) < 0, the angle is obtuse.

        // Angle at B (lineStart)
        let epsilon = Double.ulpOfOne
        let cosB = (pow(distAB, 2) + pow(distBC, 2) - pow(distAC, 2)) / (2 * distAB * distBC + epsilon)
        if cosB < 0 {
            return distAB
        }

        // Angle at C (lineEnd)
        let cosC = (pow(distAC, 2) + pow(distBC, 2) - pow(distAB, 2)) / (2 * distAC * distBC + epsilon)
        if cosC < 0 {
            return distAC
        }

        // If both angles are acute, the closest point is on the line segment itself.
        // We can calculate the distance (height of the triangle) using its area.

        // 1. Calculate the semi-perimeter of the triangle ABC
        let semi = (distAB + distAC + distBC) / 2

        // 2. Calculate the area using Heron's formula
        let area = sqrt(max(0, semi * (semi - distAB) * (semi - distAC) * (semi - distBC)))

        // 3. The distance is the height of the triangle from point A to the base BC
        // Area = 0.5 * base * height  =>  height = 2 * Area / base
        return (2 * area) / (distBC + epsilon)
    }

    private func distancePointToRoute(_ point: [Double]) -> Double {
        // If the route has less than 2 points, we can't form a segment.
        if plannedRoute.count < 2 {
            if plannedRoute.count == 1 {
                return haversine(point, plannedRoute[0])
            }
            return Double.infinity // No line segments to measure against
        }

        var minDistance = Double.infinity

        for pointIndex in 0..<(plannedRoute.count - 1) {
            let lineStart = plannedRoute[pointIndex]
            let lineEnd = plannedRoute[pointIndex + 1]
            let distance = distancePointToLineSegment(point, lineStart, lineEnd)
            if distance < minDistance {
                minDistance = distance
            }
        }

        return minDistance
    }

    private func checkRouteDeviation(_ location: CLLocation) {
        guard audioPlayer != nil && plannedRoute.count > 0 else { return }

        let currentPoint = [location.coordinate.longitude, location.coordinate.latitude]
        let offRoute = distancePointToRoute(currentPoint) > distanceThreshold

        if offRoute && !isOffRoute {
            audioPlayer?.play()
        }

        isOffRoute = offRoute
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
            checkRouteDeviation(location)
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

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": self.PLUGIN_VERSION])
    }

}
