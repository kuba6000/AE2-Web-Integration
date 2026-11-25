package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import static java.net.HttpURLConnection.HTTP_OK;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.live.LiveSnapshotRequest;
import pl.kuba6000.ae2webintegration.core.live.LiveView;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderCoordinator;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderRegistry;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderTopic;
import pl.kuba6000.ae2webintegration.core.patterns.PatternProviderView;

/**
 * List pattern providers.
 *
 * <p>
 * Returns the network's providers and their physical pattern slots. Requires the {@code patternProviders}
 * capability. Set {@code live=true} to reserve a stream that continues from this snapshot.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} Complete provider snapshot.
 * @response 400 {@link ErrorResponse} Invalid request parameters or unsupported terminal.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 403 {@link ErrorResponse} Network access denied.
 * @response 404 {@link ErrorResponse} Network not found.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure.
 * @response 503 {@link ErrorResponse} Server unavailable, request timed out or stream capacity reached.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/pattern-providers")
public final class GetPatternProviders extends LiveSnapshotRequest {

    /**
     * @param status       {@code OK} for a successful request
     * @param data         all providers and their physical slots; empty if the network has none
     * @param icons        icon atlas metadata; null when not requested or unavailable
     * @param view         version of this live snapshot; null unless live was requested
     * @param subscription single-use stream token; null unless live was requested
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<PatternProviderView> data,
        @Nullable IconMappings icons, @Nullable LiveView view, @Nullable String subscription)
        implements IResponseBody {}

    /** Include icon references and atlas metadata; defaults to false. */
    @QueryParam("icons")
    boolean icons;

    private @Nullable Acquisition acquired;

    @Desugar
    private record Acquisition(PatternProviderRegistry registry, PatternProviderTopic topic) {}

    @Override
    protected void handle(@Nullable IAEGrid grid) {
        PatternProviderCoordinator providers = CoreEngine.getPatternProviders();
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        if (providers == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        if (!providers.isSupported() || replace != null && replace.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        ApiStatus failure = snapshotAccessFailure();
        if (failure != null) {
            deny(failure);
            return;
        }
        if (context.getExchange()
            .getRequestMethod()
            .equals("HEAD")) {
            done();
            return;
        }
        PatternProviderTopic topic = providers.prepare(gridKey, grid, System.currentTimeMillis());
        if (topic == null) {
            deny(ApiStatus.SERVER_BUSY);
            return;
        }
        acquired = new Acquisition(providers.registry(), topic);
        done();
    }

    @Override
    public ApiResponse finishResponse() {
        ApiResponse initial = getResponse();
        if (initial.httpStatus() != HTTP_OK) return initial;
        ApiStatus failure = snapshotAccessFailure();
        if (failure != null) return ApiResponse.error(failure);
        Acquisition result = acquired;
        if (result == null) return ApiResponse.headOnly();

        // Only a successful dispatch may replace the viewer's current subscription.
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        PatternProviderRegistry.Bootstrap snapshot = result.registry()
            .snapshot(
                result.topic(),
                context.getSession(),
                live,
                icons,
                pack == null ? null : pack.packId(),
                replace,
                System.currentTimeMillis());
        if (snapshot == null) return ApiResponse.error(ApiStatus.SERVER_BUSY);
        String token = snapshot.subscription();
        boolean deliver = false;
        try {
            IconMappings mappings = pack == null ? null : new IconMappings(pack);
            ApiResponse response = ApiResponse.of(
                HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    PatternProviderView.rows(snapshot.providers(), mappings),
                    mappings,
                    live ? snapshot.view() : null,
                    token));
            failure = snapshotAccessFailure();
            if (failure != null) return ApiResponse.error(failure);
            deliver = true;
            return response;
        } finally {
            if (!deliver && token != null) cancel(token);
        }
    }

    @Override
    protected void cancel(String token) {
        Acquisition result = acquired;
        if (result != null) result.registry()
            .cancel(token);
    }
}
