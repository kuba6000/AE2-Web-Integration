package pl.kuba6000.ae2webintegration.core.ae2request;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.http.ApiResponse;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
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

    @Desugar
    private record RequestResult<T>(@NotNull ApiStatus status, @Nullable T data) implements IResponseBody {}

    private static final ApiResponse PENDING_RESULT = ApiResponse.error(ApiStatus.TIMEOUT);
    private final CompletableFuture<ResponseResult> completion = new CompletableFuture<>();

    private static final class ResponseResult {

        private final int httpStatus;
        private final @Nullable RequestContext context;
        private @Nullable IResponseBody body;
        private @Nullable ApiResponse response;

        private ResponseResult(@NotNull ApiResponse response) {
            this.httpStatus = response.httpStatus();
            this.context = null;
            this.response = response;
        }

        private ResponseResult(int httpStatus, @NotNull IResponseBody body, @Nullable RequestContext context) {
            this.httpStatus = httpStatus;
            this.body = body;
            this.context = context;
        }

        private synchronized @NotNull ApiResponse materialize() {
            if (context != null && !context.isLifecycleUnchanged()) {
                body = null;
                response = ApiResponse.error(ApiStatus.SERVER_STOPPING);
                return response;
            }
            if (response == null) {
                String json = JSONBuilder.create()
                    .toJson(body);
                response = context != null && !context.isLifecycleUnchanged()
                    ? ApiResponse.error(ApiStatus.SERVER_STOPPING)
                    : new ApiResponse(httpStatus, json);
                body = null;
            }
            return response;
        }
    }

    public String getJSON() {
        return getResponse().json();
    }

    public final ApiResponse getResponse() {
        ResponseResult result = completion.getNow(null);
        return result == null ? PENDING_RESULT : result.materialize();
    }

    public final void awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        try {
            completion.get(timeout, unit);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Request completion failed", e.getCause());
        }
    }

    protected final <T> void succeed(@Nullable T data) {
        complete(ApiStatus.OK, data);
    }

    public final void done() {
        succeed(null);
    }

    public final void deny(ApiStatus status) {
        deny(status, null);
    }

    protected final <T> void deny(ApiStatus status, @Nullable T data) {
        complete(status, data);
    }

    /** Serialize on the handler's thread before publishing; no payload reference reaches the HTTP worker. */
    private <T> void complete(ApiStatus status, @Nullable T data) {
        int httpStatus = status.httpStatus();
        respond(httpStatus, new RequestResult<>(status, data));
    }

    /** Serializes a declared API response on the handler thread, then publishes status and body together. */
    protected final void respond(int httpStatus, @NotNull IResponseBody body) {
        if (completion.isDone()) return;
        String json = JSONBuilder.create()
            .toJson(body);
        completion.complete(new ResponseResult(new ApiResponse(httpStatus, json)));
    }

    /**
     * Transfers exclusive ownership of a complete, detached response to its consumer. No native objects,
     * live collections or later mutations are allowed; JSON is produced when the HTTP worker reads it.
     */
    protected final void respondDetached(int httpStatus, @NotNull IResponseBody body) {
        if (completion.isDone()) return;
        completion.complete(new ResponseResult(httpStatus, body, context));
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
