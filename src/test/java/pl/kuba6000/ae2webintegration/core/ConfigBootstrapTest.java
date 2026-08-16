package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.IConfigBuilder;
import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;

/** Tests for {@link ConfigBootstrap} config key definition. */
class ConfigBootstrapTest {

    /** Reset ConfigBootstrap fields to their pre-init defaults before each test. */
    @BeforeEach
    void resetConfigBootstrap() {
        ConfigBootstrap.aePortValue = () -> 2324;
        ConfigBootstrap.aePasswordValue = () -> "";
        ConfigBootstrap.allowNoPasswordOnLocalhostValue = () -> true;
        ConfigBootstrap.aePublicModeValue = () -> true;
        ConfigBootstrap.aeMaxRequestsBeforeLoggedInPerMinuteValue = () -> 20;
        ConfigBootstrap.checkForUpdatesValue = () -> true;
        ConfigBootstrap.discordWebhookValue = () -> "";
        ConfigBootstrap.discordRoleIdValue = () -> "";
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = () -> 0;
        ConfigBootstrap.discordMinimumCraftingAmountValue = () -> 0;
        ConfigBootstrap.trackingTrackMachineCraftingValue = () -> false;
    }

    @Test
    void testAllConfigKeysRegistered() {
        RecordingConfigBuilder builder = new RecordingConfigBuilder();
        ConfigBootstrap.init(builder);

        // Should have exactly 12 config key definitions
        assertEquals(12, builder.calls.size(), "expected exactly 12 config key definitions");

        // Verify all expected keys with their types
        assertContainsCall("int", "port", builder.calls);
        assertContainsCall("string", "password", builder.calls);
        assertContainsCall("boolean", "allow_no_password_on_localhost", builder.calls);
        assertContainsCall("string", "trusted_proxies", builder.calls);
        assertContainsCall("boolean", "public_mode", builder.calls);
        assertContainsCall("int", "max_requests_before_logged_in_per_minute", builder.calls);
        assertContainsCall("boolean", "check_for_updates", builder.calls);
        assertContainsCall("string", "discord_webhook", builder.calls);
        assertContainsCall("string", "discord_role_id", builder.calls);
        assertContainsCall("int", "discord_minimum_crafting_duration_seconds", builder.calls);
        assertContainsCall("int", "discord_minimum_crafting_amount", builder.calls);
        assertContainsCall("boolean", "track_machine_crafting", builder.calls);
    }

    @Test
    void testDefaultValues() {
        RecordingConfigBuilder builder = new RecordingConfigBuilder();
        ConfigBootstrap.init(builder);

        for (DefCall call : builder.calls) {
            switch (call.key) {
                case "port":
                    assertEquals(2324, call.defValue, "port default");
                    assertEquals(1, call.min, "port min");
                    assertEquals(65535, call.max, "port max");
                    break;
                case "max_requests_before_logged_in_per_minute":
                    assertEquals(20, call.defValue, "max_requests default");
                    break;
                case "discord_minimum_crafting_duration_seconds":
                case "discord_minimum_crafting_amount":
                    assertEquals(0, call.defValue, call.key + " default");
                    assertEquals(0, call.min, call.key + " min");
                    assertEquals(Integer.MAX_VALUE, call.max, call.key + " max");
                    break;
                case "allow_no_password_on_localhost":
                case "public_mode":
                case "check_for_updates":
                    assertEquals(true, call.defValue, call.key + " default");
                    break;
                case "trusted_proxies":
                    // Empty: a proxy on this machine is handled by a built-in rule, not by config.
                    assertEquals("", call.defValue, "trusted_proxies default");
                    break;
                case "track_machine_crafting":
                    assertEquals(false, call.defValue, call.key + " default");
                    break;
                case "password":
                    // Password should be a non-empty random string
                    assertNotNull(call.defValue, "password should not be null");
                    assertFalse(((String) call.defValue).isEmpty(), "password should not be empty");
                    break;
                case "discord_webhook":
                case "discord_role_id":
                    assertEquals("", call.defValue, call.key + " default");
                    break;
                default:
                    fail("unexpected key: " + call.key);
            }
        }
    }

