package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
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
        settings.setTracked(false);
        assertSaveDoesNotRewrite(CoreEngine.GRID_IDENTITIES);
        settings.setTracked(true);
        CoreEngine.GRID_IDENTITIES.saveIfDirty();
        var current = CoreEngine.GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(current);
        assertSame(settings, current.getSettings());
        assertTrue(settings.isTracked());
        assertSaveDoesNotRewrite(CoreEngine.GRID_IDENTITIES);
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
        registry.controllerRemoved(first);
        var reduced = registry.getPersistentData(key);
        assertNotNull(reduced);
        assertSame(settings, reduced.getSettings());
        assertTrue(settings.isTracked());
        assertSaveDoesNotRewrite(CoreEngine.GRID_IDENTITIES);
        var restarted = new GridIdentityRegistry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        var loaded = restarted.getPersistentData(key);
        assertNotNull(loaded);
        assertTrue(
            loaded.getSettings()
                .isTracked());
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
        assertSaveDoesNotRewrite(registry);
        assertNull(registry.getPersistentData(key));
        var restarted = new GridIdentityRegistry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        assertNull(restarted.getPersistentData(key));
    }

    @Test
    void oldSettingsCannotTriggerWritesAfterReopeningTheSave() throws Exception {
        var registry = CoreEngine.GRID_IDENTITIES;
        var grid = TestGridFixtures.grid(1);
        var key = registry.getKey(grid);
        assertNotNull(key);
        var previous = registry.getPersistentData(key);
        assertNotNull(previous);
        registry.initialize(gridSave);
        previous.getSettings()
            .setTracked(true);
        assertSaveDoesNotRewrite(registry);
        var current = registry.getPersistentData(key);
        assertNotNull(current);
        assertFalse(
            current.getSettings()
                .isTracked());
        current.getSettings()
            .setTracked(true);
        registry.saveIfDirty();
        var restarted = new GridIdentityRegistry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        assertTrue(TestGridFixtures.isTracked(restarted, key));
    }

    private void assertSaveDoesNotRewrite(GridIdentityRegistry registry) throws Exception {
        var file = gridSave.toPath()
            .resolve("ae2webintegration/grid-identities.json");
        Files.setLastModifiedTime(file, FileTime.fromMillis(0));
        var before = Files.getLastModifiedTime(file);
        registry.saveIfDirty();
        assertEquals(before, Files.getLastModifiedTime(file));
    }
}
