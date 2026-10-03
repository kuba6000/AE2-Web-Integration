package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;

import org.jetbrains.annotations.NotNull;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.relauncher.ReflectionHelper;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.export.IMetadataSource;
import pl.kuba6000.ae2webintegration.core.icons.export.ResourcePackFingerprint;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Native-thread provenance snapshot; resource-pack file reads belong to the export worker. */
final class ExportEnvironment implements IMetadataSource {

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

    @Override
    public @NotNull IconPack.Metadata create(@NotNull BooleanSupplier canceled) throws IOException {
        List<String> fingerprints = new ArrayList<>();
        for (Path pack : resourcePacks) fingerprints.add(
            ResourcePackFingerprint.hash(pack, canceled)
                .toString());
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

}
