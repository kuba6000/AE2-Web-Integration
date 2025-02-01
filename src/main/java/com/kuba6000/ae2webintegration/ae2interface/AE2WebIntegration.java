package com.kuba6000.ae2webintegration.ae2interface;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.kuba6000.ae2webintegration.Tags;

import cpw.mods.fml.common.Mod;

@Mod(
    modid = AE2WebIntegration.MODID,
    version = Tags.VERSION,
    name = "AE2WebIntegration-Interface",
    acceptedMinecraftVersions = "[99.99.99]",
    acceptableRemoteVersions = "*")
public class AE2WebIntegration {

    public static final String MODID = "ae2webintegration-interface";
    public static final Logger LOG = LogManager.getLogger(MODID);

    // THIS MOD CONNECTS AE2 WEB TO THE GAME
}
