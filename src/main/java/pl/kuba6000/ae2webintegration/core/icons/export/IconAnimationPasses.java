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
    private static final long SAMPLE_WINDOW = TimeUnit.SECONDS.toNanos(5);
    private static final long GROUP_TARGET = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long PASS_GAP = TimeUnit.MILLISECONDS.toNanos(100);
    private static final int MAX_SAMPLES = 256;
    private final @NotNull LongSupplier clock;
    private final Deque<Entry> sprites = new ArrayDeque<>();
    private final Deque<Entry> sampled = new ArrayDeque<>();
    private final Deque<Entry> group = new ArrayDeque<>();
    private final Deque<Deque<Entry>> queuedGroups = new ArrayDeque<>();
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
    private long passStarted, passWork;
    private long workBudget = GROUP_TARGET;
    private int groupsStarted, groupsFinished;
    private long groupTime;

    IconAnimationPasses(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    void add(@NotNull StableKey key, @NotNull String context, @NotNull IIconAnimation cursor) {
        Entry entry = new Entry(key, context, cursor);
        if (cursor.kind() == IIconAnimation.Kind.SPRITE) sprites.addLast(entry);
        else sampled.addLast(entry);
    }

    boolean done() {
        return !awaitingProbe && sprites.isEmpty() && sampled.isEmpty() && group.isEmpty() && queuedGroups.isEmpty();
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
            } else {
                entry.workNanos = Math.max(1, entry.workNanos / entry.samples);
                entry.samples = 0;
            }
        }
        while (!sampled.isEmpty()) {
            Deque<Entry> nextGroup = new ArrayDeque<>();
            long selectedWork = 0;
            while (!sampled.isEmpty()) {
                Entry entry = sampled.getFirst();
                if (!nextGroup.isEmpty() && selectedWork + entry.workNanos > workBudget) break;
                nextGroup.addLast(sampled.removeFirst());
                selectedWork += entry.workNanos;
            }
            queuedGroups.addLast(nextGroup);
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
            Deque<Entry> queue = sprite ? sprites : probeFinished ? group : sampled;
            Entry entry = queue.getFirst();
            boolean more;
            long drawStarted = clock.getAsLong();
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
            if (!sprite) {
                long elapsed = Math.max(0, clock.getAsLong() - drawStarted);
                passWork += elapsed;
                if (!probeFinished) entry.workNanos += elapsed;
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
        if (probeFinished && group.isEmpty()) {
            if (queuedGroups.isEmpty()) return false;
            group.addAll(queuedGroups.removeFirst());
            groupsStarted++;
            recordedPasses = 0;
            sampling = false;
        }
        if (!sampling) {
            sampling = true;
            sampleStarted = now;
            nextPass = now;
        }
        if (remaining == 0) {
            if (probeFinished && recordedPasses >= 2 && now - sampleStarted >= SAMPLE_WINDOW) {
                finishGroup(now);
                return false;
            }
            if (now - nextPass < 0) return false;
            remaining = (probeFinished ? group : sampled).size();
            passStarted = now;
            passWork = 0;
        }
        return true;
    }

    /** Called only after the renderer drained detached readback for this callback. */
    void releaseCompleted() {
        if (passCompleted) {
            long now = clock.getAsLong();
            long elapsed = now - passStarted;
            // Include yielding and readback in measured sweep time, but exclude deliberate inter-pass gaps.
            if (!probeFinished && elapsed > 0 && passWork > 0)
                workBudget = Math.max(1, (long) (GROUP_TARGET * ((double) passWork / elapsed)));
            if (!probeFinished && passes == 2) awaitingProbe = true;
            else if (probeFinished && (group.isEmpty() || recordedPasses >= 2 && now - sampleStarted >= SAMPLE_WINDOW
                || recordedPasses >= MAX_SAMPLES)) {
                    finishGroup(now);
                }
            nextPass = now + PASS_GAP;
            passCompleted = false;
        }
        closeEntries(completed);
    }

    private void finishGroup(long now) {
        completed.addAll(group);
        group.clear();
        groupsFinished++;
        groupTime += Math.max(0, now - sampleStarted);
    }

    @Override
    public void close() {
        completed.addAll(sprites);
        completed.addAll(sampled);
        completed.addAll(group);
        for (Deque<Entry> queued : queuedGroups) completed.addAll(queued);
        queuedGroups.clear();
        sprites.clear();
        sampled.clear();
        group.clear();
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
        if (!sprites.isEmpty()) return "Rendering sprite animations: " + sprites.size() + " remaining";
        if (!probeFinished) return "Sampling icon animations: probe " + Math.min(2, passes + 1) + "/2";
        if (groupsStarted == 0) return "Preparing custom animation groups";
        long typicalGroupTime = groupsFinished == 0 ? SAMPLE_WINDOW : groupTime / groupsFinished;
        long currentTime = group.isEmpty() ? 0 : Math.max(0, SAMPLE_WINDOW - (clock.getAsLong() - sampleStarted));
        long seconds = (long) Math
            .ceil((currentTime + (double) queuedGroups.size() * typicalGroupTime) / 1_000_000_000d);
        return "Sampling icon animations: group " + groupsStarted
            + "/"
            + (groupsStarted + queuedGroups.size())
            + ", pass "
            + (recordedPasses + 1)
            + "; recording ETA ~"
            + seconds
            + "s";
    }

    void statistics(@NotNull Map<String, Long> counts) {
        counts.put("animationCaptures", (long) steps);
        counts.put("animationPasses", (long) passes);
        counts.put("animationPrunedStatic", (long) pruned);
        counts.put("animationGroups", (long) groupsStarted);
    }

    static final class Entry {

        final @NotNull StableKey key;
        final @NotNull String context;
        final @NotNull IIconAnimation cursor;
        int samples;
        long workNanos;

        Entry(@NotNull StableKey key, @NotNull String context, @NotNull IIconAnimation cursor) {
            this.key = key;
            this.context = context;
            this.cursor = cursor;
        }
    }
}
