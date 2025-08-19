package pl.kuba6000.ae2webintegration.core.interfaces;

import net.minecraft.util.IChatComponent;

import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public interface IAEGrid {

    IAECraftingGrid web$getCraftingGrid();

    IAEPathingGrid web$getPathingGrid();

    IAEStorageGrid web$getStorageGrid();

    IAESecurityGrid web$getSecurityGrid();

    boolean web$isEmpty();

    Object web$getPlayerSource();

    IChatComponent web$getLastFakePlayerChatMessage();

}
