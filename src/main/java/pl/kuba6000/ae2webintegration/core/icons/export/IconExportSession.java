package pl.kuba6000.ae2webintegration.core.icons.export;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.core.icons.export.PackExportWriter.Failure;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** One client-thread producer owns discovery and capture; only detached batches cross to writer workers. */
public final class IconExportSession<C extends IIconCandidate<C>> {

    private static final Logger LOG = LogManager.getLogger(IconExportSession.class);
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 512;
    private static final int MAX_STEPS_PER_FRAME = 4096;
    // Bound retained native candidates to the format-1 mapping ceiling, including candidates that later fail rendering.
    private static final int MAX_QUEUED_ICONS = 250_000;
    private static final int ETA_MIN_SAMPLES = 20;
    private static final int PERCENT_SCALE = 100;
    private static final long ETA_MIN_ELAPSED = TimeUnit.SECONDS.toNanos(2);
    // Yield a frame regularly for window events and the exclusive progress dialog.
    private static final long FRAME_BUDGET = TimeUnit.MILLISECONDS.toNanos(800);
    private final IIconCapture<C> renderer;
    private final PackExportWriter writer;
    private final Map<StableKey, C> identities = new HashMap<>();
    private final Deque<QueuedIcon> pendingIcons = new ArrayDeque<>();
    private List<Failure> failures = new ArrayList<>();
    private @Nullable IIconSource<C> catalogue;
    private String sourceNote = "";
    private @Nullable C baseline;
    private int captured;
    private int processed;
    private int total;
    private int renderFailures;
    private boolean discoveryComplete;
    private long renderStarted;
    private int omitted;
    private int duplicates;
    private int exactCandidates;
    private int baseCandidates;
    private long longestStep;
    private long captureWorkNanos;
    private long captureBackpressureNanos;
    private long backpressureStarted;
    private boolean finishing;
    private boolean canceling;
    private String status = "Preparing full icon export";

