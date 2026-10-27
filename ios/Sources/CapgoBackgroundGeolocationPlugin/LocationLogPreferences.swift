import Foundation

enum LocationLogPreferences {
    private static let suiteName = "CapgoBackgroundGeolocationWatcher"
    private static let locationLogSqliteKey = "locationLogSqlite"

    static func setLocationLogSqlite(_ enabled: Bool) {
        UserDefaults(suiteName: suiteName)?.set(enabled, forKey: locationLogSqliteKey)
    }

    static var locationLogSqlite: Bool {
        UserDefaults(suiteName: suiteName)?.bool(forKey: locationLogSqliteKey) ?? false
    }
}
