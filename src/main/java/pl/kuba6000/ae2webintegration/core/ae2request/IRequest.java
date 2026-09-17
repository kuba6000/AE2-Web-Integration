package pl.kuba6000.ae2webintegration.core.ae2request;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.RequestInputs;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public abstract class IRequest {

    protected RequestContext context;

    public boolean init(RequestContext context) {
        this.context = context;
        try {
            RequestInputs.bind(this, context);
            return true;
        } catch (IllegalArgumentException | JsonParseException exception) {
            deny(ApiStatus.BAD_PARAM);
            return false;
        }
    }

    protected static GsonBuilder JSONBuilder = GSONUtils.GSON_BUILDER;

    @SuppressWarnings({ "unused", "FieldCanBeLocal" }) // Gson reads the fields reflectively.
    private static final class RequestResult {

        private final ApiStatus status;
        private final Object data;

        private RequestResult(ApiStatus status, Object data) {
            this.status = status;
            this.data = data;
        }
    }

    private static final ApiResponse PENDING_RESULT = ApiResponse.error(ApiStatus.TIMEOUT);
    private final CompletableFuture<ApiResponse> completion = new CompletableFuture<>();

    public String getJSON() {
        return getResponse().json();
    }

    public final ApiResponse getResponse() {
        return completion.getNow(PENDING_RESULT);
    }

    public final void awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        try {
            completion.get(timeout, unit);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Request completion failed", e.getCause());
        }
    }

    protected final void succeed(Object data) {
        complete(ApiStatus.OK, data);
    }

    public final void done() {
        succeed(null);
    }

    public final void deny(ApiStatus status) {
        deny(status, null);
    }

    protected final void deny(ApiStatus status, Object data) {
        complete(status, data);
    }

    /** Serialize on the handler's thread before publishing; no payload reference reaches the HTTP worker. */
    private void complete(ApiStatus status, Object data) {
        int httpStatus = status.httpStatus();
        respond(httpStatus, new RequestResult(status, data));
    }

    /** Serializes a declared API response on the handler thread, then publishes status and body together. */
    protected final void respond(int httpStatus, Object body) {
        if (completion.isDone()) return;
        String json = JSONBuilder.create()
            .toJson(body);
        completion.complete(new ApiResponse(httpStatus, json));
    }

    /**
     * Answers a request whose handler died in the tick pump, so its HTTP worker returns at once instead of
     * waiting for the request timeout and then reporting TIMEOUT.
     * <p>
     * Does nothing when the handler already produced a result: one that threw after {@link #done()} still
     * answered, the HTTP thread may already be reading that answer, and overwriting it would be both a
     * race and a lie about what happened.
     */
    public final void failIfPending(ApiStatus status) {
        deny(status);
    }

}
