package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations.service;

import appeng.me.cache.SecurityCache;
import com.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = SecurityCache.class, remap = false)
public interface AESecurityGridMixin extends IAESecurityGrid {

    @Override
    default boolean web$isAvailable(){
        return ((SecurityCache) (Object) this).isAvailable();
    }

    @Override
    default long web$getSecurityKey(){
        return ((SecurityCache) (Object) this).getSecurityKey();
    }
}
