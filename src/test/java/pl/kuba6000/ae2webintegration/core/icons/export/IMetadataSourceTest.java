package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;

class IMetadataSourceTest {

    @TempDir
    Path directory;

    @Test
    void retainsCapturedProvenanceAndHashesPacksInPriorityOrderOnlyWhenCreated() throws Exception {
        Path low = directory.resolve("low.zip");
        Path high = directory.resolve("high.zip");
        Instant before = Instant.now();
        IMetadataSource source = IMetadataSource.fromFilesystem(
            new IconPack.Target("game", "loader", "identity", "base"),
            "generator",
            Collections.singletonMap("mod", "version"),
            Arrays.asList(low, high));
        Instant after = Instant.now();
        // The client capture must not open these files; they only exist when the worker creates metadata.
        Files.write(low, "abc".getBytes(StandardCharsets.UTF_8));
        Files.write(high, "abcd".getBytes(StandardCharsets.UTF_8));
        IconPack.Metadata metadata = source.create(() -> false);
        assertEquals("game", metadata.minecraftVersion);
        assertEquals("loader", metadata.loader);
        assertEquals("identity", metadata.identityContract);
        assertEquals("base", metadata.basePolicy);
        assertEquals("generator", metadata.generatorVersion);
        assertEquals(Collections.singletonMap("mod", "version"), metadata.mods);
        assertEquals(
            Arrays.asList(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "88d4266fd4e6338d13b845fcf289579d209c897823b9217da3e161936f031589"),
            metadata.resourcePacks);
        Instant captured = Instant.parse(metadata.generatedAt);
        assertFalse(captured.isBefore(before));
        assertFalse(captured.isAfter(after));
    }

    @Test
    void missingAndCanceledResourcePacksFailMetadataCreation() throws Exception {
        Path pack = directory.resolve("pack.zip");
        IMetadataSource source = IMetadataSource.fromFilesystem(
            new IconPack.Target("game", "loader", "identity", "base"),
            "generator",
            Collections.emptyMap(),
            Collections.singletonList(pack));
        assertThrows(IOException.class, () -> source.create(() -> false));
        Files.write(pack, new byte[] { 1 });
        assertThrows(CancellationException.class, () -> source.create(() -> true));
    }
}
