package pl.kuba6000.ae2webintegration.core.icons.export;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class SpriteTimelineTest {

    private static final StableKey KEY = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
    @TempDir
    Path directory;

    @Test
    void combinedSpriteCyclesExportEveryChangeWithItsActualHold() throws Exception {
        SpriteTimeline timeline = SpriteTimeline.create(
            Arrays.asList(
                new SpriteTimeline.Track(new int[] { 2, 1 }, false),
                new SpriteTimeline.Track(new int[] { 1, 3 }, false)));
        assertFalse(timeline.truncated());
        verifyPack(
            timeline,
            new int[] { 0, 1, 2, 3, 4, 5, 6, 8, 9, 11 },
            new int[] { 50, 50, 50, 50, 50, 50, 100, 50, 100, 50 });
    }

    private void verifyPack(SpriteTimeline timeline, int[] expectedTicks, int[] expectedDurations) throws Exception {
        try (IconPack pack = writePack(timeline)) {
            List<IconPack.Frame> frames = pack.animation(KEY)
                .frames();
            assertEquals(expectedTicks.length, frames.size());
            for (int index = 0; index < expectedTicks.length; index++) {
                IconPack.Frame frame = frames.get(index);
                assertEquals(expectedDurations[index], frame.durationMillis());
                try (InputStream input = pack.openPage(
                    pack.animation(KEY)
                        .page()
                        .digest())) {
                    assertEquals(
                        0xff000000 | expectedTicks[index],
                        ImageIO.read(input)
                            .getRGB(frame.x(), frame.y()));
                }
            }
        }
    }

    private IconPack writePack(SpriteTimeline timeline) throws Exception {
        Path output;
        try (IconPackWriter writer = new IconPackWriter(
            directory,
            new IconPack.Metadata(
                "test",
                "loader",
                "1",
                "test",
                "today",
                Collections.emptyMap(),
                Collections.emptyList()),
            64)) {
            writer.add(KEY, pixels(0xffabcdef));
            for (SpriteTimeline.Frame frame : timeline.frames())
                writer.addAnimationFrame(KEY, pixels(0xff000000 | frame.tick()), frame.durationMillis());
            output = writer.finish();
        }
        return IconPack.open(output, "test", "loader", "1");
    }

    @Test
    void interpolationExportsIntermediateTicksBeforeTheAuthoredFrameChanges() throws Exception {
        SpriteTimeline timeline = SpriteTimeline
            .create(Collections.singletonList(new SpriteTimeline.Track(new int[] { 2, 3 }, true)));
        assertFalse(timeline.truncated());
        verifyPack(timeline, new int[] { 0, 1, 2, 3, 4 }, new int[] { 50, 50, 50, 50, 50 });
    }

    @Test
    void frameLimitDistinguishesACompleteCycleFromAnIntentionallyShortenedClip() throws Exception {
        SpriteTimeline complete = SpriteTimeline
            .create(Collections.singletonList(new SpriteTimeline.Track(new int[] { 128, 128 }, true)));
        SpriteTimeline clipped = SpriteTimeline
            .create(Collections.singletonList(new SpriteTimeline.Track(new int[] { 128, 129 }, true)));
        assertFalse(complete.truncated());
        assertTrue(clipped.truncated());
        for (SpriteTimeline timeline : Arrays.asList(complete, clipped)) {
            try (IconPack pack = writePack(timeline)) {
                assertEquals(
                    256,
                    pack.animation(KEY)
                        .frames()
                        .size());
                assertEquals(
                    12800,
                    pack.animation(KEY)
                        .frames()
                        .stream()
                        .mapToInt(IconPack.Frame::durationMillis)
                        .sum());
                assertEquals(
                    50,
                    pack.animation(KEY)
                        .frames()
                        .get(255)
                        .durationMillis());
            }
        }
    }

    @Test
    void veryLongCyclesClipTheFinalHoldWithoutMultiplyingUnboundedPeriods() throws Exception {
        SpriteTimeline timeline = SpriteTimeline.create(
            Arrays.asList(
                new SpriteTimeline.Track(new int[] { 2, Integer.MAX_VALUE }, false),
                new SpriteTimeline.Track(new int[] { 3, Integer.MAX_VALUE }, false),
                new SpriteTimeline.Track(new int[] { 3, Integer.MAX_VALUE - 2 }, false)));
        assertTrue(timeline.truncated());
        verifyPack(timeline, new int[] { 0, 2, 3 }, new int[] { 100, 50, 59850 });
    }

    private static int[] pixels(int color) {
        int[] result = new int[4096];
        Arrays.fill(result, color);
        return result;
    }
}
