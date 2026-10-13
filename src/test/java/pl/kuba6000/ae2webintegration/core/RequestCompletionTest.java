package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class RequestCompletionTest {

    @ParameterizedTest
    @CsvSource({ "GRID_NOT_FOUND,GRID_NOT_FOUND,404", "INVALID_USER,INVALID_USER,401",
        "INVALID_PASSWORD,INVALID_PASSWORD,401", "NOT_ONLINE,NOT_ONLINE,409" })
    void typedFailuresPreserveTheirWireCodesAndHttpStatuses(ApiStatus status, String expectedCode, int expectedHttp) {
        TestRequest request = new TestRequest();
        request.deny(status);
        assertEquals(
            expectedHttp,
            request.getResponse()
                .httpStatus());
        JsonObject response = new Gson().fromJson(request.getJSON(), JsonObject.class);
        assertEquals(
            expectedCode,
            response.get("status")
                .getAsString());
        assertTrue(
            response.get("data")
                .isJsonNull());
    }

    private static final class TestRequest extends ISyncedRequest {

        @Override
        public void handle() {}

        void reject(Object data) {
            deny(ApiStatus.BAD_PARAM, data);
        }

        void respond(Object data) {
            succeed(data);
        }

        void respondDetachedBody(Object body) {
            respondDetached(200, body);
        }

    }

    @Test
    void detachedResponseIsSerializedByItsConsumerOnlyOnce() throws Exception {
        TestRequest request = new TestRequest();
        ConsumerOwnedData body = new ConsumerOwnedData();
        request.respondDetachedBody(body);
        request.awaitCompletion(1, TimeUnit.SECONDS);
        assertEquals(0, body.reads.get());
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = consumer.submit(request::getJSON);
            assertEquals("[\"Żelazo\"]", result.get(1, TimeUnit.SECONDS));
            assertEquals("[\"Żelazo\"]", request.getJSON());
            assertEquals(1, body.reads.get());
        } finally {
            consumer.shutdownNow();
        }
    }

    private static final class ConsumerOwnedData extends AbstractCollection<String> {

        private final Thread producer = Thread.currentThread();
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public @NotNull Iterator<String> iterator() {
            assertNotSame(producer, Thread.currentThread(), "Serialization must not run on the game thread");
            reads.incrementAndGet();
            return Collections.singletonList("Żelazo")
                .iterator();
        }

        @Override
        public int size() {
            return 1;
        }
    }

    @Test
    void lifecycleChangeDiscardsADetachedPayloadBeforeSerialization() {
        TestRequest request = new TestRequest();
        assertTrue(request.init(TestGridFixtures.context(-1, "")));
        request.respondDetachedBody(new UnserializableData());
        AE2Controller.stopHTTPServer();
        assertEquals(
            503,
            request.getResponse()
                .httpStatus());
        assertEquals(
            "SERVER_STOPPING",
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void lifecycleChangeDuringSerializationDiscardsTheOldSnapshot() throws Exception {
        TestRequest request = new TestRequest();
        assertTrue(request.init(TestGridFixtures.context(-1, "")));
        BlockingData body = new BlockingData();
        request.respondDetachedBody(body);
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        try {
            Future<ApiResponse> result = consumer.submit(request::getResponse);
            assertTrue(body.entered.await(1, TimeUnit.SECONDS));
            AE2Controller.stopHTTPServer();
            body.release.countDown();
            assertEquals(
                503,
                result.get(1, TimeUnit.SECONDS)
                    .httpStatus());
            assertEquals(
                "SERVER_STOPPING",
                new Gson().fromJson(request.getJSON(), JsonObject.class)
                    .get("status")
                    .getAsString());
        } finally {
            body.release.countDown();
            consumer.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({ "TIMEOUT", "SERVER_STOPPING", "SERVER_BUSY" })
    void infrastructureFailureRejectsALateDetachedPayload(ApiStatus status) {
        TestRequest request = new TestRequest();
        request.failIfPending(status);
        request.respondDetachedBody(new UnserializableData());
        assertEquals(
            status.name(),
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void detachedCompletionWinsAgainstALaterFailure() {
        TestRequest request = new TestRequest();
        request.respondDetachedBody(Collections.singletonList("ready"));
        request.failIfPending(ApiStatus.TIMEOUT);
        assertEquals("[\"ready\"]", request.getJSON());
    }

    @Test
    void concurrentConsumersReceiveTheSameCompleteDetachedResponse() throws Exception {
        TestRequest request = new TestRequest();
        BlockingData body = new BlockingData();
        request.respondDetachedBody(body);
        ExecutorService consumers = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResponse> first = consumers.submit(request::getResponse);
            assertTrue(body.entered.await(1, TimeUnit.SECONDS));
            Future<ApiResponse> second = consumers.submit(request::getResponse);
            body.release.countDown();
            assertEquals(
                "[\"old world\"]",
                first.get(1, TimeUnit.SECONDS)
                    .json());
            assertEquals(first.get(), second.get(1, TimeUnit.SECONDS));
            assertEquals(1, body.reads.get());
        } finally {
            body.release.countDown();
            consumers.shutdownNow();
        }
    }

    private static final class BlockingData extends AbstractCollection<String> {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public @NotNull Iterator<String> iterator() {
            reads.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread()
                    .interrupt();
                throw new IllegalStateException(exception);
            }
            return Collections.singletonList("old world")
                .iterator();
        }

        @Override
        public int size() {
            return 1;
        }
    }

    @Test
    void successCapturesThePayloadBeforeReturningToItsCaller() {
        TestRequest request = new TestRequest();
        List<String> data = new ArrayList<>(Collections.singletonList("before"));
        request.respond(data);
        data.clear();
        JsonObject response = new Gson().fromJson(request.getJSON(), JsonObject.class);
        assertEquals(
            "before",
            response.getAsJsonArray("data")
                .get(0)
                .getAsString());
    }

    private static final class UnserializableData extends AbstractCollection<String> {

        @Override
        public @NotNull Iterator<String> iterator() {
            throw new IllegalStateException("Payload cannot be read");
        }

        @Override
        public int size() {
            return 1;
        }
    }

    @Test
    void serializationFailureLeavesTheRequestAvailableForAnErrorResponse() {
        TestRequest request = new TestRequest();
        assertThrows(IllegalStateException.class, () -> request.respond(new UnserializableData()));
        request.failIfPending(ApiStatus.INTERNAL_ERROR);
        assertEquals(
            "INTERNAL_ERROR",
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void completedRequestDoesNotReadALatePayload() {
        TestRequest request = new TestRequest();
        request.failIfPending(ApiStatus.SERVER_BUSY);
        assertDoesNotThrow(() -> request.respond(new UnserializableData()));
        assertEquals(
            "SERVER_BUSY",
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void readingAPendingResponseDoesNotPreventLaterCompletion() {
        TestRequest request = new TestRequest();
        assertEquals(
            "TIMEOUT",
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
        request.done();
        assertEquals(
            "OK",
            new Gson().fromJson(request.getJSON(), JsonObject.class)
                .get("status")
                .getAsString());
    }

    @Test
    void denialCapturesItsPayloadBeforeReturning() {
        TestRequest request = new TestRequest();
        List<String> data = new ArrayList<>(Collections.singletonList("invalid"));
        request.reject(data);
        data.clear();
        JsonObject response = new Gson().fromJson(request.getJSON(), JsonObject.class);
        assertEquals(
            "BAD_PARAM",
            response.get("status")
                .getAsString());
        assertEquals(
            "invalid",
            response.getAsJsonArray("data")
                .get(0)
                .getAsString());
    }

    @Test
    void infrastructureFailureCannotBeOverwrittenByALateHandlerCompletion() {
        TestRequest request = new TestRequest();

        request.failIfPending(ApiStatus.SERVER_BUSY);
        request.done();

        JsonObject response = new Gson().fromJson(request.getJSON(), JsonObject.class);
        assertEquals(
            "SERVER_BUSY",
            response.get("status")
                .getAsString());
    }

    @Test
    void completionReleasesAWaitingHttpWorker() throws Exception {
        TestRequest request = new TestRequest();
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> {
                entered.countDown();
                try {
                    request.awaitCompletion(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(waiter.isDone());
            request.done();

            waiter.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
