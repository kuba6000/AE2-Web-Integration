package pl.kuba6000.ae2webintegration.core;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;

import java.util.Collections;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.CreateCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.crafting.GetCraftingPlan;
import pl.kuba6000.ae2webintegration.core.http.endpoint.tracking.GetTracking;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

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
        GRID = GRID_IDENTITIES.getKey(liveGrid);
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
                HTTP_BAD_REQUEST,
                request.getResponse()
                    .httpStatus());
        }
    }

    // --- parameters that cannot be parsed or are out of range ---

    private static Stream<Arguments> unparseableOrInvalidParameters() {
        Supplier<ISyncedRequest> order = CreateCraftingPlan::new;
        Supplier<ISyncedRequest> job = GetCraftingPlan::new;
        String key = "itemKey=AAAAAAAAAAAAAAAAAAAAAA";
        return Stream.of(
            Arguments.of("order with a malformed item key", order, "grid=<grid>&itemKey=abc&quantity=1", "BAD_PARAM"),
            Arguments
                .of("order with a non-numeric quantity", order, "grid=<grid>&" + key + "&quantity=abc", "BAD_PARAM"),
            Arguments.of("order of zero", order, "grid=<grid>&" + key + "&quantity=0", "INVALID_QUANTITY"),
            // A negative stack size has its own meaning inside AE2, so it must never reach the planner.
            Arguments
                .of("order of a negative amount", order, "grid=<grid>&" + key + "&quantity=-5", "INVALID_QUANTITY"),
            Arguments.of("order with a non-numeric grid", order, "grid=abc&" + key + "&quantity=1", "BAD_PARAM"),
            Arguments.of("job with a non-numeric id", job, "grid=<grid>&id=abc", "BAD_PARAM"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unparseableOrInvalidParameters")
    void aBadParameterIsAnsweredNotThrown(String description, Supplier<ISyncedRequest> request, String query,
        String expectedStatus) {
        assertStatus(expectedStatus, runSynced(request.get(), query.replace("<grid>", GRID.toString())));
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

    // --- a handler that parses its own parameters ---

    @Test
    void getTrackingWithANonNumericIdIsAnsweredNotThrown() {
        GetTracking request = new GetTracking();
        request.handle(TestGridFixtures.context(ME, "grid=" + GRID + "&id=abc"));
        assertStatus("BAD_PARAM", request.getJSON());
    }
}
