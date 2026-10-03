package pl.kuba6000.ae2webintegration.core.icons.export;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;

/** Builds metadata once on the export worker, from detached state captured by the platform. */
@FunctionalInterface
public interface IMetadataSource {

    /** File reads belong here; implementations must not access live game state. */
    @NotNull
    IconPack.Metadata create(@NotNull BooleanSupplier canceled) throws IOException;

    /**
     * Takes ownership of detached provenance and paths in resource-pack priority order. Captures the
     * timestamp now; file reads are deferred until create is called on the export worker.
     */
    static @NotNull IMetadataSource fromFilesystem(@NotNull IconPack.Target target, @NotNull String generatorVersion,
        @NotNull Map<String, String> mods, @NotNull List<Path> resourcePacks) {
        String generatedAt = Instant.now()
            .toString();
        return canceled -> {
            List<String> fingerprints = new ArrayList<>();
            for (Path pack : resourcePacks) fingerprints.add(
                ResourcePackFingerprint.hash(pack, canceled)
                    .toString());
            return new IconPack.Metadata(
                target.minecraftVersion,
                target.loader,
                target.identityContract,
                target.basePolicy,
                generatorVersion,
                generatedAt,
                mods,
                fingerprints);
        };
    }
}
