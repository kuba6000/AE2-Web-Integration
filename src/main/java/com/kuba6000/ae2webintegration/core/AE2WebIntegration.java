package com.kuba6000.ae2webintegration.core;

import static com.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kuba6000.ae2webintegration.Tags;

@Mod(
    modid = MODID,
    version = Tags.VERSION,
    name = "AE2WebIntegration-Core",
    acceptedMinecraftVersions = "*",
    acceptableRemoteVersions = "*")
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration-core";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "com.kuba6000.ae2webintegration.core.ClientProxy",
        serverSide = "com.kuba6000.ae2webintegration.core.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        proxy.serverStarted(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }

}
