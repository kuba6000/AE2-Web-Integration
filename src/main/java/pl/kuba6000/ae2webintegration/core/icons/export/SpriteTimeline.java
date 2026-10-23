package pl.kuba6000.ae2webintegration.core.icons.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Bounded common capture times for native sprites whose frame schedules are already normalized. */
public final class SpriteTimeline {

    private static final int MAX_TICKS = 1200;
    private static final int MAX_FRAMES = 256;
    private final @NotNull List<Frame> frames;
    private final boolean truncated;

    private SpriteTimeline(@NotNull List<Frame> frames, boolean truncated) {
        this.frames = Collections.unmodifiableList(frames);
        this.truncated = truncated;
    }

    public static @NotNull SpriteTimeline create(@NotNull List<Track> tracks) {
        if (tracks.isEmpty()) return new SpriteTimeline(Collections.emptyList(), false);
        long period = 1;
        for (Track track : tracks) {
            long cycle = track.ends[track.ends.length - 1];
            long factor = period / gcd(period, cycle);
            period = cycle > MAX_TICKS || factor > MAX_TICKS / cycle ? MAX_TICKS + 1 : factor * cycle;
            if (period > MAX_TICKS) break;
        }
        int end = (int) Math.min(period, MAX_TICKS);
        List<Frame> frames = new ArrayList<>();
        int tick = 0;
        while (tick < end && frames.size() < MAX_FRAMES) {
            int next = end;
            for (Track track : tracks) next = Math.min(next, track.nextChange(tick, end));
            frames.add(new Frame(tick, (next - tick) * 50));
            tick = next;
        }
        return new SpriteTimeline(frames, period > MAX_TICKS || tick < end);
    }

    public @NotNull List<Frame> frames() {
        return frames;
    }

    public boolean truncated() {
        return truncated;
    }

    private static long gcd(long left, long right) {
        while (right != 0) {
            long remainder = left % right;
            left = right;
            right = remainder;
        }
        return left;
    }

    /** Loaded frame durations in ticks, including repeated frame entries in their original order. */
    public static final class Track {

        private final long[] ends;
        private final boolean interpolated;

        public Track(int @NotNull [] frameDurationsTicks, boolean interpolated) {
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
            if (interpolated) return Math.min(end, tick + 1);
            long cycle = ends[ends.length - 1];
            long position = tick % cycle;
            int index = Arrays.binarySearch(ends, position);
            index = index < 0 ? -index - 1 : index + 1;
            return (int) Math.min(end, tick + ends[index] - position);
        }
    }

    @Desugar
    public record Frame(int tick, int durationMillis) {}
}
