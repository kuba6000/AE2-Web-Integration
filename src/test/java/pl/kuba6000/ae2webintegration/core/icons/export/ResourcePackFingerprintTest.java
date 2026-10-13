package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcePackFingerprintTest {

    @TempDir
    Path directory;

    @Test
    void fileFingerprintUsesItsBytesAndChangesWhenContentChanges() throws Exception {
        Path file = directory.resolve("pack.zip");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ResourcePackFingerprint.hash(file, () -> false)
                .toString());
        Files.write(file, "abcd".getBytes(StandardCharsets.UTF_8));
        assertEquals(
            "88d4266fd4e6338d13b845fcf289579d209c897823b9217da3e161936f031589",
            ResourcePackFingerprint.hash(file, () -> false)
                .toString());
    }

    @Test
    void directoryFingerprintDependsOnRelativePathsAndContentRatherThanCreationOrderOrRoot() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.createDirectories(first.resolve("nested"));
        Files.createDirectories(second.resolve("nested"));
        Files.write(first.resolve("z.txt"), new byte[] { 1, 2 });
        Files.write(first.resolve("nested/a.txt"), new byte[] { 3, 4 });
        Files.write(second.resolve("nested/a.txt"), new byte[] { 3, 4 });
        Files.write(second.resolve("z.txt"), new byte[] { 1, 2 });
        String expected = ResourcePackFingerprint.hash(first, () -> false)
            .toString();
        // Independent SHA-256 fixture: big-endian UTF-8 path lengths, slash paths, then file digests.
        assertEquals("1c5379461caaecdda4f36889072b1679476f784732da9f2489d366ee1f438087", expected);
        assertEquals(
            expected,
            ResourcePackFingerprint.hash(second, () -> false)
                .toString());
        Files.move(second.resolve("nested/a.txt"), second.resolve("a.txt"));
        assertNotEquals(
            expected,
            ResourcePackFingerprint.hash(second, () -> false)
                .toString());
    }

    @Test
    void unreadableAndCanceledInputsDoNotProduceFingerprints() throws Exception {
        assertThrows(IOException.class, () -> ResourcePackFingerprint.hash(directory.resolve("missing"), () -> false));
        Path file = Files.write(directory.resolve("pack"), new byte[131072]);
        assertThrows(CancellationException.class, () -> ResourcePackFingerprint.hash(file, () -> true));
        assertThrows(CancellationException.class, () -> ResourcePackFingerprint.hash(directory, () -> true));
    }
}
