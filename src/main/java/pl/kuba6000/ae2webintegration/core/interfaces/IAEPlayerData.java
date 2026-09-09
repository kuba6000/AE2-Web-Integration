package pl.kuba6000.ae2webintegration.core.interfaces;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public interface IAEPlayerData {

    PlayerIdentity web$getPlayerProfile(int playerId);

    int web$getPlayerId(PlayerIdentity identity);

}
