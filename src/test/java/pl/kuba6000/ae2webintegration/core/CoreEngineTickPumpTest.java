package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

/**
 * The tick pump is the only place anything HTTP-originated touches live AE2 state, and it runs inside the
 * server tick event. An exception escaping it is not a failed request, it is a stopped server: Forge's
 * EventBus rethrows after notifying its handler, and NeoForge lets it reach MinecraftServer.tickServer.
 */
class CoreEngineTickPumpTest {

    /** Records that it ran, then does whatever the test told it to. Answers OK by default. */
    private static class TestRequest extends ISyncedRequest {

        private final List<String> log;
        private final String name;
        private final Consumer<TestRequest> body;

        TestRequest(List<String> log, String name, Consumer<TestRequest> body) {
            this.log = log;
            this.name = name;
            this.body = body;
        }

        @Override
        public void handle(IAE ae) {
            log.add(name);
            if (body == null) {
                done();
            } else {
                body.accept(this);
            }
        }
    }

    /** Returns each scripted reading in turn, then repeats the last one forever. */
    private static LongSupplier clock(long... readings) {
        int[] index = { 0 };
        return () -> readings[Math.min(index[0]++, readings.length - 1)];
    }

    private List<String> ran;

    @BeforeEach
    void setUp() {
        AE2Controller.requests.clear();
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        ran = new ArrayList<>();
    }

    private TestRequest queue(String name, Consumer<TestRequest> body) {
        TestRequest request = new TestRequest(ran, name, body);
        AE2Controller.requests.offer(request);
        return request;
    }

    private static void assertStatus(String expected, TestRequest request) {
        String json = request.getJSON();
        assertTrue(json.contains("\"status\":\"" + expected + "\""), "expected " + expected + " but got " + json);
    }

    @Test
    void anEmptyQueueIsANoOp() {
        assertDoesNotThrow(() -> CoreEngine.drainRequests(clock(0L)));
        assertTrue(ran.isEmpty());
    }

    @Test
    void aThrowingHandlerDoesNotPropagateOutOfTheTick() {
        queue("boom", r -> { throw new IllegalStateException("handler blew up"); });
        assertDoesNotThrow(() -> CoreEngine.drainRequests(clock(0L)));
        assertEquals(Arrays.asList("boom"), ran);
    }

    @Test
    void anErrorIsCaughtTooNotJustAnException() {
        // A runaway handler ending in StackOverflowError must not take the server with it.
        queue("boom", r -> { throw new StackOverflowError(); });
        assertDoesNotThrow(() -> CoreEngine.drainRequests(clock(0L)));
    }

    @Test
    void aThrowingHandlerLeavesTheRequestAnsweredWithInternalError() {
        // Without this the HTTP worker spins the full 50 x 200 ms in sendRequest and then reports TIMEOUT,
        // which is both slow and the wrong reason.
        TestRequest request = queue("boom", r -> { throw new IllegalStateException(); });
        CoreEngine.drainRequests(clock(0L));
        assertTrue(request.isDone.get(), "the HTTP worker must not be left waiting");
        assertStatus("INTERNAL_ERROR", request);
    }

    @Test
    void aHandlerThatAnsweredBeforeThrowingKeepsItsAnswer() {
        // Its response is already determined and the HTTP thread may already be reading it.
        TestRequest request = queue("late", r -> {
            r.done();
            throw new IllegalStateException("thrown after answering");
        });
        CoreEngine.drainRequests(clock(0L));
        assertStatus("OK", request);
    }

    @Test
    void requestsQueuedBehindAFailingOneStillRun() {
        queue("first", r -> { throw new IllegalStateException(); });
        queue("second", null);
        CoreEngine.drainRequests(clock(0L));
        assertEquals(Arrays.asList("first", "second"), ran);
    }

    @Test
    void theBudgetEndsTheDrainAndTheRemainderRunOnTheNextTick() {
        queue("a", null);
        queue("b", null);
        queue("c", null);
        // The entry reading sets the deadline; the reading taken after the first request is past it.
        CoreEngine.drainRequests(clock(0L, CoreEngine.DRAIN_BUDGET_NANOS + 1));
        assertEquals(Arrays.asList("a"), ran);
        assertEquals(2, AE2Controller.requests.size());

        CoreEngine.drainRequests(clock(0L));
        assertEquals(Arrays.asList("a", "b", "c"), ran);
        assertTrue(AE2Controller.requests.isEmpty());
    }

    @Test
    void oneRequestAlwaysRunsEvenWhenTheBudgetIsAlreadySpent() {
        // Guarantees that a single request costlier than the whole budget can never starve the queue.
        queue("a", null);
        queue("b", null);
        CoreEngine.drainRequests(clock(Long.MAX_VALUE - 1));
        assertEquals(Arrays.asList("a"), ran);
        assertFalse(AE2Controller.requests.isEmpty());
    }
}
