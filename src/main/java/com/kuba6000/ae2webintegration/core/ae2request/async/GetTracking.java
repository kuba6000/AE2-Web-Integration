package com.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2JobTracker;
import com.kuba6000.ae2webintegration.core.api.JSON_CompactedJobTrackingInfo;

public class GetTracking extends IAsyncRequest {

    @Override
    public void handle(Map<String, String> getParams) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        if (!getParams.containsKey("id")) {
            noParam("id");
            return;
        }
        int id = Integer.parseInt(getParams.get("id"));

        AE2JobTracker.JobTrackingInfo info = grid.trackingInfo.trackingInfos.get(id);
        if (info == null) {
            deny("TRACKING_NOT_FOUND");
            return;
        }

        setData(new JSON_CompactedJobTrackingInfo(info));
        done();
    }

}
