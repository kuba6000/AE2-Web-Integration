package com.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2Controller;
import com.kuba6000.ae2webintegration.core.GridData;
import com.kuba6000.ae2webintegration.core.ae2request.IRequest;

public abstract class IAsyncRequest extends IRequest {

    protected AE2Controller.RequestContext context = null;
    protected long gridKey = -1;
    protected GridData grid = null;

    public void handle(Map<String, String> getParams) {};

    @Override
    public void handle(AE2Controller.RequestContext context) {
        this.context = context;
        String gridstr = context.getGetParams()
            .get("grid");
        if (gridstr == null || gridstr.isEmpty()) gridKey = -1;
        else gridKey = Long.parseLong(gridstr);
        if (gridKey != -1) {
            grid = GridData.get(gridKey);
        }
        handle(context.getGetParams());
    }
}
