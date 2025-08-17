package com.kuba6000.ae2webintegration.core.interfaces;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

public interface IItemList extends Iterable<Object2LongMap.Entry<IAEKey>> {

    long web$findPrecise(IAEKey stack);

}
