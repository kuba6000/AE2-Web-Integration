package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import java.awt.AWTError;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPack.Location;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Reads bounded icon crops for Discord thumbnails and full crafting cards. */
final class DiscordCraftingIcon {

    private DiscordCraftingIcon() {}

    static byte @Nullable [] thumbnail(@Nullable IconPack pack, @NotNull ResourceDescriptor description)
        throws IOException {
        try {
            BufferedImage icon = read(pack, description);
            if (icon == null) return null;
            try {
                return png(icon);
            } finally {
                icon.flush();
            }
        } catch (AWTError failure) {
            throw new IOException("Graphics environment unavailable", failure);
        }
    }

    static byte @NotNull [] png(@NotNull BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
        }
        return bytes.toByteArray();
    }

    static @Nullable BufferedImage read(@Nullable IconPack pack, @NotNull ResourceDescriptor description)
        throws IOException {
        if (pack == null) return null;
        Location location = description.itemKey() == null ? null : pack.find(description.itemKey());
        if (location == null && description.baseKey() != null) location = pack.find(description.baseKey());
        if (location == null) return null;
        try (InputStream page = pack.openPage(
            location.page()
                .digest());
            MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(page)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw new IOException("PNG decoder unavailable");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceRegion(
                    new Rectangle(location.x(), location.y(), IconPack.CONTENT_SIZE, IconPack.CONTENT_SIZE));
                return reader.read(0, parameters);
            } finally {
                reader.dispose();
            }
        }
    }
}
