package pl.kuba6000.ae2webintegration.core.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

class VersionCheckerTest {

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void timesOutIdleResponseAndCanCheckAgain(boolean headers) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            VersionChecker checker = new VersionChecker(
                new URL("http://127.0.0.1:" + server.getLocalPort() + "/"),
                "1.0.0-forge-1.7.10",
                "-forge-1.7.10")) {
            server.setSoTimeout(5000);
            CompletableFuture<ReleaseManifest.Release> first = checker.checkForUpdates();
            try (Socket stalled = server.accept()) {
                stalled.getOutputStream()
                    .write(
                        (headers ? "HTTP/1.1 200 OK\r\nX-Slow: " : "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                stalled.getOutputStream()
                    .flush();
                ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> first.get(8, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
            }
            CompletableFuture<ReleaseManifest.Release> second = checker.checkForUpdates();
            try (Socket healthy = server.accept()) {
                byte[] body = ReleaseManifestTest.feed("1.1.0", null)
                    .getBytes(StandardCharsets.UTF_8);
                healthy.getOutputStream()
                    .write(
                        ("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                healthy.getOutputStream()
                    .write(body);
                healthy.getOutputStream()
                    .flush();
                assertEquals("1.1.0", second.get(5, TimeUnit.SECONDS).version);
            }
        }
    }

    @Test
    void rejectsOversizedChunkedFeedWithoutContentLength() throws Exception {
        AtomicReference<String> body = new AtomicReference<>(ReleaseManifestTest.feed("1.1.0", null));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
            try {
                exchange.getResponseBody()
                    .write(
                        body.get()
                            .getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (VersionChecker checker = new VersionChecker(
            new URL(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort() + "/"),
            "1.0.0-forge-1.7.10",
            "-forge-1.7.10")) {
            assertEquals(
                "1.1.0",
                checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS).version);
            // Valid JSON plus whitespace would parse successfully without the streaming size limit.
            body.set(body.get() + new String(new char[70_000]).replace('\0', ' '));
            ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("1.1.0", checker.getAvailableUpdate().version);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshesExistingUpdateAndRetainsLastGoodResultAcrossFailures() throws Exception {
        AtomicReference<String> body = new AtomicReference<>(ReleaseManifestTest.feed("1.1.0", null));
        AtomicInteger status = new AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.get()
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody()
                .write(bytes);
            exchange.close();
        });
        server.start();
        try (VersionChecker checker = new VersionChecker(
            new URL(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort() + "/"),
            "1.0.0-forge-1.7.10",
            "-forge-1.7.10")) {
            assertEquals(
                "1.1.0",
                checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS).version);
            status.set(503);
            assertThrows(
                ExecutionException.class,
                () -> checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS));
            assertEquals("1.1.0", checker.getAvailableUpdate().version);
            status.set(200);
            body.set("not json");
            assertThrows(
                ExecutionException.class,
                () -> checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS));
            body.set(new String(new char[70_000]).replace('\0', 'x'));
            assertThrows(
                ExecutionException.class,
                () -> checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS));
            body.set(ReleaseManifestTest.feed("1.2.0", null));
            assertEquals(
                "1.2.0",
                checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS).version);
            body.set(ReleaseManifestTest.feed(null, null));
            assertNull(
                checker.checkForUpdates()
                    .get(5, TimeUnit.SECONDS));
            assertNull(checker.getAvailableUpdate());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stoppingDuringRequestReturnsImmediatelyAndCannotPublishLateResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch respond = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody()
                .write(' ');
            exchange.getResponseBody()
                .flush();
            entered.countDown();
            try {
                respond.await(5, TimeUnit.SECONDS);
                byte[] bytes = ReleaseManifestTest.feed("1.1.0", null)
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseBody()
                    .write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        VersionChecker checker = new VersionChecker(
            new URL(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort() + "/"),
            "1.0.0-forge-1.7.10",
            "-forge-1.7.10");
        try {
            CompletableFuture<ReleaseManifest.Release> result = checker.checkForUpdates();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture.runAsync(checker::close)
                .get(1, TimeUnit.SECONDS);
            assertTrue(result.isCancelled());
            respond.countDown();
            // Wait for the old worker to finish before checking for a late publication.
            // This is fixture synchronization, without a production-only test accessor.
            Field executorField = VersionChecker.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            ExecutorService executor = (ExecutorService) executorField.get(checker);
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(checker.getAvailableUpdate());
            assertTrue(
                checker.checkForUpdates()
                    .isCancelled());
        } finally {
            respond.countDown();
            checker.close();
            server.stop(0);
        }
    }

    @Test
    void fetchesOffCallerThreadCoalescesAndCachesCompletedResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch respond = new CountDownLatch(1);
        AtomicReference<String> requestedPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            requestedPath.set(
                exchange.getRequestURI()
                    .getPath());
            entered.countDown();
            try {
                if (!respond.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test response not released");
                byte[] body = ReleaseManifestTest.feed("1.1.0", null)
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody()
                    .write(body);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (VersionChecker checker = new VersionChecker(
            new URL(
                "http://127.0.0.1:" + server.getAddress()
                    .getPort() + "/"),
            "1.0.0-forge-1.7.10",
            "-forge-1.7.10")) {
            CompletableFuture<ReleaseManifest.Release> result = checker.checkForUpdates();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertNull(checker.getAvailableUpdate());
            assertSame(result, checker.checkForUpdates());
            respond.countDown();
            assertEquals("1.1.0", result.get(5, TimeUnit.SECONDS).version);
            assertSame(result.get(), checker.getAvailableUpdate());
            assertEquals("/1.7.10.json", requestedPath.get());
        } finally {
            respond.countDown();
            server.stop(0);
        }
    }
}
