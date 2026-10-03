package pl.kuba6000.ae2webintegration.core.icons.export;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;

/** Builds metadata once on the export worker, from detached state captured by the platform. */
@FunctionalInterface
public interface IMetadataSource {

    /** File reads belong here; implementations must not access live game state. */
    @NotNull
    IconPack.Metadata create(@NotNull BooleanSupplier canceled) throws IOException;
}
