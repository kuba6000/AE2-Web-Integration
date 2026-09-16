package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

public interface IPlayerProfileLookup {

    PlayerIdentity web$getPlayerProfile(int playerId);

}
