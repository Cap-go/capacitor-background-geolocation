package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class LocationLogSqliteStorageTest {

    private LocationLogSqliteStorage storage;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("capgo_background_geolocation.db");
        storage = new LocationLogSqliteStorage(context);
    }

    @Test
    public void testInsertAndRead() throws Exception {
        JSONObject location = new JSONObject();
        location.put("latitude", 39.7392);
        location.put("longitude", -104.9903);
        location.put("accuracy", 5.0);
        location.put("time", 1_700_000_000_000L);

        long id = storage.insert(location, 10);
        assertTrue(id > 0);

        JSArray entries = storage.read(0, 10);
        assertEquals(1, entries.length());
        assertEquals(id, entries.getJSONObject(0).getLong("id"));
        assertEquals(LocationLog.STATUS_PENDING, entries.getJSONObject(0).getString("status"));
    }
}
