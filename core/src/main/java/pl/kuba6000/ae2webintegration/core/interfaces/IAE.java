package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.Map;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.api.ServerCapability;

public interface IAE {

    /** Immutable native feature declarations; callable off-thread without reading live game state. */
    @NotNull
    Map<ServerCapability, Boolean> web$getCapabilities();

    Iterable<IAEGrid> web$getGrids();

    IStackList web$createStackList();

    IAEGenericStack web$stackOf(IAEKey key, long amount);

}
