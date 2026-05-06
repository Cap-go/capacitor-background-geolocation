package com.capgo.capacitor_background_geolocation;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.Logger;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofenceStatusCodes;
import com.google.android.gms.location.GeofencingEvent;
import java.util.List;
import org.json.JSONObject;

public class GeofenceBroadcastReceiver extends BroadcastReceiver {

    private static final int GEOFENCE_PENDING_INTENT_REQUEST_CODE = 83620;

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

    static PendingIntent createPendingIntent(Context context) {
        Intent intent = new Intent(context, GeofenceBroadcastReceiver.class);
        intent.setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, GEOFENCE_PENDING_INTENT_REQUEST_CODE, intent, flags);
    }
}
