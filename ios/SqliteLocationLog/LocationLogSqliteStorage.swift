import Foundation
import SQLite3

enum LocationLogSqliteStorage {
    static func make() -> LocationLogStorage {
        LocationLogSqliteStorageImpl()
    }
}

final class LocationLogSqliteStorageImpl: LocationLogStorage {
    static let interrupted = "The app stopped before the request finished"
    private static let openFlags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX |
        SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    private static let columns = "id, time, latitude, longitude, accuracy, altitude, altitude_accuracy, " +
        "speed, bearing, simulated, status, http_status, error"

    private var database: OpaquePointer?
    private let queue = DispatchQueue(label: "CapgoBackgroundGeolocation.LocationLogSqlite")

    init() {
        guard let url = Self.defaultUrl() else { return }
        guard sqlite3_open_v2(url.path, &database, Self.openFlags, nil) == SQLITE_OK else {
            logError("open")
            sqlite3_close(database)
            database = nil
            return
        }
        let schema = """
        CREATE TABLE IF NOT EXISTS location_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            time INTEGER,
            latitude REAL NOT NULL,
            longitude REAL NOT NULL,
            accuracy REAL,
            altitude REAL,
            altitude_accuracy REAL,
            speed REAL,
            bearing REAL,
            simulated INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL,
            http_status INTEGER,
            error TEXT
        );
        CREATE INDEX IF NOT EXISTS location_log_time ON location_log (time);
        """
        if sqlite3_exec(database, schema, nil, nil, nil) != SQLITE_OK {
            logError("create")
        }
        failInterruptedEntries()
    }

    deinit {
        sqlite3_close(database)
    }

    var tracksPostOutcomes: Bool { true }

    func open() {}

    func append(_ location: [String: Any], maxEntries: Int) throws {
        guard insert(location, maxEntries: maxEntries) != nil else {
            throw CocoaError(.coderInvalidValue)
        }
    }

