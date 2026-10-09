package pl.kuba6000.ae2webintegration.icongenerator;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** Standalone physical-client entry point; native events are registered by side-filtered subscribers. */
@Mod(value = IconGeneratorMod.MOD_ID, dist = Dist.CLIENT)
public final class IconGeneratorMod {

    public static final String MOD_ID = "ae2webintegration_icon_generator";

    public IconGeneratorMod() {}
}
