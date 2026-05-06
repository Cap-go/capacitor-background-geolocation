package com.capgo.capacitor_background_geolocation;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "BackgroundGeolocation",
    permissions = {
        @Permission(strings = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, alias = "location"),
        @Permission(strings = { Manifest.permission.ACCESS_BACKGROUND_LOCATION }, alias = "backgroundLocation"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notification")
    }
)
public class BackgroundGeolocation extends Plugin {

    private static final int GEOFENCE_PENDING_INTENT_REQUEST_CODE = 83620;

    private final String pluginVersion = "";

    private CompletableFuture<BackgroundGeolocationService.LocalBinder> serviceConnectionFuture;
    private CompletableFuture<Void> locationPermissionFuture;
    private CompletableFuture<Void> geofencePermissionFuture;
    private BroadcastReceiver serviceReceiver;
    private BroadcastReceiver geofenceEventReceiver;

    private void fetchLastLocation(PluginCall call) {
        try {
            LocationServices.getFusedLocationProviderClient(getContext())
                .getLastLocation()
                .addOnSuccessListener(getActivity(), (location) -> {
                    if (location != null) {
                        call.resolve(formatLocation(location));
                    }
                });
        } catch (SecurityException ignore) {}
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void start(final PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED && !call.getBoolean("requestPermissions", true)) {
            call.reject("User denied location permission", "NOT_AUTHORIZED");
            return;
        }

        if (serviceConnectionFuture != null) {
            call.reject("Service already started", "ALREADY_STARTED");
            return;
        }

        if (getPermissionState("location") != PermissionState.GRANTED && call.getBoolean("requestPermissions", true)) {
            call.setKeepAlive(true);
            requestLocationPermissions(call)
                .thenRun(() -> {
                    proceedWithStart(call);
                })
                .exceptionally((throwable) -> {
                    call.reject("User denied location permission", "NOT_AUTHORIZED");
                    return null;
                });
            return;
        }

        // location permission granted.
        if (!isLocationEnabled(getContext())) {
            call.reject("Location services disabled.", "NOT_AUTHORIZED");
            return;
        }

        // Everything is OK, continuing to adding a watcher
        call.setKeepAlive(true);
        proceedWithStart(call);
    }

    private void proceedWithStart(PluginCall call) {
        if (call.getBoolean("stale", false)) {
            fetchLastLocation(call);
        }
        getServiceConnection().thenAccept((serviceBinder) -> {
            serviceBinder.start(
                call.getCallbackId(),
                call.getString("backgroundTitle", "Using your location"),
                call.getString("backgroundMessage", ""),
                call.getFloat("distanceFilter", 0f)
            );
        });
    }

    private CompletableFuture<Void> requestLocationPermissions(PluginCall call) {
        if (locationPermissionFuture != null) {
            return locationPermissionFuture;
        }
        locationPermissionFuture = new CompletableFuture<>();
        requestPermissionForAlias("location", call, "locationPermissionsCallback");
        return locationPermissionFuture;
    }

    @PermissionCallback
    private void locationPermissionsCallback(PluginCall call) {
        if (locationPermissionFuture == null) {
            return;
        }

        requestPermissionForAlias("notification", call, "notificationPermissionsCallback");

        if (getPermissionState("location") != PermissionState.GRANTED) {
            locationPermissionFuture.completeExceptionally(new SecurityException("User denied location permission"));
            locationPermissionFuture = null;
            return;
        }

        locationPermissionFuture.complete(null);
        locationPermissionFuture = null;
    }

    @PermissionCallback
    private void notificationPermissionsCallback(PluginCall call) {
        Logger.debug("notification permission callback");
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (serviceConnectionFuture == null) {
            call.resolve();
            return;
        }
        getServiceConnection()
            .thenAccept((service) -> {
                var callbackId = service.stop();
                PluginCall savedCall = getBridge().getSavedCall(callbackId);
                if (savedCall != null) {
                    savedCall.release(getBridge());
                }
                call.resolve();
                serviceConnectionFuture = null;
            })
            .exceptionally((throwable) -> {
                call.reject("Service connection failed: " + throwable.getMessage());
                return null;
            });
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getContext().getPackageName(), null);
        intent.setData(uri);
        getContext().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void setPlannedRoute(PluginCall call) {
        String soundFile = call.getString("soundFile");
        if (soundFile == null || soundFile.isEmpty()) {
            call.reject("Sound file is required");
            return;
        }
        if (serviceConnectionFuture == null) {
            call.reject("Service not started, make sure to call start() first", "NOT_STARTED");
            return;
        }
        try {
            double[][] javaDoubleArray = getJavaDoubleArray(call.getArray("route"));
            serviceConnectionFuture
                .thenAccept((service) -> {
                    service.setPlannedRoute(soundFile, javaDoubleArray, call.getFloat("distance", 50f));
                    call.resolve();
                })
                .exceptionally((throwable) -> {
                    call.reject("Failed to set route: " + throwable.getMessage());
                    return null;
                });
        } catch (Exception ex) {
            call.reject("Unable to parse route parameters");
        }
    }

