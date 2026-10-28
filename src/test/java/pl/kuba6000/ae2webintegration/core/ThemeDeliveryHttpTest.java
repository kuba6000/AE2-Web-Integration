package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;

/** Tests installed ZIP packages through configuration, server lifecycle and authenticated HTTP. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class ThemeDeliveryHttpTest {

    @TempDir
    Path directory;
    private ConfigTestFixture config;
    private IServerPlatform previousPlatform;
    private int port;

    @BeforeEach
    void setUp() {
        CoreEngine.onServerStopped();
        previousPlatform = AE2Controller.serverPlatform;
        config = new ConfigTestFixture(directory.toFile());
        config.write("general.allow_no_password_on_localhost", true);
        config.write("general.check_for_updates", false);
        Config.reload();
        AE2Controller.serverPlatform = new IServerPlatform() {

            @Override
            public java.nio.file.Path getModFile() {
                return null;
            }

            @Override
            public boolean isSingleplayer() {
                return false;
            }

            public @NotNull String getModVersion() {
                return "1.2.0";
            }

            public UUID getOnlinePlayerUUID(String username) {
                return null;
            }

            public File getConfigDirectory() {
                return directory.toFile();
            }

            public ILegacyConfigProvider getLegacyConfig() {
                return null;
            }

            public File getWorldDirectory() {
                return directory.resolve("world")
                    .toFile();
            }

            public @NotNull String getMinecraftVersion() {
                return "1.7.10";
            }

            public @NotNull String getLoader() {
                return "forge";
            }

            public @NotNull String getIconPackCompatibilityVersion() {
                return "1";
            }
        };
        AE2Controller.AE2Interface = TestGridFixtures.ae();
    }

    @AfterEach
    void tearDown() {
        CoreEngine.onServerStopped();
        AE2Controller.serverPlatform = previousPlatform;
        config.close();
    }

    @Test
    void installedThemeCanDeclareResourceTrackingView() throws Exception {
        install("tracking.zip", manifest("tracking").replace("\"items\"", "\"tracking\""), Collections.emptyMap());
        port = HttpServerTestFixture.startWorld(config);
        JsonObject theme = catalog().getAsJsonArray("themes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "tracking",
            theme.getAsJsonArray("views")
                .get(1)
                .getAsString());
    }

    @Test
    void installedZipExposesManifestDefaultAndAuthenticatedAssetsWithoutExtraction() throws Exception {
        Path archive = install(
            "list.zip",
            manifest("list"),
            Collections.singletonMap("data.txt", "example".getBytes(StandardCharsets.UTF_8)));
        config.set("general.default_theme", "server:list");
        port = HttpServerTestFixture.startWorld(config);
        JsonObject catalog = catalog();
        assertEquals(
            "server:list",
            catalog.get("defaultTheme")
                .getAsString());
        assertEquals(
            1,
            catalog.getAsJsonArray("themes")
                .size());
        JsonObject theme = catalog.getAsJsonArray("themes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "list",
            theme.get("id")
                .getAsString());
        assertEquals(
            "1.2.x",
            theme.get("supportedModVersion")
                .getAsString());
        HttpURLConnection script = connection("/themes/list/theme.js");
        assertEquals(200, script.getResponseCode());
        assertEquals("text/javascript; charset=UTF-8", script.getHeaderField("Content-Type"));
        assertEquals("nosniff", script.getHeaderField("X-Content-Type-Options"));
        try (InputStream input = script.getInputStream()) {
            assertEquals("export default {};", IOUtils.toString(input, StandardCharsets.UTF_8));
        }
        HttpURLConnection head = connection("/themes/list/style.css");
        head.setRequestMethod("HEAD");
        assertEquals(200, head.getResponseCode());
        assertEquals("text/css; charset=UTF-8", head.getHeaderField("Content-Type"));
        head.disconnect();
        assertEquals(404, connection("/themes/list/nope.js").getResponseCode());
        HttpURLConnection dataFile = connection("/themes/list/data.txt");
        assertEquals(200, dataFile.getResponseCode());
        assertEquals("application/octet-stream", dataFile.getHeaderField("Content-Type"));
        assertEquals("nosniff", dataFile.getHeaderField("X-Content-Type-Options"));
        dataFile.disconnect();
        assertEquals(404, connection("/themes/list/%2e%2e/config.toml").getResponseCode());
        config.set("general.allow_no_password_on_localhost", false);
        assertEquals(401, connection("/api/themes").getResponseCode());
        assertEquals(401, connection("/themes/list/theme.js").getResponseCode());
        CoreEngine.onServerStopped();
        Files.delete(archive); // Windows cannot remove a ZIP while its handle remains open.
        assertFalse(
            Files.exists(
                archive.getParent()
                    .resolve("theme.js")));
    }

    private Path install(String filename, String manifest, Map<String, byte[]> extra) throws Exception {
        Path folder = directory.resolve("ae2webintegration/themes");
        Files.createDirectories(folder);
        Path file = folder.resolve(filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "theme.json", manifest.getBytes(StandardCharsets.UTF_8));
            entry(zip, "theme.js", "export default {};".getBytes(StandardCharsets.UTF_8));
            entry(zip, "style.css", "body {}".getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> asset : extra.entrySet()) entry(zip, asset.getKey(), asset.getValue());
        }
        return file;
    }

    @Test
    void malformedAndUnsafePackagesAreExcludedWithoutBreakingCatalogOrRestart() throws Exception {
        install("malformed.zip", "{", Collections.emptyMap());
        install("traversal.zip", manifest("traversal"), Collections.singletonMap("../escape.js", new byte[] { 1 }));
        install("loose-json.zip", manifest("loose").replace("\"id\"", "id"), Collections.emptyMap());
        install("valid.zip", manifest("valid"), Collections.emptyMap());
        config.set("general.default_theme", "server:traversal");
        port = HttpServerTestFixture.startWorld(config);
        JsonObject data = catalog();
        assertEquals(
            "builtin:default",
            data.get("defaultTheme")
                .getAsString());
        assertEquals(
            1,
            data.getAsJsonArray("themes")
                .size());
        assertEquals(
            "valid",
            data.getAsJsonArray("themes")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString());
        assertEquals(404, connection("/themes/traversal/theme.js").getResponseCode());
        CoreEngine.onServerStopped();
        install("duplicate.zip", manifest("valid"), Collections.emptyMap());
        CoreEngine.onServerStarted();
        assertEquals(
            0,
            catalog().getAsJsonArray("themes")
                .size());
        assertEquals(404, connection("/themes/valid/theme.js").getResponseCode());
    }

    @Test
    void packageLimitsAndManifestReferencesAreValidatedBeforePublication() throws Exception {
        install(
            "oversized.zip",
            manifest("oversized"),
            Collections.singletonMap("large.bin", new byte[16 * 1024 * 1024 + 1]));
        install(
            "large-manifest.zip",
            manifest("large-manifest") + String.join("", Collections.nCopies(65536, " ")),
            Collections.emptyMap());
        Map<String, byte[]> excessiveEntries = new LinkedHashMap<>();
        for (int i = 0; i < 510; i++) excessiveEntries.put("asset-" + i + ".js", new byte[0]);
        install("entries.zip", manifest("entries"), excessiveEntries);
        install(
            "missing-entry.zip",
            manifest("missing-entry").replace("theme.js", "missing.js"),
            Collections.emptyMap());
        install(
            "unknown-view.zip",
            manifest("unknown-view").replace("\"home\"", "\"unknown\""),
            Collections.emptyMap());
        install(
            "duplicate-view.zip",
            manifest("duplicate-view").replace("\"items\"", "\"home\""),
            Collections.emptyMap());
        install(
            "directory-data.zip",
            manifest("directory-data"),
            Collections.singletonMap("assets/", new byte[] { 1 }));
        port = HttpServerTestFixture.startWorld(config);
        assertEquals(
            0,
            catalog().getAsJsonArray("themes")
                .size());
        CoreEngine.onServerStopped();
        try (Stream<Path> files = Files.list(directory.resolve("ae2webintegration/themes"))) {
            for (Path file : (Iterable<Path>) files::iterator) Files.delete(file);
        }
        install("good.zip", manifest("good"), Collections.emptyMap());
        CoreEngine.onServerStarted();
        assertEquals(
            1,
            catalog().getAsJsonArray("themes")
                .size());
        HttpURLConnection post = connection("/themes/good/theme.js");
        post.setRequestMethod("POST");
        assertEquals(405, post.getResponseCode());
        assertEquals("GET, HEAD", post.getHeaderField("Allow"));
        post.disconnect();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String manifest(String id) {
        return "{\"id\":\"" + id
            + "\",\"name\":\"List\",\"version\":\"1.0.0\","
            + "\"supportedModVersion\":\"1.2.x\",\"views\":[\"home\",\"items\"],"
            + "\"entry\":\"theme.js\",\"styles\":[\"style.css\"]}";
    }

    private JsonObject catalog() throws Exception {
        HttpURLConnection connection = connection("/api/themes");
        assertEquals(200, connection.getResponseCode());
        try (InputStream input = connection.getInputStream()) {
            return new Gson().fromJson(IOUtils.toString(input, StandardCharsets.UTF_8), JsonObject.class)
                .getAsJsonObject("data");
        }
    }

    private HttpURLConnection connection(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        return connection;
    }
}
