package pl.kuba6000.ae2webintegration.ae2interface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import appeng.me.cache.SecurityCache;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import pl.kuba6000.ae2webintegration.Tags;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import pl.kuba6000.ae2webintegration.core.api.IAEWebInterface;

@Mod(
    modid = AE2WebIntegration.MODID,
    version = Tags.VERSION,
    name = "AE2WebIntegration-Interface",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*")
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration-interface";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {}

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        IAEWebInterface.getInstance()
            .initAEInterface(AE.instance);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        SecurityCache.registerOpPlayer(
            IAEWebInterface.getInstance()
                .getAEWebGameProfile());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {

    }

}
