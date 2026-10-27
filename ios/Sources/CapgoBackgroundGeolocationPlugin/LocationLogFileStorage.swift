import Foundation

// Keeps the locations the plugin received in a file, one per line, as the
// identifier followed by the location as JSON. Identifiers only go up, so the
// lines are in order and the last one read works as a cursor.
final class LocationLogFileStorage: LocationLogStorage {
    static let protection = Data.WritingOptions.completeFileProtectionUntilFirstUserAuthentication
    static let newline = UInt8(ascii: "\n")
    static let space = UInt8(ascii: " ")

    let url: URL
    private var loaded = false
    private var lastId: Int64 = 0
    private var count = 0

    init(url: URL) {
        self.url = url
    }

    func append(_ location: [String: Any], maxEntries: Int) throws {
        try write(location, maxEntries: maxEntries)
    }

    func entries(afterId: Int64, limit: Int) throws -> [[String: Any]] {
        var entries: [[String: Any]] = []
        for line in try lines() {
            if entries.count >= limit { break }
            if let id = LocationLogFileStorage.id(of: line), id > afterId,
               var entry = LocationLogFileStorage.location(of: line) {
                entry["id"] = id
                entries.append(entry)
            }
        }
        return entries
    }

    func clear(upToId: Int64?) throws {
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        try load()
        try rewrite(upToId: upToId ?? lastId)
    }

    private func write(_ location: [String: Any], maxEntries: Int) throws {
        guard JSONSerialization.isValidJSONObject(location) else {
            throw CocoaError(.coderInvalidValue)
        }
        try load()
        if count >= maxEntries {
            count = 0
            try rewrite(upToId: lastId - Int64(maxEntries - max(1, maxEntries / 10)))
        }
        var line = Data("\n\(lastId + 1) ".utf8)
        line.append(try JSONSerialization.data(withJSONObject: location, options: .sortedKeys))
        if !FileManager.default.fileExists(atPath: url.path) {
            var directory = url.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? directory.setResourceValues(values)
            try Data().write(to: url, options: LocationLogFileStorage.protection)
            syncParentDirectory(of: url)
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: line)
        lastId += 1
        count += 1
        try handle.synchronize()
    }

    private func load() throws {
        if loaded { return }
        let lines = try lines()
        lastId = lines.compactMap { LocationLogFileStorage.id(of: $0) }.max() ?? 0
        count = lines.filter { $0.contains(LocationLogFileStorage.space) }.count
        loaded = true
    }

    private func rewrite(upToId: Int64) throws {
        let data = try Data(contentsOf: url, options: .alwaysMapped)
        let kept = data.split(separator: LocationLogFileStorage.newline).drop { (LocationLogFileStorage.id(of: $0) ?? -1) <= upToId }
        let rewritten = url.appendingPathExtension("tmp")
        defer { try? FileManager.default.removeItem(at: rewritten) }
        try Data().write(to: rewritten, options: LocationLogFileStorage.protection)
        let handle = try FileHandle(forWritingTo: rewritten)
        defer { try? handle.close() }
        if let first = kept.first {
            try handle.write(contentsOf: data[max(data.startIndex, first.startIndex - 1)...])
        } else {
            try handle.write(contentsOf: Data("\n\(lastId)".utf8))
        }
        try handle.synchronize()
        guard rename(rewritten.path, url.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
        syncParentDirectory(of: url)
        count = kept.count
    }

    private func syncParentDirectory(of url: URL) {
        let directoryDescriptor = open(url.deletingLastPathComponent().path, O_RDONLY)
        if directoryDescriptor >= 0 {
            fsync(directoryDescriptor)
            close(directoryDescriptor)
        }
    }

    private func lines() throws -> [Data] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        return try Data(contentsOf: url, options: .alwaysMapped).split(separator: LocationLogFileStorage.newline)
    }

    static func id(of line: Data) -> Int64? {
        let end = line.firstIndex(of: space) ?? line.endIndex
        return String(bytes: line[..<end], encoding: .utf8).flatMap { Int64($0) }
    }

    static func location(of line: Data) -> [String: Any]? {
        guard let space = line.firstIndex(of: space) else { return nil }
        return (try? JSONSerialization.jsonObject(with: line[(space + 1)...])) as? [String: Any]
    }
}
