package com.capgo.capacitor_background_geolocation;

import static org.junit.Assert.*;

import android.content.Context;
import com.getcapacitor.JSArray;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class LocationLogTest {

    private Context context;
    private LocationLog log;
    private ServerSocket server;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        log = new LocationLog(context, "location_log_test.db");
        LocationLog.getInstance(context).clear(null);
    }

    @After
    public void tearDown() throws IOException {
        log.close();
        if (server != null) {
            server.close();
        }
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
        location.put("source", "native");
        return location;
    }

    private static long[] ids(JSArray entries) throws JSONException {
        long[] ids = new long[entries.length()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = entries.getJSONObject(i).getLong("id");
        }
        return ids;
    }

    private String startServer(int responseCode) throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        new Thread(() -> respond(responseCode)).start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/locations";
    }

    private void respond(int responseCode) {
        try (Socket socket = server.accept()) {
            String response = "HTTP/1.1 " + responseCode + " Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            socket.getInputStream().transferTo(OutputStream.nullOutputStream());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void saveSetup(String url, boolean locationLog) {
        LocationStore.saveSetup(context, url, "title", "message", 0f, Collections.emptyMap(), 0L, false, locationLog, 3);
    }

    private void send(JSONObject location) throws IOException {
        LocationStore.sendLocation(context, location, LocationStore.logLocation(context, location));
    }

    @Test
    public void testInsertAddsPendingEntry() throws JSONException {
        long id = log.insert(location(1_700_000_000_000L), 3);

        JSArray entries = log.query(null, null, 10);
        assertEquals(1, entries.length());
        JSONObject entry = entries.getJSONObject(0);
        assertEquals(id, entry.getLong("id"));
        assertEquals(39.7392, entry.getDouble("latitude"), 0);
        assertEquals(-104.9903, entry.getDouble("longitude"), 0);
        assertEquals(5.0, entry.getDouble("accuracy"), 0);
        assertEquals(1609.0, entry.getDouble("altitude"), 0);
        assertEquals(3.0, entry.getDouble("altitudeAccuracy"), 0);
        assertFalse(entry.getBoolean("simulated"));
        assertTrue(entry.isNull("speed"));
        assertEquals(270.0, entry.getDouble("bearing"), 0);
        assertEquals(1_700_000_000_000L, entry.getLong("time"));
        assertEquals("pending", entry.getString("status"));
        assertTrue(entry.isNull("httpStatus"));
        assertTrue(entry.isNull("error"));
    }

    @Test
    public void testQueryFiltersAndPages() throws JSONException {
        long first = log.insert(location(1000), 3);
        long second = log.insert(location(2000), 3);
        long third = log.insert(location(3000), 3);

        assertArrayEquals(new long[] { first, second, third }, ids(log.query(null, null, 10)));
        assertArrayEquals(new long[] { second, third }, ids(log.query(first, null, 10)));
        assertArrayEquals(new long[] { second, third }, ids(log.query(null, 2000L, 10)));
        assertArrayEquals(new long[] { first, second }, ids(log.query(null, null, 2)));
        assertArrayEquals(new long[] { third }, ids(log.query(second, null, 2)));
    }

    @Test
    public void testInsertRemovesOldestEntriesBeyondMaxEntries() throws JSONException {
        log.insert(location(1000), 3);
        long second = log.insert(location(2000), 3);
        long third = log.insert(location(3000), 3);
        long fourth = log.insert(location(4000), 3);

        assertArrayEquals(new long[] { second, third, fourth }, ids(log.query(null, null, 10)));
    }

    @Test
    public void testClearRemovesEntriesUpToIdentifier() throws JSONException {
        long first = log.insert(location(1000), 3);
        long second = log.insert(location(2000), 3);

        log.clear(first);

        assertArrayEquals(new long[] { second }, ids(log.query(null, null, 10)));
    }

    @Test
    public void testIdentifiersAreNotReusedAfterClear() throws JSONException {
        long first = log.insert(location(1000), 3);

        log.clear(null);

        assertEquals(0, log.query(null, null, 10).length());
        assertTrue(log.insert(location(2000), 3) > first);
    }

    @Test
    public void testEntriesSurviveReopeningTheDatabase() throws JSONException {
        long id = log.insert(location(1000), 3);
        log.close();

        log = new LocationLog(context, "location_log_test.db");

        assertArrayEquals(new long[] { id }, ids(log.query(null, null, 10)));
    }

    @Test
    public void testReopeningMarksPendingEntriesFailed() throws JSONException {
        log.insert(location(1000), 3);
        log.close();

        log = new LocationLog(context, "location_log_test.db");

        JSONObject entry = log.query(null, null, 10).getJSONObject(0);
        assertEquals("failed", entry.getString("status"));
        assertEquals("The app stopped before the request finished", entry.getString("error"));
    }

    @Test
    public void testLogLocationAddsPendingEntry() throws Exception {
        saveSetup("http://127.0.0.1:1/locations", true);

        LocationStore.logLocation(context, location(1000));

        JSONObject entry = LocationLog.getInstance(context).query(null, null, 10).getJSONObject(0);
        assertEquals("pending", entry.getString("status"));
    }

    @Test
    public void testSendLocationRecordsSentEntry() throws Exception {
        saveSetup(startServer(200), true);

        send(location(1000));

        JSONObject entry = LocationLog.getInstance(context).query(null, null, 10).getJSONObject(0);
        assertEquals("sent", entry.getString("status"));
        assertEquals(200, entry.getInt("httpStatus"));
        assertTrue(entry.isNull("error"));
    }

    @Test
    public void testSendLocationRecordsFailedEntryForErrorResponse() throws Exception {
        saveSetup(startServer(401), true);

        assertThrows(IOException.class, () -> send(location(1000)));

        JSONObject entry = LocationLog.getInstance(context).query(null, null, 10).getJSONObject(0);
        assertEquals("failed", entry.getString("status"));
        assertEquals(401, entry.getInt("httpStatus"));
        assertEquals("Location POST failed with response code: 401", entry.getString("error"));
    }

    @Test
    public void testSendLocationRecordsFailedEntryWhenServerIsUnreachable() throws Exception {
        saveSetup("http://127.0.0.1:1/locations", true);

        assertThrows(IOException.class, () -> send(location(1000)));

        JSONObject entry = LocationLog.getInstance(context).query(null, null, 10).getJSONObject(0);
        assertEquals("failed", entry.getString("status"));
        assertTrue(entry.isNull("httpStatus"));
        assertFalse(entry.isNull("error"));
    }

    @Test
    public void testSendLocationRemovesEntrySkippedByMinInterval() throws Exception {
        LocationStore.saveSetup(context, startServer(200), "title", "message", 0f, Collections.emptyMap(), 5000L, false, true, 3);
        send(location(1000));

        send(location(2000));

        JSArray entries = LocationLog.getInstance(context).query(null, null, 10);
        assertEquals(1, entries.length());
        assertEquals(1000, entries.getJSONObject(0).getLong("time"));
    }

    @Test
    public void testSendLocationAppliesLocationLogMaxEntries() throws Exception {
        saveSetup("http://127.0.0.1:1/locations", true);

        assertThrows(IOException.class, () -> send(location(1000)));
        assertThrows(IOException.class, () -> send(location(2000)));
        assertThrows(IOException.class, () -> send(location(3000)));
        assertThrows(IOException.class, () -> send(location(4000)));

        JSArray entries = LocationLog.getInstance(context).query(null, null, 10);
        assertEquals(3, entries.length());
        assertEquals(2000, entries.getJSONObject(0).getLong("time"));
    }

    @Test
    public void testSendLocationRecordsNothingWhenTheLogIsDisabled() throws Exception {
        saveSetup("http://127.0.0.1:1/locations", false);

        assertThrows(IOException.class, () -> send(location(1000)));

        assertEquals(0, LocationLog.getInstance(context).query(null, null, 10).length());
    }
}
