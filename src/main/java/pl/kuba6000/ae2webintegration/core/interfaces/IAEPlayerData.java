package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.UUID;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public interface IAEPlayerData {

    PlayerIdentity web$getPlayerProfile(int playerId);

    int web$getPlayerId(UUID id);

}
