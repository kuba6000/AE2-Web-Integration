package pl.kuba6000.ae2webintegration.core.interfaces.service;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public interface IAESecurityGrid {

    boolean web$isAvailable();

    long web$getSecurityKey();

    int web$getOwner();

    PlayerIdentity web$getOwnerProfile();

    boolean web$hasPermissions(int playerId);

}
