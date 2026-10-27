#if BGGEO_LOCATION_LOG_SQLITE
import XCTest
@testable import CapgoBackgroundGeolocationPlugin

class LocationLogSqliteTests: XCTestCase {

    private var log: LocationLog!

    override func setUp() {
        super.setUp()
        LocationLogPreferences.setLocationLogSqlite(true)
        LocationLog.resetShared()
        log = LocationLog.shared
        log.open()
        try? log.clear(upToId: nil)
    }

    override func tearDown() {
        try? log.clear(upToId: nil)
        LocationLogPreferences.setLocationLogSqlite(false)
        LocationLog.resetShared()
        super.tearDown()
    }

    private func location(time: Int64) -> [String: Any] {
        [
            "latitude": 39.7392,
            "longitude": -104.9903,
            "accuracy": 5.0,
            "altitude": 1609.0,
            "altitudeAccuracy": 3.0,
            "simulated": false,
            "speed": NSNull(),
            "bearing": 270.0,
            "time": NSNumber(value: time)
        ]
    }

    func testSqliteBackendTracksPostOutcomes() {
        XCTAssertTrue(log.tracksPostOutcomes)
    }

    func testInsertAndComplete() throws {
        guard let id = log.insert(location(time: 1000), maxEntries: 10) else {
            XCTFail("insert failed")
            return
        }
        log.complete(id: id, httpStatus: 200, error: nil)
        let entries = try log.entries(afterId: 0, limit: 10)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0]["id"] as? Int64, id)
        XCTAssertEqual(entries[0]["status"] as? String, LocationLog.statusSent)
    }
}
#endif
