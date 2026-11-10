package pl.kuba6000.ae2webintegration.core.notification;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage.Severity;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class NotificationManagerTest {

    private ConfigTestFixture config;

    @BeforeEach
    void resetConfig() {
        config = new ConfigTestFixture();
    }

    @AfterEach
    void restoreConfig() {
        config.close();
    }

    @Test
    void anUnsupportedDestinationDoesNotPreventOtherDeliveriesOrLaterMessages() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            JsonObject payload = new JsonParser()
                .parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            received.add(
                payload.get("title")
                    .getAsString());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        config.set("general.public_mode", false);
        config.set("discord.webhook", "file:/unsupported-webhook");
        config.set(
            "ntfy.host",
            "http://127.0.0.1:" + server.getAddress()
                .getPort() + "/notify");
        config.set("ntfy.topic", "test-notifications");
        try {
            NotificationManager.init();
            assertNotNull(received.poll(3, SECONDS), "The other destination must receive startup");
            NotificationManager
                .postMessageNonBlocking(new StatusMessage("After failure", "Next notification", Severity.NONE));
            assertEquals("After failure", received.poll(3, SECONDS));
        } finally {
            NotificationManager.shutdown();
            server.stop(0);
        }
    }

    @Test
    void restartingDeliveryDiscardsMessagesFromTheRetiredWorker() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/notify", exchange -> {
            JsonObject payload = new JsonParser()
                .parse(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            received.add(
                payload.get("title")
                    .getAsString());
            if (first.getAndSet(false)) {
                try {
                    assertTrue(releaseFirst.await(5, SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        config.set("general.public_mode", false);
        config.set(
            "ntfy.host",
            "http://127.0.0.1:" + server.getAddress()
                .getPort() + "/notify");
        config.set("ntfy.topic", "test-notifications");
        try {
            NotificationManager.init();
            assertNotNull(received.poll(3, SECONDS));
            NotificationManager
                .postMessageNonBlocking(new StatusMessage("Retired message", "Queued before stop", Severity.NONE));
            NotificationManager.shutdown();
            NotificationManager.init();
            releaseFirst.countDown();
            String restarted = received.poll(3, SECONDS);
            assertNotNull(restarted);
            assertNotEquals("Retired message", restarted, "A restarted worker must not inherit old messages");
            NotificationManager
                .postMessageNonBlocking(new StatusMessage("Current message", "Queued after restart", Severity.NONE));
            assertEquals("Current message", received.poll(3, SECONDS));
        } finally {
            releaseFirst.countDown();
            NotificationManager.shutdown();
            server.stop(0);
        }
    }

    @Test
    void invalidWebhooksAreDiagnosedWithoutKillingTheNotificationWorker() throws InterruptedException {
        config.set("discord.webhook", "malformed-webhook");
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        Logger logger = (Logger) LogManager.getLogger("ae2webintegration - WEBHOOK INTEGRATION");
        AbstractAppender appender = new AbstractAppender("webhook-errors", null, null, false, Property.EMPTY_ARRAY) {

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
        appender.start();
        logger.addAppender(appender);
        try {
            NotificationManager.init();
            assertNotNull(errors.poll(3, SECONDS), "Malformed startup webhook must be diagnosed");
            NotificationManager.postMessageNonBlocking(new StatusMessage("First", "First message", Severity.NONE));
            assertNotNull(errors.poll(3, SECONDS), "Malformed webhook must be diagnosed");

            // No connection should be opened for a protocol Discord webhooks do not support.
            config.set("discord.webhook", "http://127.0.0.1:1/webhook");
            NotificationManager.reloadSettings();
            NotificationManager.postMessageNonBlocking(new StatusMessage("Second", "Second message", Severity.NONE));
            assertNotNull(errors.poll(3, SECONDS), "Unsupported protocol must be diagnosed");

            config.set("discord.webhook", "https://localhost:65536/webhook");
            NotificationManager.reloadSettings();
            NotificationManager.postMessageNonBlocking(new StatusMessage("Third", "Third message", Severity.NONE));
            assertNotNull(errors.poll(3, SECONDS), "Invalid port must be diagnosed");

            config.set("discord.webhook", "another-malformed-webhook");
            NotificationManager.reloadSettings();
            NotificationManager.postMessageNonBlocking(new StatusMessage("Fourth", "Fourth message", Severity.NONE));
            assertNotNull(errors.poll(3, SECONDS), "Worker must continue processing the queue");
        } finally {
            NotificationManager.shutdown();
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({ "250, 0.25s", "3285, 3.285s", "4999, 4.999s", "5000, 5s", "59499, 59s", "59500, '1m 0s'",
        "3599500, '1h 0m 0s'", "86399500, '1d 0h 0m 0s'", "47000, 47s", "800000, '13m 20s'", "3661000, '1h 1m 1s'",
        "7509000, '2h 5m 9s'", "86400000, '1d 0h 0m 0s'", "183845000, '2d 3h 4m 5s'" })
    void formatsCraftingDuration(long durationMillis, String expected) {
        assertEquals(expected, NotificationManager.formatDuration(durationMillis));
    }

    @Test
    void durationThresholdFiltersShortCraftingJobs() {
        config.set("notifications.minimum_crafting_duration_seconds", 300);

        assertFalse(NotificationManager.shouldPostCraftingNotification(299_999L, 1L));
        assertTrue(NotificationManager.shouldPostCraftingNotification(300_000L, 1L));
    }

    @Test
    void amountThresholdFiltersSmallCraftingJobs() {
        config.set("notifications.minimum_crafting_amount", 1000);

        assertFalse(NotificationManager.shouldPostCraftingNotification(1L, 999L));
        assertTrue(NotificationManager.shouldPostCraftingNotification(1L, 1000L));
    }
}
