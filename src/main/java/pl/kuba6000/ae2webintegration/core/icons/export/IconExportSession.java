package pl.kuba6000.ae2webintegration.core.icons.export;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
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
    // Yield a frame regularly for window events and the exclusive progress dialog.
    private static final long FRAME_BUDGET = TimeUnit.MILLISECONDS.toNanos(800);
    private final IIconCapture<C> renderer;
    private final PackExportWriter writer;
    private final IconExportProgress progress = new IconExportProgress();
    private final @Nullable IconAnimationPasses animations;
    private final Map<StableKey, C> identities = new HashMap<>();
    private final Deque<QueuedIcon<C>> pendingIcons = new ArrayDeque<>();
    private List<Failure> failures = new ArrayList<>();
    private @Nullable IIconSource<C> catalogue;
    private String sourceNote = "";
    private @Nullable C baseline;
    private boolean discoveryComplete;
    private boolean animationPhase;
    private boolean sampleProbeSubmitted;
    private boolean finishing;
    private boolean canceling;
    private String status = "Preparing full icon export";

    /** Takes ownership of the native source/capture backend and the detached immutable metadata. */
    public IconExportSession(@NotNull Path root, @NotNull IconPack.Metadata environment,
        @NotNull IIconSource<C> catalogue, @NotNull IIconCapture<C> renderer) {
        this(root, environment, catalogue, renderer, false);
    }

    public IconExportSession(@NotNull Path root, @NotNull IconPack.Metadata environment,
        @NotNull IIconSource<C> catalogue, @NotNull IIconCapture<C> renderer, boolean animated) {
        this(root, environment, catalogue, renderer, animated, System::nanoTime);
    }

    /** The same monotonic clock controls pass scheduling and native draw-submission timestamps. */
    public IconExportSession(@NotNull Path root, @NotNull IconPack.Metadata environment,
        @NotNull IIconSource<C> catalogue, @NotNull IIconCapture<C> renderer, boolean animated,
        @NotNull LongSupplier clock) {
        this.catalogue = catalogue;
        this.renderer = renderer;
        animations = animated ? new IconAnimationPasses(clock) : null;
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
                status = progress.complete(writer.completedPath(), writer.failureCount(), sourceNote);
                return true;
            }
            if (!writer.ready()) {
                if (discoveryComplete) progress.waitingForWriter();
                return false;
            }
            progress.writerReady();
            IIconSource<C> source = catalogue;
            assert source != null : "Active export requires a catalogue";
            String waiting = source.prepare(this::omit);
            sourceNote = source.note();
            if (waiting != null) {
                status = waiting;
                return false;
            }
            source.verify();
            if (animationPhase) captureAnimations(source);
            else if (discoveryComplete) captureBatch(source);
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
            progress.longestStep = Math.max(progress.longestStep, System.nanoTime() - before);
            if (System.nanoTime() - started >= FRAME_BUDGET) break;
        }
        if (!failures.isEmpty()) {
            writer.write(Collections.emptyList(), failures);
            failures = new ArrayList<>();
        }
        status = progress.discovering(pendingIcons.size(), sourceNote);
        if (source.done() && baseline == null) {
            identities.clear();
            discoveryComplete = true;
            progress.startRendering(pendingIcons.size());
        }
    }

    private boolean discoverNext(@NotNull IIconSource<C> source) {
        if (baseline != null) {
            C candidate = baseline;
            baseline = null;
            progress.baseCandidates++;
            enqueue(candidate);
            return true;
        }
        if (source.done()) return false;
        C candidate = discover(source);
        if (candidate == null) return true;
        progress.exactCandidates++;
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
            progress.processed++;
            progress.longestStep = Math.max(progress.longestStep, System.nanoTime() - before);
            if (System.nanoTime() - started >= FRAME_BUDGET) break;
        }
        renderer.drain(captures);
        progress.captureWorkNanos += System.nanoTime() - started;
        if (!captures.isEmpty() || !failures.isEmpty()) {
            writer.write(captures, failures);
            failures = new ArrayList<>();
        }
        if (pendingIcons.isEmpty()) {
            if (animations != null && !animations.done()) {
                writer.beginAnimations();
                animationPhase = true;
            } else finish(source);
        }
    }

    private void captureAnimations(@NotNull IIconSource<C> source) {
        assert animations != null;
        if (writer.animationsStopped()) {
            animations.close();
            finish(source);
            return;
        }
        if (animations.awaitingProbe()) {
            Set<StableKey> unchanged = writer.sampledStaticKeys();
            if (unchanged == null) return;
            animations.finishProbe(unchanged);
        }
        List<Capture> captures = new ArrayList<>();
        animations.capture(captures, (entry, cause) -> omitAnimation(entry.key, entry.context, cause));
        renderer.drain(captures);
        animations.releaseCompleted();
        if (!captures.isEmpty() || !failures.isEmpty()) {
            writer.write(captures, failures);
            failures = new ArrayList<>();
        }
        if (animations.awaitingProbe() && !sampleProbeSubmitted) {
            writer.finishSampleProbe();
            sampleProbeSubmitted = true;
        }
        if (animations.done()) finish(source);
    }

    private void finish(@NotNull IIconSource<C> source) {
        source.verify();
        closeNative();
        Map<String, Long> counts = source.counts();
        progress.addStatistics(counts);
        renderer.statistics(counts);
        if (animations != null) animations.statistics(counts);
        writer.finish(counts, source.sources());
        catalogue = null;
        finishing = true;
        status = progress.saving(sourceNote);
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
        if (key == null) {
            failures.add(new Failure(candidate.context() + "/identity", "Unsupported resource identity"));
            progress.omitted++;
            return;
        }
        C previous = identities.get(key);
        if (previous != null) {
            if (!previous.sameIdentity(candidate))
                throw new IllegalStateException("Conflicting native identities for key " + key);
            progress.duplicates++;
            return;
        }
        if (pendingIcons.size() >= MAX_QUEUED_ICONS) {
            throw new IllegalStateException("Catalogue exceeds the queued-icon limit of " + MAX_QUEUED_ICONS);
        }
        // Discovery finishes before rendering: these owned stacks cannot be changed by a renderer yet.
        identities.put(key, candidate);
        pendingIcons.addLast(new QueuedIcon<>(key, candidate));
    }

    private void capture(@NotNull QueuedIcon<C> queued, @NotNull List<Capture> captures) {
        try {
            renderer.capture(queued.candidate(), queued.key(), captures);
            progress.captured++;
        } catch (IIconCapture.RenderFailure failure) {
            progress.renderFailures++;
            omit(
                queued.candidate()
                    .context() + "/"
                    + queued.key(),
                failure.getCause());
            return;
        }
        if (animations != null) {
            try {
                IIconAnimation cursor = renderer.animation(queued.candidate(), queued.key());
                if (cursor != null) animations.add(
                    queued.key(),
                    queued.candidate()
                        .context(),
                    cursor);
            } catch (IIconCapture.RenderFailure failure) {
                omitAnimation(
                    queued.key(),
                    queued.candidate()
                        .context(),
                    failure.getCause());
            }
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
            closeNative();
        } catch (Throwable failure) {
            rethrowFatal(failure);
            LOG.warn("Icon renderer cleanup failed", failure);
            status = "Icon export cleanup failed: " + failure;
        }
    }

    @NotNull
    public String status() {
        if (!discoveryComplete || finishing || canceling) return status;
        if (animationPhase && animations != null) return animations.status();
        return progress.rendering(sourceNote);
    }

    @Desugar
    private record QueuedIcon<C extends IIconCandidate<C>>(@NotNull StableKey key, @NotNull C candidate) {}

    private void omit(@NotNull String context, @NotNull Throwable failure) {
        String reason = failureReason(failure);
        failures.add(new Failure(context, reason));
        progress.omitted++;
        LOG.warn("Icon candidate omitted ({}): {}", context, reason);
    }

    private void omitAnimation(@NotNull StableKey key, @NotNull String context, @NotNull Throwable failure) {
        String reason = failureReason(failure);
        failures.add(Failure.animation(key, context + "/animation", reason));
        LOG.warn("Icon animation omitted ({}): {}", context, reason);
    }

    private @NotNull String failureReason(@NotNull Throwable failure) {
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
        return reason;
    }

    private void closeNative() {
        Throwable failure = null;
        try {
            if (animations != null) animations.close();
        } catch (RuntimeException | Error cleanup) {
            failure = cleanup;
        }
        try {
            renderer.close();
        } catch (RuntimeException | Error cleanup) {
            if (failure == null) failure = cleanup;
            else if (cleanup instanceof VirtualMachineError || cleanup instanceof ThreadDeath) {
                if (cleanup != failure) cleanup.addSuppressed(failure);
                failure = cleanup;
            } else if (cleanup != failure) failure.addSuppressed(cleanup);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException cleanup) throw cleanup;
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
