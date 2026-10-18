package com.capgo.capacitor_background_geolocation;

import android.location.Location;
import android.os.SystemClock;

/**
 * Software gate for JavaScript (and matching native POST) delivery when
 * {@code intervalMs} is set: native {@code minDistance} stays at zero while
 * movement and time thresholds are applied here.
 */
final class LocationForwardFilter {

    private float distanceFilterMeters;
    private long intervalMs;
    private Location lastForwarded;
    private long lastForwardedAtElapsed;

    void configure(float distanceFilterMeters, long intervalMs) {
        this.distanceFilterMeters = Math.max(0f, distanceFilterMeters);
        this.intervalMs = Math.max(0L, intervalMs);
        lastForwarded = null;
        lastForwardedAtElapsed = 0L;
    }

    boolean usesSoftwareDistanceFilter() {
        return intervalMs > 0;
    }

    float nativeMinDistanceMeters() {
        return usesSoftwareDistanceFilter() ? 0f : distanceFilterMeters;
    }

    boolean shouldForward(Location location) {
        if (location == null) {
            return false;
        }
        if (lastForwarded == null) {
            return true;
        }
        if (distanceFilterMeters > 0 && location.distanceTo(lastForwarded) >= distanceFilterMeters) {
            return true;
        }
        if (intervalMs > 0 && location.getTime() - lastForwarded.getTime() >= intervalMs) {
            return true;
        }
        return false;
    }

    void markForwarded(Location location) {
        lastForwarded = location;
        lastForwardedAtElapsed = SystemClock.elapsedRealtime();
    }

    long getIntervalMs() {
        return intervalMs;
    }

    Location getLastForwarded() {
        return lastForwarded;
    }

    long millisSinceLastForward() {
        if (lastForwarded == null || lastForwardedAtElapsed == 0L) {
            return Long.MAX_VALUE;
        }
        return SystemClock.elapsedRealtime() - lastForwardedAtElapsed;
    }
}
