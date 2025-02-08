package com.kuba6000.ae2webintegration.ae2interface;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kuba6000.ae2webintegration.Tags;
import com.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import com.kuba6000.ae2webintegration.core.api.IAEWebInterface;

@Mod(
    modid = AE2WebIntegration.MODID,
    version = Tags.VERSION,
    name = "AE2WebIntegration-Interface",
    acceptedMinecraftVersions = "[1.12.2]",
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

    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {

    }

}
