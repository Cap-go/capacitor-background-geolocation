package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
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
        connection.onServiceConnected(
            new ComponentName(context, BackgroundGeolocationService.class),
            serviceBinder
        );
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

        Context bindRefusingContext = new ContextWrapper(context) {
            @Override
            public boolean bindService(Intent service, ServiceConnection conn, int flags) {
                return false;
            }
        };
        ContextBoundBackgroundGeolocation plugin = new ContextBoundBackgroundGeolocation(bindRefusingContext);
        AtomicBoolean resolved = new AtomicBoolean();
        PluginCall call = stopCall(resolved);

        plugin.stop(call);

        assertTrue("stop() should resolve when bind is refused", resolved.get());
        assertFalse("bind refusal should clear native delivery prefs", LocationStore.isEnabled(context));
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

    private static final class ContextBoundBackgroundGeolocation extends BackgroundGeolocation {

        private final Context appContext;

        ContextBoundBackgroundGeolocation(Context context) {
            this.appContext = context;
        }

        @Override
        public Context getContext() {
            return appContext;
        }
    }
}