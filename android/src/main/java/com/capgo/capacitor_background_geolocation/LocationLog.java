package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import com.getcapacitor.JSArray;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.json.JSONObject;

final class LocationLog {

    static final int DEFAULT_MAX_ENTRIES = 100000;
    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 10000;

    static final String STATUS_PENDING = "pending";
    static final String STATUS_SENT = "sent";
    static final String STATUS_FAILED = "failed";

    static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    static final String SQLITE_DISABLED_MESSAGE =
        "SQLite location log is disabled. Set plugins.BackgroundGeolocation.locationLogSqlite to true in capacitor.config and run npx cap sync.";

    private static LocationLog instance;

    private final LocationLogBackend backend;

    static synchronized LocationLog getInstance(Context context) {
        if (instance == null) {
            instance = new LocationLog(LocationLogBackendFactory.create(context));
        }
        return instance;
    }

    static synchronized void resetForTests() {
        instance = null;
    }

    static synchronized void resetInstance() {
        instance = null;
    }

    LocationLog(LocationLogBackend backend) {
        this.backend = backend;
    }

    void open() {
        backend.open();
    }

    void append(JSONObject location, int maxEntries) throws IOException {
        backend.append(location, maxEntries);
    }

    JSArray read(long afterId, int limit) throws IOException {
        return backend.read(afterId, limit);
    }

    void clear(Long upToId) throws IOException {
        backend.clear(upToId);
    }

    long insert(JSONObject location, int maxEntries) {
        return backend.insert(location, maxEntries);
    }

    void update(long id, String status, Integer httpStatus, String error) {
        backend.update(id, status, httpStatus, error);
    }

    void remove(long id) {
        backend.remove(id);
    }

    boolean tracksPostOutcomes() {
        return backend.tracksPostOutcomes();
    }
}
