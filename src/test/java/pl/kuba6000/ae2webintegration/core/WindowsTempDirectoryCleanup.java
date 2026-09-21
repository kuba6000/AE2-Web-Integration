package pl.kuba6000.ae2webintegration.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Removes JUnit temporary directories before JUnit's own cleanup.
 * <p>
 * {@code @TempDir} records {@link java.nio.file.DirectoryNotEmptyException} without waiting, so a
 * Windows delete that has not settled yet fails the test after its assertions passed.
 */
public class WindowsTempDirectoryCleanup implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
        .create(WindowsTempDirectoryCleanup.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        if (!isWindows()) {
            return;
        }
        context.getStore(NAMESPACE)
            .put("junit-temp-dirs", listJUnitTempDirectories());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (!isWindows()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> previous = (Set<String>) context.getStore(NAMESPACE)
            .get("junit-temp-dirs", Set.class);
        if (previous == null) {
            return;
        }
        Set<String> created = listJUnitTempDirectories();
        for (String directory : created) {
            if (!previous.contains(directory)) {
                TempDirectories.deleteRecursively(Paths.get(directory));
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
            .startsWith("Windows");
    }

    private static Set<String> listJUnitTempDirectories() {
        Set<String> directories = new HashSet<String>();
        Path temporary = Paths.get(System.getProperty("java.io.tmpdir"));
        try (DirectoryStream<Path> children = Files.newDirectoryStream(temporary, "junit*")) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    directories.add(child.toString());
                }
            }
        } catch (IOException ignored) {
            // A missing temp directory has nothing for JUnit to clean up.
        }
        return directories;
    }
}
