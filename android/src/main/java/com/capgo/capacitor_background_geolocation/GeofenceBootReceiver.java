package com.capgo.capacitor_background_geolocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.getcapacitor.Logger;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public class GeofenceBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !shouldRestoreAction(intent.getAction())) {
            return;
        }

        PendingResult pendingResult = goAsync();
        List<Task<Void>> tasks = restorePersistedGeofences(context);
        if (tasks.isEmpty()) {
            pendingResult.finish();
            return;
        }
        Tasks.whenAllComplete(tasks).addOnCompleteListener((unused) -> pendingResult.finish());
    }

    static boolean shouldRestoreAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }

    private static List<Task<Void>> restorePersistedGeofences(Context context) {
        List<Task<Void>> tasks = new ArrayList<>();
        if (!GeofenceStore.getBackgroundLocation(context)) {
            return tasks;
        }
        var client = LocationServices.getGeofencingClient(context);
        for (String identifier : GeofenceStore.getRegionIds(context)) {
            JSONObject region = GeofenceStore.getRegion(context, identifier);
            try {
                tasks.add(
                    client
                        .addGeofences(GeofenceStore.buildGeofencingRequest(region), GeofenceBroadcastReceiver.createPendingIntent(context))
                        .addOnSuccessListener((unused) -> Logger.debug("Restored geofence after boot: " + identifier))
                        .addOnFailureListener((exception) ->
                            Logger.error("Could not restore geofence after boot: " + identifier, exception)
                        )
                );
            } catch (SecurityException exception) {
                Logger.error("Missing permission to restore geofence after boot: " + identifier, exception);
            } catch (Exception exception) {
                GeofenceStore.removeRegion(context, identifier);
                Logger.error("Invalid persisted geofence removed after boot: " + identifier, exception);
            }
        }
        return tasks;
    }
}
