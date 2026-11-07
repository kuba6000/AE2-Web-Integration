package pl.kuba6000.ae2webintegration.core.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;

/** Best-effort usage reports from packaged mods, owned by one game-server lifetime. */
public final class Telemetry implements AutoCloseable {

    private static final int TIMEOUT_MILLIS = 5000;
    private static final long INITIAL_DELAY_SECONDS = TimeUnit.MINUTES.toSeconds(10);
    private static final long INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(1);
    private final @NotNull URL endpoint;
    private @NotNull String sessionId = UUID.randomUUID()
        .toString();
    private @NotNull LocalDate sessionDay;
    private final @NotNull Clock clock;
    private final @NotNull String minecraftVersion;
    private final boolean singleplayer;
    private final @NotNull ScheduledExecutorService worker;
    private volatile boolean closed;
    private final @Nullable Path modFile;
    private final SecureRandom random = new SecureRandom();
    // Accessed only by the single telemetry worker; the artifact is read at most once per lifecycle.
    private boolean hashAttempted;
    private byte @Nullable [] jarDigest;

    public Telemetry(@NotNull String minecraftVersion, boolean singleplayer, @Nullable Path modFile) {
        this(endpoint(), minecraftVersion, singleplayer, modFile, Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "AE2Web-Telemetry");
            thread.setDaemon(true);
            return thread;
        }), Clock.systemUTC());
    }

    Telemetry(@NotNull URL endpoint, @NotNull String minecraftVersion, boolean singleplayer, @Nullable Path modFile,
        @NotNull ScheduledExecutorService worker, @NotNull Clock clock) {
        this.endpoint = endpoint;
        this.minecraftVersion = minecraftVersion;
        this.singleplayer = singleplayer;
        this.worker = worker;
        this.modFile = modFile;
        this.clock = clock;
        this.sessionDay = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!endpoint.getProtocol()
            .equals("https")) {
            worker.shutdownNow();
            throw new IllegalArgumentException("Telemetry requires HTTPS");
        }
        if (modFile != null) {
            try {
                worker.scheduleWithFixedDelay(this::report, INITIAL_DELAY_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
            } catch (RuntimeException failure) {
                worker.shutdownNow();
                throw failure;
            }
        }
    }

    private static URL endpoint() {
        try {
            return new URL("https://ae2web.kuba6000.pl/api/telemetry");
        } catch (MalformedURLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private JsonElement jarProof() {
        if (!hashAttempted) {
            Path file = modFile;
            if (file == null) return JsonNull.INSTANCE;
            try {
                if (!Files.isRegularFile(file)) {
                    hashAttempted = true;
                    return JsonNull.INSTANCE;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (closed || !Config.INSTANCE.telemetry.enabled) return JsonNull.INSTANCE;
                        digest.update(buffer, 0, count);
                    }
                }
                jarDigest = digest.digest();
                hashAttempted = true;
            } catch (IOException | SecurityException ignored) {
                hashAttempted = true;
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException(failure);
            }
        }
        byte[] digest = jarDigest;
        if (digest == null) return JsonNull.INSTANCE;
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return JarProof.create(digest, salt);
    }

    private void report() {
        ConfigSettings settings = Config.INSTANCE;
        if (closed || !settings.telemetry.enabled) return;
        HttpsURLConnection connection = null;
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            if (!today.equals(sessionDay)) {
                sessionId = UUID.randomUUID()
                    .toString();
                sessionDay = today;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", sessionId);
            payload.addProperty("minecraftVersion", minecraftVersion);
            payload.addProperty("publicMode", settings.general.publicMode);
            payload.addProperty("singleplayer", singleplayer);
            payload.add("jarProof", jarProof());
            byte[] bytes = payload.toString()
                .getBytes(StandardCharsets.UTF_8);
            connection = (HttpsURLConnection) endpoint.openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setAllowUserInteraction(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "AE2-Web-Integration");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setDoOutput(true);
            if (closed || !Config.INSTANCE.telemetry.enabled) return;
            try (OutputStream output = connection.getOutputStream()) {
                if (closed || !Config.INSTANCE.telemetry.enabled) return;
                output.write(bytes);
            }
            connection.getResponseCode();
        } catch (IOException | RuntimeException ignored) {
            // Telemetry is optional: no retry queue, player message or automatic failure log.
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (RuntimeException ignored) {
                    // A transport cleanup failure must not cancel subsequent scheduled attempts.
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        // The worker owns disconnect: it may block behind an active socket read.
        worker.shutdownNow();
    }
}
