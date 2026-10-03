package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ReportedException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.Loader;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;
import pl.kuba6000.ae2webintegration.icongenerator.client.PackExportWriter.Capture;
import pl.kuba6000.ae2webintegration.icongenerator.client.PackExportWriter.Failure;

final class ExportSession {

    private static final Logger LOG = LogManager.getLogger(IconGeneratorMod.MOD_ID);
    private static final int MAX_FAILURE_MESSAGE_LENGTH = 512;
    private static final int MAX_CAPTURES_PER_FRAME = 1024;
    private static final int MAX_STEPS_PER_FRAME = 4096;
    // Bound retained native candidates to the format-1 mapping ceiling, including candidates that later fail rendering.
    private static final int MAX_QUEUED_ICONS = 250_000;
    private static final int ETA_MIN_SAMPLES = 20;
    private static final int PERCENT_SCALE = 100;
    private static final long ETA_MIN_ELAPSED = TimeUnit.SECONDS.toNanos(2);
    // Leave headroom below Timer.updateTimer's one-second clock reset, which otherwise starves GUI input ticks.
    private static final long FRAME_BUDGET = TimeUnit.MILLISECONDS.toNanos(800);
    private final WorldClient world;
    private final LegacyIconRenderer renderer;
    private final PackExportWriter writer;
    private final boolean neiInstalled;
    private boolean useNei;
    private boolean neiDisabled;
    private boolean neiUnavailable;
    private final Map<StableKey, IconCandidate> identities = new HashMap<>();
    private final Deque<QueuedIcon> pendingIcons = new ArrayDeque<>();
    private List<Failure> failures = new ArrayList<>();
    private @Nullable IconCatalogue catalogue;
    private @Nullable IconCandidate baseline;
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

    ExportSession(@NotNull Minecraft minecraft) {
        world = minecraft.theWorld;
        neiInstalled = Loader.isModLoaded("NotEnoughItems");
        renderer = new LegacyIconRenderer(minecraft);
        writer = new PackExportWriter(
            minecraft.mcDataDir.toPath()
                .resolve("ae2webicons"),
            ExportEnvironment.capture(minecraft));
    }

    boolean tick(@NotNull Minecraft minecraft) {
        try {
            if (!canceling && !isCurrentWorld(minecraft)) cancel("world closed or changed");
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
                    + sourceNote();
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
            IconCatalogue source = readyCatalogue(minecraft);
            if (source == null) return false;
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

    boolean isCurrentWorld(@NotNull Minecraft minecraft) {
        return minecraft.theWorld == world;
    }

    private @Nullable IconCatalogue readyCatalogue(@NotNull Minecraft minecraft) {
        if (catalogue != null) return catalogue;
        NeiCatalogue nei = null;
        try {
            neiDisabled = neiInstalled && NeiCatalogue.disabled();
            useNei = neiInstalled && !neiDisabled;
            nei = useNei ? NeiCatalogue.ready() : null;
        } catch (NoClassDefFoundError failure) {
            neiUnavailable = true;
            useNei = false;
            String warning = "NEI catalogue unavailable (missing class); exporting native items and fluids only";
            LOG.warn(warning, failure);
            if (minecraft.thePlayer != null) minecraft.thePlayer.addChatMessage(new ChatComponentText(warning));
        }
        if (useNei && nei == null) {
            status = "Waiting for NEI to finish loading its full catalogue; /ae2webicons cancel to stop";
            return null;
        }
        catalogue = new IconCatalogue(nei, this::omit);
        return catalogue;
    }

    private void discoverBatch(@NotNull IconCatalogue source) {
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
            + sourceNote();
        if (source.done() && baseline == null) {
            total = pendingIcons.size();
            identities.clear();
            discoveryComplete = true;
            renderStarted = System.nanoTime();
        }
    }

    private boolean discoverNext(@NotNull IconCatalogue source) {
        if (baseline != null) {
            IconCandidate candidate = baseline;
            baseline = null;
            baseCandidates++;
            enqueue(candidate);
            return true;
        }
        if (source.done()) return false;
        IconCandidate candidate = discover(source);
        if (candidate == null) return true;
        exactCandidates++;
        try {
            baseline = candidate.baseline();
        } catch (Throwable failure) {
            omit(candidate.context + "/base", failure);
        }
        enqueue(candidate);
        return true;
    }

    private void captureBatch(@NotNull IconCatalogue source) {
        List<Capture> captures = new ArrayList<>();
        long started = System.nanoTime();
        for (int count = 0; count < MAX_CAPTURES_PER_FRAME && !pendingIcons.isEmpty(); count++) {
            long before = System.nanoTime();
            capture(pendingIcons.removeFirst(), captures);
            processed++;
            longestStep = Math.max(longestStep, System.nanoTime() - before);
            if (System.nanoTime() - started >= FRAME_BUDGET) break;
        }
        captureWorkNanos += System.nanoTime() - started;
        if (!captures.isEmpty() || !failures.isEmpty()) {
            writer.write(captures, failures);
            failures = new ArrayList<>();
        }
        if (pendingIcons.isEmpty()) finish(source);
    }

    private void finish(@NotNull IconCatalogue source) {
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
        String neiSource = useNei ? "nei"
            : neiUnavailable ? "nei-unavailable" : neiDisabled ? "nei-disabled" : "nei-absent";
        writer.finish(counts, Arrays.asList("native", neiSource, "fluids"));
        catalogue = null;
        finishing = true;
        status = "Saving icon pack: " + processed
            + "/"
            + total
            + " icons processed, 0 remaining, "
            + omitted
            + " failures"
            + sourceNote();
    }

    private @NotNull String sourceNote() {
        if (neiUnavailable) return " (NEI unavailable; native catalogue only)";
        if (neiDisabled) return " (NEI disabled; native catalogue only)";
        return "";
    }

    private @Nullable IconCandidate discover(@NotNull IconCatalogue source) {
        try {
            return source.next();
        } catch (NeiCatalogue.Changed failure) {
            throw failure;
        } catch (Throwable failure) {
            omit(source.context(), failure);
            return null;
        }
    }

    private void enqueue(@NotNull IconCandidate candidate) {
        StableKey key;
        try {
            key = candidate.key();
        } catch (Throwable failure) {
            omit(candidate.context + "/identity", failure);
            return;
        }
        IconCandidate previous = identities.get(key);
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
            captures.add(new Capture(queued.key, queued.candidate.render(renderer)));
            captured++;
        } catch (LegacyIconRenderer.RenderFailure failure) {
            renderFailures++;
            omit(queued.candidate.context + "/" + queued.key, failure.failure);
        }
    }

    void cancel(@NotNull String reason) {
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
    String status() {
        if (!discoveryComplete || finishing || canceling) return status;
        if (processed == total) {
            return "Saving icon pack: " + processed
                + "/"
                + total
                + " icons processed, 0 remaining; waiting for image writes"
                + sourceNote();
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
            + sourceNote();
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

    private static final class QueuedIcon {

        final StableKey key;
        final IconCandidate candidate;

        QueuedIcon(@NotNull StableKey key, @NotNull IconCandidate candidate) {
            this.key = key;
            this.candidate = candidate;
        }
    }

    private void omit(@NotNull String context, @NotNull Throwable failure) {
        Throwable cause = failure instanceof ReportedException ? failure.getCause() : failure;
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
