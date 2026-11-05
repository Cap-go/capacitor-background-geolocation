import CoreLocation
import Foundation
import Network
import UIKit

/// Receives geofence transitions whether or not the plugin is loaded.
///
/// iOS relaunches a terminated app in the background to deliver a region
/// transition, but Capacitor only creates plugins once a view controller loads
/// its view. Scene-based apps create that view controller when a scene
/// connects, which a background launch may never do, so a transition delivered
/// to a location manager owned by the plugin is lost. The relay is created from
/// the app delegate instead, owns the one location manager that monitors
/// geofences, and works the way the Android receiver does: it builds the
/// transition from the configuration the plugin persisted, queues it on disk,
/// POSTs it to the configured URL, and hands it to the plugin for JavaScript
/// once one is attached.
///
/// Call `GeofenceRelay.shared.start()` from
/// `application(_:didFinishLaunchingWithOptions:)`.
@objc public final class GeofenceRelay: NSObject, CLLocationManagerDelegate {
    @objc public static let shared = GeofenceRelay()

    static let urlKey = "CapgoBackgroundGeolocation.geofence.url"
    static let headersKey = "CapgoBackgroundGeolocation.geofence.headers"
    static let payloadKey = "CapgoBackgroundGeolocation.geofence.payload"
    static let regionPrefix = "CapgoBackgroundGeolocation.geofence.region."
    private static let queueKey = "CapgoBackgroundGeolocation.geofence.queue"
    private static let waitingKey = "CapgoBackgroundGeolocation.geofence.waiting"
    // A queue that cannot drain, behind a token never replaced or a server that keeps failing,
    // stays bounded: the newest 100 transitions, none older than a week.
    static let maxQueued = 100
    static let maxQueuedAgeMs = 7 * 24 * 60 * 60 * 1000
    // iOS reports a crossing to both didEnterRegion and didDetermineState; the same transition
    // for a region again within this window is that second report, not a new crossing.
    static let repeatWindowMs = 60 * 1000

    let manager: CLLocationManager
    // Every loaded plugin instance, one per Capacitor bridge, each passing transitions to its
    // own JavaScript and resolving its own pending geofence calls.
    private let plugins = NSHashTable<BackgroundGeolocation>.weakObjects()
    private var lastTransition: [String: (transition: String, at: Int)] = [:]
    private var sending = false
    // A flush asked for while a POST is in flight; it runs once that POST is done if the POST
    // was refused and the headers have changed since, such as a token replaced mid-send.
    private var flushAgain = false
    private let network = NWPathMonitor()
    private var networkUp: Bool?

