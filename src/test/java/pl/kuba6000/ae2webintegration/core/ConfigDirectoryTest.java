package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.config.Config;

class ConfigDirectoryTest {

    @TempDir
    Path root;

    @Test
    void initializationCreatesAndReusesTheConfigDirectory() {
        Config.init(root.toFile());
        Path directory = Config.getConfigDirectory()
            .toPath();
        assertTrue(Files.isDirectory(directory));

        Config.init(root.toFile());
        assertEquals(
            directory,
            Config.getConfigDirectory()
                .toPath());
    }

    @Test
    void initializationReportsAnUnusableConfigDirectory() throws Exception {
        Files.createFile(root.resolve("ae2webintegration"));

        assertThrows(UncheckedIOException.class, () -> Config.init(root.toFile()));
    }
}
