package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deletes this test's {@link TempDir} before JUnit's own cleanup.
 * <p>
 * JUnit records {@link java.nio.file.DirectoryNotEmptyException} without waiting. Only this test's
 * directory is removed: wiping every {@code junit*} folder races other tests that still have it open.
 */
public class WindowsTempDirectoryCleanup implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (!System.getProperty("os.name", "")
            .startsWith("Windows")) {
            return;
        }
        if (!context.getTestInstances()
            .isPresent()) {
            return;
        }
        for (Object instance : context.getTestInstances()
            .get()
            .getAllInstances()) {
            Class<?> type = instance.getClass();
            while (type != null && type != Object.class) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getAnnotation(TempDir.class) == null) {
                        continue;
                    }
                    field.setAccessible(true);
                    delete(field.get(instance));
                }
                type = type.getSuperclass();
            }
        }
    }

    private static void delete(Object value) {
        if (value instanceof Path) {
            TempDirectories.deleteRecursively((Path) value);
        } else if (value instanceof File) {
            TempDirectories.deleteRecursively(((File) value).toPath());
        }
    }
}
