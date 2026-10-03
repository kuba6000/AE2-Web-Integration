package pl.kuba6000.ae2webintegration.core.icons;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pl.kuba6000.ae2webintegration.core.WindowsFileLocks;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@EnabledOnOs(OS.WINDOWS)
class IconPackWriterCleanupTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void temporaryFileLockDoesNotFailCloseOrPublication(boolean publish) throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor();
        IconPack.Metadata metadata = new IconPack.Metadata(
            "1.7.10",
            "forge",
            "legacy-v1",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.emptyList());
        StableKey key = StableKey.create(sink -> sink.putInt(1));
        try (IconPackWriter writer = new IconPackWriter(directory, metadata, 512)) {
            writer.add(key, new int[4096]);
            Path scratch;
            try (Stream<Path> files = Files.list(directory)) {
                scratch = files.filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Writer did not create a scratch directory"));
            }
            Path heldFile = Files.createFile(scratch.resolve("held-by-another-process"));
            Future<Path> completion;
            CountDownLatch started = new CountDownLatch(1);
            try (SeekableByteChannel lock = WindowsFileLocks.preventDeletion(heldFile)) {
                completion = owner.submit(() -> {
                    started.countDown();
                    if (publish) return writer.finish();
                    writer.close();
                    return null;
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> completion.get(300, TimeUnit.MILLISECONDS));
            }
            Path output = completion.get(15, TimeUnit.SECONDS);
            assertFalse(Files.exists(scratch));
            if (publish) {
                try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1")) {
                    assertNotNull(pack.find(key));
                }
            }
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(publish ? 1 : 0, files.count());
            }
        } finally {
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(15, TimeUnit.SECONDS));
        }
    }
}
