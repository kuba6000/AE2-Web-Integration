package pl.kuba6000.ae2webintegration.core.http.endpoint.grid;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.stream.TerminalStreams;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

/**
 * Resumes terminal changes from a live item snapshot's generation:revision cursor.
 * The Last-Event-ID header takes precedence over cursor on reconnect. Cursors are scoped to this grid.
 * Delta events upsert exact item keys, remove listed keys, and replace the entire unkeyed array.
 * Each delta carries its own icon page table. Reset, gap and invalidate require a new live snapshot;
 * revoked closes the stream after access is lost. Heartbeat comments do not advance the cursor.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 Server-sent ready, delta, reset, gap, invalidate and revoked events.
 * @responseMedia 200 text/event-stream
 * @responseHeader 200 Cache-Control No-store: event streams must not be cached.
 * @response 400 {@link ErrorResponse} Invalid cursor or request input.
 * @response 401 {@link ErrorResponse} Authentication required.
 * @response 403 {@link ErrorResponse} Grid permission denied.
 * @response 404 {@link ErrorResponse} Grid not found.
 * @response 405 {@link ErrorResponse} Method not allowed.
 * @response 413 {@link ErrorResponse} Request body too large.
 * @response 429 {@link ErrorResponse} Request rate limit exceeded.
 * @response 500 {@link ErrorResponse} Unexpected failure before streaming begins.
 * @response 503 {@link ErrorResponse} Stream capacity reached or server unavailable.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/items/events")
public final class GetItemEvents extends ISyncedRequest {

    /** Decimal generation:revision returned by GET items?live=true; Last-Event-ID overrides it. */
    @QueryParam("cursor")
    private @Nullable String cursor;
    /** Include an icon page table with each delta. */
    @QueryParam("icons")
    private boolean icons;
    private @Nullable TerminalStreams streams;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        String last = context.getExchange()
            .getRequestHeaders()
            .getFirst("Last-Event-ID");
        if (last != null) cursor = last;
        if (!TerminalStreams.validCursor(cursor)) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        streams = CoreEngine.getTerminalStreams();
        if (streams == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        done();
    }

    /** Transfers exchange ownership after the normal request's permission check and completion. */
    public boolean startStream() throws IOException {
        if (getResponse().httpStatus() != HttpURLConnection.HTTP_OK || streams == null || cursor == null) return false;
        if (!context.isLifecycleUnchanged()) {
            ApiResponse.error(ApiStatus.SERVER_STOPPING)
                .send(context.getExchange());
            return true;
        }
        if (context.getExchange()
            .getRequestMethod()
            .equals("HEAD")) {
            context.getExchange()
                .getResponseHeaders()
                .set("Content-Type", "text/event-stream; charset=UTF-8");
            context.getExchange()
                .sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
            context.getExchange()
                .close();
        } else streams.open(context, gridKey, cursor, icons);
        return true;
    }
}
