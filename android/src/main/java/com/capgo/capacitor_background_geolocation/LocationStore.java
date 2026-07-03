package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.Logger;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

// Persists the configuration for a native location watcher and delivers
// location updates to a configured URL directly from native code. This mirrors
// GeofenceStore and exists so that background location delivery keeps working
// after the WebView (and its JavaScript callback) has been destroyed.
final class LocationStore {

    private static final String PREFS_NAME = "CapgoBackgroundGeolocationWatcher";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_URL = "url";
    private static final String KEY_TITLE = "title";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_DISTANCE_FILTER = "distanceFilter";

    private LocationStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // Persists the watcher config. A null or empty url disables native delivery.
    static void saveSetup(Context context, String url, String title, String message, float distanceFilter) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (url == null || url.isEmpty()) {
            editor.clear();
        } else {
            editor
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_URL, url)
                .putString(KEY_TITLE, title)
                .putString(KEY_MESSAGE, message)
                .putFloat(KEY_DISTANCE_FILTER, distanceFilter);
        }
        editor.apply();
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    static boolean isEnabled(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean(KEY_ENABLED, false) && prefs.getString(KEY_URL, null) != null;
    }

    static String getUrl(Context context) {
        return prefs(context).getString(KEY_URL, null);
    }

    static String getTitle(Context context) {
        return prefs(context).getString(KEY_TITLE, "Using your location");
    }

    static String getMessage(Context context) {
        return prefs(context).getString(KEY_MESSAGE, "");
    }

    static float getDistanceFilter(Context context) {
        return prefs(context).getFloat(KEY_DISTANCE_FILTER, 0f);
    }

    // POSTs a single location as JSON to the configured url. Runs synchronously,
    // so callers must invoke it off the main thread.
    static void sendLocation(Context context, JSONObject data) throws IOException {
        String urlString = getUrl(context);
        if (urlString == null || urlString.isEmpty()) {
            return;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }
            int responseCode = connection.getResponseCode();
            Logger.debug("Location POST finished with response code: " + responseCode);
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("Location POST failed with response code: " + responseCode);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
