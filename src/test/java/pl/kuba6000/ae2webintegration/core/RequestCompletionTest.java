package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class RequestCompletionTest {

    private static final class TestRequest extends ISyncedRequest {

        @Override
        public void handle(IAE ae) {}
    }

    @Test
    void infrastructureFailureCannotBeOverwrittenByALateHandlerCompletion() {
        TestRequest request = new TestRequest();

        request.failIfPending("SERVER_BUSY");
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
