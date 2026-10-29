package pl.kuba6000.ae2webintegration.core.icons.export;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Client-thread counters, timing and status text for one icon export. */
final class IconExportProgress {

    private static final int ETA_MIN_SAMPLES = 20;
    private static final int PERCENT_SCALE = 100;
    private static final long ETA_MIN_ELAPSED = TimeUnit.SECONDS.toNanos(2);
    int captured;
    int processed;
    private int total;
    int renderFailures;
    int animationFallbacks;
    int omitted;
    int duplicates;
    int exactCandidates;
    int baseCandidates;
    long longestStep;
    long captureWorkNanos;
    private long renderStarted;
    private long captureBackpressureNanos;
    private long backpressureStarted;

    void startRendering(int total) {
        this.total = total;
        renderStarted = System.nanoTime();
    }

    void waitingForWriter() {
        if (backpressureStarted == 0) backpressureStarted = System.nanoTime();
    }

    void writerReady() {
        if (backpressureStarted == 0) return;
        captureBackpressureNanos += System.nanoTime() - backpressureStarted;
        backpressureStarted = 0;
    }

    void addStatistics(@NotNull Map<String, Long> counts) {
        counts.put("exactCandidates", (long) exactCandidates);
        counts.put("baseCandidates", (long) baseCandidates);
        counts.put("uniqueIdentities", (long) total);
        counts.put("processed", (long) processed);
        counts.put("renderFailures", (long) renderFailures);
        counts.put("animationFallbacks", (long) animationFallbacks);
        counts.put("discoveryFailures", (long) (omitted - renderFailures));
        counts.put("captured", (long) captured);
        counts.put("failures", (long) omitted);
        counts.put("duplicates", (long) duplicates);
        counts.put("longestNativeStepNanos", longestStep);
        counts.put("captureWorkNanos", captureWorkNanos);
        counts.put("captureBackpressureNanos", captureBackpressureNanos);
    }

    @NotNull
    String discovering(int queued, @NotNull String sourceNote) {
        return "Discovering icons: " + queued
            + " unique icons found, "
            + exactCandidates
            + " resources inspected, "
            + omitted
            + " discovery failures; total not known yet"
            + sourceNote;
    }

    @NotNull
    String saving(@NotNull String sourceNote) {
        return "Saving icon pack: " + processed
            + "/"
            + total
            + " icons processed, 0 remaining, "
            + omitted
            + " failures"
            + sourceNote;
    }

    @NotNull
    String complete(@Nullable Path completedPath, int failures, @NotNull String sourceNote) {
        return "Icon export complete: " + processed
            + "/"
            + total
            + " icons processed, "
            + captured
            + " captured, "
            + failures
            + " failures; "
            + completedPath
            + sourceNote;
    }

    @NotNull
    String rendering(@NotNull String sourceNote) {
        if (processed == total) {
            return "Saving icon pack: " + processed
                + "/"
                + total
                + " icons processed, 0 remaining; waiting for image writes"
                + sourceNote;
        }
        int remaining = total - processed;
        int percent = (int) ((long) processed * PERCENT_SCALE / total);
        return "Rendering icons: " + processed
            + "/"
            + total
            + " ("
            + percent
            + "%), "
            + remaining
            + " remaining, render ETA "
            + remainingTime(remaining)
            + "; "
            + renderFailures
            + " render failures, "
            + (omitted - renderFailures)
            + " discovery failures"
            + sourceNote;
    }

    private @NotNull String remainingTime(int remaining) {
        long elapsed = System.nanoTime() - renderStarted;
        if (processed < ETA_MIN_SAMPLES || elapsed < ETA_MIN_ELAPSED) return "estimating";
        long seconds = (long) Math.ceil((double) elapsed / processed * remaining / TimeUnit.SECONDS.toNanos(1));
        long hours = TimeUnit.SECONDS.toHours(seconds);
        long minutes = TimeUnit.SECONDS.toMinutes(seconds);
        if (hours > 0) return "~" + hours + "h " + minutes % TimeUnit.HOURS.toMinutes(1) + "m";
        if (minutes > 0) return "~" + minutes + "m " + seconds % TimeUnit.MINUTES.toSeconds(1) + "s";
        return "~" + seconds + "s";
    }

}
