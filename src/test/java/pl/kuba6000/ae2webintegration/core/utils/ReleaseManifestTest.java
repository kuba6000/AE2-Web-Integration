package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class ReleaseManifestTest {

    @Test
    void handlesAllAdaptersOverridesAndDevelopmentBuildsConservatively() {
        for (String target : new String[] { "1.7.10", "1.12.2", "1.20.1", "1.21.1" }) {
            String loader = target.equals("1.21.1") ? "neoforge" : "forge";
            String identifier = "-" + loader + "-" + target;
            ReleaseManifest manifest = ReleaseManifest.parse(
                feed("1.1.0", "1.2.0").replace("1.7.10", target)
                    .replace("forge", loader),
                target);
            assertEquals("1.1.0", requireUpdate(manifest, "1.0.9" + identifier, loader).version);
            assertEquals("1.1.0", requireUpdate(manifest, "1.0.9", loader).version);
            assertEquals("1.2.0", requireUpdate(manifest, "0.2.1-alpha" + identifier + "-pre", loader).version);
            assertEquals("1.1.0", requireUpdate(manifest, "1.0.9" + identifier + "-19-gabcdef.dirty", loader).version);
            assertNull(manifest.findUpdate("1.1.0" + identifier + "-19-gabcdef.dirty", loader));
            assertNull(manifest.findUpdate("NO-GIT-TAG-SET", loader));
        }
        ReleaseManifest manifest = ReleaseManifest.parse(feed("1.1.0", "1.1.0"), "1.7.10");
        assertNull(manifest.findUpdate("1.1.0-forge-pre-1.7.10-1-7-10+28363aee73-dirty", "forge"));
        assertNull(manifest.findUpdate("0.0.1-neoforge-1.21.1", "forge"));
    }

    @Test
    void rejectsInvalidMetadataAndSupportsEmptyChannels() {
        assertNull(
            ReleaseManifest.parse(feed(null, null), "1.7.10")
                .findUpdate("1.0.0", "forge"));
        for (String invalid : new String[] { feed("1.1.0", null).replace("1786902605", "\"1786902605\""),
            feed("1.1.0", null).replace("1786902605", "1786902605.5"),
            feed("1.1.0", null).replace("https://github.com", "javascript:evil"),
            feed("1.1.0", null).replace("\"version\":\"1.7.10\"", "\"version\":\"1.12.2\""), feed("garbage", null),
            feed("1.1.0-alpha.1", null), feed("1.1.0", "1.2.0-beta..2"), feed("1.1.0", "1.2.0-beta.1+build..1") })
            assertThrows(RuntimeException.class, () -> ReleaseManifest.parse(invalid, "1.7.10"));
    }

    @Test
    void treatsBuildMetadataAsUnorderedAndChecksWholePlatformTarget() {
        ReleaseManifest manifest = ReleaseManifest.parse(feed("1.2.0+build.1", "1.3.0-beta.2+build.3"), "1.7.10");
        assertEquals("1.2.0+build.1", requireUpdate(manifest, "1.1.0+build.9", "forge").version);
        assertNull(manifest.findUpdate("1.2.0+build.9", "forge"));
        assertNull(manifest.findUpdate("1.0.0-forge-1.7.100", "forge"));
        assertEquals(
            "1.3.0-beta.2+build.3",
            requireUpdate(manifest, "1.2.0-forge-1.7.10-pre-19-gabcdef", "forge").version);
    }

    @Test
    void prereleasesFollowBothChannelsWithoutDowngradesOrInventedOrdering() {
        String loader = "forge";
        ReleaseManifest manifest = ReleaseManifest.parse(feed("1.1.0", "1.2.0-beta.2"), "1.7.10");
        assertEquals("1.2.0-beta.2", requireUpdate(manifest, "1.1.0-forge-pre-1.7.10", loader).version);
        assertEquals("1.2.0-beta.2", requireUpdate(manifest, "1.2.0-beta.1-forge-1.7.10", loader).version);
        assertNull(manifest.findUpdate("1.2.0-beta.10-forge-1.7.10", loader));
        manifest = ReleaseManifest.parse(feed("1.1.0", "1.1.0"), "1.7.10");
        assertEquals(ReleaseManifest.Channel.STABLE, requireUpdate(manifest, "1.1.0-forge-pre-1.7.10", loader).channel);
        assertNull(manifest.findUpdate("1.2.0-forge-pre-1.7.10", loader));
        manifest = ReleaseManifest.parse(feed("1.0.2", "1.1.0"), "1.7.10");
        assertNull(manifest.findUpdate("1.1.0-other-feature-forge-pre-1.7.10", loader));
        assertEquals(
            "1.1.0",
            requireUpdate(manifest, "1.0.3-GTNH-Native-Fluids-Support-forge-pre-1.7.10", loader).version);
    }

    @Test
    void stableInstallOnlySelectsNewerStableAndPreservesMetadata() {
        ReleaseManifest manifest = ReleaseManifest.parse(feed("1.0.2", "1.1.0"), "1.7.10");
        ReleaseManifest.Release update = manifest.findUpdate("1.0.1-forge-1.7.10", "forge");
        assertNotNull(update);
        assertEquals("1.0.2", update.version);
        assertEquals(ReleaseManifest.Channel.STABLE, update.channel);
        assertEquals(1786902605L, update.timestamp);
        assertTrue(update.releaseUrl.endsWith("/1.0.2-forge-1.7.10"));
        assertTrue(update.downloadUrl.endsWith("/mod.jar"));
        assertNull(manifest.findUpdate("1.0.2-forge-1.7.10", "forge"));
        assertNull(manifest.findUpdate("1.0.3-forge-1.7.10", "forge"));
    }

    private static @NotNull ReleaseManifest.Release requireUpdate(@NotNull ReleaseManifest manifest,
        @NotNull String currentVersion, @NotNull String loader) {
        ReleaseManifest.Release update = manifest.findUpdate(currentVersion, loader);
        assertNotNull(update, "Expected an update for " + currentVersion);
        return update;
    }

    static String feed(String stable, String pre) {
        return "{\"version\":\"1.7.10\",\"releases\":{\"stable\":" + release(stable, false)
            + ",\"prerelease\":"
            + release(pre, true)
            + "}}";
    }

    static String release(String version, boolean pre) {
        if (version == null) return "null";
        String tag = version + (pre ? "-forge-pre-1.7.10" : "-forge-1.7.10");
        return "{\"newest\":\"" + version
            + "\",\"timestamp\":1786902605,\"github_release_tag\":\""
            + tag
            + "\",\"github_release_url\":\"https://github.com/kuba6000/AE2-Web-Integration/releases/tag/"
            + tag
            + "\",\"github_release_download_url\":\"https://github.com/kuba6000/AE2-Web-Integration/releases/download/"
            + tag
            + "/mod.jar\"}";
    }
}
