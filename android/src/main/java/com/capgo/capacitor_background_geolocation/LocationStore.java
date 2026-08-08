package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.Logger;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONException;
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
    private static final String KEY_HEADERS = "headers";
    private static final String KEY_MIN_INTERVAL_MS = "minIntervalMs";
    private static final String KEY_NETWORK_FALLBACK = "networkFallback";
    private static final String KEY_LAST_POST_TIME = "lastPostTime";

    private LocationStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // Persists the watcher config. A null or empty url disables native delivery.
    static void saveSetup(
        Context context,
        String url,
        String title,
        String message,
        float distanceFilter,
        Map<String, String> headers,
        long minIntervalMs,
        boolean networkFallback
    ) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (url == null || url.isEmpty()) {
            editor.clear();
        } else {
            editor
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_URL, url)
                .putString(KEY_TITLE, title)
                .putString(KEY_MESSAGE, message)
                .putFloat(KEY_DISTANCE_FILTER, distanceFilter)
                .putString(KEY_HEADERS, headersToJson(headers))
                .putLong(KEY_MIN_INTERVAL_MS, Math.max(0L, minIntervalMs))
                .putBoolean(KEY_NETWORK_FALLBACK, networkFallback)
                .remove(KEY_LAST_POST_TIME);
        }
        editor.apply();
    }

    static void saveHeaders(Context context, Map<String, String> headers) {
        prefs(context).edit().putString(KEY_HEADERS, headersToJson(headers)).apply();
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

    static long getMinIntervalMs(Context context) {
        return prefs(context).getLong(KEY_MIN_INTERVAL_MS, 0L);
    }

    static boolean getNetworkFallback(Context context) {
        return prefs(context).getBoolean(KEY_NETWORK_FALLBACK, false);
    }

    static Map<String, String> getHeaders(Context context) {
        return headersFromJson(prefs(context).getString(KEY_HEADERS, null));
    }

    // Returns true when this location should be POSTed given the configured
    // minimum interval. Points older than the last sent time pass through.
    static boolean shouldPost(Context context, long locationTimeMs) {
        long minIntervalMs = getMinIntervalMs(context);
        if (minIntervalMs <= 0) {
            return true;
        }
        long lastPostTime = prefs(context).getLong(KEY_LAST_POST_TIME, Long.MIN_VALUE);
        if (lastPostTime != Long.MIN_VALUE && locationTimeMs < lastPostTime) {
            return true;
        }
        if (lastPostTime != Long.MIN_VALUE && locationTimeMs - lastPostTime < minIntervalMs) {
            return false;
        }
        return true;
    }

    static void markPosted(Context context, long locationTimeMs) {
        prefs(context).edit().putLong(KEY_LAST_POST_TIME, locationTimeMs).apply();
    }

    // POSTs a single location as JSON to the configured url. Runs synchronously,
    // so callers must invoke it off the main thread.
    static void sendLocation(Context context, JSONObject data) throws IOException {
        String urlString = getUrl(context);
        if (urlString == null || urlString.isEmpty()) {
            return;
        }
        long locationTimeMs = data.optLong("time", System.currentTimeMillis());
        if (!shouldPost(context, locationTimeMs)) {
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
            for (Map.Entry<String, String> header : getHeaders(context).entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
            }
            int responseCode = connection.getResponseCode();
            Logger.debug("Location POST finished with response code: " + responseCode);
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("Location POST failed with response code: " + responseCode);
            }
            markPosted(context, locationTimeMs);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static String headersToJson(Map<String, String> headers) {
        JSONObject object = new JSONObject();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                try {
                    object.put(entry.getKey(), entry.getValue());
                } catch (JSONException ignore) {}
            }
        }
        return object.toString();
    }

    static Map<String, String> headersFromJson(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            JSONObject object = new JSONObject(json);
            Map<String, String> headers = new HashMap<>();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (value != null && value != JSONObject.NULL) {
                    headers.put(key, String.valueOf(value));
                }
            }
            return headers;
        } catch (JSONException exception) {
            Logger.error("Could not parse persisted location headers", exception);
            return Collections.emptyMap();
        }
    }
}
