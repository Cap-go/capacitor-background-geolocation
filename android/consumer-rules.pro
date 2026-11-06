# Consumer ProGuard rules for @capgo/background-geolocation (applied to apps that depend on this AAR).

# LocationLogBackend loads this optional implementation with Class.forName when sqlite logging is enabled,
# so the name and matching constructor must survive R8.
-keep class com.capgo.capacitor_background_geolocation.LocationLogSqliteStorage {
  <init>(android.content.Context);
}

# WorkManager instantiates workers by class name from the WorkRequest; keep the public constructor used by the framework.
-keep class * extends androidx.work.Worker {
  <init>(android.content.Context, androidx.work.WorkerParameters);
}
