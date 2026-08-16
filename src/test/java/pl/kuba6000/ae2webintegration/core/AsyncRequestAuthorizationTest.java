package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;

/**
 * Regression guard for the missing authorization on the async endpoints (C-01).
 * <p>
 * Before the fix, {@code IAsyncRequest} resolved {@code GridData} straight from the {@code grid} query
 * parameter, so any authenticated user could read or modify any network's tracking data.
 */
class AsyncRequestAuthorizationTest {

    private static final long MY_GRID = 10L;
    private static final long OTHER_GRID = 20L;
    private static final WebPrincipal ME = TestGridFixtures.principal(42);
    private static final WebPrincipal ADMIN = WebPrincipal.admin();
    private static final WebPrincipal LOCALHOST = WebPrincipal.localhost();

    /** Minimal concrete handler that records whether it was allowed to run. */
    private static class ProbeRequest extends IAsyncRequest {

        boolean handlerRan = false;

        @Override
        public void handle(Map<String, String> getParams) {
            handlerRan = true;
            done();
        }
    }

    private static void grantAccess(WebPrincipal principal, long... keys) {
        Set<Long> set = new HashSet<>();
        for (long key : keys) {
            set.add(key);
        }
        GridAccessSessions
            .put(principal, new GridAccess(GridAccess.UNRESOLVED_PLAYER_ID, set, System.currentTimeMillis()));
    }

    private static ProbeRequest run(WebPrincipal principal, String query) {
        ProbeRequest request = new ProbeRequest();
        request.handle(TestGridFixtures.context(principal, query));
        return request;
    }

    private static void assertStatus(String expected, ProbeRequest request) {
        assertTrue(
            request.getJSON()
                .contains("\"status\":\"" + expected + "\""),
            "expected status " + expected + " but got " + request.getJSON());
    }

    @BeforeEach
    void setUp() {
        GridAccessSessions.clear();
        AE2Controller.AE2Interface = TestGridFixtures.ae();
    }

    @Test
    void unauthorizedUserCannotReachAnotherGrid() {
        grantAccess(ME, MY_GRID);

        ProbeRequest request = run(ME, "grid=" + OTHER_GRID);

        assertStatus("NO_PERMISSIONS", request);
        assertFalse(request.handlerRan, "handler must not run for an unauthorized grid");
    }

    @Test
    void authorizedUserReachesTheirOwnGrid() {
        grantAccess(ME, MY_GRID);

        ProbeRequest request = run(ME, "grid=" + MY_GRID);

        assertStatus("OK", request);
        assertTrue(request.handlerRan);
    }

    @Test
    void missingSessionAsksClientToRefreshRatherThanReportingPermissionError() {
        // Fail-closed: nothing cached yet for this user.
        ProbeRequest request = run(ME, "grid=" + MY_GRID);

        assertStatus("REFRESH_REQUIRED", request);
        assertFalse(request.handlerRan);
    }

    @Test
    void expiredSessionAsksClientToRefresh() {
        GridAccessSessions.put(
            ME,
            new GridAccess(
                42,
                new HashSet<>(Collections.singletonList(MY_GRID)),
                System.currentTimeMillis() - GridAccess.TTL_MILLIS - 1));

        ProbeRequest request = run(ME, "grid=" + MY_GRID);

        assertStatus("REFRESH_REQUIRED", request);
        assertFalse(request.handlerRan);
    }

    @Test
    void anAdminIsCheckedTooSoAPhantomKeyIsRejected() {
        // Admins are not permission-restricted, but their set still only holds grids that exist, so a
        // made-up key cannot reach GridData and be written to griddata.json.
        grantAccess(ADMIN, MY_GRID);

        assertStatus("NO_PERMISSIONS", run(ADMIN, "grid=" + OTHER_GRID));
        assertStatus("OK", run(ADMIN, "grid=" + MY_GRID));
    }

    @Test
    void localhostIsCheckedTheSameWay() {
        grantAccess(LOCALHOST, MY_GRID);

        assertStatus("NO_PERMISSIONS", run(LOCALHOST, "grid=" + OTHER_GRID));
        assertStatus("OK", run(LOCALHOST, "grid=" + MY_GRID));
    }

    @Test
    void unauthorizedRequestDoesNotCreateGridData() {
        grantAccess(ME, MY_GRID);
        long inventedKey = 123456789L;

        run(ME, "grid=" + inventedKey);

        assertNull(GridData.find(inventedKey), "an unauthorized key must not fabricate a GridData entry");
    }

    @Test
    void nonNumericGridParameterIsRejectedInsteadOfThrowing() {
        grantAccess(ME, MY_GRID);

        ProbeRequest request = run(ME, "grid=notanumber");

        assertStatus("BAD_PARAM", request);
        assertFalse(request.handlerRan);
    }

    @Test
    void missingGridParameterStillRunsTheHandler() {
        // Endpoints that do not target a grid keep working; the handler decides what to do.
        ProbeRequest request = run(ME, "");

        assertTrue(request.handlerRan);
    }
}