    @Test
    void testGetValuesAfterInit() {
        RecordingConfigBuilder builder = new RecordingConfigBuilder();
        ConfigBootstrap.init(builder);

        // After init, values should be returned by the IConfigValue stubs
        assertEquals(
            2324,
            ConfigBootstrap.aePortValue.get()
                .intValue(),
            "AE_PORT");
        assertEquals(true, ConfigBootstrap.allowNoPasswordOnLocalhostValue.get(), "ALLOW_NO_PASSWORD");
        assertEquals(true, ConfigBootstrap.aePublicModeValue.get(), "AE_PUBLIC_MODE");
        assertEquals(
            20,
            ConfigBootstrap.aeMaxRequestsBeforeLoggedInPerMinuteValue.get()
                .intValue(),
            "MAX_REQUESTS");
        assertEquals(true, ConfigBootstrap.checkForUpdatesValue.get(), "CHECK_FOR_UPDATES");
        assertEquals("", ConfigBootstrap.discordWebhookValue.get(), "DISCORD_WEBHOOK");
        assertEquals("", ConfigBootstrap.discordRoleIdValue.get(), "DISCORD_ROLE_ID");
        assertEquals(
            0,
            ConfigBootstrap.discordMinimumCraftingDurationSecondsValue.get()
                .intValue(),
            "DISCORD_MINIMUM_CRAFTING_DURATION_SECONDS");
        assertEquals(
            0,
            ConfigBootstrap.discordMinimumCraftingAmountValue.get()
                .intValue(),
            "DISCORD_MINIMUM_CRAFTING_AMOUNT");
        assertEquals(false, ConfigBootstrap.trackingTrackMachineCraftingValue.get(), "TRACK_MACHINE_CRAFTING");
        // Password should be a non-empty random string (generated at init time)
        String password = ConfigBootstrap.aePasswordValue.get();
        assertNotNull(password, "password should not be null");
        assertFalse(password.isEmpty(), "password should not be empty");
        assertTrue(password.length() >= 8, "password should be reasonably long");
    }

    @Test
    void testPasswordRandomized() {
        // Verify that password is generated fresh each time init() is called
        RecordingConfigBuilder builder1 = new RecordingConfigBuilder();
        ConfigBootstrap.init(builder1);
        String pwd1 = ConfigBootstrap.aePasswordValue.get();

        resetConfigBootstrap();

        RecordingConfigBuilder builder2 = new RecordingConfigBuilder();
        ConfigBootstrap.init(builder2);
        String pwd2 = ConfigBootstrap.aePasswordValue.get();

        // Two different init() calls should produce different passwords
        // (extremely unlikely to collide randomly)
        assertNotEquals(pwd1, pwd2, "passwords should be randomized");
    }

    // --- Helper assertions ---

    private static void assertContainsCall(String type, String key, List<DefCall> calls) {
        for (DefCall call : calls) {
            if (call.type.equals(type) && call.key.equals(key)) return;
        }
        fail("missing config definition: " + type + " " + key);
    }

    // --- Recording IConfigBuilder stub ---

    private static class RecordingConfigBuilder implements IConfigBuilder {

        final List<DefCall> calls = new ArrayList<>();

        @Override
        public IConfigValue<Integer> defineInt(String key, int defaultValue, int min, int max, String comment) {
            calls.add(new DefCall("int", key, defaultValue, min, max, comment));
            return () -> defaultValue;
        }

        @Override
        public IConfigValue<String> defineString(String key, String defaultValue, String comment) {
            calls.add(new DefCall("string", key, defaultValue, comment));
            return () -> defaultValue;
        }

        @Override
        public IConfigValue<Boolean> defineBoolean(String key, boolean defaultValue, String comment) {
            calls.add(new DefCall("boolean", key, defaultValue, comment));
            return () -> defaultValue;
        }
    }

    /** Records a single config definition call. */
    private static class DefCall {

        final String type;
        final String key;
        final Object defValue;
        final int min;
        final int max;
        final String comment;

        DefCall(String type, String key, Object defValue, int min, int max, String comment) {
            this.type = type;
            this.key = key;
            this.defValue = defValue;
            this.min = min;
            this.max = max;
            this.comment = comment;
        }

        DefCall(String type, String key, Object defValue, String comment) {
            this(type, key, defValue, 0, 0, comment);
        }
    }
}
