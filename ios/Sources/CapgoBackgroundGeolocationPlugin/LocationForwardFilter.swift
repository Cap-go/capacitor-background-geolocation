import CoreLocation
import Foundation

struct LocationForwardFilter {
    private(set) var distanceFilterMeters: Double = 0
    private(set) var intervalMs: Double = 0
    private var lastForwarded: CLLocation?
    private var lastForwardedAt: Date?

    mutating func configure(distanceFilterMeters: Double, intervalMs: Double) {
        self.distanceFilterMeters = max(0, distanceFilterMeters)
        self.intervalMs = max(0, intervalMs)
        lastForwarded = nil
        lastForwardedAt = nil
    }

    var usesSoftwareDistanceFilter: Bool {
        intervalMs > 0
    }

    func shouldForward(_ location: CLLocation) -> Bool {
        guard intervalMs > 0 else {
            return true
        }
        guard distanceFilterMeters > 0, let lastForwarded else {
            return true
        }
        if distanceFilterMeters > 0, location.distance(from: lastForwarded) >= distanceFilterMeters {
            return true
        }
        if intervalMs > 0 {
            let elapsedMs = location.timestamp.timeIntervalSince(lastForwarded.timestamp) * 1000
            if elapsedMs >= intervalMs {
                return true
            }
        }
        return false
    }

    mutating func markForwarded(_ location: CLLocation) {
        lastForwarded = location
        lastForwardedAt = Date()
    }

    func millisSinceLastForward() -> TimeInterval {
        guard let lastForwardedAt else {
            return .infinity
        }
        return Date().timeIntervalSince(lastForwardedAt) * 1000
    }
}
