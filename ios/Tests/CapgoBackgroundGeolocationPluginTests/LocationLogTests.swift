import XCTest
@testable import CapgoBackgroundGeolocationPlugin

class LocationLogTests: XCTestCase {

    private let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).sqlite")
    private lazy var log = LocationLog(url: url)

    override func tearDown() {
        try? FileManager.default.removeItem(at: url)
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
            "time": NSNumber(value: time),
            "source": "native"
        ]
    }

    private func ids(_ entries: [[String: Any]]) -> [Int64] {
        entries.compactMap { $0["id"] as? Int64 }
    }

    func testInsertAddsPendingEntry() {
        let id = log.insert(location(time: 1_700_000_000_000), maxEntries: 3)

        let entries = log.entries(afterId: nil, since: nil, limit: 10)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0]["id"] as? Int64, id)
        XCTAssertEqual(entries[0]["latitude"] as? Double, 39.7392)
        XCTAssertEqual(entries[0]["longitude"] as? Double, -104.9903)
        XCTAssertEqual(entries[0]["accuracy"] as? Double, 5.0)
        XCTAssertEqual(entries[0]["altitude"] as? Double, 1609.0)
        XCTAssertEqual(entries[0]["altitudeAccuracy"] as? Double, 3.0)
        XCTAssertEqual(entries[0]["simulated"] as? Bool, false)
        XCTAssertTrue(entries[0]["speed"] is NSNull)
        XCTAssertEqual(entries[0]["bearing"] as? Double, 270.0)
        XCTAssertEqual(entries[0]["time"] as? Int64, 1_700_000_000_000)
        XCTAssertEqual(entries[0]["status"] as? String, "pending")
        XCTAssertTrue(entries[0]["httpStatus"] is NSNull)
        XCTAssertTrue(entries[0]["error"] is NSNull)
    }

    func testCompleteRecordsSentEntry() throws {
        let id = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))

        log.complete(id: id, httpStatus: 200, error: nil)

        let entry = log.entries(afterId: nil, since: nil, limit: 10)[0]
        XCTAssertEqual(entry["status"] as? String, "sent")
        XCTAssertEqual(entry["httpStatus"] as? Int64, 200)
        XCTAssertTrue(entry["error"] is NSNull)
    }

    func testCompleteRecordsFailedEntryForErrorResponse() throws {
        let id = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))

        log.complete(id: id, httpStatus: 401, error: nil)

        let entry = log.entries(afterId: nil, since: nil, limit: 10)[0]
        XCTAssertEqual(entry["status"] as? String, "failed")
        XCTAssertEqual(entry["httpStatus"] as? Int64, 401)
        XCTAssertEqual(entry["error"] as? String, "Location POST failed with response code: 401")
    }

    func testCompleteRecordsFailedEntryForNetworkError() throws {
        let id = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))

        log.complete(id: id, httpStatus: nil, error: URLError(.notConnectedToInternet))

        let entry = log.entries(afterId: nil, since: nil, limit: 10)[0]
        XCTAssertEqual(entry["status"] as? String, "failed")
        XCTAssertTrue(entry["httpStatus"] is NSNull)
        XCTAssertNotNil(entry["error"] as? String)
    }

    func testEntriesFiltersAndPages() throws {
        let first = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))
        let second = try XCTUnwrap(log.insert(location(time: 2000), maxEntries: 3))
        let third = try XCTUnwrap(log.insert(location(time: 3000), maxEntries: 3))

        XCTAssertEqual(ids(log.entries(afterId: nil, since: nil, limit: 10)), [first, second, third])
        XCTAssertEqual(ids(log.entries(afterId: first, since: nil, limit: 10)), [second, third])
        XCTAssertEqual(ids(log.entries(afterId: nil, since: 2000, limit: 10)), [second, third])
        XCTAssertEqual(ids(log.entries(afterId: nil, since: nil, limit: 2)), [first, second])
        XCTAssertEqual(ids(log.entries(afterId: second, since: nil, limit: 2)), [third])
    }

    func testInsertRemovesOldestEntriesBeyondMaxEntries() throws {
        _ = log.insert(location(time: 1000), maxEntries: 3)
        let second = try XCTUnwrap(log.insert(location(time: 2000), maxEntries: 3))
        let third = try XCTUnwrap(log.insert(location(time: 3000), maxEntries: 3))
        let fourth = try XCTUnwrap(log.insert(location(time: 4000), maxEntries: 3))

        XCTAssertEqual(ids(log.entries(afterId: nil, since: nil, limit: 10)), [second, third, fourth])
    }

    func testClearRemovesEntriesUpToIdentifier() throws {
        let first = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))
        let second = try XCTUnwrap(log.insert(location(time: 2000), maxEntries: 3))

        log.clear(upToId: first)

        XCTAssertEqual(ids(log.entries(afterId: nil, since: nil, limit: 10)), [second])
    }

    func testIdentifiersAreNotReusedAfterClear() throws {
        let first = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))

        log.clear(upToId: nil)

        XCTAssertTrue(log.entries(afterId: nil, since: nil, limit: 10).isEmpty)
        XCTAssertGreaterThan(try XCTUnwrap(log.insert(location(time: 2000), maxEntries: 3)), first)
    }

    func testReopeningMarksPendingEntriesFailed() throws {
        _ = log.insert(location(time: 1000), maxEntries: 3)

        log = LocationLog(url: url)

        let entry = log.entries(afterId: nil, since: nil, limit: 10)[0]
        XCTAssertEqual(entry["status"] as? String, "failed")
        XCTAssertEqual(entry["error"] as? String, "The app stopped before the request finished")
    }

    func testEntriesSurviveReopeningTheDatabase() throws {
        let id = try XCTUnwrap(log.insert(location(time: 1000), maxEntries: 3))

        log = LocationLog(url: url)

        XCTAssertEqual(ids(log.entries(afterId: nil, since: nil, limit: 10)), [id])
    }
}
