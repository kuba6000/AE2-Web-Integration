package pl.kuba6000.ae2webintegration.core.icons;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Real PNG encoder with valid ancillary padding to exercise the archive byte boundary. */
final class PaddedPng extends ImageWriterSpi implements AutoCloseable {

    private final ImageWriterSpi delegate;
    private final ToIntFunction<BufferedImage> paddingBytes;
    public final AtomicInteger completed = new AtomicInteger();

    public PaddedPng() {
        this(image -> 15 * 1024 * 1024);
    }

    PaddedPng(ToIntFunction<BufferedImage> paddingBytes) {
        super(
            "test",
            "1",
            new String[] { "PNG" },
            new String[] { "png" },
            new String[] { "image/png" },
            ImageWriter.class.getName(),
            new Class<?>[] { ImageOutputStream.class },
            null,
            false,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null);
        this.paddingBytes = paddingBytes;
        ImageWriter standard = ImageIO.getImageWritersByFormatName("PNG")
            .next();
        delegate = standard.getOriginatingProvider();
        standard.dispose();
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> providers = new ArrayList<>();
        Iterator<ImageWriterSpi> iterator = registry.getServiceProviders(ImageWriterSpi.class, true);
        while (iterator.hasNext()) providers.add(iterator.next());
        registry.registerServiceProvider(this);
        for (ImageWriterSpi provider : providers) registry.setOrdering(ImageWriterSpi.class, this, provider);
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier type) {
        return delegate.canEncodeImage(type);
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) throws IOException {
        ImageWriter actual = delegate.createWriterInstance();
        return new ImageWriter(this) {

            @Override
            public void write(IIOMetadata metadata, IIOImage image, ImageWriteParam parameters) throws IOException {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (MemoryCacheImageOutputStream buffer = new MemoryCacheImageOutputStream(bytes)) {
                    actual.setOutput(buffer);
                    actual.write(metadata, image, parameters);
                } finally {
                    actual.dispose();
                }
                byte[] png = bytes.toByteArray();
                ImageOutputStream output = (ImageOutputStream) getOutput();
                output.write(png, 0, png.length - 12);
                // A valid, unknown ancillary chunk expands real encoded bytes without changing pixels.
                byte[] kind = "npAD".getBytes(StandardCharsets.US_ASCII);
                byte[] block = new byte[65536];
                int padding = paddingBytes.applyAsInt((BufferedImage) image.getRenderedImage());
                output.writeInt(padding);
                output.write(kind);
                CRC32 crc = new CRC32();
                crc.update(kind);
                for (int remaining = padding; remaining > 0; remaining -= block.length) {
                    int count = Math.min(remaining, block.length);
                    output.write(block, 0, count);
                    crc.update(block, 0, count);
                }
                output.writeInt((int) crc.getValue());
                output.write(png, png.length - 12, 12);
                completed.incrementAndGet();
            }

            @Override
            public IIOMetadata getDefaultStreamMetadata(ImageWriteParam parameters) {
                return null;
            }

            @Override
            public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier type, ImageWriteParam parameters) {
                return null;
            }

            @Override
            public IIOMetadata convertStreamMetadata(IIOMetadata metadata, ImageWriteParam parameters) {
                return null;
            }

            @Override
            public IIOMetadata convertImageMetadata(IIOMetadata metadata, ImageTypeSpecifier type,
                ImageWriteParam parameters) {
                return null;
            }
        };
    }

    @Override
    public String getDescription(Locale locale) {
        return "Controlled PNG encoder";
    }

    @Override
    public void close() {
        IIORegistry.getDefaultInstance()
            .deregisterServiceProvider(this);
    }

}
