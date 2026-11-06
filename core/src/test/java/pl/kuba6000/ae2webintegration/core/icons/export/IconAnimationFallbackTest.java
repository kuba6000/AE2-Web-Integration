package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconAnimationFallbackTest {

    @TempDir
    Path directory;

    @Test
    void restoredControlledFailureReplacesPriorImmediateAndDeferredFramesWithLiveSamples() throws Exception {
        Renderer renderer = new Renderer();
        IconExportSession<Candidate> session = session(renderer);
        finish(session, renderer.clock);
        try (IconPack pack = openPack()) {
            assertNotNull(
                pack.animation(Candidate.BROKEN.key()),
                "A safely restored controlled failure should sample live animation");
            assertEquals(Arrays.asList(0xff003300, 0xff004400), animationColors(pack, Candidate.BROKEN));
            assertEquals(Arrays.asList(0xffddaa00, 0xffffffff), animationColors(pack, Candidate.HEALTHY));
            assertEquals(0xff555555, staticColor(pack, Candidate.BROKEN));
            assertEquals(1, pack.failureCount(), "The recovered failure should still be reported");
        }
        assertEquals(1, fallbackCount());
    }

    @Test
    void failedLiveReplacementKeepsStaticAndLaterIconsInsteadOfTryingAnotherReplacement() throws Exception {
        Renderer renderer = new Renderer();
        renderer.failSample = true;
        finish(session(renderer), renderer.clock);
        try (IconPack pack = openPack()) {
            assertNull(pack.animation(Candidate.BROKEN.key()));
            assertEquals(0xff555555, staticColor(pack, Candidate.BROKEN));
            assertEquals(Arrays.asList(0xffddaa00, 0xffffffff), animationColors(pack, Candidate.HEALTHY));
            assertEquals(2, pack.failureCount());
        }
        assertEquals(1, fallbackCount());
    }

    @Test
    void failedControlledPreparationCanUseLiveSamplesWithoutLosingDeferredStaticPixels() throws Exception {
        Renderer renderer = new Renderer();
        renderer.failPreparation = true;
        finish(session(renderer), renderer.clock);
        try (IconPack pack = openPack()) {
            assertEquals(Arrays.asList(0xff003300, 0xff004400), animationColors(pack, Candidate.BROKEN));
            assertEquals(0xff555555, staticColor(pack, Candidate.BROKEN));
            assertEquals(Arrays.asList(0xffddaa00, 0xffffffff), animationColors(pack, Candidate.HEALTHY));
            assertEquals(1, pack.failureCount());
        }
    }

    @Test
    void safeFailureCreatingReplacementKeepsStaticAndContinuesOtherIcons() throws Exception {
        Renderer renderer = new Renderer();
        renderer.failFallbackPreparation = true;
        finish(session(renderer), renderer.clock);
        try (IconPack pack = openPack()) {
            assertNull(pack.animation(Candidate.BROKEN.key()));
            assertEquals(0xff555555, staticColor(pack, Candidate.BROKEN));
            assertEquals(Arrays.asList(0xffddaa00, 0xffffffff), animationColors(pack, Candidate.HEALTHY));
            assertEquals(2, pack.failureCount());
        }
    }

    @Test
    void failedControlledCleanupAbortsPublicationRatherThanStartingLiveSamples() throws Exception {
        Renderer renderer = new Renderer();
        renderer.failClose = true;
        finish(session(renderer), renderer.clock);
        assertNoPack();
    }

    @Test
    void readbackFailureWhileRecoveringAbortsPublication() throws Exception {
        Renderer renderer = new Renderer();
        renderer.failDrain = true;
        finish(session(renderer), renderer.clock);
        assertNoPack();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void fatalFailureDuringControlledCaptureIsNotRecoverable(boolean threadDeath) throws Exception {
        Renderer renderer = new Renderer();
        renderer.fatal = threadDeath ? new ThreadDeath() : new OutOfMemoryError("Native allocation failure");
        IconExportSession<Candidate> session = session(renderer);
        try {
            assertSame(renderer.fatal, assertThrows(Error.class, () -> finish(session, renderer.clock)));
        } finally {
            session.cancel("fatal owner teardown");
            finish(session, renderer.clock);
        }
        assertNoPack();
    }

    private void assertNoPack() throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            assertFalse(
                files.anyMatch(
                    path -> path.toString()
                        .endsWith(".ae2wi-icons")));
        }
    }

    private IconExportSession<Candidate> session(Renderer renderer) {
        return new IconExportSession<>(
            directory,
            new IconPack.Metadata("test", "loader", "identity", "test", "today", Collections.emptyMap()),
            new Source(),
            renderer,
            true,
            renderer.clock::get);
    }

    private static void finish(IconExportSession<?> session, AtomicLong clock) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!session.tick()) {
            assertTrue(System.nanoTime() < deadline, session.status());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
            Thread.sleep(1);
        }
    }

    private long fallbackCount() throws Exception {
        try (ZipFile zip = new ZipFile(archive().toFile());
            InputStream input = zip.getInputStream(zip.getEntry("manifest.json"));
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader)
                .getAsJsonObject()
                .getAsJsonObject("statistics")
                .getAsJsonObject("counts")
                .get("animationFallbacks")
                .getAsLong();
        }
    }

    private IconPack openPack() throws Exception {
        return IconPack.open(archive(), "test", "loader", "identity");
    }

    private Path archive() throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(
                path -> path.toString()
                    .endsWith(".ae2wi-icons"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No archive published"));
        }
    }

    private static List<Integer> animationColors(IconPack pack, Candidate candidate) throws Exception {
        IconPack.Animation animation = pack.animation(candidate.key());
        assertNotNull(animation);
        List<Integer> colors = new ArrayList<>();
        try (InputStream input = pack.openPage(
            animation.page()
                .digest())) {
            BufferedImage image = ImageIO.read(input);
            for (IconPack.Frame frame : animation.frames()) {
                if (candidate == Candidate.BROKEN) assertTrue(
                    frame.durationMillis() >= 120,
                    "Live samples include the 20ms draw and 100ms between ticks");
                else assertEquals(70, frame.durationMillis());
                colors.add(image.getRGB(frame.x(), frame.y()));
            }
            image.flush();
        }
        return colors;
    }

    private static int staticColor(IconPack pack, Candidate candidate) throws Exception {
        IconPack.Location location = pack.find(candidate.key());
        assertNotNull(location);
        try (InputStream input = pack.openPage(
            location.page()
                .digest())) {
            BufferedImage image = ImageIO.read(input);
            int color = image.getRGB(location.x(), location.y());
            image.flush();
            return color;
        }
    }

    private static int[] pixels(int color) {
        int[] pixels = new int[4096];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private enum Candidate implements IIconCandidate<Candidate> {

        BROKEN,
        HEALTHY;

        public @NotNull StableKey key() {
            return StableKey.create(sink -> sink.putInt(ordinal()));
        }

        public @Nullable Candidate baseline() {
            return null;
        }

        public boolean sameIdentity(@NotNull Candidate other) {
            return this == other;
        }

        public @NotNull String context() {
            return name();
        }
    }

    private static final class Source implements IIconSource<Candidate> {

        int index;

        public @Nullable String prepare(@NotNull BiConsumer<String, Throwable> failures) {
            return null;
        }

        public @Nullable Candidate next() {
            return Candidate.values()[index++];
        }

        public boolean done() {
            return index == Candidate.values().length;
        }

        public void verify() {}

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

        final AtomicLong clock = new AtomicLong();
        final List<PackExportWriter.Capture> deferred = new ArrayList<>();
        boolean failPreparation;
        boolean failFallbackPreparation;
        boolean failSample;
        boolean failClose;
        boolean failDrain;
        boolean controlledFailed;
        Error fatal;
        int fallbacks;

        public void capture(@NotNull Candidate candidate, @NotNull StableKey key,
            @NotNull List<PackExportWriter.Capture> captures) {
            deferred.add(new PackExportWriter.Capture(key, pixels(0xff555555)));
        }

        public @NotNull IIconAnimation animation(@NotNull Candidate candidate, @NotNull StableKey key)
            throws RenderFailure {
            if (candidate == Candidate.BROKEN && failPreparation)
                throw new RenderFailure(new IllegalStateException("Controlled preparation restored safely"));
            return new IIconAnimation() {

                int draws;

                public @NotNull Kind kind() {
                    return Kind.SPRITE;
                }

                public boolean capture(@NotNull List<PackExportWriter.Capture> captures,
                    @NotNull LongSupplier drawClock) throws RenderFailure {
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
                    draws++;
                    if (candidate == Candidate.HEALTHY) {
                        deferred
                            .add(PackExportWriter.Capture.frame(key, pixels(draws == 1 ? 0xffddaa00 : 0xffffffff), 70));
                        return draws < 2;
                    }
                    captures.add(PackExportWriter.Capture.frame(key, pixels(draws == 1 ? 0xffff0000 : 0xffff00ff), 70));
                    deferred.add(PackExportWriter.Capture.frame(key, pixels(draws == 1 ? 0xff0000ff : 0xff00ffff), 70));
                    if (draws == 2) {
                        if (fatal != null) throw fatal;
                        controlledFailed = true;
                        throw new RenderFailure(new IllegalStateException("Controlled draw restored safely"));
                    }
                    return true;
                }

                public void close() {
                    if (candidate == Candidate.BROKEN && failClose)
                        throw new IllegalStateException("Controlled cleanup failed");
                }
            };
        }

        @Override
        public @NotNull IIconAnimation sampledAnimation(@NotNull Candidate candidate, @NotNull StableKey key)
            throws RenderFailure {
            if (failFallbackPreparation)
                throw new RenderFailure(new IllegalStateException("Live preparation failed safely"));
            int attempt = ++fallbacks;
            return new IIconAnimation() {

                int draws;

                public @NotNull Kind kind() {
                    return Kind.SAMPLED;
                }

                public boolean capture(@NotNull List<PackExportWriter.Capture> captures,
                    @NotNull LongSupplier drawClock) throws RenderFailure {
                    long submitted = drawClock.getAsLong();
                    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
                    deferred
                        .add(PackExportWriter.Capture.sample(key, pixels(0xff000000 | (++draws * 0x1100)), submitted));
                    // A repeated fallback would succeed, making recursive retry visible in the published archive.
                    if (failSample && attempt == 1 && draws == 3)
                        throw new RenderFailure(new IllegalStateException("Live capture failed safely"));
                    return draws < 4;
                }

                public void close() {}
            };
        }

        public void drain(@NotNull List<PackExportWriter.Capture> captures) {
            if (failDrain && controlledFailed) throw new IllegalStateException("Readback failed");
            captures.addAll(deferred);
            deferred.clear();
        }

        public void statistics(@NotNull Map<String, Long> counts) {}

        public void close() {}
    }
}
