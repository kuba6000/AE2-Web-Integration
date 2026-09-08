package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.ArrayList;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

public class GetTrackingHistory extends IAsyncRequest {

    private static class JSON_TrackingHistoryElement {

        public long timeStarted;
        public long timeDone;
        public boolean wasCancelled;
        public final @NotNull JSON_Stack finalOutput;
        public int id;

        private JSON_TrackingHistoryElement(@NotNull JSON_Stack finalOutput) {
            this.finalOutput = finalOutput;
        }
    }

    @Override
    public void handle(Map<String, String> getParams) {
        if (grid == null) {
            // Nothing has ever been tracked on this grid; an empty history is the honest answer.
            succeed(new ArrayList<JSON_TrackingHistoryElement>());
            return;
        }
        ArrayList<JSON_TrackingHistoryElement> jobs = new ArrayList<>(grid.trackingInfo.trackingInfos.size());

        for (Map.Entry<Integer, AE2JobTracker.JobTrackingInfo> integerJobTrackingInfoEntry : grid.trackingInfo.trackingInfos
            .entrySet()) {
            JSON_TrackingHistoryElement element = new JSON_TrackingHistoryElement(
                integerJobTrackingInfoEntry.getValue().finalOutput);
            element.id = integerJobTrackingInfoEntry.getKey();
            element.timeStarted = integerJobTrackingInfoEntry.getValue().timeStarted;
            element.timeDone = integerJobTrackingInfoEntry.getValue().timeDone;
            element.wasCancelled = integerJobTrackingInfoEntry.getValue().wasCancelled;
            jobs.add(element);
        }

        jobs.sort((i1, i2) -> Long.compare(i2.timeDone, i1.timeDone));

        succeed(jobs);
    }

}
