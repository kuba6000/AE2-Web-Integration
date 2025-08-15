package com.kuba6000.ae2webintegration.core.interfaces;

import net.minecraft.util.IChatComponent;

import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public interface IAEGrid {

    IAECraftingGrid web$getCraftingGrid();

    IAEPathingGrid web$getPathingGrid();

    IAEStorageGrid web$getStorageGrid();

    IAESecurityGrid web$getSecurityGrid();

    boolean web$isEmpty();

    Object web$getPlayerSource();

    IChatComponent web$getLastFakePlayerChatMessage();

}