    private override init() {
        manager = CLLocationManager()
        super.init()
        manager.delegate = self
        manager.pausesLocationUpdatesAutomatically = false

        // A queued transition waits for a send; the app may stay running for hours, so the
        // network coming back and the app coming to the screen send it too. The monitor reports
        // every change of interface, so only a return from no network counts.
        network.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            DispatchQueue.main.async {
                guard let self else { return }
                let cameBack = up && self.networkUp != true
                self.networkUp = up
                if cameBack { self.flush() }
            }
        }
        network.start(queue: DispatchQueue(label: "CapgoGeofenceRelay.network"))
        NotificationCenter.default.addObserver(
            self, selector: #selector(appBecameActive), name: UIApplication.didBecomeActiveNotification, object: nil
        )
    }

    /// Sends what is queued when the app comes to the screen.
    @objc private func appBecameActive() {
        flush()
    }

    /// Creates the location manager, so iOS can deliver the transition it
    /// relaunched the app for, and sends anything still queued.
    @objc public func start() {
        flush()
    }

    /// Called by the plugin when it loads: it receives transitions from now on,
    /// and the ones that came in while no plugin was loaded, kept on disk in
    /// case iOS ended that background launch before the WebView came up.
    func attach(_ plugin: BackgroundGeolocation) -> CLLocationManager {
        plugins.add(plugin)
        let held = waiting
        waiting = []
        held.compactMap { jsonObject($0) }.forEach { plugin.relayGeofenceTransition($0) }
        flush()
        return manager
    }

    /// Forgets the last transition of a region the plugin stopped monitoring.
    func forget(_ identifier: String) {
        lastTransition.removeValue(forKey: identifier)
    }

    /// Drops every queued transition: for a new endpoint, which should not
    /// receive the old one's, or for a new account, through `clearGeofenceQueue`.
    func clearQueue() {
        queue = []
    }

    // MARK: CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        handle(region, enter: true)
    }

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        handle(region, enter: false)
    }

    public func locationManager(
        _ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion
    ) {
        if state == .inside {
            handle(region, enter: true)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
        plugins.allObjects.forEach { $0.locationManager(manager, didStartMonitoringFor: region) }
    }

    public func locationManager(
        _ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error
    ) {
        plugins.allObjects.forEach { $0.locationManager(manager, monitoringDidFailFor: region, withError: error) }
    }

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        plugins.allObjects.forEach { $0.locationManager(manager, didChangeAuthorization: status) }
    }

    // MARK: Transitions

    /// Turns a region callback into a transition for JavaScript and the queue.
    private func handle(_ region: CLRegion, enter: Bool) {
        // iOS reports every region the app monitors to every location manager;
        // only the ones this plugin added, which it persisted, are its own.
        guard let circular = region as? CLCircularRegion,
              UserDefaults.standard.object(forKey: GeofenceRelay.regionPrefix + region.identifier) != nil else {
            return
        }
        if enter && !circular.notifyOnEntry { return }
        if !enter && !circular.notifyOnExit { return }
        let transition = enter ? "enter" : "exit"
        let now = GeofenceRelay.nowMs()
        if GeofenceRelay.isRepeat(transition, at: now, after: lastTransition[region.identifier]) { return }
        lastTransition[region.identifier] = (transition, now)

        let data = transitionData(for: region, enter: enter)
        let attached = plugins.allObjects
        if attached.isEmpty {
            if JSONSerialization.isValidJSONObject(data), let body = try? JSONSerialization.data(withJSONObject: data) {
                waiting = GeofenceRelay.trimmed(waiting + [body], now: now)
            }
        } else {
            attached.forEach { $0.relayGeofenceTransition(data) }
        }
        enqueue(data)
    }

    /// Whether this transition is the second report of the last one: the same
    /// transition for the region within `repeatWindowMs`.
    static func isRepeat(_ transition: String, at now: Int, after last: (transition: String, at: Int)?) -> Bool {
        guard let last else { return false }
        return last.transition == transition && now - last.at < repeatWindowMs
    }

    /// The transition as JavaScript and the POST receive it, from the persisted setup.
    private func transitionData(for region: CLRegion, enter: Bool) -> [String: Any] {
        let defaults = UserDefaults.standard
        let persistedRegion = jsonObject(defaults.data(forKey: GeofenceRelay.regionPrefix + region.identifier)) ?? [:]
        var payload = jsonObject(defaults.data(forKey: GeofenceRelay.payloadKey)) ?? [:]
        for (key, value) in persistedRegion["payload"] as? [String: Any] ?? [:] {
            payload[key] = value
        }

        var data = payload
        data["identifier"] = region.identifier
        data["transition"] = enter ? "enter" : "exit"
        data["enter"] = enter
        if let circular = region as? CLCircularRegion {
            data["latitude"] = circular.center.latitude
            data["longitude"] = circular.center.longitude
            data["radius"] = circular.radius
        } else {
            data["latitude"] = persistedRegion["latitude"]
            data["longitude"] = persistedRegion["longitude"]
            data["radius"] = persistedRegion["radius"]
        }
        // When iOS reported the transition, in milliseconds: a queued POST can
        // be sent much later.
        data["time"] = GeofenceRelay.nowMs()
        data["payload"] = payload
        return data
    }

    /// A JSON dictionary from persisted data, or nil.
    private func jsonObject(_ data: Data?) -> [String: Any]? {
        guard let data else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    // MARK: Queue

    private var queue: [Data] {
        get { UserDefaults.standard.array(forKey: GeofenceRelay.queueKey) as? [Data] ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: GeofenceRelay.queueKey) }
    }

    // Transitions for JavaScript that came in while no plugin was loaded.
    private var waiting: [Data] {
        get { UserDefaults.standard.array(forKey: GeofenceRelay.waitingKey) as? [Data] ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: GeofenceRelay.waitingKey) }
    }

    /// Adds a transition to the queue and sends it, when a url is set.
    private func enqueue(_ data: [String: Any]) {
        guard UserDefaults.standard.string(forKey: GeofenceRelay.urlKey)?.isEmpty == false,
              JSONSerialization.isValidJSONObject(data),
              let body = try? JSONSerialization.data(withJSONObject: data) else {
            return
        }
        queue = GeofenceRelay.trimmed(queue + [body], now: GeofenceRelay.nowMs())
        flush()
    }

    /// The current time in milliseconds since the epoch, as `time` carries it.
    static func nowMs() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }

    /// The queue without transitions older than `maxQueuedAgeMs`, and only the
    /// newest `maxQueued` of the rest.
    static func trimmed(_ queue: [Data], now: Int) -> [Data] {
        let fresh = queue.filter { body in
            let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
            guard let time = object?["time"] as? Int else { return true }
            return now - time <= maxQueuedAgeMs
        }
        return Array(fresh.suffix(maxQueued))
    }

    /// Whether a transition stays queued after this answer: none at all (no
    /// network), a server error, refused headers (401, 403: a lapsed token the
    /// app replaces with `updateHeaders`), a timeout (408) or rate limiting
    /// (429). Any other answer removes it: a success, or a refusal a retry
    /// would meet again.
    static func shouldRetry(status: Int?) -> Bool {
        guard let status else { return true }
        return status >= 500 || [401, 403, 408, 429].contains(status)
    }

    /// Sends the oldest queued transition, then the next. One that should be
    /// retried stays queued for the next launch, transition, plugin load,
    /// header update, return of the network or return of the app to the
    /// screen.
    func flush() {
        queue = GeofenceRelay.trimmed(queue, now: GeofenceRelay.nowMs())
        if sending {
            flushAgain = true
            return
        }
        flushAgain = false
        guard let body = queue.first,
              let urlString = UserDefaults.standard.string(forKey: GeofenceRelay.urlKey),
              let url = URL(string: urlString) else {
            return
        }
        sending = true

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        request.addValue("application/json", forHTTPHeaderField: "Accept")
        let sentHeaders = UserDefaults.standard.data(forKey: GeofenceRelay.headersKey)
        let headers = jsonObject(sentHeaders) ?? [:]
        for (key, value) in headers {
            if let value = value as? String {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }
        request.httpBody = body

        var backgroundTask = UIBackgroundTaskIdentifier.invalid
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "CapgoGeofenceTransition") {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
        URLSession.shared.dataTask(with: request) { _, response, error in
            DispatchQueue.main.async {
                let status = (response as? HTTPURLResponse)?.statusCode
                let delivered = !GeofenceRelay.shouldRetry(status: status)
                // The queue can be cleared or trimmed while the POST is in
                // flight, so only the transition that was sent is removed.
                if delivered, self.queue.first == body {
                    self.queue.removeFirst()
                }
                self.sending = false
                if backgroundTask != .invalid {
                    UIApplication.shared.endBackgroundTask(backgroundTask)
                    backgroundTask = .invalid
                }
                let headersChanged = UserDefaults.standard.data(forKey: GeofenceRelay.headersKey) != sentHeaders
                if delivered || (self.flushAgain && headersChanged) {
                    self.flush()
                }
            }
        }.resume()
    }
}
