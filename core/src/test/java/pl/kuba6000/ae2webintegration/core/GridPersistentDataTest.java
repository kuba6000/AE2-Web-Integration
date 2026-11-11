package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class GridPersistentDataTest extends GridTestScope {

    @Test
    void machineCraftingSettingPersistsIndependentlyAndClearingRestoresDefaults() throws Exception {
        StableKey key = GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        assertNotNull(key);
        GridPersistentData data = GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData settings = data.getSettings();
        assertFalse(settings.isTrackMachineCrafting());
        settings.setTrackMachineCrafting(true);
        assertFalse(settings.isTracked());
        assertFalse(settings.isDefault());
        GRID_IDENTITIES.saveIfDirty();
        File file = new File(gridSave, "ae2webintegration/grid-identities.json");
        GridIdentityRegistry restarted = worlds.registry(file);
        GridPersistentData loaded = restarted.getPersistentData(key);
        assertNotNull(loaded);
        assertTrue(
            loaded.getSettings()
                .isTrackMachineCrafting());
        assertFalse(
            loaded.getSettings()
                .isTracked());
        loaded.getSettings()
            .setTrackMachineCrafting(false);
        restarted.saveIfDirty();
        GridPersistentData cleared = worlds.registry(file)
            .getPersistentData(key);
        assertNotNull(cleared);
        assertFalse(
            cleared.getSettings()
                .isTrackMachineCrafting());
        assertTrue(
            cleared.getSettings()
                .isDefault());
    }

    @Test
    void independentFlowAndStockSamplingSettingsPersistAndValidate() throws Exception {
        StableKey key = GRID_IDENTITIES.getKey(TestGridFixtures.grid(1));
        assertNotNull(key);
        GridPersistentData data = GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData settings = data.getSettings();
        assertFalse(settings.isFlowTracked());
        assertFalse(settings.isStorageTracked());
        assertEquals(300, settings.getStockSampleIntervalSeconds());
        settings.setFlowTracked(true);
        settings.setStockSampleIntervalSeconds(117);
        GRID_IDENTITIES.saveIfDirty();
        GridPersistentData loaded = worlds.registry(new File(gridSave, "ae2webintegration/grid-identities.json"))
            .getPersistentData(key);
        assertNotNull(loaded);
        assertTrue(
            loaded.getSettings()
                .isFlowTracked());
        assertFalse(
            loaded.getSettings()
                .isStorageTracked());
        assertEquals(
            117,
            loaded.getSettings()
                .getStockSampleIntervalSeconds());
        assertFalse(
            loaded.getSettings()
                .isDefault());
        assertThrows(IllegalArgumentException.class, () -> settings.setStockSampleIntervalSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> settings.setStockSampleIntervalSeconds(86401));
        assertEquals(117, settings.getStockSampleIntervalSeconds());
        settings.setFlowTracked(false);
        settings.setStockSampleIntervalSeconds(300);
        assertTrue(settings.isDefault());
    }

    @Test
    void namePersistsWithoutTrackingAndClearingRestoresDefaults() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        GridPersistentData data = GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData settings = data.getSettings();
        assertEquals("", settings.getName());
        settings.setName("Factory");
        GRID_IDENTITIES.saveIfDirty();
        settings.setName("Factory");
        GRID_IDENTITIES.saveIfDirty();
        File file = new File(gridSave, "ae2webintegration/grid-identities.json");
        GridIdentityRegistry restarted = worlds.registry(file);
        GridPersistentData loaded = restarted.getPersistentData(key);
        assertNotNull(loaded);
        assertEquals(
            "Factory",
            loaded.getSettings()
                .getName());
        assertFalse(
            loaded.getSettings()
                .isTracked());
        assertFalse(
            loaded.getSettings()
                .isDefault());
        loaded.getSettings()
            .setName("");
        restarted.saveIfDirty();
        GridPersistentData cleared = worlds.registry(file)
            .getPersistentData(key);
        assertNotNull(cleared);
        assertEquals(
            "",
            cleared.getSettings()
                .getName());
        assertTrue(
            cleared.getSettings()
                .isDefault());
    }

    @Test
    void settingsRemainTheSameObjectWhenEdited() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        GridPersistentData data = GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData settings = data.getSettings();
        settings.setTracked(false);
        GRID_IDENTITIES.saveIfDirty();
        settings.setTracked(true);
        GRID_IDENTITIES.saveIfDirty();
        GridPersistentData current = GRID_IDENTITIES.getPersistentData(key);
        assertNotNull(current);
        assertSame(settings, current.getSettings());
        assertTrue(settings.isTracked());
        GRID_IDENTITIES.saveIfDirty();
    }

    @Test
    void controllerChangesKeepTheSettingsHandleAndSaveItsEdits() throws Exception {
        GridIdentityRegistry registry = GRID_IDENTITIES;
        Set<DimensionalCoords> controllers = new LinkedHashSet<>();
        DimensionalCoords first = new DimensionalCoords("world", 0, 0, 0);
        DimensionalCoords added = new DimensionalCoords("world", 1, 0, 0);
        controllers.add(first);
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(0, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                return controllers;
            }
        };
        registry.controllerValidated(grid);
        StableKey key = registry.getKey(grid);
        assertNotNull(key);
        GridPersistentData data = registry.getPersistentData(key);
        assertNotNull(data);
        GridSettingsData settings = data.getSettings();
        settings.setTracked(true);
        controllers.add(added);
        registry.controllerValidated(grid);
        GridPersistentData expanded = registry.getPersistentData(key);
        assertNotNull(expanded);
        assertSame(settings, expanded.getSettings());
        registry.controllerRemoved(first);
        GridPersistentData reduced = registry.getPersistentData(key);
        assertNotNull(reduced);
        assertSame(settings, reduced.getSettings());
        assertTrue(settings.isTracked());
        GRID_IDENTITIES.saveIfDirty();
        GridIdentityRegistry restarted = worlds.registry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        GridPersistentData loaded = restarted.getPersistentData(key);
        assertNotNull(loaded);
        assertTrue(
            loaded.getSettings()
                .isTracked());
        assertEquals(key, restarted.findIdentity(added));
        assertNull(restarted.findIdentity(first));
    }

    @Test
    void editingRetiredSettingsCannotRestoreTheGrid() throws Exception {
        GridIdentityRegistry registry = GRID_IDENTITIES;
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        StableKey key = registry.getKey(grid);
        assertNotNull(key);
        GridPersistentData data = registry.getPersistentData(key);
        assertNotNull(data);
        registry.controllerRemoved(grid.position());
        data.getSettings()
            .setTracked(true);
        registry.saveIfDirty();
        assertNull(registry.getPersistentData(key));
        GridIdentityRegistry restarted = worlds.registry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        assertNull(restarted.getPersistentData(key));
    }

    @Test
    void oldSettingsCannotTriggerWritesAfterReopeningTheSave() throws Exception {
        GridIdentityRegistry registry = GRID_IDENTITIES;
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        StableKey key = registry.getKey(grid);
        assertNotNull(key);
        GridPersistentData previous = registry.getPersistentData(key);
        assertNotNull(previous);
        registry.saveIfDirty();
        worlds.initialize(gridSave);
        previous.getSettings()
            .setTracked(true);
        registry.saveIfDirty();
        GridPersistentData current = registry.getPersistentData(key);
        assertNotNull(current);
        assertFalse(
            current.getSettings()
                .isTracked());
        current.getSettings()
            .setTracked(true);
        registry.saveIfDirty();
        GridIdentityRegistry restarted = worlds.registry(new File(gridSave, "ae2webintegration/grid-identities.json"));
        assertTrue(TestGridFixtures.isTracked(restarted, key));
    }

}
