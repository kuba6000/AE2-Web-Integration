package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static pl.kuba6000.ae2webintegration.core.CoreEngine.GRID_IDENTITIES;
import static pl.kuba6000.ae2webintegration.core.TestGridFixtures.OWNER_ID;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_CONFLICT;
import static pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState.CONTROLLER_ONLINE;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.CraftingPlanRegistry;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;

@SuppressWarnings("PMD.AvoidMagicNumbers")
class GridIdentityLifecycleTest extends GridTestScope {

    @Test
    void failedWorldImportDoesNotPublishPreviousWorldBindings() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(81);
        TestGridFixtures.track(grid);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        File invalidSave = new File(gridSave, "invalid-save");
        Path invalidFile = new File(invalidSave, "ae2webintegration/grid-identities.json").toPath();
        Files.createDirectories(invalidFile.getParent());
        byte[] invalid = "{".getBytes(UTF_8);
        Files.write(invalidFile, invalid);

        assertThrows(IOException.class, () -> worlds.initialize(invalidSave));
        assertFalse(GRID_IDENTITIES.isInitialized());
        assertNull(GRID_IDENTITIES.getKey(grid));
        assertFalse(TestGridFixtures.isTracked(GRID_IDENTITIES, key));
        assertArrayEquals(invalid, Files.readAllBytes(invalidFile));

