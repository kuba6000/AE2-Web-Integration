package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.grid.GridSettingsData;
import pl.kuba6000.ae2webintegration.core.identity.GridIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.persistence.WorldDatabase;
import pl.kuba6000.ae2webintegration.core.persistence.WorldGridStore;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridIdentityRegistryTest {

    private final TestGridFixtures.Worlds worlds = new TestGridFixtures.Worlds();

    @AfterEach
    void closeWorlds() throws IOException {
        worlds.close();
    }

    @TempDir
    Path directory;

    @Test
    void applyingLiveBoundsClampsRamAndPendingRetiredSettingsWithoutWritingSql() throws Exception {
        ConfigSettings.ResourceHistory initial = new ConfigSettings.ResourceHistory();
        initial.stockSampleIntervalSeconds = new ConfigSettings.Range(10, 600, 120);
        initial.flowFlushIntervalSeconds = new ConfigSettings.Range(10, 600, 90);
        initial.historyRetentionDays = new ConfigSettings.Range(1, 30, 7);
        try (WorldDatabase database = new WorldDatabase(directory.resolve("live-policy"), initial)) {
            GridIdentityRegistry registry = new GridIdentityRegistry();
            registry.initialize(database, directory.resolve("absent.json"));
            StableKey active = resolve(registry, controller(1));
            GridPersistentData activeData = registry.getPersistentData(active);
            assertNotNull(activeData);
            GridSettingsData settings = activeData.getSettings();
            settings.setStockSampleIntervalSeconds(500);
            settings.setFlowFlushIntervalSeconds(100);
            settings.setHistoryRetentionDays(30);
            settings.setName("Keep");
            settings.setTracked(true);
            registry.saveIfDirty();
            StableKey retired = resolve(registry, controller(2));
            GridPersistentData retiredData = registry.getPersistentData(retired);
            assertNotNull(retiredData);
            retiredData.getSettings()
                .setHistoryRetentionDays(30);
            registry.controllerRemoved(controller(2));
            ConfigSettings.ResourceHistory tightened = new ConfigSettings.ResourceHistory();
            tightened.stockSampleIntervalSeconds = new ConfigSettings.Range(20, 90, 60);
            tightened.flowFlushIntervalSeconds = new ConfigSettings.Range(50, 150, 100);
            tightened.historyRetentionDays = new ConfigSettings.Range(2, 14, 3);
            assertTrue(database.applyPolicy(tightened));
            assertEquals(active, registry.findIdentity(controller(1)));
            assertEquals(90, settings.getStockSampleIntervalSeconds());
            assertEquals(100, settings.getFlowFlushIntervalSeconds());
            assertEquals(14, settings.getHistoryRetentionDays());
            assertEquals("Keep", settings.getName());
            assertTrue(settings.isTracked());
            assertThrows(IllegalArgumentException.class, () -> settings.setStockSampleIntervalSeconds(100));
            database.read(db -> {
                try (Statement sql = db.createStatement();
                    ResultSet rows = sql.executeQuery("SELECT stock_seconds FROM grids")) {
                    assertTrue(rows.next());
                    assertEquals(500, rows.getInt(1));
                    assertFalse(rows.next());
                }
                return null;
            });
            StableKey created = resolve(registry, controller(3));
            GridPersistentData createdData = registry.getPersistentData(created);
            assertNotNull(createdData);
            GridSettingsData defaults = createdData.getSettings();
            assertEquals(60, defaults.getStockSampleIntervalSeconds());
            assertEquals(100, defaults.getFlowFlushIntervalSeconds());
            assertEquals(3, defaults.getHistoryRetentionDays());
            registry.saveIfDirty();
            database.read(db -> {
                try (Statement sql = db.createStatement();
                    ResultSet rows = sql
                        .executeQuery("SELECT retention_days FROM grids WHERE grid_key = '" + retired + "'")) {
                    assertTrue(rows.next());
                    assertEquals(14, rows.getInt(1));
                }
                return null;
            });
        }
    }

    @Test
    void nativeMembershipPublishesImmediatelyAndPersistsOnlyWhenFlushed() throws Exception {
        File source = directory.resolve("deferred.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(source);
        WorldDatabase database = worlds.database(source);
        WorldGridStore durable = new WorldGridStore(database);
        StableKey key = resolve(registry, controller(1));
        assertNotNull(key);
        assertTrue(
            durable.load(source.toPath())
                .isEmpty());
        assertEquals(key, resolve(registry, controller(1), controller(2)));
        registry.controllerRemoved(controller(1));
        assertNull(registry.findIdentity(controller(1)));
        assertEquals(key, registry.findIdentity(controller(2)));
        assertTrue(
            durable.load(source.toPath())
                .isEmpty());
        registry.saveIfDirty();
        assertEquals(
            Collections.singleton(controller(2)),
            durable.load(source.toPath())
                .get(key)
                .getControllers());
        registry.controllerRemoved(controller(2));
        assertFalse(registry.containsIdentity(key));
        assertTrue(
            durable.load(source.toPath())
                .containsKey(key));
        registry.saveIfDirty();
        assertTrue(
            durable.load(source.toPath())
                .isEmpty());
    }

    @Test
    void configuredDefaultsAndTighterBoundsPreserveSavedIdentities() throws Exception {
        ConfigSettings previous = Config.INSTANCE;
        try {
            ConfigSettings configured = new ConfigSettings();
            configured.resourceHistory.stockSampleIntervalSeconds = new ConfigSettings.Range(10, 600, 120);
            Config.INSTANCE = configured;
            File file = directory.resolve("policy.json")
                .toFile();
            GridIdentityRegistry registry = worlds.registry(file);
            StableKey key = resolve(registry, controller(1));
            assertEquals(
                120,
                registry.getPersistentData(key)
                    .getSettings()
                    .getStockSampleIntervalSeconds());
            assertTrue(
                registry.getPersistentData(key)
                    .getSettings()
                    .isDefault());
            registry.getPersistentData(key)
                .getSettings()
                .setStockSampleIntervalSeconds(500);
            registry.saveIfDirty();
            ConfigSettings tightened = new ConfigSettings();
            tightened.resourceHistory.stockSampleIntervalSeconds = new ConfigSettings.Range(20, 90, 60);
            Config.INSTANCE = tightened;
            // A config reload does not replace the policy of an already initialized world.
            registry.getPersistentData(key)
                .getSettings()
                .setStockSampleIntervalSeconds(400);
            registry.saveIfDirty();
            worlds.close(file);
            GridIdentityRegistry reopened = worlds.registry(file);
            assertEquals(key, reopened.findIdentity(controller(1)));
            assertEquals(
                90,
                reopened.getPersistentData(key)
                    .getSettings()
                    .getStockSampleIntervalSeconds());
            Config.INSTANCE = configured;
            worlds.close(file);
            GridIdentityRegistry restored = worlds.registry(file);
            assertEquals(key, restored.findIdentity(controller(1)));
            assertEquals(
                90,
                restored.getPersistentData(key)
                    .getSettings()
                    .getStockSampleIntervalSeconds());
        } finally {
            Config.INSTANCE = previous;
        }
    }

    @Test
    void defaultIdentitySurvivesRestartAndControllerChanges() throws Exception {
        File file = directory.resolve("defaults.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        StableKey key = resolve(registry, controller(2), controller(1));

        assertFalse(TestGridFixtures.isTracked(registry, key));
        registry.removeController(controller(1));
        registry.saveIfDirty();
        worlds.close(file);
        GridIdentityRegistry restarted = worlds.registry(file);
        assertEquals(key, resolve(restarted, controller(3), controller(2)));
        restarted.saveIfDirty();
        assertEquals(
            key,
            worlds.registry(file)
                .findIdentity(controller(3)));
    }

    @Test
    void identicalCoordinatesInDifferentSavesReceiveIndependentIdentities() throws Exception {
        GridIdentityRegistry first = worlds.registry(
            directory.resolve("first.json")
                .toFile());
        GridIdentityRegistry second = worlds.registry(
            directory.resolve("second.json")
                .toFile());
        StableKey firstKey = resolve(first, controller(1));
        StableKey secondKey = resolve(second, controller(1));
        assertNotEquals(firstKey, secondKey);
        TestGridFixtures.setTracked(first, firstKey, true);
        assertFalse(TestGridFixtures.isTracked(second, secondKey));
    }

    @Test
    void firstValidatedPartKeepsTheKeyEvenWithFewerControllers() throws Exception {
        File file = directory.resolve("split.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        StableKey original = resolve(registry, controller(1), controller(2), controller(3));
        TestGridFixtures.setTracked(registry, original, true);
        ControllerGrid first = new ControllerGrid(controller(1));
        ControllerGrid later = new ControllerGrid(controller(2), controller(3));
        registry.controllerValidated(first);
        registry.controllerValidated(later);
        assertEquals(original, registry.getKey(first));
        StableKey fresh = registry.getKey(later);
        assertNotNull(fresh);
        assertNotEquals(original, fresh);
        assertTrue(TestGridFixtures.isTracked(registry, original));
        assertFalse(TestGridFixtures.isTracked(registry, fresh));
        registry.saveIfDirty();
        GridIdentityRegistry restarted = worlds.registry(file);
        restarted.controllerValidated(later);
        restarted.controllerValidated(first);
        assertEquals(original, restarted.getKey(first));
        assertEquals(fresh, restarted.getKey(later));
    }

    @Test
    void successfulMergePrefersSettingsAndRetiresConsumedIdentities() throws Exception {
        File file = directory.resolve("merge.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        StableKey untracked = resolve(registry, controller(1));
        StableKey tracked = resolve(registry, controller(2));
        TestGridFixtures.setTracked(registry, tracked, true);
        assertEquals(tracked, resolve(registry, controller(1), controller(2)));
        assertFalse(registry.containsIdentity(untracked));
        registry.saveIfDirty();
        GridIdentityRegistry restarted = worlds.registry(file);
        assertEquals(tracked, restarted.findIdentity(controller(1)));
        assertTrue(TestGridFixtures.isTracked(restarted, tracked));
    }

    @Test
    void mergePrefersNamedUntrackedIdentityAndPreservesNameAfterRestart() throws Exception {
        File file = directory.resolve("named-merge.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        StableKey first = resolve(registry, controller(1));
        StableKey second = resolve(registry, controller(2));
        StableKey named = first.toString()
            .compareTo(second.toString()) > 0 ? first : second;
        GridPersistentData data = registry.getPersistentData(named);
        assertNotNull(data);
        data.getSettings()
            .setName("Factory");
        registry.saveIfDirty();
        assertEquals(named, resolve(registry, controller(1), controller(2)));
        GridPersistentData loaded = worlds.registry(file)
            .getPersistentData(named);
        assertNotNull(loaded);
        assertEquals(
            "Factory",
            loaded.getSettings()
                .getName());
        assertFalse(
            loaded.getSettings()
                .isTracked());
        loaded.getSettings()
            .setName("");
        assertTrue(
            loaded.getSettings()
                .isDefault());
    }

    @Test
    void equalSettingsMergeKeepsTheSameKeyRegardlessOfControllerOrder() throws Exception {
        for (boolean reverse : Arrays.asList(false, true)) {
            GridIdentityRegistry registry = worlds.registry(
                directory.resolve("tie-" + reverse + ".json")
                    .toFile());
            StableKey first = resolve(registry, controller(1));
            StableKey second = resolve(registry, controller(2));
            StableKey expected = first.toString()
                .compareTo(second.toString()) < 0 ? first : second;
            assertEquals(
                expected,
                reverse ? resolve(registry, controller(2), controller(1))
                    : resolve(registry, controller(1), controller(2)));
        }
    }

    @Test
    void conflictingBridgeDoesNotChangeSavedIdentitiesAndRepairRestoresBoth() throws Exception {
        Path file = directory.resolve("conflict.json");
        GridIdentityRegistry registry = worlds.registry(file.toFile());
        ControllerGrid first = new ControllerGrid(controller(1));
        ControllerGrid second = new ControllerGrid(controller(2));
        registry.controllerValidated(first);
        registry.controllerValidated(second);
        StableKey firstKey = registry.getKey(first);
        assertNotNull(firstKey);
        StableKey secondKey = registry.getKey(second);
        assertNotNull(secondKey);
        TestGridFixtures.setTracked(registry, firstKey, true);
        TestGridFixtures.setTracked(registry, secondKey, true);

        first.controllers.add(controller(2));
        first.state = AEControllerState.CONTROLLER_CONFLICT;
        first.mayReadControllers = false;
        registry.controllerValidated(first);
        GridIdentityRegistry saved = worlds.registry(file.toFile());
        assertEquals(firstKey, saved.findIdentity(controller(1)));
        assertEquals(secondKey, saved.findIdentity(controller(2)));
        assertEquals(firstKey, registry.findIdentity(controller(1)));
        assertEquals(secondKey, registry.findIdentity(controller(2)));
        assertTrue(TestGridFixtures.isTracked(registry, firstKey));
        assertTrue(TestGridFixtures.isTracked(registry, secondKey));
        assertNull(registry.getKey(first));

        first.controllers.remove(controller(2));
        first.state = AEControllerState.CONTROLLER_ONLINE;
        // The ignored callback did not erase the previous runtime binding either.
        assertEquals(firstKey, registry.getKey(first));
        first.mayReadControllers = true;
        registry.controllerValidated(second);
        registry.controllerValidated(first);
        assertEquals(firstKey, registry.getKey(first));
        assertEquals(secondKey, registry.getKey(second));
    }

    @Test
    void physicalRemovalRetiresOnlyTheLastKnownController() throws Exception {
        File file = directory.resolve("removal.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        ControllerGrid grid = new ControllerGrid(controller(1), controller(2));
        registry.controllerValidated(grid);
        StableKey key = registry.getKey(grid);
        assertNotNull(key);
        TestGridFixtures.setTracked(registry, key, true);
        registry.controllerRemoved(controller(1));
        assertTrue(registry.containsIdentity(key));
        assertEquals(key, registry.findIdentity(controller(2)));
        registry.controllerRemoved(controller(2));
        assertFalse(registry.containsIdentity(key));
        assertNull(registry.getKey(grid));
        registry.saveIfDirty();
        assertNotEquals(key, resolve(worlds.registry(file), controller(1)));
    }

    @Test
    void UnobservedRecordsRemainSavedAndReadsDoNotDiscoverGrids() throws Exception {
        File file = directory.resolve("unobserved.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        StableKey absent = resolve(registry, controller(1));
        TestGridFixtures.setTracked(registry, absent, true);
        ControllerGrid present = new ControllerGrid(controller(2));
        assertNull(registry.getKey(present));
        registry.controllerValidated(present);
        present.mayReadControllers = false;
        assertNotNull(registry.getKey(present));
        assertTrue(TestGridFixtures.isTracked(worlds.registry(file), absent));
        assertEquals(
            absent,
            worlds.registry(file)
                .findIdentity(controller(1)));
    }

    @Test
    void settingsDefaultsRemainPersistedAfterUnchangedObservations() throws Exception {
        Path file = directory.resolve("unchanged.json");
        GridIdentityRegistry registry = worlds.registry(file.toFile());
        StableKey key = resolve(registry, controller(1));
        TestGridFixtures.setTracked(registry, key, true);
        TestGridFixtures.setTracked(registry, key, false);
        assertEquals(
            key,
            worlds.registry(file.toFile())
                .findIdentity(controller(1)));
        assertFalse(TestGridFixtures.isTracked(worlds.registry(file.toFile()), key));

        TestGridFixtures.setTracked(registry, key, false);
        resolve(registry, controller(1));
        registry.removeController(controller(99));
        assertFalse(TestGridFixtures.isTracked(worlds.registry(file.toFile()), key));
        assertNull(registry.getPersistentData(StableKey.random()));
    }

    @Test
    void failedCommitDoesNotPreventNativeMembershipPublication() throws Exception {
        File source = directory.resolve("failure.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(source);
        StableKey key = resolve(registry, controller(1));
        registry.saveIfDirty();
        worlds.close(source);
        assertThrows(IOException.class, () -> TestGridFixtures.setTracked(registry, key, true));
        ControllerGrid changed = new ControllerGrid(controller(1), controller(2));
        registry.controllerValidated(changed);
        assertEquals(key, registry.getKey(changed));
        registry.removeController(controller(1));
        assertNull(registry.findIdentity(controller(1)));
        assertEquals(key, registry.findIdentity(controller(2)));
        assertThrows(IOException.class, registry::saveIfDirty);
        GridIdentityRegistry saved = worlds.registry(source);
        assertEquals(key, saved.findIdentity(controller(1)));
        assertFalse(TestGridFixtures.isTracked(saved, key));
        TestGridFixtures.setTracked(saved, key, true);
        assertTrue(TestGridFixtures.isTracked(worlds.registry(source), key));
    }

    @Test
    void storedCoordinatesRoundTripAcrossDimensionsAndIntegerLimits() throws Exception {
        File file = directory.resolve("coordinates.json")
            .toFile();
        GridIdentityRegistry registry = worlds.registry(file);
        DimensionalCoords first = new DimensionalCoords("world", Integer.MIN_VALUE, Integer.MAX_VALUE, -1);
        DimensionalCoords second = new DimensionalCoords("other-world", -1, 0, 1);
        StableKey key = resolve(registry, first, second);
        registry.saveIfDirty();
        GridIdentityRegistry restarted = worlds.registry(file);
        assertEquals(key, restarted.findIdentity(first));
        assertEquals(key, restarted.findIdentity(second));
    }

    @Test
    @SuppressWarnings("ReadWriteStringCanBeUsed") // These tests also run on Java 8, without Files.writeString.
    void missingSettingsFieldsUseDefaultsWithoutLosingControllerRecognition() throws Exception {
        Path file = directory.resolve("default-settings.json");
        StableKey key = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        String json = "{\"" + key
            + "\":{\"controllers\":[{\"dimensionId\":\"world\",\"x\":1,\"y\":0,\"z\":0}],\"settings\":{}}}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        GridIdentityRegistry registry = worlds.registry(file.toFile());
        assertEquals(key, registry.findIdentity(controller(1)));
        GridPersistentData data = registry.getPersistentData(key);
        assertNotNull(data);
        assertTrue(
            data.getSettings()
                .isDefault());
        TestGridFixtures.setTracked(registry, key, true);
        assertTrue(TestGridFixtures.isTracked(worlds.registry(file.toFile()), key));
    }

    @Test
    @SuppressWarnings("ReadWriteStringCanBeUsed") // Java 8 runtime compatibility.
    void currentDimensionEncodingSurvivesSaveAndMatchesHttp() throws Exception {
        Path file = directory.resolve("coordinates.json");
        StableKey key = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        String saved = "{\"" + key
            + "\":{\"controllers\":["
            + "{\"dimensionId\":\"-1\",\"x\":-32,\"y\":64,\"z\":120},"
            + "{\"dimensionId\":\"minecraft:overworld\",\"x\":-32,\"y\":64,\"z\":120}],\"settings\":{}}}";
        Files.write(file, saved.getBytes(StandardCharsets.UTF_8));
        DimensionalCoords legacy = new DimensionalCoords(-1, -32, 64, 120);
        DimensionalCoords modern = new DimensionalCoords("minecraft:overworld", -32, 64, 120);
        GridIdentityRegistry registry = worlds.registry(file.toFile());
        assertEquals(key, registry.findIdentity(legacy));
        assertEquals(key, registry.findIdentity(modern));
        TestGridFixtures.setTracked(registry, key, true);
        GridIdentityRegistry restarted = worlds.registry(file.toFile());
        assertEquals(key, restarted.findIdentity(legacy));
        assertEquals(key, restarted.findIdentity(modern));
        assertTrue(TestGridFixtures.isTracked(restarted, key));
        JsonObject http = new JsonParser().parse(
            GSONUtils.GSON_BUILDER.create()
                .toJson(modern))
            .getAsJsonObject();
        assertEquals(
            "minecraft:overworld",
            http.get("dimensionId")
                .getAsString());
        assertFalse(http.has("dimid"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "minecraft:overworld" })
    @SuppressWarnings("ReadWriteStringCanBeUsed") // Java 8 runtime compatibility.
    void releasedDimensionFieldPreservesIdentityAndSettingsInArchivedSource(String dimension) throws Exception {
        Path file = directory.resolve("released-identities.json");
        StableKey key = StableKey.parse("DtK3pQIgqQ-xyPjLOqQW9A");
        String document = "{\"" + key
            + "\":{\"controllers\":[{\"dimid\":\""
            + dimension
            + "\",\"x\":-643,\"y\":4,\"z\":-907}],\"settings\":{\"isTracked\":true}}}";
        Files.write(file, document.getBytes(StandardCharsets.UTF_8));

        GridIdentityRegistry registry = worlds.registry(file.toFile());
        DimensionalCoords controller = new DimensionalCoords(dimension, -643, 4, -907);
        assertEquals(key, registry.findIdentity(controller));
        assertTrue(TestGridFixtures.isTracked(registry, key));
        Path archived = file.resolveSibling("released-identities.json.old");
        assertFalse(Files.exists(file));
        assertEquals(document, new String(Files.readAllBytes(archived), StandardCharsets.UTF_8));

        registry.saveIfDirty();
        assertEquals(document, new String(Files.readAllBytes(archived), StandardCharsets.UTF_8));
        GridIdentityRegistry restarted = worlds.registry(file.toFile());
        assertEquals(key, restarted.findIdentity(controller));
        assertTrue(TestGridFixtures.isTracked(restarted, key));

        DimensionalCoords apiCoordinates = GSONUtils.GSON_BUILDER.create()
            .fromJson("{\"dimid\":\"" + dimension + "\",\"x\":-643,\"y\":4,\"z\":-907}", DimensionalCoords.class);
        assertNull(apiCoordinates.dimensionId(), "The storage alias must not affect API deserialization");
    }

    private static StableKey resolve(GridIdentityRegistry registry, DimensionalCoords... positions) {
        ControllerGrid grid = new ControllerGrid(positions);
        registry.controllerValidated(grid);
        return registry.getKey(grid);
    }

    private static DimensionalCoords controller(int x) {
        return new DimensionalCoords("world", x, 0, 0);
    }

    private static final class ControllerGrid extends TestGridFixtures.TestGrid {

        final Set<DimensionalCoords> controllers;
        AEControllerState state = AEControllerState.CONTROLLER_ONLINE;
        boolean mayReadControllers = true;

        ControllerGrid(DimensionalCoords... positions) {
            super(0, false, AEControllerState.CONTROLLER_ONLINE);
            controllers = new LinkedHashSet<>(Arrays.asList(positions));
        }

        @Override
        public @NotNull Set<DimensionalCoords> web$getControllers() {
            if (!mayReadControllers) throw new AssertionError("This operation must not discover controllers");
            return controllers;
        }

        @Override
        public AEControllerState web$getControllerState() {
            return state;
        }
    }
}
