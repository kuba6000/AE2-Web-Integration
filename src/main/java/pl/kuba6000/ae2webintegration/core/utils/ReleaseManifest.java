package pl.kuba6000.ae2webintegration.core.utils;

import java.math.BigInteger;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The published recommendations for one Minecraft version. */
public final class ReleaseManifest {

    public enum Channel {
        STABLE,
        PRERELEASE
    }

    public static final class Release {

        public final @NotNull String version;
        public final @NotNull Channel channel;
        /** Seconds since the Unix epoch. */
        public final long timestamp;
        public final @NotNull String tag;
        public final @NotNull String releaseUrl;
        public final @NotNull String downloadUrl;
        private final Version comparable;

        private Release(JsonObject json, Channel channel) {
            version = string(json, "newest");
            comparable = Version.parse(version);
            if (comparable == null || (channel == Channel.STABLE && comparable.suffix != null)) {
                throw new IllegalArgumentException("Invalid release version");
            }
            this.channel = channel;
            String seconds = json.get("timestamp")
                .toString();
            if (!seconds.matches("[0-9]+")) throw new IllegalArgumentException("Expected Unix seconds");
            timestamp = Long.parseLong(seconds);
            tag = string(json, "github_release_tag");
            releaseUrl = url(json, "github_release_url");
            downloadUrl = url(json, "github_release_download_url");
        }
    }

    private final @NotNull String minecraftVersion;
    private final @Nullable Release stable;
    private final @Nullable Release prerelease;

    private ReleaseManifest(@NotNull String minecraftVersion, @Nullable Release stable, @Nullable Release prerelease) {
        this.minecraftVersion = minecraftVersion;
        this.stable = stable;
        this.prerelease = prerelease;
    }

    public static @NotNull ReleaseManifest parse(@NotNull String json, @NotNull String minecraftVersion) {
        JsonObject root = new JsonParser().parse(json)
            .getAsJsonObject();
        if (!minecraftVersion.equals(string(root, "version"))) {
            throw new IllegalArgumentException("Release feed targets another Minecraft version");
        }
        JsonObject releases = root.getAsJsonObject("releases");
        return new ReleaseManifest(
            minecraftVersion,
            readRelease(releases, "stable", Channel.STABLE),
            readRelease(releases, "prerelease", Channel.PRERELEASE));
    }

    public @Nullable Release findUpdate(@NotNull String installedVersion, @NotNull String versionIdentifier) {
        if (!versionIdentifier.endsWith("-" + minecraftVersion)) return null;
        String loader = versionIdentifier.substring(0, versionIdentifier.length() - minecraftVersion.length());
        Matcher platform = Pattern
            .compile(Pattern.quote(loader) + "(pre-)?" + Pattern.quote(minecraftVersion) + "(?=$|[-+])(.*)")
            .matcher(installedVersion);
        boolean platformFound = platform.find();
        if (!platformFound && Pattern.compile("-(?:neo)?forge-")
            .matcher(installedVersion)
            .find()) return null;
        Version installed = Version
            .parse(platformFound ? installedVersion.substring(0, platform.start()) : installedVersion);
        if (installed == null) return null;
        String tail = platformFound ? platform.group(2) : "";
        boolean isPre = installed.suffix != null || (platformFound && platform.group(1) != null)
            || tail.equals("-pre")
            || tail.startsWith("-pre-")
            || tail.startsWith("-pre+");
        boolean development = !(tail.isEmpty() || tail.equals("-pre")) || installedVersion.contains("+");
        Release selected = isUpgrade(stable, installed, installedVersion, isPre, development) ? stable : null;
        if (isPre && isUpgrade(prerelease, installed, installedVersion, true, development)) {
            if (selected == null || prerelease.comparable.compareBase(selected.comparable) > 0) selected = prerelease;
        }
        return selected;
    }

    private static boolean isUpgrade(@Nullable Release release, Version installed, String installedTag, boolean isPre,
        boolean development) {
        if (release == null || release.tag.equals(installedTag)) return false;
        int base = release.comparable.compareBase(installed);
        if (base != 0) return base > 0;
        if (!isPre || development) return false;
        if (release.channel == Channel.STABLE) return true;
        // Old descriptive/unnumbered preview tags carry no reliable within-release ordering.
        return release.comparable.comparePrerelease(installed) > 0;
    }

    private static @Nullable Release readRelease(JsonObject releases, String name, Channel channel) {
        JsonElement value = releases.get(name);
        if (value == null) throw new IllegalArgumentException("Missing release channel: " + name);
        return value.isJsonNull() ? null : new Release(value.getAsJsonObject(), channel);
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive()
                .isString()
            || value.getAsString()
                .isEmpty())
            throw new IllegalArgumentException("Missing string: " + name);
        return value.getAsString();
    }

    private static String url(JsonObject json, String name) {
        String value = string(json, name);
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Expected a GitHub HTTPS URL: " + name);
        }
        return value;
    }

    private static final class Version {

        private static final Pattern FORMAT = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
        private final BigInteger[] numbers;
        private final @Nullable String suffix;

        private Version(Matcher matcher) {
            numbers = new BigInteger[] { new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)) };
            suffix = matcher.group(4);
        }

        private static @Nullable Version parse(String text) {
            Matcher matcher = FORMAT.matcher(text);
            if (!matcher.matches()) return null;
            String suffix = matcher.group(4);
            if (suffix != null) {
                for (String component : suffix.split("\\.", -1)) {
                    if (component.isEmpty() || component.matches("0[0-9]+")) return null;
                }
            }
            return new Version(matcher);
        }

        private int compareBase(Version other) {
            for (int i = 0; i < numbers.length; i++) {
                int comparison = numbers[i].compareTo(other.numbers[i]);
                if (comparison != 0) return comparison;
            }
            return 0;
        }

        private int comparePrerelease(Version other) {
            if (suffix == null || other.suffix == null
                || !suffix.matches("[a-zA-Z][a-zA-Z0-9-]*\\.[0-9]+(?:\\.[0-9]+)*")
                || !other.suffix.matches("[a-zA-Z][a-zA-Z0-9-]*\\.[0-9]+(?:\\.[0-9]+)*")) return 0;
            String[] left = suffix.split("\\.");
            String[] right = other.suffix.split("\\.");
            int label = left[0].compareTo(right[0]);
            if (label != 0) return label;
            for (int i = 1; i < Math.min(left.length, right.length); i++) {
                int comparison = new BigInteger(left[i]).compareTo(new BigInteger(right[i]));
                if (comparison != 0) return comparison;
            }
            return Integer.compare(left.length, right.length);
        }
    }
}
