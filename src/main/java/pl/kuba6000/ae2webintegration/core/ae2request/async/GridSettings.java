package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.util.Map;

import pl.kuba6000.ae2webintegration.core.GridData;

public class GridSettings extends IAsyncRequest {

    @Override
    public void handle(Map<String, String> getParams) {
        if (grid == null) {
            deny("GRID_NOT_FOUND");
            return;
        }

        if (getParams.containsKey("track")) {
            grid.isTracked = getParams.get("track")
                .equals("1");
            GridData.saveChanges();
        }

        setData(grid);
        done();
    }
}
