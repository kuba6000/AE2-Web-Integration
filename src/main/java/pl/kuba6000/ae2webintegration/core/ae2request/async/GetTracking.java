package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.api.JSON_CompactedJobTrackingInfo;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.utils.HTTPUtils;

public class GetTracking extends IAsyncRequest {

    @Override
    public void handle(Map<String, String> getParams) {
        if (!getParams.containsKey("id")) {
            noParam("id");
            return;
        }
        Integer id = HTTPUtils.parseInt(getParams.get("id"));
        if (id == null) {
            deny("BAD_PARAM");
            return;
        }

        if (grid == null) {
            // The grid is real - access was checked - it simply has no tracking data at all.
            deny("TRACKING_NOT_FOUND");
            return;
        }

        AE2JobTracker.JobTrackingInfo info = grid.trackingInfo.trackingInfos.get(id);
        if (info == null) {
            deny("TRACKING_NOT_FOUND");
            return;
        }

        succeed(new JSON_CompactedJobTrackingInfo(info));
    }

}
