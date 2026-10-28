package pl.kuba6000.ae2webintegration.core.icons;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconPackAnimationTest {

    private static final StableKey KEY = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 64, 512, 1024, 2048 })
    void everyFrameOfMaximumUniqueSequenceLivesOnOnePage(int staticPageSize) throws Exception {
        Path output;
        try (IconPackWriter writer = new IconPackWriter(
            directory,
            new IconPack.Metadata("1.7.10", "forge", "1", "test", "today", Collections.emptyMap()),
            staticPageSize)) {
            writer.add(KEY, pixels(0xff000000));
            for (int index = 0; index < 256; index++)
                writer.addAnimationFrame(KEY, pixels(0xff000000 | index), index + 1);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            List<IconPack.Frame> frames = pack.animation(KEY)
                .frames();
            assertEquals(256, frames.size());
            Set<IconPack.Location> locations = new HashSet<>();
            for (int index = 0; index < frames.size(); index++) {
                locations.add(location(pack.animation(KEY), index));
                assertEquals(
                    index + 1,
                    frames.get(index)
                        .durationMillis());
            }
            assertEquals(256, locations.size());
            assertEquals(
                2048,
                location(pack.animation(KEY), 0).page()
                    .width());
            assertEquals(0xff000000, color(pack, location(pack.animation(KEY), 0)));
            assertEquals(0xff0000ff, color(pack, location(pack.animation(KEY), 255)));
            assertEquals(0xff000000, color(pack, pack.find(KEY)));
        }
    }

    @Test
    void groupsShareAvailableSlotsButNeverSplitAcrossPages() throws Exception {
        StableKey second = StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ");
        StableKey third = StableKey.parse("AgICAgICAgICAgICAgICAg");
        Path output;
        try (IconPackWriter writer = writer()) {
            for (StableKey key : Arrays.asList(KEY, second, third)) writer.add(key, pixels(0xff000000));
            for (int index = 0; index < 220; index++) writer.addAnimationFrame(KEY, pixels(0xff001000 | index), 10);
            writer.addAnimationFrame(second, pixels(0xff001000), 20);
            writer.addAnimationFrame(second, pixels(0xff002000), 30);
            writer.addAnimationFrame(second, pixels(0xff001000), 40);
            for (int index = 0; index < 10; index++) writer.addAnimationFrame(third, pixels(0xff003000 | index), 50);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            IconPack.Animation a = pack.animation(KEY);
            IconPack.Animation b = pack.animation(second);
            IconPack.Animation c = pack.animation(third);
            assertEquals(location(a, 0), location(b, 0));
            assertEquals(location(b, 0), location(b, 2));
            assertEquals(
                20,
                b.frames()
                    .get(0)
                    .durationMillis());
            assertEquals(
                40,
                b.frames()
                    .get(2)
                    .durationMillis());
            assertEquals(location(a, 0).page(), location(b, 1).page());
            assertNotEquals(location(a, 0).page(), location(c, 0).page());
            assertEquals(0xff003009, color(pack, location(c, 9)));
        }
    }

    @Test
    void identicalCompletePagesAreReusedAfterInterveningAnimationPage() throws Exception {
        StableKey second = StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ");
        StableKey third = StableKey.parse("AgICAgICAgICAgICAgICAg");
        Path output;
        try (IconPackWriter writer = writer()) {
            for (StableKey key : Arrays.asList(KEY, second, third)) writer.add(key, pixels(0xff000000));
            for (int index = 0; index < 225; index++) {
                writer.addAnimationFrame(KEY, pixels(0xff001000 | index), 10);
                writer.addAnimationFrame(second, pixels(0xff002000 | index), 20);
                writer.addAnimationFrame(third, pixels(0xff001000 | index), 30);
            }
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            assertEquals(
                3,
                pack.pages()
                    .size());
            for (int index = 0; index < 225; index++)
                assertEquals(location(pack.animation(KEY), index), location(pack.animation(third), index));
        }
    }

    @Test
    void discardedOptionalPixelsNeverReachThePngEncoder() throws Exception {
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> delegate.run())) {
            Path output;
            try (IconPackWriter writer = writer()) {
                writer.add(KEY, pixels(0xff000000));
                for (int index = 0; index < 256; index++) writer.addAnimationFrame(KEY, pixels(0xff001000 | index), 10);
                writer.discardAnimation(KEY);
                output = writer.finish();
            }
            assertEquals(1, encoder.completed.get());
            try (IconPack pack = open(output)) {
                assertEquals(
                    1,
                    pack.pages()
                        .size());
                assertNull(pack.animation(KEY));
            }
        }
    }

    @Test
    void cancellationWhileEncodingFinalAnimationPagesSettlesWorkersAndPublishesNothing() throws Exception {
        AtomicBoolean canceled = new AtomicBoolean();
        try (ControlledPng encoder = new ControlledPng((image, delegate) -> {
            if (image.getWidth() >= 1024) canceled.set(true);
            delegate.run();
        })) {
            try (IconPackWriter writer = writer()) {
                writer.add(KEY, pixels(0xff000000));
                writer.addAnimationFrame(KEY, pixels(0xff001000), 10);
                writer.addAnimationFrame(KEY, pixels(0xff002000), 20);
                assertThrows(CancellationException.class, () -> writer.finish(canceled::get));
            }
            assertTrue(canceled.get());
            assertEquals(2, encoder.completed.get());
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void singleSampleAndDiscardedModesStayStaticWhileMaximumTimedSequenceFinishes() throws Exception {
        StableKey single = StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ");
        Path output;
        try (IconPackWriter writer = writer()) {
            writer.add(KEY, pixels(0xff112233));
            writer.add(single, pixels(0xff112233));
            writer.addAnimationFrame(KEY, pixels(0xff445566), 100);
            assertThrows(IllegalStateException.class, () -> writer.addAnimationSample(KEY, pixels(0xff778899), 0));
            writer.discardAnimation(KEY);
            writer.addAnimationSample(single, pixels(0xff445566), Long.MIN_VALUE);
            for (int index = 0; index < 256; index++)
                writer.addAnimationSample(KEY, pixels(index % 2 == 0 ? 0xff445566 : 0xff778899), index * 1_000_000L);
            IconPackWriter.CapacityException limit = assertThrows(
                IconPackWriter.CapacityException.class,
                () -> writer.addAnimationSample(KEY, pixels(0xff445566), 256_000_000L));
            assertEquals(IconPackWriter.CapacityException.Scope.RESOURCE, limit.scope());
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            assertNull(pack.animation(single));
            assertEquals(
                256,
                pack.animation(KEY)
                    .frames()
                    .size());
            assertEquals(
                1,
                pack.animation(KEY)
                    .frames()
                    .get(255)
                    .durationMillis());
        }
    }

    @Test
    void timedBudgetReservesLastHoldAndRejectsModeOrTimestampChangesWithoutMutatingSequence() throws Exception {
        Path output;
        try (IconPackWriter writer = writer()) {
            writer.add(KEY, pixels(0xff112233));
            writer.addAnimationSample(KEY, pixels(0xff445566), 0);
            assertThrows(IllegalStateException.class, () -> writer.addAnimationFrame(KEY, pixels(0xff778899), 100));
            assertThrows(IllegalArgumentException.class, () -> writer.addAnimationSample(KEY, pixels(0xff778899), -1));
            assertThrows(
                IconPackWriter.CapacityException.class,
                () -> writer.addAnimationSample(KEY, pixels(0xff778899), 400_000_000_000L));
            writer.addAnimationSample(KEY, pixels(0xff778899), 200_000_000_000L);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            assertEquals(
                2,
                pack.animation(KEY)
                    .frames()
                    .size());
            assertEquals(
                200000,
                pack.animation(KEY)
                    .frames()
                    .get(0)
                    .durationMillis());
            assertEquals(
                200000,
                pack.animation(KEY)
                    .frames()
                    .get(1)
                    .durationMillis());
        }
    }

    @Test
    void sealingStaticPagesKeepsOptionalImagesOutOfTheBaselineAtlas() throws Exception {
        Path output;
        try (IconPackWriter writer = new IconPackWriter(
            directory,
            new IconPack.Metadata("1.7.10", "forge", "1", "test", "today", Collections.emptyMap()),
            512)) {
            writer.add(KEY, pixels(0xff112233));
            writer.sealStatic();
            writer.sealStatic();
            assertThrows(IllegalStateException.class, () -> writer.add(KEY, pixels(0xff112233)));
            writer.addAnimationFrame(KEY, pixels(0xff445566), 100);
            writer.addAnimationFrame(KEY, pixels(0xff778899), 200);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            assertEquals(
                2,
                pack.pages()
                    .size());
            assertNotEquals(
                pack.find(KEY)
                    .page(),
                location(pack.animation(KEY), 0).page());
            assertEquals(0xff445566, color(pack, location(pack.animation(KEY), 0)));
            assertEquals(0xff778899, color(pack, location(pack.animation(KEY), 1)));
        }
    }

    @Test
    void sampledTimingUsesSubmissionIntervalsAndLastObservedIntervalAfterMerging() throws Exception {
        Path output;
        try (IconPackWriter writer = writer()) {
            writer.add(KEY, pixels(0xff112233));
            writer.addAnimationSample(KEY, pixels(0xff445566), -900_000_000L);
            writer.addAnimationSample(KEY, pixels(0xff445566), -800_000_000L);
            writer.addAnimationSample(KEY, pixels(0xff778899), -550_000_000L);
            writer.addAnimationSample(KEY, pixels(0xff778899), -350_000_000L);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            List<IconPack.Frame> frames = pack.animation(KEY)
                .frames();
            assertEquals(2, frames.size());
            assertEquals(
                350,
                frames.get(0)
                    .durationMillis());
            assertEquals(
                400,
                frames.get(1)
                    .durationMillis());
            assertEquals(0xff445566, color(pack, location(pack.animation(KEY), 0)));
            assertEquals(0xff778899, color(pack, location(pack.animation(KEY), 1)));
        }
    }

    @Test
    void animationRoundTripsTimingAndRepeatedImagesWithoutReplacingStaticIcon() throws Exception {
        Path output;
        try (IconPackWriter writer = writer()) {
            writer.add(KEY, pixels(0xff112233));
            writer.addAnimationFrame(KEY, pixels(0xff445566), 100);
            writer.addAnimationFrame(KEY, pixels(0xff445566), 150);
            writer.addAnimationFrame(KEY, pixels(0xff778899), 200);
            writer.addAnimationFrame(KEY, pixels(0xff445566), 300);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            assertEquals(0xff112233, color(pack, pack.find(KEY)));
            List<IconPack.Frame> frames = pack.animation(KEY)
                .frames();
            assertEquals(3, frames.size());
            assertEquals(
                250,
                frames.get(0)
                    .durationMillis());
            assertEquals(
                200,
                frames.get(1)
                    .durationMillis());
            assertEquals(
                300,
                frames.get(2)
                    .durationMillis());
            assertEquals(0xff445566, color(pack, location(pack.animation(KEY), 0)));
            assertEquals(0xff778899, color(pack, location(pack.animation(KEY), 1)));
            assertEquals(location(pack.animation(KEY), 0), location(pack.animation(KEY), 2));
            assertEquals(
                2,
                pack.pages()
                    .size());
            assertThrows(UnsupportedOperationException.class, frames::clear);
            assertNull(pack.animation(StableKey.random()));
        }
    }

    @Test
    void unchangedAndDiscardedAnimationsKeepStaticVersionAndDropUnusedPages() throws Exception {
        for (boolean discard : new boolean[] { false, true }) {
            Path output;
            try (IconPackWriter writer = writer()) {
                writer.add(KEY, pixels(0xff112233));
                writer.addAnimationFrame(KEY, pixels(0xff445566), 100);
                writer.addAnimationFrame(KEY, pixels(discard ? 0xff778899 : 0xff445566), 200);
                if (discard) {
                    writer.discardAnimation(KEY);
                    writer.discardAnimation(KEY);
                }
                output = writer.finish();
            }
            try (IconPack pack = open(output)) {
                assertNull(pack.animation(KEY));
                assertEquals(
                    1,
                    pack.pages()
                        .size());
                assertEquals(0xff112233, color(pack, pack.find(KEY)));
            }
            try (ZipFile zip = new ZipFile(output.toFile());
                InputStream manifest = zip.getInputStream(zip.getEntry("manifest.json"))) {
                assertEquals(
                    2,
                    new JsonParser().parse(new InputStreamReader(manifest, StandardCharsets.UTF_8))
                        .getAsJsonObject()
                        .get("formatVersion")
                        .getAsInt());
            }
            Files.delete(output);
        }
    }

    @Test
    void rejectedSamplesLeaveExistingAnimationUsableAndDiscardAllowsRestart() throws Exception {
        Path output;
        try (IconPackWriter writer = writer()) {
            assertThrows(IllegalArgumentException.class, () -> writer.addAnimationFrame(KEY, pixels(1), 100));
            writer.add(KEY, pixels(0xff112233));
            for (int duration : new int[] { -1, 0 }) {
                assertThrows(IllegalArgumentException.class, () -> writer.addAnimationFrame(KEY, pixels(1), duration));
            }
            for (int duration : new int[] { 600001, Integer.MAX_VALUE }) {
                assertThrows(
                    IconPackWriter.CapacityException.class,
                    () -> writer.addAnimationFrame(KEY, pixels(1), duration));
            }
            writer.addAnimationFrame(KEY, pixels(0xff445566), 300000);
            writer.addAnimationFrame(KEY, pixels(0xff778899), 300000);
            assertThrows(IOException.class, () -> writer.addAnimationFrame(KEY, pixels(0xff778899), 1));
            writer.discardAnimation(KEY);
            for (int index = 0; index < 256; index++) writer.addAnimationFrame(KEY, pixels(index % 2), 100);
            assertThrows(IOException.class, () -> writer.addAnimationFrame(KEY, pixels(2), 100));
            // Merging an identical last image uses no additional frame reference.
            writer.addAnimationFrame(KEY, pixels(1), 100);
            output = writer.finish();
        }
        try (IconPack pack = open(output)) {
            assertEquals(
                256,
                pack.animation(KEY)
                    .frames()
                    .size());
            assertEquals(
                200,
                pack.animation(KEY)
                    .frames()
                    .get(255)
                    .durationMillis());
            assertEquals(0xff112233, color(pack, pack.find(KEY)));
        }
    }

    @Test
    void animationOrderAndDurationParticipateInPackIdentity() throws Exception {
        Set<String> identities = new HashSet<>();
        for (int variant = 0; variant < 3; variant++) {
            Path output;
            try (IconPackWriter writer = writer()) {
                writer.add(KEY, pixels(0xff112233));
                // Allocate the same images in the same order for every pack.
                writer.add(StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ"), pixels(0xff445566));
                writer.add(StableKey.parse("AgICAgICAgICAgICAgICAg"), pixels(0xff778899));
                writer.addAnimationFrame(KEY, pixels(variant == 1 ? 0xff778899 : 0xff445566), variant == 2 ? 101 : 100);
                writer.addAnimationFrame(KEY, pixels(variant == 1 ? 0xff445566 : 0xff778899), 200);
                output = writer.finish();
            }
            try (IconPack pack = open(output)) {
                identities.add(pack.packId());
            }
        }
        assertEquals(3, identities.size());
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "[]", "null", "[[0,0,0,100]]", "[[0,0,0,0],[1,0,0,100]]", "[[0,0,0,-1],[1,0,0,100]]",
            "[[0,0,0,1.5],[1,0,0,100]]", "[[0,0,0,2147483648],[1,0,0,100]]", "[[0,0,0,600000],[1,0,0,1]]",
            "[[0,0,0,100],[0,0,0,100]]", "[[99,0,0,100],[1,0,0,100]]", "[[0,1,0,100],[1,0,0,100]]",
            "[[0,0,0,100,1],[1,0,0,100]]" })
    void malformedTimelinesAreRejectedEvenWithRecomputedDigest(String sequence) throws Exception {
        Path valid = animatedPack();
        Path changed = rewrite(
            valid,
            root -> root.getAsJsonObject("animations")
                .add(KEY.toString(), new JsonParser().parse(sequence)),
            true);
        assertRejected(changed);
    }

    @Test
    void malformedAnimationOwnershipVersionAndFrameLimitAreRejected() throws Exception {
        Path valid = animatedPack();
        // A correctly resealed control must open, so malformed cases cannot pass on a broken test digest.
        try (IconPack original = open(valid); IconPack rewritten = open(rewrite(valid, root -> {}, true))) {
            assertEquals(original.packId(), rewritten.packId());
            assertEquals(original.animation(KEY), rewritten.animation(KEY));
        }
        for (int variation = 0; variation < 6; variation++) {
            final int variant = variation;
            assertRejected(rewrite(valid, root -> {
                JsonObject animations = root.getAsJsonObject("animations");
                if (variant == 0) root.remove("animations");
                else if (variant == 1) root.addProperty("formatVersion", 2);
                else if (variant == 2) root.addProperty("formatVersion", 4);
                else if (variant == 3) animations.add(
                    StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ")
                        .toString(),
                    animations.remove(KEY.toString()));
                else if (variant == 4) root.add("animations", new JsonObject());
                else {
                    JsonArray frames = new JsonArray();
                    for (int index = 0; index < 257; index++)
                        frames.add(new JsonParser().parse("[" + (index % 2) + ",0,0,100]"));
                    animations.add(KEY.toString(), frames);
                }
            }, true));
        }
    }

    @Test
    void animationCannotReferenceTwoValidPagesEvenWithRecomputedDigest() throws Exception {
        Path valid = animatedPack();
        Path changed = rewrite(valid, root -> {
            JsonArray sequence = root.getAsJsonObject("animations")
                .getAsJsonArray(KEY.toString());
            JsonArray fallback = new JsonParser().parse(
                root.getAsJsonObject("entries")
                    .get(KEY.toString())
                    .toString())
                .getAsJsonArray();
            fallback.add(new JsonPrimitive(200));
            JsonArray modified = new JsonArray();
            modified.add(sequence.get(0));
            modified.add(fallback);
            root.getAsJsonObject("animations")
                .add(KEY.toString(), modified);
        }, true);
        assertRejected(changed);
    }

    @Test
    void modifiedAnimationCannotReuseOriginalPackDigest() throws Exception {
        Path valid = animatedPack();
        assertRejected(
            rewrite(
                valid,
                root -> root.getAsJsonObject("animations")
                    .add(KEY.toString(), new JsonParser().parse("[[0,0,0,101],[1,0,0,200]]")),
                false));
    }

    @Test
    void cancelingAnimatedPackPublishesNothingAndReleasesScratch() throws Exception {
        try (IconPackWriter writer = writer()) {
            writer.add(KEY, pixels(0xff112233));
            writer.addAnimationFrame(KEY, pixels(0xff445566), 100);
            writer.addAnimationFrame(KEY, pixels(0xff778899), 200);
            assertThrows(CancellationException.class, () -> writer.finish(() -> true));
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    private Path animatedPack() throws Exception {
        try (IconPackWriter writer = writer()) {
            writer.add(KEY, pixels(0xff112233));
            writer.addAnimationFrame(KEY, pixels(0xff445566), 100);
            writer.addAnimationFrame(KEY, pixels(0xff778899), 200);
            return writer.finish();
        }
    }

    private Path rewrite(Path original, Consumer<JsonObject> mutation, boolean reseal) throws Exception {
        Path output = Files.createTempFile(directory, "modified-", ".ae2wi-icons");
        try (ZipFile zip = new ZipFile(original.toFile());
            ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry);
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
                    bytes = buffer.toByteArray();
                }
                if (entry.getName()
                    .equals("manifest.json")) {
                    JsonObject root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                    mutation.accept(root);
                    if (reseal) {
                        JsonObject projection = new JsonParser().parse(root.toString())
                            .getAsJsonObject();
                        for (String excluded : new String[] { "packId", "generatedAt", "failures", "statistics" })
                            projection.remove(excluded);
                        byte[] digest = MessageDigest.getInstance("SHA-256")
                            .digest(
                                new GsonBuilder().disableHtmlEscaping()
                                    .create()
                                    .toJson(canonical(projection))
                                    .getBytes(StandardCharsets.UTF_8));
                        root.addProperty("packId", String.format(Locale.ROOT, "%064x", new BigInteger(1, digest)));
                    }
                    bytes = root.toString()
                        .getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry replacement = new ZipEntry(entry.getName());
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    replacement.setMethod(ZipEntry.STORED);
                    replacement.setSize(bytes.length);
                    replacement.setCrc(crc.getValue());
                }
                target.putNextEntry(replacement);
                target.write(bytes);
                target.closeEntry();
            }
        }
        return output;
    }

    private static Object canonical(JsonElement value) {
        if (value.isJsonObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject()
                .entrySet()) sorted.put(entry.getKey(), canonical(entry.getValue()));
            return sorted;
        }
        if (value.isJsonArray()) {
            List<Object> ordered = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) ordered.add(canonical(item));
            return ordered;
        }
        return value;
    }

    private static void assertRejected(Path path) {
        assertThrows(IOException.class, () -> {
            try (IconPack pack = open(path)) {
                fail("Accepted malformed archive " + pack.packId());
            }
        });
    }

    private IconPackWriter writer() throws Exception {
        return new IconPackWriter(
            directory,
            new IconPack.Metadata("1.7.10", "forge", "1", "test", "today", Collections.emptyMap()),
            64);
    }

    private static IconPack open(Path output) throws Exception {
        return IconPack.open(output, "1.7.10", "forge", "1");
    }

    private static int[] pixels(int color) {
        int[] pixels = new int[4096];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private static IconPack.Location location(IconPack.Animation animation, int index) {
        IconPack.Frame frame = animation.frames()
            .get(index);
        return new IconPack.Location(animation.page(), frame.x(), frame.y());
    }

    private static int color(IconPack pack, IconPack.Location location) throws Exception {
        assertNotNull(location);
        try (InputStream input = pack.openPage(
            location.page()
                .digest())) {
            return ImageIO.read(input)
                .getRGB(location.x(), location.y());
        }
    }
}
