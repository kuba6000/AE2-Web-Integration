package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;

public interface IPlayerProfileLookup extends IAEPlayerData {

    PlayerIdentity web$getPlayerProfile(int playerId);

}
