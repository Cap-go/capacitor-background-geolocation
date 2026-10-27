package com.capgo.capacitor_background_geolocation;

import android.content.Context;

final class LocationLogBackendFactory {

    private LocationLogBackendFactory() {}

    static LocationLogBackend create(Context context) {
        if (LocationStore.getLocationLogSqlite(context) && !BuildConfig.LOCATION_LOG_SQLITE) {
            throw new IllegalStateException(LocationLog.SQLITE_DISABLED_MESSAGE);
        }
        return new LocationLogBackend(context);
    }
}
