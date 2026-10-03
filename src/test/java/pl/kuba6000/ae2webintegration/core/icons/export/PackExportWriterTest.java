package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.kuba6000.ae2webintegration.core.icons.ControlledPng;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class PackExportWriterTest {

    @TempDir
    Path directory;

    @Test
    void boundedAdmissionAndCancellationWaitForEncodingToSettle() throws Exception {
        CountDownLatch encoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ControlledPng png = new ControlledPng((image, delegate) -> {
            encoding.countDown();
            await(release);
            delegate.run();
        })) {
            PackExportWriter writer = new PackExportWriter(directory, canceled -> metadata());
            try {
                awaitReady(writer);
                // More pages than the encoder allowance: the producer must eventually stop admission.
                for (int batch = 0; batch < 4; batch++) {
                    awaitReady(writer);
                    writer.write(captures(batch), Collections.emptyList());
                }
                await(encoding);
                assertFalse(writer.ready());
                assertTrue(writer.cancel());
                assertFalse(writer.finished());
                assertThrows(IllegalStateException.class, writer::close);
                assertNull(writer.completedPath());
            } finally {
                release.countDown();
                writer.cancel();
                awaitFinished(writer);
                writer.close();
            }
            assertTrue(png.completed.get() > 0);
            assertEmptyDirectory();
        }
    }

    @Test
    void encoderFailureIsNotTerminalUntilOtherEncodingHasSettled() throws Exception {
        CountDownLatch encoding = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IOException failure = new IOException("Controlled PNG failure");
        try (ControlledPng png = new ControlledPng((image, delegate) -> {
            if (image.getRGB(0, 0) == 0xff000000) {
                await(encoding);
                failed.countDown();
                throw failure;
            }
            encoding.countDown();
            await(release);
            delegate.run();
        })) {
            PackExportWriter writer = new PackExportWriter(directory, canceled -> metadata());
            awaitReady(writer);
            writer.write(captures(0), Collections.emptyList());
            writer.finish(new HashMap<>(), Collections.emptyList());
            try {
                await(failed);
                assertFalse(writer.finished());
                assertNull(writer.completedPath());
            } finally {
                release.countDown();
            }
            ExecutionException problem = assertThrows(ExecutionException.class, () -> awaitFinished(writer));
            assertSame(
                failure,
                problem.getCause()
                    .getCause());
            writer.close();
            assertTrue(png.completed.get() > 0);
            assertEmptyDirectory();
        }
    }

    private static ArrayList<PackExportWriter.Capture> captures(int batch) {
        ArrayList<PackExportWriter.Capture> captures = new ArrayList<>();
        for (int index = 0; index < PackExportWriter.MAX_CAPTURES_PER_BATCH; index++) {
            int color = batch * PackExportWriter.MAX_CAPTURES_PER_BATCH + index;
            int[] pixels = new int[4096];
            Arrays.fill(pixels, 0xff000000 | color);
            captures.add(new PackExportWriter.Capture(StableKey.create(sink -> sink.putInt(color)), pixels));
        }
        return captures;
    }

    @Test
    void cancellationDuringMetadataInitializationWaitsForTheWorkerAndClosesAdmission() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread producer = Thread.currentThread();
        AtomicReference<Thread> initializer = new AtomicReference<>();
        PackExportWriter writer = new PackExportWriter(directory, canceled -> {
            initializer.set(Thread.currentThread());
            started.countDown();
            await(release);
            assertTrue(canceled.getAsBoolean());
            throw new CancellationException();
        });
        try {
            await(started);
            assertNotSame(producer, initializer.get());
            assertFalse(writer.ready());
            assertTrue(writer.cancel());
            assertTrue(writer.cancel());
            assertFalse(writer.ready());
            assertFalse(writer.finished());
            assertThrows(IllegalStateException.class, writer::close);
            assertThrows(
                IllegalStateException.class,
                () -> writer.write(Collections.emptyList(), Collections.emptyList()));
            assertThrows(IllegalStateException.class, () -> writer.finish(new HashMap<>(), Collections.emptyList()));
        } finally {
            release.countDown();
            writer.cancel();
            awaitFinished(writer);
            writer.close();
        }
        assertNull(writer.completedPath());
        assertEmptyDirectory();
    }

    @Test
    void initializationFailureRetainsItsCauseThroughCleanup() throws Exception {
        IOException problem = new IOException("Cannot read detached resource pack");
        PackExportWriter writer = new PackExportWriter(directory, canceled -> { throw problem; });
        assertSame(problem, assertThrows(ExecutionException.class, () -> awaitReady(writer)).getCause());
        writer.cancel();
        assertSame(problem, assertThrows(ExecutionException.class, () -> awaitFinished(writer)).getCause());
        writer.close();
        assertNull(writer.completedPath());
        assertEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void fatalInitializationFailureIsNotDowngraded(boolean threadDeath) throws Exception {
        Error fatal = threadDeath ? new ThreadDeath() : new OutOfMemoryError("Controlled fatal initialization");
        PackExportWriter writer = new PackExportWriter(directory, canceled -> { throw fatal; });
        assertSame(fatal, assertThrows(ExecutionException.class, () -> awaitReady(writer)).getCause());
        writer.cancel();
        assertSame(fatal, assertThrows(ExecutionException.class, () -> awaitFinished(writer)).getCause());
        writer.close();
        assertEmptyDirectory();
    }

    @Test
    void invalidCaptureFailsTheExportAndSettlesCleanup() throws Exception {
        PackExportWriter writer = new PackExportWriter(directory, canceled -> metadata());
        awaitReady(writer);
        writer.write(
            new ArrayList<>(
                Collections.singletonList(
                    new PackExportWriter.Capture(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[1]))),
            Collections.emptyList());
        ExecutionException early = assertThrows(ExecutionException.class, () -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                writer.ready();
                Thread.sleep(1);
            }
            fail("Invalid capture did not fail");
        });
        writer.cancel();
        assertSame(early.getCause(), assertThrows(ExecutionException.class, () -> awaitFinished(writer)).getCause());
        writer.close();
        assertNull(writer.completedPath());
        assertEmptyDirectory();
    }

    private void assertEmptyDirectory() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            assertEquals(0, paths.count());
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Controlled worker timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException(interrupted);
        }
    }

    @Test
    void terminalRequestsCloseAdmissionAndActiveCloseIsInvalid() throws Exception {
        PackExportWriter writer = new PackExportWriter(directory, canceled -> metadata());
        try {
            awaitReady(writer);
            assertThrows(IllegalStateException.class, writer::close);
            writer.finish(new HashMap<>(), Collections.emptyList());
            assertFalse(writer.ready());
            assertThrows(
                IllegalStateException.class,
                () -> writer.write(Collections.emptyList(), Collections.emptyList()));
            assertThrows(IllegalStateException.class, () -> writer.finish(new HashMap<>(), Collections.emptyList()));
            awaitFinished(writer);
            assertFalse(writer.cancel());
            writer.close();
            writer.close();
        } finally {
            if (writer.cancel()) awaitFinished(writer);
            writer.close();
        }
    }

    @Test
    void resourcePackPriorityOrderSurvivesWorkerInitializationAndPublication() throws Exception {
        Path low = Files.write(directory.resolve("low.zip"), new byte[] { 1 });
        Path high = Files.write(directory.resolve("high.zip"), new byte[] { 2 });
        List<String> expected = Arrays.asList(
            ResourcePackFingerprint.hash(low, () -> false)
                .toString(),
            ResourcePackFingerprint.hash(high, () -> false)
                .toString());
        PackExportWriter writer = new PackExportWriter(
            directory,
            canceled -> new IconPack.Metadata(
                "test",
                "loader",
                "identity",
                "base",
                "generator",
                "today",
                Collections.emptyMap(),
                Arrays.asList(
                    ResourcePackFingerprint.hash(low, canceled)
                        .toString(),
                    ResourcePackFingerprint.hash(high, canceled)
                        .toString())));
        awaitReady(writer);
        writer.finish(new HashMap<>(), Collections.emptyList());
        awaitFinished(writer);
        writer.close();
        Path completed = writer.completedPath();
        assertNotNull(completed, "Finished export must publish a pack");
        try (IconPack pack = IconPack.open(completed, "test", "loader", "identity", "base")) {
            assertEquals(expected, pack.metadata().resourcePacks);
        }
    }

    @Test
    void submittedBatchesArePublishedBeforeCompletion() throws Exception {
        PackExportWriter writer = new PackExportWriter(directory, canceled -> metadata());
        awaitReady(writer);
        StableKey key = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        writer.write(
            new ArrayList<>(Collections.singletonList(new PackExportWriter.Capture(key, new int[4096]))),
            new ArrayList<>(Collections.singletonList(new PackExportWriter.Failure("example", "failed render"))));
        writer.finish(new HashMap<>(), Arrays.asList("native", "fluids"));
        awaitFinished(writer);
        writer.close();
        Path completed = writer.completedPath();
        assertNotNull(completed, "Finished export must publish a pack");
        try (IconPack pack = IconPack.open(completed, "test", "loader", "identity", "base")) {
            assertNotNull(pack.find(key));
            assertEquals(1, pack.failureCount());
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

    private static void awaitReady(PackExportWriter writer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!writer.ready()) {
            assertTrue(System.nanoTime() < deadline, "Pipeline did not become ready");
            Thread.sleep(1);
        }
    }

    private static void awaitFinished(PackExportWriter writer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!writer.finished()) {
            assertTrue(System.nanoTime() < deadline, "Pipeline did not finish");
            Thread.sleep(1);
        }
    }
}
