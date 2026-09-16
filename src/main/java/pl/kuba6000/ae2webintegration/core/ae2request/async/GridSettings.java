package pl.kuba6000.ae2webintegration.core.ae2request.async;

import java.io.IOException;
import java.util.Map;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;

public class GridSettings extends IAsyncRequest {

    @Override
    public void handle(Map<String, String> getParams) {
        if (gridKey == null) {
            deny("GRID_NOT_FOUND");
            return;
        }
        GridIdentityRegistry registry = CoreEngine.GRID_IDENTITIES;
        synchronized (registry) {
            GridPersistentData data = registry.getPersistentData(gridKey);
            if (data == null) {
                deny("GRID_NOT_FOUND");
                return;
            }
            GridSettingsData settings = data.getSettings();
            try {
                if (getParams.containsKey("track")) {
                    settings.setTracked(
                        getParams.get("track")
                            .equals("1"));
                    registry.saveIfDirty();
                }
                // Completion serializes under the same monitor used by settings mutations and file writes.
                succeed(settings);
            } catch (IOException e) {
                deny("INTERNAL_ERROR");
            }
        }
    }
}
