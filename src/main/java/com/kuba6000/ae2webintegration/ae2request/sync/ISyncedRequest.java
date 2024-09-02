package com.kuba6000.ae2webintegration.ae2request.sync;

import java.util.Map;

import com.kuba6000.ae2webintegration.ae2request.IRequest;

import appeng.me.Grid;

public abstract class ISyncedRequest extends IRequest {

    abstract public boolean init(Map<String, String> getParams);

    abstract public void handle(Grid grid);

    @Override
    public void handle(Map<String, String> getParams) {
        throw new IllegalArgumentException("ONLY SYNCED");
    }
}
