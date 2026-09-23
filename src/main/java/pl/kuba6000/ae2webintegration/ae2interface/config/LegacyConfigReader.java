package pl.kuba6000.ae2webintegration.ae2interface.config;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;

@Deprecated
public final class LegacyConfigReader implements ILegacyConfigProvider {

    private static final LegacyConfigReader ABSENT = new LegacyConfigReader(Collections.emptyMap(), null);

    private final Map<String, Object> values;
    private final Path source;

    private LegacyConfigReader(Map<String, Object> values, Path source) {
        this.values = values;
        this.source = source;
    }

    public static LegacyConfigReader open(File configDirectory) {
        Path nested = configDirectory.toPath()
            .resolve("ae2webintegration/ae2webintegration.cfg");
        Path flat = configDirectory.toPath()
            .resolve("ae2webintegration.cfg");
        if (globalConfig()) {
            // Forge uses these paths as child identifiers even when only global.cfg exists.
            Map<String, Object> settings = collect(new Configuration(nested.toFile()));
            if (!settings.isEmpty()) {
                return new LegacyConfigReader(settings, Files.exists(nested) ? nested : null);
            }
            settings = collect(new Configuration(flat.toFile()));
            return new LegacyConfigReader(settings, Files.exists(flat) ? flat : null);
        }
        Path file = Files.exists(nested) ? nested : flat;
        if (!Files.exists(file)) return ABSENT;
        try {
            return new LegacyConfigReader(readFile(file), file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read legacy configuration " + file, e);
        }
    }

    @Override
    public boolean isAvailable() {
        return source != null || !values.isEmpty();
    }

    @Override
    public Object get(String key) {
        return values.get(key);
    }

    /** Keeps the imported file, but not under the name of a live configuration. */
    @Override
    public void markAsMigrated() {
        if (source == null) return;
        Path renamed = source.resolveSibling(
            source.getFileName()
                .toString() + ".old");
        try {
            Files.move(source, renamed, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rename legacy configuration " + source, e);
        }
    }

    private static boolean globalConfig() {
        return ForgeModContainer.getConfig()
            .getCategory(Configuration.CATEGORY_GENERAL)
            .get("enableGlobalConfig")
            .getBoolean();
    }

    private static Map<String, Object> readFile(Path file) throws IOException {
        // Forge renames malformed files and creates empty replacements, so parse a disposable copy.
        Path directory = Files.createTempDirectory(file.getParent(), ".ae2-config-migration-");
        try {
            Path copy = directory.resolve("legacy.cfg");
            Files.copy(file, copy);
            Configuration config = new Configuration(copy.toFile());
            try (DirectoryStream<Path> errors = Files.newDirectoryStream(directory, "*.errored")) {
                if (errors.iterator()
                    .hasNext()) {
                    throw new IOException("Invalid legacy configuration " + file);
                }
            }
            return collect(config);
        } finally {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path temporary : files) {
                    Files.deleteIfExists(temporary);
                }
            }
            Files.delete(directory);
        }
    }

    private static Map<String, Object> collect(Configuration config) {
        Map<String, Object> settings = new HashMap<>();
        for (String category : config.getCategoryNames()) {
            for (Map.Entry<String, Property> entry : config.getCategory(category)
                .entrySet()) {
                settings.put(entry.getKey(), value(entry.getValue()));
            }
        }
        return settings;
    }

    private static Object value(Property property) {
        if (property.isList()) return Arrays.asList(property.getStringList());
        String value = property.getString();
        if (property.getType() == Property.Type.INTEGER) return Integer.valueOf(value);
        if (property.getType() == Property.Type.DOUBLE) return Double.valueOf(value);
        if (property.getType() == Property.Type.BOOLEAN
            && ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
            return Boolean.valueOf(value);
        }
        return value;
    }
}