    /** Takes ownership of the native source/capture backend and the detached metadata source. */
    public IconExportSession(@NotNull Path root, @NotNull IMetadataSource environment,
        @NotNull IIconSource<C> catalogue, @NotNull IIconCapture<C> renderer) {
        this.catalogue = catalogue;
        this.renderer = renderer;
        try {
            writer = new PackExportWriter(root, environment);
        } catch (RuntimeException | Error failure) {
            try {
                renderer.close();
            } catch (RuntimeException | Error cleanup) {
                if (!(failure instanceof VirtualMachineError) && !(failure instanceof ThreadDeath)
                    && (cleanup instanceof VirtualMachineError || cleanup instanceof ThreadDeath)) {
                    cleanup.addSuppressed(failure);
                    throw cleanup;
                }
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Pumps one bounded client frame; true means publication or cancellation cleanup has settled. */
    public boolean tick() {
        try {
            if (canceling || finishing) {
                if (!writer.finished()) return false;
                writer.close();
                if (canceling) return true;
                status = "Icon export complete: " + processed
                    + "/"
                    + total
                    + " icons processed, "
                    + captured
                    + " captured, "
                    + omitted
                    + " failures; "
                    + writer.completedPath()
                    + sourceNote;
                return true;
            }
            if (!writer.ready()) {
                if (discoveryComplete && backpressureStarted == 0) backpressureStarted = System.nanoTime();
                return false;
            }
            if (backpressureStarted != 0) {
                captureBackpressureNanos += System.nanoTime() - backpressureStarted;
                backpressureStarted = 0;
            }
            IIconSource<C> source = catalogue;
            assert source != null : "Active export requires a catalogue";
            String waiting = source.prepare(this::omit);
            sourceNote = source.note();
            if (waiting != null) {
                status = waiting;
                return false;
            }
            source.verify();
            if (discoveryComplete) captureBatch(source);
            else discoverBatch(source);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread()
                .interrupt();
            return fail(exception);
        } catch (ExecutionException exception) {
            rethrowFatal(exception.getCause());
            return fail(exception.getCause());
        } catch (Throwable exception) {
            rethrowFatal(exception);
            return fail(exception);
        }
    }

    private void discoverBatch(@NotNull IIconSource<C> source) {
        long started = System.nanoTime();
        for (int step = 0; step < MAX_STEPS_PER_FRAME; step++) {
            long before = System.nanoTime();
            if (!discoverNext(source)) break;
            longestStep = Math.max(longestStep, System.nanoTime() - before);
            if (System.nanoTime() - started >= FRAME_BUDGET) break;
        }
        if (!failures.isEmpty()) {
            writer.write(Collections.emptyList(), failures);
            failures = new ArrayList<>();
        }
        status = "Discovering icons: " + pendingIcons.size()
            + " unique icons found, "
            + exactCandidates
            + " resources inspected, "
            + omitted
            + " discovery failures; total not known yet"
            + sourceNote;
        if (source.done() && baseline == null) {
            total = pendingIcons.size();
            identities.clear();
            discoveryComplete = true;
            renderStarted = System.nanoTime();
        }
    }

    private boolean discoverNext(@NotNull IIconSource<C> source) {
        if (baseline != null) {
            C candidate = baseline;
            baseline = null;
            baseCandidates++;
            enqueue(candidate);
            return true;
        }
        if (source.done()) return false;
        C candidate = discover(source);
        if (candidate == null) return true;
        exactCandidates++;
        try {
            baseline = candidate.baseline();
        } catch (Throwable failure) {
            omit(candidate.context() + "/base", failure);
        }
        enqueue(candidate);
        return true;
    }

    private void captureBatch(@NotNull IIconSource<C> source) {
        List<Capture> captures = new ArrayList<>();
        long started = System.nanoTime();
        for (int count = 0; count < PackExportWriter.MAX_CAPTURES_PER_BATCH && !pendingIcons.isEmpty(); count++) {
            long before = System.nanoTime();
            capture(pendingIcons.removeFirst(), captures);
            processed++;
            longestStep = Math.max(longestStep, System.nanoTime() - before);
            if (System.nanoTime() - started >= FRAME_BUDGET) break;
        }
        renderer.drain(captures);
        captureWorkNanos += System.nanoTime() - started;
        if (!captures.isEmpty() || !failures.isEmpty()) {
            writer.write(captures, failures);
            failures = new ArrayList<>();
        }
        if (pendingIcons.isEmpty()) finish(source);
    }

    private void finish(@NotNull IIconSource<C> source) {
        source.verify();
        renderer.close();
        Map<String, Long> counts = source.counts();
        counts.put("exactCandidates", (long) exactCandidates);
        counts.put("baseCandidates", (long) baseCandidates);
        counts.put("uniqueIdentities", (long) total);
        counts.put("processed", (long) processed);
        counts.put("renderFailures", (long) renderFailures);
        counts.put("discoveryFailures", (long) (omitted - renderFailures));
        counts.put("captured", (long) captured);
        counts.put("failures", (long) omitted);
        counts.put("duplicates", (long) duplicates);
        counts.put("longestNativeStepNanos", longestStep);
        counts.put("captureWorkNanos", captureWorkNanos);
        counts.put("captureBackpressureNanos", captureBackpressureNanos);
        renderer.statistics(counts);
        writer.finish(counts, source.sources());
        catalogue = null;
        finishing = true;
        status = "Saving icon pack: " + processed
            + "/"
            + total
            + " icons processed, 0 remaining, "
            + omitted
            + " failures"
            + sourceNote;
    }

    private @Nullable C discover(@NotNull IIconSource<C> source) {
        try {
            return source.next();
        } catch (Throwable failure) {
            omit(source.context(), failure);
            return null;
        }
    }

    private void enqueue(@NotNull C candidate) {
        StableKey key;
        try {
            key = candidate.key();
        } catch (Throwable failure) {
            omit(candidate.context() + "/identity", failure);
            return;
        }
        C previous = identities.get(key);
        if (previous != null) {
            if (!previous.sameIdentity(candidate))
                throw new IllegalStateException("Conflicting native identities for key " + key);
            duplicates++;
            return;
        }
        if (pendingIcons.size() >= MAX_QUEUED_ICONS) {
            throw new IllegalStateException("Catalogue exceeds the queued-icon limit of " + MAX_QUEUED_ICONS);
        }
        // Discovery finishes before rendering: these owned stacks cannot be changed by a renderer yet.
        identities.put(key, candidate);
        pendingIcons.addLast(new QueuedIcon(key, candidate));
    }

    private void capture(@NotNull QueuedIcon queued, @NotNull List<Capture> captures) {
        try {
            renderer.capture(queued.candidate, queued.key, captures);
            captured++;
        } catch (IIconCapture.RenderFailure failure) {
            renderFailures++;
            omit(queued.candidate.context() + "/" + queued.key, failure.getCause());
        }
    }

    public void cancel(@NotNull String reason) {
        if (canceling) return;
        if (!writer.cancel()) {
            status = "Finishing icon export: publication has already started";
            return;
        }
        canceling = true;
        status = "Icon export canceled: " + reason;
        identities.clear();
        pendingIcons.clear();
        failures.clear();
        baseline = null;
        catalogue = null;
        try {
            renderer.close();
        } catch (Throwable failure) {
            rethrowFatal(failure);
            LOG.warn("Icon renderer cleanup failed", failure);
            status = "Icon export cleanup failed: " + failure;
        }
    }

    @NotNull
    public String status() {
        if (!discoveryComplete || finishing || canceling) return status;
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

    private final class QueuedIcon {

        final StableKey key;
        final C candidate;

        QueuedIcon(@NotNull StableKey key, @NotNull C candidate) {
            this.key = key;
            this.candidate = candidate;
        }
    }

    private void omit(@NotNull String context, @NotNull Throwable failure) {
        assert catalogue != null : "Only active discovery/capture can omit candidates";
        Throwable cause = catalogue.failureCause(failure);
        rethrowFatal(cause);
        String message = cause.getMessage();
        String reason = cause.getClass()
            .getName();
        if (message != null)
            reason += ": " + message.substring(0, Math.min(message.length(), MAX_FAILURE_MESSAGE_LENGTH))
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ');
        failures.add(new Failure(context, reason));
        omitted++;
        LOG.warn("Icon candidate omitted ({}): {}", context, reason);
    }

    private static void rethrowFatal(@NotNull Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    private boolean fail(@NotNull Throwable failure) {
        LOG.warn("Icon export failed", failure);
        if (canceling || writer.completedPath() != null) {
            writer.close();
            status = "Icon export cleanup failed: " + failure;
            return true;
        }
        cancel("export failed: " + failure);
        if (canceling) status = "Icon export failed: " + failure;
        return false;
    }
}
