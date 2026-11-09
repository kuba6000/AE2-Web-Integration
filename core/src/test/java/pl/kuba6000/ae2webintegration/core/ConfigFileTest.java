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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;

class ConfigFileTest {

    @Test
    void telemetryTogglePersistsWithoutReloadingOtherSettings() throws Exception {
        Config.init(root.toFile());
        assertTrue(Config.INSTANCE.telemetry.enabled);
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        document.set("general.public_mode", false);
        document.set("notifications.minimum_crafting_amount", 123);
        document.set("discord.webhook", "https://example.invalid/pending-secret");
        document.set("future.value", "preserved");
        document.setComment("future.value", " custom comment");
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        ConfigSettings before = Config.INSTANCE;

        Config.setTelemetryEnabled(false);

        assertTrue(before.telemetry.enabled);
        assertFalse(Config.INSTANCE.telemetry.enabled);
        assertTrue(Config.INSTANCE.general.publicMode, "an explicit telemetry command must not reload unrelated edits");
        assertEquals(before.notifications.minimumCraftingAmount, Config.INSTANCE.notifications.minimumCraftingAmount);
        assertEquals(before.discord.webhook, Config.INSTANCE.discord.webhook);
        CommentedConfig saved = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals("preserved", saved.get("future.value"));
        assertEquals(" custom comment", saved.getComment("future.value"));
        assertEquals(123, (Integer) saved.get("notifications.minimum_crafting_amount"));
        assertEquals("https://example.invalid/pending-secret", saved.get("discord.webhook"));
        Config.reload();
        assertFalse(Config.INSTANCE.telemetry.enabled);
        assertFalse(Config.INSTANCE.general.publicMode);
        assertEquals(123, Config.INSTANCE.notifications.minimumCraftingAmount);
        assertEquals("https://example.invalid/pending-secret", Config.INSTANCE.discord.webhook);
    }

