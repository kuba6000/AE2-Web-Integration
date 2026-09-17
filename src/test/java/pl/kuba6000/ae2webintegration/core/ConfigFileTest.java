package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import pl.kuba6000.ae2webintegration.core.config.Config;

class ConfigFileTest {

    @TempDir
    Path root;

    @Test
    void firstStartCreatesReadableConfigAndRetainsPasswordAcrossRestarts() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        assertTrue(Files.isRegularFile(file));
        String password = Config.AE_PASSWORD();
        assertFalse(password.isEmpty());
        String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        CommentedConfig document = new TomlParser().parse(contents);
        assertNotNull(document.getComment("general.port"));
        assertNotNull(document.getComment("discord.webhook"));
        assertNotNull(document.getComment("tracking.track_machine_crafting"));
        assertTrue(document.contains("general.password"));

        Config.init(root.toFile());
        assertEquals(password, Config.AE_PASSWORD());
    }

    @Test
    void migrationPreservesSettingsAndExistingTomlTakesPrecedence() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("port", 25432);
        legacy.put("password", "existing-admin-password");
        legacy.put("allow_no_password_on_localhost", false);
        legacy.put("trusted_proxies", "192.0.2.10");
        legacy.put("public_mode", false);
        legacy.put("max_requests_before_logged_in_per_minute", 37);
        legacy.put("check_for_updates", false);
        legacy.put("discord_webhook", "https://example.invalid/webhook");
        legacy.put("discord_role_id", "123456789");
        legacy.put("discord_minimum_crafting_duration_seconds", 64);
        legacy.put("discord_minimum_crafting_amount", 128);
        legacy.put("track_machine_crafting", true);

        Config.init(root.toFile(), () -> legacy);
        Config.init(root.toFile(), () -> { throw new AssertionError("Existing TOML must bypass migration"); });

        assertEquals(25432, Config.AE_PORT());
        assertEquals("existing-admin-password", Config.AE_PASSWORD());
        assertFalse(Config.ALLOW_NO_PASSWORD_ON_LOCALHOST());
        assertEquals("192.0.2.10", Config.TRUSTED_PROXIES());
        assertFalse(Config.AE_PUBLIC_MODE());
        assertEquals(37, Config.AE_MAX_REQUESTS_BEFORE_LOGGED_IN_PER_MINUTE());
        assertFalse(Config.CHECK_FOR_UPDATES());
        assertEquals("https://example.invalid/webhook", Config.DISCORD_WEBHOOK());
        assertEquals("123456789", Config.DISCORD_ROLE_ID());
        assertEquals(64, Config.DISCORD_MINIMUM_CRAFTING_DURATION_SECONDS());
        assertEquals(128, Config.DISCORD_MINIMUM_CRAFTING_AMOUNT());
        assertTrue(Config.TRACKING_TRACK_MACHINE_CRAFTING());
    }

    @Test
    void reloadAppliesEditsAndRetainsActiveSettingsWhenTheFileIsInvalid() throws Exception {
        Config.init(root.toFile());
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        Files.write(file, "[general]\nport = 25433\npassword = 'edited-password'\n".getBytes(StandardCharsets.UTF_8));
        Config.reload();
        assertEquals(25433, Config.AE_PORT());
        assertEquals("edited-password", Config.AE_PASSWORD());

        String invalid = "[general]\nport = 65536\npassword = 'must-not-be-published'\n";
        Files.write(file, invalid.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
        assertEquals(25433, Config.AE_PORT());
        assertEquals("edited-password", Config.AE_PASSWORD());
        assertEquals(invalid, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void partialTomlReceivesDefaultsAndPersistsItsGeneratedPassword() throws Exception {
        Path file = root.resolve("ae2webintegration/config.toml");
        Files.createDirectories(file.getParent());
        Files.write(file, "[general]\nport = 25434\n".getBytes(StandardCharsets.UTF_8));
        Config.init(root.toFile());
        String password = Config.AE_PASSWORD();
        assertFalse(password.isEmpty());
        assertTrue(Config.AE_PUBLIC_MODE());
        assertFalse(Config.TRACKING_TRACK_MACHINE_CRAFTING());
        Config.init(root.toFile());
        assertEquals(password, Config.AE_PASSWORD());
        assertEquals(25434, Config.AE_PORT());
    }

    @Test
    void malformedOrMissingFileCannotResetAnActiveConfiguration() throws Exception {
        Config.init(root.toFile());
        String password = Config.AE_PASSWORD();
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        Files.write(file, "[general".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, Config::reload);
        assertEquals(password, Config.AE_PASSWORD());
        Files.delete(file);
        assertThrows(UncheckedIOException.class, Config::reload);
        assertFalse(Files.exists(file));
        assertEquals(password, Config.AE_PASSWORD());
    }

    @Test
    void failedMigrationDoesNotCreateAConfigOrReplaceActiveSettings() {
        Path workingRoot = root.resolve("working");
        Config.init(workingRoot.toFile());
        String password = Config.AE_PASSWORD();
        assertThrows(IllegalArgumentException.class, () -> Config.init(root.toFile(), () -> {
            throw new IllegalArgumentException("Cannot read legacy configuration");
        }));
        assertEquals(password, Config.AE_PASSWORD());
        assertEquals(
            workingRoot.resolve("ae2webintegration")
                .toFile(),
            Config.getConfigDirectory());
        assertFalse(Files.exists(root.resolve("ae2webintegration/config.toml")));
    }

    @Test
    void migrationRejectsNumbersThatWouldOverflowAnIntegerSetting() {
        Map<String, Object> legacy = Collections.singletonMap("port", 4294992728L);
        assertThrows(RuntimeException.class, () -> Config.init(root.toFile(), () -> legacy));
        assertFalse(Files.exists(root.resolve("ae2webintegration/config.toml")));
    }

    @Test
    void userCommentsAndLiteralBackslashesSurviveReload() throws Exception {
        Config.init(root.toFile());
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        String contents = "[general]\n# My administrator password\npassword = 'C:\\Minecraft\\config'\n"
            + "# My custom port\nport = 25435\n";
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        Config.reload();
        assertEquals("C:\\Minecraft\\config", Config.AE_PASSWORD());
        Config.init(root.toFile());
        assertEquals("C:\\Minecraft\\config", Config.AE_PASSWORD());
        CommentedConfig saved = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertTrue(
            saved.getComment("general.password")
                .contains("My administrator password"));
        assertTrue(
            saved.getComment("general.port")
                .contains("My custom port"));
        assertNotNull(saved.getComment("discord.webhook"));
    }

    @Test
    void savingSpecialCharactersPreservesTheirValues() {
        String password = "quotes ' and \" and backslash \\ and newline\n and CRLF\r\n and CR\r and tab\t"
            + " and backspace\b and formfeed\f and Unicode zażółć";
        Config.init(root.toFile(), () -> Collections.singletonMap("password", password));
        Config.reload();
        assertEquals(password, Config.AE_PASSWORD());
    }

    @Test
    void unsupportedControlCharacterCannotReplaceTheFileOrActivePassword() throws Exception {
        Config.init(root.toFile());
        String password = Config.AE_PASSWORD();
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        String contents = "[general]\npassword = \"a\\u0001b\"\n";
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
        assertEquals(contents, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(password, Config.AE_PASSWORD());
    }
}
