# Consumer ProGuard rules for @capgo/background-geolocation (applied to apps that depend on this AAR).

# LocationLogBackend loads this optional implementation with Class.forName when sqlite logging is enabled,
# so the name and matching constructor must survive R8.
-keep class com.capgo.capacitor_background_geolocation.LocationLogSqliteStorage {
  <init>(android.content.Context);
}

# WorkManager instantiates workers by class name from the WorkRequest; keep this plugin worker and its public constructor.
-keep class com.capgo.capacitor_background_geolocation.GeofenceTransitionWorker {
  <init>(android.content.Context, androidx.work.WorkerParameters);
}
