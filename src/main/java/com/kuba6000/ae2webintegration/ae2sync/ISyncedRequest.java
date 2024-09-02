package com.kuba6000.ae2webintegration.ae2sync;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.GsonBuilder;
import com.kuba6000.ae2webintegration.utils.GSONUtils;

import appeng.me.Grid;

public abstract class ISyncedRequest {

    protected static GsonBuilder JSONBuilder = GSONUtils.GSON_BUILDER;

    private static class JSON_Structure {

        String status;
        Object data;
    }

    public AtomicBoolean isDone = new AtomicBoolean(false);
    protected String status = "TIMEOUT";
    protected Object data = null;

    abstract public boolean init(Map<String, String> getParams);

    abstract public void handle(Grid grid);

    Object getData() {
        return data;
    }

    void setData(Object data) {
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
