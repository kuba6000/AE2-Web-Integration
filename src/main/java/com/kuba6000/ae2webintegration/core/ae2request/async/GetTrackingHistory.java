package com.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.ArrayList;
import java.util.Map;

import com.kuba6000.ae2webintegration.core.AE2JobTracker;
import com.kuba6000.ae2webintegration.core.interfaces.IItemStack;

public class GetTrackingHistory extends IAsyncRequest {

    private static class JSON_TrackingHistoryElement {

        public long timeStarted;
        public long timeDone;
        public boolean wasCancelled;
        public IItemStack finalOutput;
        public int id;
    }

    @Override
    public void handle(Map<String, String> getParams) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        ArrayList<JSON_TrackingHistoryElement> jobs = new ArrayList<>(grid.trackingInfo.trackingInfos.size());

        for (Map.Entry<Integer, AE2JobTracker.JobTrackingInfo> integerJobTrackingInfoEntry : grid.trackingInfo.trackingInfos
            .entrySet()) {
            JSON_TrackingHistoryElement element = new JSON_TrackingHistoryElement();
            element.id = integerJobTrackingInfoEntry.getKey();
            element.timeStarted = integerJobTrackingInfoEntry.getValue().timeStarted;
            element.timeDone = integerJobTrackingInfoEntry.getValue().timeDone;
            element.wasCancelled = integerJobTrackingInfoEntry.getValue().wasCancelled;
            element.finalOutput = integerJobTrackingInfoEntry.getValue().finalOutput;
            jobs.add(element);
        }

        jobs.sort((i1, i2) -> Long.compare(i2.timeDone, i1.timeDone));

        setData(jobs);
        done();
    }

}
