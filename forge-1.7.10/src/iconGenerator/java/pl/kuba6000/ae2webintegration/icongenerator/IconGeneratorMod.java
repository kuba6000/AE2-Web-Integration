package pl.kuba6000.ae2webintegration.icongenerator;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;

@Mod(
    modid = IconGeneratorMod.MOD_ID,
    name = "AE2 Web Integration Icon Generator",
    version = IconGeneratorMod.VERSION,
    dependencies = "required-after:appliedenergistics2;required-after:gtnhlib@[0.11.39,)",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public final class IconGeneratorMod {

    public static final String VERSION = "0.2";
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
