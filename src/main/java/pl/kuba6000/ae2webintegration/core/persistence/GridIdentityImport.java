package pl.kuba6000.ae2webintegration.core.persistence;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

import com.google.gson.Gson;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.DataPolicy;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

/** One-time adapter for released grid identity JSON; remove after the transition release. */
final class GridIdentityImport {

    private GridIdentityImport() {}

    static @NotNull Map<StableKey, GridPersistentData> read(@NotNull Path source, @NotNull DataPolicy policy)
        throws IOException {
        if (Files.notExists(source)) return Collections.emptyMap();
        Gson standard = GSONUtils.GSON_BUILDER.create();
        JsonDeserializer<DimensionalCoords> coordinates = (json, type, context) -> {
            JsonObject position = json.getAsJsonObject();
            if (!position.has("dimensionId") && position.has("dimid"))
                position.add("dimensionId", position.remove("dimid"));
            return standard.fromJson(position, DimensionalCoords.class);
        };
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            Map<StableKey, GridPersistentData> records = GSONUtils.createBuilder()
                .registerTypeAdapter(DimensionalCoords.class, coordinates)
                .registerTypeAdapter(
                    GridSettingsData.class,
                    (InstanceCreator<GridSettingsData>) type -> new GridSettingsData(policy))
                .create()
                .fromJson(reader, new TypeToken<Map<StableKey, GridPersistentData>>() {}.getType());
            if (records == null) throw new IOException("Empty grid identity import");
            for (Map.Entry<StableKey, GridPersistentData> entry : records.entrySet()) {
                GridPersistentData data = entry.getValue();
                if (entry.getKey() == null || data == null) throw new IOException("Incomplete grid identity import");
                data.getSettings()
                    .applyPolicy(policy);
                data.attach(new Object(), () -> {});
                if (data.getControllers()
                    .isEmpty()) throw new IOException("Imported grid has no controllers");
                for (DimensionalCoords position : data.getControllers()) {
                    if (position == null || position.dimensionId() == null
                        || position.dimensionId()
                            .isEmpty())
                        throw new IOException("Incomplete imported controller coordinates");
                }
            }
            return records;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid grid identity import: " + source, failure);
        }
    }
}
