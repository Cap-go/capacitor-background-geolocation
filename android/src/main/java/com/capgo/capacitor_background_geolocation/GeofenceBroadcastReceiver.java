package com.capgo.capacitor_background_geolocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.Logger;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.GeofencingEvent;
import java.util.List;
import org.json.JSONObject;

public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent event = GeofencingEvent.fromIntent(intent);
        if (event == null) {
            return;
        }
        if (event.hasError()) {
            int errorCode = event.getErrorCode();
            String message = GeofenceStatusCodes.getStatusCodeString(errorCode);
            Logger.error("Geofence event failed with code: " + errorCode);
            if (shouldClearStoredRegions(errorCode)) {
                GeofenceStore.clearRegions(context);
            }
            try {
                JSONObject data = new JSONObject();
                data.put("code", errorCode);
                data.put("message", message);
                Intent localIntent = new Intent(GeofenceStore.ACTION_GEOFENCE_ERROR);
                localIntent.putExtra(GeofenceStore.EXTRA_GEOFENCE_ERROR, data.toString());
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);
            } catch (Exception exception) {
                Logger.error("Failed to emit geofence error", exception);
            }
            return;
        }

        int transition = event.getGeofenceTransition();
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) {
            return;
        }

        List<Geofence> triggeringGeofences = event.getTriggeringGeofences();
        if (triggeringGeofences == null || triggeringGeofences.isEmpty()) {
            return;
        }

        boolean enter = transition == Geofence.GEOFENCE_TRANSITION_ENTER;
        try {
            for (Geofence geofence : triggeringGeofences) {
                JSONObject data = GeofenceStore.buildTransitionData(context, geofence.getRequestId(), enter);
                Intent localIntent = new Intent(GeofenceStore.ACTION_GEOFENCE_EVENT);
                localIntent.putExtra(GeofenceStore.EXTRA_GEOFENCE_PAYLOAD, data.toString());
                LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);
                GeofenceStore.enqueueTransition(context, data);
            }
        } catch (Exception exception) {
            Logger.error("Failed to handle geofence transition", exception);
        }
    }

    static boolean shouldClearStoredRegions(int errorCode) {
        return errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE;
    }
}
