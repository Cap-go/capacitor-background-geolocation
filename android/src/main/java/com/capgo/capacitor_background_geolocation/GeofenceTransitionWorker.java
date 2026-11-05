package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.getcapacitor.Logger;
import org.json.JSONException;
import org.json.JSONObject;

public class GeofenceTransitionWorker extends Worker {

    public GeofenceTransitionWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String payload = getInputData().getString(GeofenceStore.EXTRA_GEOFENCE_PAYLOAD);
        if (payload == null || payload.isEmpty()) {
            return Result.success();
        }
        try {
            int generation = getInputData().getInt(GeofenceStore.EXTRA_QUEUE_GENERATION, 0);
            GeofenceStore.sendTransition(getApplicationContext(), new JSONObject(payload), generation);
            return Result.success();
        } catch (JSONException exception) {
            Logger.error("Invalid geofence transition payload", exception);
            return Result.failure();
        } catch (Exception exception) {
            Logger.error("Failed to send geofence transition", exception);
            return Result.retry();
        }
    }
}
