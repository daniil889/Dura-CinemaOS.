package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional, provider-specific catalogue adapters.
 *
 * The launcher first consumes content that the streaming app publishes through Android TV
 * Preview Programs / Watch Next and MediaSession. These adapters are explicit fallbacks for
 * services that do not publish those rows:
 *  - YouTube Data API v3 public regional chart (requires the user's API key).
 *  - KinopoiskDev v1.4 metadata (third-party API, requires the user's token).
 *
 * These are discovery cards, not account history or a promise that a title is playable in the
 * user's subscription. Continue Watching always comes from the app's own playback metadata.
 */
final class StreamingCatalog {
    private static final String TAG = "DuraStreamingCatalog";
    private static final String PREFS = "streaming_catalog";
    static final String KEY_YOUTUBE = "youtube_data_api_key";
    static final String KEY_KINOPOISK = "kinopoiskdev_api_key";
    static final String KEY_REGION = "catalog_region";
    private static final String CACHE_PREFIX = "catalog_cache_";
    private static final String TIME_PREFIX = "catalog_time_";
    private static final long CACHE_TTL_MS = 4L * 60L * 60L * 1000L;
    private static final int MAX_ITEMS = 12;

    private StreamingCatalog() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String region(Context c) {
        String saved = prefs(c).getString(KEY_REGION, "").trim().toUpperCase(Locale.ROOT);
        if (saved.matches("[A-Z]{2}")) return saved;
        String locale = Locale.getDefault().getCountry();
        return locale != null && locale.matches("(?i)[a-z]{2}")
                ? locale.toUpperCase(Locale.ROOT) : "US";
    }

    static void invalidateCache(Context c, String packageName) {
        if (c == null) return;
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor edit = p.edit();
        if (packageName == null || packageName.isEmpty()) {
            for (String key : p.getAll().keySet()) {
                if (key.startsWith(CACHE_PREFIX) || key.startsWith(TIME_PREFIX)) edit.remove(key);
            }
        } else {
            edit.remove(CACHE_PREFIX + packageName).remove(TIME_PREFIX + packageName);
        }
        edit.apply();
    }

    static List<Suggestions.Item> fetchForPackage(Context context, String packageName, int limit) {
        if (context == null || packageName == null || packageName.isEmpty() || limit <= 0) {
            return new ArrayList<>();
        }
        Context c = context.getApplicationContext();
        if (StreamingPlatformRegistry.isYouTube(packageName, "")) {
            return cachedOrFetch(c, packageName, () -> fetchYouTube(c, packageName), limit);
        }
        if (StreamingPlatformRegistry.isKinopoisk(packageName, "")) {
            return cachedOrFetch(c, packageName, () -> fetchKinopoisk(c, packageName), limit);
        }
        return new ArrayList<>();
    }

    private interface Loader {
        List<Suggestions.Item> load() throws Exception;
    }

    private static List<Suggestions.Item> cachedOrFetch(
            Context c, String pkg, Loader loader, int limit) {
        SharedPreferences p = prefs(c);
        String cacheKey = CACHE_PREFIX + pkg;
        String timeKey = TIME_PREFIX + pkg;
        List<Suggestions.Item> cached = decode(p.getString(cacheKey, ""), pkg);
        long age = System.currentTimeMillis() - p.getLong(timeKey, 0L);
        if (cached != null && !cached.isEmpty() && age >= 0 && age < CACHE_TTL_MS) {
            return trim(cached, limit);
        }
        try {
            List<Suggestions.Item> fresh = loader.load();
            if (fresh != null && !fresh.isEmpty()) {
                p.edit().putString(cacheKey, encode(fresh)).putLong(timeKey, System.currentTimeMillis()).apply();
                return trim(fresh, limit);
            }
        } catch (Exception e) {
            Log.w(TAG, "Catalogue refresh failed for " + pkg + "; retaining cached items", e);
        }
        return cached == null ? new ArrayList<>() : trim(cached, limit);
    }

    private static List<Suggestions.Item> fetchYouTube(Context c, String pkg) throws Exception {
        String key = prefs(c).getString(KEY_YOUTUBE, "").trim();
        if (key.isEmpty()) return new ArrayList<>();
        String region = region(c);
        String url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails"
                + "&chart=mostPopular&regionCode=" + Uri.encode(region)
                + "&maxResults=" + MAX_ITEMS + "&key=" + Uri.encode(key);
        JSONObject root = get(url, null);
        JSONArray rows = root.optJSONArray("items");
        List<Suggestions.Item> out = new ArrayList<>();
        for (int i = 0; rows != null && i < rows.length() && out.size() < MAX_ITEMS; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("id", "");
            JSONObject snippet = row.optJSONObject("snippet");
            if (id.isEmpty() || snippet == null) continue;
            JSONObject thumbs = snippet.optJSONObject("thumbnails");
            String art = thumbnail(thumbs);
            if (art.isEmpty()) continue;
            Suggestions.Item item = new Suggestions.Item();
            item.id = id.hashCode();
            item.type = "video";
            item.pkg = pkg;
            item.key = pkg + "|youtube|" + id;
            item.title = snippet.optString("title", "").trim();
            item.overview = snippet.optString("channelTitle", "");
            item.backdrop = art;
            item.link = "https://www.youtube.com/watch?v=" + Uri.encode(id);
            item.year = "";
            item.genres = "";
            item.progress = -1f;
            item.published = 0L;
            if (!item.title.isEmpty()) out.add(item);
        }
        return out;
    }

    private static String thumbnail(JSONObject thumbs) {
        if (thumbs == null) return "";
        String[] order = {"maxres", "standard", "high", "medium", "default"};
        for (String size : order) {
            JSONObject image = thumbs.optJSONObject(size);
            if (image != null) {
                String url = image.optString("url", "");
                if (url.startsWith("https://")) return url;
            }
        }
        return "";
    }

    private static List<Suggestions.Item> fetchKinopoisk(Context c, String pkg) throws Exception {
        String token = prefs(c).getString(KEY_KINOPOISK, "").trim();
        if (token.isEmpty()) return new ArrayList<>();
        String url = "https://api.kinopoisk.dev/v1.4/movie?limit=" + MAX_ITEMS
                + "&sortField=votes.imdb&sortType=-1&notNullFields=poster.url";
        JSONObject root = get(url, token);
        JSONArray rows = root.optJSONArray("docs");
        List<Suggestions.Item> out = new ArrayList<>();
        for (int i = 0; rows != null && i < rows.length() && out.size() < MAX_ITEMS; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String title = row.optString("name", "").trim();
            if (title.isEmpty()) title = row.optString("alternativeName", "").trim();
            JSONObject poster = row.optJSONObject("poster");
            JSONObject backdrop = row.optJSONObject("backdrop");
            String art = backdrop == null ? "" : backdrop.optString("url", "");
            if (art.isEmpty() && poster != null) art = poster.optString("url", "");
            if (title.isEmpty() || !art.startsWith("https://")) continue;
            Suggestions.Item item = new Suggestions.Item();
            item.id = row.optInt("id", title.hashCode());
            item.type = row.optString("type", "movie");
            item.pkg = pkg;
            item.key = pkg + "|kinopoisk|" + item.id;
            item.title = title;
            item.overview = row.optString("description", "");
            item.backdrop = art;
            item.link = ""; // No stable, documented Android TV item deep-link is exposed by Kinopoisk.
            item.year = row.optString("year", "");
            item.genres = "";
            JSONObject rating = row.optJSONObject("rating");
            item.rating = rating == null ? 0d : rating.optDouble("imdb", 0d);
            item.progress = -1f;
            item.published = 0L;
            out.add(item);
        }
        return out;
    }

    private static JSONObject get(String address, String kinopoiskToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Dura-CinemaOS/1.0 (Android TV)");
        if (kinopoiskToken != null && !kinopoiskToken.isEmpty()) {
            connection.setRequestProperty("X-API-KEY", kinopoiskToken);
        }
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = read(stream);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Catalogue API HTTP " + code + ": " + body.substring(0, Math.min(220, body.length())));
            }
            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String encode(List<Suggestions.Item> items) {
        JSONArray array = new JSONArray();
        try {
            for (Suggestions.Item item : items) {
                JSONObject row = item.toJson();
                row.put("pkg", item.pkg).put("key", item.key);
                array.put(row);
            }
        } catch (Exception ignored) { }
        return array.toString();
    }

    private static List<Suggestions.Item> decode(String raw, String pkg) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONArray array = new JSONArray(raw);
            List<Suggestions.Item> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                Suggestions.Item item = Suggestions.Item.fromJson(array.getJSONObject(i));
                item.pkg = pkg;
                item.key = array.getJSONObject(i).optString("key", pkg + "|" + item.id);
                out.add(item);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<Suggestions.Item> trim(List<Suggestions.Item> items, int limit) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(items.subList(0, Math.min(limit, items.size())));
    }
}
