package pl.kuba6000.ae2webintegration.core.config;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

/** Edits real configuration files through the same init/reload boundary used by a server. */
public final class ConfigTestFixture implements AutoCloseable {

    private final File previousDirectory = Config.getConfigDirectory();
    private final File root;
    private final boolean ownsRoot;
    private final Path file;

    public ConfigTestFixture() {
        this(temporaryDirectory(), true);
    }

    public ConfigTestFixture(File root) {
        this(root, false);
    }

    private ConfigTestFixture(File root, boolean ownsRoot) {
        this.root = root;
        this.ownsRoot = ownsRoot;
        Config.init(root);
        file = Config.getConfigFile("config.toml")
            .toPath();
    }

    public void set(String key, Object value) {
        write(key, value);
        Config.reload();
    }

    public void write(String key, Object value) {
        CommentedConfig settings;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            settings = new TomlParser().parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        settings.set(key, value);
        writeRaw(new TomlWriter().writeToString(settings));
    }

    public void writeRaw(String toml) {
        try {
            Files.write(file, toml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static int unusedLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        if (previousDirectory != null && new File(previousDirectory, "config.toml").isFile()) {
            Config.init(previousDirectory.getParentFile());
        } else {
            writeRaw("");
            Config.reload();
        }
        if (ownsRoot) {
            try (Stream<Path> paths = Files.walk(root.toPath())) {
                paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static File temporaryDirectory() {
        try {
            return Files.createTempDirectory("ae2-config-test-")
                .toFile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
