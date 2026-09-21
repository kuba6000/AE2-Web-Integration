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
        String password = Config.INSTANCE.general.password;
        assertFalse(password.isEmpty());
        String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        CommentedConfig document = new TomlParser().parse(contents);
        assertNotNull(document.getComment("general.port"));
        assertNotNull(document.getComment("discord.webhook"));
        assertNotNull(document.getComment("tracking.track_machine_crafting"));
        assertTrue(document.contains("general.password"));

        Config.init(root.toFile());
        assertEquals(password, Config.INSTANCE.general.password);
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

        assertEquals(25432, Config.INSTANCE.general.port);
        assertEquals("existing-admin-password", Config.INSTANCE.general.password);
        assertFalse(Config.INSTANCE.general.allowNoPasswordOnLocalhost);
        assertEquals("192.0.2.10", Config.INSTANCE.general.trustedProxies);
        assertFalse(Config.INSTANCE.general.publicMode);
        assertEquals(37, Config.INSTANCE.general.maxRequestsBeforeLoggedInPerMinute);
        assertFalse(Config.INSTANCE.general.checkForUpdates);
        assertEquals("https://example.invalid/webhook", Config.INSTANCE.discord.webhook);
        assertEquals("123456789", Config.INSTANCE.discord.roleId);
        assertEquals(64, Config.INSTANCE.discord.minimumCraftingDurationSeconds);
        assertEquals(128, Config.INSTANCE.discord.minimumCraftingAmount);
        assertTrue(Config.INSTANCE.tracking.trackMachineCrafting);
    }

    @Test
    void reloadAppliesEditsAndRetainsActiveSettingsWhenTheFileIsInvalid() throws Exception {
        Config.init(root.toFile());
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        Files.write(file, "[general]\nport = 25433\npassword = 'edited-password'\n".getBytes(StandardCharsets.UTF_8));
        Config.reload();
        assertEquals(25433, Config.INSTANCE.general.port);
        assertEquals("edited-password", Config.INSTANCE.general.password);

        String invalid = "[general]\nport = 65536\npassword = 'must-not-be-published'\n";
        Files.write(file, invalid.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
        assertEquals(25433, Config.INSTANCE.general.port);
        assertEquals("edited-password", Config.INSTANCE.general.password);
        assertEquals(invalid, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    void partialTomlReceivesDefaultsAndPersistsItsGeneratedPassword() throws Exception {
        Path file = root.resolve("ae2webintegration/config.toml");
        Files.createDirectories(file.getParent());
        Files.write(file, "[general]\nport = 25434\n".getBytes(StandardCharsets.UTF_8));
        Config.init(root.toFile());
        String password = Config.INSTANCE.general.password;
        assertFalse(password.isEmpty());
        assertTrue(Config.INSTANCE.general.publicMode);
        assertFalse(Config.INSTANCE.tracking.trackMachineCrafting);
        Config.init(root.toFile());
        assertEquals(password, Config.INSTANCE.general.password);
        assertEquals(25434, Config.INSTANCE.general.port);
    }

    @Test
    void malformedOrMissingFileCannotResetAnActiveConfiguration() throws Exception {
        Config.init(root.toFile());
        String password = Config.INSTANCE.general.password;
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        Files.write(file, "[general".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, Config::reload);
        assertEquals(password, Config.INSTANCE.general.password);
        Files.delete(file);
        assertThrows(UncheckedIOException.class, Config::reload);
        assertFalse(Files.exists(file));
        assertEquals(password, Config.INSTANCE.general.password);
    }

    @Test
    void failedMigrationDoesNotCreateAConfigOrReplaceActiveSettings() {
        Path workingRoot = root.resolve("working");
        Config.init(workingRoot.toFile());
        String password = Config.INSTANCE.general.password;
        assertThrows(IllegalArgumentException.class, () -> Config.init(root.toFile(), () -> {
            throw new IllegalArgumentException("Cannot read legacy configuration");
        }));
        assertEquals(password, Config.INSTANCE.general.password);
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
        assertEquals("C:\\Minecraft\\config", Config.INSTANCE.general.password);
        Config.init(root.toFile());
        assertEquals("C:\\Minecraft\\config", Config.INSTANCE.general.password);
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
        assertEquals(password, Config.INSTANCE.general.password);
    }

    @Test
    void unsupportedControlCharacterCannotReplaceTheFileOrActivePassword() throws Exception {
        Config.init(root.toFile());
        String password = Config.INSTANCE.general.password;
        Path file = Config.getConfigFile("config.toml")
            .toPath();
        String contents = "[general]\npassword = \"a\\u0001b\"\n";
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
        assertEquals(contents, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(password, Config.INSTANCE.general.password);
    }
}
