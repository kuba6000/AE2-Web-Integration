package pl.kuba6000.ae2webintegration.core.notification.destination.discord;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.IconPack.Location;
import pl.kuba6000.ae2webintegration.core.notification.message.CraftingMessage;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Fixed dark card, rendered only by the notification worker from detached data. */
final class DiscordCraftingCard {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 320;
    private static final Color PANEL = new Color(0x45434e);
    private static final Color SLOT = new Color(0x393842);
    private static final Color LIGHT_EDGE = new Color(0x766e83);
    private static final Color DARK_EDGE = new Color(0x26232e);
    private static final Color TEXT = new Color(0xeeeaf4);
    private static final Color MUTED = new Color(0xc0bacd);
    private @Nullable Font font;

    byte @NotNull [] render(@NotNull CraftingMessage message, @Nullable IconPack pack) throws IOException {
        Font cardFont = font();
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setColor(new Color(0x161616));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            frame(graphics, 4, 4, WIDTH - 8, HEIGHT - 8, PANEL);
            text(graphics, cardFont, "AE2 Web Integration", 28, 49, 22, TEXT, 580);
            Color status = new Color(message.wasCancelled() ? 0xf19b9b : 0x8bce9e);
            graphics.setColor(new Color(message.wasCancelled() ? 0x59383c : 0x304e3c));
            graphics.fillRect(638, 22, 294, 38);
            text(
                graphics,
                cardFont,
                message.wasCancelled() ? "CRAFTING CANCELLED" : "CRAFTING COMPLETED",
                654,
                48,
                18,
                status,
                270);
            frame(graphics, 24, 82, 912, 126, SLOT);
            BufferedImage icon = icon(
                pack,
                message.output()
                    .description());
            try {
                if (icon != null) graphics.drawImage(icon, 40, 96, 96, 96, null);
                else {
                    frame(graphics, 48, 104, 80, 80, PANEL);
                    text(graphics, cardFont, "?", 76, 158, 32, MUTED, 40);
                }
            } finally {
                if (icon != null) icon.flush();
            }
            text(
                graphics,
                cardFont,
                message.output()
                    .description()
                    .displayName(),
                160,
                132,
                29,
                TEXT,
                752);
            String quantity = NumberFormat.getIntegerInstance(Locale.US)
                .format(
                    message.output()
                        .quantity());
            text(
                graphics,
                cardFont,
                quantity + (message.wasCancelled() ? " requested" : " crafted"),
                160,
                177,
                24,
                status,
                752);
            String[] labels = { "NETWORK", "CRAFTING CPU", "DURATION" };
            String[] values = { message.gridName(), message.cpuName(), message.durationString() };
            for (int index = 0; index < labels.length; index++) {
                int x = 30 + index * 306;
                text(graphics, cardFont, labels[index], x, 246, 14, MUTED, 280);
                text(graphics, cardFont, values[index], x, 283, 22, TEXT, 280);
            }
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
            }
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    private @NotNull Font font() throws IOException {
        if (font != null) return font;
        try (InputStream input = DiscordCraftingCard.class
            .getResourceAsStream("/assets/web/themes/default/fonts/monocraft/Monocraft.ttf")) {
            if (input == null) throw new IOException("Notification font unavailable");
            try {
                font = Font.createFont(Font.TRUETYPE_FONT, input);
            } catch (FontFormatException failure) {
                throw new IOException("Invalid notification font", failure);
            }
            return font;
        }
    }

    private static void frame(@NotNull Graphics2D graphics, int x, int y, int width, int height, @NotNull Color fill) {
        graphics.setColor(DARK_EDGE);
        graphics.fillRect(x, y, width, height);
        graphics.setColor(LIGHT_EDGE);
        graphics.fillRect(x + 2, y + 2, width - 2, height - 2);
        graphics.setColor(fill);
        graphics.fillRect(x + 2, y + 2, width - 4, height - 4);
    }

    private static void text(@NotNull Graphics2D graphics, @NotNull Font font, @NotNull String value, int x, int y,
        int size, @NotNull Color color, int width) {
        // Native names may include formatting codes and control characters; keep text inside its fixed field.
        String label = value.substring(0, Math.min(value.length(), 512))
            .replaceAll("§.", "")
            .replaceAll("[\\p{Cntrl}]", " ");
        graphics.setFont(font.deriveFont((float) size));
        if (graphics.getFontMetrics()
            .stringWidth(label) > width) {
            while (!label.isEmpty() && graphics.getFontMetrics()
                .stringWidth(label + "…") > width) {
                label = label.substring(0, label.offsetByCodePoints(label.length(), -1));
            }
            label += "…";
        }
        graphics.setColor(color);
        graphics.drawString(label, x, y);
    }

    private static @Nullable BufferedImage icon(@Nullable IconPack pack, @NotNull ResourceDescriptor description)
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
