package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.GridData;

public class GridSettings extends IAsyncRequest {

    @Override
    public void handle(Map<String, String> getParams) {
        if (gridKey == -1) {
            deny("GRID_NOT_FOUND");
            return;
        }
        if (getParams.containsKey("track")) {
            // Access was already verified against the live grids, so creating the entry here is safe -
            // this is the one async endpoint that legitimately stores something.
            GridData stored = GridData.getOrCreate(gridKey);
            stored.isTracked = getParams.get("track")
                .equals("1");
            GridData.saveChanges();
            grid = stored;
        }
        succeed(grid != null ? grid : new GridData());
    }
}
