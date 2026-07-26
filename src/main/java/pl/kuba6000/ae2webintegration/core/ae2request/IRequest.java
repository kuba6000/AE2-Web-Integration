package pl.kuba6000.ae2webintegration.core.ae2request;

import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.GsonBuilder;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public abstract class IRequest {

    protected static GsonBuilder JSONBuilder = GSONUtils.GSON_BUILDER;

    private static class JSON_Structure {

        String status;
        Object data;
    }

    public AtomicBoolean isDone = new AtomicBoolean(false);
    protected String status = "TIMEOUT";
    protected Object data = null;

    abstract public void handle(AE2Controller.RequestContext context);

    Object getData() {
        return data;
    }

    protected void setData(Object data) {
        this.data = data;
    }

    public String getJSON() {
        JSON_Structure structure = new JSON_Structure();
        structure.status = status;
        structure.data = getData();
        return JSONBuilder.create()
            .toJson(structure);
    }

    public void done() {
        this.status = "OK";
        this.isDone.set(true);
    }

    public void deny(String status) {
        this.status = status;
        this.isDone.set(true);
    }

    /**
     * Answers a request whose handler died in the tick pump, so its HTTP worker returns at once instead of
     * spinning out the ten second poll in {@code sendRequest} and then reporting TIMEOUT.
     * <p>
     * Does nothing when the handler already produced a result: one that threw after {@link #done()} still
     * answered, the HTTP thread may already be reading that answer, and overwriting it would be both a
     * race and a lie about what happened.
     */
    public void failIfPending(String status) {
        if (isDone.get()) {
            return;
        }
        deny(status);
    }

    public void noParam(String... params) {
        deny("NO_PARAM");
        setData(params);
    }

}
