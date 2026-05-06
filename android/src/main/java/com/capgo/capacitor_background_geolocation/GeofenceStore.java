package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

final class GeofenceStore {

    static final String ACTION_GEOFENCE_EVENT = GeofenceStore.class.getPackage().getName() + ".geofence";
    static final String ACTION_GEOFENCE_ERROR = GeofenceStore.class.getPackage().getName() + ".geofence.error";
    static final String EXTRA_GEOFENCE_PAYLOAD = "payload";
    static final String EXTRA_GEOFENCE_ERROR = "error";

    private static final String PREFS_NAME = "CapgoBackgroundGeolocationGeofences";
    private static final String KEY_URL = "url";
    private static final String KEY_NOTIFY_ON_ENTRY = "notifyOnEntry";
    private static final String KEY_NOTIFY_ON_EXIT = "notifyOnExit";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_REGION_IDS = "regionIds";
    private static final String KEY_REGION_PREFIX = "region.";

    private GeofenceStore() {}

    static void saveSetup(Context context, String url, boolean notifyOnEntry, boolean notifyOnExit, JSONObject payload) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (url == null || url.isEmpty()) {
            editor.remove(KEY_URL);
        } else {
            editor.putString(KEY_URL, url);
        }
        editor.putBoolean(KEY_NOTIFY_ON_ENTRY, notifyOnEntry);
        editor.putBoolean(KEY_NOTIFY_ON_EXIT, notifyOnExit);
        editor.putString(KEY_PAYLOAD, payload == null ? new JSONObject().toString() : payload.toString());
        editor.apply();
    }

    static String getUrl(Context context) {
        return prefs(context).getString(KEY_URL, null);
    }

    static boolean getNotifyOnEntry(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY_ON_ENTRY, true);
    }

    static boolean getNotifyOnExit(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY_ON_EXIT, true);
    }

    static void saveRegion(
        Context context,
        String identifier,
        double latitude,
        double longitude,
        float radius,
        boolean notifyOnEntry,
        boolean notifyOnExit,
        JSONObject payload
    ) throws JSONException {
        JSONObject region = new JSONObject();
        region.put("identifier", identifier);
        region.put("latitude", latitude);
        region.put("longitude", longitude);
        region.put("radius", radius);
        region.put("notifyOnEntry", notifyOnEntry);
        region.put("notifyOnExit", notifyOnExit);
        region.put("payload", payload == null ? new JSONObject() : payload);

        Set<String> regionIds = getRegionIds(context);
        regionIds.add(identifier);
        prefs(context).edit().putStringSet(KEY_REGION_IDS, regionIds).putString(KEY_REGION_PREFIX + identifier, region.toString()).apply();
    }

    static void removeRegion(Context context, String identifier) {
        Set<String> regionIds = getRegionIds(context);
        regionIds.remove(identifier);
        prefs(context).edit().putStringSet(KEY_REGION_IDS, regionIds).remove(KEY_REGION_PREFIX + identifier).apply();
    }

    static void clearRegions(Context context) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (String identifier : getRegionIds(context)) {
            editor.remove(KEY_REGION_PREFIX + identifier);
        }
        editor.remove(KEY_REGION_IDS).apply();
    }

    static Set<String> getRegionIds(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_REGION_IDS, new HashSet<>()));
    }

    static JSONObject getRegion(Context context, String identifier) {
        return jsonFromString(prefs(context).getString(KEY_REGION_PREFIX + identifier, null));
    }

    static GeofencingRequest buildGeofencingRequest(JSONObject region) throws JSONException {
        String identifier = region.getString("identifier");
        double latitude = region.getDouble("latitude");
        double longitude = region.getDouble("longitude");
        float radius = (float) region.getDouble("radius");
        boolean notifyOnEntry = region.optBoolean("notifyOnEntry", true);
        boolean notifyOnExit = region.optBoolean("notifyOnExit", true);

        int transitionTypes = geofenceTransitionTypes(notifyOnEntry, notifyOnExit);
        int initialTrigger = notifyOnEntry ? GeofencingRequest.INITIAL_TRIGGER_ENTER : 0;
        Geofence geofence = new Geofence.Builder()
            .setRequestId(identifier)
            .setCircularRegion(latitude, longitude, radius)
            .setTransitionTypes(transitionTypes)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build();
        return new GeofencingRequest.Builder().setInitialTrigger(initialTrigger).addGeofence(geofence).build();
    }

    static int geofenceTransitionTypes(boolean notifyOnEntry, boolean notifyOnExit) throws JSONException {
        int transitionTypes = 0;
        if (notifyOnEntry) {
            transitionTypes |= Geofence.GEOFENCE_TRANSITION_ENTER;
        }
        if (notifyOnExit) {
            transitionTypes |= Geofence.GEOFENCE_TRANSITION_EXIT;
        }
        if (transitionTypes == 0) {
            throw new JSONException("At least one transition must be enabled");
        }
        return transitionTypes;
    }

    static JSONObject buildTransitionData(Context context, String identifier, boolean enter) throws JSONException {
        JSONObject region = getRegion(context, identifier);
        JSONObject payload = copy(jsonFromString(prefs(context).getString(KEY_PAYLOAD, null)));
        JSONObject regionPayload = region.optJSONObject("payload");
        if (regionPayload != null) {
            merge(payload, regionPayload);
        }

        JSONObject data = copy(payload);
        data.put("identifier", identifier);
        data.put("transition", enter ? "enter" : "exit");
        data.put("enter", enter);
        if (region.has("latitude")) {
            data.put("latitude", region.optDouble("latitude"));
        }
        if (region.has("longitude")) {
            data.put("longitude", region.optDouble("longitude"));
        }
        if (region.has("radius")) {
            data.put("radius", region.optDouble("radius"));
        }
        data.put("payload", payload);
        return data;
    }

    static void enqueueTransition(Context context, JSONObject data) {
        if (getUrl(context) == null || getUrl(context).isEmpty()) {
            return;
        }
        Data inputData = new Data.Builder().putString(EXTRA_GEOFENCE_PAYLOAD, data.toString()).build();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(GeofenceTransitionWorker.class)
            .setInputData(inputData)
            .setConstraints(constraints)
            .build();
        WorkManager.getInstance(context).enqueue(request);
    }

    static void sendTransition(Context context, JSONObject data) throws IOException {
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
            Logger.debug("Geofence transition POST finished with response code: " + responseCode);
            if (responseCode < HttpURLConnection.HTTP_OK || responseCode >= HttpURLConnection.HTTP_MULT_CHOICE) {
                throw new IOException("Geofence transition POST failed with response code: " + responseCode);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static JSObject toJSObject(JSONObject jsonObject) throws JSONException {
        JSObject result = new JSObject();
        Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, jsonObject.get(key));
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject jsonFromString(String value) {
        if (value == null || value.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value);
        } catch (JSONException exception) {
            return new JSONObject();
        }
    }

    private static JSONObject copy(JSONObject source) throws JSONException {
        JSONObject target = new JSONObject();
        merge(target, source);
        return target;
    }

    private static void merge(JSONObject target, JSONObject source) throws JSONException {
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            target.put(key, source.get(key));
        }
    }
}