    func insert(_ location: [String: Any], maxEntries: Int) -> Int64? {
        queue.sync {
            let sql = """
            INSERT INTO location_log
                (time, latitude, longitude, accuracy, altitude, altitude_accuracy, speed, bearing, simulated, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
            guard let latitude = (location["latitude"] as? NSNumber)?.doubleValue,
                  let longitude = (location["longitude"] as? NSNumber)?.doubleValue,
                  let statement = prepare(sql) else {
                return nil
            }
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, (location["time"] as? NSNumber)?.int64Value)
            sqlite3_bind_double(statement, 2, latitude)
            sqlite3_bind_double(statement, 3, longitude)
            bind(statement, 4, (location["accuracy"] as? NSNumber)?.doubleValue)
            bind(statement, 5, (location["altitude"] as? NSNumber)?.doubleValue)
            bind(statement, 6, (location["altitudeAccuracy"] as? NSNumber)?.doubleValue)
            bind(statement, 7, (location["speed"] as? NSNumber)?.doubleValue)
            bind(statement, 8, (location["bearing"] as? NSNumber)?.doubleValue)
            sqlite3_bind_int(statement, 9, (location["simulated"] as? Bool) == true ? 1 : 0)
            bind(statement, 10, LocationLog.statusPending)
            guard sqlite3_step(statement) == SQLITE_DONE else {
                logError("insert")
                return nil
            }
            let id = sqlite3_last_insert_rowid(database)
            if let prune = prepare("DELETE FROM location_log WHERE id <= ?") {
                defer { sqlite3_finalize(prune) }
                sqlite3_bind_int64(prune, 1, id - Int64(maxEntries))
                if sqlite3_step(prune) != SQLITE_DONE {
                    logError("prune")
                }
            }
            return id
        }
    }

    func complete(id: Int64, httpStatus: Int?, error: Error?) {
        let sent = error == nil && (200..<300).contains(httpStatus ?? 0)
        let message = error?.localizedDescription ?? "Location POST failed with response code: \(httpStatus ?? 0)"
        queue.sync {
            guard let statement = prepare("UPDATE location_log SET status = ?, http_status = ?, error = ? WHERE id = ?") else {
                return
            }
            defer { sqlite3_finalize(statement) }
            bind(statement, 1, sent ? LocationLog.statusSent : LocationLog.statusFailed)
            bind(statement, 2, httpStatus.map { Int64($0) })
            bind(statement, 3, sent ? nil : message)
            sqlite3_bind_int64(statement, 4, id)
            if sqlite3_step(statement) != SQLITE_DONE {
                logError("update")
            }
        }
    }

    func entries(afterId: Int64, limit: Int) throws -> [[String: Any]] {
        queue.sync {
            let sql = "SELECT \(Self.columns) FROM location_log WHERE id > ? ORDER BY id ASC LIMIT ?"
            guard let statement = prepare(sql) else { return [] }
            defer { sqlite3_finalize(statement) }
            sqlite3_bind_int64(statement, 1, afterId)
            sqlite3_bind_int64(statement, 2, Int64(max(1, limit)))
            var entries: [[String: Any]] = []
            while sqlite3_step(statement) == SQLITE_ROW {
                entries.append(entry(statement))
            }
            return entries
        }
    }

    func clear(upToId: Int64?) throws {
        queue.sync {
            let sql = upToId == nil ? "DELETE FROM location_log" : "DELETE FROM location_log WHERE id <= ?"
            guard let statement = prepare(sql) else { return }
            defer { sqlite3_finalize(statement) }
            if let upToId {
                sqlite3_bind_int64(statement, 1, upToId)
            }
            if sqlite3_step(statement) != SQLITE_DONE {
                logError("clear")
            }
        }
    }

    private func failInterruptedEntries() {
        let sql = "UPDATE location_log SET status = '\(LocationLog.statusFailed)', error = '\(Self.interrupted)' " +
            "WHERE status = '\(LocationLog.statusPending)'"
        if sqlite3_exec(database, sql, nil, nil, nil) != SQLITE_OK {
            logError("fail interrupted")
        }
    }

    private static func defaultUrl() -> URL? {
        let fileManager = FileManager.default
        guard let support = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let directory = support.appendingPathComponent("CapgoBackgroundGeolocation", isDirectory: true)
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            NSLog("CapgoBackgroundGeolocation: could not create the location log directory: \(error)")
            return nil
        }
        return directory.appendingPathComponent("location_log.sqlite")
    }

    private func entry(_ statement: OpaquePointer) -> [String: Any] {
        [
            "id": sqlite3_column_int64(statement, 0),
            "time": int64(statement, 1),
            "latitude": sqlite3_column_double(statement, 2),
            "longitude": sqlite3_column_double(statement, 3),
            "accuracy": double(statement, 4),
            "altitude": double(statement, 5),
            "altitudeAccuracy": double(statement, 6),
            "speed": double(statement, 7),
            "bearing": double(statement, 8),
            "simulated": sqlite3_column_int(statement, 9) != 0,
            "status": text(statement, 10),
            "httpStatus": int64(statement, 11),
            "error": text(statement, 12)
        ]
    }

    private func prepare(_ sql: String) -> OpaquePointer? {
        guard database != nil else { return nil }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, sql, -1, &statement, nil) == SQLITE_OK else {
            logError("prepare")
            return nil
        }
        return statement
    }

    private func bind(_ statement: OpaquePointer, _ index: Int32, _ value: Double?) {
        if let value {
            sqlite3_bind_double(statement, index, value)
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func bind(_ statement: OpaquePointer, _ index: Int32, _ value: Int64?) {
        if let value {
            sqlite3_bind_int64(statement, index, value)
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func bind(_ statement: OpaquePointer, _ index: Int32, _ value: String?) {
        if let value {
            sqlite3_bind_text(statement, index, value, -1, Self.transient)
        } else {
            sqlite3_bind_null(statement, index)
        }
    }

    private func double(_ statement: OpaquePointer, _ column: Int32) -> Any {
        sqlite3_column_type(statement, column) == SQLITE_NULL ? NSNull() : sqlite3_column_double(statement, column)
    }

    private func int64(_ statement: OpaquePointer, _ column: Int32) -> Any {
        sqlite3_column_type(statement, column) == SQLITE_NULL ? NSNull() : sqlite3_column_int64(statement, column)
    }

    private func text(_ statement: OpaquePointer, _ column: Int32) -> Any {
        guard let value = sqlite3_column_text(statement, column) else { return NSNull() }
        return String(cString: value)
    }

    private func logError(_ action: String) {
        NSLog("CapgoBackgroundGeolocation: location log \(action) failed: \(String(cString: sqlite3_errmsg(database)))")
    }
}
