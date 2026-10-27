package com.capgo.capacitor_background_geolocation;

import com.getcapacitor.JSArray;
import java.io.IOException;
import org.json.JSONObject;

interface LocationLogStorage {
    void append(JSONObject location, int maxEntries) throws IOException;

    JSArray read(long afterId, int limit) throws IOException;

    void clear(Long upToId) throws IOException;

    default void open() {}

    default long insert(JSONObject location, int maxEntries) {
        return -1;
    }

    default void update(long id, String status, Integer httpStatus, String error) {}

    default void remove(long id) {}

    default boolean tracksPostOutcomes() {
        return false;
    }
}
