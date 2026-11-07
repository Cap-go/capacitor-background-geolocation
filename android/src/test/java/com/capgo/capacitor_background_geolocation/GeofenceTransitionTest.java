package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.location.Location;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GeofenceTransitionTest {

    @Test
    public void usesTheTimeOfTheTriggeringLocation() {
        Location location = new Location("gps");
        location.setTime(1_762_350_000_000L);
        assertEquals(1_762_350_000_000L, GeofenceBroadcastReceiver.transitionTime(location, 1_762_350_090_000L));
    }

    @Test
    public void treatsOnlyADifferentEndpointAsAUrlChange() {
        assertFalse(GeofenceStore.urlChanged("https://a.example/geo", "https://a.example/geo"));
        assertFalse(GeofenceStore.urlChanged(null, ""));
        assertTrue(GeofenceStore.urlChanged("https://a.example/geo", "https://b.example/geo"));
        assertTrue(GeofenceStore.urlChanged(null, "https://a.example/geo"));
    }

    @Test
    public void raisingTheQueueGenerationOutdatesQueuedJobs() {
        Context context = ApplicationProvider.getApplicationContext();
        int queued = GeofenceStore.queueGeneration(context);
        GeofenceStore.raiseQueueGeneration(context);
        assertEquals(queued + 1, GeofenceStore.queueGeneration(context));
    }

    @Test
    public void fallsBackToNowWithoutALocation() {
        assertEquals(1_762_350_090_000L, GeofenceBroadcastReceiver.transitionTime(null, 1_762_350_090_000L));
    }
}