    @Test
    void failedTelemetryWriteKeepsThePublishedSettingAndExistingFile() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        byte[] malformed = "[telemetry\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, malformed);
        ConfigSettings before = Config.INSTANCE;
        assertThrows(RuntimeException.class, () -> Config.setTelemetryEnabled(false));
        assertSame(before, Config.INSTANCE);
        assertTrue(Config.INSTANCE.telemetry.enabled);
        assertArrayEquals(malformed, Files.readAllBytes(file));
    }

    @Test
    void discordCraftingStylesRoundTripThroughToml() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals("IMAGE_CARD", document.get("discord.crafting_style"));
        for (String style : new String[] { "SIMPLE", "SMALL_ICON", "IMAGE_CARD" }) {
            document.set("discord.crafting_style", style);
            Files.write(
                file,
                new TomlWriter().writeToString(document)
                    .getBytes(StandardCharsets.UTF_8));
            Config.reload();
            assertEquals(style, Config.INSTANCE.discord.craftingStyle.name());
            Config.init(root.toFile());
            assertEquals(style, Config.INSTANCE.discord.craftingStyle.name());
            CommentedConfig restored = new TomlParser()
                .parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            assertEquals(style, restored.get("discord.crafting_style"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void invalidCraftingStyleUsesDefaultWithoutResettingOtherSettings(boolean reload) throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        document.set("general.password", "keep-this-password");
        document.set("discord.role_id", "123456789");
        for (Object invalid : new Object[] { "INVALID", "", 1, true }) {
            document.set("discord.crafting_style", "SIMPLE");
            Files.write(
                file,
                new TomlWriter().writeToString(document)
                    .getBytes(StandardCharsets.UTF_8));
            Config.reload();
            document.set("discord.crafting_style", invalid);
            Files.write(
                file,
                new TomlWriter().writeToString(document)
                    .getBytes(StandardCharsets.UTF_8));
            if (reload) Config.reload();
            else Config.init(root.toFile());
            assertEquals(ConfigSettings.Discord.CraftingStyle.IMAGE_CARD, Config.INSTANCE.discord.craftingStyle);
            assertEquals("keep-this-password", Config.INSTANCE.general.password);
            assertEquals("123456789", Config.INSTANCE.discord.roleId);
            CommentedConfig restored = new TomlParser()
                .parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            assertEquals("IMAGE_CARD", restored.get("discord.crafting_style"));
            assertEquals("keep-this-password", restored.get("general.password"));
            assertEquals("123456789", restored.get("discord.role_id"));
        }
    }

    @Test
    void craftingRetentionUsesIndependentBoundsAndSurvivesDetachedCopy() {
        ConfigSettings.ResourceHistory resource = new ConfigSettings.ResourceHistory();
        ConfigSettings.CraftingHistory crafting = new ConfigSettings.CraftingHistory();
        crafting.historyRetentionDays = new ConfigSettings.Range(2, 12, 4);
        GridSettingsData settings = new GridSettingsData(resource, crafting);
        assertEquals(4, settings.getCraftingHistoryRetentionDays());
        settings.setCraftingHistoryRetentionDays(10);
        assertEquals(7, settings.getHistoryRetentionDays());
        assertEquals(
            10,
            settings.copy()
                .getCraftingHistoryRetentionDays());
        assertThrows(IllegalArgumentException.class, () -> settings.setCraftingHistoryRetentionDays(13));
        ConfigSettings.CraftingHistory tighter = new ConfigSettings.CraftingHistory();
        tighter.historyRetentionDays = new ConfigSettings.Range(2, 5, 3);
        assertTrue(settings.applyPolicy(resource, tighter));
        assertEquals(5, settings.getCraftingHistoryRetentionDays());
        assertFalse(settings.applyPolicy(resource, tighter));
        assertEquals(7, settings.getHistoryRetentionDays());
    }

    @Test
    void craftingHistoryBoundsRoundTripAndRejectInvalidCaps() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(7, (Integer) document.get("crafting_history.history_retention_days.default"));
        assertEquals(1000, (Integer) document.get("crafting_history.history_max_jobs_per_grid"));
        document.set("crafting_history.history_retention_days.min", 2);
        document.set("crafting_history.history_retention_days.max", 12);
        document.set("crafting_history.history_retention_days.default", 4);
        document.set("crafting_history.history_max_jobs_per_grid", 20);
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        Config.init(root.toFile());
        CommentedConfig restored = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(4, (Integer) restored.get("crafting_history.history_retention_days.default"));
        assertEquals(20, (Integer) restored.get("crafting_history.history_max_jobs_per_grid"));
        ConfigSettings active = Config.INSTANCE;
        document.set("crafting_history.history_max_jobs_per_grid", 0);
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
        assertSame(active, Config.INSTANCE);
    }

    @Test
    void publishedDataConfigDirectlyDefinesGridDefaultsAndBounds() {
        ConfigSettings.ResourceHistory data = new ConfigSettings.ResourceHistory();
        data.stockSampleIntervalSeconds = new ConfigSettings.Range(5, 30, 10);
        GridSettingsData settings = new GridSettingsData(data);
        assertEquals(10, settings.getStockSampleIntervalSeconds());
        settings.setStockSampleIntervalSeconds(30);
        assertEquals(30, settings.getStockSampleIntervalSeconds());
        assertThrows(IllegalArgumentException.class, () -> settings.setStockSampleIntervalSeconds(31));
        assertEquals(30, settings.getStockSampleIntervalSeconds());
    }

    @TempDir
    Path root;

    @Test
    void dataPolicyRangesRoundTripAndRejectInvalidDefaultWithoutReplacingActiveConfig() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(300, (Integer) document.get("resource_history.stock_sample_interval_seconds.default"));
        assertEquals(60, (Integer) document.get("resource_history.flow_flush_interval_seconds.default"));
        assertEquals(7, (Integer) document.get("resource_history.history_retention_days.default"));
        assertEquals(60, (Integer) document.get("resource_history.stock_sample_interval_seconds.min"));
        assertEquals(60, (Integer) document.get("resource_history.flow_flush_interval_seconds.min"));
        document.set("resource_history.stock_sample_interval_seconds.min", 10);
        document.set("resource_history.stock_sample_interval_seconds.max", 600);
        document.set("resource_history.stock_sample_interval_seconds.default", 120);
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        Config.init(root.toFile());
        CommentedConfig restored = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(120, (Integer) restored.get("resource_history.stock_sample_interval_seconds.default"));
        ConfigSettings active = Config.INSTANCE;
        document.set("resource_history.stock_sample_interval_seconds.default", 5);
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
        assertSame(active, Config.INSTANCE);
    }

    @Test
    void minuteFlowDefaultDoesNotReplaceExplicitFiveSecondConfiguration() throws Exception {
        Config.init(root.toFile());
        assertEquals(60, Config.INSTANCE.resourceHistory.flowFlushIntervalSeconds.defaultValue);
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        document.set("resource_history.flow_flush_interval_seconds.default", 5);
        document.set("resource_history.flow_flush_interval_seconds.min", 1);
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        Config.init(root.toFile());
        assertEquals(5, Config.INSTANCE.resourceHistory.flowFlushIntervalSeconds.defaultValue);
        document.remove("resource_history.flow_flush_interval_seconds.default");
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        assertEquals(60, Config.INSTANCE.resourceHistory.flowFlushIntervalSeconds.defaultValue);
    }

    @Test
    void flowHistoryIntervalIsConfigurableAndRejectsZero() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertTrue(document.contains("resource_history.flow_flush_interval_seconds.default"));
        document.set("resource_history.flow_flush_interval_seconds.default", 11);
        document.set("resource_history.flow_flush_interval_seconds.min", 1);
        TomlWriter writer = new TomlWriter();
        Files.write(
            file,
            writer.writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        assertEquals(11, Config.INSTANCE.resourceHistory.flowFlushIntervalSeconds.defaultValue);
        document.set("resource_history.flow_flush_interval_seconds.default", 0);
        Files.write(
            file,
            writer.writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
    }

    @Test
    void historyRetentionAndRowLimitsArePersistedAndValidated() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(7, (Integer) document.get("resource_history.history_retention_days.default"));
        assertTrue(document.contains("resource_history.history_max_rows_per_grid"));
        for (String key : new String[] { "history_retention_days.default", "history_max_rows_per_grid" }) {
            Object original = document.get("resource_history." + key);
            document.set("resource_history." + key, 0);
            Files.write(
                file,
                new TomlWriter().writeToString(document)
                    .getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, Config::reload);
            document.set("resource_history." + key, original);
        }
        document.set("resource_history.history_retention_days.default", 3);
        document.set("resource_history.history_max_rows_per_grid", 5000);
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        Config.init(root.toFile());
        CommentedConfig reloaded = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(3, (Integer) reloaded.get("resource_history.history_retention_days.default"));
        assertEquals(5000, (Integer) reloaded.get("resource_history.history_max_rows_per_grid"));
    }

    @Test
    void resourceTrackingLimitsArePersistedAndInvalidIntervalsAreRejected() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        CommentedConfig document = new TomlParser().parse(contents);
        assertTrue(document.contains("resource_tracking.active_interval_millis"));
        assertTrue(document.contains("resource_history.history_max_rows_per_grid"));
        Files.write(
            file,
            contents.replaceAll("active_interval_millis = [0-9]+", "active_interval_millis = 0")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, Config::reload);
    }

    @Test
    void obsoleteTrackingTableIsRemovedWithoutChangingUnrelatedUnknownConfig() throws Exception {
        Config.init(root.toFile());
        Path file = root.resolve("ae2webintegration/config.toml");
        CommentedConfig document = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        document.set("tracking.track_machine_crafting", true);
        document.set("custom_extension.enabled", true);
        document.setComment("custom_extension.enabled", "Keep this extension option");
        Files.write(
            file,
            new TomlWriter().writeToString(document)
                .getBytes(StandardCharsets.UTF_8));
        Config.reload();
        CommentedConfig reloaded = new TomlParser().parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertFalse(reloaded.contains("tracking"));
        assertEquals(Boolean.TRUE, reloaded.get("custom_extension.enabled"));
        assertEquals("Keep this extension option", reloaded.getComment("custom_extension.enabled"));
    }

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
        assertFalse(document.contains("tracking"));
        assertTrue(document.contains("general.password"));

        Config.init(root.toFile());
        assertEquals(password, Config.INSTANCE.general.password);
    }

    @Test
    void migrationPreservesSettingsAndExistingTomlTakesPrecedence() throws Exception {
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

        MapLegacyConfig legacyConfig = new MapLegacyConfig(legacy);
        Config.init(root.toFile(), () -> legacyConfig);
        assertTrue(legacyConfig.migrated);
        Config
            .init(root.toFile(), () -> { throw new AssertionError("Existing TOML must bypass legacy file parsing"); });

        assertEquals(25432, Config.INSTANCE.general.port);
        assertEquals("existing-admin-password", Config.INSTANCE.general.password);
        assertFalse(Config.INSTANCE.general.allowNoPasswordOnLocalhost);
        assertEquals("192.0.2.10", Config.INSTANCE.general.trustedProxies);
        assertFalse(Config.INSTANCE.general.publicMode);
        assertEquals(37, Config.INSTANCE.general.maxRequestsBeforeLoggedInPerMinute);
        assertFalse(Config.INSTANCE.general.checkForUpdates);
        assertEquals("https://example.invalid/webhook", Config.INSTANCE.discord.webhook);
        assertEquals("123456789", Config.INSTANCE.discord.roleId);
        assertEquals(64, Config.INSTANCE.notifications.minimumCraftingDurationSeconds);
        assertEquals(128, Config.INSTANCE.notifications.minimumCraftingAmount);
        CommentedConfig document = new TomlParser().parse(
            new String(Files.readAllBytes(root.resolve("ae2webintegration/config.toml")), StandardCharsets.UTF_8));
        assertFalse(document.contains("tracking"));
        assertFalse(new GridSettingsData().isTrackMachineCrafting());
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
        assertThrows(
            IllegalArgumentException.class,
            () -> Config.init(root.toFile(), () -> new ThrowingLegacyConfig("Cannot read legacy configuration")));
        assertEquals(password, Config.INSTANCE.general.password);
        assertEquals(
            workingRoot.resolve("ae2webintegration")
                .toFile(),
            Config.getConfigDirectory());
        assertFalse(Files.exists(root.resolve("ae2webintegration/config.toml")));
    }

    @Test
    void migrationRejectsNumbersThatWouldOverflowAnIntegerSetting() {
        MapLegacyConfig legacy = new MapLegacyConfig(Collections.singletonMap("port", 4294992728L));
        assertThrows(RuntimeException.class, () -> Config.init(root.toFile(), () -> legacy));
        assertFalse(legacy.migrated);
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
        Config.init(root.toFile(), () -> new MapLegacyConfig(Collections.singletonMap("password", password)));
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

    private static final class MapLegacyConfig implements ILegacyConfigProvider {

        private final Map<String, Object> values;
        private boolean migrated;

        private MapLegacyConfig(Map<String, Object> values) {
            this.values = values;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Object get(String key) {
            return values.get(key);
        }

        @Override
        public void markAsMigrated() {
            migrated = true;
        }
    }

    private static final class ThrowingLegacyConfig implements ILegacyConfigProvider {

        private final String message;

        private ThrowingLegacyConfig(String message) {
            this.message = message;
        }

        @Override
        public boolean isAvailable() {
            throw new IllegalArgumentException(message);
        }

        @Override
        public Object get(String key) {
            throw new IllegalArgumentException(message);
        }

        @Override
        public void markAsMigrated() {
            throw new AssertionError(message);
        }
    }
}
