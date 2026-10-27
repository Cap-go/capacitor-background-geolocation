import Foundation

final class LocationLog {
    public static let statusPending = "pending"
    public static let statusSent = "sent"
    public static let statusFailed = "failed"

    static let defaultMaxEntries = 100000
    static let defaultLimit = 1000
    static let maxLimit = 10000

    static var shared = LocationLog(storage: LocationLogBackend.makeStorage())

    static func resetShared() {
        shared = LocationLog(storage: LocationLogBackend.makeStorage())
    }

    private let storage: LocationLogStorage
    private let queue = DispatchQueue(label: "CapgoBackgroundGeolocation.LocationLog", qos: .utility)

    init(url: URL) {
        storage = LocationLogFileStorage(url: url)
    }

    private init(storage: LocationLogStorage) {
        self.storage = storage
    }

    var tracksPostOutcomes: Bool {
        storage.tracksPostOutcomes
    }

    func open() {
        storage.open()
    }

    func append(_ location: [String: Any], maxEntries: Int) {
        queue.async {
            do {
                try self.storage.append(location, maxEntries: maxEntries)
            } catch {
                NSLog("CapgoBackgroundGeolocation: could not add the location to the location log: \(error)")
            }
        }
    }

    func entries(afterId: Int64, limit: Int) throws -> [[String: Any]] {
        try queue.sync {
            try storage.entries(afterId: afterId, limit: limit)
        }
    }

    func clear(upToId: Int64?) throws {
        try queue.sync {
            try storage.clear(upToId: upToId)
        }
    }

    func insert(_ location: [String: Any], maxEntries: Int) -> Int64? {
        queue.sync {
            storage.insert(location, maxEntries: maxEntries)
        }
    }

    func complete(id: Int64, httpStatus: Int?, error: Error?) {
        queue.async {
            self.storage.complete(id: id, httpStatus: httpStatus, error: error)
        }
    }
}
