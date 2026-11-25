package pl.kuba6000.ae2webintegration.core.http.endpoint.tracking;

import static java.net.HttpURLConnection.HTTP_OK;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.Activity;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.ResourceFilter;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.ResourceSort;
import pl.kuba6000.ae2webintegration.core.tracking.storage.IStorageHistoryStore.Stock;
import pl.kuba6000.ae2webintegration.core.tracking.storage.StorageTrackingService;

/**
 * Lists resources with retained storage history for this network.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Retained resources page.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request parameters.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: request body exceeds the limit.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: request rate limit exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_STOPPING: history unavailable.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/tracking/resources")
public final class GetTrackingResources extends IAsyncRequest {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_SEARCH = 256;
    private static final int QUARTER_HOUR = 15;
    private static final int HOUR = 60;
    private static final int DAY = 1440;

    /** Page size, from 1 to 100. */
    @QueryParam("limit")
    int limit = DEFAULT_LIMIT;

    /** Exclusive cursor returned by the previous page. */
    @QueryParam("after")
    String after;

    /** ASCII case-insensitive display name or registry identifier substring. */
    @QueryParam("search")
    String search;

    /**
     * Resource type (ITEM, FLUID or OTHER); omit for all types. Required for unit-based STOCK/INPUT/OUTPUT rankings.
     */
    @QueryParam("type")
    String type;

    /**
     * NAME (default), NAME_DESC, RECENT, STOCK, INPUT, OUTPUT, INSERTS or EXTRACTS. Activity rankings are highest
     * first.
     */
    @QueryParam("sort")
    String sort = "NAME";

    /** Activity window duration: 15, 60 (default) or 1440 minutes. */
    @QueryParam("minutes")
    int minutes = HOUR;

    /** Inclusive upper observation bound in Unix epoch milliseconds. Defaults to now; reuse windowEnd when paging. */
    @QueryParam("until")
    long until = System.currentTimeMillis();

    /** Include resource icons and atlas metadata; defaults to false. */
    @QueryParam("icons")
    boolean icons;

    /** Catalog descriptor, including its last known base variant for existing clients. */
    public static final class Resource extends ResourceDescription {

        /** Last known base variant, if retained. */
        public final @Nullable StableKey baseKey;

        Resource(ResourceDescriptor descriptor, @Nullable IconMappings mappings) {
            super(descriptor, mappings);
            baseKey = descriptor.baseKey();
        }
    }

    /**
     * @param status         request status
     * @param data           retained resource descriptions
     * @param nextAfter      exclusive cursor for the next page, or null
     * @param lastObservedAt last stock or flow observation for each resource, in Unix epoch milliseconds
     * @param activity       recorded totals by key, including whole flow intervals ending inside the window (exclusive
     *                       start,
     *                       inclusive end). Absent for resources with no recorded transfers. Dividing by minutes gives
     *                       a rate over wall time,
     *                       including gaps, not an estimate of unobserved traffic
     * @param stock          latest retained stock measurement by key at or before windowEnd; absent when never measured
     * @param windowEnd      inclusive upper bound used for this page, in Unix epoch milliseconds
     * @param icons          optional atlas metadata
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<Resource> data, @Nullable String nextAfter,
        @NotNull Map<String, Long> lastObservedAt, @NotNull Map<String, Activity> activity,
        @NotNull Map<String, Stock> stock, long windowEnd, @Nullable IconMappings icons) implements IResponseBody {}

    private @Nullable Long cursor() {
        if (after == null) return null;
        long parsed = Long.parseLong(after);
        if (parsed < 1 || !Long.toString(parsed)
            .equals(after)) throw new IllegalArgumentException("Invalid cursor");
        return parsed;
    }

    private ResourceFilter filter() {
        if (limit < 1 || limit > MAX_LIMIT || search != null && search.length() > MAX_SEARCH)
            throw new IllegalArgumentException("Invalid page size or search");
        if (until < 0 || minutes != QUARTER_HOUR && minutes != HOUR && minutes != DAY)
            throw new IllegalArgumentException("Invalid activity window");
        ResourceType category = type == null ? null : ResourceType.valueOf(type);
        ResourceSort order = ResourceSort.valueOf(sort);
        if (category == null && order.requiresType())
            throw new IllegalArgumentException("Quantity ranking requires a type");
        return new ResourceFilter(search, category, order, until - TimeUnit.MINUTES.toMillis(minutes), until);
    }

    @Override
    public void handle() {
        Long cursor;
        ResourceFilter filter;
        try {
            cursor = cursor();
            filter = filter();
        } catch (IllegalArgumentException invalid) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        StorageTrackingService service = CoreEngine.getStorageTracking();
        if (service == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        try {
            IStorageHistoryStore.ResourcePage page = service.resources(gridKey, cursor, filter, limit);
            IconPack pack = icons ? CoreEngine.getIconPack() : null;
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            List<Resource> entries = new ArrayList<>(
                page.entries()
                    .size());
            for (ResourceDescriptor resource : page.entries()) entries.add(new Resource(resource, mappings));
            respondDetached(
                HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    entries,
                    page.nextAfter() == null ? null : Long.toString(page.nextAfter()),
                    page.lastObservedAt(),
                    page.activity(),
                    page.stock(),
                    until,
                    mappings));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
