package com.kuba6000.ae2webintegration.core.ae2request.sync;

import java.util.Map;

import com.kuba6000.ae2webintegration.core.ae2request.IRequest;
import com.kuba6000.ae2webintegration.core.interfaces.IAEGrid;

public abstract class ISyncedRequest extends IRequest {

    abstract public boolean init(Map<String, String> getParams);

    abstract public void handle(IAEGrid grid);

    @Override
    public void handle(Map<String, String> getParams) {
        throw new IllegalArgumentException("ONLY SYNCED");
    }
}
