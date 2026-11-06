package pl.kuba6000.ae2webintegration.icongenerator;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;

@Mod(
    modid = IconGeneratorMod.MOD_ID,
    name = "AE2 Web Integration Icon Generator",
    dependencies = "required-after:appliedenergistics2",
    acceptedMinecraftVersions = "[1.12.2]",
    acceptableRemoteVersions = "*",
    useMetadata = true)
public final class IconGeneratorMod {

    public static final String MOD_ID = "ae2webintegration_icon_generator";

    @SidedProxy(
        clientSide = "pl.kuba6000.ae2webintegration.icongenerator.client.ClientBootstrap",
        serverSide = "pl.kuba6000.ae2webintegration.icongenerator.GeneratorProxy")
    public static GeneratorProxy proxy;

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.initialize();
    }
}
