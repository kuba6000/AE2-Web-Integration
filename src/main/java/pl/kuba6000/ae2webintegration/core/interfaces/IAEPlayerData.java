package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.UUID;

public interface IAEPlayerData {

    Object web$getPlayerProfile(int playerId);

    int web$getPlayerId(UUID id);

    int web$getPlayerId(Object profile);

}
