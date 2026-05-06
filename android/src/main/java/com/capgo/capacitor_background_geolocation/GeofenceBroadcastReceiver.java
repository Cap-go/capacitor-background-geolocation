package com.capgo.capacitor_background_geolocation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.getcapacitor.Logger;
import com.google.android.gms.location.Geofence;
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
            Logger.error("Geofence event failed with code: " + event.getErrorCode());
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
        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                for (Geofence geofence : triggeringGeofences) {
                    JSONObject data = GeofenceStore.buildTransitionData(context, geofence.getRequestId(), enter);
                    Intent localIntent = new Intent(GeofenceStore.ACTION_GEOFENCE_EVENT);
                    localIntent.putExtra(GeofenceStore.EXTRA_GEOFENCE_PAYLOAD, data.toString());
                    LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);
                    GeofenceStore.sendTransition(context, data);
                }
            } catch (Exception exception) {
                Logger.error("Failed to handle geofence transition", exception);
            } finally {
                pendingResult.finish();
            }
        })
            .start();
    }
}
