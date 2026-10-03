package pl.kuba6000.ae2webintegration.core.icons;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.icons.IconPack.Metadata;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconPackWriterConcurrencyTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void fatalSiblingFailureTakesPriorityOverAnEarlierNonfatalErrorAfterSettlement(boolean close, boolean threadDeath)
        throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstFailed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AssertionError ordinary = new AssertionError("Controlled nonfatal codec error");
        Error fatal = threadDeath ? new ThreadDeath() : new OutOfMemoryError("Controlled sibling fatal error");
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            if (image.getRGB(0, 0) == 0xff000001) {
                await(secondStarted);
                firstFailed.countDown();
                throw ordinary;
            }
            secondStarted.countDown();
            await(release);
            throw fatal;
        })) {
            Future<Path> export = owner.submit(() -> {
                try (IconPackWriter writer = new IconPackWriter(directory, metadata(), 64)) {
                    add(writer, 1);
                    add(writer, 2);
                    if (close) {
                        writer.close();
                        return null;
                    }
                    return writer.finish();
                }
            });
            try {
                await(firstFailed);
                assertThrows(TimeoutException.class, () -> export.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            ExecutionException failed = assertThrows(ExecutionException.class, () -> export.get(5, TimeUnit.SECONDS));
            assertSame(fatal, failed.getCause());
            assertTrue(
                Arrays.asList(fatal.getSuppressed())
                    .contains(ordinary));
            assertEquals(0, encoder.completed.get());
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        } finally {
            release.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void fatalEncoderErrorSurvivesCleanupInsteadOfBecomingAnIoFailure() throws Exception {
        OutOfMemoryError fatal = new OutOfMemoryError("Controlled codec fatal error");
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> { throw fatal; })) {
            assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> writeTwoPages(directory)));
            assertEquals(0, encoder.completed.get());
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void encoderFailureSettlesSiblingWorkAndNeverPublishes() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstFailed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        IOException codecFailure = new IOException("Controlled encoder failure");
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            if (image.getRGB(0, 0) == 0xff000001) {
                await(secondStarted);
                firstFailed.countDown();
                throw codecFailure;
            }
            secondStarted.countDown();
            await(release);
            delegate.run();
        })) {
            Future<Path> export = owner.submit(() -> writeTwoPages(directory));
            try {
                await(firstFailed);
                assertThrows(TimeoutException.class, () -> export.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            ExecutionException failed = assertThrows(ExecutionException.class, () -> export.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failed.getCause());
            assertSame(
                codecFailure,
                failed.getCause()
                    .getCause());
            assertEquals(1, encoder.completed.get());
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        } finally {
            release.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedCloseStillSettlesWorkersAndRestoresInterruptStatus() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            started.countDown();
            await(release);
            delegate.run();
        }); IconPackWriter writer = new IconPackWriter(directory, metadata(), 64)) {
            add(writer, 1);
            await(started);
            Future<Boolean> closed = owner.submit(() -> {
                Thread.currentThread()
                    .interrupt();
                try {
                    writer.close();
                    return Thread.currentThread()
                        .isInterrupted();
                } finally {
                    // Clear the flag after asserting it, so this test does not interrupt later work.
                    // noinspection ResultOfMethodCallIgnored
                    Thread.interrupted();
                }
            });
            try {
                assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(closed.get(5, TimeUnit.SECONDS));
            assertEquals(1, encoder.completed.get());
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
            assertThrows(IOException.class, () -> add(writer, 2));
        } finally {
            release.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 64, 512, 1024, 2048 })
    void finishedPageOwnershipIsBoundedUntilEncodersReleaseTheirBuffers(int pageSize) throws Exception {
        int allowance = pageSize == 2048 ? 2 : 10;
        int cells = pageSize == 64 ? 1 : (pageSize / 66) * (pageSize / 66);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch full = new CountDownLatch(1);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            await(release);
            delegate.run();
        })) {
            Future<Path> export = owner.submit(() -> {
                try (IconPackWriter writer = new IconPackWriter(directory, metadata(), pageSize)) {
                    for (int i = 1; i <= cells * (allowance + 1); i++) {
                        if (i == cells * (allowance + 1)) full.countDown();
                        add(writer, i);
                    }
                    return writer.finish();
                }
            });
            try {
                await(full);
                assertThrows(TimeoutException.class, () -> export.get(100, TimeUnit.MILLISECONDS));
                assertEquals(0, encoder.completed.get());
            } finally {
                release.countDown();
            }
            Path output = export.get(15, TimeUnit.SECONDS);
            try (IconPack pack = open(output)) {
                assertEquals(
                    allowance + 1,
                    pack.pages()
                        .size());
            }
            JsonObject counts = counts(output);
            assertEquals(
                allowance,
                counts.get("pngPeakPendingPages")
                    .getAsInt());
            assertEquals(
                Math.max(
                    4,
                    Runtime.getRuntime()
                        .availableProcessors() / 2),
                counts.get("pngWorkers")
                    .getAsInt());
            assertTrue(
                counts.get("pngWorkNanosSum")
                    .getAsLong() > 0);
            assertTrue(
                counts.get("pngQueueWaitNanosSum")
                    .getAsLong() >= 0);
            assertTrue(
                counts.get("pngBackpressureNanos")
                    .getAsLong() > 0);
        } finally {
            release.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void fullCallerStatisticsRemainAcceptedWithoutExceedingManifestLimit() throws Exception {
        Map<String, Long> supplied = new TreeMap<>();
        for (int i = 0; i < 64; i++) supplied.put("caller" + i, (long) i);
        Path output;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata(), 64)) {
            writer.statistics(supplied, Collections.emptyList());
            add(writer, 1);
            output = writer.finish();
        }
        JsonObject counts = counts(output);
        assertEquals(
            64,
            counts.entrySet()
                .size());
        for (Map.Entry<String, Long> entry : supplied.entrySet()) assertEquals(
            entry.getValue()
                .longValue(),
            counts.get(entry.getKey())
                .getAsLong());
    }

    private static JsonObject counts(Path output) throws IOException {
        try (ZipFile archive = new ZipFile(output.toFile());
            InputStreamReader manifest = new InputStreamReader(
                archive.getInputStream(archive.getEntry("manifest.json")),
                StandardCharsets.UTF_8)) {
            return new JsonParser().parse(manifest)
                .getAsJsonObject()
                .getAsJsonObject("statistics")
                .getAsJsonObject("counts");
        }
    }

    @Test
    void canceledFinishSettlesActiveEncodersAndCleansScratchBeforeReturning() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService owner = Executors.newSingleThreadExecutor();
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            started.countDown();
            await(release);
            delegate.run();
        }); IconPackWriter writer = new IconPackWriter(directory, metadata(), 64)) {
            add(writer, 1);
            await(started);
            Future<Path> finish = owner.submit(() -> writer.finish(() -> true));
            try {
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            ExecutionException cancellation = assertThrows(
                ExecutionException.class,
                () -> finish.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, cancellation.getCause());
            assertEquals(1, encoder.completed.get());
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        } finally {
            release.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void laterPageCanFinishWhileEarlierPageEncodesWithoutChangingPackIdentityOrPixels() throws Exception {
        Path serialOrder = writeTwoPages(directory.resolve("reference"));
        CountDownLatch laterEncoded = new CountDownLatch(1);
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            if (image.getRGB(0, 0) == 0xff000001) {
                await(laterEncoded);
                delegate.run();
            } else {
                delegate.run();
                laterEncoded.countDown();
            }
        })) {
            Path reversedOrder = writeTwoPages(directory.resolve("parallel"));
            assertEquals(2, encoder.completed.get());
            try (IconPack expected = open(serialOrder); IconPack actual = open(reversedOrder)) {
                assertEquals(expected.packId(), actual.packId());
                for (int i = 1; i <= 2; i++) {
                    IconPack.Location location = actual.find(key(i));
                    assertNotNull(location);
                    try (InputStream input = actual.openPage(location.page.digest)) {
                        assertEquals(
                            0xff000000 | i,
                            ImageIO.read(input)
                                .getRGB(location.x, location.y));
                    }
                }
            }
        }
    }

    private static Path writeTwoPages(Path directory) throws IOException {
        try (IconPackWriter writer = new IconPackWriter(directory, metadata(), 64)) {
            add(writer, 1);
            add(writer, 2);
            return writer.finish();
        }
    }

    private static void add(IconPackWriter writer, int index) throws IOException {
        int[] pixels = new int[4096];
        Arrays.fill(pixels, 0xff000000 | index);
        writer.add(key(index), pixels);
    }

    private static StableKey key(int index) {
        return StableKey.create(sink -> sink.putInt(index));
    }

    private static Metadata metadata() {
        return new Metadata(
            "1.7.10",
            "forge",
            "legacy-v1",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
    }

    private static IconPack open(Path output) throws IOException {
        return IconPack.open(output, "1.7.10", "forge", "legacy-v1");
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out awaiting controlled PNG encode");
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted controlled PNG encode", interrupted);
        }
    }

}
