package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.grid.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public interface IAEGrid {

    @NotNull
    Set<DimensionalCoords> web$getControllers();

    /** Server thread only. The live map and its lists must not be retained by HTTP workers. */
    @NotNull
    Map<UUID, List<GridAccessSource>> web$getPermissions();

    /** Thread-safe lookup of adapter-owned permission data; must never read native AE2 state. */
    boolean web$hasAccess(@NotNull UUID playerId);

    /** Presentation only; this person has no special authorization through this label. */
    @Nullable
    PlayerIdentity web$getRepresentativeOwner();

    IAECraftingGrid web$getCraftingGrid();

    IAEPathingGrid web$getPathingGrid();

    IAEStorageGrid web$getStorageGrid();

}
