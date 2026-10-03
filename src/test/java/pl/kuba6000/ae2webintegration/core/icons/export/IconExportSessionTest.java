package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.kuba6000.ae2webintegration.core.icons.ControlledPng;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconExportSessionTest {

    @TempDir
    Path directory;

    @Test
    void publishesExactAndBaselineIdentitiesAfterCompleteDiscoveryAndDrainsDeferredPixels() throws Exception {
        Candidate baseline = new Candidate(2);
        Candidate exact = new Candidate(1);
        exact.base = baseline;
        Source source = new Source(exact, new Candidate(1));
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(exact.key()));
            assertNotNull(pack.find(baseline.key()));
            assertEquals(0, pack.failureCount());
        }
        assertTrue(renderer.closed);
    }

    @Test
    void omittedNativeCandidatesDoNotPreventHealthyResourcesFromBeingPublished() throws Exception {
        Candidate badIdentity = new Candidate(1);
        badIdentity.identityFailure = new IllegalArgumentException("Broken identity");
        Candidate badBaseline = new Candidate(2);
        badBaseline.baselineFailure = new IllegalArgumentException("Broken baseline");
        Candidate badDraw = new Candidate(3);
        badDraw.drawFailure = new IllegalArgumentException("Broken model");
        Candidate healthy = new Candidate(4);
        Source source = new Source(badIdentity, badBaseline, badDraw, healthy);
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        try (IconPack pack = openPack()) {
            assertNull(pack.find(key(1)));
            assertNotNull(pack.find(key(2)));
            assertNull(pack.find(key(3)));
            assertNotNull(pack.find(key(4)));
            assertEquals(3, pack.failureCount());
        }
    }

    @Test
    void differentNativeIdentitiesWithTheSameStableKeyAbortWithoutPublishing() throws Exception {
        Candidate collision = new Candidate(2);
        collision.encodedIdentity = 1;
        Source source = new Source(new Candidate(1), collision);
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        assertEmptyDirectory();
        assertTrue(renderer.closed);
    }

    @Test
    void generationChangeDuringLastCapturePreventsPublication() throws Exception {
        Source source = new Source(new Candidate(1));
        Renderer renderer = new Renderer(source);
        renderer.invalidateSource = true;
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        assertEmptyDirectory();
        assertTrue(renderer.closed);
    }

    @Test
    void sourceInvalidationIsNotTreatedAsAnIsolatedDiscoveryFailure() throws Exception {
        Source source = new Source(new Candidate(1));
        source.discoveryFailure = new SourceChanged();
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        assertEmptyDirectory();
    }

    @Test
    void readbackFailureAbortsTheWholeExport() throws Exception {
        Source source = new Source(new Candidate(1));
        Renderer renderer = new Renderer(source);
        renderer.readbackFailure = new IllegalStateException("Readback is unavailable");
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        assertEmptyDirectory();
        assertTrue(renderer.closed);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void fatalNativeFailureEscapesInsteadOfBecomingAnOmittedIcon(boolean threadDeath) throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new OutOfMemoryError("Controlled fatal render");
        Candidate broken = new Candidate(1);
        broken.drawFailure = fatal;
        Source source = new Source(broken);
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        try {
            assertSame(fatal, assertThrows(fatal.getClass(), () -> awaitFinished(session)));
        } finally {
            session.cancel("Test cleanup after fatal error");
            awaitFinished(session);
        }
        assertEmptyDirectory();
    }

    @Test
    void cancellationWaitsForMetadataCleanupAndPreventsPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Source source = new Source(new Candidate(1));
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(directory, canceled -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Controlled metadata worker timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException(interrupted);
            }
            if (canceled.getAsBoolean()) throw new CancellationException();
            return metadata();
        }, source, renderer);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            session.cancel("User canceled");
            session.cancel("Repeated request");
            assertTrue(renderer.closed);
            assertFalse(session.tick());
        } finally {
            release.countDown();
            session.cancel("Test cleanup");
            awaitFinished(session);
        }
        assertEmptyDirectory();
    }

    @Test
    void asynchronousInitializationFailureSettlesAndReleasesNativeResources() throws Exception {
        Source source = new Source(new Candidate(1));
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(directory, canceled -> {
            throw new IOException("Missing resource pack");
        }, source, renderer);
        awaitFinished(session);
        assertTrue(renderer.closed);
        assertEmptyDirectory();
    }

    @Test
    void nativeReadinessDefersDiscoveryAndCaptureUntilTheCatalogueIsAvailable() throws Exception {
        Source source = new Source(new Candidate(1));
        source.available = false;
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!source.waitingReported) {
            assertFalse(session.tick());
            assertTrue(System.nanoTime() < deadline, "Native readiness was not polled");
            Thread.sleep(1);
        }
        source.available = true;
        awaitFinished(session);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(key(1)));
        }
    }

    @Test
    void captureRemainsBoundedAcrossMultipleBatchesAndPublishedPacksSurviveLateCancellation() throws Exception {
        Candidate[] candidates = new Candidate[PackExportWriter.MAX_CAPTURES_PER_BATCH + 7];
        for (int index = 0; index < candidates.length; index++) candidates[index] = new Candidate(index);
        Source source = new Source(candidates);
        Renderer renderer = new Renderer(source);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer);
        awaitFinished(session);
        session.cancel("Late cancellation");
        assertTrue(session.tick());
        try (IconPack pack = openPack()) {
            for (Candidate candidate : candidates) assertNotNull(pack.find(candidate.key()));
        }
    }

    @Test
    void slowEncodingAppliesBackpressureWithoutLosingRemainingResources() throws Exception {
        Candidate[] candidates = new Candidate[10_000];
        for (int index = 0; index < candidates.length; index++) candidates[index] = new Candidate(index);
        Source source = new Source(candidates);
        Renderer renderer = new Renderer(source);
        CountDownLatch encoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ControlledPng ignored = new ControlledPng((image, delegate) -> {
            encoding.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Controlled PNG timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException(interrupted);
            }
            delegate.run();
        })) {
            IconExportSession<Candidate> session = new IconExportSession<>(
                directory,
                canceled -> metadata(),
                source,
                renderer);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (encoding.getCount() != 0) {
                    assertFalse(session.tick());
                    assertTrue(System.nanoTime() < deadline, "No atlas reached the encoder");
                    Thread.sleep(1);
                }
                // Held encoding must prevent the producer from rendering the entire remaining catalogue.
                for (int frame = 0; frame < 100; frame++) assertFalse(session.tick());
                assertTrue(renderer.rendered < candidates.length, "Producer ignored blocked output capacity");
                release.countDown();
                awaitFinished(session);
                try (IconPack pack = openPack()) {
                    for (Candidate candidate : candidates) assertNotNull(pack.find(candidate.key()));
                }
            } finally {
                release.countDown();
                session.cancel("Test cleanup");
                awaitFinished(session);
            }
        }
    }

    private void assertEmptyDirectory() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        }
    }

    private static StableKey key(int identity) {
        return StableKey.create(sink -> sink.putInt(identity));
    }

    private IconPack openPack() throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            return IconPack.open(
                paths.filter(
                    path -> path.toString()
                        .endsWith(".ae2wi-icons"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No published pack")),
                "test",
                "loader",
                "identity",
                "base");
        }
    }

    private static void awaitFinished(IconExportSession<?> session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!session.tick()) {
            assertTrue(System.nanoTime() < deadline, "Export did not settle: " + session.status());
            Thread.sleep(1);
        }
    }

    private static IconPack.Metadata metadata() {
        return new IconPack.Metadata(
            "test",
            "loader",
            "identity",
            "base",
            "generator",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
    }

    private static final class Candidate implements IIconCandidate<Candidate> {

        final int identity;
        int encodedIdentity;
        @Nullable
        Candidate base;
        @Nullable
        RuntimeException identityFailure;
        @Nullable
        RuntimeException baselineFailure;
        @Nullable
        Throwable drawFailure;

        Candidate(int identity) {
            this.identity = identity;
            this.encodedIdentity = identity;
        }

        public @NotNull StableKey key() {
            if (identityFailure != null) throw identityFailure;
            return IconExportSessionTest.key(encodedIdentity);
        }

        public @Nullable Candidate baseline() {
            if (baselineFailure != null) throw baselineFailure;
            return base;
        }

        public boolean sameIdentity(@NotNull Candidate other) {
            return identity == other.identity;
        }

        public @NotNull String context() {
            return "candidate/" + identity;
        }
    }

    private static final class Source implements IIconSource<Candidate> {

        final Iterator<Candidate> candidates;
        boolean done;
        boolean available = true;
        boolean waitingReported;
        boolean invalidated;
        @Nullable
        RuntimeException discoveryFailure;

        Source(Candidate... candidates) {
            this.candidates = Arrays.asList(candidates)
                .iterator();
        }

        public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
            waitingReported = !available;
            return available ? null : "Native catalogue is loading";
        }

        public @Nullable Candidate next() {
            if (!available) throw new IllegalStateException("Discovery started before readiness");
            if (discoveryFailure != null) throw discoveryFailure;
            if (candidates.hasNext()) return candidates.next();
            done = true;
            return null;
        }

        public boolean done() {
            return done;
        }

        public void verify() {
            if (invalidated) throw new SourceChanged();
        }

        public @NotNull String context() {
            return "source";
        }

        public @NotNull Map<String, Long> counts() {
            return new HashMap<>();
        }

        public @NotNull List<String> sources() {
            return Collections.singletonList("native");
        }

        public @NotNull String note() {
            return "";
        }

        public @NotNull Throwable failureCause(@NotNull Throwable failure) {
            if (failure instanceof SourceChanged changed) throw changed;
            return failure;
        }
    }

    private static final class Renderer implements IIconCapture<Candidate> {

        final Source source;
        final List<PackExportWriter.Capture> pending = new ArrayList<>();
        boolean closed;
        int rendered;
        boolean invalidateSource;
        @Nullable
        RuntimeException readbackFailure;

        Renderer(Source source) {
            this.source = source;
        }

        public void capture(@NotNull Candidate candidate, @NotNull StableKey key,
            @NotNull List<PackExportWriter.Capture> captures) throws RenderFailure {
            if (!source.done()) throw new IllegalStateException("Native identity changed before discovery finished");
            if (closed) throw new IllegalStateException("Renderer is closed");
            if (candidate.drawFailure != null) throw new RenderFailure(candidate.drawFailure);
            if (pending.size() >= PackExportWriter.MAX_CAPTURES_PER_BATCH)
                throw new IllegalStateException("Capture memory budget exceeded");
            int[] pixels = new int[4096];
            Arrays.fill(pixels, 0xff000000 | candidate.identity);
            pending.add(new PackExportWriter.Capture(key, pixels));
            rendered++;
        }

        public void drain(@NotNull List<PackExportWriter.Capture> captures) {
            if (readbackFailure != null) throw readbackFailure;
            captures.addAll(pending);
            pending.clear();
            if (invalidateSource) source.invalidated = true;
        }

        public void statistics(@NotNull Map<String, Long> counts) {}

        public void close() {
            closed = true;
        }
    }

    private static final class SourceChanged extends RuntimeException {
    }
}
