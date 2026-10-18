package com.capgo.capacitor_background_geolocation;

import android.location.Location;
import android.os.SystemClock;

/**
 * Software gate for JavaScript (and matching native POST) delivery when
 * {@code intervalMs} is set: native {@code minDistance} stays at zero while
 * movement and time thresholds are applied here.
 */
final class LocationForwardFilter {

    private static final double EARTH_RADIUS_M = 6371000.0;

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
        if (intervalMs <= 0) {
            return true;
        }
        if (lastForwarded == null || distanceFilterMeters <= 0) {
            return true;
        }
        return shouldForwardDecision(
            distanceFilterMeters,
            intervalMs,
            location.getTime(),
            location.getLatitude(),
            location.getLongitude(),
            lastForwarded.getTime(),
            lastForwarded.getLatitude(),
            lastForwarded.getLongitude()
        );
    }

    static boolean shouldForwardDecision(
        float distanceFilterMeters,
        long intervalMs,
        long timeMs,
        double latitude,
        double longitude,
        long lastTimeMs,
        double lastLatitude,
        double lastLongitude
    ) {
        if (distanceFilterMeters > 0) {
            double moved = distanceMeters(latitude, longitude, lastLatitude, lastLongitude);
            if (moved >= distanceFilterMeters) {
                return true;
            }
        }
        if (intervalMs > 0 && timeMs - lastTimeMs >= intervalMs) {
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

    static double distanceMeters(Location from, Location to) {
        return distanceMeters(from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude());
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double radLat1 = Math.toRadians(lat1);
        double radLon1 = Math.toRadians(lon1);
        double radLat2 = Math.toRadians(lat2);
        double radLon2 = Math.toRadians(lon2);
        double dLat = radLat2 - radLat1;
        double dLon = radLon2 - radLon1;
        double a =
            Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }
}