    @PluginMethod
    public void setupGeofencing(PluginCall call) {
        String url = call.getString("url");
        if (url != null && !url.isEmpty()) {
            try {
                new URL(url);
            } catch (Exception exception) {
                call.reject("Given url is not valid");
                return;
            }
        }

        JSObject payload = call.getObject("payload", new JSObject());
        GeofenceStore.saveSetup(getContext(), url, call.getBoolean("notifyOnEntry", true), call.getBoolean("notifyOnExit", true), payload);

        if (!call.getBoolean("requestPermissions", true)) {
            call.resolve();
            return;
        }

        requestGeofencePermissions(call)
            .thenRun(call::resolve)
            .exceptionally((throwable) -> {
                call.reject("Background location permission is required for geofencing", "NOT_AUTHORIZED");
                return null;
            });
    }

    @PluginMethod
    public void addGeofence(PluginCall call) {
        if (!hasGeofencePermissions()) {
            call.reject("Background location permission is required for geofencing", "NOT_AUTHORIZED");
            return;
        }
        if (!isLocationEnabled(getContext())) {
            call.reject("Location services disabled.", "NOT_AUTHORIZED");
            return;
        }

        Double latitude = call.getDouble("latitude");
        Double longitude = call.getDouble("longitude");
        String identifier = call.getString("identifier");
        double radius = call.getDouble("radius", 50.0);
        if (identifier == null || identifier.isEmpty()) {
            call.reject("Identifier is required");
            return;
        }
        if (latitude == null || latitude < -90 || latitude > 90) {
            call.reject("Latitude must be between -90 and 90");
            return;
        }
        if (longitude == null || longitude < -180 || longitude > 180) {
            call.reject("Longitude must be between -180 and 180");
            return;
        }
        if (radius <= 0) {
            call.reject("Radius must be greater than 0");
            return;
        }

        boolean notifyOnEntry = call.getBoolean("notifyOnEntry", GeofenceStore.getNotifyOnEntry(getContext()));
        boolean notifyOnExit = call.getBoolean("notifyOnExit", GeofenceStore.getNotifyOnExit(getContext()));
        int transitionTypes = 0;
        int initialTrigger = 0;
        if (notifyOnEntry) {
            transitionTypes |= Geofence.GEOFENCE_TRANSITION_ENTER;
            initialTrigger |= GeofencingRequest.INITIAL_TRIGGER_ENTER;
        }
        if (notifyOnExit) {
            transitionTypes |= Geofence.GEOFENCE_TRANSITION_EXIT;
        }
        if (transitionTypes == 0) {
            call.reject("At least one transition must be enabled");
            return;
        }

        JSObject payload = call.getObject("payload", new JSObject());
        Geofence geofence = new Geofence.Builder()
            .setRequestId(identifier)
            .setCircularRegion(latitude, longitude, (float) radius)
            .setTransitionTypes(transitionTypes)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build();
        GeofencingRequest request = new GeofencingRequest.Builder().setInitialTrigger(initialTrigger).addGeofence(geofence).build();

        try {
            getGeofencingClient()
                .addGeofences(request, getGeofencePendingIntent())
                .addOnSuccessListener((unused) -> {
                    try {
                        GeofenceStore.saveRegion(
                            getContext(),
                            identifier,
                            latitude,
                            longitude,
                            (float) radius,
                            notifyOnEntry,
                            notifyOnExit,
                            payload
                        );
                        call.resolve();
                    } catch (JSONException exception) {
                        call.reject("Could not persist geofence", exception);
                    }
                })
                .addOnFailureListener((exception) -> call.reject("Could not start monitoring the geofence", exception));
        } catch (SecurityException exception) {
            call.reject("Background location permission is required for geofencing", "NOT_AUTHORIZED", exception);
        }
    }

