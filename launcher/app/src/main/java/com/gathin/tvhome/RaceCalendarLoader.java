package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the F1 calendar from a URL a theme points at with {@code race_calendar_url}, so the TV can show
 * session times that were changed after the theme was pushed. A copy of the last good download is kept in
 * the app's cache, which makes the panel work on a TV that boots before its Wi-Fi does; until either the
 * cache or the network has data, the {@code race_calendar} embedded in the theme's config.json is shown.
 *
 * Parsing ({@link #parse}) is plain Java plus org.json and is unit tested on the JVM.
 */
final class RaceCalendarLoader {

    /** Called on the main thread with a usable calendar; never with an empty list. */
    interface Listener {
        /** @param fromNetwork false for the cached copy, true for the just-downloaded one. */
        void onCalendar(List<RaceWeekend.Race> calendar, boolean fromNetwork);
    }

    private static final String TAG = "RaceCalendar";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    /** A season is about 30 KB; anything near this is not a calendar and must not fill the TV's storage. */
    private static final int MAX_BYTES = 512 * 1024;

    private RaceCalendarLoader() {
    }

    /**
     * Shows the cached calendar, if there is one, then downloads {@code url} and shows the new one. Any
     * failure leaves the cache (and the theme's own calendar) in place and is only logged.
     */
    static void load(Context context, String url, Listener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            File cache = cacheFile(app, url);
            List<RaceWeekend.Race> cached = read(cache);
            if (!cached.isEmpty()) deliver(listener, cached, false);

            try {
                String body = fetch(url);
                List<RaceWeekend.Race> fresh = parse(body);
                if (fresh.isEmpty()) throw new IOException("calendar has no readable races");
                write(cache, body);
                deliver(listener, fresh, true);
            } catch (Exception e) {
                Log.w(TAG, "remote calendar unavailable (" + url + "): " + e.getMessage());
            }
        }, "race-calendar").start();
    }

    private static void deliver(Listener listener, List<RaceWeekend.Race> calendar, boolean fromNetwork) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onCalendar(calendar, fromNetwork));
    }

    private static String fetch(String url) throws IOException {
        URL target = new URL(url);
        String scheme = target.getProtocol();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IOException("unsupported scheme: " + scheme);
        }

        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            // raw.githubusercontent.com (and most static hosts) are friendlier with a real client name.
            connection.setRequestProperty("User-Agent", "CleanYourTV");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);

            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int total = 0, read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("response larger than " + MAX_BYTES + " bytes");
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    /** A theme's {@code race_calendar} array, or the wrapper object the hosted file and config.json use. */
    static List<RaceWeekend.Race> parse(String text) {
        if (text == null) return Collections.emptyList();
        String trimmed = text.trim();
        if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1);
        if (trimmed.isEmpty()) return Collections.emptyList();
        try {
            if (trimmed.startsWith("[")) return races(new JSONArray(trimmed));
            return parse(new JSONObject(trimmed));
        } catch (Exception e) {
            Log.w(TAG, "ignoring calendar: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /** The {@code race_calendar} array inside {@code json}; an object without it means no races. */
    static List<RaceWeekend.Race> parse(JSONObject json) {
        return json == null ? Collections.emptyList() : races(json.optJSONArray("race_calendar"));
    }

    private static List<RaceWeekend.Race> races(JSONArray list) {
        if (list == null) return Collections.emptyList();
        List<RaceWeekend.Race> races = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject race = list.optJSONObject(i);
            JSONArray sessions = race == null ? null : race.optJSONArray("sessions");
            if (sessions == null) continue;

            List<RaceWeekend.Session> parsed = new ArrayList<>();
            for (int j = 0; j < sessions.length(); j++) {
                JSONObject session = sessions.optJSONObject(j);
                if (session == null) continue;
                String name = session.optString("name", "").trim();
                if (name.isEmpty()) continue;
                try {
                    parsed.add(new RaceWeekend.Session(name,
                            java.time.Instant.parse(session.optString("start", ""))));
                } catch (DateTimeParseException ignored) {
                    // A session without a readable start time cannot be counted down; the rest still can.
                }
            }
            if (!parsed.isEmpty()) {
                races.add(new RaceWeekend.Race(race.optInt("round", 0),
                        race.optString("name", "Grand Prix").trim(),
                        race.optString("place", "").trim(), parsed));
            }
        }
        return races;
    }

    private static File cacheFile(Context context, String url) {
        return new File(context.getCacheDir(), "race_calendar_" + Integer.toHexString(url.hashCode()) + ".json");
    }

    private static List<RaceWeekend.Race> read(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return Collections.emptyList();
        if (file.length() > MAX_BYTES) return Collections.emptyList();
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0, n;
            while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
            return parse(new String(bytes, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "ignoring cached calendar: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static void write(File file, String body) {
        if (file == null) return;
        try {
            // Write beside the cache and rename, so a power cut cannot leave half a calendar behind.
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) {
                // Some Android storage backs rename off; the cached copy is optional, so just drop the temp.
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "could not cache calendar: " + e.getMessage());
        }
    }
}
