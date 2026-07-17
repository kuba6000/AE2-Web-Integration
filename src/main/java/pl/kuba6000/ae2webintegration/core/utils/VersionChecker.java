package pl.kuba6000.ae2webintegration.core.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.CoreEngine;

public class VersionChecker {

    // example version: 0.0.9-alpha-forge-1.12.2
    private static String VERSION_IDENTIFIER = "";

    private static final String versionCheckURL = "https://api.github.com/repos/kuba6000/AE2-Web-Integration/tags";
    private static String latestTag = null;

    private static long lastChecked = 0L;

    /**
     * Sets the version identifier used to filter GitHub tags for update checks.
     * Must be called from the interface layer before any version check runs.
     * Example: "-forge-1.7.10", "-forge-1.12.2", "-neoforge-1.21.1".
     */
    public static void setVersionIdentifier(String identifier) {
        VERSION_IDENTIFIER = identifier;
    }

    /**
     * Extracts the version identifier suffix from a version string when
     * VERSION_IDENTIFIER has not been explicitly set via setVersionIdentifier().
     * Matches patterns like -forge-1.7.10 or -neoforge-1.21.1 within the version string.
     */
    private static String extractVersionIdentifier(String version) {
        Matcher matcher = Pattern.compile("-(?:forge|neoforge)-\\d+\\.\\d+\\.\\d+").matcher(version);
        return matcher.find() ? matcher.group() : "";
    }

    private static void updateLatestVersion(String currentVersion) {
        if (currentVersion == null || currentVersion.isEmpty()) return;

        // Fallback: extract VERSION_IDENTIFIER from currentVersion if not explicitly set
        if (VERSION_IDENTIFIER == null || VERSION_IDENTIFIER.isEmpty()) {
            VERSION_IDENTIFIER = extractVersionIdentifier(currentVersion);
            if (VERSION_IDENTIFIER == null || VERSION_IDENTIFIER.isEmpty()) {
                return; // Cannot determine version identifier for this version
            }
        }

        if (lastChecked != 0L) {
            long elapsed = System.currentTimeMillis() - lastChecked;
            if (latestTag == null) {
                if (elapsed < 5 * 60 * 1000) // 5 minutes
                    return;
            } else if (!currentVersion.equals(latestTag)) {
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
                    latestTag = currentVersion;
                }
            }

        } catch (Exception ignored) {

        }
    }

    public static boolean isOutdated() {
        String currentVersion = CoreEngine.getModVersion();
        if (currentVersion == null || currentVersion.isEmpty()) return false;
        updateLatestVersion(currentVersion);
        if (latestTag == null) return false;
        return !latestTag.equals(currentVersion);
    }

    public static String getLatestTag() {
        return latestTag;
    }

}
