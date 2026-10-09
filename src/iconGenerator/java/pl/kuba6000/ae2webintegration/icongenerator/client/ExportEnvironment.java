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

import org.jetbrains.annotations.NotNull;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.relauncher.ReflectionHelper;
import pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants;
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
        IResourcePack serverPack = repository.func_148530_e();
        if (serverPack != null) packs.add(backingFile(serverPack));
        return IMetadataSource.fromFilesystem(
            new IconPack.Target(
                PlatformConstants.MINECRAFT_VERSION,
                PlatformConstants.LOADER,
                PlatformConstants.ICON_PACK_COMPATIBILITY_VERSION),
            IconGeneratorMod.VERSION,
            mods,
            packs);
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
