package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.VanillaPackResources;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.resource.EmptyPackResources;
import net.neoforged.neoforge.resource.ResourcePackLoader;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.export.IMetadataSource;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Captures only paths and provenance on the client thread; file hashing belongs to the worker. */
final class ExportEnvironment {

    private ExportEnvironment() {}

    static @NotNull IMetadataSource capture(@NotNull Minecraft minecraft) {
        Map<String, String> mods = new TreeMap<>();
        ModList.get()
            .getMods()
            .forEach(
                mod -> mods.put(
                    mod.getModId(),
                    mod.getVersion()
                        .toString()));
        List<Path> paths = new ArrayList<>();
        // The loaded list already expands NeoForge's hidden mod-resource children in priority order.
        minecraft.getResourceManager()
            .listPacks()
            .forEach(loaded -> {
                PackResources pack = loaded instanceof CompositePackResources composite ? composite.primaryPackResources
                    : loaded;
                if (pack instanceof FilePackResources || pack instanceof PathPackResources) {
                    // Hash the complete backing archive/tree, including every selected version overlay.
                    paths.add(backingPath(pack));
                } else if (!(pack instanceof VanillaPackResources && "vanilla".equals(pack.packId()))
                    && !(pack instanceof EmptyPackResources
                        && ResourcePackLoader.MOD_RESOURCES_ID.equals(pack.packId()))) {
                            throw new IllegalStateException("Cannot fingerprint resource pack " + pack.packId());
                        }
            });
        return IMetadataSource.fromFilesystem(
            new IconPack.Target(
                PlatformConstants.MINECRAFT_VERSION,
                PlatformConstants.LOADER,
                PlatformConstants.ICON_PACK_COMPATIBILITY_VERSION),
            mods.get(IconGeneratorMod.MOD_ID),
            mods,
            paths);
    }

    private static @NotNull Path backingPath(@NotNull PackResources pack) {
        // Narrow generator-only access transformers expose paths unavailable through vanilla's public API.
        if (pack instanceof FilePackResources file) return file.zipFileAccess.file.toPath();
        if (pack instanceof PathPackResources folder) return folder.root;
        throw new IllegalStateException("Cannot fingerprint resource pack " + pack.packId());
    }
}
