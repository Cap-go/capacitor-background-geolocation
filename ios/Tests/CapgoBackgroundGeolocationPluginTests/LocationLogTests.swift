import XCTest
@testable import CapgoBackgroundGeolocationPlugin

class LocationLogTests: XCTestCase {

    private let url = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString)
        .appendingPathComponent("location_log.txt")
    private lazy var log = LocationLog(url: url)

    override func tearDown() {
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
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

    private func appendLocations(_ count: Int, maxEntries: Int) {
        for index in 1...count {
            log.append(location(time: Int64(1000 * index)), maxEntries: maxEntries)
        }
    }

    private func ids(afterId: Int64 = 0, limit: Int = 10) throws -> [Int64] {
        try log.entries(afterId: afterId, limit: limit).compactMap { $0["id"] as? Int64 }
    }

    func testEntriesReturnsTheLocationWithItsIdentifier() throws {
        XCTAssertEqual(try ids(), [])

        log.append(location(time: 1_700_000_000_000), maxEntries: 10)

        let entries = try log.entries(afterId: 0, limit: 10)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0]["id"] as? Int64, 1)
        XCTAssertEqual(entries[0]["latitude"] as? Double, 39.7392)
        XCTAssertEqual(entries[0]["simulated"] as? Bool, false)
        XCTAssertTrue(entries[0]["speed"] is NSNull)
        XCTAssertEqual(entries[0]["time"] as? Int64, 1_700_000_000_000)
    }

    func testALineHasItsKeysInAlphabeticalOrder() throws {
        log.append(location(time: 1000), maxEntries: 10)
        XCTAssertEqual(try ids(), [1])

        let line = try String(contentsOf: url, encoding: .utf8)
        let keys = line.components(separatedBy: "\"").enumerated().filter { $0.offset % 2 == 1 }.map(\.element)
        XCTAssertEqual(keys.count, 9)
        XCTAssertEqual(keys, keys.sorted())
    }

    func testTheLogIsLeftOutOfBackups() throws {
        log.append(location(time: 1000), maxEntries: 10)
        XCTAssertEqual(try ids(), [1])

        let directory = url.deletingLastPathComponent()
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testEntriesReturnsEntriesAfterAfterIdUpToLimit() throws {
        appendLocations(3, maxEntries: 10)

        XCTAssertEqual(try ids(), [1, 2, 3])
        XCTAssertEqual(try ids(afterId: 1), [2, 3])
        XCTAssertEqual(try ids(limit: 2), [1, 2])
        XCTAssertEqual(try ids(afterId: 3), [])
    }

    func testClearKeepsALocationAddedAfterTheRead() throws {
        appendLocations(2, maxEntries: 10)
        let read = try ids()
        appendLocations(1, maxEntries: 10)

        try log.clear(upToId: read.last)

        XCTAssertEqual(try ids(), [3])
    }

    func testAClearThatFailsLeavesTheLogAsItWas() throws {
        appendLocations(3, maxEntries: 10)
        XCTAssertEqual(try ids(), [1, 2, 3])
        let inTheWay = url.appendingPathExtension("tmp").appendingPathComponent("child")
        try FileManager.default.createDirectory(at: inTheWay, withIntermediateDirectories: true)

        XCTAssertThrowsError(try log.clear(upToId: 1))

        XCTAssertEqual(try ids(), [1, 2, 3])
    }

    func testIdentifiersAreNotReusedAfterTheLogWasCleared() throws {
        appendLocations(2, maxEntries: 10)
        XCTAssertEqual(try ids(), [1, 2])

        log = LocationLog(url: url)
        try log.clear(upToId: nil)
        XCTAssertEqual(try ids(), [])
        log = LocationLog(url: url)
        appendLocations(1, maxEntries: 10)

        XCTAssertEqual(try ids(), [3])
    }

    func testALogThatIsOpenedAgainRemovesTheOldestTenthAtTheSameSize() throws {
        appendLocations(19, maxEntries: 20)
        XCTAssertEqual(try ids(limit: 30), Array(1...19))

        log = LocationLog(url: url)
        appendLocations(1, maxEntries: 20)
        XCTAssertEqual(try ids(limit: 30), Array(1...20))

        appendLocations(1, maxEntries: 20)

        XCTAssertEqual(try ids(limit: 30), Array(3...21))
    }

    func testALineThatWasCutOffDoesNotRunIntoTheNext() throws {
        appendLocations(1, maxEntries: 10)
        XCTAssertEqual(try ids(), [1])
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("\n2 {\"latitude\":39.7".utf8))
        try handle.close()

        log.append(location(time: 2000), maxEntries: 10)

        let entries = try log.entries(afterId: 0, limit: 10)
        XCTAssertEqual(entries.compactMap { $0["id"] as? Int64 }, [1, 2])
        XCTAssertEqual(entries.last?["time"] as? Int64, 2000)
    }

}
