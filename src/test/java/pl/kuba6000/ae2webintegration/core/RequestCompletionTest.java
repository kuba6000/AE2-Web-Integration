package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class RequestCompletionTest {

    @ParameterizedTest
    @CsvSource({ "GRID_NOT_FOUND,GRID_NOT_FOUND,404", "INVALID_USER,invaliduser,401",
        "INVALID_PASSWORD,invalidpassword,401", "NOT_ONLINE,notonline,409" })
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
