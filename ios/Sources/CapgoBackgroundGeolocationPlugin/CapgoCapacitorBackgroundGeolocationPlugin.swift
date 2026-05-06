// swiftlint:disable file_length
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
        if let sourceInfo = location.sourceInformation {
            simulated = sourceInfo.isSimulatedBySoftware
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
// swiftlint:disable:next type_body_length
public class BackgroundGeolocation: CAPPlugin, CLLocationManagerDelegate, CAPBridgedPlugin {
    private let pluginVersion: String = "8.0.32"
    public let identifier = "BackgroundGeolocationPlugin"
    public let jsName = "BackgroundGeolocation"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnCallback),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setPlannedRoute", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setupGeofencing", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeGeofence", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeAllGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getMonitoredGeofences", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPluginVersion", returnType: CAPPluginReturnPromise)
    ]
    private var locationManager: CLLocationManager?
    private var geofenceLocationManager: CLLocationManager?
    private var created: Date?
    private var allowStale: Bool = false
    private var isUpdatingLocation: Bool = false
    private var activeCallbackId: String?
    private var audioPlayer: AVAudioPlayer?
    private var plannedRoute: [[Double]] = []
    private var isOffRoute: Bool = true
    private var distanceThreshold: Double = 50.0 // Default distance threshold in meters
    private var geofenceBackendUrl: URL?
    private var geofenceNotifyOnEntry: Bool = true
    private var geofenceNotifyOnExit: Bool = true
    private var geofencePayload: [String: Any] = [:]
    private var pendingGeofenceSetupCall: CAPPluginCall?
    private var lastGeofenceTransition: [String: String] = [:]

    private let geofenceUrlKey = "CapgoBackgroundGeolocation.geofence.url"
    private let geofenceNotifyOnEntryKey = "CapgoBackgroundGeolocation.geofence.notifyOnEntry"
    private let geofenceNotifyOnExitKey = "CapgoBackgroundGeolocation.geofence.notifyOnExit"
    private let geofencePayloadKey = "CapgoBackgroundGeolocation.geofence.payload"
    private let geofenceRegionPrefix = "CapgoBackgroundGeolocation.geofence.region."

    // Earth radius in meters for distance calculations
    private static let earthRadiusMeters: Double = 6371000.0

    @objc override public func load() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        restoreGeofenceConfiguration()
        DispatchQueue.main.async {
            _ = self.ensureGeofenceLocationManager()
        }
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
            guard let manager = self.locationManager else {
                return call.reject("Failed to create location manager")
            }
            manager.delegate = self
            self.created = Date()

            let background = call.getString("backgroundMessage") != nil
            self.allowStale = call.getBool("stale") ?? false
            self.activeCallbackId = call.callbackId

            self.configureLocationManager(manager, call: call, background: background)

            if call.getBool("requestPermissions") != false {
                if self.handlePermissions(manager, background: background) {
                    return
                }
            }
            return self.startUpdatingLocation()
        }
    }

    private func configureLocationManager(_ manager: CLLocationManager, call: CAPPluginCall, background: Bool) {
        let externalPower = [
            .full,
            .charging
        ].contains(UIDevice.current.batteryState)
        manager.desiredAccuracy = (
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
        manager.distanceFilter = distanceFilter ?? kCLDistanceFilterNone
        manager.allowsBackgroundLocationUpdates = background
        manager.showsBackgroundLocationIndicator = background
        manager.pausesLocationUpdatesAutomatically = false
    }

    private func handlePermissions(_ manager: CLLocationManager, background: Bool) -> Bool {
        let status = manager.authorizationStatus
        if [
            .notDetermined,
            .denied,
            .restricted
        ].contains(status) {
            if background {
                manager.requestAlwaysAuthorization()
            } else {
                manager.requestWhenInUseAuthorization()
            }
            return true
        }
        if background && status == .authorizedWhenInUse {
            // Attempt to escalate.
            manager.requestAlwaysAuthorization()
        }
        return false
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
                UIApplication.shared.open(settingsUrl, completionHandler: { (success) in
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

    @objc func setupGeofencing(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.pendingGeofenceSetupCall != nil {
                return call.reject("A geofence permission request is already in progress", "PERMISSION_REQUEST_IN_PROGRESS")
            }
            guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else {
                return call.reject("Geofencing is not available on this device", "NOT_AVAILABLE")
            }
            var backendUrl: URL?
            if let urlString = call.getString("url"), !urlString.isEmpty {
                guard let url = URL(string: urlString),
                      let scheme = url.scheme?.lowercased(),
                      ["http", "https"].contains(scheme) else {
                    return call.reject("Given url is not valid")
                }
                backendUrl = url
            }
            let payload = call.getObject("payload") ?? [:]
            guard JSONSerialization.isValidJSONObject(payload) else {
                return call.reject("Payload must be valid JSON")
            }

            self.geofenceBackendUrl = backendUrl
            self.geofenceNotifyOnEntry = call.getBool("notifyOnEntry") ?? true
            self.geofenceNotifyOnExit = call.getBool("notifyOnExit") ?? true
            self.geofencePayload = payload
            self.persistGeofenceConfiguration()

            let manager = self.ensureGeofenceLocationManager()
            let status = manager.authorizationStatus
            if status == .authorizedAlways {
                return call.resolve()
            }
            if call.getBool("requestPermissions") == false {
                return call.reject("Always location permission is required for geofencing", "NOT_AUTHORIZED")
            }
            if [.denied, .restricted].contains(status) {
                return call.reject("Always location permission is required for geofencing", "NOT_AUTHORIZED")
            }
            self.pendingGeofenceSetupCall = call
            manager.requestAlwaysAuthorization()
        }
    }

    @objc func addGeofence(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let manager = self.ensureGeofenceLocationManager()
            guard self.geofenceAvailable(manager) else {
                return call.reject("Always location permission is required for geofencing", "NOT_AUTHORIZED")
            }
            guard let latitude = call.getDouble("latitude") else {
                return call.reject("Latitude is required")
            }
            guard let longitude = call.getDouble("longitude") else {
                return call.reject("Longitude is required")
            }
            guard let identifier = call.getString("identifier"), !identifier.isEmpty else {
                return call.reject("Identifier is required")
            }
            let radius = call.getDouble("radius") ?? 50.0
            guard CLLocationCoordinate2DIsValid(CLLocationCoordinate2D(latitude: latitude, longitude: longitude)) else {
                return call.reject("Invalid latitude or longitude")
            }
            guard radius > 0 else {
                return call.reject("Radius must be greater than 0")
            }
            let maximumDistance = manager.maximumRegionMonitoringDistance
            guard maximumDistance <= 0 || radius <= maximumDistance else {
                return call.reject("Radius exceeds the maximum supported region monitoring distance")
            }
            let notifyOnEntry = call.getBool("notifyOnEntry") ?? self.geofenceNotifyOnEntry
            let notifyOnExit = call.getBool("notifyOnExit") ?? self.geofenceNotifyOnExit
            guard notifyOnEntry || notifyOnExit else {
                return call.reject("At least one transition must be enabled")
            }
            let payload = call.getObject("payload") ?? [:]
            guard JSONSerialization.isValidJSONObject(payload) else {
                return call.reject("Payload must be valid JSON")
            }

            let center = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
            let region = CLCircularRegion(center: center, radius: radius, identifier: identifier)
            region.notifyOnEntry = notifyOnEntry
            region.notifyOnExit = notifyOnExit
            manager.startMonitoring(for: region)
            self.persistGeofenceRegion(region, payload: payload)
            call.resolve()
        }
    }

    @objc func removeGeofence(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let identifier = call.getString("identifier"), !identifier.isEmpty else {
                return call.reject("Identifier is required")
            }
            let manager = self.ensureGeofenceLocationManager()
            guard self.persistedGeofenceRegionIds().contains(identifier) else {
                return call.reject("Could not find a region with that identifier", "NOT_FOUND")
            }
            guard let region = manager.monitoredRegions.first(where: { $0.identifier == identifier && $0 is CLCircularRegion }) else {
                self.removePersistedGeofenceRegion(identifier)
                self.lastGeofenceTransition.removeValue(forKey: identifier)
                return call.resolve()
            }
            manager.stopMonitoring(for: region)
            self.removePersistedGeofenceRegion(identifier)
            self.lastGeofenceTransition.removeValue(forKey: identifier)
            call.resolve()
        }
    }

    @objc func removeAllGeofences(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let manager = self.ensureGeofenceLocationManager()
            let identifiers = self.persistedGeofenceRegionIds()
            for region in manager.monitoredRegions where region is CLCircularRegion && identifiers.contains(region.identifier) {
                manager.stopMonitoring(for: region)
            }
            for identifier in identifiers {
                self.removePersistedGeofenceRegion(identifier)
                self.lastGeofenceTransition.removeValue(forKey: identifier)
            }
            call.resolve()
        }
    }

    @objc func getMonitoredGeofences(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let manager = self.ensureGeofenceLocationManager()
            let identifiers = self.persistedGeofenceRegionIds()
            let regions = manager.monitoredRegions.compactMap { region -> String? in
                region is CLCircularRegion && identifiers.contains(region.identifier) ? region.identifier : nil
            }.sorted()
            call.resolve(["regions": regions])
        }
    }

    private func ensureGeofenceLocationManager() -> CLLocationManager {
        if let manager = geofenceLocationManager {
            return manager
        }
        let manager = CLLocationManager()
        manager.delegate = self
        manager.pausesLocationUpdatesAutomatically = false
        geofenceLocationManager = manager
        return manager
    }

    private func geofenceAvailable(_ manager: CLLocationManager) -> Bool {
        CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) && manager.authorizationStatus == .authorizedAlways
    }

    private func persistGeofenceConfiguration() {
        let defaults = UserDefaults.standard
        defaults.set(geofenceBackendUrl?.absoluteString, forKey: geofenceUrlKey)
        defaults.set(geofenceNotifyOnEntry, forKey: geofenceNotifyOnEntryKey)
        defaults.set(geofenceNotifyOnExit, forKey: geofenceNotifyOnExitKey)
        if JSONSerialization.isValidJSONObject(geofencePayload),
           let data = try? JSONSerialization.data(withJSONObject: geofencePayload) {
            defaults.set(data, forKey: geofencePayloadKey)
        } else {
            defaults.removeObject(forKey: geofencePayloadKey)
        }
    }

    private func restoreGeofenceConfiguration() {
        let defaults = UserDefaults.standard
        if let urlString = defaults.string(forKey: geofenceUrlKey), !urlString.isEmpty {
            geofenceBackendUrl = URL(string: urlString)
        }
        if defaults.object(forKey: geofenceNotifyOnEntryKey) != nil {
            geofenceNotifyOnEntry = defaults.bool(forKey: geofenceNotifyOnEntryKey)
        }
        if defaults.object(forKey: geofenceNotifyOnExitKey) != nil {
            geofenceNotifyOnExit = defaults.bool(forKey: geofenceNotifyOnExitKey)
        }
        if let data = defaults.data(forKey: geofencePayloadKey),
           let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            geofencePayload = payload
        }
    }

    private func persistGeofenceRegion(_ region: CLCircularRegion, payload: [String: Any]) {
        let data: [String: Any] = [
            "latitude": region.center.latitude,
            "longitude": region.center.longitude,
            "radius": region.radius,
            "payload": payload
        ]
        if JSONSerialization.isValidJSONObject(data),
           let encoded = try? JSONSerialization.data(withJSONObject: data) {
            UserDefaults.standard.set(encoded, forKey: geofenceRegionPrefix + region.identifier)
        }
    }

    private func persistedGeofenceRegion(_ identifier: String) -> [String: Any] {
        guard let data = UserDefaults.standard.data(forKey: geofenceRegionPrefix + identifier),
              let decoded = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return decoded
    }

    private func removePersistedGeofenceRegion(_ identifier: String) {
        UserDefaults.standard.removeObject(forKey: geofenceRegionPrefix + identifier)
    }

    private func persistedGeofenceRegionIds() -> Set<String> {
        Set(UserDefaults.standard.dictionaryRepresentation().keys.compactMap { key in
            guard key.hasPrefix(geofenceRegionPrefix) else { return nil }
            return String(key.dropFirst(geofenceRegionPrefix.count))
        })
    }

    private func geofenceTransitionData(for region: CLRegion, enter: Bool) -> [String: Any] {
        let persistedRegion = persistedGeofenceRegion(region.identifier)
        let regionPayload = persistedRegion["payload"] as? [String: Any] ?? [:]
        var payload = geofencePayload
        for (key, value) in regionPayload {
            payload[key] = value
        }

        var data = payload
        data["identifier"] = region.identifier
        data["transition"] = enter ? "enter" : "exit"
        data["enter"] = enter
        if let circularRegion = region as? CLCircularRegion {
            data["latitude"] = circularRegion.center.latitude
            data["longitude"] = circularRegion.center.longitude
            data["radius"] = circularRegion.radius
        } else {
            data["latitude"] = persistedRegion["latitude"]
            data["longitude"] = persistedRegion["longitude"]
            data["radius"] = persistedRegion["radius"]
        }
        data["payload"] = payload
        return data
    }

    private func handleGeofenceTransition(for region: CLRegion, enter: Bool) {
        if let circularRegion = region as? CLCircularRegion {
            if enter && !circularRegion.notifyOnEntry {
                return
            }
            if !enter && !circularRegion.notifyOnExit {
                return
            }
        }

        let transition = enter ? "enter" : "exit"
        if lastGeofenceTransition[region.identifier] == transition {
            return
        }
        lastGeofenceTransition[region.identifier] = transition

        let data = geofenceTransitionData(for: region, enter: enter)
        notifyListeners("geofenceTransition", data: data, retainUntilConsumed: true)
        postGeofenceTransition(data)
    }

    private func postGeofenceTransition(_ data: [String: Any]) {
        guard let backendUrl = geofenceBackendUrl,
              JSONSerialization.isValidJSONObject(data),
              let body = try? JSONSerialization.data(withJSONObject: data) else {
            return
        }
        var request = URLRequest(url: backendUrl)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = body

        var backgroundTask = UIBackgroundTaskIdentifier.invalid
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "CapgoGeofenceTransition") {
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
        }
        URLSession.shared.dataTask(with: request) { _, _, _ in
            if backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(backgroundTask)
                backgroundTask = .invalid
            }
        }.resume()
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

        return BackgroundGeolocation.earthRadiusMeters * ccc
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
        guard manager === locationManager,
              let callbackId = activeCallbackId,
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
        guard manager === locationManager,
              let location = locations.last,
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
        if let pendingCall = pendingGeofenceSetupCall {
            if status == .authorizedAlways {
                pendingGeofenceSetupCall = nil
                pendingCall.resolve()
            } else if status == .denied || status == .restricted || status == .authorizedWhenInUse {
                pendingGeofenceSetupCall = nil
                pendingCall.reject("Always location permission is required for geofencing", "NOT_AUTHORIZED")
            }
        }

        // If this method is called before the user decides on a permission, as
        // it is on iOS 14 when the permissions dialog is presented, we ignore
        // it.
        if manager === locationManager && status != .notDetermined {
            startUpdatingLocation()
        }
    }

    public func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
        guard manager === geofenceLocationManager else { return }
        manager.requestState(for: region)
    }

    public func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        guard manager === geofenceLocationManager, let region = region else { return }
        let nsError = error as NSError
        notifyListeners(
            "geofenceError",
            data: [
                "identifier": region.identifier,
                "message": error.localizedDescription,
                "code": nsError.code,
                "domain": nsError.domain
            ],
            retainUntilConsumed: true
        )
    }

    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard manager === geofenceLocationManager, region is CLCircularRegion else { return }
        handleGeofenceTransition(for: region, enter: true)
    }

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard manager === geofenceLocationManager, region is CLCircularRegion else { return }
        handleGeofenceTransition(for: region, enter: false)
    }

    public func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        guard manager === geofenceLocationManager, region is CLCircularRegion else { return }
        if state == .inside {
            handleGeofenceTransition(for: region, enter: true)
        }
    }

    @objc func getPluginVersion(_ call: CAPPluginCall) {
        call.resolve(["version": self.pluginVersion])
    }

}
