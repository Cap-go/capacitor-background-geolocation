package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.PluginCall;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class BackgroundGeolocationPermissionTest {

    private static JSObject locationOnlyPermissions() throws Exception {
        JSObject data = new JSObject();
        JSArray permissions = new JSArray();
        permissions.put("location");
        data.put("permissions", permissions);
        return data;
    }

    private static PluginCall requestPermissionsCall(JSObject data) {
        return new NoHandlerPluginCall("BackgroundGeolocation", "test-callback", "requestPermissions", data);
    }

    @Test
    public void requestLocationPermissionsReturnsFutureWhenCallbackClearsFieldSynchronously() throws Exception {
        SyncPermissionBackgroundGeolocation plugin = new SyncPermissionBackgroundGeolocation(PermissionState.DENIED);

        Method requestLocationPermissions = BackgroundGeolocation.class.getDeclaredMethod("requestLocationPermissions", PluginCall.class);
        requestLocationPermissions.setAccessible(true);

        PluginCall call = new PluginCall(null, "BackgroundGeolocation", "test-callback", "start", new JSObject());

        CompletableFuture<Void> future = (CompletableFuture<Void>) requestLocationPermissions.invoke(plugin, call);

        assertNotNull("start() must chain thenRun on a non-null future", future);
        assertTrue("Denied or cancelled location should complete exceptionally", future.isCompletedExceptionally());
    }

    @Test
    public void requestPermissionsDoesNotReRequestLocationAfterDenial() throws Exception {
        SyncPermissionBackgroundGeolocation plugin = new SyncPermissionBackgroundGeolocation(PermissionState.DENIED);
        AtomicInteger locationRequests = new AtomicInteger();
        plugin.setLocationRequestCounter(locationRequests);

        PluginCall call = requestPermissionsCall(locationOnlyPermissions());
        // Synchronous callback matches Android when another permission dialog is already open.
        plugin.requestPermissionForAlias("location", call, "generalLocationPermissionsCallback");
        assertEquals("Each permission should be requested at most once per call", 1, locationRequests.get());
    }

    private static final class NoHandlerPluginCall extends PluginCall {

        NoHandlerPluginCall(String pluginId, String callbackId, String methodName, JSObject data) {
            super(null, pluginId, callbackId, methodName, data);
        }

        @Override
        public void resolve(JSObject data) {}

        @Override
        public void resolve() {}
    }

    private static final class SyncPermissionBackgroundGeolocation extends BackgroundGeolocation {

        private final PermissionState locationState;
        private AtomicInteger locationRequestCounter;

        SyncPermissionBackgroundGeolocation(PermissionState locationState) {
            this.locationState = locationState;
        }

        void setLocationRequestCounter(AtomicInteger locationRequestCounter) {
            this.locationRequestCounter = locationRequestCounter;
        }

        @Override
        public PermissionState getPermissionState(String alias) {
            if ("location".equals(alias)) {
                return locationState;
            }
            return PermissionState.GRANTED;
        }

        @Override
        protected void requestPermissionForAlias(String alias, PluginCall call, String callbackName) {
            if ("location".equals(alias) && locationRequestCounter != null) {
                locationRequestCounter.incrementAndGet();
            }
            try {
                Method callback = BackgroundGeolocation.class.getDeclaredMethod(callbackName, PluginCall.class);
                callback.setAccessible(true);
                callback.invoke(this, call);
            } catch (ReflectiveOperationException error) {
                throw new RuntimeException(error);
            }
        }
    }
}
