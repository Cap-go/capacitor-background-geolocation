import XCTest
@testable import CapgoBackgroundGeolocationPlugin

class GeofenceRelayTests: XCTestCase {

    private func body(time: Int) -> Data {
        // swiftlint:disable:next force_try
        try! JSONSerialization.data(withJSONObject: ["identifier": "gym", "time": time])
    }

    private func times(_ queue: [Data]) -> [Int] {
        queue.compactMap { ((try? JSONSerialization.jsonObject(with: $0)) as? [String: Any])?["time"] as? Int }
    }

    func testRetriesWhatTheServerMayAcceptLater() {
        XCTAssertTrue(GeofenceRelay.shouldRetry(status: nil))
        for status in [401, 403, 408, 429, 500, 503] {
            XCTAssertTrue(GeofenceRelay.shouldRetry(status: status), "\(status) should stay queued")
        }
    }

    func testDropsWhatTheServerTookOrWillKeepRefusing() {
        for status in [200, 201, 204, 302, 400, 404, 409, 422] {
            XCTAssertFalse(GeofenceRelay.shouldRetry(status: status), "\(status) should leave the queue")
        }
    }

    func testIgnoresTheSecondReportOfOneCrossing() {
        let last = (transition: "enter", at: 1_000)
        XCTAssertTrue(GeofenceRelay.isRepeat("enter", at: 1_000 + 50, after: last))
        XCTAssertFalse(GeofenceRelay.isRepeat("exit", at: 1_000 + 50, after: last))
        XCTAssertFalse(GeofenceRelay.isRepeat("enter", at: 1_000 + GeofenceRelay.repeatWindowMs, after: last))
        XCTAssertFalse(GeofenceRelay.isRepeat("enter", at: 1_000, after: nil))
    }

    func testKeepsOnlyTheNewestTransitions() {
        let now = 1_000_000_000
        let queue = (0..<(GeofenceRelay.maxQueued + 5)).map { body(time: now - 1000 + $0) }
        let kept = GeofenceRelay.trimmed(queue, now: now)
        XCTAssertEqual(kept.count, GeofenceRelay.maxQueued)
        XCTAssertEqual(times(kept).first, now - 1000 + 5)
    }

    func testDropsTransitionsOlderThanTheAgeLimit() {
        let now = GeofenceRelay.maxQueuedAgeMs * 2
        let oldest = now - GeofenceRelay.maxQueuedAgeMs
        let queue = [body(time: oldest - 1), body(time: oldest), body(time: now)]
        XCTAssertEqual(times(GeofenceRelay.trimmed(queue, now: now)), [oldest, now])
    }
}
