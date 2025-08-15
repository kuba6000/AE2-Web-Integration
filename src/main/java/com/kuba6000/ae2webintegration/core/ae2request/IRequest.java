package com.kuba6000.ae2webintegration.core.ae2request;

import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.GsonBuilder;
import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.utils.GSONUtils;

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

    public void noParam(String... params) {
        deny("NO_PARAM");
        setData(params);
    }

}
