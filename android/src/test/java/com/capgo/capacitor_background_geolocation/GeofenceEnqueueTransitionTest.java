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
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class GeofenceEnqueueTransitionTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        Configuration configuration = new Configuration.Builder().setExecutor(new SynchronousExecutor()).build();
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration);
    }

    @Test
    public void dropsEnqueueWhenCaptureGenerationIsStale() throws Exception {
        GeofenceStore.saveSetup(context, "https://example.com/geo/", true, true, new JSONObject(), true, Collections.emptyMap());
        int beforeRaise = GeofenceStore.queueGeneration(context);
        GeofenceStore.raiseQueueGeneration(context);
        JSONObject data = new JSONObject();
        data.put("identifier", "r1");
        GeofenceStore.enqueueTransition(context, data, beforeRaise);
        List<WorkInfo> workInfos = WorkManager.getInstance(context).getWorkInfosByTag(GeofenceStore.TRANSITION_WORK_TAG).get();
        assertEquals(0, workInfos.size());
    }

    @Test
    public void enqueuesWhenCaptureGenerationMatchesCurrent() throws Exception {
        GeofenceStore.saveSetup(context, "https://example.com/geo/", true, true, new JSONObject(), true, Collections.emptyMap());
        int generation = GeofenceStore.queueGeneration(context);
        JSONObject data = new JSONObject();
        data.put("identifier", "r1");
        GeofenceStore.enqueueTransition(context, data, generation);
        List<WorkInfo> workInfos = WorkManager.getInstance(context).getWorkInfosByTag(GeofenceStore.TRANSITION_WORK_TAG).get();
        assertEquals(1, workInfos.size());
    }
}
