package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridIdentityRegistryTest {

    @TempDir
    Path directory;

    @Test
    void defaultIdentitySurvivesRestartAndControllerChanges() throws Exception {
        File file = directory.resolve("defaults.json")
            .toFile();
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        StableKey key = resolve(registry, controller(2), controller(1));
        assertTrue(file.isFile());
        assertFalse(registry.isTracked(key));
        registry.removeController(controller(1));
        GridIdentityRegistry restarted = new GridIdentityRegistry(file);
        assertEquals(key, resolve(restarted, controller(3), controller(2)));
        assertEquals(key, new GridIdentityRegistry(file).findIdentity(controller(3)));
    }

    @Test
    void identicalCoordinatesInDifferentSavesReceiveIndependentIdentities() throws Exception {
        GridIdentityRegistry first = new GridIdentityRegistry(
            directory.resolve("first.json")
                .toFile());
        GridIdentityRegistry second = new GridIdentityRegistry(
            directory.resolve("second.json")
                .toFile());
        StableKey firstKey = resolve(first, controller(1));
        StableKey secondKey = resolve(second, controller(1));
        assertNotEquals(firstKey, secondKey);
        first.setTracked(firstKey, true);
        assertFalse(second.isTracked(secondKey));
    }

    @Test
    void firstValidatedPartKeepsTheKeyEvenWithFewerControllers() throws Exception {
        File file = directory.resolve("split.json")
            .toFile();
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        StableKey original = resolve(registry, controller(1), controller(2), controller(3));
        registry.setTracked(original, true);
        ControllerGrid first = new ControllerGrid(controller(1));
        ControllerGrid later = new ControllerGrid(controller(2), controller(3));
        registry.controllerValidated(first);
        registry.controllerValidated(later);
        assertEquals(original, registry.getKey(first));
        StableKey fresh = registry.getKey(later);
        assertNotNull(fresh);
        assertNotEquals(original, fresh);
        assertTrue(registry.isTracked(original));
        assertFalse(registry.isTracked(fresh));
        GridIdentityRegistry restarted = new GridIdentityRegistry(file);
        restarted.controllerValidated(later);
        restarted.controllerValidated(first);
        assertEquals(original, restarted.getKey(first));
        assertEquals(fresh, restarted.getKey(later));
    }

    @Test
    void successfulMergePrefersSettingsAndRetiresConsumedIdentities() throws Exception {
        File file = directory.resolve("merge.json")
            .toFile();
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        StableKey untracked = resolve(registry, controller(1));
        StableKey tracked = resolve(registry, controller(2));
        registry.setTracked(tracked, true);
        assertEquals(tracked, resolve(registry, controller(1), controller(2)));
        assertFalse(registry.containsIdentity(untracked));
        GridIdentityRegistry restarted = new GridIdentityRegistry(file);
        assertEquals(tracked, restarted.findIdentity(controller(1)));
        assertEquals(new GridSettingsData(true), restarted.getSettings(tracked));
    }

    @Test
    void equalSettingsMergeKeepsTheSameKeyRegardlessOfControllerOrder() throws Exception {
        File file = directory.resolve("tie.json")
            .toFile();
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        StableKey first = resolve(registry, controller(1));
        StableKey second = resolve(registry, controller(2));
        StableKey expected = first.toString()
            .compareTo(second.toString()) < 0 ? first : second;
        for (boolean reverse : Arrays.asList(false, true)) {
            Path copy = directory.resolve("tie-" + reverse + ".json");
            Files.copy(file.toPath(), copy);
            GridIdentityRegistry merged = new GridIdentityRegistry(copy.toFile());
            assertEquals(
                expected,
                reverse ? resolve(merged, controller(2), controller(1))
                    : resolve(merged, controller(1), controller(2)));
        }
    }

    @Test
    void conflictingBridgeDoesNotChangeSavedIdentitiesAndRepairRestoresBoth() throws Exception {
        Path file = directory.resolve("conflict.json");
        GridIdentityRegistry registry = new GridIdentityRegistry(file.toFile());
        ControllerGrid first = new ControllerGrid(controller(1));
        ControllerGrid second = new ControllerGrid(controller(2));
        registry.controllerValidated(first);
        registry.controllerValidated(second);
        StableKey firstKey = registry.getKey(first);
        assertNotNull(firstKey);
        StableKey secondKey = registry.getKey(second);
        assertNotNull(secondKey);
        registry.setTracked(firstKey, true);
        registry.setTracked(secondKey, true);
        byte[] before = Files.readAllBytes(file);

        first.controllers.add(controller(2));
        first.state = AEControllerState.CONTROLLER_CONFLICT;
        first.mayReadControllers = false;
        registry.controllerValidated(first);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(firstKey, registry.findIdentity(controller(1)));
        assertEquals(secondKey, registry.findIdentity(controller(2)));
        assertTrue(registry.isTracked(firstKey));
        assertTrue(registry.isTracked(secondKey));
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
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        ControllerGrid grid = new ControllerGrid(controller(1), controller(2));
        registry.controllerValidated(grid);
        StableKey key = registry.getKey(grid);
        assertNotNull(key);
        registry.setTracked(key, true);
        registry.controllerRemoved(controller(1));
        assertTrue(registry.containsIdentity(key));
        assertEquals(key, registry.findIdentity(controller(2)));
        registry.controllerRemoved(controller(2));
        assertFalse(registry.containsIdentity(key));
        assertNull(registry.getKey(grid));
        assertNotEquals(key, resolve(new GridIdentityRegistry(file), controller(1)));
    }

    @Test
    void UnobservedRecordsRemainSavedAndReadsDoNotDiscoverGrids() throws Exception {
        File file = directory.resolve("unobserved.json")
            .toFile();
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        StableKey absent = resolve(registry, controller(1));
        registry.setTracked(absent, true);
        ControllerGrid present = new ControllerGrid(controller(2));
        assertNull(registry.getKey(present));
        registry.controllerValidated(present);
        present.mayReadControllers = false;
        assertNotNull(registry.getKey(present));
        assertTrue(new GridIdentityRegistry(file).isTracked(absent));
        assertEquals(absent, new GridIdentityRegistry(file).findIdentity(controller(1)));
    }

    @Test
    void settingsDefaultsRemainPersistedAndUnchangedObservationsDoNotRewrite() throws Exception {
        Path file = directory.resolve("unchanged.json");
        GridIdentityRegistry registry = new GridIdentityRegistry(file.toFile());
        StableKey key = resolve(registry, controller(1));
        registry.setSettings(key, new GridSettingsData(true));
        registry.setSettings(key, new GridSettingsData());
        assertEquals(key, new GridIdentityRegistry(file.toFile()).findIdentity(controller(1)));
        assertFalse(new GridIdentityRegistry(file.toFile()).isTracked(key));
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000000000000L));
        FileTime before = Files.getLastModifiedTime(file);
        registry.setTracked(key, false);
        resolve(registry, controller(1));
        registry.removeController(controller(99));
        assertEquals(before, Files.getLastModifiedTime(file));
        assertThrows(IllegalArgumentException.class, () -> registry.setTracked(StableKey.random(), true));
    }

    @Test
    void failedWritesDoNotPublishSettingsMembershipOrRemoval() throws Exception {
        Path file = directory.resolve("failure.json");
        GridIdentityRegistry registry = new GridIdentityRegistry(file.toFile());
        StableKey key = resolve(registry, controller(1));
        byte[] original = Files.readAllBytes(file);
        Path backup = directory.resolve("saved.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        Path blocker = file.resolve("unrelated-file");
        Files.write(blocker, new byte[] { 1 });
        for (int operation = 0; operation < 3; operation++) {
            if (operation == 0) assertThrows(IOException.class, () -> registry.setTracked(key, true));
            else if (operation == 1) {
                ControllerGrid changed = new ControllerGrid(controller(1), controller(2));
                registry.controllerValidated(changed);
                assertNull(registry.getKey(changed));
            } else assertThrows(IOException.class, () -> registry.removeController(controller(1)));
            assertEquals(key, registry.findIdentity(controller(1)));
            assertNull(registry.findIdentity(controller(2)));
            assertFalse(registry.isTracked(key));
            assertArrayEquals(original, Files.readAllBytes(backup));
            assertArrayEquals(new byte[] { 1 }, Files.readAllBytes(blocker));
        }
        Files.delete(blocker);
        Files.delete(file);
        Files.move(backup, file);
        assertEquals(key, new GridIdentityRegistry(file.toFile()).findIdentity(controller(1)));
    }

    @Test
    void malformedFilesIncludingDuplicateKeysFailWithoutOverwritingTheirSource() throws Exception {
        String record = "\"" + StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA")
            + "\":{\"controllers\":[{\"dimid\":\"world\",\"x\":1,\"y\":0,\"z\":0}],\"settings\":{\"isTracked\":true}}";
        for (String invalid : Arrays.asList("", "null", "{", "{" + record + "," + record + "}")) {
            Path file = directory.resolve("invalid.json");
            byte[] original = invalid.getBytes(StandardCharsets.UTF_8);
            Files.write(file, original);
            assertThrows(IOException.class, () -> new GridIdentityRegistry(file.toFile()));
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test
    void storedCoordinatesRoundTripAcrossDimensionsAndIntegerLimits() throws Exception {
        File file = directory.resolve("coordinates.json")
            .toFile();
        GridIdentityRegistry registry = new GridIdentityRegistry(file);
        DimensionalCoords first = new DimensionalCoords("world", Integer.MIN_VALUE, Integer.MAX_VALUE, -1);
        DimensionalCoords second = new DimensionalCoords("other-world", -1, 0, 1);
        StableKey key = resolve(registry, first, second);
        GridIdentityRegistry restarted = new GridIdentityRegistry(file);
        assertEquals(key, restarted.findIdentity(first));
        assertEquals(key, restarted.findIdentity(second));
    }

    @Test
    @SuppressWarnings("ReadWriteStringCanBeUsed") // These tests also run on Java 8, without Files.writeString.
    void missingSettingsFieldsUseDefaultsWithoutLosingControllerRecognition() throws Exception {
        Path file = directory.resolve("default-settings.json");
        StableKey key = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        String json = "{\"" + key
            + "\":{\"controllers\":[{\"dimid\":\"world\",\"x\":1,\"y\":0,\"z\":0}],\"settings\":{}}}";
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        GridIdentityRegistry registry = new GridIdentityRegistry(file.toFile());
        assertEquals(key, registry.findIdentity(controller(1)));
        assertEquals(new GridSettingsData(), registry.getSettings(key));
        registry.setTracked(key, true);
        assertTrue(new GridIdentityRegistry(file.toFile()).isTracked(key));
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
