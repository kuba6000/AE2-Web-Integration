package com.kuba6000.ae2webintegration.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.kuba6000.ae2webintegration.Tags;

public class VersionChecker {

    private static final String versionCheckURL = "https://api.github.com/repos/kuba6000/AE2-Web-Integration/releases/latest";
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
                    latestTag = element.getAsJsonObject()
                        .get("tag_name")
                        .getAsString();
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
