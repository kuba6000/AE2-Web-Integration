package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.ae2request.async.GetTracking;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.Job;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.Order;

/**
 * {@code ISyncedRequest.init} is called at {@code AE2Controller:488} with no try/catch around it, so a
 * parameter that fails to parse must be answered, never thrown.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class RequestParameterValidationTest {

    private static final long GRID = 10L;
    private static final int ME = 42;

    @BeforeEach
    void setUp() {
        GridAccessSessions.clear();
        AE2Controller.AE2Interface = TestGridFixtures.ae();
        Set<Long> keys = new HashSet<>();
        keys.add(GRID);
        GridAccessSessions.put(TestGridFixtures.principal(ME), new GridAccess(ME, keys, System.currentTimeMillis()));
        AE2Controller.itemIdentities.clear();
    }

    private static void assertStatus(String expected, String json) {
        assertTrue(
            json.contains("\"status\":\"" + expected + "\""),
            "expected status " + expected + " but got " + json);
    }

    private static String runSynced(ISyncedRequest request, String query) {
        request.init(TestGridFixtures.context(ME, query));
        return request.getJSON();
    }

    @Test
    void queryValuesAreDecodedOnceWithoutCreatingAdditionalParameters() {
        Map<String, String> params = TestGridFixtures
            .context(ME, "name=cell%26track%3D1&literalPlus=%2B&percent=%2526&space=two+words")
            .getGetParams();

        assertEquals(4, params.size());
        assertEquals("cell&track=1", params.get("name"));
        assertEquals("+", params.get("literalPlus"));
        assertEquals("%26", params.get("percent"));
        assertEquals("two words", params.get("space"));
    }

    // --- Order ---

    @Test
    void orderWithAMalformedItemKeyIsAnsweredNotThrown() {
        assertStatus("BAD_PARAM", runSynced(new Order(), "grid=" + GRID + "&itemKey=abc&quantity=1"));
    }

    @Test
    void orderWithANonNumericQuantityIsAnsweredNotThrown() {
        assertStatus(
            "BAD_PARAM",
            runSynced(new Order(), "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=abc"));
    }

    @Test
    void orderOfZeroIsRejected() {
        assertStatus(
            "INVALID_QUANTITY",
            runSynced(new Order(), "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=0"));
    }

    @Test
    void orderOfANegativeAmountIsRejected() {
        // A negative stack size has its own meaning inside AE2, so it must never reach the planner.
        assertStatus(
            "INVALID_QUANTITY",
            runSynced(new Order(), "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=-5"));
    }

    @Test
    void orderAcceptsAnAmountBeyondIntRange() {
        // Every platform's AE2 takes a long; the old int ceiling was purely our own parser.
        long beyondInt = (long) Integer.MAX_VALUE + 1;
        // Native lookup now runs only after server-thread authorization, not during parameter parsing.
        assertTrue(
            new Order().init(
                TestGridFixtures
                    .context(ME, "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=" + beyondInt)));
    }

    // --- other numeric parameters ---

    @Test
    void aNonNumericGridIsAnsweredNotThrown() {
        assertStatus("BAD_PARAM", runSynced(new Order(), "grid=abc&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=1"));
    }

    @Test
    void jobWithANonNumericIdIsAnsweredNotThrown() {
        assertStatus("BAD_PARAM", runSynced(new Job(), "grid=" + GRID + "&id=abc"));
    }

    @Test
    void getTrackingWithANonNumericIdIsAnsweredNotThrown() {
        GetTracking request = new GetTracking();
        request.handle(TestGridFixtures.context(ME, "grid=" + GRID + "&id=abc"));
        assertStatus("BAD_PARAM", request.getJSON());
    }
}
