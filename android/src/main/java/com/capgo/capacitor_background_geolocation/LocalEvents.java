package com.capgo.capacitor_background_geolocation;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// In-process event bus connecting the background service and the geofence
// receiver to the plugin. It replaces LocalBroadcastManager, which AndroidX
// deprecated in favour of exactly this: since every participant lives in the
// same process, there is no reason to route messages through Intents.
//
// Events are always delivered on the main thread, and are dropped when nothing
// is listening — the plugin only listens while it is loaded, which is the same
// behaviour a registered BroadcastReceiver had.
final class LocalEvents {

    interface Listener {
        default void onLocation(String callbackId, Location location) {}

        default void onGeofenceTransition(String payload) {}

        default void onGeofenceError(String payload) {}
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private LocalEvents() {}

    static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static void emitLocation(String callbackId, Location location) {
        dispatch((listener) -> listener.onLocation(callbackId, location));
    }

    static void emitGeofenceTransition(String payload) {
        dispatch((listener) -> listener.onGeofenceTransition(payload));
    }

    static void emitGeofenceError(String payload) {
        dispatch((listener) -> listener.onGeofenceError(payload));
    }

    private static void dispatch(Consumer<Listener> delivery) {
        MAIN_HANDLER.post(() -> {
            for (Listener listener : LISTENERS) {
                delivery.accept(listener);
            }
        });
    }
}
