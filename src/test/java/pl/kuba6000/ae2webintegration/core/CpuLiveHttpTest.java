package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.bsideup.jabel.Desugar;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.tracking.AEMixinCallbacks;

/** The live CPU endpoints through the real engine, router, native hooks and server tick. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CpuLiveHttpTest {

    @TempDir
    File root;

    private final CpuFixtures.Resource iron = new CpuFixtures.Resource("iron");
    private final CpuFixtures.Resource gear = new CpuFixtures.Resource("gear");
    private final CpuFixtures.PausableCpu cpu = new CpuFixtures.PausableCpu(1);
    private final CpuFixtures.Grid grid = new CpuFixtures.Grid(9301, cpu);
    private final ScheduledExecutorService game = Executors.newSingleThreadScheduledExecutor();
    private IAE previousAe;
    private int port;
    private String base;
    private StableKey gridKey;

    @BeforeEach
    void startEngine() throws Exception {
        previousAe = AE2Controller.AE2Interface;
        CoreEngine.init(new Platform(root));
        Config.INSTANCE.general.allowNoPasswordOnLocalhost = true;
        Config.INSTANCE.general.checkForUpdates = false;
        Config.INSTANCE.resourceTracking.activeIntervalMillis = 50;
        port = HttpServerTestFixture.startWorld();
        cpu.busy = true;
        cpu.output = gear;
        cpu.set(iron, 4, 2, 1)
            .set(gear, 0, 3, 0);
        gridKey = onGame(() -> {
            AE2Controller.AE2Interface = new CpuFixtures.Ae(grid);
            return TestGridFixtures.resolvedKey(grid);
        });
        game.scheduleAtFixedRate(CoreEngine::onServerTick, 0, 20, TimeUnit.MILLISECONDS);
        base = "http://127.0.0.1:" + port + "/api/grids/" + gridKey + "/cpus/" + cpu.key;
    }

    @AfterEach
    void stopEngine() throws Exception {
        game.shutdownNow();
        assertTrue(game.awaitTermination(3, TimeUnit.SECONDS));
        CoreEngine.onServerStopped();
        AE2Controller.AE2Interface = previousAe;
        AE2JobTracker.clearActiveJobs();
    }

    private <T> T onGame(Callable<T> task) throws Exception {
        return game.submit(task)
            .get(3, TimeUnit.SECONDS);
    }

    private void onGame(Runnable task) throws Exception {
        game.submit(task)
            .get(3, TimeUnit.SECONDS);
    }

    @Test
    void nativeRequesterFallbackRoundTripsThroughListAndDetailAndYieldsToTrackedSources() throws Exception {
        String listUrl = "http://127.0.0.1:" + port + "/api/grids/" + gridKey + "/cpus";
        onGame(() -> { cpu.nativeRequesterName = "Native crafter"; });
        for (JsonObject data : Arrays.asList(
            json(listUrl).getAsJsonObject("data")
                .getAsJsonObject(cpu.key.toString()),
            json(base).getAsJsonObject("data"))) {
            assertEquals(
                "Native crafter",
                data.get("nativeRequesterName")
                    .getAsString());
            assertEquals(
                0,
                data.getAsJsonArray("requesters")
                    .size());
        }
        onGame(() -> TestGridFixtures.track(grid));
        for (CraftingRequester requester : Arrays.asList(
            CraftingRequester.player(TestGridFixtures.playerIdentity(7)),
            CraftingRequester.web(),
            CraftingRequester.automation())) {
            onGame(() -> AE2JobTracker.addJob(cpu, grid, false, requester));
            for (JsonObject data : Arrays.asList(
                json(listUrl).getAsJsonObject("data")
                    .getAsJsonObject(cpu.key.toString()),
                json(base).getAsJsonObject("data"))) {
                assertTrue(
                    data.get("nativeRequesterName")
                        .isJsonNull());
                assertEquals(
                    requester.source()
                        .name(),
                    data.getAsJsonArray("requesters")
                        .get(0)
                        .getAsJsonObject()
                        .get("source")
                        .getAsString());
            }
        }
        onGame(() -> { cpu.busy = false; });
        for (JsonObject data : Arrays.asList(
            json(listUrl).getAsJsonObject("data")
                .getAsJsonObject(cpu.key.toString()),
            json(base).getAsJsonObject("data"))) {
            assertTrue(
                data.get("nativeRequesterName")
                    .isJsonNull());
            assertEquals(
                0,
                data.getAsJsonArray("requesters")
                    .size());
        }
        onGame(() -> {
            AE2JobTracker.clearActiveJobs();
            cpu.busy = true;
            cpu.nativeRequesterName = null;
        });
        for (JsonObject data : Arrays.asList(
            json(listUrl).getAsJsonObject("data")
                .getAsJsonObject(cpu.key.toString()),
            json(base).getAsJsonObject("data"))) {
            assertTrue(
                data.get("nativeRequesterName")
                    .isJsonNull());
            assertEquals(
                0,
                data.getAsJsonArray("requesters")
                    .size());
        }
    }

    @Test
    void nativeRequesterNameChangesReachAnExistingSseViewer() throws Exception {
        onGame(() -> { cpu.nativeRequesterName = "First crafter"; });
        JsonObject live = json(base + "?live=true");
        assertEquals(
            "First crafter",
            live.getAsJsonObject("data")
                .get("nativeRequesterName")
                .getAsString());
        String token = live.get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        stream.setReadTimeout(3000);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            onGame(() -> {
                cpu.nativeRequesterName = "Second crafter";
                AEMixinCallbacks.INSTANCE.craftingStatusPostedUpdate(cpu, iron);
            });
            CpuFixtures.Event event = CpuFixtures.readEvent(input);
            assertEquals("delta", event.name());
            assertEquals(
                "Second crafter",
                event.data()
                    .getAsJsonObject("cpu")
                    .get("nativeRequesterName")
                    .getAsString());
        } finally {
            stream.disconnect();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "complete", "cancel", "replace", "start", "merge" })
    void plainCpuReadsReleaseTheirJobIdentitiesWithoutAnotherRead(String boundary) throws Exception {
        for (int job = 0; job < 3; job++) {
            CpuFixtures.Resource row = new CpuFixtures.Resource("job-" + job);
            onGame(() -> {
                cpu.set(row, 1, 0, 0);
                AE2Controller.itemIdentities.remember(grid, iron);
            });
            json(base);
            WeakReference<IAEKey> rowIdentity = onGame(
                () -> new WeakReference<>(AE2Controller.itemIdentities.resolve(row.key())));
            WeakReference<IAEKey> outputIdentity = onGame(
                () -> new WeakReference<>(AE2Controller.itemIdentities.resolve(gear.key())));
            assertNotNull(rowIdentity.get());
            assertNotNull(outputIdentity.get());
            System.gc();
            assertNotNull(
                onGame(() -> AE2Controller.itemIdentities.resolve(row.key())),
                "The current HTTP job remains actionable");
            onGame(() -> {
                switch (boundary) {
                    case "complete" -> AEMixinCallbacks.INSTANCE.jobCompleted(grid, cpu);
                    case "cancel" -> AEMixinCallbacks.INSTANCE.jobCancelled(grid, cpu);
                    case "replace" -> AEMixinCallbacks.INSTANCE.cpuStateReplaced(cpu);
                    case "start" -> AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, false, false, null);
                    case "merge" -> AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, false, null);
                    default -> throw new AssertionError(boundary);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((rowIdentity.get() != null || outputIdentity.get() != null) && System.nanoTime() < deadline) {
                System.gc();
                onGame(() -> AE2Controller.itemIdentities.cleanUp());
                Thread.sleep(10);
            }
            assertNull(rowIdentity.get(), "The job boundary must release the last HTTP resource snapshot");
            assertNull(outputIdentity.get(), "The job boundary must release its output identity");
            assertNotNull(
                onGame(() -> AE2Controller.itemIdentities.resolve(iron.key())),
                "The grid keeps its independent ownership");
            onGame(() -> cpu.work.remove(row));
        }
    }

    @Test
    void aListOnlyOutputRemainsActionableUntilItsJobEnds() throws Exception {
        String cpus = "http://127.0.0.1:" + port + "/api/grids/" + gridKey + "/cpus";
        json(cpus);
        WeakReference<IAEKey> identity = onGame(
            () -> new WeakReference<>(AE2Controller.itemIdentities.resolve(gear.key())));
        assertNotNull(identity.get());
        System.gc();
        assertNotNull(onGame(() -> AE2Controller.itemIdentities.resolve(gear.key())));
        onGame(() -> AEMixinCallbacks.INSTANCE.jobCompleted(grid, cpu));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (identity.get() != null && System.nanoTime() < deadline) {
            System.gc();
            onGame(() -> AE2Controller.itemIdentities.cleanUp());
            Thread.sleep(10);
        }
        assertNull(identity.get(), "A list-only output must not accumulate on a surviving grid or CPU");
    }

    @Test
    void liveSnapshotCarriesAViewAndSubscriptionWhilePlainReadsStayUnchanged() throws Exception {
        JsonObject plain = json(base);
        assertTrue(
            plain.get("view")
                .isJsonNull());
        assertTrue(
            plain.get("subscription")
                .isJsonNull());
        JsonObject live = json(base + "?live=true");
        assertEquals(
            36,
            live.get("subscription")
                .getAsString()
                .length());
        assertNotNull(live.getAsJsonObject("view"));
        assertEquals(
            plain.getAsJsonObject("data"),
            live.getAsJsonObject("data"),
            "A live snapshot is the same response as a plain read");
        JsonObject first = live.getAsJsonObject("data")
            .getAsJsonArray("items")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            iron.key()
                .toString(),
            first.get("itemKey")
                .getAsString());
        assertFalse(
            first.get("crafting")
                .getAsBoolean());
        assertEquals(
            64,
            live.getAsJsonObject("data")
                .get("size")
                .getAsLong());
    }

    @Test
    void streamDeliversNativeNotificationsAndTheLeaseCanBeRenewed() throws Exception {
        JsonObject live = json(base + "?live=true");
        String token = live.get("subscription")
            .getAsString();
        assertEquals(401, status(base + "/events?subscription=" + token, "GET", true, "Bearer invalid"));
        assertEquals(400, status(base + "/events?subscription=not-a-token", "GET", false, null));
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("text/event-stream; charset=UTF-8", stream.getContentType());
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            assertEquals(403, status(base + "/events?subscription=" + token, "POST", false, null));
            assertEquals(200, status(base + "/events?subscription=" + token, "POST", true, null));
            assertEquals(400, status(base + "/events?subscription=" + UUID.randomUUID(), "POST", true, null));

            onGame(() -> {
                cpu.set(iron, 1, 2, 3);
                AEMixinCallbacks.INSTANCE.craftingStatusPostedUpdate(cpu, iron);
            });
            JsonObject delta = CpuFixtures.readEvent(input)
                .data();
            JsonObject update = delta.getAsJsonArray("updates")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                1,
                update.get("active")
                    .getAsLong());
            assertEquals(
                3,
                update.get("stored")
                    .getAsLong());
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void inventoryNotificationUpdatesStoredCountsWithoutAdvancingCraftingMeasurements() throws Exception {
        onGame(() -> {
            TestGridFixtures.track(grid);
            AE2JobTracker.addJob(cpu, grid, false, CraftingRequester.automation());
            cpu.waiting.amounts.put(iron, 5L);
            AEMixinCallbacks.INSTANCE.craftingStatusPostedUpdate(cpu, iron);
        });
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            onGame(() -> {
                cpu.set(iron, 4, 2, 0);
                cpu.waiting.amounts.put(iron, 3L);
                AEMixinCallbacks.INSTANCE.cpuInventoryChanged(cpu, iron);
            });
            JsonObject row = CpuFixtures.readEvent(input)
                .data()
                .getAsJsonArray("updates")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                0,
                row.get("stored")
                    .getAsLong());
            assertEquals(
                0,
                row.get("craftedTotal")
                    .getAsLong());
            assertTrue(
                row.get("crafting")
                    .getAsBoolean());
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void webPauseAndResumeRefreshTheHeaderForAnExistingViewer() throws Exception {
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        stream.setReadTimeout(1000);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            int reads = onGame(() -> cpu.bulkReads);
            for (boolean paused : new boolean[] { true, false }) {
                HttpURLConnection mutation = connection(base + "/pause");
                mutation.setRequestMethod("POST");
                mutation.setRequestProperty("X-AE2-Request", "true");
                mutation.setRequestProperty("Content-Type", "application/json");
                mutation.setDoOutput(true);
                try {
                    try (OutputStream body = mutation.getOutputStream()) {
                        body.write(("{\"paused\":" + paused + "}").getBytes(StandardCharsets.UTF_8));
                    }
                    assertEquals(200, mutation.getResponseCode());
                } finally {
                    mutation.disconnect();
                }
                CpuFixtures.Event event = CpuFixtures.readEvent(input);
                assertEquals("delta", event.name());
                assertEquals(
                    paused,
                    event.data()
                        .getAsJsonObject("cpu")
                        .get("isPaused")
                        .getAsBoolean());
            }
            assertEquals(reads, onGame(() -> cpu.bulkReads));
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void nativeMetadataNotificationsPublishPauseAndSelectionWithoutResourceReads() throws Exception {
        onGame(() -> {
            AEMixinCallbacks.INSTANCE.cpuMetadataChanged(cpu);
            AEMixinCallbacks.INSTANCE.cpuStateReplaced(cpu);
        });
        assertEquals(0, onGame(() -> cpu.bulkReads));
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            int reads = onGame(() -> cpu.bulkReads);
            onGame(() -> {
                cpu.paused = true;
                cpu.selectionMode = CpuSelectionMode.AUTOMATION_ONLY;
                AEMixinCallbacks.INSTANCE.cpuMetadataChanged(cpu);
            });
            CpuFixtures.Event event = CpuFixtures.readEvent(input);
            assertEquals("delta", event.name());
            assertTrue(
                event.data()
                    .getAsJsonObject("cpu")
                    .get("isPaused")
                    .getAsBoolean());
            assertEquals(
                "AUTOMATION_ONLY",
                event.data()
                    .getAsJsonObject("cpu")
                    .get("selectionMode")
                    .getAsString());
            assertTrue(
                event.data()
                    .getAsJsonArray("updates")
                    .isEmpty());
            assertTrue(
                event.data()
                    .getAsJsonArray("definitions")
                    .isEmpty());
            assertEquals(reads, onGame(() -> cpu.bulkReads));
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void restoredStateInvalidatesTheViewWithoutChangingJobMeasurements() throws Exception {
        AE2JobTracker.JobTrackingInfo measured = onGame(() -> {
            TestGridFixtures.track(grid);
            AE2JobTracker.addJob(cpu, grid, false, CraftingRequester.automation());
            return AE2JobTracker.findActiveJob(cpu);
        });
        assertNotNull(measured);
        CpuFixtures.Resource restored = new CpuFixtures.Resource("restored");
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            onGame(() -> {
                cpu.work.clear();
                cpu.set(restored, 0, 7, 0);
                AEMixinCallbacks.INSTANCE.cpuStateReplaced(cpu);
            });
            assertEquals(
                "invalidate",
                CpuFixtures.readEvent(input)
                    .name());
            assertSame(measured, onGame(() -> AE2JobTracker.findActiveJob(cpu)));
        } finally {
            stream.disconnect();
        }
        JsonObject fresh = json(base + "?live=true");
        assertEquals(
            1,
            fresh.getAsJsonObject("data")
                .getAsJsonArray("items")
                .size());
        JsonObject row = fresh.getAsJsonObject("data")
            .getAsJsonArray("items")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            restored.key()
                .toString(),
            row.get("itemKey")
                .getAsString());
        assertEquals(
            7,
            row.get("pending")
                .getAsLong());
    }

    @Test
    void successfulPatternPushUpdatesPendingOutputsWithoutJobTrackingOrWaitingNotifications() throws Exception {
        onGame(() -> {
            assertNull(AE2JobTracker.findActiveJob(cpu));
            AEMixinCallbacks.INSTANCE.pushedPattern(cpu, null, () -> {
                throw new AssertionError("An unviewed, untracked CPU must not inspect pattern outputs");
            });
        });
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            onGame(() -> {
                AEMixinCallbacks.INSTANCE
                    .pushedPattern(cpu, null, () -> new IAEGenericStack[] { new CpuFixtures.Stack(gear, 1) });
                // Fake crafting changes pending work after the successful-push callback, without waiting insertion.
                cpu.set(gear, 0, 2, 0);
            });
            CpuFixtures.Event event = CpuFixtures.readEvent(input);
            assertEquals("delta", event.name());
            assertEquals(
                1,
                event.data()
                    .getAsJsonArray("updates")
                    .size());
            JsonObject row = event.data()
                .getAsJsonArray("updates")
                .get(0)
                .getAsJsonObject();
            assertEquals(
                gear.key()
                    .toString(),
                row.get("itemKey")
                    .getAsString());
            assertEquals(
                2,
                row.get("pending")
                    .getAsLong());
            assertEquals(
                0,
                row.get("craftedTotal")
                    .getAsLong());
            assertFalse(
                row.get("crafting")
                    .getAsBoolean());
            assertNull(onGame(() -> AE2JobTracker.findActiveJob(cpu)));
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void everyJobLifecycleNotificationInvalidatesTheStream() throws Exception {
        String[] hooks = { "started", "merged", "completed", "cancelled" };
        for (String hook : hooks) {
            String token = json(base + "?live=true").get("subscription")
                .getAsString();
            HttpURLConnection stream = connection(base + "/events?subscription=" + token);
            try (BufferedReader input = new BufferedReader(
                new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals(
                    "ready",
                    CpuFixtures.readEvent(input)
                        .name());
                onGame(() -> {
                    switch (hook) {
                        case "started":
                            AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, false, false, null);
                            break;
                        case "merged":
                            AEMixinCallbacks.INSTANCE.jobStarted(cpu, grid, true, false, null);
                            break;
                        case "completed":
                            AEMixinCallbacks.INSTANCE.jobCompleted(grid, cpu);
                            break;
                        default:
                            AEMixinCallbacks.INSTANCE.jobCancelled(grid, cpu);
                    }
                });
                assertEquals(
                    "invalidate",
                    CpuFixtures.readEvent(input)
                        .name(),
                    hook);
            } finally {
                stream.disconnect();
            }
        }
    }

    @Test
    void deniedGridInTheSameLoginCannotRetireItsAllowedLiveView() throws Exception {
        Config.INSTANCE.general.publicMode = true;
        CoreData.setPassword(
            TestGridFixtures.playerIdentity(TestGridFixtures.OWNER_ID),
            PasswordHelper.generateStrongPasswordHash("handoff-test"));
        CpuFixtures.Grid forbidden = new CpuFixtures.Grid(9302, new CpuFixtures.Cpu(2));
        forbidden.withoutSources();
        StableKey forbiddenKey = onGame(() -> {
            AE2Controller.AE2Interface = new CpuFixtures.Ae(grid, forbidden);
            return TestGridFixtures.resolvedKey(forbidden);
        });
        String host = "http://127.0.0.1:" + port;
        HttpURLConnection login = connection(host + "/api/auth/login");
        login.setRequestMethod("POST");
        login.setRequestProperty("Content-Type", "application/json");
        login.setRequestProperty("X-AE2-Request", "true");
        login.setDoOutput(true);
        try (OutputStream body = login.getOutputStream()) {
            body.write("{\"username\":\"Player7\",\"password\":\"handoff-test\"}".getBytes(StandardCharsets.UTF_8));
        }
        String authorization;
        try (InputStreamReader input = new InputStreamReader(login.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject data = new JsonParser().parse(input)
                .getAsJsonObject()
                .getAsJsonObject("data");
            assertFalse(
                data.get("isAdmin")
                    .getAsBoolean());
            authorization = "Bearer " + data.get("token")
                .getAsString();
        } finally {
            login.disconnect();
        }
        HttpURLConnection snapshot = connection(base + "?live=true");
        snapshot.setRequestProperty("Authorization", authorization);
        String token;
        try (InputStreamReader input = new InputStreamReader(snapshot.getInputStream(), StandardCharsets.UTF_8)) {
            token = new JsonParser().parse(input)
                .getAsJsonObject()
                .get("subscription")
                .getAsString();
        } finally {
            snapshot.disconnect();
        }
        String denied = host + "/api/grids/"
            + forbiddenKey
            + "/cpus/"
            + TestGridFixtures.key(2)
            + "?live=true&replace="
            + token;
        assertEquals(403, status(denied, "GET", false, authorization));
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        stream.setRequestProperty("Authorization", authorization);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void failedTargetDoesNotRetireThePreviousLiveView() throws Exception {
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        assertEquals(401, status(base + "?live=true&replace=" + token, "GET", true, "Bearer invalid"));
        String missing = "http://127.0.0.1:" + port
            + "/api/grids/"
            + gridKey
            + "/cpus/"
            + TestGridFixtures.key(77)
            + "?live=true&replace="
            + token;
        assertEquals(404, status(missing, "GET", false, null));
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
        } finally {
            stream.disconnect();
        }
    }

    @Test
    void replacementReservationEndsThePreviousOneAndBadInputsAreRejected() throws Exception {
        String first = json(base + "?live=true").get("subscription")
            .getAsString();
        JsonObject replacement = json(base + "?live=true&replace=" + first);
        assertNotNull(replacement.get("subscription"));
        assertEquals(400, status(base + "/events?subscription=" + first, "POST", true, null));
        assertEquals(400, status(base + "?live=true&replace=short", "GET", false, null));
        String missing = "http://127.0.0.1:" + port
            + "/api/grids/"
            + gridKey
            + "/cpus/"
            + TestGridFixtures.key(77)
            + "?live=true";
        assertEquals(404, status(missing, "GET", false, null));
    }

    @Test
    void stoppingTheServerEndsTheViewAndReleasesTheCpuState() throws Exception {
        String token = json(base + "?live=true").get("subscription")
            .getAsString();
        HttpURLConnection stream = connection(base + "/events?subscription=" + token);
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals(
                "ready",
                CpuFixtures.readEvent(input)
                    .name());
            assertNotNull(CoreEngine.getCpuLive());
            game.shutdownNow();
            assertTrue(game.awaitTermination(3, TimeUnit.SECONDS));
            CoreEngine.onServerStopped();
            assertNull(CoreEngine.getCpuLive());
            assertNull(CoreEngine.getCpuRegistry());
            try {
                String line;
                do {
                    line = input.readLine(); // Drain until the server closes the stream.
                } while (line != null);
            } catch (IOException closedConnection) {
                // Stopping may abort an in-flight HTTP chunk.
            }
        } finally {
            stream.disconnect();
        }
    }

    private static HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setReadTimeout(5000);
        return connection;
    }

    private static int status(String url, String method, boolean csrf, String authorization) throws Exception {
        HttpURLConnection connection = connection(url);
        connection.setRequestMethod(method);
        if (csrf) connection.setRequestProperty("X-AE2-Request", "true");
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static JsonObject json(String url) throws Exception {
        HttpURLConnection connection = connection(url);
        try (InputStreamReader input = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(input)
                .getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    @Desugar
    private record Platform(File root) implements IServerPlatform {

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return root;
        }

        @Override
        public ILegacyConfigProvider getLegacyConfig() {
            return null;
        }

        @Override
        public File getWorldDirectory() {
            return new File(root, "save");
        }

        @Override
        public @NotNull String getModVersion() {
            return "test";
        }

        @Override
        public @NotNull String getLoader() {
            return "forge";
        }

        @Override
        public @NotNull String getMinecraftVersion() {
            return "1.7.10";
        }

        @Override
        public @NotNull String getIconPackCompatibilityVersion() {
            return "test";
        }
    }
}
