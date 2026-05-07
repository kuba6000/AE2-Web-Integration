package pl.kuba6000.ae2webintegration.core.interfaces.service;



public interface IAESecurityGrid {

    boolean web$isAvailable();

    long web$getSecurityKey();

    int web$getOwner();

    pl.kuba6000.ae2webintegration.core.api.PlayerProfile web$getOwnerProfile();

    boolean web$hasPermissions(int playerId);

}
