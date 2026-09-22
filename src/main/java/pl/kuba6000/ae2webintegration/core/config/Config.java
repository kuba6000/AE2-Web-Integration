package pl.kuba6000.ae2webintegration.core.config;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.conversion.ObjectConverter;
import com.electronwill.nightconfig.core.conversion.Path;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.utils.AtomicFileWriter;

public class Config {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    private static final ObjectConverter CONVERTER = new ObjectConverter();

    /** Replaced as a whole on init and reload. Fields of a published instance are read-only. */
    public static volatile ConfigSettings INSTANCE = new ConfigSettings();

    private static File configDirectory;

    // --- Directory / file setup ---

    public static synchronized void init(File configDirectory) {
        init(configDirectory, null);
    }

    /** Imports legacy settings only when the TOML configuration does not exist. */
    public static synchronized void init(File configDirectory, ILegacyConfigProvider legacyReader) {
        File directory = new File(configDirectory, "ae2webintegration");
        File file = new File(directory, "config.toml");
        try {
            boolean wasMigrated = false;
            CommentedConfig document;
            if (file.exists()) {
                document = read(file);
            } else if (legacyReader != null && legacyReader.isAvailable()) {
                document = migrate(legacyReader);
                wasMigrated = true;
            } else {
                document = newDocument();
                CONVERTER.toConfig(new ConfigSettings(), document);
            }
            publish(file, document);
            if (wasMigrated) {
                legacyReader.markAsMigrated();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load configuration: " + file, e);
        }
        Config.configDirectory = directory;
    }

    /** Loads a complete configuration before replacing the settings visible to request threads. */
    public static synchronized void reload() {
        File file = getConfigFile("config.toml");
        try {
            publish(file, read(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reload configuration: " + file, e);
        }
    }

    private static CommentedConfig newDocument() {
        return CommentedConfig.of(LinkedHashMap::new, TomlFormat.instance());
    }

    private static CommentedConfig read(File file) throws IOException {
        CommentedConfig document = newDocument();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            new TomlParser().parse(reader, document, ParsingMode.REPLACE);
        }
        return document;
    }

    @Deprecated
    private static final Map<String, String> LEGACY_KEYS = new LinkedHashMap<>();

    static {
        LEGACY_KEYS.put("port", "general.port");
        LEGACY_KEYS.put("password", "general.password");
        LEGACY_KEYS.put("allow_no_password_on_localhost", "general.allow_no_password_on_localhost");
        LEGACY_KEYS.put("trusted_proxies", "general.trusted_proxies");
        LEGACY_KEYS.put("public_mode", "general.public_mode");
        LEGACY_KEYS.put("max_requests_before_logged_in_per_minute", "general.max_requests_before_logged_in_per_minute");
        LEGACY_KEYS.put("check_for_updates", "general.check_for_updates");
        LEGACY_KEYS.put("discord_webhook", "discord.webhook");
        LEGACY_KEYS.put("discord_role_id", "discord.role_id");
        LEGACY_KEYS.put("discord_minimum_crafting_duration_seconds", "notifications.minimum_crafting_duration_seconds");
        LEGACY_KEYS.put("discord_minimum_crafting_amount", "notifications.minimum_crafting_amount");
        LEGACY_KEYS.put("track_machine_crafting", "tracking.track_machine_crafting");
    }

    @Deprecated
    private static CommentedConfig migrate(ILegacyConfigProvider legacy) {
        LOG.info("LEGACY CONFIG MIGRATION INIT");
        CommentedConfig document = newDocument();
        CONVERTER.toConfig(new ConfigSettings(), document);
        for (Map.Entry<String, String> entry : LEGACY_KEYS.entrySet()) {
            Object value = legacy.get(entry.getKey());
            if (value == null) {
                continue;
            }
            document.set(entry.getValue(), value);
            LOG.info("Mapped {} to {}", entry.getKey(), entry.getValue());
        }
        return document;
    }

    private static void publish(File file, CommentedConfig document) throws IOException {
        ConfigSettings loaded = new ConfigSettings();
        CommentedConfig defaults = newDocument();
        CONVERTER.toConfig(loaded, defaults);
        fillDefaults(document, defaults);
        CONVERTER.toObject(document, loaded);
        loaded.validate();
        addComments(document, loaded);
        TomlWriter serializer = new TomlWriter();
        serializer.setIndent("");
        serializer.setWriteStringLiteralPredicate(Config::useLiteralString);
        disableMultilineStrings(serializer);
        AtomicFileWriter.write(file, writer -> serializer.write(document, writer));
        INSTANCE = loaded;
    }

    private static boolean useLiteralString(String value) {
        boolean literal = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\'', '\b', '\f', '\n', '\r', '\t' -> literal = false;
                default -> {
                    // 3.6.4 emits these raw instead of escaping them, making its next read fail.
                    if (character < ' ' || character == '\u007f') {
                        throw new IllegalArgumentException("Unsupported control character in TOML configuration");
                    }
                }
            }
        }
        return literal;
    }

    private static void disableMultilineStrings(TomlWriter serializer) {
        // Newer NightConfig normalizes line endings in multiline strings, changing stored passwords.
        try {
            TomlWriter.class.getMethod("setWriteStringMultilinePredicate", Predicate.class)
                .invoke(serializer, (Predicate<String>) value -> false);
        } catch (NoSuchMethodException ignored) {
            // 3.6.4 always writes escaped single-line strings and has no multiline setting.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not configure lossless TOML string writing", e);
        }
    }

    private static void fillDefaults(CommentedConfig document, UnmodifiableConfig defaults) {
        for (UnmodifiableConfig.Entry entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            if (!document.contains(key)) {
                document.set(key, defaultValue);
            } else if (defaultValue instanceof UnmodifiableConfig section) {
                Object value = document.get(key);
                if (!(value instanceof CommentedConfig existing)) {
                    throw new IllegalArgumentException("Expected a configuration table: " + key);
                }
                fillDefaults(existing, section);
            }
        }
    }

    private static void addComments(CommentedConfig document, Object model) {
        for (Field field : model.getClass()
            .getDeclaredFields()) {
            Path path = field.getAnnotation(Path.class);
            String key = path == null ? field.getName() : path.value();
            Comment comment = field.getAnnotation(Comment.class);
            if (comment != null && document.getComment(key) == null) {
                document.setComment(key, " " + String.join("\n ", comment.value()));
            }
            if (document.get(key) instanceof CommentedConfig section) {
                try {
                    addComments(section, field.get(model));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read configuration descriptions", e);
                }
            }
        }
    }

    public static File getConfigDirectory() {
        return configDirectory;
    }

    public static File getConfigFile(String fileName) {
        return new File(configDirectory, fileName);
    }
}
