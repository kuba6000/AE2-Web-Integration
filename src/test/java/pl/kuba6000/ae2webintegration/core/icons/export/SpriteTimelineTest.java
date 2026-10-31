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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
                new SpriteTimeline.Track(new int[] { 2, 1 }, new int[] { 0, 1 }, false),
                new SpriteTimeline.Track(new int[] { 1, 3 }, new int[] { 0, 1 }, false)));
        assertFalse(timeline.reduced());
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
            new IconPack.Metadata("test", "loader", "1", "test", "today", Collections.emptyMap()))) {
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
            .create(Collections.singletonList(new SpriteTimeline.Track(new int[] { 2, 3 }, new int[] { 0, 1 }, true)));
        assertFalse(timeline.reduced());
        verifyPack(timeline, new int[] { 0, 1, 2, 3, 4 }, new int[] { 50, 50, 50, 50, 50 });
    }

    @ParameterizedTest
    @ValueSource(ints = { 3300, 6000 })
    void longInterpolationRetainsTheCompleteCycleWithinOneAtlas(int frameTicks) throws Exception {
        SpriteTimeline timeline = SpriteTimeline.create(
            Collections.singletonList(
                new SpriteTimeline.Track(new int[] { frameTicks, frameTicks }, new int[] { 0, 1 }, true)));
        try (IconPack pack = writePack(timeline)) {
            IconPack.Animation animation = pack.animation(KEY);
            assertTrue(
                animation.frames()
                    .size() <= 225);
            assertEquals(
                frameTicks * 100,
                animation.frames()
                    .stream()
                    .mapToInt(IconPack.Frame::durationMillis)
                    .sum());
            try (InputStream input = pack.openPage(
                animation.page()
                    .digest())) {
                java.awt.image.BufferedImage image = ImageIO.read(input);
                IconPack.Frame last = animation.frames()
                    .get(
                        animation.frames()
                            .size() - 1);
                assertTrue((image.getRGB(last.x(), last.y()) & 0xffffff) > frameTicks * 2 - 100);
            }
        }
    }

    @Test
    void repeatedNativeImagesHoldBeforeTheOriginalTransitionDuration() throws Exception {
        SpriteTimeline timeline = SpriteTimeline.create(
            Collections.singletonList(new SpriteTimeline.Track(new int[] { 100, 2, 2 }, new int[] { 7, 7, 9 }, true)));
        verifyPack(timeline, new int[] { 0, 101, 102, 103 }, new int[] { 5050, 50, 50, 50 });
        assertFalse(timeline.reduced());
    }

    @Test
    void exactPageCapacityKeepsEveryShortInterpolationMoment() throws Exception {
        SpriteTimeline timeline = SpriteTimeline.create(
            Collections.singletonList(new SpriteTimeline.Track(new int[] { 112, 113 }, new int[] { 0, 1 }, true)));
        assertFalse(timeline.reduced());
        try (IconPack pack = writePack(timeline)) {
            IconPack.Animation animation = pack.animation(KEY);
            assertEquals(
                225,
                animation.frames()
                    .size());
            for (IconPack.Frame frame : animation.frames()) assertEquals(50, frame.durationMillis());
            IconPack.Frame last = animation.frames()
                .get(224);
            try (InputStream input = pack.openPage(
                animation.page()
                    .digest())) {
                assertEquals(
                    0xff0000e0,
                    ImageIO.read(input)
                        .getRGB(last.x(), last.y()));
            }
        }
    }

    @Test
    void unsupportedCommonCycleNeverProducesAPrefixClip() {
        for (int duration : new int[] { 12001, Integer.MAX_VALUE }) assertThrows(
            IllegalArgumentException.class,
            () -> SpriteTimeline.create(
                Collections.singletonList(new SpriteTimeline.Track(new int[] { duration }, new int[] { 0 }, false))));
        assertThrows(
            IllegalArgumentException.class,
            () -> SpriteTimeline.create(
                Arrays.asList(
                    new SpriteTimeline.Track(new int[] { 100, 1 }, new int[] { 0, 1 }, false),
                    new SpriteTimeline.Track(new int[] { 120, 1 }, new int[] { 0, 1 }, false))));
    }

    @Test
    void reducedTimelineRetainsLongHoldAfterDenseEarlyChanges() throws Exception {
        int[] durations = new int[301];
        int[] indexes = new int[301];
        Arrays.fill(durations, 1);
        durations[300] = 10000;
        for (int index = 0; index < indexes.length; index++) indexes[index] = index;
        SpriteTimeline timeline = SpriteTimeline
            .create(Collections.singletonList(new SpriteTimeline.Track(durations, indexes, false)));
        assertTrue(timeline.reduced());
        try (IconPack pack = writePack(timeline)) {
            IconPack.Animation animation = pack.animation(KEY);
            assertTrue(
                animation.frames()
                    .size() <= 225);
            assertEquals(
                515000,
                animation.frames()
                    .stream()
                    .mapToInt(IconPack.Frame::durationMillis)
                    .sum());
            IconPack.Frame last = animation.frames()
                .get(
                    animation.frames()
                        .size() - 1);
            assertEquals(500000, last.durationMillis());
            try (InputStream input = pack.openPage(
                animation.page()
                    .digest())) {
                assertEquals(
                    0xff00012c,
                    ImageIO.read(input)
                        .getRGB(last.x(), last.y()));
            }
        }
    }

    private static int[] pixels(int color) {
        int[] result = new int[4096];
        Arrays.fill(result, color);
        return result;
    }
}
