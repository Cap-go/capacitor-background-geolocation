package com.capgo.capacitor_background_geolocation;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

// A bound and started service that is promoted to a foreground service
// (showing a persistent notification) when the first background watcher is
// added, and demoted when the last background watcher is removed.
public class BackgroundGeolocationService extends Service {

    static final String ACTION_BROADCAST = (BackgroundGeolocationService.class.getPackage().getName() + ".broadcast");
    private final IBinder binder = new LocalBinder();

    private static final double EARTH_RADIUS_M = 6371000;

    // Must be unique for this application.
    private static final int NOTIFICATION_ID = 28351;

    private String callbackId;

    private LocationManager client;
    private LocationListener locationCallback;
    private MediaPlayer mediaPlayer;
    private double[][] route;
    private double distanceThreshold;
    private boolean isOffRoute;

    private Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private Runnable watchdogRunnable;
    private Runnable restartRunnable;
    private float currentDistanceFilter;
    private PowerManager.WakeLock wakeLock;

    // When set (via the "url" start option), each location is also POSTed to
    // this URL directly from native code so delivery survives the WebView being
    // destroyed. Delivery runs on postExecutor to keep it off the main thread.
    private String nativePostUrl;
    private ExecutorService postExecutor;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Some devices allow a foreground service to outlive the application's main
    // activity, leading to nasty crashes as reported in issue #59. If we learn
    // that the application has been killed, all watchers are stopped and the
    // service is terminated immediately.
    //
    // The exception is native delivery mode (a "url" was given to start()): there
    // the service is meant to outlive the app UI, so it keeps running and relies
    // on onStartCommand to re-establish updates if the process is later restarted.
    @Override
    public boolean onUnbind(Intent intent) {
        if (LocationStore.isEnabled(getApplicationContext())) {
            return false;
        }
        if (client != null && locationCallback != null) {
            client.removeUpdates(locationCallback);
        }
        releaseMediaPlayer();
        releaseWakeLock();
        stopWatchdog();
        stopSelf();
        return false;
    }

