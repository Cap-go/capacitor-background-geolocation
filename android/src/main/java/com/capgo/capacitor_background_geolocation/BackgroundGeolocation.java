package com.capgo.capacitor_background_geolocation;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import androidx.annotation.Nullable;
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
import com.google.android.gms.location.LocationServices;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "BackgroundGeolocation",
    permissions = {
        @Permission(strings = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, alias = "location"),
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notification")
    }
)
public class BackgroundGeolocation extends Plugin {

    private final String pluginVersion = "";

    private CompletableFuture<BackgroundGeolocationService.LocalBinder> serviceConnectionFuture;
    private CompletableFuture<Void> locationPermissionFuture;

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

        LocalBroadcastManager.getInstance(this.getContext()).registerReceiver(
            new ServiceReceiver(),
            new IntentFilter(BackgroundGeolocationService.ACTION_BROADCAST)
        );
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
