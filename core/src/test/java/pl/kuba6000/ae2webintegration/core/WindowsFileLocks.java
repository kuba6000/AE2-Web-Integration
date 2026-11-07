package pl.kuba6000.ae2webintegration.core;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Opens a real Windows handle which prevents deletion while remaining readable and writable. */
public final class WindowsFileLocks {

    private WindowsFileLocks() {}

    public static SeekableByteChannel preventDeletion(Path file) throws IOException, ReflectiveOperationException {
        // The option exists on Java 8 and modern Windows JDKs, but is absent from javac --release 8.
        OpenOption noDelete = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption")
            .getField("NOSHARE_DELETE")
            .get(null);
        return Files.newByteChannel(file, StandardOpenOption.READ, noDelete);
    }
}
