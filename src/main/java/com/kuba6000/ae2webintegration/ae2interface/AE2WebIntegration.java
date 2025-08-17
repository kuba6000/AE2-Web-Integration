package com.kuba6000.ae2webintegration.ae2interface;

import static com.kuba6000.ae2webintegration.core.AE2WebIntegration.MODID;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kuba6000.ae2webintegration.ae2interface.implementations.AE;
import com.kuba6000.ae2webintegration.core.api.IAEWebInterface;

@Mod(value = AE2WebIntegration.MODID)
@Mod.EventBusSubscriber(modid = MODID)
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration-interface";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SubscribeEvent
    public void postInit(FMLCommonSetupEvent event) {
        IAEWebInterface.getInstance()
            .initAEInterface(AE.instance);
        // SecurityCache.registerOpPlayer(
        // IAEWebInterface.getInstance()
        // .getAEWebGameProfile());
    }

}
