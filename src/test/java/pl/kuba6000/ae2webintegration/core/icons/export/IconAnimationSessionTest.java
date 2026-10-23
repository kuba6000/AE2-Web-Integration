package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconAnimationSessionTest {

    @TempDir
    Path directory;
    final AtomicLong clock = new AtomicLong(-10_000_000_000L);

    @Test
    void sampledPassesYieldThroughGapsAndUseDrawTimesRatherThanDeferredDrainTimes() throws Exception {
        Candidate candidate = new Candidate(1);
        Source source = new Source(candidate);
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> renderer.draws == 1);
        for (int count = 0; count < 50; count++) assertFalse(session.tick());
        assertEquals(1, renderer.draws);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(99));
        for (int count = 0; count < 50; count++) assertFalse(session.tick());
        assertEquals(1, renderer.draws);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        tickUntil(session, () -> renderer.draws == 2);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        tickUntil(session, () -> renderer.draws == 3);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        finish(session);
        assertEquals(3, renderer.draws);
        assertTrue(renderer.closed);
        assertTrue(renderer.animationClosed);
        try (IconPack pack = openPack()) {
            List<IconPack.Frame> frames = pack.animation(candidate.key());
            assertEquals(3, frames.size());
            assertEquals(
                100,
                frames.get(0)
                    .durationMillis());
            assertEquals(
                200,
                frames.get(1)
                    .durationMillis());
            assertEquals(
                200,
                frames.get(2)
                    .durationMillis());
            assertNotNull(pack.find(candidate.key()));
        }
    }

    @Test
    void gapStartsAfterDeferredReadbackHasDrained() throws Exception {
        Candidate candidate = new Candidate(1);
        Renderer renderer = new Renderer(clock);
        renderer.drainNanos = TimeUnit.MILLISECONDS.toNanos(200);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer,
            true,
            clock::get);
        try {
            tickUntil(session, () -> renderer.draws == 1);
            int drained = renderer.drains;
            tickUntil(session, () -> renderer.drains >= drained + 3);
            assertEquals(1, renderer.draws);
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            tickUntil(session, () -> renderer.draws == 2);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            finish(session);
            try (IconPack pack = openPack()) {
                assertEquals(
                    300,
                    pack.animation(candidate.key())
                        .get(0)
                        .durationMillis());
                assertEquals(
                    300,
                    pack.animation(candidate.key())
                        .get(1)
                        .durationMillis());
            }
        } finally {
            session.cancel("test cleanup");
            finish(session);
        }
    }

    private IconPack openPack() throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            return IconPack.open(
                paths.filter(
                    path -> path.toString()
                        .endsWith(".ae2wi-icons"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No pack")),
                "test",
                "loader",
                "identity");
        }
    }

    @Test
    void repeatedFatalCleanupStillClosesEveryOwnedCursorAndRenderer() throws Exception {
        Candidate first = new Candidate(1), second = new Candidate(2), third = new Candidate(3);
        OutOfMemoryError shared = new OutOfMemoryError("fixture cleanup");
        first.closeError = shared;
        second.closeError = shared;
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(first, second, third),
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> renderer.draws > 0);
        assertSame(shared, assertThrows(OutOfMemoryError.class, () -> session.cancel("test")));
        finish(session);
        assertTrue(first.closed);
        assertTrue(second.closed);
        assertTrue(third.closed);
        assertTrue(renderer.closed);
        assertNoPack();
    }

    private void assertNoPack() throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void controlledSpritesYieldAndDoNotConsumeTheSampleWindow() throws Exception {
        Candidate sprite = new Candidate(1), sampled = new Candidate(2);
        sprite.kind = IIconAnimation.Kind.SPRITE;
        sprite.stopAfter = 3;
        sprite.drawNanos = TimeUnit.SECONDS.toNanos(1);
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(sprite, sampled),
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> sprite.draws == 1);
        assertEquals(0, sampled.draws);
        tickUntil(session, () -> sprite.draws == 2);
        assertEquals(0, sampled.draws);
        tickUntil(session, () -> sampled.draws == 1);
        assertEquals(3, sprite.draws);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        tickUntil(session, () -> sampled.draws == 2);
        assertFalse(sampled.closed);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        finish(session);
        try (IconPack pack = openPack()) {
            assertEquals(
                3,
                pack.animation(sprite.key())
                    .size());
            assertEquals(
                2,
                pack.animation(sampled.key())
                    .size());
            assertEquals(
                100,
                pack.animation(sampled.key())
                    .get(0)
                    .durationMillis());
        }
    }

    @Test
    void slowSampleGroupsCompleteTwoWholePassesWithoutAnExtraSweep() throws Exception {
        Candidate first = new Candidate(1), second = new Candidate(2);
        first.drawNanos = second.drawNanos = TimeUnit.SECONDS.toNanos(2);
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(first, second),
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> second.draws == 1);
        assertEquals(1, first.draws);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        finish(session);
        assertEquals(2, first.draws);
        assertEquals(2, second.draws);
        try (IconPack pack = openPack()) {
            assertEquals(
                4100,
                pack.animation(first.key())
                    .get(0)
                    .durationMillis());
            assertEquals(
                4100,
                pack.animation(second.key())
                    .get(1)
                    .durationMillis());
        }
    }

    @Test
    void safelyFailedAnimationKeepsItsBaselineAndOtherAnimations() throws Exception {
        Candidate broken = new Candidate(1), healthy = new Candidate(2);
        broken.failAt = 2;
        healthy.stopAfter = 3;
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(broken, healthy),
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> healthy.draws == 1);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        tickUntil(session, () -> healthy.draws == 2);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        finish(session);
        assertTrue(broken.closed);
        assertTrue(healthy.closed);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(broken.key()));
            assertTrue(
                pack.animation(broken.key())
                    .isEmpty());
            assertEquals(
                3,
                pack.animation(healthy.key())
                    .size());
            assertEquals(1, pack.failureCount());
        }
    }

    @Test
    void cancelDuringPassGapPublishesNothingAndClosesNativeState() throws Exception {
        Candidate candidate = new Candidate(1);
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> candidate.draws == 1);
        session.cancel("user cancellation");
        finish(session);
        assertTrue(candidate.closed);
        assertTrue(renderer.closed);
        assertNoPack();
    }

    @Test
    void sourceReplacementDuringPassGapCancelsTheWholeExport() throws Exception {
        Candidate candidate = new Candidate(1);
        Renderer renderer = new Renderer(clock);
        Source source = new Source(candidate);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            source,
            renderer,
            true,
            clock::get);
        tickUntil(session, () -> candidate.draws == 1);
        source.invalid = true;
        finish(session);
        assertTrue(candidate.closed);
        assertTrue(renderer.closed);
        assertNoPack();
    }

    @Test
    void unsafeNativeFailureAbortsInsteadOfPublishingAnOmission() throws Exception {
        Candidate candidate = new Candidate(1);
        candidate.fatalAt = 1;
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer,
            true,
            clock::get);
        finish(session);
        assertTrue(candidate.closed);
        assertTrue(renderer.closed);
        assertNoPack();
    }

    @Test
    void unfinishedControlledPlanIsOmittedAtTheStepBound() throws Exception {
        Candidate candidate = new Candidate(1);
        candidate.kind = IIconAnimation.Kind.SPRITE;
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer,
            true,
            clock::get);
        finish(session);
        assertEquals(256, candidate.draws);
        assertTrue(candidate.closed);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(candidate.key()));
            assertTrue(
                pack.animation(candidate.key())
                    .isEmpty());
            assertEquals(1, pack.failureCount());
        }
    }

    @Test
    void legacyConstructorDoesNotRequestAnAnimation() throws Exception {
        Candidate candidate = new Candidate(1);
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer);
        finish(session);
        assertEquals(0, renderer.plans);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(candidate.key()));
            assertTrue(
                pack.animation(candidate.key())
                    .isEmpty());
        }
    }

    @Test
    void optInSupportsAnAdapterWithNoAnimationHook() throws Exception {
        Candidate candidate = new Candidate(1);
        Renderer renderer = new Renderer(clock);
        renderer.supported = false;
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer,
            true,
            clock::get);
        finish(session);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(candidate.key()));
            assertTrue(
                pack.animation(candidate.key())
                    .isEmpty());
        }
    }

    @Test
    void earlyCompletedSingleSampleCollapsesToStatic() throws Exception {
        Candidate candidate = new Candidate(1);
        candidate.stopAfter = 1;
        Renderer renderer = new Renderer(clock);
        IconExportSession<Candidate> session = new IconExportSession<>(
            directory,
            canceled -> metadata(),
            new Source(candidate),
            renderer,
            true,
            clock::get);
        finish(session);
        try (IconPack pack = openPack()) {
            assertNotNull(pack.find(candidate.key()));
            assertTrue(
                pack.animation(candidate.key())
                    .isEmpty());
            assertEquals(0, pack.failureCount());
        }
    }

    private static void tickUntil(IconExportSession<?> session, BooleanSupplier reached) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!reached.getAsBoolean()) {
            assertFalse(session.tick(), session.status());
            assertTrue(System.nanoTime() < deadline, session.status());
            Thread.yield();
        }
    }

    private static void finish(IconExportSession<?> session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!session.tick()) {
            assertTrue(System.nanoTime() < deadline, session.status());
            Thread.yield();
        }
    }

    private static IconPack.Metadata metadata() {
        return new IconPack.Metadata(
            "test",
            "loader",
            "identity",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
    }

    private static final class Candidate implements IIconCandidate<Candidate> {

        final int id;
        IIconAnimation.Kind kind = IIconAnimation.Kind.SAMPLED;
        int draws;
        int stopAfter;
        int failAt;
        int fatalAt;
        long drawNanos;
        boolean closed;
        Error closeError;

        Candidate(int id) {
            this.id = id;
        }

        public @NotNull StableKey key() {
            return StableKey.create(sink -> sink.putInt(id));
        }

        public @Nullable Candidate baseline() {
            return null;
        }

        public boolean sameIdentity(@NotNull Candidate other) {
            return id == other.id;
        }

        public @NotNull String context() {
            return "candidate/" + id;
        }
    }

    private static final class Source implements IIconSource<Candidate> {

        final List<Candidate> candidates;
        int index;
        boolean invalid;

        Source(Candidate... candidates) {
            this.candidates = Arrays.asList(candidates);
        }

        public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
            return null;
        }

        public @Nullable Candidate next() {
            return candidates.get(index++);
        }

        public boolean done() {
            return index == candidates.size();
        }

        public void verify() {
            if (invalid) throw new IllegalStateException("Generation replaced");
        }

        public @NotNull String context() {
            return "source";
        }

        public @NotNull Map<String, Long> counts() {
            return new HashMap<>();
        }

        public @NotNull List<String> sources() {
            return Collections.singletonList("fixture");
        }

        public @NotNull String note() {
            return "";
        }

        public @NotNull Throwable failureCause(@NotNull Throwable failure) {
            return failure;
        }
    }

    private static final class Renderer implements IIconCapture<Candidate> {

        final AtomicLong clock;
        final List<PackExportWriter.Capture> deferred = new ArrayList<>();
        int draws;
        int drains;
        int plans;
        boolean supported = true;
        long drainNanos;
        boolean closed;
        boolean animationClosed;

        Renderer(AtomicLong clock) {
            this.clock = clock;
        }

        public void capture(@NotNull Candidate candidate, @NotNull StableKey key,
            @NotNull List<PackExportWriter.Capture> captures) {
            deferred.add(new PackExportWriter.Capture(key, new int[4096]));
        }

        public @Nullable IIconAnimation animation(@NotNull Candidate candidate, @NotNull StableKey key)
            throws RenderFailure {
            plans++;
            if (!supported) return IIconCapture.super.animation(candidate, key);
            return new IIconAnimation() {

                public @NotNull Kind kind() {
                    return candidate.kind;
                }

                public boolean capture(@NotNull List<PackExportWriter.Capture> captures,
                    @NotNull LongSupplier drawClock) throws RenderFailure {
                    long submitted = drawClock.getAsLong();
                    draws++;
                    candidate.draws++;
                    clock.addAndGet(candidate.drawNanos);
                    if (candidate.draws == candidate.failAt)
                        throw new RenderFailure(new IllegalStateException("Draw failed safely"));
                    if (candidate.draws == candidate.fatalAt)
                        throw new IllegalStateException("Native state restoration failed");
                    int[] pixels = new int[4096];
                    Arrays.fill(pixels, 0xff000000 | candidate.draws);
                    deferred.add(
                        candidate.kind == Kind.SPRITE ? PackExportWriter.Capture.frame(key, pixels, 100)
                            : PackExportWriter.Capture.sample(key, pixels, submitted));
                    return candidate.stopAfter == 0 || candidate.draws < candidate.stopAfter;
                }

                public void close() {
                    animationClosed = true;
                    candidate.closed = true;
                    if (candidate.closeError != null) throw candidate.closeError;
                }
            };
        }

        public void drain(@NotNull List<PackExportWriter.Capture> captures) {
            drains++;
            if (!deferred.isEmpty()) clock.addAndGet(drainNanos);
            captures.addAll(deferred);
            deferred.clear();
        }

        public void statistics(@NotNull Map<String, Long> counts) {}

        public void close() {
            closed = true;
        }
    }
}