    @PluginMethod
    public void removeGeofence(PluginCall call) {
        String identifier = call.getString("identifier");
        if (identifier == null || identifier.isEmpty()) {
            call.reject("Identifier is required");
            return;
        }
        getGeofencingClient()
            .removeGeofences(Collections.singletonList(identifier))
            .addOnSuccessListener((unused) -> {
                GeofenceStore.removeRegion(getContext(), identifier);
                call.resolve();
            })
            .addOnFailureListener((exception) -> call.reject("Could not stop monitoring the geofence", exception));
    }

    @PluginMethod
    public void removeAllGeofences(PluginCall call) {
        getGeofencingClient()
            .removeGeofences(getGeofencePendingIntent())
            .addOnSuccessListener((unused) -> {
                GeofenceStore.clearRegions(getContext());
                call.resolve();
            })
            .addOnFailureListener((exception) -> call.reject("Could not stop monitoring geofences", exception));
    }

    @PluginMethod
    public void getMonitoredGeofences(PluginCall call) {
        JSObject result = new JSObject();
        Set<String> regionIds = GeofenceStore.getRegionIds(getContext());
        JSArray regions = new JSArray();
        for (String regionId : regionIds) {
            regions.put(regionId);
        }
        result.put("regions", regions);
        call.resolve(result);
    }

