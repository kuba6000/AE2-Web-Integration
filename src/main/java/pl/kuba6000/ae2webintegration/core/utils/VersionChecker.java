package pl.kuba6000.ae2webintegration.core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.io.ByteStreams;

/** One server lifecycle's background checks and last completed recommendation. */
public final class VersionChecker implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");
    private static final long CHECK_INTERVAL_HOURS = 5;
    private static final long RETRY_DELAY_MINUTES = 5;
    // These bound connection and read inactivity, not total response duration.
    private static final int HTTP_TIMEOUT_MILLIS = (int) TimeUnit.SECONDS.toMillis(5);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final @NotNull String currentVersion;
    private final @NotNull String versionIdentifier;
    private final @NotNull String minecraftVersion;
    private final @NotNull URL feedUrl;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "AE2WebIntegration-VersionChecker");
        thread.setDaemon(true);
        return thread;
    });
    private volatile @Nullable ReleaseManifest.Release availableUpdate;
    private @Nullable CompletableFuture<ReleaseManifest.Release> pending;
    private @Nullable ScheduledFuture<?> scheduled;
    private boolean closed;

    public VersionChecker(@NotNull URL feedBaseUrl, @NotNull String currentVersion, @NotNull String versionIdentifier) {
        this.currentVersion = currentVersion;
        this.versionIdentifier = versionIdentifier;
        Matcher target = Pattern.compile("-(?:neo)?forge-(\\d+\\.\\d+\\.\\d+)")
            .matcher(versionIdentifier);
        if (!target.matches())
            throw new IllegalArgumentException("Unsupported version identifier: " + versionIdentifier);
        minecraftVersion = target.group(1);
        try {
            feedUrl = new URL(feedBaseUrl, minecraftVersion + ".json");
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid feed URL", e);
        }
    }

    /**
     * Starts or joins a background refresh. Successful checks repeat after five hours; failures retry in five minutes.
     */
    public synchronized @NotNull CompletableFuture<ReleaseManifest.Release> checkForUpdates() {
        if (closed) {
            CompletableFuture<ReleaseManifest.Release> cancelled = new CompletableFuture<>();
            cancelled.cancel(false);
            return cancelled;
        }
        if (pending != null) return pending;
        if (scheduled != null) scheduled.cancel(false);
        CompletableFuture<ReleaseManifest.Release> result = new CompletableFuture<>();
        pending = result;
        executor.execute(() -> refresh(result));
        return result;
    }

    /** Read-only snapshot; this never performs network I/O. Null means no completed recommendation. */
    public @Nullable ReleaseManifest.Release getAvailableUpdate() {
        return availableUpdate;
    }

    private void refresh(CompletableFuture<ReleaseManifest.Release> result) {
        try {
            ReleaseManifest.Release release = fetch().findUpdate(currentVersion, versionIdentifier);
            synchronized (this) {
                if (closed) return;
                ReleaseManifest.Release previous = availableUpdate;
                availableUpdate = release;
                if (release != null && (previous == null || !previous.tag.equals(release.tag))) {
                    LOG.warn(
                        "New {} release of AE2 Web Integration: {} at {}",
                        release.channel == ReleaseManifest.Channel.STABLE ? "stable" : "prerelease",
                        release.tag,
                        release.releaseUrl);
                }
                pending = null;
                scheduled = executor.schedule(this::checkForUpdates, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);
                result.complete(release);
            }
        } catch (Exception e) {
            synchronized (this) {
                if (closed) return;
                LOG.debug("Could not check AE2 Web Integration releases", e);
                pending = null;
                scheduled = executor.schedule(this::checkForUpdates, RETRY_DELAY_MINUTES, TimeUnit.MINUTES);
                result.completeExceptionally(e);
            }
        }
    }

    private ReleaseManifest fetch() throws IOException {
        HttpURLConnection request = (HttpURLConnection) feedUrl.openConnection();
        request.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
        request.setReadTimeout(HTTP_TIMEOUT_MILLIS);
        request.setRequestProperty("User-Agent", "AE2-Web-Integration");
        request.setRequestProperty("Accept", "application/json");
        synchronized (this) {
            if (closed) throw new IOException("Version checker stopped");
        }
        try {
            int status = request.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Release feed HTTP status: " + status);
            if (request.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IOException("Release feed is too large");
            try (InputStream body = request.getInputStream()) {
                byte[] bytes = ByteStreams.toByteArray(ByteStreams.limit(body, MAX_RESPONSE_BYTES + 1));
                if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Release feed is too large");
                return ReleaseManifest.parse(new String(bytes, StandardCharsets.UTF_8), minecraftVersion);
            }
        } finally {
            request.disconnect();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        availableUpdate = null;
        if (pending != null) pending.cancel(false);
        // The worker owns disconnect: calling it here can block the server thread behind an active read.
        // Interruption does not guarantee immediate termination of HttpURLConnection I/O.
        executor.shutdownNow();
    }
}
