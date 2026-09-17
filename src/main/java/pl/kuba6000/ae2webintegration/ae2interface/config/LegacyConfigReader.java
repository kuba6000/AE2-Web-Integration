package pl.kuba6000.ae2webintegration.ae2interface.config;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

public final class LegacyConfigReader {

    private LegacyConfigReader() {}

    public static Map<String, Object> read(File configDirectory) {
        Path file = configDirectory.toPath()
            .resolve("ae2webintegration/ae2webintegration.toml");
        Map<String, Object> settings = new HashMap<>();
        if (!Files.exists(file)) return settings;
        try (Reader reader = Files.newBufferedReader(file)) {
            collect(new TomlParser().parse(reader), settings);
            return settings;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read legacy configuration " + file, e);
        }
    }

    private static void collect(UnmodifiableConfig config, Map<String, Object> settings) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig section) {
                collect(section, settings);
            } else {
                settings.put(entry.getKey(), value);
            }
        }
    }
}
