package com.kuba6000.ae2webintegration.core.interfaces;

import com.kuba6000.ae2webintegration.core.api.AEApi.AEActionable;

public interface IAEMeInventoryItem {

    long web$extractItems(IAEKey stack, long amount, AEActionable mode, IAEGrid grid);

    long web$getAvailableItem(IAEKey stack, IAEGrid grid);

}
