package pl.kuba6000.ae2webintegration.core.icons;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kuba6000.ae2webintegration.core.icons.IconPack.Location;
import pl.kuba6000.ae2webintegration.core.icons.IconPack.Metadata;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconPackTest {

    @TempDir
    Path directory;

    private static Metadata metadata(String time) {
        return new Metadata(
            "1.7.10",
            "forge",
            "legacy-v1",
            "base-v1",
            "test",
            time,
            Collections.singletonMap("example", "1"),
            Collections.singletonList("0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void portablePackRoundTripsPixelsAndMetadata() throws Exception {
        StableKey key = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        int[] pixels = new int[4096];
        Arrays.fill(pixels, 0x804080ff);
        Path output;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 512)) {
            writer.add(key, pixels);
            writer.failure(key.toString(), "renderer failed");
            output = writer.finish();
        }
        try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1")) {
            assertEquals("today", pack.metadata().generatedAt);
            assertEquals(1, pack.failureCount());
            assertEquals(
                1,
                pack.pages()
                    .size());
            Location location = pack.find(key);
            assertNotNull(location);
            try (InputStream stream = pack.openPage(location.page.digest)) {
                BufferedImage image = ImageIO.read(stream);
                assertEquals(0x804080ff, image.getRGB(location.x, location.y));
                assertEquals(0x804080ff, image.getRGB(location.x - 1, location.y - 1));
            }
            assertNull(pack.find(StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ")));
        }
    }

    @Test
    void closingArchiveDrainsExistingReadersAndRejectsNewOnes() throws Exception {
        Path output;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            output = writer.finish();
        }
        IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1");
        String digest = pack.pages()
            .get(0).digest;
        try (InputStream first = pack.openPage(digest); InputStream second = pack.openPage(digest)) {
            pack.close();
            assertThrows(IOException.class, () -> pack.openPage(digest));
            assertNotNull(ImageIO.read(first));
            first.close();
            assertNotNull(ImageIO.read(second));
            pack.close();
        }
        // In particular, no live ZIP handle prevents replacement on Windows after the final reader drains.
        Files.move(output, directory.resolve("replacement.ae2wi-icons"));
    }

    @Test
    void duplicatePixelsShareRectangleAndConflictingKeysFail() throws Exception {
        StableKey exact = StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA");
        StableKey base = StableKey.parse("AQEBAQEBAQEBAQEBAQEBAQ");
        int[] pixels = new int[4096];
        Arrays.fill(pixels, 0xff123456);
        Path output;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(exact, pixels);
            writer.add(base, pixels);
            writer.add(exact, pixels);
            pixels[0] = 0;
            assertThrows(IOException.class, () -> writer.add(exact, pixels));
            output = writer.finish();
        }
        try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1")) {
            assertEquals(
                1,
                pack.pages()
                    .size());
            Location exactLocation = pack.find(exact);
            Location baseLocation = pack.find(base);
            assertNotNull(exactLocation);
            assertNotNull(baseLocation);
            assertEquals(exactLocation.page.digest, baseLocation.page.digest);
            assertEquals(exactLocation.x, baseLocation.x);
            assertEquals(exactLocation.y, baseLocation.y);
        }
    }

    @Test
    void cancellationRemovesUnpublishedOutputAndScratch() throws Exception {
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 512)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            assertThrows(CancellationException.class, () -> writer.finish(() -> true));
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void publicationRequiresAtomicJobOwnershipAfterValidation() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            assertThrows(CancellationException.class, () -> writer.finish(() -> false, () -> {
                attempts.incrementAndGet();
                return false;
            }));
        }
        assertEquals(1, attempts.get());
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            Path output = writer.finish(() -> false, () -> {
                attempts.incrementAndGet();
                return true;
            });
            assertTrue(Files.isRegularFile(output));
            writer.close();
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals(1, files.count());
            }
        }
        assertEquals(2, attempts.get());
    }

    @Test
    void exportTimestampFailuresAndStatisticsDoNotChangeContentIdentity() throws Exception {
        String[] ids = new String[2];
        for (int i = 0; i < 2; i++) {
            Path output;
            try (IconPackWriter writer = new IconPackWriter(directory, metadata("time" + i), 64)) {
                writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
                writer.statistics(
                    Collections.singletonMap("discovered", (long) i),
                    Collections.singletonList("registry"));
                for (int j = 0; j < i; j++) writer.failure("example", "failure");
                output = writer.finish();
            }
            try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1")) {
                ids[i] = pack.packId();
            }
        }
        assertEquals(ids[0], ids[1]);
    }

    @Test
    void canonicalEmptyPackMatchesIndependentSha256Vector() throws Exception {
        Path output;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 1024)) {
            output = writer.finish();
        }
        try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1")) {
            // SHA-256 calculated with .NET from the specification's compact, alphabetically ordered projection.
            assertEquals("eb4a860e4027e8dd0ce793020aee411544ca5dd56a70d60b68e72fbb67147077", pack.packId());
            assertThrows(FileNotFoundException.class, () -> pack.openPage("unknown"));
        }
        assertRejectedPack(output, "1.12.2");
    }

    @Test
    void imagesCrossPageBoundariesWithoutRetainingOrChangingPixels() throws Exception {
        Path output;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 512)) {
            for (int i = 0; i < 50; i++) {
                int[] pixels = new int[4096];
                Arrays.fill(pixels, 0xff000000 | i);
                final int identity = i;
                writer.add(StableKey.create(sink -> sink.putInt(identity)), pixels);
            }
            output = writer.finish();
        }
        try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1")) {
            assertEquals(
                2,
                pack.pages()
                    .size());
            for (int i = 0; i < 50; i++) {
                final int identity = i;
                Location location = pack.find(StableKey.create(sink -> sink.putInt(identity)));
                assertNotNull(location);
                try (InputStream stream = pack.openPage(location.page.digest)) {
                    assertEquals(
                        0xff000000 | i,
                        ImageIO.read(stream)
                            .getRGB(location.x + 63, location.y + 63));
                }
            }
        }
    }

    @Test
    void malformedInputAndAbortedWritersNeverPublishPartialPacks() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new IconPackWriter(directory, metadata("today"), 65));
        IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 512);
        try (IconPackWriter ignored = writer) {
            assertThrows(IllegalArgumentException.class, () -> writer.add(StableKey.random(), new int[1]));
            assertThrows(
                IllegalArgumentException.class,
                () -> writer.statistics(Collections.singletonMap("invalid", -1L), Collections.emptyList()));
            writer.add(StableKey.random(), new int[4096]);
        }
        assertThrows(IOException.class, writer::finish);
        writer.close();
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void corruptPagesDuplicateFieldsAndUnexpectedEntriesAreRejected() throws Exception {
        Path original;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            original = writer.finish();
        }
        for (int mutation = 0; mutation < 5; mutation++) {
            Path damaged = directory.resolve("damaged" + mutation + ".zip");
            try (ZipFile source = new ZipFile(original.toFile());
                ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(damaged))) {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    byte[] data;
                    try (InputStream input = source.getInputStream(entry);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                        data = bytes.toByteArray();
                    }
                    if (entry.getName()
                        .equals("manifest.json")) {
                        String json = new String(data, StandardCharsets.UTF_8);
                        if (mutation == 0)
                            json = json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1");
                        if (mutation == 1) json = reseal(json.replace("[0,0,0]", "[0,2147483647,0]"));
                        data = json.getBytes(StandardCharsets.UTF_8);
                        if (mutation == 4) data[json.indexOf("today")] = (byte) 0xff;
                    } else if (mutation == 2) data[data.length - 1] ^= 1;
                    writeEntry(
                        target,
                        entry.getName(),
                        data,
                        entry.getName()
                            .endsWith(".png"));
                }
                if (mutation == 3) {
                    target.putNextEntry(new ZipEntry("../unrelated"));
                    target.write(1);
                    target.closeEntry();
                }
            }
            assertRejectedPack(damaged, "1.7.10");
        }
    }

    private static String reseal(String json) throws Exception {
        JsonObject manifest = new JsonParser().parse(json)
            .getAsJsonObject();
        JsonObject projection = new JsonParser().parse(json)
            .getAsJsonObject();
        projection.remove("packId");
        projection.remove("generatedAt");
        projection.remove("failures");
        projection.remove("statistics");
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(
                new GsonBuilder().disableHtmlEscaping()
                    .create()
                    .toJson(projection)
                    .getBytes(StandardCharsets.UTF_8));
        manifest.addProperty("packId", String.format(Locale.ROOT, "%064x", new BigInteger(1, digest)));
        return manifest.toString();
    }

    private static IOException assertRejectedPack(Path path, String minecraftVersion) {
        return assertThrows(IOException.class, () -> {
            try (IconPack unexpected = IconPack.open(path, minecraftVersion, "forge", "legacy-v1", "base-v1")) {
                fail("Unexpectedly accepted pack " + unexpected.packId());
            }
        });
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] entryBytes(ZipFile zip, String name) throws IOException {
        try (InputStream input = zip.getInputStream(zip.getEntry(name));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    @Test
    void precompressedPagesCannotAddAnotherInflationLayer() throws Exception {
        Path original;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            original = writer.finish();
        }
        Path compressed = directory.resolve("compressed.zip");
        try (ZipFile source = new ZipFile(original.toFile());
            ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(compressed))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement()
                    .getName();
                writeEntry(target, name, entryBytes(source, name), false);
            }
        }
        assertRejectedPack(compressed, "1.7.10");
    }

    @Test
    void pngDimensionsAreValidatedEvenWithCorrectArchiveAndContentDigests() throws Exception {
        Path original;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata("today"), 64)) {
            writer.add(StableKey.parse("AAAAAAAAAAAAAAAAAAAAAA"), new int[4096]);
            original = writer.finish();
        }
        Path damaged = directory.resolve("wrong-dimensions.zip");
        try (ZipFile source = new ZipFile(original.toFile());
            ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(damaged))) {
            JsonObject manifest = new JsonParser()
                .parse(new String(entryBytes(source, "manifest.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonObject page = manifest.getAsJsonArray("pages")
                .get(0)
                .getAsJsonObject();
            byte[] png = entryBytes(
                source,
                page.get("path")
                    .getAsString());
            ByteBuffer.wrap(png)
                .putInt(16, 32);
            CRC32 crc = new CRC32();
            crc.update(png, 12, 17);
            ByteBuffer.wrap(png)
                .putInt(29, (int) crc.getValue());
            String digest = String.format(
                Locale.ROOT,
                "%064x",
                new BigInteger(
                    1,
                    MessageDigest.getInstance("SHA-256")
                        .digest(png)));
            page.addProperty("digest", digest);
            page.addProperty("path", "pages/" + digest + ".png");
            writeEntry(target, "manifest.json", reseal(manifest.toString()).getBytes(StandardCharsets.UTF_8), false);
            writeEntry(
                target,
                page.get("path")
                    .getAsString(),
                png,
                true);
        }
        assertRejectedPack(damaged, "1.7.10");
    }

    @Test
    void manifestInflationIsBoundedEvenWhenZipDeclaredLengthLies() throws Exception {
        Path oversized = directory.resolve("oversized.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(oversized))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            byte[] zeros = new byte[8192];
            for (int i = 0; i <= 8192; i++) zip.write(zeros);
            zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(oversized);
        ByteBuffer fields = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN);
        int modified = 0;
        for (int i = 0; i + 28 <= bytes.length; i++) if (fields.getInt(i) == 0x02014b50) {
            fields.putInt(i + 24, 1);
            modified++;
        }
        assertEquals(1, modified);
        Files.write(oversized, bytes);
        IOException failure = assertThrows(
            IOException.class,
            () -> IconPack.open(oversized, "1.7.10", "forge", "legacy-v1", "base-v1"));
        assertTrue(
            failure.getMessage()
                .contains("size limit"));
    }

    @Test
    void duplicateZipNamesCannotHideConflictingEntries() throws Exception {
        Path archive = directory.resolve("duplicate.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.jsox"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archiveBytes = Files.readAllBytes(archive);
        byte[] alternate = "manifest.jsox".getBytes(StandardCharsets.US_ASCII);
        int renamed = 0;
        for (int i = 0; i <= archiveBytes.length - alternate.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(archiveBytes, i, i + alternate.length), alternate)) {
                archiveBytes[i + alternate.length - 1] = 'n';
                renamed++;
            }
        }
        assertEquals(2, renamed);
        Files.write(archive, archiveBytes);
        assertRejectedPack(archive, "1.7.10");
    }

    @Test
    void malformedResourceFingerprintsAreNotPublished() throws Exception {
        Metadata invalid = new Metadata(
            "1.7.10",
            "forge",
            "legacy-v1",
            "base-v1",
            "test",
            "today",
            Collections.emptyMap(),
            Collections.singletonList("not-a-digest"));
        try (IconPackWriter writer = new IconPackWriter(directory, invalid, 64)) {
            assertThrows(IOException.class, writer::finish);
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void changedPixelsKeysAndResourcePriorityChangeIdentity() throws Exception {
        String first = "0000000000000000000000000000000000000000000000000000000000000000";
        String second = "1111111111111111111111111111111111111111111111111111111111111111";
        Set<String> ids = new HashSet<>();
        for (int variation = 0; variation < 4; variation++) {
            Metadata metadata = new Metadata(
                "1.7.10",
                "forge",
                "legacy-v1",
                "base-v1",
                "test",
                "today",
                Collections.emptyMap(),
                variation == 3 ? Arrays.asList(second, first) : Arrays.asList(first, second));
            int[] pixels = new int[4096];
            pixels[0] = variation == 1 ? 0xffffffff : 0;
            StableKey key = StableKey.parse(variation == 2 ? "AQEBAQEBAQEBAQEBAQEBAQ" : "AAAAAAAAAAAAAAAAAAAAAA");
            Path output;
            try (IconPackWriter writer = new IconPackWriter(directory, metadata, 64)) {
                writer.add(key, pixels);
                output = writer.finish();
            }
            try (IconPack pack = IconPack.open(output, "1.7.10", "forge", "legacy-v1", "base-v1")) {
                ids.add(pack.packId());
            }
        }
        assertEquals(4, ids.size());
    }
}
