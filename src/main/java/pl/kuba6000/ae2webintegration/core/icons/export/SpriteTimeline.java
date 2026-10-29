package pl.kuba6000.ae2webintegration.core.icons.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;

/** Bounded common capture times for native sprites whose frame schedules are already normalized. */
public final class SpriteTimeline {

    private static final int MAX_TICKS = 12000;
    private static final int MAX_FRAMES = IconPackWriter.MAX_ANIMATION_IMAGES;
    private final @NotNull List<Frame> frames;
    private final boolean reduced;

    private SpriteTimeline(@NotNull List<Frame> frames, boolean reduced) {
        this.frames = Collections.unmodifiableList(frames);
        this.reduced = reduced;
    }

    public static @NotNull SpriteTimeline create(@NotNull List<Track> tracks) {
        if (tracks.isEmpty()) return new SpriteTimeline(Collections.emptyList(), false);
        long period = 1;
        for (Track track : tracks) {
            long cycle = track.ends[track.ends.length - 1];
            long factor = period / gcd(period, cycle);
            if (cycle > MAX_TICKS || factor > MAX_TICKS / cycle)
                throw new IllegalArgumentException("Complete sprite cycle exceeds 600000 ms");
            period = factor * cycle;
        }
        int end = (int) period;
        List<Frame> frames = new ArrayList<>();
        int tick = 0;
        while (tick < end) {
            int next = end;
            for (Track track : tracks) next = Math.min(next, track.nextChange(tick, end));
            frames.add(new Frame(tick, (next - tick) * 50));
            tick = next;
        }
        if (frames.size() <= MAX_FRAMES) return new SpriteTimeline(frames, false);
        List<Frame> selected = new ArrayList<>();
        // Sampling time rather than event rank preserves long holds amid dense changes.
        int event = 0;
        for (int index = 0; index < MAX_FRAMES; index++) {
            int moment = index * end / MAX_FRAMES;
            while (event + 1 < frames.size() && frames.get(event + 1)
                .tick() <= moment) event++;
            Frame frame = frames.get(event);
            if (selected.isEmpty() || selected.get(selected.size() - 1)
                .tick() != frame.tick()) selected.add(frame);
        }
        for (int index = 0; index < selected.size(); index++) {
            int start = selected.get(index)
                .tick();
            int next = index + 1 == selected.size() ? end
                : selected.get(index + 1)
                    .tick();
            selected.set(index, new Frame(start, (next - start) * 50));
        }
        return new SpriteTimeline(selected, true);
    }

    public @NotNull List<Frame> frames() {
        return frames;
    }

    /** True when intermediate native states were omitted while retaining the complete cycle duration. */
    public boolean reduced() {
        return reduced;
    }

    private static long gcd(long left, long right) {
        while (right != 0) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    /** Loaded durations in ticks and native image indexes, both in the original schedule order. */
    public static final class Track {

        private final long[] ends;
        private final boolean interpolated;
        private final int[] indexes;

        public Track(int @NotNull [] frameDurationsTicks, int @NotNull [] frameIndexes, boolean interpolated) {
            if (frameIndexes.length != frameDurationsTicks.length)
                throw new IllegalArgumentException("Mismatched sprite schedule");
            indexes = frameIndexes.clone();
            if (frameDurationsTicks.length == 0) throw new IllegalArgumentException("Empty sprite timeline");
            ends = new long[frameDurationsTicks.length];
            long total = 0;
            for (int index = 0; index < frameDurationsTicks.length; index++) {
                int duration = frameDurationsTicks[index];
                if (duration <= 0) throw new IllegalArgumentException("Nonpositive sprite frame duration");
                total += duration;
                ends[index] = total;
            }
            this.interpolated = interpolated;
        }

        private int nextChange(int tick, int end) {
            long cycle = ends[ends.length - 1];
            long position = tick % cycle;
            int index = Arrays.binarySearch(ends, position);
            index = index < 0 ? -index - 1 : index + 1;
            long next = tick + ends[index] - position;
            if (interpolated && indexes[index] != indexes[(index + 1) % indexes.length]) return Math.min(end, tick + 1);
            for (int visited = 0; visited < indexes.length; visited++) {
                int following = (index + 1) % indexes.length;
                if (indexes[index] != indexes[following]) return (int) Math.min(end, next);
                index = following;
                if (interpolated && indexes[index] != indexes[(index + 1) % indexes.length])
                    return (int) Math.min(end, next + 1);
                next += ends[index] - (index == 0 ? 0 : ends[index - 1]);
            }
            return end;
        }
    }

    @Desugar
    public record Frame(int tick, int durationMillis) {}
}
