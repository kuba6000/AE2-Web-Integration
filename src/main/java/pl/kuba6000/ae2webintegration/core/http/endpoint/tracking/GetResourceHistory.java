package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.TrackedResource;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistory;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/**
 * Reads sampled stock and completed input/output intervals. Missing observations are not zero activity.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 {@link Response} Bounded history query result.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid key, timestamp or limit outside 1-10000.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: this grid is not accessible.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid is not loaded.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: request body exceeds the limit.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: rate limit exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: history could not be read.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: history service is unavailable.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_STOPPING","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/resource-history")
public final class GetResourceHistory extends IAsyncRequest {

    private static final int DEFAULT_QUERY_LIMIT = 200;
    private static final int MAX_QUERY_LIMIT = 10000;

    /** Optional exact resource key. Omit to read all resources. */
    @QueryParam("itemKey")
    private @Nullable StableKey itemKey;
    /** Earliest observation time in Unix epoch milliseconds, inclusive; defaults to zero. */
    @QueryParam("from")
    private long from;
    /** Maximum points returned, from 1 through 10000; defaults to 200. */
    @QueryParam("limit")
    private int limit = DEFAULT_QUERY_LIMIT;

    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull History data) {}

    /**
     * Baseline is the last retained stock measurement preceding the returned window, or null when unknown.
     * It keeps its original timestamp and is not counted against limit.
     */
    @Desugar
    public record History(@NotNull List<Point> points, boolean complete, boolean truncated, boolean enabled,
        @Nullable Point baseline) {}

    /** Decimal strings preserve exact 64-bit counts and generation identifiers in browser clients. */
    @Desugar
    public record Point(long observedAt, @NotNull String generation, @NotNull StorageHistoryPoint.Kind kind,
        @Nullable TrackedResource resource, boolean craftable, @NotNull String input, @NotNull String output,
        boolean complete) {}

    @Override
    public void handle() {
        if (from < 0 || limit < 1 || limit > MAX_QUERY_LIMIT) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        StorageTrackingService service = CoreEngine.getStorageTracking();
        if (service == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        try {
            StorageHistory history = service.history(gridKey, itemKey, from, limit);
            List<Point> points = new ArrayList<>(
                history.points()
                    .size());
            for (StorageHistoryPoint point : history.points()) points.add(toPoint(point, history));
            GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(gridKey);
            respondDetached(
                HttpURLConnection.HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    new History(
                        points,
                        history.complete(),
                        history.truncated(),
                        data != null && (data.getSettings()
                            .isStorageTracked()
                            || data.getSettings()
                                .isFlowTracked()),
                        history.baseline() == null ? null : toPoint(history.baseline(), history))));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Point toPoint(StorageHistoryPoint point, StorageHistory history) {
        return new Point(
            point.observedAt(),
            Long.toString(point.generation()),
            point.kind(),
            point.itemKey() == null ? null
                : new TrackedResource(
                    history.descriptions()
                        .get(point.itemKey()),
                    point.quantity()),
            point.craftable(),
            Long.toString(point.input()),
            Long.toString(point.output()),
            point.complete());
    }

}
