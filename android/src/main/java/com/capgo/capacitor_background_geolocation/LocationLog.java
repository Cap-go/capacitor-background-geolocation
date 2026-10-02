package com.capgo.capacitor_background_geolocation;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

// Keeps every natively POSTed location and the outcome of its request in
// SQLite. Nothing in here throws, so a broken log never stops a location from
// being sent.
final class LocationLog extends SQLiteOpenHelper {

    static final String STATUS_PENDING = "pending";
    static final String STATUS_SENT = "sent";
    static final String STATUS_FAILED = "failed";
    static final String INTERRUPTED = "The app stopped before the request finished";
    static final int DEFAULT_MAX_ENTRIES = 100000;
    static final int DEFAULT_LIMIT = 1000;

    private static final String DATABASE_NAME = "capgo_background_geolocation.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "location_log";

    private static LocationLog instance;

    static synchronized LocationLog getInstance(Context context) {
        if (instance == null) {
            instance = new LocationLog(context.getApplicationContext(), DATABASE_NAME);
        }
        return instance;
    }

    LocationLog(Context context, String name) {
        super(context, name, null, DATABASE_VERSION);
    }

    // Identifiers are never reused (AUTOINCREMENT), so the last one read stays
    // valid as a cursor after older entries are removed.
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " +
                TABLE +
                " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "time INTEGER, " +
                "latitude REAL NOT NULL, " +
                "longitude REAL NOT NULL, " +
                "accuracy REAL, " +
                "altitude REAL, " +
                "altitude_accuracy REAL, " +
                "speed REAL, " +
                "bearing REAL, " +
                "simulated INTEGER NOT NULL DEFAULT 0, " +
                "status TEXT NOT NULL, " +
                "http_status INTEGER, " +
                "error TEXT)"
        );
        db.execSQL("CREATE INDEX location_log_time ON " + TABLE + " (time)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    // Entries still pending when the log is opened were left by a process that
    // stopped before their request finished, so they are marked as failed.
    @Override
    public void onOpen(SQLiteDatabase db) {
        if (db.isReadOnly()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("status", STATUS_FAILED);
        values.put("error", INTERRUPTED);
        db.update(TABLE, values, "status = ?", new String[] { STATUS_PENDING });
    }

    // Adds a location as pending and returns its identifier, or -1 if it could
    // not be added. The oldest entries beyond maxEntries are removed.
    long insert(JSONObject location, int maxEntries) {
        try {
            ContentValues values = new ContentValues();
            if (location.isNull("time")) {
                values.putNull("time");
            } else {
                values.put("time", location.getLong("time"));
            }
            values.put("latitude", location.getDouble("latitude"));
            values.put("longitude", location.getDouble("longitude"));
            putDouble(values, "accuracy", location, "accuracy");
            putDouble(values, "altitude", location, "altitude");
            putDouble(values, "altitude_accuracy", location, "altitudeAccuracy");
            putDouble(values, "speed", location, "speed");
            putDouble(values, "bearing", location, "bearing");
            values.put("simulated", location.optBoolean("simulated", false) ? 1 : 0);
            values.put("status", STATUS_PENDING);

            SQLiteDatabase db = getWritableDatabase();
            long id = db.insert(TABLE, null, values);
            if (id != -1) {
                db.delete(TABLE, "id <= ?", new String[] { String.valueOf(id - maxEntries) });
            }
            return id;
        } catch (Exception exception) {
            Logger.error("Could not add the location to the location log", exception);
            return -1;
        }
    }

    void update(long id, String status, Integer httpStatus, String error) {
        if (id == -1) {
            return;
        }
        try {
            ContentValues values = new ContentValues();
            values.put("status", status);
            values.put("http_status", httpStatus);
            values.put("error", error);
            getWritableDatabase().update(TABLE, values, "id = ?", new String[] { String.valueOf(id) });
        } catch (RuntimeException exception) {
            Logger.error("Could not update the location log", exception);
        }
    }

    void remove(long id) {
        if (id == -1) {
            return;
        }
        try {
            getWritableDatabase().delete(TABLE, "id = ?", new String[] { String.valueOf(id) });
        } catch (RuntimeException exception) {
            Logger.error("Could not remove the location from the location log", exception);
        }
    }

    // Returns the matching entries, oldest first. A null filter is not applied.
    JSArray query(Long afterId, Long since, int limit) {
        JSArray entries = new JSArray();
        List<String> clauses = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (afterId != null) {
            clauses.add("id > ?");
            args.add(String.valueOf(afterId));
        }
        if (since != null) {
            clauses.add("time >= ?");
            args.add(String.valueOf(since));
        }
        String selection = clauses.isEmpty() ? null : String.join(" AND ", clauses);
        try (
            Cursor cursor = getReadableDatabase().query(
                TABLE,
                null,
                selection,
                args.toArray(new String[0]),
                null,
                null,
                "id ASC",
                String.valueOf(Math.max(1, limit))
            )
        ) {
            while (cursor.moveToNext()) {
                entries.put(toEntry(cursor));
            }
        } catch (RuntimeException exception) {
            Logger.error("Could not read the location log", exception);
        }
        return entries;
    }

    // Removes the entries up to and including upToId, or every entry when it is null.
    void clear(Long upToId) {
        try {
            if (upToId == null) {
                getWritableDatabase().delete(TABLE, null, null);
            } else {
                getWritableDatabase().delete(TABLE, "id <= ?", new String[] { String.valueOf(upToId) });
            }
        } catch (RuntimeException exception) {
            Logger.error("Could not clear the location log", exception);
        }
    }

    private static void putDouble(ContentValues values, String column, JSONObject location, String key) throws JSONException {
        if (location.isNull(key)) {
            values.putNull(column);
        } else {
            values.put(column, location.getDouble(key));
        }
    }

    private static JSObject toEntry(Cursor cursor) {
        JSObject entry = new JSObject();
        entry.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("id")));
        entry.put("latitude", cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")));
        entry.put("longitude", cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")));
        entry.put("accuracy", nullableDouble(cursor, "accuracy"));
        entry.put("altitude", nullableDouble(cursor, "altitude"));
        entry.put("altitudeAccuracy", nullableDouble(cursor, "altitude_accuracy"));
        entry.put("simulated", cursor.getInt(cursor.getColumnIndexOrThrow("simulated")) != 0);
        entry.put("speed", nullableDouble(cursor, "speed"));
        entry.put("bearing", nullableDouble(cursor, "bearing"));
        int time = cursor.getColumnIndexOrThrow("time");
        entry.put("time", cursor.isNull(time) ? JSONObject.NULL : cursor.getLong(time));
        entry.put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")));
        int httpStatus = cursor.getColumnIndexOrThrow("http_status");
        entry.put("httpStatus", cursor.isNull(httpStatus) ? JSONObject.NULL : cursor.getInt(httpStatus));
        int error = cursor.getColumnIndexOrThrow("error");
        entry.put("error", cursor.isNull(error) ? JSONObject.NULL : cursor.getString(error));
        return entry;
    }

    private static Object nullableDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? JSONObject.NULL : cursor.getDouble(index);
    }
}
