package pl.kuba6000.ae2webintegration.icongenerator.client;

import static pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants.ICON_PACK_COMPATIBILITY_VERSION;
import static pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants.MINECRAFT_VERSION;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import net.minecraftforge.fml.ModList;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.ae2interface.platform.PlatformConstants;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

/** Detached export metadata captured on the client thread. */
final class ExportEnvironment {

    private ExportEnvironment() {}

    static @NotNull IconPack.Metadata capture() {
        Map<String, String> mods = new TreeMap<>();
        ModList.get()
            .getMods()
            .forEach(
                mod -> mods.put(
                    mod.getModId(),
                    mod.getVersion()
                        .toString()));
        return new IconPack.Metadata(
            MINECRAFT_VERSION,
            PlatformConstants.LOADER,
            ICON_PACK_COMPATIBILITY_VERSION,
            mods.get(IconGeneratorMod.MOD_ID),
            Instant.now()
                .toString(),
            mods);
    }
}
