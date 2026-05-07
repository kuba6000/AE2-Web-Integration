package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.UUID;

public interface IAEPlayerData {

    pl.kuba6000.ae2webintegration.core.api.PlayerProfile web$getPlayerProfile(int playerId);

    int web$getPlayerId(UUID id);

}
