package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginCall;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class BackgroundGeolocationStopOrphanTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        LocationStore.clear(context);
        ShadowApplication application = Shadows.shadowOf((android.app.Application) context);
        application.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        );
    }

    @After
    public void tearDown() {
        LocationStore.clear(context);
    }

    @Test
    public void stopWithoutConnectionNoOpsWhenNativeDeliveryDisabled() {
        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(context);
        AtomicBoolean resolved = new AtomicBoolean();
        PluginCall call = stopCall(resolved);

        plugin.stop(call);

        assertTrue("stop() should resolve immediately when nothing is running", resolved.get());
        assertFalse(LocationStore.isEnabled(context));
    }

    @Test
    public void stopWithoutConnectionStopsOrphanedNativeDelivery() {
        LocationStore.saveSetup(
            context,
            "https://example.com/locations",
            "Tracking",
            "Sharing location",
            0f,
            Collections.emptyMap(),
            10_000L,
            false,
            false,
            LocationLog.DEFAULT_MAX_ENTRIES,
            false
        );
        assertTrue("fixture should run native delivery", LocationStore.isEnabled(context));
        BackgroundGeolocationService service = Robolectric.setupService(BackgroundGeolocationService.class);
        IBinder serviceBinder = service.onBind(new Intent(context, BackgroundGeolocationService.class));
        AtomicReference<ServiceConnection> capturedConnection = new AtomicReference<>();
        Context capturingContext = new ContextWrapper(context) {
            @Override
            public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                capturedConnection.set(conn);
                return true;
            }

            @Override
            public void unbindService(ServiceConnection conn) {}
        };

        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(capturingContext);
        AtomicBoolean resolved = new AtomicBoolean();
        PluginCall call = stopCall(resolved);

        plugin.stop(call);
        ServiceConnection connection = capturedConnection.get();
        assertNotNull("stop() should bind to the running service", connection);
        connection.onServiceConnected(new ComponentName(context, BackgroundGeolocationService.class), serviceBinder);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("stop() should resolve after tearing down orphaned native delivery", resolved.get());
        assertFalse("native delivery prefs should be cleared", LocationStore.isEnabled(context));
    }

    @Test
    public void stopWithoutConnectionFallsBackWhenBindRefused() {
        LocationStore.saveSetup(
            context,
            "https://example.com/locations",
            "Tracking",
            "Sharing location",
            0f,
            Collections.emptyMap(),
            10_000L,
            false,
            false,
            LocationLog.DEFAULT_MAX_ENTRIES,
            false
        );

        AtomicReference<Intent> stoppedServiceIntent = new AtomicReference<>();
        Context bindRefusingContext = new ContextWrapper(context) {
            @Override
            public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                return false;
            }

            @Override
            public boolean stopService(Intent name) {
                stoppedServiceIntent.set(name);
                return super.stopService(name);
            }
        };
        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(bindRefusingContext);
        AtomicBoolean resolved = new AtomicBoolean();
        PluginCall call = stopCall(resolved);

        plugin.stop(call);

        assertTrue("stop() should resolve when bind is refused", resolved.get());
        assertFalse("bind refusal should clear native delivery prefs", LocationStore.isEnabled(context));
        Intent stopIntent = stoppedServiceIntent.get();
        assertNotNull("bind refusal should stop BackgroundGeolocationService", stopIntent);
        assertEquals(BackgroundGeolocationService.class.getName(), stopIntent.getComponent().getClassName());
    }

    @Test
    public void stopWithPendingPermissionStillStopsOrphanedNativeDelivery() throws Exception {
        LocationStore.saveSetup(
            context,
            "https://example.com/locations",
            "Tracking",
            "Sharing location",
            0f,
            Collections.emptyMap(),
            10_000L,
            false,
            false,
            LocationLog.DEFAULT_MAX_ENTRIES,
            false
        );

        AtomicReference<ServiceConnection> capturedConnection = new AtomicReference<>();
        Context capturingContext = new ContextWrapper(context) {
            @Override
            public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                capturedConnection.set(conn);
                return true;
            }

            @Override
            public void unbindService(ServiceConnection conn) {}
        };

        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(capturingContext);
        Field locationPermissionFutureField = BackgroundGeolocation.class.getDeclaredField("locationPermissionFuture");
        locationPermissionFutureField.setAccessible(true);
        locationPermissionFutureField.set(plugin, new CompletableFuture<Void>());

        PluginCall pendingStart = new PluginCall(null, "BackgroundGeolocation", "pending-start", "start", new JSObject()) {
            @Override
            public void setKeepAlive(Boolean keepAlive) {}
        };
        Field watchCallField = BackgroundGeolocation.class.getDeclaredField("watchCall");
        watchCallField.setAccessible(true);
        watchCallField.set(plugin, pendingStart);

        AtomicBoolean stopResolved = new AtomicBoolean();
        plugin.stop(stopCall(stopResolved));
        ServiceConnection connection = capturedConnection.get();
        assertNotNull("stop() should reach orphan bind after canceling a pending permission start", connection);

        BackgroundGeolocationService service = Robolectric.setupService(BackgroundGeolocationService.class);
        IBinder serviceBinder = service.onBind(new Intent(context, BackgroundGeolocationService.class));
        connection.onServiceConnected(new ComponentName(context, BackgroundGeolocationService.class), serviceBinder);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("stop() should resolve after orphan cleanup", stopResolved.get());
        assertFalse("orphan cleanup should clear prefs", LocationStore.isEnabled(context));
    }

    @Test
    public void startRejectedWhileOrphanStopIsPending() throws Exception {
        LocationStore.saveSetup(
            context,
            "https://example.com/locations",
            "Tracking",
            "Sharing location",
            0f,
            Collections.emptyMap(),
            10_000L,
            false,
            false,
            LocationLog.DEFAULT_MAX_ENTRIES,
            false
        );

        AtomicReference<ServiceConnection> capturedConnection = new AtomicReference<>();
        Context slowBindContext = new ContextWrapper(context) {
            @Override
            public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                capturedConnection.set(conn);
                return true;
            }

            @Override
            public void unbindService(ServiceConnection conn) {}
        };

        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(slowBindContext);
        AtomicBoolean stopResolved = new AtomicBoolean();
        plugin.stop(stopCall(stopResolved));

        ServiceConnection orphanConnection = capturedConnection.get();
        assertNotNull("orphan stop should bind without completing", orphanConnection);

        AtomicReference<String> startRejectCode = new AtomicReference<>();
        PluginCall startCall = new PluginCall(null, "BackgroundGeolocation", "start-callback", "start", new JSObject()) {
            @Override
            public void reject(String msg, String code) {
                startRejectCode.set(code);
            }
        };
        plugin.start(startCall);
        assertEquals("start() should block while orphan stop is pending", "ALREADY_STARTED", startRejectCode.get());

        BackgroundGeolocationService service = Robolectric.setupService(BackgroundGeolocationService.class);
        IBinder serviceBinder = service.onBind(new Intent(context, BackgroundGeolocationService.class));
        orphanConnection.onServiceConnected(new ComponentName(context, BackgroundGeolocationService.class), serviceBinder);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertTrue("orphan stop should finish", stopResolved.get());
        assertFalse("orphan stop should clear native delivery prefs", LocationStore.isEnabled(context));
    }

    @Test
    public void secondStopWaitsForOrphanTeardownInProgress() {
        LocationStore.saveSetup(
            context,
            "https://example.com/locations",
            "Tracking",
            "Sharing location",
            0f,
            Collections.emptyMap(),
            10_000L,
            false,
            false,
            LocationLog.DEFAULT_MAX_ENTRIES,
            false
        );

        AtomicReference<ServiceConnection> capturedConnection = new AtomicReference<>();
        Context capturingContext = new ContextWrapper(context) {
            @Override
            public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                capturedConnection.set(conn);
                return true;
            }

            @Override
            public void unbindService(ServiceConnection conn) {}
        };

        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(capturingContext);
        AtomicInteger firstResolved = new AtomicInteger();
        AtomicInteger secondResolved = new AtomicInteger();

        plugin.stop(countingStopCall(firstResolved));
        assertFalse("native delivery prefs should clear before bind callback", LocationStore.isEnabled(context));

        plugin.stop(countingStopCall(secondResolved));
        assertEquals("second stop() must wait for teardown", 0, secondResolved.get());

        ServiceConnection connection = capturedConnection.get();
        assertNotNull(connection);
        BackgroundGeolocationService service = Robolectric.setupService(BackgroundGeolocationService.class);
        IBinder serviceBinder = service.onBind(new Intent(context, BackgroundGeolocationService.class));
        connection.onServiceConnected(new ComponentName(context, BackgroundGeolocationService.class), serviceBinder);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals(1, firstResolved.get());
        assertEquals(1, secondResolved.get());
    }

    @Test
    public void resolveStopCallDoesNotClearReplacementWatchCall() throws Exception {
        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(context);
        PluginCall stopCall = stopCall(new AtomicBoolean());
        PluginCall newWatchCall = new PluginCall(null, "BackgroundGeolocation", "watch-callback", "start", new JSObject()) {
            @Override
            public void setKeepAlive(Boolean keepAlive) {}
        };

        Field watchCallField = BackgroundGeolocation.class.getDeclaredField("watchCall");
        watchCallField.setAccessible(true);
        watchCallField.set(plugin, newWatchCall);

        java.lang.reflect.Method resolveStopCall = BackgroundGeolocation.class.getDeclaredMethod(
            "resolveStopCall",
            PluginCall.class,
            PluginCall.class
        );
        resolveStopCall.setAccessible(true);
        resolveStopCall.invoke(plugin, stopCall, null);

        assertSameWatchCall(
            "resolveStopCall must not clear a watchCall installed after orphan stop began",
            newWatchCall,
            watchCallField.get(plugin)
        );
    }

    private static void assertSameWatchCall(String message, PluginCall expected, Object actual) {
        assertNotNull(message, actual);
        assertEquals(message, expected.getCallbackId(), ((PluginCall) actual).getCallbackId());
    }

    private static PluginCall stopCall(AtomicBoolean resolved) {
        return new PluginCall(null, "BackgroundGeolocation", "test-callback", "stop", new JSObject()) {
            @Override
            public void resolve() {
                resolved.set(true);
            }

            @Override
            public void resolve(JSObject data) {
                resolved.set(true);
            }
        };
    }

    private static PluginCall countingStopCall(AtomicInteger resolved) {
        return new PluginCall(null, "BackgroundGeolocation", "test-callback", "stop", new JSObject()) {
            @Override
            public void resolve() {
                resolved.incrementAndGet();
            }

            @Override
            public void resolve(JSObject data) {
                resolved.incrementAndGet();
            }
        };
    }

    private static final class ContextBoundBackgroundGeolocation extends BackgroundGeolocation {

        private final Context appContext;

        ContextBoundBackgroundGeolocation(Context context) {
            this.appContext = context;
        }

        @Override
        public Context getContext() {
            return appContext;
        }

        @Override
        public PermissionState getPermissionState(String alias) {
            return PermissionState.GRANTED;
        }
    }
}
