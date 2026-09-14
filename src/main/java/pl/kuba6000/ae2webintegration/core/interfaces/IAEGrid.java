package pl.kuba6000.ae2webintegration.core.interfaces;

import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.api.GridAccessSource;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public interface IAEGrid {

    @NotNull
    Set<DimensionalCoords> web$getControllers();

    @NotNull
    List<GridAccessSource> web$getAccessSources();

    /** Presentation only; this person has no special authorization through this label. */
    @Nullable
    PlayerIdentity web$getRepresentativeOwner();

    IAECraftingGrid web$getCraftingGrid();

    IAEPathingGrid web$getPathingGrid();

    IAEStorageGrid web$getStorageGrid();

}
