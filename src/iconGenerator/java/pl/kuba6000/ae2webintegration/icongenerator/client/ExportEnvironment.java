package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.export.IMetadataSource;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Native-thread provenance snapshot; resource-pack file reads belong to the export worker. */
final class ExportEnvironment {

    private ExportEnvironment() {}

    static @NotNull IMetadataSource capture(@NotNull Minecraft minecraft) {
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
        IResourcePack serverPack = repository.getServerResourcePack();
        if (serverPack != null) packs.add(backingFile(serverPack));
        return IMetadataSource.fromFilesystem(
            new IconPack.Target("1.12.2", "forge", "ae2wi-legacy-1.12.2-v1", "ae2wi-legacy-1.12.2-base-v1"),
            mods.get(IconGeneratorMod.MOD_ID),
            mods,
            packs);
    }

    private static @NotNull Path backingFile(@NotNull IResourcePack pack) {
        if (!(pack instanceof AbstractResourcePack filePack)) {
            throw new IllegalStateException("Cannot fingerprint resource pack " + pack.getPackName());
        }
        // The native file/folder/server-pack APIs expose no backing-file getter.
        File file = ObfuscationReflectionHelper.getPrivateValue(AbstractResourcePack.class, filePack, "field_110597_b");
        return file.toPath();
    }

}
