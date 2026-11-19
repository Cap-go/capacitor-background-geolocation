package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

// stop() on a plugin instance that never started the service: the app's task was removed
// (swiped away), its activity was recreated, or its process was restarted while native
// delivery ("url") kept the service running.
@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationStopTest {

    private final Application context = ApplicationProvider.getApplicationContext();
    private final ComponentName serviceName = new ComponentName(context, BackgroundGeolocationService.class);

    @After
    public void clearStore() {
        LocationStore.clear(context);
    }

    @Test
    public void stopOnNewInstanceRunsTheServiceStopFromEarlierInstance() {
        saveNativeDeliverySetup();
        BackgroundGeolocationService service = Robolectric.buildService(BackgroundGeolocationService.class).create().get();
        IBinder binder = service.onBind(new Intent(context, BackgroundGeolocationService.class));
        shadowOf(context).setComponentNameAndServiceForBindService(serviceName, binder);
        CountingCall call = stopCall();

        new ContextPlugin(context).stop(call);
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse("stop() must turn native delivery off", LocationStore.isEnabled(context));
        assertTrue("stop() must run the service's own stop()", shadowOf(service).isStoppedBySelf());
        assertEquals("stop() must release its own connection", 1, shadowOf(context).getUnboundServiceConnections().size());
        assertEquals(1, call.resolved.get());
    }

    @Test
    public void stopOnNewInstanceWithoutNativeDeliveryTouchesNoService() {
        CountingCall call = stopCall();

        new ContextPlugin(context).stop(call);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(shadowOf(context).getBoundServiceConnections().isEmpty());
        assertEquals(1, call.resolved.get());
    }

    @Test
    public void stopOnNewInstanceFallsBackToStopServiceWhenBindIsRefused() {
        saveNativeDeliverySetup();
        shadowOf(context).declareComponentUnbindable(serviceName);
        CountingCall call = stopCall();

        new ContextPlugin(context).stop(call);
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(LocationStore.isEnabled(context));
        Intent stopped = shadowOf(context).getNextStoppedService();
        assertNotNull("stop() must still stop the service", stopped);
        assertEquals(BackgroundGeolocationService.class.getName(), stopped.getComponent().getClassName());
        assertEquals(1, call.resolved.get());
    }

    private void saveNativeDeliverySetup() {
        LocationStore.saveSetup(
            context,
            "https://example.com/locations",
            "Title",
            "Message",
            0f,
            new HashMap<>(),
            0L,
            false,
            false,
            1,
            false
        );
        assertTrue(LocationStore.isEnabled(context));
    }

    private static CountingCall stopCall() {
        return new CountingCall("BackgroundGeolocation", "test-callback", "stop", new JSObject());
    }

    private static final class ContextPlugin extends BackgroundGeolocation {

        private final Context context;

        ContextPlugin(Context context) {
            this.context = context;
        }

        @Override
        public Context getContext() {
            return context;
        }
    }

    private static final class CountingCall extends PluginCall {

        final AtomicInteger resolved = new AtomicInteger();

        CountingCall(String pluginId, String callbackId, String methodName, JSObject data) {
            super(null, pluginId, callbackId, methodName, data);
        }

        @Override
        public void resolve(JSObject data) {
            resolved.incrementAndGet();
        }

        @Override
        public void resolve() {
            resolved.incrementAndGet();
        }
    }
}
