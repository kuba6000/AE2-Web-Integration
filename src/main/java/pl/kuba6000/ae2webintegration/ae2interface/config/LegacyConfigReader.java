package pl.kuba6000.ae2webintegration.ae2interface.config;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

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
        Path file = configDirectory.toPath()
            .resolve("ae2webintegration/ae2webintegration.toml");
        if (!Files.exists(file)) return ABSENT;
        Map<String, Object> settings = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            collect(new TomlParser().parse(reader), settings);
            return new LegacyConfigReader(settings, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read legacy configuration " + file, e);
        }
    }

    @Override
    public boolean isAvailable() {
        return source != null;
    }

    @Override
    public Object get(String key) {
        return values.get(key);
    }

    /** Keeps the imported file, but not under the name of a live configuration. */
    @Override
    public void markAsMigrated() {
        if (source == null) return;
        Path renamed = source.resolveSibling(source.getFileName()
            .toString() + ".old");
        try {
            Files.move(source, renamed, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rename legacy configuration " + source, e);
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
