package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPackWriter;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

class DiscordCraftingCardTest {

    @TempDir
    Path directory;

    @Test
    void usesExactIconBeforeBaseFallbackAndDoesNotRetainAtlasReaders() throws Exception {
        StableKey exact = StableKey.random();
        StableKey base = StableKey.random();
        Path archive;
        IconPack.Metadata metadata = new IconPack.Metadata(
            "test",
            "test",
            "test",
            "test",
            "today",
            Collections.emptyMap());
        int exactColor = 0xff1234ab;
        int baseColor = 0xffab3412;
        try (IconPackWriter writer = new IconPackWriter(directory, metadata)) {
            int[] pixels = new int[4096];
            Arrays.fill(pixels, exactColor);
            writer.add(exact, pixels);
            Arrays.fill(pixels, baseColor);
            writer.add(base, pixels);
            archive = writer.finish();
        }
        DiscordCraftingCard renderer = new DiscordCraftingCard();
        try (IconPack pack = IconPack.open(archive, "test", "test", "test")) {
            BufferedImage exactImage = decode(renderer.render(message(exact, base, false, "Powered Rail"), pack));
            assertTrue(count(exactImage, exactColor) > 4000, "Exact variant pixels must be visible in the card");
            assertEquals(0, count(exactImage, baseColor));
            BufferedImage baseImage = decode(
                renderer.render(message(StableKey.random(), base, false, "Powered Rail"), pack));
            assertTrue(count(baseImage, baseColor) > 4000, "Missing variants must use their base icon");
            BufferedImage missingImage = decode(
                renderer.render(message(null, null, false, "Unsupported resource"), pack));
            assertEquals(0, count(missingImage, exactColor));
            assertEquals(exactImage.getWidth(), missingImage.getWidth());
        }
        // On Windows a live ZipFile lease prevents deletion, so this also detects retained archive readers.
        java.nio.file.Files.delete(archive);
    }

    @Test
    void cardsRemainBoundedWithoutIconsAndLongFormattedNamesDoNotChangeTheirSize() throws Exception {
        DiscordCraftingCard renderer = new DiscordCraftingCard();
        BufferedImage normal = decode(renderer.render(message(null, null, false, "Powered Rail"), null));
        BufferedImage longName = decode(
            renderer.render(
                message(
                    null,
                    null,
                    true,
                    "§a" + String.join("", Collections.nCopies(5000, "Very long resource name "))),
                null));
        assertEquals(normal.getWidth(), longName.getWidth());
        assertEquals(normal.getHeight(), longName.getHeight());
        assertTrue(normal.getWidth() <= 1200 && normal.getHeight() <= 500);
        assertTrue(count(normal, 0xff8bce9e) > 0, "Completed cards must have a success accent");
        assertTrue(count(longName, 0xfff19b9b) > 0, "Cancelled cards must have a cancellation accent");
    }

    @Test
    void requesterNamesAreVisibleInTheirOwnBoundedCardRow() throws Exception {
        DiscordCraftingCard renderer = new DiscordCraftingCard();
        CraftingMessage unknown = message(null, null, false, "Powered Rail");
        CraftingMessage single = new CraftingMessage(
            unknown.gridName(),
            unknown.cpuName(),
            unknown.output(),
            unknown.durationString(),
            false,
            Collections.singletonList(CraftingRequester.player(new PlayerIdentity(new UUID(0, 1), "Alice"))));
        CraftingMessage multiple = new CraftingMessage(
            unknown.gridName(),
            unknown.cpuName(),
            unknown.output(),
            unknown.durationString(),
            false,
            Arrays.asList(
                CraftingRequester.player(new PlayerIdentity(new UUID(0, 1), "Alice")),
                CraftingRequester.player(new PlayerIdentity(new UUID(0, 2), "Bob"))));
        CraftingMessage sources = new CraftingMessage(
            unknown.gridName(),
            unknown.cpuName(),
            unknown.output(),
            unknown.durationString(),
            false,
            Arrays.asList(CraftingRequester.automation(), CraftingRequester.web()));
        BufferedImage sourceNames = decode(renderer.render(sources, null));
        BufferedImage noNames = decode(renderer.render(unknown, null));
        BufferedImage oneName = decode(renderer.render(single, null));
        BufferedImage twoNames = decode(renderer.render(multiple, null));
        assertFalse(
            Arrays.equals(
                noNames.getRGB(0, 300, 960, 84, null, 0, 960),
                sourceNames.getRGB(0, 300, 960, 84, null, 0, 960)));
        assertEquals(noNames.getWidth(), twoNames.getWidth());
        assertEquals(noNames.getHeight(), twoNames.getHeight());
        assertTrue(noNames.getHeight() <= 500);
        int height = noNames.getHeight();
        int width = noNames.getWidth();
        assertArrayEquals(
            noNames.getRGB(0, 0, width, height / 2, null, 0, width),
            twoNames.getRGB(0, 0, width, height / 2, null, 0, width),
            "Requester changes must not overwrite product details");
        assertFalse(
            Arrays.equals(
                noNames.getRGB(0, height / 2, width, height / 2, null, 0, width),
                oneName.getRGB(0, height / 2, width, height / 2, null, 0, width)),
            "Known requester must visibly replace Unknown");
        assertFalse(
            Arrays.equals(
                oneName.getRGB(0, height / 2, width, height / 2, null, 0, width),
                twoNames.getRGB(0, height / 2, width, height / 2, null, 0, width)),
            "Each additional known requester must be shown");
    }

    private static CraftingMessage message(StableKey key, StableKey base, boolean cancelled, String name) {
        return new CraftingMessage(
            "Factory",
            "CPU",
            new ResourceSnapshot(
                new ResourceDescriptor("minecraft", "rail", 0, 0, name, ResourceType.ITEM, key, base),
                4096),
            "16m 42s",
            cancelled,
            java.util.Collections.emptyList());
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "A rendered notification must be a decodable PNG");
        return image;
    }

    private static long count(BufferedImage image, int color) {
        long matches = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) == color) matches++;
        return matches;
    }
}
