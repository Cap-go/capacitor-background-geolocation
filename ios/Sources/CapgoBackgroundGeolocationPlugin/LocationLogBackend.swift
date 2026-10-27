import Foundation
#if BGGEO_LOCATION_LOG_SQLITE
import CapgoBackgroundGeolocationLocationLogSqlite
#endif

enum LocationLogBackend {
    static func makeStorage() -> LocationLogStorage {
        #if BGGEO_LOCATION_LOG_SQLITE
        if LocationLogPreferences.locationLogSqlite {
            return LocationLogSqliteStorage.make()
        }
        #endif
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return LocationLogFileStorage(
            url: support.appendingPathComponent("CapgoBackgroundGeolocation/location_log.txt")
        )
    }
}
