package pl.kuba6000.ae2webintegration.core.icons;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/** Public ImageIO extension point: real PNG bytes, with controlled completion or faults. */
public final class ControlledPng extends ImageWriterSpi implements AutoCloseable {

    private final ImageWriterSpi delegate;
    private final Encoding encoding;
    public final AtomicInteger completed = new AtomicInteger();

    public ControlledPng(Encoding encoding) {
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
        this.encoding = encoding;
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
                actual.setOutput(getOutput());
                try {
                    encoding.write(
                        (BufferedImage) image.getRenderedImage(),
                        () -> actual.write(metadata, image, parameters));
                    completed.incrementAndGet();
                } finally {
                    actual.dispose();
                }
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

    @FunctionalInterface
    public interface Encode {

        void run() throws IOException;
    }

    @FunctionalInterface
    public interface Encoding {

        void write(BufferedImage image, Encode delegate) throws IOException;
    }

}