        worlds.initialize(gridSave);
        GRID_IDENTITIES.controllerValidated(grid);
        GridAccess.list(TestGridFixtures.ae(grid));
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, key));
    }

    @Test
    void conflictHidesCachedAccessUntilTheControllerIsValidAgain() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(83);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        TestGridFixtures.setTracked(GRID_IDENTITIES, key, true);
        grid.controllerState(CONTROLLER_CONFLICT);
        GRID_IDENTITIES.controllerValidated(grid);

        assertNull(GRID_IDENTITIES.getKey(grid));
        assertTrue(
            GridAccess.list(TestGridFixtures.ae(grid))
                .isEmpty());
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, key));

        grid.controllerState(CONTROLLER_ONLINE);
        GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
        assertEquals(
            key,
            GridAccess.list(TestGridFixtures.ae(grid))
                .get(0)
                .key());
    }

    @Test
    void validatedControllerChangesPersistWhileChannelsAreRebuilding() throws Exception {
        Set<DimensionalCoords> controllers = new LinkedHashSet<>();
        controllers.add(new DimensionalCoords("world", 81, 0, 0));
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(81, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                return controllers;
            }
        };
        TestGridFixtures.track(grid);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        controllers.add(new DimensionalCoords("world", 82, 0, 0));
        grid.booting();
        GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, key));

        DimensionalCoords removed = new DimensionalCoords("world", 81, 0, 0);
        controllers.remove(removed);
        GRID_IDENTITIES.controllerRemoved(removed);
        GRID_IDENTITIES.controllerValidated(grid);
        GRID_IDENTITIES.saveIfDirty();
        worlds.initialize(gridSave);
        assertNull(GRID_IDENTITIES.getKey(grid));
        GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, key));
    }

    @Test
    void keyAndAccessReadsNeverDiscoverControllerMembership() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(81, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                reads.incrementAndGet();
                return super.web$getControllers();
            }
        };
        assertNull(GRID_IDENTITIES.getKey(grid));
        assertTrue(
            GridAccess.list(TestGridFixtures.ae(grid))
                .isEmpty());
        assertEquals(0, reads.get());
        assertNull(
            worlds.registry(TestGridFixtures.Worlds.source(gridSave))
                .findIdentity(grid.position()));

        GRID_IDENTITIES.controllerValidated(grid);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        reads.set(0);
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
        assertEquals(
            key,
            GridAccess.list(TestGridFixtures.ae(grid))
                .get(0)
                .key());
        assertEquals(0, reads.get());
    }

    @Test
    void conflictCallbacksDoNotInspectControllersOrRewriteSavedIdentity() throws Exception {
        boolean[] mayRead = { true };
        TestGridFixtures.TestGrid grid = new TestGridFixtures.TestGrid(82, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                if (!mayRead[0]) throw new AssertionError("Conflicted membership must not be inspected");
                return super.web$getControllers();
            }
        };
        TestGridFixtures.track(grid);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);

        mayRead[0] = false;
        grid.controllerState(CONTROLLER_CONFLICT);
        GRID_IDENTITIES.controllerValidated(grid);
        assertNull(GRID_IDENTITIES.getKey(grid));
        assertEquals(
            key,
            worlds.registry(TestGridFixtures.Worlds.source(gridSave))
                .findIdentity(grid.position()));
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, key));

        grid.controllerState(CONTROLLER_ONLINE);
        assertEquals(key, GRID_IDENTITIES.getKey(grid), "conflict reads must not erase the retained binding");
    }

    @Test
    void firstValidatedSplitKeepsIdentityWithoutWaitingForOtherControllersOrChannels() throws Exception {
        TestGridFixtures.TestGrid first = new TestGridFixtures.TestGrid(1, true, CONTROLLER_ONLINE);
        TestGridFixtures.TestGrid second = new TestGridFixtures.TestGrid(2, false, CONTROLLER_ONLINE);
        TestGridFixtures.TestGrid joined = new TestGridFixtures.TestGrid(1, false, CONTROLLER_ONLINE) {

            @Override
            public @NotNull Set<DimensionalCoords> web$getControllers() {
                Set<DimensionalCoords> result = new LinkedHashSet<>(first.web$getControllers());
                result.addAll(second.web$getControllers());
                result.add(new DimensionalCoords("world", 3, 0, 0));
                return result;
            }
        };
        TestGridFixtures.track(joined);
        StableKey original = GRID_IDENTITIES.getKey(joined);
        assertNotNull(original);
        GRID_IDENTITIES.controllerValidated(first);
        assertEquals(original, GRID_IDENTITIES.getKey(first));
        assertNull(GRID_IDENTITIES.getKey(second));
        GRID_IDENTITIES.controllerValidated(second);
        StableKey secondKey = GRID_IDENTITIES.getKey(second);
        assertNotNull(secondKey);
        assertNotEquals(original, secondKey);
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, original));
        assertFalse(TestGridFixtures.isTracked(GRID_IDENTITIES, secondKey));
        assertEquals(
            1,
            GridAccess.list(TestGridFixtures.ae(first, second))
                .size());
    }

    @Test
    void unobservedDefaultGridKeepsPendingPlanUntilConfirmedRemoval() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(51);
        GridAccess.list(TestGridFixtures.ae(grid));
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        CompletableFuture<IAECraftingJob> plan = new CompletableFuture<>();
        int id = GridData.getOrCreate(key)
            .addJob(plan, TestGridFixtures.planOutput(), new CraftingOptions(false, false), false);

        GridAccess.list(TestGridFixtures.ae());
        GridAccess.list(TestGridFixtures.ae(grid));
        CraftingPlanRegistry.Entry entry = GridData.getOrCreate(key)
            .getJob(id);
        assertNotNull(entry);
        assertSame(plan, entry.plan);
        assertFalse(plan.isCancelled());
        GRID_IDENTITIES.saveIfDirty();
        assertNotNull(
            worlds.registry(TestGridFixtures.Worlds.source(gridSave))
                .findIdentity(grid.position()));

        GridAccess.list(TestGridFixtures.ae());
        GRID_IDENTITIES.controllerRemoved(grid.position());
        assertTrue(plan.isCancelled());
    }

    @Test
    void snapshotExplainsCurrentOwnershipOfTheValidatedIdentity() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1, 42);
        List<GridAccess.View> views = GridAccess.list(TestGridFixtures.ae(grid));
        assertEquals(1, views.size());
        GridAccess.View view = views.get(0);
        assertSame(grid, view.grid());
        assertNotNull(view.key());
        assertEquals(view.key(), GRID_IDENTITIES.getKey(grid));
        assertEquals(
            2,
            view.grid()
                .web$getPermissions()
                .size());
        assertTrue(view.allows(TestGridFixtures.principal(42)));
        assertTrue(view.allows(TestGridFixtures.principal(OWNER_ID)));
        assertFalse(view.allows(TestGridFixtures.principal(99)));
        GRID_IDENTITIES.saveIfDirty();
        assertNotNull(
            worlds.registry(TestGridFixtures.Worlds.source(gridSave))
                .findIdentity(grid.position()));
        worlds.initialize(gridSave);
        GRID_IDENTITIES.controllerValidated(grid);
        assertEquals(
            view.key(),
            GridAccess.list(TestGridFixtures.ae(grid))
                .get(0)
                .key());
        assertFalse(TestGridFixtures.isTracked(GRID_IDENTITIES, view.key()));
        grid.withoutSources();
        assertFalse(view.allows(TestGridFixtures.principal(42)), "authorization reads current sources");
        assertFalse(
            GridAccess.list(TestGridFixtures.ae(grid))
                .get(0)
                .allows(TestGridFixtures.principal(42)));
    }

    @Test
    void presentationOwnerDoesNotGrantAccessAndControllerlessGridIsUnavailable() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1)
            .withoutSources();
        GridAccess.View view = GridAccess.list(TestGridFixtures.ae(grid))
            .get(0);
        PlayerIdentity owner = assertInstanceOf(
            PlayerIdentity.class,
            view.grid()
                .web$getRepresentativeOwner());
        assertNotNull(owner);
        assertFalse(view.allows(WebPrincipal.forPlayer(owner)));
        assertTrue(view.allows(WebPrincipal.admin()));
        grid.noController();
        assertTrue(
            GridAccess.list(TestGridFixtures.ae(grid))
                .isEmpty());
        assertNull(GRID_IDENTITIES.getKey(grid));
    }

    @Test
    void physicalControllerRemovalRetiresTheKeyAndRebuildingGetsANewIdentity() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        GridAccess.list(TestGridFixtures.ae(grid));
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        TestGridFixtures.setTracked(GRID_IDENTITIES, key, true);
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
        GRID_IDENTITIES.controllerRemoved(grid.position());
        assertNull(GRID_IDENTITIES.getKey(grid));
        assertNull(GRID_IDENTITIES.getGrid(key));
        assertFalse(TestGridFixtures.isTracked(GRID_IDENTITIES, key));
        assertNotEquals(
            key,
            GridAccess.list(TestGridFixtures.ae(TestGridFixtures.grid(1)))
                .get(0)
                .key());
        assertFalse(
            TestGridFixtures.isTracked(GRID_IDENTITIES, key),
            "rebuilding the removed last controller cannot revive old settings");
    }

    @Test
    void reopeningASaveRestoresSettingsWhileAnotherSaveDoesNotInheritThem() throws Exception {
        TestGridFixtures.TestGrid grid = TestGridFixtures.grid(1);
        TestGridFixtures.track(grid);
        StableKey key = GRID_IDENTITIES.getKey(grid);
        assertNotNull(key);
        GRID_IDENTITIES.clear();
        worlds.initialize(new File(gridSave, "other-save"));
        GridAccess.list(TestGridFixtures.ae(grid));
        assertFalse(TestGridFixtures.isTracked(GRID_IDENTITIES, key));
        worlds.initialize(gridSave);
        assertTrue(TestGridFixtures.isTracked(GRID_IDENTITIES, key));
        assertNull(GRID_IDENTITIES.getKey(grid));
        GRID_IDENTITIES.controllerValidated(grid);
        GridAccess.list(TestGridFixtures.ae(grid));
        assertEquals(key, GRID_IDENTITIES.getKey(grid));
    }
}
