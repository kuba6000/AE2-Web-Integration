package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.io.IOException;
import java.util.Map;

import pl.kuba6000.ae2webintegration.core.CoreEngine;

public class GridSettings extends IAsyncRequest {

    @Override
    public void handle(Map<String, String> getParams) {
        if (gridKey == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        if (getParams.containsKey("track")) {
            try {
                CoreEngine.GRID_IDENTITIES.setTracked(
                    gridKey,
                    getParams.get("track")
                        .equals("1"));
            } catch (IOException e) {
                deny("INTERNAL_ERROR");
                return;
            }
        }
        succeed(CoreEngine.GRID_IDENTITIES.getSettings(gridKey));
    }
}
