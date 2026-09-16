package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSessions;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridIdentityLifecycleTest extends GridTestScope {

    @Test
    void failedSaveLoadLeavesRegistryUnavailableUntilSuccessfulInitialization() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(81);
        TestGridFixtures.track(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        File invalidSave = new File(gridSave, "invalid-save");
        java.nio.file.Path invalidFile = new File(invalidSave, "ae2webintegration/grid-identities.json").toPath();
        java.nio.file.Files.createDirectories(invalidFile.getParent());
        byte[] invalid = "{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(invalidFile, invalid);

        assertThrows(java.io.IOException.class, () -> CoreEngine.GRID_IDENTITIES.initialize(invalidSave));
        assertFalse(CoreEngine.GRID_IDENTITIES.isInitialized());
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertFalse(CoreEngine.GRID_IDENTITIES.isTracked(key));
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
        assertThrows(IllegalStateException.class, () -> CoreEngine.GRID_IDENTITIES.setTracked(key, false));
        assertArrayEquals(invalid, java.nio.file.Files.readAllBytes(invalidFile));

        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(key));
    }

    @Test
    void conflictHidesCachedAccessUntilTheControllerIsValidAgain() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(83);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        CoreEngine.GRID_IDENTITIES.setTracked(key, true);
        grid.controllerState(AEControllerState.CONTROLLER_CONFLICT);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);

        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertTrue(
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .isEmpty());
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(key));

        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertEquals(
            key,
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .get(0)
                .key());
    }

    @Test
    void validatedControllerChangesPersistWhileChannelsAreRebuilding() throws Exception {
        Set<DimensionalCoords> controllers = new LinkedHashSet<>();
        controllers.add(new DimensionalCoords("world", 81, 0, 0));
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(81, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                return controllers;
            }
        };
        TestGridFixtures.track(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        controllers.add(new DimensionalCoords("world", 82, 0, 0));
        grid.booting();
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(key));

        DimensionalCoords removed = new DimensionalCoords("world", 81, 0, 0);
        controllers.remove(removed);
        CoreEngine.GRID_IDENTITIES.controllerRemoved(removed);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(key));
    }

    @Test
    void keyAndAccessReadsNeverDiscoverControllerMembership() throws Exception {
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(81, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                reads.incrementAndGet();
                return super.web$getControllers();
            }
        };
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertTrue(
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .isEmpty());
        assertEquals(0, reads.get());
        assertFalse(new File(gridSave, "ae2webintegration/grid-identities.json").exists());

        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        reads.set(0);
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertEquals(
            key,
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .get(0)
                .key());
        assertEquals(0, reads.get());
    }

    @Test
    void conflictCallbacksDoNotInspectControllersOrRewriteSavedIdentity() throws Exception {
        boolean[] mayRead = { true };
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(82, false, AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                if (!mayRead[0]) throw new AssertionError("Conflicted membership must not be inspected");
                return super.web$getControllers();
            }
        };
        TestGridFixtures.track(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        java.nio.file.Path file = new File(gridSave, "ae2webintegration/grid-identities.json").toPath();
        byte[] saved = java.nio.file.Files.readAllBytes(file);
        mayRead[0] = false;
        grid.controllerState(AEControllerState.CONTROLLER_CONFLICT);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertArrayEquals(saved, java.nio.file.Files.readAllBytes(file));
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(key));

        grid.controllerState(AEControllerState.CONTROLLER_ONLINE);
        assertEquals(
            key,
            CoreEngine.GRID_IDENTITIES.getKey(grid),
            "conflict reads must not erase the retained binding");
    }

    @Test
    void firstValidatedSplitKeepsIdentityWithoutWaitingForOtherControllersOrChannels() throws Exception {
        TestGridFixtures.TestGrid first = new TestGridFixtures.TestGrid(1, true, AEControllerState.CONTROLLER_ONLINE);
        TestGridFixtures.TestGrid second = new TestGridFixtures.TestGrid(2, false, AEControllerState.CONTROLLER_ONLINE);
        TestGridFixtures.TestGrid joined = new TestGridFixtures.TestGrid(
            1,
            false,
            AEControllerState.CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                Set<DimensionalCoords> result = new LinkedHashSet<>(first.web$getControllers());
                result.addAll(second.web$getControllers());
                result.add(new DimensionalCoords("world", 3, 0, 0));
                return result;
            }
        };
        TestGridFixtures.track(joined);
        StableKey original = CoreEngine.GRID_IDENTITIES.getKey(joined);
        assertNotNull(original);
        CoreEngine.GRID_IDENTITIES.controllerValidated(first);
        assertEquals(original, CoreEngine.GRID_IDENTITIES.getKey(first));
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(second));
        CoreEngine.GRID_IDENTITIES.controllerValidated(second);
        StableKey secondKey = CoreEngine.GRID_IDENTITIES.getKey(second);
        assertNotNull(secondKey);
        assertNotEquals(original, secondKey);
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(original));
        assertFalse(CoreEngine.GRID_IDENTITIES.isTracked(secondKey));
        assertEquals(
            1,
            GridAccessSessions.snapshot(TestGridFixtures.ae(first, second))
                .size());
    }

    @Test
    void unobservedDefaultGridKeepsPendingPlanUntilConfirmedRemoval() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(51);
        GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int id = GridData.getOrCreate(key)
            .addJob(plan);

        GridAccessSessions.snapshot(TestGridFixtures.ae());
        GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        assertSame(
            plan,
            GridData.getOrCreate(key)
                .getJob(id));
        assertFalse(plan.isCancelled());
        assertTrue(new File(gridSave, "ae2webintegration/grid-identities.json").exists());

        GridAccessSessions.snapshot(TestGridFixtures.ae());
        CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
        assertTrue(plan.isCancelled());
    }

    @Test
    void snapshotExplainsCurrentOwnershipOfTheValidatedIdentity() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1, 42);
        List<GridAccessSessions.View> views = GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        assertEquals(1, views.size());
        GridAccessSessions.View view = views.get(0);
        assertSame(grid, view.grid());
        assertNotNull(view.key());
        assertEquals(view.key(), CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertEquals(
            2,
            view.sources()
                .size());
        assertTrue(view.allows(TestGridFixtures.principal(42)));
        assertTrue(view.allows(TestGridFixtures.principal(TestGridFixtures.OWNER_ID)));
        assertFalse(view.allows(TestGridFixtures.principal(99)));
        assertTrue(new File(gridSave, "ae2webintegration/grid-identities.json").exists());
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(
            view.key(),
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .get(0)
                .key());
        assertFalse(CoreEngine.GRID_IDENTITIES.isTracked(view.key()));
        grid.withoutSources();
        assertTrue(view.allows(TestGridFixtures.principal(42)), "the completed view is a detached snapshot");
        assertFalse(
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .get(0)
                .allows(TestGridFixtures.principal(42)));
    }

    @Test
    void presentationOwnerDoesNotGrantAccessAndControllerlessGridIsUnavailable() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1)
            .withoutSources();
        GridAccessSessions.View view = GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
            .get(0);
        PlayerIdentity owner = assertInstanceOf(PlayerIdentity.class, view.owner());
        assertFalse(view.allows(WebPrincipal.forPlayer(owner)));
        assertTrue(view.allows(WebPrincipal.admin()));
        grid.noController();
        assertTrue(
            GridAccessSessions.snapshot(TestGridFixtures.ae(grid))
                .isEmpty());
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
    }

    @Test
    void physicalControllerRemovalRetiresTheKeyAndRebuildingGetsANewIdentity() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        CoreEngine.GRID_IDENTITIES.setTracked(key, true);
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
        GridAccessSessions.put(WebPrincipal.admin(), new GridAccess(Collections.singleton(key), 0));
        CoreEngine.GRID_IDENTITIES.controllerRemoved(grid.position());
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        assertNull(GridAccessSessions.get(WebPrincipal.admin()));
        assertFalse(CoreEngine.GRID_IDENTITIES.isTracked(key));
        assertNotEquals(
            key,
            GridAccessSessions.snapshot(TestGridFixtures.ae(TestGridFixtures.grid(1)))
                .get(0)
                .key());
        assertFalse(
            CoreEngine.GRID_IDENTITIES.isTracked(key),
            "rebuilding the removed last controller cannot revive old settings");
    }

    @Test
    void reopeningASaveRestoresSettingsWhileAnotherSaveDoesNotInheritThem() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        TestGridFixtures.track(grid);
        StableKey key = CoreEngine.GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        CoreEngine.GRID_IDENTITIES.clear();
        CoreEngine.GRID_IDENTITIES.initialize(new File(gridSave, "other-save"));
        GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        assertFalse(CoreEngine.GRID_IDENTITIES.isTracked(key));
        CoreEngine.GRID_IDENTITIES.initialize(gridSave);
        assertTrue(CoreEngine.GRID_IDENTITIES.isTracked(key));
        assertNull(CoreEngine.GRID_IDENTITIES.getKey(grid));
        CoreEngine.GRID_IDENTITIES.controllerValidated(grid);
        GridAccessSessions.snapshot(TestGridFixtures.ae(grid));
        assertEquals(key, CoreEngine.GRID_IDENTITIES.getKey(grid));
    }
}
