package pl.kuba6000.ae2webintegration.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Removes JUnit temporary directories before JUnit's own cleanup.
 * <p>
 * {@code @TempDir} is created in an earlier callback, so a snapshot taken in {@code beforeEach}
 * already contains it and would skip the directory that later fails to delete. {@code @TempDir}
 * records {@link java.nio.file.DirectoryNotEmptyException} without waiting.
 */
public class WindowsTempDirectoryCleanup implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        if (!System.getProperty("os.name", "")
            .startsWith("Windows")) {
            return;
        }
        Path temporary = Paths.get(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> children = Files.newDirectoryStream(temporary, "junit*")) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    TempDirectories.deleteRecursively(child);
                }
            }
        } catch (IOException ignored) {
            // A missing temp directory has nothing for JUnit to clean up.
        }
    }
}
