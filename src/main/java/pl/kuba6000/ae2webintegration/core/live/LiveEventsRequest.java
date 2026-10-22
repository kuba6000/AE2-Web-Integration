package pl.kuba6000.ae2webintegration.core.live;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;

/** Opens the event stream of a subscription reserved with a live snapshot; subclasses name their topic. */
public abstract class LiveEventsRequest extends IAsyncRequest {

    /** Single-use token returned with the initial snapshot. Bound to the login session, view and icon mode. */
    @QueryParam("subscription")
    @Nullable
    String subscription;
    /** Include icon references when new or changed resource definitions are sent. */
    @QueryParam("icons")
    boolean icons;
    private @Nullable LiveStreams streams;

    protected abstract @NotNull LiveTopic.Key topicKey();

    @Override
    public final void handle() {
        if (gridKey == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        if (subscription == null || subscription.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        streams = CoreEngine.getLiveStreams();
        if (streams == null) {
            deny(ApiStatus.SERVER_STOPPING);
            return;
        }
        done();
    }

    /** Transfers exchange ownership after the normal request's permission check and completion. */
    public final boolean startStream() throws IOException {
        if (getResponse().httpStatus() != HttpURLConnection.HTTP_OK || streams == null || subscription == null)
            return false;
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
        } else streams.open(context, topicKey(), subscription, icons);
        return true;
    }
}