    // In native delivery mode, keep tracking after the user swipes the app away
    // from the recents list; otherwise fall back to the default teardown.
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (LocationStore.isEnabled(getApplicationContext())) {
            return;
        }
        super.onTaskRemoved(rootIntent);
    }

    // The system may kill and later restart a START_STICKY foreground service
    // with a null intent and no binder call. When native delivery is configured,
    // re-establish location updates from the persisted config so tracking resumes
    // without the app UI.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context context = getApplicationContext();
        if (!LocationStore.isEnabled(context)) {
            // Not in native delivery mode: preserve the original behavior where the
            // service does not outlive the app, so it is not sticky-restarted.
            return START_NOT_STICKY;
        }
        nativePostUrl = LocationStore.getUrl(context);
        promoteToForeground(LocationStore.getTitle(context), LocationStore.getMessage(context));
        if (client == null || locationCallback == null) {
            acquireWakeLock();
            client = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            currentDistanceFilter = LocationStore.getDistanceFilter(context);
            locationCallback = createLocationListener(this);
            requestLocationUpdates();
            startWatchdog();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (client != null && locationCallback != null) {
            client.removeUpdates(locationCallback);
        }
        super.onDestroy();
        releaseMediaPlayer();
        releaseWakeLock();
        stopWatchdog();
        if (postExecutor != null) {
            postExecutor.shutdown();
            postExecutor = null;
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer == null) {
            return;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
        } catch (Exception e) {
            Logger.error("Error releasing MediaPlayer", e);
        }
        mediaPlayer = null;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BackgroundGeolocation::LocationWakeLock");
            wakeLock.acquire();
            Logger.info("Wake lock acquired");
        } catch (Exception e) {
            Logger.error("Error acquiring wake lock", e);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Logger.info("Wake lock released");
            }
        } catch (Exception e) {
            Logger.error("Error releasing wake lock", e);
        }
        wakeLock = null;
    }

    private void restartLocationUpdates() {
        Logger.debug("Location watchdog timed out, restarting updates");
        if (client == null || locationCallback == null) {
            return;
        }
        client.removeUpdates(locationCallback);
        if (restartRunnable != null) {
            watchdogHandler.removeCallbacks(restartRunnable);
        }
        restartRunnable = () -> {
            if (client == null || locationCallback == null) {
                return;
            }
            try {
                client.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, currentDistanceFilter, locationCallback);
            } catch (SecurityException ignore) {
                // Permission issues are handled in the start() method
            }
            startWatchdog();
        };
        watchdogHandler.postDelayed(restartRunnable, 10000);
    }

    private void startWatchdog() {
        stopWatchdog();
        if (watchdogRunnable == null) {
            watchdogRunnable = this::restartLocationUpdates;
        }
        watchdogHandler.postDelayed(watchdogRunnable, 60000);
    }

    private void stopWatchdog() {
        if (watchdogRunnable != null) {
            watchdogHandler.removeCallbacks(watchdogRunnable);
        }
        if (restartRunnable != null) {
            watchdogHandler.removeCallbacks(restartRunnable);
        }
    }

    private void handleLocationChanged(android.location.Location location) {
        startWatchdog();
        if (nativePostUrl != null) {
            postLocationNatively(location);
        }
        if (mediaPlayer != null) {
            double[] point = { location.getLongitude(), location.getLatitude() };
            var offRoute = distancePointToRoute(point) > distanceThreshold;
            if (offRoute == true && isOffRoute == false) {
                mediaPlayer.start();
            }
            isOffRoute = offRoute;
        }
        Intent intent = new Intent(ACTION_BROADCAST);
        intent.putExtra("location", location);
        intent.putExtra("id", callbackId);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    // Delivers a location to the configured URL from native code, so it works
    // even when the WebView/JavaScript layer no longer exists.
    private void postLocationNatively(android.location.Location location) {
        if (postExecutor == null) {
            postExecutor = Executors.newSingleThreadExecutor();
        }
        Context context = getApplicationContext();
        JSONObject payload = locationToJson(location);
        postExecutor.execute(() -> {
            try {
                LocationStore.sendLocation(context, payload);
            } catch (Exception e) {
                Logger.error("Native location POST failed", e);
            }
        });
    }

    private static JSONObject locationToJson(android.location.Location location) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("latitude", location.getLatitude());
            obj.put("longitude", location.getLongitude());
            obj.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            obj.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
                obj.put("altitudeAccuracy", location.getVerticalAccuracyMeters());
            } else {
                obj.put("altitudeAccuracy", JSONObject.NULL);
            }
            obj.put("simulated", location.isFromMockProvider());
            obj.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
            obj.put("bearing", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
            obj.put("time", location.getTime());
            // Lets the server distinguish native-delivered updates from those
            // forwarded by the JavaScript callback.
            obj.put("source", "native");
        } catch (org.json.JSONException e) {
            Logger.error("Could not serialize location", e);
        }
        return obj;
    }

    // Android API < 30 requires these legacy callbacks to be implemented.
    static LocationListener createLocationListener(final BackgroundGeolocationService service) {
        return new LocationListener() {
            @Override
            public void onLocationChanged(android.location.Location location) {
                service.handleLocationChanged(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {}
        };
    }

    private void requestLocationUpdates() {
        try {
            client.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, currentDistanceFilter, locationCallback);
        } catch (SecurityException ignore) {
            // According to Android Studio, this method can throw a Security Exception if
            // permissions are not yet granted. Rather than check the permissions, which is fiddly,
            // we simply ignore the exception.
        }
    }

    // Promote the service to the foreground if necessary.
    // Ideally we would only call 'startForeground' if the service is not already
    // foregrounded. Unfortunately, 'getForegroundServiceType' was only introduced
    // in API level 29 and seems to behave weirdly, as reported in #120. However,
    // it appears that 'startForeground' is idempotent, so we just call it repeatedly
    // each time a background watcher is added.
    private void promoteToForeground(String notificationTitle, String notificationMessage) {
        try {
            // This method has been known to fail due to weird
            // permission bugs, so we prevent any exceptions from
            // crashing the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createBackgroundNotification(notificationTitle, notificationMessage),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            } else {
                startForeground(NOTIFICATION_ID, createBackgroundNotification(notificationTitle, notificationMessage));
            }
        } catch (Exception exception) {
            Logger.error("Failed to foreground service", exception);
        }
    }

    // Handles requests from the activity.
    public class LocalBinder extends Binder {

        void start(
            final String id,
            final String notificationTitle,
            final String notificationMessage,
            float distanceFilter,
            final String url
        ) {
            releaseMediaPlayer();
            acquireWakeLock();
            client = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            callbackId = id;
            currentDistanceFilter = distanceFilter;

            nativePostUrl = (url == null || url.isEmpty()) ? null : url;
            LocationStore.saveSetup(getApplicationContext(), nativePostUrl, notificationTitle, notificationMessage, distanceFilter);

            // The service may already be running (for example after a sticky
            // restart), so drop any previous listener before registering a new one.
            if (locationCallback != null) {
                client.removeUpdates(locationCallback);
            }
            locationCallback = createLocationListener(BackgroundGeolocationService.this);
            requestLocationUpdates();
            promoteToForeground(notificationTitle, notificationMessage);
        }

        String stop() {
            LocationStore.clear(getApplicationContext());
            nativePostUrl = null;
            stopWatchdog();
            client.removeUpdates(locationCallback);
            stopForeground(true);
            stopSelf();
            releaseMediaPlayer();
            releaseWakeLock();
            return callbackId;
        }

        void setPlannedRoute(String filePath, double[][] routeCoordinates, float distance) {
            route = routeCoordinates;
            distanceThreshold = distance;
            isOffRoute = true;
            try {
                if (mediaPlayer != null) {
                    return;
                }
                mediaPlayer = new MediaPlayer();
                AssetManager am = getApplicationContext().getResources().getAssets();
                AssetFileDescriptor assetFileDescriptor = am.openFd("public/" + filePath);

                mediaPlayer.setDataSource(
                    assetFileDescriptor.getFileDescriptor(),
                    assetFileDescriptor.getStartOffset(),
                    assetFileDescriptor.getLength()
                );
                mediaPlayer.setLooping(false);

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Logger.error("MediaPlayer error: what=" + what + ", extra=" + extra);
                    releaseMediaPlayer();
                    return true; // Indicate we handled the error
                });

                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                Logger.error("PlaySound: Unexpected error", e);
                releaseMediaPlayer();
            }
        }
    }

    private Notification createBackgroundNotification(String backgroundTitle, String backgroundMessage) {
        Notification.Builder builder = new Notification.Builder(getApplicationContext())
            .setContentTitle(backgroundTitle)
            .setContentText(backgroundMessage)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis());

        try {
            String name = getAppString("capacitor_background_geolocation_notification_icon", "mipmap/ic_launcher", getApplicationContext());
            String[] parts = name.split("/");
            // It is actually necessary to set a valid icon for the notification to behave
            // correctly when tapped. If there is no icon specified, tapping it will open the
            // app's settings, rather than bringing the application to the foreground.
            builder.setSmallIcon(getAppResourceIdentifier(parts[1], parts[0], getApplicationContext()));
        } catch (Exception e) {
            Logger.error("Could not set notification icon", e);
        }

        try {
            String color = getAppString("capacitor_background_geolocation_notification_color", null, getApplicationContext());
            if (color != null) {
                builder.setColor(Color.parseColor(color));
            }
        } catch (Exception e) {
            Logger.error("Could not set notification color", e);
        }

        Intent launchIntent = getApplicationContext()
            .getPackageManager()
            .getLaunchIntentForPackage(getApplicationContext().getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            builder.setContentIntent(
                PendingIntent.getActivity(
                    getApplicationContext(),
                    0,
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                )
            );
        }

        // Set the Channel ID for Android O.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(BackgroundGeolocationService.class.getPackage().getName());
        }

        return builder.build();
    }

    // Gets the identifier of the app's resource by name, returning 0 if not found.
    private static int getAppResourceIdentifier(String name, String defType, Context context) {
        return context.getResources().getIdentifier(name, defType, context.getPackageName());
    }

    // Gets a string from the app's strings.xml file, resorting to a fallback if it is not defined.
    public static String getAppString(String name, String fallback, Context context) {
        int id = getAppResourceIdentifier(name, "string", context);
        return id == 0 ? fallback : context.getString(id);
    }

    private static double haversine(double[] point1, double[] point2) {
        double lon1 = point1[0];
        double lat1 = point1[1];
        double lon2 = point2[0];
        double lat2 = point2[1];

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_M * c;
    }

    private static double distancePointToLineSegment(double[] point, double[] lineStart, double[] lineEnd) {
        // Calculate the distances between the three points using Haversine
        double dist_A_B = haversine(point, lineStart);
        double dist_A_C = haversine(point, lineEnd);
        double dist_B_C = haversine(lineStart, lineEnd);

        // Handle the edge case where the line segment is a single point
        if (dist_B_C == 0) {
            return dist_A_B;
        }

        // Check if the angles at the line segment's endpoints are obtuse.
        // We use the Law of Cosines (c^2 = a^2 + b^2 - 2ab*cos(C))
        // If cos(C) < 0, the angle is obtuse.

        // Angle at B (lineStart)
        // Use a small epsilon to handle floating point inaccuracies in division by zero
        double cos_B = (Math.pow(dist_A_B, 2) + Math.pow(dist_B_C, 2) - Math.pow(dist_A_C, 2)) / (2 * dist_A_B * dist_B_C);
        if (cos_B < 0) {
            return dist_A_B;
        }

        // Angle at C (lineEnd)
        double cos_C = (Math.pow(dist_A_C, 2) + Math.pow(dist_B_C, 2) - Math.pow(dist_A_B, 2)) / (2 * dist_A_C * dist_B_C);
        if (cos_C < 0) {
            return dist_A_C;
        }

        // If both angles are acute, the closest point is on the line segment itself.
        // We can calculate the distance (height of the triangle) using its area.

        // 1. Calculate the semi-perimeter of the triangle ABC
        double s = (dist_A_B + dist_A_C + dist_B_C) / 2;

        // 2. Calculate the area using Heron's formula
        double area = Math.sqrt(Math.max(0, s * (s - dist_A_B) * (s - dist_A_C) * (s - dist_B_C)));

        // 3. The distance is the height of the triangle from point A to the base BC
        // Area = 0.5 * base * height  =>  height = 2 * Area / base
        return (2 * area) / dist_B_C;
    }

    public double distancePointToRoute(double[] point) {
        // If the polyline has less than 2 points, we can't form a segment.
        if (this.route.length < 2) {
            if (this.route.length == 1) {
                return haversine(point, this.route[0]);
            }
            return Double.POSITIVE_INFINITY; // No line segments to measure against
        }

        double minDistance = Double.POSITIVE_INFINITY;

        for (int i = 0; i < this.route.length - 1; i++) {
            double[] lineStart = this.route[i];
            double[] lineEnd = this.route[i + 1];
            double distance = distancePointToLineSegment(point, lineStart, lineEnd);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }

        return minDistance;
    }
}
