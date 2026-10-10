package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.*;

import com.getcapacitor.JSArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocationLogTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File file;
    private LocationLog log;

    @Before
    public void setUp() {
        file = new File(folder.getRoot(), "location_log.txt");
        log = new LocationLog(file);
    }

    private static JSONObject location(long time) throws JSONException {
        JSONObject location = new JSONObject();
        location.put("latitude", 39.7392);
        location.put("longitude", -104.9903);
        location.put("accuracy", 5.0);
        location.put("altitude", 1609.0);
        location.put("altitudeAccuracy", 3.0);
        location.put("simulated", false);
        location.put("speed", JSONObject.NULL);
        location.put("bearing", 270.0);
        location.put("time", time);
        return location;
    }

    private static long[] ids(JSArray entries) throws JSONException {
        long[] ids = new long[entries.length()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = entries.getJSONObject(i).getLong("id");
        }
        return ids;
    }

    private void appendLocations(int count, int maxEntries) throws IOException, JSONException {
        for (int i = 0; i < count; i++) {
            log.append(location(1000 * (i + 1)), maxEntries);
        }
    }

    @Test
    public void testReadReturnsTheLocationWithItsIdentifier() throws Exception {
        assertEquals(0, log.read(0, 10).length());

        log.append(location(1_700_000_000_000L), 10);

        JSArray entries = log.read(0, 10);
        assertEquals(1, entries.length());
        JSONObject entry = entries.getJSONObject(0);
        assertEquals(1, entry.getLong("id"));
        assertEquals(39.7392, entry.getDouble("latitude"), 0);
        assertFalse(entry.getBoolean("simulated"));
        assertTrue(entry.isNull("speed"));
        assertEquals(1_700_000_000_000L, entry.getLong("time"));
    }

    @Test
    public void testReadReturnsEntriesAfterAfterIdUpToLimit() throws Exception {
        appendLocations(3, 10);

        assertArrayEquals(new long[] { 1, 2, 3 }, ids(log.read(0, 10)));
        assertArrayEquals(new long[] { 2, 3 }, ids(log.read(1, 10)));
        assertArrayEquals(new long[] { 1, 2 }, ids(log.read(0, 2)));
        assertEquals(0, log.read(3, 10).length());
    }

    @Test
    public void testClearKeepsALocationAddedAfterTheRead() throws Exception {
        appendLocations(2, 10);
        long[] read = ids(log.read(0, 10));
        appendLocations(1, 10);

        log.clear(read[read.length - 1]);

        assertArrayEquals(new long[] { 3 }, ids(log.read(0, 10)));
    }

    @Test
    public void testAClearThatFailsLeavesTheLogAsItWas() throws Exception {
        appendLocations(3, 10);
        File inTheWay = new File(file.getPath() + ".tmp");
        assertTrue(new File(inTheWay, "child").mkdirs());

        assertThrows(IOException.class, () -> log.clear(1L));

        assertArrayEquals(new long[] { 1, 2, 3 }, ids(log.read(0, 10)));
    }

    @Test
    public void testIdentifiersAreNotReusedAfterTheLogWasCleared() throws Exception {
        appendLocations(2, 10);

        log = new LocationLog(file);
        log.clear(null);
        assertEquals(0, log.read(0, 10).length());
        log = new LocationLog(file);
        appendLocations(1, 10);

        assertArrayEquals(new long[] { 3 }, ids(log.read(0, 10)));
    }

    @Test
    public void testALogThatIsOpenedAgainRemovesTheOldestTenthAtTheSameSize() throws Exception {
        appendLocations(19, 20);

        log = new LocationLog(file);
        appendLocations(1, 20);
        assertEquals(20, log.read(0, 30).length());

        appendLocations(1, 20);

        long[] kept = ids(log.read(0, 30));
        assertEquals(19, kept.length);
        assertEquals(3, kept[0]);
        assertEquals(21, kept[18]);
    }

    @Test
    public void testALineThatWasCutOffDoesNotRunIntoTheNext() throws Exception {
        appendLocations(1, 10);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write("\n2 {\"latitude\":39.7".getBytes(StandardCharsets.UTF_8));
        }

        log.append(location(2000), 10);

        assertArrayEquals(new long[] { 1, 2 }, ids(log.read(0, 10)));
        assertEquals(2000, log.read(1, 10).getJSONObject(0).getLong("time"));
    }
}
