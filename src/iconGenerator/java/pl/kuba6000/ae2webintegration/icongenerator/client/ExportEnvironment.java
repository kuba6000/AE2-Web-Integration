package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;

import org.jetbrains.annotations.NotNull;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.relauncher.ReflectionHelper;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Native-thread provenance snapshot; resource-pack file reads belong to the export worker. */
final class ExportEnvironment {

    private static final int HASH_BUFFER_SIZE = 65536;
    private final Map<String, String> mods;
    private final List<Path> resourcePacks;
    private final String generatedAt;

    private ExportEnvironment(@NotNull Map<String, String> mods, @NotNull List<Path> resourcePacks) {
        this.mods = mods;
        this.resourcePacks = resourcePacks;
        generatedAt = Instant.now()
            .toString();
    }

    static @NotNull ExportEnvironment capture(@NotNull Minecraft minecraft) {
        Map<String, String> mods = new TreeMap<>();
        for (ModContainer mod : Loader.instance()
            .getActiveModList()) {
            mods.put(mod.getModId(), mod.getVersion());
        }
        List<Path> packs = new ArrayList<>();
        ResourcePackRepository repository = minecraft.getResourcePackRepository();
        // Preserve the same low-to-high priority order used by Minecraft.refreshResources.
        for (ResourcePackRepository.Entry entry : repository.getRepositoryEntries()) {
            packs.add(backingFile(entry.getResourcePack()));
        }
        IResourcePack serverPack = repository.func_148530_e();
        if (serverPack != null) packs.add(backingFile(serverPack));
        return new ExportEnvironment(mods, packs);
    }

    @NotNull
    IconPack.Metadata metadata(@NotNull BooleanSupplier canceled) throws IOException {
        List<String> fingerprints = new ArrayList<>();
        for (Path pack : resourcePacks) fingerprints.add(fingerprint(pack, canceled).toString());
        return new IconPack.Metadata(
            "1.7.10",
            "forge",
            "ae2wi-legacy-1.7.10-v1",
            "ae2wi-legacy-1.7.10-base-v1",
            IconGeneratorMod.VERSION,
            generatedAt,
            mods,
            fingerprints);
    }

    private static @NotNull Path backingFile(@NotNull IResourcePack pack) {
        if (!(pack instanceof AbstractResourcePack filePack)) {
            throw new IllegalStateException("Cannot fingerprint resource pack " + pack.getPackName());
        }
        // The native file/folder/server-pack APIs expose no backing-file getter.
        File file = ReflectionHelper
            .getPrivateValue(AbstractResourcePack.class, filePack, "resourcePackFile", "field_110597_b");
        return file.toPath();
    }

    private static @NotNull HashCode fingerprint(@NotNull Path pack, @NotNull BooleanSupplier canceled)
        throws IOException {
        if (!Files.isDirectory(pack)) return hashFile(pack, canceled);
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(pack)) {
            entries = paths.filter(path -> {
                checkCanceled(canceled);
                return Files.isRegularFile(path);
            })
                .sorted(
                    Comparator.comparing(
                        path -> pack.relativize(path)
                            .toString()
                            .replace('\\', '/')))
                .collect(Collectors.toList());
        }
        Hasher hash = Hashing.sha256()
            .newHasher();
        for (Path entry : entries) {
            StableKey.writeText(
                hash,
                pack.relativize(entry)
                    .toString()
                    .replace('\\', '/'));
            hash.putBytes(hashFile(entry, canceled).asBytes());
        }
        return hash.hash();
    }

    private static @NotNull HashCode hashFile(@NotNull Path file, @NotNull BooleanSupplier canceled)
        throws IOException {
        checkCanceled(canceled);
        Hasher hash = Hashing.sha256()
            .newHasher();
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkCanceled(canceled);
                if (Thread.currentThread()
                    .isInterrupted()) throw new InterruptedIOException("Export interrupted");
                hash.putBytes(buffer, 0, read);
            }
        }
        return hash.hash();
    }

    private static void checkCanceled(@NotNull BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) throw new CancellationException("Icon export canceled");
    }

}
