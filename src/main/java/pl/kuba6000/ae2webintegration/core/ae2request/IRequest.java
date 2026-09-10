package pl.kuba6000.ae2webintegration.core.ae2request;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.GsonBuilder;

import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public abstract class IRequest {

    protected static GsonBuilder JSONBuilder = GSONUtils.GSON_BUILDER;

    @SuppressWarnings({ "unused", "FieldCanBeLocal" }) // Gson reads the fields reflectively.
    private static final class RequestResult {

        private final String status;
        private final Object data;

        private RequestResult(String status, Object data) {
            this.status = status;
            this.data = data;
        }
    }

    private static final RequestResult PENDING_RESULT = new RequestResult("TIMEOUT", null);
    private final CompletableFuture<RequestResult> completion = new CompletableFuture<>();

    public String getJSON() {
        RequestResult result = completion.getNow(PENDING_RESULT);
        return JSONBuilder.create()
            .toJson(result);
    }

    public final void awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        try {
            completion.get(timeout, unit);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Request completion failed", e.getCause());
        }
    }

    protected final void succeed(Object data) {
        completion.complete(new RequestResult("OK", data));
    }

    public final void done() {
        succeed(null);
    }

    public final void deny(String status) {
        deny(status, null);
    }

    protected final void deny(String status, Object data) {
        completion.complete(new RequestResult(status, data));
    }

    /**
     * Answers a request whose handler died in the tick pump, so its HTTP worker returns at once instead of
     * waiting for the request timeout and then reporting TIMEOUT.
     * <p>
     * Does nothing when the handler already produced a result: one that threw after {@link #done()} still
     * answered, the HTTP thread may already be reading that answer, and overwriting it would be both a
     * race and a lie about what happened.
     */
    public final void failIfPending(String status) {
        deny(status);
    }

    public final void noParam(String... params) {
        deny("NO_PARAM", params);
    }

}