    private CompletableFuture<Void> requestGeofencePermissions(PluginCall call) {
        if (hasGeofencePermissions()) {
            return CompletableFuture.completedFuture(null);
        }
        if (geofencePermissionFuture != null) {
            return geofencePermissionFuture;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        geofencePermissionFuture = future;
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "geofenceLocationPermissionsCallback");
            return future;
        }
        requestBackgroundLocationPermissionIfNeeded(call);
        return future;
    }

    @PermissionCallback
    private void geofenceLocationPermissionsCallback(PluginCall call) {
        if (geofencePermissionFuture == null) {
            return;
        }
        if (getPermissionState("location") != PermissionState.GRANTED) {
            geofencePermissionFuture.completeExceptionally(new SecurityException("User denied location permission"));
            geofencePermissionFuture = null;
            return;
        }
        requestBackgroundLocationPermissionIfNeeded(call);
    }

    @PermissionCallback
    private void geofenceBackgroundPermissionsCallback(PluginCall call) {
        if (geofencePermissionFuture == null) {
            return;
        }
        if (!hasBackgroundLocationPermission()) {
            geofencePermissionFuture.completeExceptionally(new SecurityException("User denied background location permission"));
            geofencePermissionFuture = null;
            return;
        }
        geofencePermissionFuture.complete(null);
        geofencePermissionFuture = null;
    }

    private void requestBackgroundLocationPermissionIfNeeded(PluginCall call) {
        if (hasBackgroundLocationPermission()) {
            geofencePermissionFuture.complete(null);
            geofencePermissionFuture = null;
            return;
        }
        requestPermissionForAlias("backgroundLocation", call, "geofenceBackgroundPermissionsCallback");
    }

    private boolean hasGeofencePermissions() {
        return getPermissionState("location") == PermissionState.GRANTED && hasBackgroundLocationPermission();
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return (
            ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        );
    }

    private GeofencingClient getGeofencingClient() {
        return LocationServices.getGeofencingClient(getContext());
    }

    private PendingIntent getGeofencePendingIntent() {
        Intent intent = new Intent(getContext(), GeofenceBroadcastReceiver.class);
        intent.setPackage(getContext().getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(getContext(), GEOFENCE_PENDING_INTENT_REQUEST_CODE, intent, flags);
    }

    private static double[][] getJavaDoubleArray(JSArray jsArray) throws JSONException {
        int rows = jsArray.length();
        if (rows == 0) {
            return new double[0][2];
        }

        JSONArray firstRow = jsArray.getJSONArray(0);
        int cols = firstRow.length();

        var javaDoubleArray = new double[rows][cols];

        for (int i = 0; i < rows; i++) {
            JSONArray rowArray = jsArray.getJSONArray(i);
            if (rowArray.length() != cols) {
                throw new JSONException("Input array is not a consistent 2D array.");
            }
            for (int j = 0; j < cols; j++) {
                javaDoubleArray[i][j] = rowArray.getDouble(j);
            }
        }
        return javaDoubleArray;
    }

    // Checks if device-wide location services are disabled
    private static Boolean isLocationEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } else {
            return (
                Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                Settings.Secure.LOCATION_MODE_OFF
            );
        }
    }

    private static JSObject formatLocation(Location location) {
        JSObject obj = new JSObject();
        obj.put("latitude", location.getLatitude());
        obj.put("longitude", location.getLongitude());
        // The docs state that all Location objects have an accuracy, but then why is there a
        // hasAccuracy method? Better safe than sorry.
        obj.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
        obj.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
        if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
            obj.put("altitudeAccuracy", location.getVerticalAccuracyMeters());
        } else {
            obj.put("altitudeAccuracy", JSONObject.NULL);
        }
        // In addition to mocking locations in development, Android allows the
        // installation of apps which have the power to simulate location
        // readings in other apps.
        obj.put("simulated", location.isFromMockProvider());
        obj.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
        obj.put("bearing", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
        obj.put("time", location.getTime());
        return obj;
    }

    // Receives messages from the service.
    private class ServiceReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra("id");
            PluginCall call = getBridge().getSavedCall(id);
            if (call == null) {
                return;
            }
            Location location = intent.getParcelableExtra("location");
            if (location != null) {
                call.resolve(formatLocation(location));
            } else {
                Logger.debug("No locations received");
            }
        }
    }

    private class GeofenceEventReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            boolean errorEvent = GeofenceStore.ACTION_GEOFENCE_ERROR.equals(intent.getAction());
            String payload = intent.getStringExtra(errorEvent ? GeofenceStore.EXTRA_GEOFENCE_ERROR : GeofenceStore.EXTRA_GEOFENCE_PAYLOAD);
            if (payload == null || payload.isEmpty()) {
                return;
            }
            try {
                notifyListeners(
                    errorEvent ? "geofenceError" : "geofenceTransition",
                    GeofenceStore.toJSObject(new JSONObject(payload)),
                    true
                );
            } catch (JSONException exception) {
                Logger.error("Could not parse geofence payload", exception);
            }
        }
    }

    @Override
    public void load() {
        super.load();

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                BackgroundGeolocationService.class.getPackage().getName(),
                BackgroundGeolocationService.getAppString(
                    "capacitor_background_geolocation_notification_channel_name",
                    "Background Tracking",
                    getContext()
                ),
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }

        serviceReceiver = new ServiceReceiver();
        LocalBroadcastManager.getInstance(this.getContext()).registerReceiver(
            serviceReceiver,
            new IntentFilter(BackgroundGeolocationService.ACTION_BROADCAST)
        );

        geofenceEventReceiver = new GeofenceEventReceiver();
        IntentFilter geofenceFilter = new IntentFilter(GeofenceStore.ACTION_GEOFENCE_EVENT);
        geofenceFilter.addAction(GeofenceStore.ACTION_GEOFENCE_ERROR);
        LocalBroadcastManager.getInstance(this.getContext()).registerReceiver(geofenceEventReceiver, geofenceFilter);
    }

    private CompletableFuture<BackgroundGeolocationService.LocalBinder> getServiceConnection() {
        if (serviceConnectionFuture != null && !serviceConnectionFuture.isCompletedExceptionally()) {
            return serviceConnectionFuture;
        }

        serviceConnectionFuture = new CompletableFuture<>();

        Intent serviceIntent = new Intent(this.getContext(), BackgroundGeolocationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.getContext().startForegroundService(serviceIntent);
        } else {
            this.getContext().startService(serviceIntent);
        }

        this.getContext().bindService(
            serviceIntent,
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    serviceConnectionFuture.complete((BackgroundGeolocationService.LocalBinder) binder);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    serviceConnectionFuture = null;
                }
            },
            Context.BIND_AUTO_CREATE
        );

        return serviceConnectionFuture;
    }

    @Override
    protected void handleOnDestroy() {
        if (serviceConnectionFuture != null) {
            serviceConnectionFuture.thenAccept(BackgroundGeolocationService.LocalBinder::stop);
        }

        if (locationPermissionFuture != null && !locationPermissionFuture.isDone()) {
            locationPermissionFuture.cancel(true);
        }
        if (geofencePermissionFuture != null && !geofencePermissionFuture.isDone()) {
            geofencePermissionFuture.cancel(true);
        }
        if (serviceReceiver != null) {
            LocalBroadcastManager.getInstance(this.getContext()).unregisterReceiver(serviceReceiver);
            serviceReceiver = null;
        }
        if (geofenceEventReceiver != null) {
            LocalBroadcastManager.getInstance(this.getContext()).unregisterReceiver(geofenceEventReceiver);
            geofenceEventReceiver = null;
        }
        super.handleOnDestroy();
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            ret.put("version", this.pluginVersion);
            call.resolve(ret);
        } catch (final Exception e) {
            call.reject("Could not get plugin version", e);
        }
    }
}
