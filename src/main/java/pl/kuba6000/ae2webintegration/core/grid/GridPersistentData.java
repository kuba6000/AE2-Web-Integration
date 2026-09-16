package pl.kuba6000.ae2webintegration.core.grid;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;

/** Durable grid data. Active crafting state remains in GridData. */
public final class GridPersistentData {

    private final Set<DimensionalCoords> controllers;
    private final GridSettingsData settings;

    public GridPersistentData(@NotNull Set<DimensionalCoords> controllers, @NotNull GridSettingsData settings) {
        this.controllers = new LinkedHashSet<>(controllers);
        this.settings = settings;
    }

    public @NotNull Set<DimensionalCoords> getControllers() {
        return Collections.unmodifiableSet(controllers);
    }

    public @NotNull GridSettingsData getSettings() {
        return settings;
    }

    /** Membership replacement retains the same mutable settings object. */
    public @NotNull GridPersistentData withControllers(@NotNull Set<DimensionalCoords> controllers) {
        return this.controllers.equals(controllers) ? this : new GridPersistentData(controllers, settings);
    }

    /** Also validates required fields after Gson loading, before exposing the record. */
    @SuppressWarnings("ConstantValue") // Gson can bypass the constructor and leave required fields null.
    public void attach(@NotNull Object lock, @NotNull Runnable onChange) {
        if (controllers == null || settings == null) throw new IllegalArgumentException("Incomplete grid data");
        settings.attach(lock, onChange);
    }
}
