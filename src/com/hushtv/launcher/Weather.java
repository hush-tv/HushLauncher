package com.hushtv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * The weather for one chosen town, from the free Open-Meteo service (no account or key).
 * This is the launcher's only use of the internet: the town's name is sent once to look up
 * where it is, and after that only its position, rounded to about a kilometre.
 */
final class Weather {
    /** How long a reading is shown before a new one is fetched. */
    static final long FRESH_MILLIS = 30 * 60_000L;
    /** A reading older than this is not shown at all. */
    private static final long RETRY_MILLIS = 15 * 60_000L;
    private static final long STALE_MILLIS = 3 * 60 * 60_000L;

    static final int CLEAR = 0, PARTLY = 1, CLOUD = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;

    private final SharedPreferences sp;

    Weather(Context ctx) {
        sp = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** The chosen town's name, or null if none has been set. */
    String place() {
        try {
            return sp.getString("weather_place", null);
        } catch (ClassCastException e) {
            return null;
        }
    }

    // Wrong-typed values (e.g. from an old or edited backup) read as the default instead of crashing.
    private long longOf(String key) {
        try { return sp.getLong(key, 0); } catch (ClassCastException e) { return 0; }
    }

    private float floatOf(String key) {
        try { return sp.getFloat(key, 0f); } catch (ClassCastException e) { return 0f; }
    }

    boolean hasReading() {
        return place() != null && System.currentTimeMillis() - longOf("weather_at") < STALE_MILLIS;
    }

    boolean due() {
        long now = System.currentTimeMillis();
        // After a failed try, wait a while rather than asking again every time the home screen shows.
        return place() != null && now - longOf("weather_at") > FRESH_MILLIS
                && now - longOf("weather_tried_at") > RETRY_MILLIS;
    }

    float tempC() { return floatOf("weather_temp"); }
    float highC() { return floatOf("weather_hi"); }
    float lowC() { return floatOf("weather_lo"); }
    boolean day() {
        try { return sp.getBoolean("weather_day", true); } catch (ClassCastException e) { return true; }
    }

    private int code() {
        try { return sp.getInt("weather_code", 0); } catch (ClassCastException e) { return 0; }
    }

    /** Which picture suits the current conditions. */
    int kind() {
        int c = code();
        if (c == 0) return CLEAR;
        if (c <= 2) return PARTLY;
        if (c == 3) return CLOUD;
        if (c == 45 || c == 48) return FOG;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return SNOW;
        if (c >= 95) return STORM;
        return RAIN;
    }

    /** How much is falling, from 0.3 (drizzle, light snow) to 1 (heavy rain or snow, storms). */
    float amount() {
        switch (code()) {
            case 51: case 56: case 71: case 77: return 0.3f;
            case 53: case 55: case 57: case 61: case 80: case 85: return 0.5f;
            case 63: case 66: case 73: case 81: return 0.75f;
            default: return 1f;
        }
    }

    /** The current conditions in a word or two. */
    String words() {
        switch (code()) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }

    /**
     * Looks a town up by name and makes it the chosen one. Returns its full name ("London,
     * United Kingdom"), or null if nothing by that name was found. Call off the UI thread.
     */
    String choosePlace(String typed) throws IOException {
        try {
            JSONObject found = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(typed.trim(), "UTF-8") + "&count=1&language=en&format=json"));
            JSONArray results = found.optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject r = results.getJSONObject(0);
            String name = r.getString("name");
            String country = r.optString("country", "");
            sp.edit().putString("weather_place", name)
                    .putFloat("weather_lat", (float) r.getDouble("latitude"))
                    .putFloat("weather_lon", (float) r.getDouble("longitude"))
                    .remove("weather_at").remove("weather_tried_at").commit();
            return country.isEmpty() ? name : name + ", " + country;
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Forgets the last failed try, so the next refresh goes ahead at once. */
    void clearRetryWait() {
        sp.edit().remove("weather_tried_at").apply();
    }

        void clearPlace() {
        sp.edit().remove("weather_place").remove("weather_at").remove("weather_tried_at").apply();
    }

    /** Fetches the current conditions for the chosen town. Call off the UI thread. */
    void refresh() throws IOException {
        if (place() == null) return;
        sp.edit().putLong("weather_tried_at", System.currentTimeMillis()).apply();
        try {
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f"
                            + "&longitude=%.2f&current=temperature_2m,weather_code,is_day"
                            + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                    floatOf("weather_lat"), floatOf("weather_lon"));
            JSONObject root = new JSONObject(get(url));
            JSONObject now = root.getJSONObject("current");
            JSONObject daily = root.getJSONObject("daily");
            sp.edit().putFloat("weather_temp", (float) now.getDouble("temperature_2m"))
                    .putInt("weather_code", now.getInt("weather_code"))
                    .putBoolean("weather_day", now.optInt("is_day", 1) == 1)
                    .putFloat("weather_hi", (float) daily.getJSONArray("temperature_2m_max").getDouble(0))
                    .putFloat("weather_lo", (float) daily.getJSONArray("temperature_2m_min").getDouble(0))
                    .putLong("weather_at", System.currentTimeMillis()).commit();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static String get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "tv-launcher");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                // The read timeout is per read, so a slow trickle could otherwise go on for ever.
                long deadline = System.currentTimeMillis() + 20_000;
                while ((n = in.read(buf)) > 0) {
                    if (System.currentTimeMillis() > deadline) throw new IOException("too slow");
                    out.write(buf, 0, n);
                    if (out.size() > 200_000) throw new IOException("reply too large");
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
