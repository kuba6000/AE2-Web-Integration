package com.kuba6000.ae2webintegration.core.interfaces.service;

import com.mojang.authlib.GameProfile;

public interface IAESecurityGrid {

    boolean web$isAvailable();

    long web$getSecurityKey();

    int web$getOwner();

    GameProfile web$getOwnerProfile();

    boolean web$hasPermissions(int playerId);

}
