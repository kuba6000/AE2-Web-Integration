package pl.kuba6000.ae2webintegration.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.notification.destination.discord.DiscordPayload;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyPayload;

class NotificationPayloadTest {

    @Test
    void queuedPayloadKeepsItsOriginalDestinationMetadataAfterConfigReload() {
        try (ConfigTestFixture config = new ConfigTestFixture()) {
            config.set("ntfy.topic", "original-topic");
            config.set("notifications.full_domain", "https://original.example");
            config.set("discord.role_id", "1234");
            NtfyPayload ntfy = new NtfyPayload("Title", "Message");
            DiscordPayload discord = new DiscordPayload("Title", "Message");

            config.set("ntfy.topic", "replacement-topic");
            config.set("notifications.full_domain", "https://replacement.example");
            config.set("discord.role_id", "5678");

            assertEquals(
                "original-topic",
                ntfy.serializePayload()
                    .get("topic")
                    .getAsString());
            assertEquals(
                "https://original.example",
                ntfy.serializePayload()
                    .get("click")
                    .getAsString());
            assertEquals(
                "<@&1234>",
                discord.serializePayload()
                    .get("content")
                    .getAsString());
        }
    }
}
