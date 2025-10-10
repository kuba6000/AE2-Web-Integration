package pl.kuba6000.ae2webintegration.core.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.Tags;

public class VersionChecker {

    // example version: 0.0.9-alpha-forge-1.12.2
    private static final String VERSION_IDENTIFIER = "-neoforge-1.21.1";

    private static final String versionCheckURL = "https://api.github.com/repos/kuba6000/AE2-Web-Integration/tags";
    private static String latestTag = null;

    private static long lastChecked = 0L;

    private static void updateLatestVersion() {
        if (lastChecked != 0L) {
            if (!Tags.VERSION.equals(latestTag)) return;
            long elapsed = System.currentTimeMillis() - lastChecked;
            if (latestTag == null) {
                if (elapsed < 5 * 60 * 1000) // 5 minutes
                    return;
            } else if (elapsed < 5 * 60 * 60 * 1000) { // 5 hours
                return;
            }
        }
        lastChecked = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(versionCheckURL).openConnection();
            if (conn.getResponseCode() == 200) {
                try (BufferedReader buf = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    JsonElement element = new JsonParser().parse(buf);
                    // this should be sorted right?
                    for (JsonElement tag : element.getAsJsonArray()) {
                        String name = tag.getAsJsonObject()
                            .get("name")
                            .getAsString();
                        if (name.contains(VERSION_IDENTIFIER)) {
                            latestTag = name;
                            return;
                        }
                    }
                    // not found???
                    latestTag = Tags.VERSION;
                }
            }

        } catch (Exception ignored) {

        }
    }

    public static boolean isOutdated() {
        updateLatestVersion();
        if (latestTag == null) return false;
        return !latestTag.equals(Tags.VERSION);
    }

    public static String getLatestTag() {
        return latestTag;
    }

}
