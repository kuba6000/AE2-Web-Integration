package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.CreateCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetTracking;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

/**
 * {@code ISyncedRequest.init} is called at {@code AE2Controller:488} with no try/catch around it, so a
 * parameter that fails to parse must be answered, never thrown.
 */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class RequestParameterValidationTest extends GridTestScope {

    private StableKey GRID;
    private static final int ME = 42;

    @BeforeEach
    void setUp() {
        TestGridFixtures.TestGrid liveGrid = TestGridFixtures.grid(10L, ME);
        AE2Controller.AE2Interface = TestGridFixtures.ae(liveGrid);
        GRID = CoreEngine.GRID_IDENTITIES.getKey(liveGrid);
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
        Map<String, String> params = HTTPUtils
            .parseQueryString("name=cell%26track%3D1&literalPlus=%2B&percent=%2526&space=two+words");

        assertEquals(4, params.size());
        assertEquals("cell&track=1", params.get("name"));
        assertEquals("+", params.get("literalPlus"));
        assertEquals("%26", params.get("percent"));
        assertEquals("two words", params.get("space"));
    }

    @Test
    void craftingInputUsesTypedJsonAndRejectsNullOrUnknownMembers() {
        for (String body : new String[] { "{\"itemKey\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"quantity\":\"1\"}",
            "{\"itemKey\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"quantity\":null}",
            "{\"itemKey\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"quantity\":1,\"extra\":true}" }) {
            CreateCraftingPlan request = new CreateCraftingPlan();
            request.init(
                new AE2Controller.RequestContext(
                    new TestGridFixtures.TestExchange(""),
                    TestGridFixtures.principal(ME),
                    Collections.singletonMap("gridKey", GRID.toString()),
                    JsonParser.parseString(body)
                        .getAsJsonObject()));
            assertStatus("BAD_PARAM", request.getJSON());
            assertEquals(
                HttpURLConnection.HTTP_BAD_REQUEST,
                request.getResponse()
                    .httpStatus());
        }
    }

    // --- CreateCraftingPlan ---

    @Test
    void orderWithAMalformedItemKeyIsAnsweredNotThrown() {
        assertStatus("BAD_PARAM", runSynced(new CreateCraftingPlan(), "grid=" + GRID + "&itemKey=abc&quantity=1"));
    }

    @Test
    void orderWithANonNumericQuantityIsAnsweredNotThrown() {
        assertStatus(
            "BAD_PARAM",
            runSynced(new CreateCraftingPlan(), "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=abc"));
    }

    @Test
    void orderOfZeroIsRejected() {
        assertStatus(
            "INVALID_QUANTITY",
            runSynced(new CreateCraftingPlan(), "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=0"));
    }

    @Test
    void orderOfANegativeAmountIsRejected() {
        // A negative stack size has its own meaning inside AE2, so it must never reach the planner.
        assertStatus(
            "INVALID_QUANTITY",
            runSynced(new CreateCraftingPlan(), "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=-5"));
    }

    @Test
    void orderAcceptsAnAmountBeyondIntRange() {
        // Every platform's AE2 takes a long; the old int ceiling was purely our own parser.
        long beyondInt = (long) Integer.MAX_VALUE + 1;
        // Native lookup now runs only after server-thread authorization, not during parameter parsing.
        assertTrue(
            new CreateCraftingPlan().init(
                TestGridFixtures
                    .context(ME, "grid=" + GRID + "&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=" + beyondInt)));
    }

    // --- other numeric parameters ---

    @Test
    void aNonNumericGridIsAnsweredNotThrown() {
        assertStatus(
            "BAD_PARAM",
            runSynced(new CreateCraftingPlan(), "grid=abc&itemKey=AAAAAAAAAAAAAAAAAAAAAA&quantity=1"));
    }

    @Test
    void jobWithANonNumericIdIsAnsweredNotThrown() {
        assertStatus("BAD_PARAM", runSynced(new GetCraftingPlan(), "grid=" + GRID + "&id=abc"));
    }

    @Test
    void getTrackingWithANonNumericIdIsAnsweredNotThrown() {
        GetTracking request = new GetTracking();
        request.handle(TestGridFixtures.context(ME, "grid=" + GRID + "&id=abc"));
        assertStatus("BAD_PARAM", request.getJSON());
    }
}
