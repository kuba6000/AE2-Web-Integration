package com.kuba6000.ae2webintegration.core.interfaces;

import net.minecraft.util.text.ITextComponent;

import com.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

public interface IAEGrid {

    IAECraftingGrid web$getCraftingGrid();

    IAEPathingGrid web$getPathingGrid();

    IAEStorageGrid web$getStorageGrid();

    boolean web$isEmpty();

    Object web$getPlayerSource();

    ITextComponent web$getLastFakePlayerChatMessage();

}
