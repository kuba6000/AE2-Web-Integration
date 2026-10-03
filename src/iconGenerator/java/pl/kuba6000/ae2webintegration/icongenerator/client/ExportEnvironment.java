package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraftforge.fml.ModList;

import org.jetbrains.annotations.NotNull;

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
        // ResourceManager lists the loaded packs in low-to-high priority order, including the server pack.
        // Forge's standard mod_resources aggregate has isBuiltin=false; its hidden mod assets are covered by versions.
        minecraft.getResourceManager()
            .listPacks()
            .forEach(pack -> {
                if (pack instanceof FilePackResources || pack instanceof PathPackResources
                    || pack instanceof net.minecraftforge.resource.PathPackResources) {
                    paths.add(backingPath(pack));
                } else if (!(pack.isBuiltin() && pack instanceof net.minecraft.server.packs.VanillaPackResources)
                    && !(pack instanceof net.minecraftforge.resource.DelegatingPackResources
                        && "mod_resources".equals(pack.packId()))) {
                            throw new IllegalStateException("Cannot fingerprint resource pack " + pack.packId());
                        }
            });
        return IMetadataSource.fromFilesystem(
            new IconPack.Target("1.20.1", "forge", "ae2wi-modern-1.20.1-v1", "ae2wi-modern-1.20.1-base-v1"),
            mods.get(IconGeneratorMod.MOD_ID),
            mods,
            paths);
    }

    private static @NotNull Path backingPath(@NotNull PackResources pack) {
        // Narrow generator-only access transformers expose paths unavailable through vanilla's public API.
        if (pack instanceof FilePackResources file) return file.file.toPath();
        if (pack instanceof PathPackResources folder) return folder.root;
        if (pack instanceof net.minecraftforge.resource.PathPackResources folder) return folder.getSource();
        throw new IllegalStateException("Cannot fingerprint resource pack " + pack.packId());
    }
}
