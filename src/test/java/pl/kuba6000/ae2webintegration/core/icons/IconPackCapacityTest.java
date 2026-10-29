package pl.kuba6000.ae2webintegration.core.icons;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

class IconPackCapacityTest {

    @TempDir
    Path directory;

    @Test
    void oversizedStaticArchivePublishesReadableRetainedIconsAndReportsOmissions() throws Exception {
        int candidates = 70 * 225;
        Path archive;
        try (PaddedPng encoder = new PaddedPng();
            IconPackWriter writer = new IconPackWriter(
                directory,
                new IconPack.Metadata("1.21.1", "neoforge", "1", "test", "today", Collections.emptyMap()))) {
            for (int index = 0; index < candidates; index++) {
                int[] pixels = new int[64 * 64];
                Arrays.fill(pixels, color(index));
                writer.add(key(index), pixels);
            }
            archive = writer.finish();
            assertTrue(encoder.completed.get() > 0, "Fixture must encode enough real data to exceed one GiB");
        }
        assertTrue(Files.size(archive) <= 1024L * 1024 * 1024);
        try (IconPack pack = IconPack.open(archive, "1.21.1", "neoforge", "1")) {
            Set<String> referenced = new HashSet<>();
            int retained = 0;
            for (int index = 0; index < candidates; index++) {
                IconPack.Location location = pack.find(key(index));
                if (location == null) continue;
                retained++;
                if (referenced.add(
                    location.page()
                        .digest())) {
                    try (InputStream input = pack.openPage(
                        location.page()
                            .digest())) {
                        BufferedImage image = ImageIO.read(input);
                        assertNotNull(image);
                        assertEquals(color(index), image.getRGB(location.x() + 32, location.y() + 32));
                        image.flush();
                    }
                }
            }
            assertTrue(retained > 0 && retained < candidates);
            assertEquals(candidates - retained, pack.failureCount());
            assertEquals(
                referenced.size(),
                pack.pages()
                    .size(),
                "No unused atlas pages may remain");
        }
    }

    @Test
    void oversizedPngOmitsItsIconsAndAnimationsButKeepsHealthyPage() throws Exception {
        Path archive;
        try (PaddedPng encoder = new PaddedPng(image -> containsColor(image, color(0)) ? 17 * 1024 * 1024 : 0);
            IconPackWriter writer = new IconPackWriter(
                directory,
                new IconPack.Metadata("1.21.1", "neoforge", "1", "test", "today", Collections.emptyMap()))) {
            for (int i = 0; i < 226; i++) {
                int[] pixels = new int[4096];
                Arrays.fill(pixels, color(i));
                writer.add(key(i), pixels);
            }
            writer.sealStatic();
            writer.addAnimationFrame(key(0), new int[4096], 100);
            int[] changed = new int[4096];
            Arrays.fill(changed, 0xff998877);
            writer.addAnimationFrame(key(0), changed, 100);
            archive = writer.finish();
        }
        try (IconPack pack = IconPack.open(archive, "1.21.1", "neoforge", "1")) {
            for (int i = 0; i < 225; i++) assertNull(pack.find(key(i)));
            assertNull(pack.animation(key(0)));
            assertNotNull(pack.find(key(225)));
            assertEquals(225, pack.failureCount());
            assertEquals(
                1,
                pack.pages()
                    .size());
        }
    }

    @Test
    void oversizedAnimationPageKeepsItsStaticFallback() throws Exception {
        Path archive;
        try (PaddedPng encoder = new PaddedPng(image -> containsColor(image, 0xff112233) ? 17 * 1024 * 1024 : 0);
            IconPackWriter writer = new IconPackWriter(
                directory,
                new IconPack.Metadata("1.21.1", "neoforge", "1", "test", "today", Collections.emptyMap()))) {
            writer.add(key(0), new int[4096]);
            writer.sealStatic();
            int[] frame = new int[4096];
            Arrays.fill(frame, 0xff112233);
            writer.addAnimationFrame(key(0), frame, 100);
            Arrays.fill(frame, 0xff998877);
            writer.addAnimationFrame(key(0), frame, 100);
            archive = writer.finish();
        }
        try (IconPack pack = IconPack.open(archive, "1.21.1", "neoforge", "1")) {
            assertNotNull(pack.find(key(0)));
            assertNull(pack.animation(key(0)));
            assertEquals(1, pack.failureCount());
            assertEquals(
                1,
                pack.pages()
                    .size());
        }
    }

    private static boolean containsColor(BufferedImage image, int color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == color) return true;
            }
        }
        return false;
    }

    private static int color(int index) {
        return 0xff000000 | index + 1;
    }

    private static StableKey key(int index) {
        return StableKey.create(sink -> sink.putInt(index));
    }
}
