package pl.kuba6000.ae2webintegration.core.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.io.BaseEncoding;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;

class TelemetryTest {

    @TempDir
    Path directory;
    private Path jar;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void createModArtifact() throws Exception {
        jar = directory.resolve("mod.jar");
        Files.write(jar, "abc".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void developmentRuntimeDoesNotScheduleAnyReport() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, null, scheduler, clock)) {
                assertNull(scheduler.scheduled);
                scheduler.runNext();
                assertEquals(0, endpoint.requests.get());
            }
        }
    }

    @Test
    void artifactIsReadOnlyOnFirstEnabledAttemptAndCachedWithFreshSaltPerReport() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            Files.delete(jar);
            Config.setTelemetryEnabled(false);
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, scheduler, clock)) {
                scheduler.runNext();
                assertEquals(0, endpoint.requests.get());
                Files.write(jar, "abc".getBytes(StandardCharsets.US_ASCII));
                Config.setTelemetryEnabled(true);
                CompletableFuture.runAsync(scheduler::runNext)
                    .get(5, TimeUnit.SECONDS);
                JsonObject first = endpoint.report.get()
                    .getAsJsonObject("jarProof");
                assertArtifactProof(first);
                Files.delete(jar);
                scheduler.runNext();
                JsonObject next = endpoint.report.get()
                    .getAsJsonObject("jarProof");
                assertArtifactProof(next);
                assertNotEquals(first.get("salt"), next.get("salt"));
                assertNotEquals(first.get("sha256"), next.get("sha256"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void missingFileOrDirectoryReportsUnknownAndDoesNotRepeatedlyRead(boolean directoryInstead) throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            Files.delete(jar);
            if (directoryInstead) Files.createDirectory(jar);
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, scheduler, clock)) {
                scheduler.runNext();
                assertTrue(
                    endpoint.report.get()
                        .get("jarProof")
                        .isJsonNull());
                if (directoryInstead) Files.delete(jar);
                Files.write(jar, "abc".getBytes(StandardCharsets.US_ASCII));
                scheduler.runNext();
                assertEquals(2, endpoint.requests.get());
                assertTrue(
                    endpoint.report.get()
                        .get("jarProof")
                        .isJsonNull());
            }
        }
    }

    private static void assertArtifactProof(JsonObject proof) {
        String salt = proof.get("salt")
            .getAsString();
        assertTrue(salt.matches("[0-9a-f]{32}"));
        assertTrue(
            proof.get("sha256")
                .getAsString()
                .matches("[0-9a-f]{64}"));
        BaseEncoding hex = BaseEncoding.base16()
            .lowerCase();
        assertEquals(
            JarProof.create(
                hex.decode("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                hex.decode(salt)),
            proof);
    }

    @Test
    void untrustedCertificatePreventsAnyHttpReport() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(false)) {
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, scheduler, clock)) {
                assertDoesNotThrow(scheduler::runNext);
                assertEquals(0, endpoint.requests.get());
            }
        }
    }

    @Test
    void neverFollowsRedirectOrRetriesImmediatelyAndRecoversOnNormalCadence() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, scheduler, clock)) {
                endpoint.status = 307;
                scheduler.runNext();
                assertEquals(1, endpoint.requests.get());
                assertEquals(0, endpoint.redirected.get());
                endpoint.status = 503;
                scheduler.runNext();
                assertEquals(2, endpoint.requests.get());
                endpoint.status = 204;
                scheduler.runNext();
                assertEquals(3, endpoint.requests.get());
                assertNotNull(endpoint.report.get());
            }
        }
    }

    @Test
    void closingDoesNotWaitForAnInFlightResponse() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            endpoint.holdResponse = true;
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, scheduler, clock)) {
                CompletableFuture<Void> attempt = CompletableFuture.runAsync(scheduler::runNext);
                try {
                    assertTrue(endpoint.received.await(5, TimeUnit.SECONDS));
                    assertTimeoutPreemptively(Duration.ofSeconds(1), telemetry::close);
                } finally {
                    endpoint.release.countDown();
                }
                attempt.get(5, TimeUnit.SECONDS);
                scheduler.runNext();
                assertEquals(1, endpoint.requests.get());
            }
        }
    }

    @Test
    void reportsOnlyTheFiveFieldsAndRetainsSessionAcrossDisableAndEnable() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            ManualScheduler scheduler = new ManualScheduler();
            String firstSession;
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", true, jar, scheduler, clock)) {
                assertEquals(0, endpoint.requests.get(), "startup must only schedule background work");
                scheduler.runNext();
                JsonObject report = endpoint.report.get();
                assertNotNull(report);
                assertEquals(
                    5,
                    report.entrySet()
                        .size());
                assertFalse(report.has("modVersion"), "plaintext mod version must not leave the client");
                assertEquals(
                    "1.7.10",
                    report.get("minecraftVersion")
                        .getAsString());
                assertTrue(
                    report.get("singleplayer")
                        .getAsBoolean());
                assertTrue(
                    report.get("publicMode")
                        .getAsBoolean());
                firstSession = report.get("sessionId")
                    .getAsString();
                assertEquals(
                    4,
                    UUID.fromString(firstSession)
                        .version());
                Config.setTelemetryEnabled(false);
                scheduler.runNext();
                assertEquals(1, endpoint.requests.get());
                Config.setTelemetryEnabled(true);
                config.set("general.public_mode", false);
                scheduler.runNext();
                assertEquals(
                    firstSession,
                    endpoint.report.get()
                        .get("sessionId")
                        .getAsString());
                assertFalse(
                    endpoint.report.get()
                        .get("publicMode")
                        .getAsBoolean());
            }
            scheduler.runNext();
            assertEquals(2, endpoint.requests.get(), "closed lifecycle must never report again");
            ManualScheduler next = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, next, clock)) {
                next.runNext();
                assertNotEquals(
                    firstSession,
                    endpoint.report.get()
                        .get("sessionId")
                        .getAsString());
                assertFalse(
                    endpoint.report.get()
                        .get("singleplayer")
                        .getAsBoolean());
            }
        }
    }

    @Test
    void rotatesAtFirstReportOfNextUtcDayAndRetainsIdAfterFailedAttempt() throws Exception {
        try (ConfigTestFixture config = new ConfigTestFixture(); Endpoint endpoint = new Endpoint(true)) {
            clock.now = Instant.parse("2026-10-25T22:16:00Z");
            ManualScheduler scheduler = new ManualScheduler();
            try (Telemetry telemetry = new Telemetry(endpoint.url(), "1.7.10", false, jar, scheduler, clock)) {
                scheduler.runNext();
                String previous = endpoint.report.get()
                    .get("sessionId")
                    .getAsString();
                clock.now = Instant.parse("2026-10-25T23:16:00Z");
                scheduler.runNext();
                assertEquals(
                    previous,
                    endpoint.report.get()
                        .get("sessionId")
                        .getAsString());
                clock.now = Instant.parse("2026-10-26T00:16:00Z");
                endpoint.status = 503;
                scheduler.runNext();
                String nextDay = endpoint.report.get()
                    .get("sessionId")
                    .getAsString();
                assertNotEquals(previous, nextDay);
                assertEquals(
                    4,
                    UUID.fromString(nextDay)
                        .version());
                clock.now = Instant.parse("2026-10-26T01:16:00Z");
                endpoint.status = 204;
                scheduler.runNext();
                assertEquals(
                    nextDay,
                    endpoint.report.get()
                        .get("sessionId")
                        .getAsString());
                Config.setTelemetryEnabled(false);
                clock.now = Instant.parse("2026-10-28T00:16:00Z");
                scheduler.runNext();
                assertEquals(4, endpoint.requests.get());
                Config.setTelemetryEnabled(true);
                scheduler.runNext();
                assertNotEquals(
                    nextDay,
                    endpoint.report.get()
                        .get("sessionId")
                        .getAsString());
            }
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-25T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.ofHours(5);
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {

        private Runnable scheduled;

        ManualScheduler() {
            super(1, task -> {
                Thread thread = new Thread(task);
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public @NotNull ScheduledFuture<?> scheduleWithFixedDelay(@NotNull Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
            assertNull(scheduled, "one recurring task, no retry backlog");
            assertEquals(600, unit.toSeconds(initialDelay));
            assertTrue(unit.toMinutes(delay) >= 55 && unit.toMinutes(delay) <= 65);
            scheduled = command;
            return super.schedule(() -> {}, 1, TimeUnit.DAYS);
        }

        void runNext() {
            if (scheduled != null) scheduled.run();
        }
    }

    private static final class Endpoint implements AutoCloseable {

        final HttpsServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<JsonObject> report = new AtomicReference<>();
        final AtomicInteger redirected = new AtomicInteger();
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean holdResponse;
        volatile int status = 204;
        private final SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();

        Endpoint(boolean trust) throws Exception {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream input = getClass().getResourceAsStream("/telemetry/localhost.p12")) {
                keys.load(input, "test-only".toCharArray());
            }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, "test-only".toCharArray());
            TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusts.init(keys);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(managers.getKeyManagers(), trusts.getTrustManagers(), null);
            if (trust) HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            server.createContext("/api/telemetry", exchange -> {
                requests.incrementAndGet();
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals(
                    "application/json",
                    exchange.getRequestHeaders()
                        .getFirst("Content-Type"));
                try (InputStream input = exchange.getRequestBody()) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                    report.set(
                        new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                            .getAsJsonObject());
                }
                received.countDown();
                if (holdResponse) {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread()
                            .interrupt();
                    }
                }
                if (status == 307) exchange.getResponseHeaders()
                    .set("Location", "/redirect");
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.createContext("/redirect", exchange -> {
                redirected.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.start();
        }

        URL url() throws Exception {
            return new URL(
                "https://127.0.0.1:" + server.getAddress()
                    .getPort() + "/api/telemetry");
        }

        @Override
        public void close() {
            server.stop(0);
            HttpsURLConnection.setDefaultSSLSocketFactory(previous);
        }
    }
}
