package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import com.getcapacitor.JSArray;
import java.io.File;
import java.io.IOException;
import org.json.JSONObject;

final class LocationLogBackend {

    private final LocationLogStorage storage;

    LocationLogBackend(Context context) {
        storage = createStorage(context);
    }

    LocationLogBackend(LocationLogFileStorage fileStorage) {
        storage = fileStorage;
    }

    private static LocationLogStorage createStorage(Context context) {
        if (BuildConfig.LOCATION_LOG_SQLITE && LocationStore.getLocationLogSqlite(context)) {
            try {
                Class<?> type = Class.forName("com.capgo.capacitor_background_geolocation.LocationLogSqliteStorage");
                var constructor = type.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                return (LocationLogStorage) constructor.newInstance(context);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(LocationLog.SQLITE_DISABLED_MESSAGE, exception);
            }
        }
        return new LocationLogFileStorage(new File(context.getNoBackupFilesDir(), "capgo_background_geolocation_location_log.txt"));
    }

    void open() {
        storage.open();
    }

    void append(JSONObject location, int maxEntries) throws IOException {
        storage.append(location, maxEntries);
    }

    JSArray read(long afterId, int limit) throws IOException {
        return storage.read(afterId, limit);
    }

    void clear(Long upToId) throws IOException {
        storage.clear(upToId);
    }

    long insert(JSONObject location, int maxEntries) {
        return storage.insert(location, maxEntries);
    }

    void update(long id, String status, Integer httpStatus, String error) {
        storage.update(id, status, httpStatus, error);
    }

    void remove(long id) {
        storage.remove(id);
    }

    boolean tracksPostOutcomes() {
        return storage.tracksPostOutcomes();
    }
}
