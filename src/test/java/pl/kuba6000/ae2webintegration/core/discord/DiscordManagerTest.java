package pl.kuba6000.ae2webintegration.core.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class DiscordManagerTest {

    private IConfigValue<Integer> previousMinimumDuration;
    private IConfigValue<Integer> previousMinimumAmount;
    private IConfigValue<String> previousWebhook;
    private IConfigValue<String> previousRole;

    @BeforeEach
    void resetConfig() {
        previousMinimumDuration = ConfigBootstrap.discordMinimumCraftingDurationSecondsValue;
        previousMinimumAmount = ConfigBootstrap.discordMinimumCraftingAmountValue;
        previousWebhook = ConfigBootstrap.discordWebhookValue;
        previousRole = ConfigBootstrap.discordRoleIdValue;
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = () -> 0;
        ConfigBootstrap.discordMinimumCraftingAmountValue = () -> 0;
    }

    @AfterEach
    void restoreConfig() {
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = previousMinimumDuration;
        ConfigBootstrap.discordMinimumCraftingAmountValue = previousMinimumAmount;
        ConfigBootstrap.discordWebhookValue = previousWebhook;
        ConfigBootstrap.discordRoleIdValue = previousRole;
    }

    @Test
    void invalidWebhooksAreDiagnosedWithoutKillingTheNotificationWorker() throws InterruptedException {
        AtomicReference<String> webhook = new AtomicReference<>("malformed-webhook");
        ConfigBootstrap.discordWebhookValue = webhook::get;
        ConfigBootstrap.discordRoleIdValue = () -> "";
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        Logger logger = (Logger) LogManager.getLogger("ae2webintegration - DISCORD INTEGRATION");
        AbstractAppender appender = new AbstractAppender("discord-errors", null, null, false, Property.EMPTY_ARRAY) {

            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.ERROR) {
                    assertTrue(
                        errors.offer(
                            event.getMessage()
                                .getFormattedMessage()));
                }
            }
        };
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        DiscordManager worker = new DiscordManager();
        worker.setDaemon(true);
        worker.setUncaughtExceptionHandler((thread, error) -> workerFailure.set(error));
        appender.start();
        logger.addAppender(appender);
        worker.start();
        try {
            DiscordManager.postMessageNonBlocking(new DiscordManager.DiscordEmbed("First", "First message"));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Malformed webhook must be diagnosed");

            // No connection should be opened for a protocol Discord webhooks do not support.
            webhook.set("http://127.0.0.1:1/webhook");
            DiscordManager.postMessageNonBlocking(new DiscordManager.DiscordEmbed("Second", "Second message"));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Unsupported protocol must be diagnosed");

            webhook.set("https://localhost:65536/webhook");
            DiscordManager.postMessageNonBlocking(new DiscordManager.DiscordEmbed("Third", "Third message"));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Invalid port must be diagnosed");

            webhook.set("another-malformed-webhook");
            DiscordManager.postMessageNonBlocking(new DiscordManager.DiscordEmbed("Fourth", "Fourth message"));
            assertNotNull(errors.poll(3, TimeUnit.SECONDS), "Worker must continue processing the queue");
            assertNull(workerFailure.get());
        } finally {
            worker.interrupt();
            worker.join(3000);
            logger.removeAppender(appender);
            appender.stop();
        }
        assertFalse(worker.isAlive(), "Idle worker must stop after interruption");
    }

    @ParameterizedTest
    @CsvSource({ "250, 0.25s", "3285, 3.285s", "47000, 47s", "800000, '13m 20s'", "3661000, '1h 1m 1s'",
        "7509000, '2h 5m 9s'", "86400000, '1d 0h 0m 0s'", "183845000, '2d 3h 4m 5s'" })
    void formatsCraftingDurationForDiscord(long durationMillis, String expected) {
        assertEquals(expected, DiscordManager.formatDuration(durationMillis));
    }

    @Test
    void durationThresholdFiltersShortCraftingJobs() {
        ConfigBootstrap.discordMinimumCraftingDurationSecondsValue = () -> 300;

        assertFalse(DiscordManager.shouldPostCraftingNotification(299_999L, 1L));
        assertTrue(DiscordManager.shouldPostCraftingNotification(300_000L, 1L));
    }

    @Test
    void amountThresholdFiltersSmallCraftingJobs() {
        ConfigBootstrap.discordMinimumCraftingAmountValue = () -> 1000;

        assertFalse(DiscordManager.shouldPostCraftingNotification(1L, 999L));
        assertTrue(DiscordManager.shouldPostCraftingNotification(1L, 1000L));
    }
}
