package com.kuba6000.ae2webintegration.core.interfaces;

import com.mojang.authlib.GameProfile;

public interface IAEPlayerData {

    GameProfile web$getPlayerProfile(int playerId);

    int web$getPlayerId(GameProfile id);

}
