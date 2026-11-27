package com.techgenius.tvoslauncher;

import java.util.Locale;

/**
 * Single source of truth for apps allowed to publish media content into Dura-CinemaOS Top Shelf.
 * This is intentionally an allow-list: a generic app, browser or utility must never become a
 * media shelf merely because its name happens to contain a service substring.
 *
 * The registry only grants eligibility. Actual titles, artwork and playback state must still come
 * from the app's Android TV Preview Programs or from a media session the app publishes.
 */
final class StreamingPlatformRegistry {
    private StreamingPlatformRegistry() { }

    static boolean isSupported(String packageName, String appLabel) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.toLowerCase(Locale.ROOT);
        String label = appLabel == null ? "" : appLabel.toLowerCase(Locale.ROOT).trim();

        if (pkg.contains("browser") || label.contains("browser")) return false;

        return isYouTube(pkg, label)
                || pkg.contains("kinopoisk") || label.contains("кинопоиск")
                || pkg.startsWith("ru.ivi") || label.equals("ivi") || label.startsWith("ivi ")
                || pkg.contains("okko") || label.contains("okko")
                || pkg.contains("zona") || label.contains("zona")
                || pkg.contains("rutube") || label.contains("rutube")
                || pkg.contains("netflix") || label.contains("netflix")
                || pkg.contains("amazonvideo") || pkg.contains("primevideo") || label.contains("prime video")
                || pkg.contains("disney") || label.contains("disney+")
                || pkg.contains("appletv") || label.equals("apple tv")
                || pkg.contains("hbomax") || label.equals("max") || label.startsWith("max ")
                || pkg.contains("hulu") || label.equals("hulu")
                || pkg.contains("paramount") || label.contains("paramount")
                || pkg.contains("peacock") || label.contains("peacock")
                || pkg.contains("crunchyroll") || label.contains("crunchyroll")
                || pkg.contains("hotstar") || label.contains("hotstar")
                || pkg.contains("zee5") || label.contains("zee5")
                || pkg.contains("sonyliv") || label.contains("sonyliv")
                || isRussianStreamingService(pkg, label);
    }

    static boolean isRussianStreamingService(String packageName, String appLabel) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        String label = appLabel == null ? "" : appLabel.toLowerCase(Locale.ROOT).trim();
        return pkg.contains("okko") || pkg.startsWith("ru.more.play") || label.contains("okko")
                || pkg.contains("wink") || pkg.equals("ru.rt.video.app.tv") || label.equals("wink")
                || pkg.startsWith("ru.ivi.") || pkg.equals("ru.ivi.client") || label.equals("ivi") || label.contains("иви")
                || pkg.contains("start") || label.equals("start") || label.equals("старт")
                || pkg.contains("premier") || label.contains("premier") || label.contains("премьер");
    }

    static boolean isYouTube(String packageName, String appLabel) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        String label = appLabel == null ? "" : appLabel.toLowerCase(Locale.ROOT);
        return pkg.contains("youtube") || label.contains("youtube");
    }

    static boolean isKinopoisk(String packageName, String appLabel) {
        String pkg = packageName == null ? "" : packageName.toLowerCase(Locale.ROOT);
        String label = appLabel == null ? "" : appLabel.toLowerCase(Locale.ROOT);
        return pkg.contains("kinopoisk") || label.contains("кинопоиск");
    }
}
