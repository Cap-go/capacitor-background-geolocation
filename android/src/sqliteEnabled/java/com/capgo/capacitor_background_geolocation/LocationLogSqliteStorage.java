package com.capgo.capacitor_background_geolocation;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

final class LocationLogSqliteStorage extends SQLiteOpenHelper implements LocationLogStorage {

    private static final String INTERRUPTED = "The app stopped before the request finished";
    private static final String DATABASE_NAME = "capgo_background_geolocation.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "location_log";

    public LocationLogSqliteStorage(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

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

    @Override
    public void onOpen(SQLiteDatabase db) {
        if (db.isReadOnly()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("status", LocationLog.STATUS_FAILED);
        values.put("error", INTERRUPTED);
        db.update(TABLE, values, "status = ?", new String[] { LocationLog.STATUS_PENDING });
    }

    @Override
    public void open() {
        try {
            getWritableDatabase();
        } catch (RuntimeException exception) {
            Logger.error("Could not open the location log", exception);
        }
    }

    @Override
    public boolean tracksPostOutcomes() {
        return true;
    }

    @Override
    public void append(JSONObject location, int maxEntries) throws IOException {
        long id = insert(location, maxEntries);
        if (id == -1) {
            throw new IOException("Could not add the location to the location log");
        }
    }

    @Override
    public long insert(JSONObject location, int maxEntries) {
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
            values.put("status", LocationLog.STATUS_PENDING);

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

    @Override
    public void update(long id, String status, Integer httpStatus, String error) {
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

    @Override
    public void remove(long id) {
        if (id == -1) {
            return;
        }
        try {
            getWritableDatabase().delete(TABLE, "id = ?", new String[] { String.valueOf(id) });
        } catch (RuntimeException exception) {
            Logger.error("Could not remove the location from the location log", exception);
        }
    }

    @Override
    public JSArray read(long afterId, int limit) throws IOException {
        return query(afterId, null, limit);
    }

    JSArray query(Long afterId, Long since, int limit) throws IOException {
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
            throw new IOException("Could not read the location log", exception);
        }
        return entries;
    }

    @Override
    public void clear(Long upToId) throws IOException {
        try {
            if (upToId == null) {
                getWritableDatabase().delete(TABLE, null, null);
            } else {
                getWritableDatabase().delete(TABLE, "id <= ?", new String[] { String.valueOf(upToId) });
            }
        } catch (RuntimeException exception) {
            throw new IOException("Could not clear the location log", exception);
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
