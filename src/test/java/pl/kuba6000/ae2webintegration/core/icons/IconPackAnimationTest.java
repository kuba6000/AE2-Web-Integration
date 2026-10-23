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

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconPackAnimationTest {

    private static final StableKey KEY = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
    @TempDir
    Path directory;

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
            List<IconPack.Frame> frames = pack.animation(KEY);
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
            assertEquals(
                0xff445566,
                color(
                    pack,
                    frames.get(0)
                        .location()));
            assertEquals(
                0xff778899,
                color(
                    pack,
                    frames.get(1)
                        .location()));
            assertEquals(
                frames.get(0)
                    .location(),
                frames.get(2)
                    .location());
            assertEquals(
                3,
                pack.pages()
                    .size());
            assertThrows(UnsupportedOperationException.class, frames::clear);
            assertTrue(
                pack.animation(StableKey.random())
                    .isEmpty());
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
                assertTrue(
                    pack.animation(KEY)
                        .isEmpty());
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
            for (int duration : new int[] { -1, 0, 600001, Integer.MAX_VALUE }) {
                assertThrows(IllegalArgumentException.class, () -> writer.addAnimationFrame(KEY, pixels(1), duration));
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
                    .size());
            assertEquals(
                200,
                pack.animation(KEY)
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
            new IconPack.Metadata(
                "1.7.10",
                "forge",
                "1",
                "test",
                "today",
                Collections.emptyMap(),
                Collections.emptyList()),
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
