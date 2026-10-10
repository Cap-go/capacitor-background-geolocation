import Foundation

// Keeps the locations the plugin received in a file, one per line, as the
// identifier followed by the location as JSON. Identifiers only go up, so the
// lines are in order and the last one read works as a cursor.
final class LocationLog {
    static let defaultMaxEntries = 100000
    static let defaultLimit = 1000
    static let maxLimit = 10000

    static let shared = LocationLog(
        url: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CapgoBackgroundGeolocation/location_log.txt")
    )

    // Keeps the file writable while the device is locked, which is when most
    // background locations arrive.
    private static let protection = Data.WritingOptions.completeFileProtectionUntilFirstUserAuthentication
    private static let newline = UInt8(ascii: "\n")
    private static let space = UInt8(ascii: " ")

    private let url: URL
    // Runs the reads and writes one at a time, in order and off the main thread.
    private let queue = DispatchQueue(label: "CapgoBackgroundGeolocation.LocationLog", qos: .utility)
    private var loaded = false
    private var lastId: Int64 = 0
    private var count = 0

    init(url: URL) {
        self.url = url
    }

    // Adds a location without waiting for the file. Once the log holds
    // maxEntries, the older half is removed first.
    func append(_ location: [String: Any], maxEntries: Int) {
        queue.async {
            do {
                try self.write(location, maxEntries: maxEntries)
            } catch {
                NSLog("CapgoBackgroundGeolocation: could not add the location to the location log: \(error)")
            }
        }
    }

    // Returns up to limit entries with an identifier above afterId, oldest first.
    func entries(afterId: Int64, limit: Int) throws -> [[String: Any]] {
        try queue.sync {
            var entries: [[String: Any]] = []
            for line in try lines() {
                if entries.count >= limit { break }
                if let id = LocationLog.id(of: line), id > afterId, var entry = LocationLog.location(of: line) {
                    entry["id"] = id
                    entries.append(entry)
                }
            }
            return entries
        }
    }

    // Removes the entries up to and including upToId, or every entry when it is nil.
    func clear(upToId: Int64?) throws {
        try queue.sync {
            guard FileManager.default.fileExists(atPath: url.path) else { return }
            try load()
            try rewrite(upToId: upToId ?? lastId)
        }
    }

    private func write(_ location: [String: Any], maxEntries: Int) throws {
        guard JSONSerialization.isValidJSONObject(location) else {
            throw CocoaError(.coderInvalidValue)
        }
        try load()
        if count >= maxEntries {
            // Counted again from here, so a removal that fails is not tried again for every location.
            count = 0
            try rewrite(upToId: lastId - Int64(maxEntries / 2))
        }
        lastId += 1
        // A line starts with its line break, so one that was cut off never runs into the next.
        var line = Data("\n\(lastId) ".utf8)
        line.append(try JSONSerialization.data(withJSONObject: location, options: .sortedKeys))
        if !FileManager.default.fileExists(atPath: url.path) {
            var directory = url.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            // Kept out of device backups. Set on the directory, so the file a rewrite puts in its place is covered too.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? directory.setResourceValues(values)
            try Data().write(to: url, options: LocationLog.protection)
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: line)
        try handle.synchronize()
        count += 1
    }

    // Reads the last identifier and the number of entries, once.
    private func load() throws {
        if loaded { return }
        let lines = try lines()
        lastId = lines.compactMap { LocationLog.id(of: $0) }.max() ?? 0
        count = lines.filter { $0.contains(LocationLog.space) }.count
        loaded = true
    }

    // Replaces the file with its lines from the first one that has an identifier
    // above upToId. They are copied into a file next to it, which is then moved
    // over it, so a stop in between leaves the log as it was. An empty log keeps
    // a line with the last identifier, so none is ever reused.
    private func rewrite(upToId: Int64) throws {
        let data = try Data(contentsOf: url, options: .alwaysMapped)
        let kept = data.split(separator: LocationLog.newline).drop { (LocationLog.id(of: $0) ?? -1) <= upToId }
        let rewritten = url.appendingPathExtension("tmp")
        var moved = false
        defer {
            if !moved { try? FileManager.default.removeItem(at: rewritten) }
        }
        try Data().write(to: rewritten, options: LocationLog.protection)
        let handle = try FileHandle(forWritingTo: rewritten)
        defer { try? handle.close() }
        if let first = kept.first {
            // Written from the file itself, starting at the line break in front of the line.
            try handle.write(contentsOf: data[max(data.startIndex, first.startIndex - 1)...])
        } else {
            try handle.write(contentsOf: Data("\n\(lastId)".utf8))
        }
        try handle.synchronize()
        guard rename(rewritten.path, url.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
        moved = true
        count = kept.count
    }

    // The lines of the file, or none if there is no file yet.
    private func lines() throws -> [Data] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        return try Data(contentsOf: url, options: .alwaysMapped).split(separator: LocationLog.newline)
    }

    // The identifier a line starts with, or nil if it has none.
    private static func id(of line: Data) -> Int64? {
        let end = line.firstIndex(of: space) ?? line.endIndex
        return String(bytes: line[..<end], encoding: .utf8).flatMap { Int64($0) }
    }

    // The location on a line, or nil if the line has none.
    private static func location(of line: Data) -> [String: Any]? {
        guard let space = line.firstIndex(of: space) else { return nil }
        return (try? JSONSerialization.jsonObject(with: line[(space + 1)...])) as? [String: Any]
    }
}
