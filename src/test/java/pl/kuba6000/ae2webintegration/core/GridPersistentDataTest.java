package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;

class GridPersistentDataTest extends GridTestScope {

    @Test
    void settingsRemainTheSameObjectWhenEdited() throws Exception {
        var grid = TestGridFixtures.grid(1);
        var key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        var data = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        var settings = data.getSettings();
        assertFalse(settings.isDirty());
        settings.setTracked(false);
        assertFalse(settings.isDirty());
        settings.setTracked(true);
        assertTrue(settings.isDirty());
        CoreEngine.GRID_IDENTITIES.saveIfDirty();
        var current = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(current);
        assertSame(settings, current.getSettings());
        assertTrue(settings.isTracked());
        assertFalse(settings.isDirty());
    }

    @Test
    void controllerChangesKeepTheSettingsHandleAndSaveItsEdits() throws Exception {
        var registry = CoreEngine.GRID_IDENTITIES;
        Set<DimensionalCoords> controllers = new LinkedHashSet<>();
        var first = new DimensionalCoords("world", 0, 0, 0);
        var added = new DimensionalCoords("world", 1, 0, 0);
        controllers.add(first);
        var grid = new TestGridFixtures.TestGrid(0, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                return controllers;
            }
        };
        registry.controllerValidated(grid);
        var key = registry.getKey(grid);
        assertNotNull(key);
        var data = registry.getPersistentData(key);
        assertNotNull(data);
        var settings = data.getSettings();
        settings.setTracked(true);
        controllers.add(added);
        registry.controllerValidated(grid);
        var expanded = registry.getPersistentData(key);
        assertNotNull(expanded);
        assertSame(settings, expanded.getSettings());
        assertFalse(settings.isDirty());
        registry.controllerRemoved(first);
        var reduced = registry.getPersistentData(key);
        assertNotNull(reduced);
        assertSame(settings, reduced.getSettings());
        assertTrue(settings.isTracked());
        var restarted = new GridIdentityRegistry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        var loaded = restarted.getPersistentData(key);
        assertNotNull(loaded);
        assertTrue(
            loaded.getSettings()
                .isTracked());
        assertFalse(
            loaded.getSettings()
                .isDirty());
        assertEquals(key, restarted.findIdentity(added));
        assertNull(restarted.findIdentity(first));
    }

    @Test
    void editingRetiredSettingsCannotRestoreTheGrid() throws Exception {
        var registry = CoreEngine.GRID_IDENTITIES;
        var grid = TestGridFixtures.grid(1);
        var key = registry.getKey(grid);
        assertNotNull(key);
        var data = registry.getPersistentData(key);
        assertNotNull(data);
        registry.controllerRemoved(grid.position());
        data.getSettings()
            .setTracked(true);
        registry.saveIfDirty();
        assertNull(registry.getPersistentData(key));
        var restarted = new GridIdentityRegistry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        assertNull(restarted.getPersistentData(key));
    }
}
