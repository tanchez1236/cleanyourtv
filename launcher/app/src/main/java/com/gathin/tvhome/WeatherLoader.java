package com.gathin.tvhome;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.util.List;

/**
 * Reads the current weather for the TV's last known location. Open-Meteo needs no key; the place name
 * comes from BigDataCloud's reverse geocoder in Spanish, with the IP city as the fallback when the TV
 * has never reported a location. The last good reading is cached, so Home has a widget on a TV that
 * boots before its network does.
 *
 * Everything here runs off the UI thread and the parsed data is unit tested on the JVM.
 */
final class WeatherLoader {

    interface Listener {
        /** @param fromNetwork false for the cached reading, true for the just-downloaded one. */
        void onWeather(Data data, boolean fromNetwork);
    }

    static final class Data {
        final String city;
        final double temperature;
        final int code;
        final boolean day;
        final long updated;

        Data(String city, double temperature, int code, boolean day, long updated) {
            this.city = city == null ? "" : city.trim();
            this.temperature = temperature;
            this.code = code;
            this.day = day;
            this.updated = updated;
        }
    }

    private static final String TAG = "Weather";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_BYTES = 128 * 1024;
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%s&longitude=%s&current=temperature_2m,weather_code,is_day&timezone=auto";
    private static final String REVERSE = "https://api.bigdatacloud.net/data/reverse-geocode-client"
            + "?latitude=%s&longitude=%s&localityLanguage=es";
    private static final String IP_LOCATION = "https://ipwho.is/";

    private WeatherLoader() {
    }

    static void load(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Data cached = read(app);
            if (cached != null) {
                Log.i(TAG, "showing cached weather: " + cached.city + " " + Math.round(cached.temperature) + "C");
                deliver(listener, cached, false);
            }

            try {
                Location location = lastLocation(app);
                double latitude, longitude;
                String city;
                if (location != null) {
                    latitude = location.getLatitude();
                    longitude = location.getLongitude();
                    city = place(app, latitude, longitude);
                } else {
                    JSONObject ip = getJson(IP_LOCATION);
                    if (!ip.optBoolean("success", false)) throw new IOException("IP location failed");
                    latitude = ip.getDouble("latitude");
                    longitude = ip.getDouble("longitude");
                    city = ip.optString("city", "").trim();
                }
                if (city.isEmpty()) city = "Mi ciudad";

                Data fresh = fetch(latitude, longitude, city);
                write(app, fresh);
                Log.i(TAG, "weather: " + fresh.city + " " + Math.round(fresh.temperature) + "C, code "
                        + fresh.code + (fresh.day ? " day" : " night"));
                deliver(listener, fresh, true);
            } catch (Exception e) {
                Log.w(TAG, "weather unavailable: " + e.getMessage());
            }
        }, "weather").start();
    }

    private static void deliver(Listener listener, Data data, boolean fromNetwork) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onWeather(data, fromNetwork));
    }

    /** The most recent last-known fix from any provider; null when the TV has no location at all. */
    private static Location lastLocation(Context context) {
        try {
            LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) return null;
            List<String> providers = manager.getProviders(true);
            Location best = null;
            for (String provider : providers) {
                Location location;
                try {
                    location = manager.getLastKnownLocation(provider);
                } catch (SecurityException | IllegalArgumentException ignored) {
                    continue;
                }
                if (location == null) continue;
                if (best == null || location.getTime() > best.getTime()) best = location;
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "location unavailable: " + e.getMessage());
            return null;
        }
    }

    /** "Chimaltenango", in Spanish, from the coordinates the TV reports. */
    private static String place(Context context, double latitude, double longitude) {
        try {
            String url = String.format(java.util.Locale.US, REVERSE, latitude, longitude);
            JSONObject json = getJson(url);
            String city = json.optString("city", "").trim();
            if (city.isEmpty()) city = json.optString("locality", "").trim();
            if (city.isEmpty()) city = json.optString("principalSubdivision", "").trim();
            if (!city.isEmpty()) return city;
        } catch (Exception e) {
            Log.w(TAG, "reverse geocode unavailable: " + e.getMessage());
        }
        try {
            android.location.Geocoder geocoder = new android.location.Geocoder(
                    context, java.util.Locale.getDefault());
            List<android.location.Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                android.location.Address address = addresses.get(0);
                if (address.getLocality() != null) return address.getLocality();
                if (address.getSubAdminArea() != null) return address.getSubAdminArea();
            }
        } catch (Exception e) {
            Log.w(TAG, "Geocoder unavailable: " + e.getMessage());
        }
        return "";
    }

    private static Data fetch(double latitude, double longitude, String city) throws Exception {
        String url = String.format(java.util.Locale.US, FORECAST, latitude, longitude);
        JSONObject current = getJson(url).getJSONObject("current");
        return new Data(city, current.getDouble("temperature_2m"), current.getInt("weather_code"),
                current.optInt("is_day", 1) != 0, System.currentTimeMillis());
    }

    /** The Open-Meteo current block, named so the JVM tests can read it without a TV. */
    static Data parse(String json, String city) throws Exception {
        JSONObject current = new JSONObject(json).getJSONObject("current");
        return new Data(city, current.getDouble("temperature_2m"), current.getInt("weather_code"),
                current.optInt("is_day", 1) != 0, System.currentTimeMillis());
    }

    private static JSONObject getJson(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "CleanYourTV");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);

            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int total = 0, read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_BYTES) throw new IOException("response too large");
                    out.write(buffer, 0, read);
                }
                try {
                    return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                } catch (org.json.JSONException e) {
                    throw new IOException("answer is not JSON", e);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static File cacheFile(Context context) {
        return new File(context.getCacheDir(), "weather.json");
    }

    private static Data read(Context context) {
        File file = cacheFile(context);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0, n;
            while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
            JSONObject json = new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8));
            return new Data(json.optString("city", ""), json.optDouble("temperature", 0),
                    json.optInt("code", 0), json.optBoolean("day", true),
                    json.optLong("updated", 0));
        } catch (Exception e) {
            Log.w(TAG, "ignoring cached weather: " + e.getMessage());
            return null;
        }
    }

    private static void write(Context context, Data data) {
        try {
            JSONObject json = new JSONObject()
                    .put("city", data.city)
                    .put("temperature", data.temperature)
                    .put("code", data.code)
                    .put("day", data.day)
                    .put("updated", data.updated);
            File temp = new File(context.getCacheDir(), "weather.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            File target = cacheFile(context);
            //noinspection ResultOfMethodCallIgnored
            if (!temp.renameTo(target)) temp.delete();
        } catch (Exception e) {
            Log.w(TAG, "could not cache weather: " + e.getMessage());
        }
    }
}
