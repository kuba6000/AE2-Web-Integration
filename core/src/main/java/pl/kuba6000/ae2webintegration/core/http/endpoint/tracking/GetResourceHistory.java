package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.TrackedResource;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistory;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageHistoryPoint;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/**
 * Get resource history.
 *
 * <p>
 * Returns stock measurements and input/output totals, with the newest matching points ordered oldest first.
 * {@code GAP} marks missing coverage; partial counts are retained.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Measurements and coverage information.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid key, timestamp or limit outside 1-10000.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found or unavailable.
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

    /** Filter by resource key; omit to include all resources. */
    @QueryParam("itemKey")
    @Nullable
    StableKey itemKey;
    /** Earliest observation time in Unix epoch milliseconds, inclusive; defaults to zero. */
    @QueryParam("from")
    long from;
    /**
     * Maximum number of newest matching points, from 1 to 10000; defaults to 200. One extra boundary GAP may be
     * returned.
     */
    @QueryParam("limit")
    int limit = DEFAULT_QUERY_LIMIT;

    /** Include resource icons and atlas metadata; defaults to false. */
    @QueryParam("icons")
    boolean icons;

    /**
     * Resource history result.
     *
     * @param status {@code OK} for a successful request
     * @param data   measurements and coverage information
     * @param icons  icon atlas metadata; null when not requested or unavailable
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull History data, @Nullable IconMappings icons)
        implements IResponseBody {}

    /**
     * Measurements and coverage for the requested period.
     *
     * @param points    newest matching points, ordered oldest first; may include one extra boundary GAP beyond limit
     * @param complete  false when the requested period includes a known gap or expired history
     * @param truncated whether the result omits points because of the limit or expired history
     * @param enabled   whether stock or flow tracking is currently enabled for the network
     * @param baseline  last known stock before the returned window for the requested resource, at its original time;
     *                  null when unavailable or itemKey was omitted. Not counted against limit
     */
    @Desugar
    public record History(@NotNull List<Point> points, boolean complete, boolean truncated, boolean enabled,
        @Nullable Point baseline) {}

    /**
     * A measurement or coverage marker. Counts use decimal strings to preserve exact values.
     *
     * @param observedAt       observation time in Unix epoch milliseconds
     * @param generation       tracking session identifier; a change marks a break in continuity
     * @param kind             STOCK or FLOW for measurements; OBSERVATION and FLOW_OBSERVATION mark coverage,
     *                         and GAP marks missing data
     * @param resource         measured resource; null for coverage markers. Its quantity is only meaningful for STOCK
     * @param input            resource units inserted during the flow interval; zero for other kinds
     * @param output           resource units extracted during the flow interval; zero for other kinds
     * @param inputOperations  number of accepted insertions during the flow interval; zero for other kinds
     * @param outputOperations number of accepted extractions during the flow interval; zero for other kinds
     * @param flowFrom         flow interval start in Unix epoch milliseconds, ending at observedAt; null when
     *                         unavailable
     */
    @Desugar
    public record Point(long observedAt, @NotNull String generation, @NotNull StorageHistoryPoint.Kind kind,
        @Nullable TrackedResource resource, @NotNull String input, @NotNull String output,
        @NotNull String inputOperations, @NotNull String outputOperations, @Nullable Long flowFrom) {}

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
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            Map<StableKey, IconMappings.Reference> references = iconReferences(history, mappings);
            List<Point> points = new ArrayList<>(
                history.points()
                    .size());
            for (StorageHistoryPoint point : history.points()) points.add(toPoint(point, history, references));
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
                        history.baseline() == null ? null : toPoint(history.baseline(), history, references)),
                    mappings));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Map<StableKey, IconMappings.Reference> iconReferences(StorageHistory history,
        @Nullable IconMappings mappings) {
        if (mappings == null) return Collections.emptyMap();
        Map<StableKey, IconMappings.Reference> references = new HashMap<>();
        for (Map.Entry<StableKey, ResourceDescriptor> entry : history.descriptions()
            .entrySet()) {
            references.put(
                entry.getKey(),
                mappings.resolve(
                    entry.getKey(),
                    entry.getValue()
                        .baseKey()));
        }
        return references;
    }

    private static Point toPoint(StorageHistoryPoint point, StorageHistory history,
        Map<StableKey, IconMappings.Reference> references) {
        return new Point(
            point.observedAt(),
            Long.toString(point.generation()),
            point.kind(),
            point.itemKey() == null ? null
                : new TrackedResource(
                    history.descriptions()
                        .get(point.itemKey()),
                    point.quantity(),
                    references.get(point.itemKey())),
            Long.toString(point.input()),
            Long.toString(point.output()),
            Long.toString(point.inputOperations()),
            Long.toString(point.outputOperations()),
            point.flowFrom());
    }

}
