package pl.kuba6000.ae2webintegration.core.icons.export;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Cooperative client-frame traversal; native cursors and their lifetime stay on this owner. */
final class IconAnimationPasses implements AutoCloseable {

    private static final long FRAME_BUDGET = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long SAMPLE_WINDOW = TimeUnit.SECONDS.toNanos(3);
    private static final long PASS_GAP = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int MAX_SAMPLES = 256;
    private final @NotNull LongSupplier clock;
    private final Deque<Entry> sprites = new ArrayDeque<>();
    private final Deque<Entry> sampled = new ArrayDeque<>();
    private final Deque<Entry> completed = new ArrayDeque<>();
    private boolean sampling;
    private long sampleStarted;
    private long nextPass;
    private int remaining;
    private int passes;
    private boolean passCompleted;
    private int steps;
    private boolean awaitingProbe;
    private boolean probeFinished;
    private int recordedPasses;
    private int pruned;

    IconAnimationPasses(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    void add(@NotNull StableKey key, @NotNull String context, @NotNull IIconAnimation cursor) {
        Entry entry = new Entry(key, context, cursor);
        if (cursor.kind() == IIconAnimation.Kind.SPRITE) sprites.addLast(entry);
        else sampled.addLast(entry);
    }

    boolean done() {
        return !awaitingProbe && sprites.isEmpty() && sampled.isEmpty();
    }

    boolean awaitingProbe() {
        return awaitingProbe;
    }

    void finishProbe(@NotNull Set<StableKey> unchanged) {
        Iterator<Entry> iterator = sampled.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (unchanged.contains(entry.key)) {
                iterator.remove();
                completed.addLast(entry);
                pruned++;
            } else entry.samples = 0;
        }
        probeFinished = true;
        awaitingProbe = false;
        sampling = false;
        closeEntries(completed);
    }

    void capture(@NotNull List<PackExportWriter.Capture> captures, @NotNull BiConsumer<Entry, Throwable> omitted) {
        long started = clock.getAsLong();
        for (int count = 0; count < PackExportWriter.MAX_CAPTURES_PER_BATCH && !done(); count++) {
            boolean sprite = !sprites.isEmpty();
            if (!sprite && !startPass()) break;
            Deque<Entry> queue = sprite ? sprites : sampled;
            Entry entry = queue.getFirst();
            boolean more;
            try {
                more = entry.cursor.capture(captures, clock);
                steps++;
                if (++entry.samples == MAX_SAMPLES && more) {
                    omitted.accept(entry, new IllegalStateException("Animation sample limit exceeded"));
                    more = false;
                }
            } catch (IIconCapture.RenderFailure failure) {
                omitted.accept(entry, failure.getCause());
                more = false;
            }
            if (!sprite || !more) queue.removeFirst();
            if (!more) completed.addLast(entry);
            else if (!sprite) queue.addLast(entry);
            if (!sprite && --remaining == 0) {
                passes++;
                if (probeFinished) recordedPasses++;
                passCompleted = true;
                break;
            }
            if (clock.getAsLong() - started >= FRAME_BUDGET) break;
        }
    }

    private boolean startPass() {
        long now = clock.getAsLong();
        if (!sampling) {
            sampling = true;
            sampleStarted = now;
            nextPass = now;
        }
        if (remaining == 0) {
            if (probeFinished && recordedPasses >= 2 && now - sampleStarted >= SAMPLE_WINDOW) {
                completed.addAll(sampled);
                sampled.clear();
                return false;
            }
            if (now - nextPass < 0) return false;
            remaining = sampled.size();
        }
        return true;
    }

    /** Called only after the renderer drained detached readback for this callback. */
    void releaseCompleted() {
        if (passCompleted) {
            long now = clock.getAsLong();
            if (!probeFinished && passes == 2) awaitingProbe = true;
            else if (probeFinished
                && (recordedPasses >= 2 && now - sampleStarted >= SAMPLE_WINDOW || recordedPasses >= MAX_SAMPLES)) {
                    completed.addAll(sampled);
                    sampled.clear();
                }
            nextPass = now + PASS_GAP;
            passCompleted = false;
        }
        closeEntries(completed);
    }

    @Override
    public void close() {
        completed.addAll(sprites);
        completed.addAll(sampled);
        sprites.clear();
        sampled.clear();
        closeEntries(completed);
    }

    private static void closeEntries(@NotNull Deque<Entry> entries) {
        Throwable problem = null;
        while (!entries.isEmpty()) {
            try {
                entries.removeFirst().cursor.close();
            } catch (RuntimeException | Error failure) {
                if (problem == null) problem = failure;
                else if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
                    if (failure != problem) failure.addSuppressed(problem);
                    problem = failure;
                } else if (problem != failure) problem.addSuppressed(failure);
            }
        }
        if (problem instanceof Error error) throw error;
        if (problem instanceof RuntimeException failure) throw failure;
    }

    @NotNull
    String status() {
        return sprites.isEmpty() ? "Sampling icon animations: pass " + (passes + 1)
            : "Rendering sprite animations: " + sprites.size() + " remaining";
    }

    void statistics(@NotNull Map<String, Long> counts) {
        counts.put("animationCaptures", (long) steps);
        counts.put("animationPasses", (long) passes);
        counts.put("animationPrunedStatic", (long) pruned);
    }

    static final class Entry {

        final @NotNull StableKey key;
        final @NotNull String context;
        final @NotNull IIconAnimation cursor;
        int samples;

        Entry(@NotNull StableKey key, @NotNull String context, @NotNull IIconAnimation cursor) {
            this.key = key;
            this.context = context;
            this.cursor = cursor;
        }
    }
}
