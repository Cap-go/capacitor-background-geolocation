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
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

final class GeofenceStore {

    static final String EXTRA_GEOFENCE_PAYLOAD = "payload";
    // Every transition POST job carries this tag, so the queued ones can be cancelled together.
    static final String TRANSITION_WORK_TAG = "CapgoGeofenceTransition";
    static final String EXTRA_QUEUE_GENERATION = "queueGeneration";

    private static final String PREFS_NAME = "CapgoBackgroundGeolocationGeofences";
    private static final String KEY_URL = "url";
    private static final String KEY_HEADERS = "headers";
    private static final String KEY_NOTIFY_ON_ENTRY = "notifyOnEntry";
    private static final String KEY_NOTIFY_ON_EXIT = "notifyOnExit";
    private static final String KEY_BACKGROUND_LOCATION = "backgroundLocation";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_REGION_IDS = "regionIds";
    private static final String KEY_REGION_PREFIX = "region.";
    // Raised each time the queue is cleared; a job queued under an older value is not sent.
    private static final String KEY_QUEUE_GENERATION = "queueGeneration";

    private GeofenceStore() {}

    static void saveSetup(
        Context context,
        String url,
        boolean notifyOnEntry,
        boolean notifyOnExit,
        JSONObject payload,
        boolean backgroundLocation,
        Map<String, String> headers
    ) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (url == null || url.isEmpty()) {
            editor.remove(KEY_URL);
            editor.remove(KEY_HEADERS);
        } else {
            editor.putString(KEY_URL, url);
            editor.putString(KEY_HEADERS, LocationStore.headersToJson(headers));
        }
        editor.putBoolean(KEY_NOTIFY_ON_ENTRY, notifyOnEntry);
        editor.putBoolean(KEY_NOTIFY_ON_EXIT, notifyOnExit);
        editor.putBoolean(KEY_BACKGROUND_LOCATION, backgroundLocation);
        editor.putString(KEY_PAYLOAD, payload == null ? new JSONObject().toString() : payload.toString());
        editor.apply();
    }

    static void saveHeaders(Context context, Map<String, String> headers) {
        prefs(context).edit().putString(KEY_HEADERS, LocationStore.headersToJson(headers)).apply();
    }

    static Map<String, String> getHeaders(Context context) {
        return LocationStore.headersFromJson(prefs(context).getString(KEY_HEADERS, null));
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

    static boolean getBackgroundLocation(Context context) {
        return prefs(context).getBoolean(KEY_BACKGROUND_LOCATION, false);
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
        prefs(context)
            .edit()
            .putStringSet(KEY_REGION_IDS, regionIds)
            .putString(KEY_REGION_PREFIX + identifier, region.toString())
            .apply();
    }

    static void removeRegion(Context context, String identifier) {
        Set<String> regionIds = getRegionIds(context);
        regionIds.remove(identifier);
        prefs(context)
            .edit()
            .putStringSet(KEY_REGION_IDS, regionIds)
            .remove(KEY_REGION_PREFIX + identifier)
            .apply();
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

    static JSONObject buildTransitionData(Context context, String identifier, boolean enter, long time) throws JSONException {
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
        // When the transition happened, in milliseconds: WorkManager can send it much later.
        data.put("time", time);
        data.put("payload", payload);
        return data;
    }

    /** Queues a transition built under the given queue generation for a native POST. */
    static void enqueueTransition(Context context, JSONObject data, int generation) {
        if (!getBackgroundLocation(context)) {
            return;
        }
        if (getUrl(context) == null || getUrl(context).isEmpty()) {
            return;
        }
        Data inputData = new Data.Builder()
            .putString(EXTRA_GEOFENCE_PAYLOAD, data.toString())
            .putInt(EXTRA_QUEUE_GENERATION, generation)
            .build();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(GeofenceTransitionWorker.class)
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(TRANSITION_WORK_TAG)
            .build();
        WorkManager.getInstance(context).enqueue(request);
    }

    /**
     * Drops every queued transition. Raising the queue generation first means a job that is
     * already running, which cancellation does not stop, is not sent either: it reads the
     * generation after the url and headers, so it never pairs an old transition with a newer
     * setup. Jobs queued before the tag existed carry only WorkManager's class-name tag.
     */
    static void cancelQueuedTransitions(Context context) {
        raiseQueueGeneration(context);
        WorkManager workManager = WorkManager.getInstance(context);
        workManager.cancelAllWorkByTag(TRANSITION_WORK_TAG);
        workManager.cancelAllWorkByTag(GeofenceTransitionWorker.class.getName());
    }

    /** The current queue generation; jobs from an older one are dropped instead of sent. */
    static int queueGeneration(Context context) {
        return prefs(context).getInt(KEY_QUEUE_GENERATION, 0);
    }

    /** Raises the queue generation, written at once so a running job sees it. */
    static void raiseQueueGeneration(Context context) {
        prefs(context)
            .edit()
            .putInt(KEY_QUEUE_GENERATION, queueGeneration(context) + 1)
            .commit();
    }

    /** Whether setup moves transitions to another endpoint, which should not receive the queued ones. */
    static boolean urlChanged(String previous, String next) {
        String before = previous == null ? "" : previous;
        String after = next == null ? "" : next;
        return !before.equals(after);
    }

    static void sendTransition(Context context, JSONObject data, int generation) throws IOException {
        String urlString = getUrl(context);
        Map<String, String> headers = getHeaders(context);
        if (urlString == null || urlString.isEmpty()) {
            return;
        }
        // Read after the url and headers: an unchanged generation means both belong to the setup
        // this transition was queued under.
        if (queueGeneration(context) != generation) {
            Logger.debug("Dropping a geofence transition queued before the queue was cleared");
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
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
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
