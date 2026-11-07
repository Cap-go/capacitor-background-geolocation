package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GeofenceSetupAtomicTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        Configuration configuration = new Configuration.Builder().setExecutor(new SynchronousExecutor()).build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration);
    }

    @Test
    public void endpointChangeRaisesGenerationAndStoresNewPayloadTogether() throws Exception {
        JSONObject oldPayload = new JSONObject().put("account", "old");
        GeofenceStore.saveSetup(context, "https://old.example/geo/", true, true, oldPayload, true, Collections.emptyMap());
        GeofenceStore.saveRegion(context, "r1", 0, 0, 50f, true, true, new JSONObject());
        int generationBefore = GeofenceStore.queueGeneration(context);

        JSONObject newPayload = new JSONObject().put("account", "new");
        GeofenceStore.saveSetupForGeofencing(context, "https://new.example/geo/", true, true, newPayload, true, Collections.emptyMap());

        assertEquals(generationBefore + 1, GeofenceStore.queueGeneration(context));
        assertEquals("https://new.example/geo/", GeofenceStore.getUrl(context));

        JSONObject transition = GeofenceStore.buildTransitionData(context, "r1", true, 1_700_000_000_000L);
        assertEquals("new", transition.getJSONObject("payload").getString("account"));
    }

    @Test
    public void handleGeofenceTransitionEnqueuesWhenSetupMatches() throws Exception {
        JSONObject payload = new JSONObject().put("account", "tenant-a");
        GeofenceStore.saveSetup(context, "https://a.example/geo/", true, true, payload, true, Collections.emptyMap());
        GeofenceStore.saveRegion(context, "r1", 0, 0, 50f, true, true, new JSONObject());

        GeofenceStore.handleGeofenceTransition(context, "r1", true, 1_700_000_000_000L);

        List<WorkInfo> workInfos = WorkManager.getInstance(context).getWorkInfosByTag(GeofenceStore.TRANSITION_WORK_TAG).get();
        assertEquals(1, workInfos.size());
    }

    @Test
    public void endpointChangeDropsStaleCaptureGenerationBeforeEnqueue() throws Exception {
        GeofenceStore.saveSetup(
            context,
            "https://old.example/geo/",
            true,
            true,
            new JSONObject().put("account", "old"),
            true,
            Collections.emptyMap()
        );
        GeofenceStore.saveRegion(context, "r1", 0, 0, 50f, true, true, new JSONObject());
        int staleGeneration = GeofenceStore.queueGeneration(context);

        GeofenceStore.saveSetupForGeofencing(
            context,
            "https://new.example/geo/",
            true,
            true,
            new JSONObject().put("account", "new"),
            true,
            Collections.emptyMap()
        );

        GeofenceStore.enqueueTransition(context, GeofenceStore.buildTransitionData(context, "r1", true, 1L), staleGeneration);
        List<WorkInfo> workInfos = WorkManager.getInstance(context).getWorkInfosByTag(GeofenceStore.TRANSITION_WORK_TAG).get();
        assertEquals(0, workInfos.size());
    }
}
