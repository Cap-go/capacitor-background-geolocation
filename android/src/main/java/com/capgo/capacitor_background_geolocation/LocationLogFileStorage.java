package com.capgo.capacitor_background_geolocation;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.getcapacitor.JSArray;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

// Keeps the locations the plugin received in a file, one per line, as the
// identifier followed by the location as JSON. Identifiers only go up, so the
// lines are in order and the last one read works as a cursor.
final class LocationLogFileStorage implements LocationLogStorage {

    private final File file;
    private boolean loaded;
    private long lastId;
    private int count;

    LocationLogFileStorage(File file) {
        this.file = file;
    }

    @Override
    public void append(JSONObject location, int maxEntries) throws IOException {
        load();
        if (count >= maxEntries) {
            count = 0;
            rewrite(lastId - maxEntries + Math.max(1, maxEntries / 10));
        }
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(("\n" + (lastId + 1) + " " + location).getBytes(StandardCharsets.UTF_8));
            lastId++;
            count++;
            output.getFD().sync();
        }
    }

    @Override
    public JSArray read(long afterId, int limit) throws IOException {
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

    @Override
    public void clear(Long upToId) throws IOException {
        if (!file.exists()) {
            return;
        }
        load();
        rewrite(upToId == null ? lastId : upToId);
    }

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
        syncParentDirectory(file.getParentFile());
        count = kept;
    }

    private static void syncParentDirectory(File directory) {
        if (directory == null) {
            return;
        }
        try {
            FileDescriptor descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(descriptor);
            } finally {
                Os.close(descriptor);
            }
        } catch (ErrnoException | RuntimeException exception) {
            // Best effort. Some hosts and unit test runtimes do not support directory fsync.
        }
    }

    private BufferedReader reader() throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
    }

    private static long idOf(String line) {
        int space = line.indexOf(' ');
        try {
            return Long.parseLong(space == -1 ? line : line.substring(0, space));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
