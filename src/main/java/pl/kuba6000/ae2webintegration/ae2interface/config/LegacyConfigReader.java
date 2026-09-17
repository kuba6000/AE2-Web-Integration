package pl.kuba6000.ae2webintegration.ae2interface.config;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public final class LegacyConfigReader {

    private LegacyConfigReader() {}

    public static Map<String, Object> read(File configDirectory) {
        Path file = configDirectory.toPath()
            .resolve("ae2webintegration/ae2webintegration.cfg");
        if (ForgeModContainer.getConfig()
            .getCategory(Configuration.CATEGORY_GENERAL)
            .get("enableGlobalConfig")
            .getBoolean()) {
            // Global mode resolves the original filename in Forge's already loaded configuration.
            Map<String, Object> settings = collect(new Configuration(file.toFile()));
            if (settings.isEmpty()) {
                return collect(new Configuration(new File(configDirectory, "ae2webintegration.cfg")));
            }
            return settings;
        }
        if (!Files.exists(file)) file = configDirectory.toPath()
            .resolve("ae2webintegration.cfg");
        if (!Files.exists(file)) return new HashMap<>();
        try {
            return readFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read legacy configuration " + file, e);
        }
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
