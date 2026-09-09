package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class HttpExecutorSaturationTest {

    @Test
    void excessWorkIsRejectedInsteadOfWaitingForCapacity() throws Exception {
        ExecutorService executor = AE2Controller.createHTTPExecutor();
        ExecutorService submitter = Executors.newSingleThreadExecutor();
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        CountDownLatch workersEntered = new CountDownLatch(32);
        try {
            Runnable blockingRequest = () -> {
                workersEntered.countDown();
                try {
                    releaseWorkers.await();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            };

            // Eight core workers start immediately, 32 requests fill the queue, and the remaining 24
            // submissions grow the pool to its maximum of 32 workers.
            for (int i = 0; i < 64; i++) {
                executor.execute(blockingRequest);
            }
            assertTrue(workersEntered.await(2, TimeUnit.SECONDS));

            Future<?> excessSubmission = submitter.submit(() -> executor.execute(() -> {}));
            ExecutionException rejection = assertThrows(
                ExecutionException.class,
                () -> excessSubmission.get(1, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, rejection.getCause());
        } finally {
            releaseWorkers.countDown();
            executor.shutdownNow();
            submitter.shutdownNow();
        }
    }
}
