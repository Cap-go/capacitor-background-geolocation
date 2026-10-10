package com.capgo.capacitor_background_geolocation;

import android.content.Context;
import com.getcapacitor.JSArray;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

// Keeps the locations the plugin received in a file, one per line, as the
// identifier followed by the location as JSON. Identifiers only go up, so the
// lines are in order and the last one read works as a cursor.
final class LocationLog {

    static final int DEFAULT_MAX_ENTRIES = 100000;
    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 10000;

    // Runs the reads and writes one at a time, in order and off the main thread.
    static final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    private static LocationLog instance;

    private final File file;
    private boolean loaded;
    private long lastId;
    private int count;

    static synchronized LocationLog getInstance(Context context) {
        if (instance == null) {
            // Kept out of device backups, which Android skips for the whole app once its files pass 25 MB.
            instance = new LocationLog(new File(context.getNoBackupFilesDir(), "capgo_background_geolocation_location_log.txt"));
        }
        return instance;
    }

    LocationLog(File file) {
        this.file = file;
    }

    // Adds a location. Once the log holds maxEntries, the oldest tenth is removed first.
    synchronized void append(JSONObject location, int maxEntries) throws IOException {
        load();
        if (count >= maxEntries) {
            // Counted again from here, so a removal that fails is not tried again for every location.
            count = 0;
            rewrite(lastId - maxEntries + Math.max(1, maxEntries / 10));
        }
        lastId++;
        // A line starts with its line break, so one that was cut off never runs into the next.
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(("\n" + lastId + " " + location).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        count++;
    }

    // Returns up to limit entries with an identifier above afterId, oldest first.
    synchronized JSArray read(long afterId, int limit) throws IOException {
        JSArray entries = new JSArray();
        if (!file.exists()) {
            return entries;
        }
        try (BufferedReader reader = reader()) {
            String line;
            while (entries.length() < limit && (line = reader.readLine()) != null) {
                long id = idOf(line);
                if (id > afterId) {
                    try {
                        entries.put(new JSONObject(line.substring(line.indexOf(' ') + 1)).put("id", id));
                    } catch (JSONException exception) {
                        // A line that was cut off, or the one an emptied log keeps for its last identifier.
                    }
                }
            }
        }
        return entries;
    }

    // Removes the entries up to and including upToId, or every entry when it is null.
    synchronized void clear(Long upToId) throws IOException {
        if (!file.exists()) {
            return;
        }
        load();
        rewrite(upToId == null ? lastId : upToId);
    }

    // Reads the last identifier and the number of entries, once.
    private void load() throws IOException {
        if (loaded) {
            return;
        }
        count = 0;
        if (file.exists()) {
            try (BufferedReader reader = reader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastId = Math.max(lastId, idOf(line));
                    if (line.indexOf(' ') != -1) {
                        count++;
                    }
                }
            }
        }
        loaded = true;
    }

    // Replaces the file with its lines that have an identifier above upToId. It
    // is written next to the file and then moved over it, so a stop in between
    // leaves the log as it was. An empty log keeps a line with the last
    // identifier, so none is ever reused.
    private void rewrite(long upToId) throws IOException {
        File rewritten = new File(file.getPath() + ".tmp");
        int kept = 0;
        try (BufferedReader reader = reader(); FileOutputStream output = new FileOutputStream(rewritten)) {
            Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (idOf(line) > upToId) {
                    writer.write("\n" + line);
                    kept++;
                }
            }
            if (kept == 0) {
                writer.write("\n" + lastId);
            }
            writer.flush();
            output.getFD().sync();
        } catch (IOException exception) {
            rewritten.delete();
            throw exception;
        }
        if (!rewritten.renameTo(file)) {
            rewritten.delete();
            throw new IOException("Could not replace " + file);
        }
        count = kept;
    }

    private BufferedReader reader() throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
    }

    // The identifier a line starts with, or -1 if it has none.
    private static long idOf(String line) {
        int space = line.indexOf(' ');
        try {
            return Long.parseLong(space == -1 ? line : line.substring(0, space));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
