package pl.kuba6000.ae2webintegration.icongenerator;

import static net.minecraftforge.network.NetworkConstants.IGNORESERVERONLY;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

/** Standalone entry point: client classes are registered only by side-filtered event subscribers. */
@Mod(IconGeneratorMod.MOD_ID)
public final class IconGeneratorMod {

    public static final String MOD_ID = "ae2webintegration_icon_generator";

    public IconGeneratorMod() {
        ModLoadingContext.get()
            .registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> IGNORESERVERONLY, (remote, isServer) -> true));
    }
}
